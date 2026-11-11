//! Present (Java `PresentExtension`): PresentPixmap tells the renderer a new frame is ready
//! and answers with CompleteNotify / IdleNotify. The Java version slept on a scheduler thread;
//! here every wait is a server timer ([`Server::schedule`] + [`Extension::on_timer`]).
//!
//! * FPS limit set: CompleteNotify at once; the pixmap goes idle one display-clock-rounded
//!   interval after the window's previous release (so the game cannot outrun the limit).
//! * No limit: the pixmap is idle when the window presents the next one (the renderer no longer
//!   shows it), with a one second safety timeout; a client that presents the same pixmap over
//!   and over is single-buffered and gets the idle at once.
//! * `wait_fence`: a present whose wait fence is not triggered yet is held (in order, per window)
//!   until it triggers (TriggerFence, or another present's idle), with a one second safety timeout.
//!
//! MSC/UST of CompleteNotify count in real refreshes: the renderer's display clock period, else
//! the RANDR rate in force.

use super::reader;
use crate::proto::consts::err;
use crate::proto::hooks::Hook;
use crate::proto::requests::Extension;
use crate::proto::{bad, ClientId, Fail, Res, Server};
use std::collections::HashMap;

pub const MAJOR: u8 = 153; // Java: (byte)-103
const BAD_IMPLEMENTATION: u8 = 17;
/// A client must not wait for a buffer's idle forever; long on purpose (an earlier release would
/// hand the buffer on screen back to a client that presents slowly).
const PENDING_IDLE_TIMEOUT_NS: i64 = 1_000_000_000;
/// A wait fence nobody triggers must not hold a frame for ever.
const WAIT_FENCE_TIMEOUT_NS: i64 = 1_000_000_000;

const QUERY_VERSION: u8 = 0;
const PRESENT_PIXMAP: u8 = 1;
const SELECT_INPUT: u8 = 3;

const EVT_CONFIGURE_NOTIFY: u16 = 0;
const EVT_COMPLETE_NOTIFY: u16 = 1;
const EVT_IDLE_NOTIFY: u16 = 2;
const PIXMAP_FLAG_WINDOW_DESTROYED: u32 = 1;
const GENERIC_EVENT: u8 = 35;

/// One `xcb_present_select_input` selection.
struct Selection {
    window: u32,
    client: ClientId,
    mask: u32,
}

#[derive(Clone, Copy)]
struct Idle {
    window: u32,
    pixmap: u32,
    serial: u32,
    fence: u32,
}

struct Pending {
    idle: Idle,
    token: u64,
}

/// A PresentPixmap that waits for its `wait_fence`.
struct Waiting {
    client: ClientId,
    idle: Idle,
    wait_fence: u32,
    token: u64,
}

#[derive(Default)]
pub struct Present {
    /// event id -> selection
    events: HashMap<u32, Selection>,
    /// FPS-limited releases waiting for their deadline: timer token -> release.
    limited: HashMap<u64, Idle>,
    /// window -> deadline of its last scheduled release
    deadlines: HashMap<u32, i64>,
    /// window -> the buffer it shows now, whose idle is still owed (no FPS limit)
    pending: HashMap<u32, Pending>,
    /// windows whose client presents one and the same pixmap over and over
    single: HashMap<u32, u32>,
    /// Presents held for their wait fence, oldest first.
    waiting: Vec<Waiting>,
    next_token: u64,
}

fn event_selected(mask: u32, evtype: u16) -> bool {
    mask & (1 << evtype) != 0
}

impl Present {
    fn targets(&self, window: u32, evtype: u16) -> Vec<(u32, ClientId)> {
        let mut t: Vec<(u32, ClientId)> = self
            .events
            .iter()
            .filter(|(_, e)| e.window == window && event_selected(e.mask, evtype))
            .map(|(id, e)| (*id, e.client))
            .collect();
        t.sort_unstable();
        t
    }

    fn send_configure(&self, s: &mut Server, window: u32, flags: u32) {
        let Some(win) = s.windows.get(&window) else { return };
        let (x, y, w, h) = (win.x, win.y, win.width, win.height);
        for (id, client) in self.targets(window, EVT_CONFIGURE_NOTIFY) {
            s.write_event(client, |wr, seq| {
                wr.u8(GENERIC_EVENT).u8(MAJOR).u16(seq).u32(2);
                wr.u16(EVT_CONFIGURE_NOTIFY).u16(0).u32(id).u32(window);
                wr.i16(x).i16(y).u16(w as u16).u16(h as u16).u16(0).u16(0).u16(w as u16).u16(h as u16).u32(flags);
            });
        }
    }

    fn send_idle(&self, s: &mut Server, i: Idle) {
        if i.fence != 0 {
            s.sync.trigger(i.fence);
        }
        for (id, client) in self.targets(i.window, EVT_IDLE_NOTIFY) {
            s.write_event(client, |wr, seq| {
                wr.u8(GENERIC_EVENT).u8(MAJOR).u16(seq).u32(0);
                wr.u16(EVT_IDLE_NOTIFY).u16(0).u32(id).u32(i.window).u32(i.serial).u32(i.pixmap).u32(i.fence);
            });
        }
    }

    fn send_complete(&self, s: &mut Server, window: u32, serial: u32, ust: i64, msc: i64) {
        for (id, client) in self.targets(window, EVT_COMPLETE_NOTIFY) {
            s.write_event(client, |wr, seq| {
                wr.u8(GENERIC_EVENT).u8(MAJOR).u16(seq).u32(2);
                // kind PIXMAP (0), mode COPY (0)
                wr.u16(EVT_COMPLETE_NOTIFY).u8(0).u8(0).u32(id).u32(window).u32(serial);
                wr.u64(ust as u64).u64(msc as u64);
            });
        }
    }

    fn token(&mut self) -> u64 {
        self.next_token += 1;
        self.next_token
    }

    /// The pixmap's release is due one frame interval after the window's previous one.
    fn schedule_idle(&mut self, s: &mut Server, idle: Idle, fps: i32) {
        let frame = s.next_frame_interval(fps);
        let now = s.platform.now_ns();
        let deadline = match self.deadlines.get(&idle.window) {
            Some(prev) if *prev >= now => prev + frame,
            _ => now + frame,
        };
        self.deadlines.insert(idle.window, deadline);
        let token = self.token();
        self.limited.insert(token, idle);
        s.schedule(MAJOR, deadline, token);
    }

    /// Records `pixmap` as the buffer `window` shows and releases the one it replaced.
    fn present_unlimited(&mut self, s: &mut Server, idle: Idle) {
        let window = idle.window;
        let mut immediate = false;
        let mut previous = None;
        let mut single = self.single.get(&window).copied();
        if single.is_some_and(|p| p != idle.pixmap) {
            self.single.remove(&window);
            single = None;
        }
        let reused = self.pending.get(&window).is_some_and(|p| p.idle.pixmap == idle.pixmap);
        if single.is_none() && reused {
            // The client reused the buffer it presented last: it has only one.
            self.single.insert(window, idle.pixmap);
            single = Some(idle.pixmap);
        }
        if single.is_some() {
            self.pending.remove(&window);
            immediate = true;
        } else {
            let token = self.token();
            previous = self.pending.insert(window, Pending { idle, token });
            let deadline = s.platform.now_ns() + PENDING_IDLE_TIMEOUT_NS;
            s.schedule(MAJOR, deadline, token);
        }
        if immediate {
            self.send_idle(s, idle);
        } else if let Some(p) = previous {
            if p.idle.pixmap != idle.pixmap {
                self.send_idle(s, p.idle);
            }
        }
    }

    /// The window stopped showing its buffer (unmapped or destroyed): nothing displaces it any more.
    fn flush_pending(&mut self, s: &mut Server, window: u32) {
        self.single.remove(&window);
        if let Some(p) = self.pending.remove(&window) {
            self.send_idle(s, p.idle);
        }
    }
}

impl Present {
    /// Shows the pixmap: tells the renderer and answers with CompleteNotify (IdleNotify follows
    /// as the mode says). The window or the pixmap may be gone if the present was held.
    fn do_present(&mut self, s: &mut Server, idle: Idle) {
        let (window, pixmap, serial) = (idle.window, idle.pixmap, idle.serial);
        if !s.windows.contains_key(&window) || !s.pixmaps.contains(&pixmap) {
            if idle.fence != 0 {
                s.sync.trigger(idle.fence);
            }
            return;
        }
        let fps = s.platform.fps_limit().min(1000);
        let ust = s.platform.now_ns() / 1000;
        let msc = ust / (s.refresh_interval_ns() / 1000).max(1);
        s.content_changed(pixmap);
        if fps <= 0 {
            self.present_unlimited(s, idle);
            self.send_complete(s, window, serial, ust, msc);
        } else {
            self.send_complete(s, window, serial, ust, msc);
            self.schedule_idle(s, idle, fps);
        }
    }

    /// Runs the held presents whose wait fence is triggered (or gone), in order; a window's
    /// later presents stay behind its earlier one.
    fn release_waiting(&mut self, s: &mut Server) {
        loop {
            let mut blocked: Vec<u32> = Vec::new();
            let mut due = None;
            for (i, w) in self.waiting.iter().enumerate() {
                if blocked.contains(&w.idle.window) {
                    continue;
                }
                if w.wait_fence != 0 && s.sync.fence_state(w.wait_fence) == Some(false) {
                    blocked.push(w.idle.window);
                    continue;
                }
                due = Some(i);
                break;
            }
            let Some(i) = due else { return };
            let w = self.waiting.remove(i);
            self.do_present(s, w.idle);
        }
    }

    /// The safety timeout of a held present: it goes ahead without its fence.
    fn expire_waiting(&mut self, s: &mut Server, token: u64) -> bool {
        let Some(i) = self.waiting.iter().position(|w| w.token == token) else { return false };
        self.waiting[i].wait_fence = 0;
        self.release_waiting(s);
        true
    }

    /// Drops the held presents `gone` matches, triggering their idle fences.
    fn drop_waiting(&mut self, s: &mut Server, gone: impl Fn(&Waiting) -> bool) {
        let (drop, keep): (Vec<Waiting>, Vec<Waiting>) = std::mem::take(&mut self.waiting).into_iter().partition(|w| gone(w));
        self.waiting = keep;
        for w in drop {
            if w.idle.fence != 0 {
                s.sync.trigger(w.idle.fence);
            }
        }
    }
}

impl Extension for Present {
    fn name(&self) -> &str {
        "Present"
    }
    fn major_opcode(&self) -> u8 {
        MAJOR
    }

    fn handle(&mut self, s: &mut Server, c: ClientId, minor: u8, body: &[u8]) -> Res {
        let mut r = reader(s, c, body);
        match minor {
            QUERY_VERSION => {
                s.reply(c, 0, 0, |w| {
                    w.u32(1).u32(2).pad(16);
                });
            }
            PRESENT_PIXMAP => {
                let (window, pixmap, serial) = (r.u32()?, r.u32()?, r.u32()?);
                r.skip(8)?; // valid, update
                let (_x_off, _y_off) = (r.i16()?, r.i16()?);
                r.skip(4)?; // target_crtc
                let wait_fence = r.u32()?;
                let fence = r.u32()?;
                if !s.windows.contains_key(&window) {
                    return Err(bad(err::BAD_WINDOW, window));
                }
                if !s.pixmaps.contains(&pixmap) {
                    return Err(bad(err::BAD_PIXMAP, pixmap));
                }
                let idle = Idle { window, pixmap, serial, fence };
                // Behind an earlier held present of the window: frames keep their order.
                let must_wait = wait_fence != 0 && s.sync.fence_state(wait_fence) == Some(false);
                if must_wait || self.waiting.iter().any(|w| w.idle.window == window) {
                    let token = self.token();
                    let deadline = s.platform.now_ns() + WAIT_FENCE_TIMEOUT_NS;
                    s.schedule(MAJOR, deadline, token);
                    self.waiting.push(Waiting { client: c, idle, wait_fence, token });
                } else {
                    self.do_present(s, idle);
                }
                self.release_waiting(s);
            }
            SELECT_INPUT => {
                let (id, window, mask) = (r.u32()?, r.u32()?, r.u32()?);
                if !s.windows.contains_key(&window) {
                    return Err(bad(err::BAD_WINDOW, window));
                }
                match self.events.get_mut(&id) {
                    Some(e) => {
                        if e.window != window || e.client != c {
                            return Err(bad(err::BAD_MATCH, 0));
                        }
                        if mask != 0 {
                            e.mask = mask;
                        } else {
                            self.events.remove(&id);
                        }
                    }
                    None => {
                        self.events.insert(id, Selection { window, client: c, mask });
                    }
                }
            }
            _ => return Err(Fail::X { code: BAD_IMPLEMENTATION, data: 0 }),
        }
        Ok(())
    }

    fn on_timer(&mut self, s: &mut Server, token: u64) {
        if self.expire_waiting(s, token) {
            return;
        }
        if let Some(idle) = self.limited.remove(&token) {
            self.send_idle(s, idle);
            return;
        }
        // Safety timeout of a pending idle (a stale token matches nothing).
        let window = self.pending.iter().find(|(_, p)| p.token == token).map(|(w, _)| *w);
        if let Some(w) = window {
            if let Some(p) = self.pending.remove(&w) {
                self.send_idle(s, p.idle);
            }
        }
    }

    fn on_hook(&mut self, s: &mut Server, hook: &Hook) {
        match *hook {
            Hook::WindowGeometry { window, .. } => self.send_configure(s, window, 0),
            Hook::WindowUnmapped(w) => self.flush_pending(s, w),
            Hook::FenceTriggered(_) => self.release_waiting(s),
            Hook::PixmapFreed(p) => {
                self.drop_waiting(s, |w| w.idle.pixmap == p);
                // Nobody to tell, but a waiting idle fence must not stay untriggered.
                let gone: Vec<u32> = self.pending.iter().filter(|(_, v)| v.idle.pixmap == p).map(|(w, _)| *w).collect();
                for w in gone {
                    if let Some(v) = self.pending.remove(&w) {
                        if v.idle.fence != 0 {
                            s.sync.trigger(v.idle.fence);
                        }
                    }
                }
                self.single.retain(|_, v| *v != p);
            }
            Hook::WindowFreed(w) => {
                self.drop_waiting(s, |q| q.idle.window == w);
                self.flush_pending(s, w); // before the window's selections go away
                self.deadlines.remove(&w);
                self.send_configure(s, w, PIXMAP_FLAG_WINDOW_DESTROYED);
                self.events.retain(|_, e| e.window != w);
            }
            _ => {}
        }
    }

    fn on_client_disconnected(&mut self, s: &mut Server, client: ClientId) {
        self.drop_waiting(s, |w| w.client == client);
        self.events.retain(|_, e| e.client != client);
    }
}
