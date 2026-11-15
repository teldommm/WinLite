//! Present (Java `PresentExtension`): PresentPixmap tells the renderer a new frame is ready
//! and answers with CompleteNotify / IdleNotify. The Java version slept on a scheduler thread;
//! here every wait is a server timer ([`Server::schedule`] + [`Extension::on_timer`]).
//!
//! When a pixmap goes idle (IdleNotify, and its idle fence triggered):
//!
//! * It must be **off the screen**: the window presented another pixmap since. The one a window
//!   shows is never given back while it shows it (a slow game would draw into the frame on
//!   screen). Exception: a window that has presented only this one pixmap so far gets it back
//!   after [`FIRST_IDLE_TIMEOUT_NS`], so a single-buffered client is not stuck before it is
//!   recognised as one; a client that presents the same pixmap over and over is single-buffered
//!   and gets the idle at once.
//! * And the **renderer is done with it** ([`Hook::BufferReleased`]): no frame in flight samples it
//!   any more. Only while the renderer reports releases ([`Server::release_tracking`]); without
//!   it, being off the screen is enough. A release that does not come within
//!   [`RELEASE_TIMEOUT_NS`] is assumed.
//! * With an FPS limit: and **not before its deadline**, one display-clock-rounded interval after
//!   the window's previous one, so the game cannot outrun the limit. Windows with the
//!   `_WL_SWAPCHAIN` property (the Vulkan wrapper's own swapchain, which paces itself in
//!   vkAcquireNextImageKHR) are not limited here.
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
use std::collections::{HashMap, HashSet};

pub const MAJOR: u8 = 153; // Java: (byte)-103
const BAD_IMPLEMENTATION: u8 = 17;
/// A window that has shown only one pixmap so far gets it back after this long (a client that
/// has one buffer and waits for it before presenting again).
const FIRST_IDLE_TIMEOUT_NS: i64 = 1_000_000_000;
/// A buffer off the screen whose release the renderer does not report goes idle after this long.
const RELEASE_TIMEOUT_NS: i64 = 1_000_000_000;
/// A wait fence nobody triggers must not hold a frame for ever.
const WAIT_FENCE_TIMEOUT_NS: i64 = 1_000_000_000;

/// Set by the Vulkan wrapper's WL swapchain on its window (see `wl_proto.h`).
const WL_SWAPCHAIN_PROPERTY: &str = "_WL_SWAPCHAIN";

const QUERY_VERSION: u8 = 0;
const PRESENT_PIXMAP: u8 = 1;
const SELECT_INPUT: u8 = 3;
const QUERY_CAPABILITIES: u8 = 4;

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

/// The pixmap a window shows now.
struct Shown {
    idle: Idle,
    /// The buffer's native handle (what the renderer reports releases by), 0 if none.
    handle: usize,
    /// FPS limit: not idle before this (platform clock, ns); 0 = no limit.
    deadline: i64,
    /// Timer of the first-present timeout ([`FIRST_IDLE_TIMEOUT_NS`]), 0 if none.
    token: u64,
}

/// A pixmap whose idle is owed: off the screen (or single-buffered), waiting for its release
/// and its deadline.
struct Owed {
    idle: Idle,
    handle: usize,
    released: bool,
    deadline: i64,
    /// When the release is assumed if it has not come (a timer fires then, and at the deadline).
    give_up: i64,
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
    /// window -> deadline of its last scheduled release (FPS limit)
    deadlines: HashMap<u32, i64>,
    /// window -> the pixmap it shows now, whose idle is not due yet
    shown: HashMap<u32, Shown>,
    /// Idles owed, oldest first.
    owed: Vec<Owed>,
    /// windows that have shown more than one pixmap (no first-present timeout for them)
    multi: HashSet<u32>,
    /// windows whose client presents one and the same pixmap over and over
    single: HashMap<u32, u32>,
    /// Presents held for their wait fence, oldest first.
    waiting: Vec<Waiting>,
    /// Releases assumed after RELEASE_TIMEOUT_NS (logged once in a while).
    release_timeouts: u64,
    next_token: u64,
}

fn event_selected(mask: u32, evtype: u16) -> bool {
    mask & (1 << evtype) != 0
}

/// The window belongs to a swapchain that paces itself.
fn self_paced(s: &Server, window: u32) -> bool {
    let id = s.atom_id(WL_SWAPCHAIN_PROPERTY);
    id >= 0 && s.windows.get(&window).is_some_and(|w| w.properties.contains_key(&(id as u32)))
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

    /// FPS limit: the pixmap's idle is due one frame interval after the window's previous one.
    fn limit_deadline(&mut self, s: &mut Server, window: u32, fps: i32) -> i64 {
        let frame = s.next_frame_interval(fps);
        let now = s.platform.now_ns();
        let deadline = match self.deadlines.get(&window) {
            Some(prev) if *prev >= now => prev + frame,
            _ => now + frame,
        };
        self.deadlines.insert(window, deadline);
        deadline
    }

    /// The idle of `idle` is owed from now on; sends it at once when nothing holds it.
    fn owe(&mut self, s: &mut Server, idle: Idle, handle: usize, released: bool, deadline: i64) {
        let now = s.platform.now_ns();
        let released = released || handle == 0 || !s.release_tracking();
        let token = self.token();
        let give_up = now + RELEASE_TIMEOUT_NS;
        if deadline > now {
            s.schedule(MAJOR, deadline, token);
        }
        if !released {
            s.schedule(MAJOR, give_up, token);
        }
        self.owed.push(Owed { idle, handle, released, deadline, give_up });
        self.send_due(s);
    }

    /// Sends every owed idle whose release and deadline are both there, in order.
    fn send_due(&mut self, s: &mut Server) {
        let now = s.platform.now_ns();
        let mut i = 0;
        while i < self.owed.len() {
            let o = &mut self.owed[i];
            if !o.released && now >= o.give_up {
                o.released = true;
                self.release_timeouts += 1;
                if self.release_timeouts == 1 || self.release_timeouts % 600 == 0 {
                    crate::log::warn(&format!(
                        "Present: the renderer did not release pixmap {:#x} in time ({} so far)",
                        o.idle.pixmap, self.release_timeouts
                    ));
                }
            }
            if o.released && now >= o.deadline {
                let o = self.owed.remove(i);
                self.send_idle(s, o.idle);
            } else {
                i += 1;
            }
        }
    }

    /// Records `idle` as what its window shows; the pixmap it replaced is owed its idle.
    fn show(&mut self, s: &mut Server, idle: Idle, deadline: i64) {
        let window = idle.window;
        let handle = s.drawables.get(&idle.pixmap).map(|d| d.store.native_handle() as usize).unwrap_or(0);

        let mut single = self.single.get(&window).copied();
        if single.is_some_and(|p| p != idle.pixmap) {
            self.single.remove(&window);
            single = None;
        }
        let reused = self.shown.get(&window).is_some_and(|p| p.idle.pixmap == idle.pixmap);
        if single.is_none() && reused {
            // The client reused the buffer it presented last: it has only one.
            self.single.insert(window, idle.pixmap);
            single = Some(idle.pixmap);
        }
        if single.is_some() {
            // Nothing will ever replace it: the client draws over the frame on screen anyway.
            self.shown.remove(&window);
            self.owe(s, idle, handle, true, deadline);
            return;
        }

        let token = if self.multi.contains(&window) {
            0
        } else {
            let t = self.token();
            let at = s.platform.now_ns() + FIRST_IDLE_TIMEOUT_NS;
            s.schedule(MAJOR, at, t);
            t
        };
        let previous = self.shown.insert(window, Shown { idle, handle, deadline, token });
        if let Some(p) = previous {
            if p.idle.pixmap != idle.pixmap {
                self.multi.insert(window);
                self.owe(s, p.idle, p.handle, false, p.deadline);
            }
        }
    }

    /// The window stopped showing anything (unmapped or destroyed): everything it owes goes now.
    fn flush_window(&mut self, s: &mut Server, window: u32) {
        self.single.remove(&window);
        let (gone, keep): (Vec<Owed>, Vec<Owed>) = std::mem::take(&mut self.owed).into_iter().partition(|o| o.idle.window == window);
        self.owed = keep;
        for o in gone {
            self.send_idle(s, o.idle);
        }
        if let Some(p) = self.shown.remove(&window) {
            self.send_idle(s, p.idle);
        }
    }

    /// The first-present timeout of a window's only pixmap so far.
    fn first_idle_timeout(&mut self, s: &mut Server, token: u64) -> bool {
        let Some(window) = self.shown.iter().find(|(_, p)| p.token == token && token != 0).map(|(w, _)| *w) else {
            return false;
        };
        if self.multi.contains(&window) {
            return true;
        }
        // Still on screen and the renderer keeps it: this is the single-buffer escape, the
        // release is not waited for.
        if let Some(p) = self.shown.remove(&window) {
            self.owe(s, p.idle, p.handle, true, p.deadline);
        }
        true
    }
}

impl Present {
    /// Shows the pixmap: tells the renderer and answers with CompleteNotify (IdleNotify follows
    /// as described at the top). The window or the pixmap may be gone if the present was held.
    fn do_present(&mut self, s: &mut Server, idle: Idle) {
        let (window, pixmap, serial) = (idle.window, idle.pixmap, idle.serial);
        if !s.windows.contains_key(&window) || !s.pixmaps.contains(&pixmap) {
            if idle.fence != 0 {
                s.sync.trigger(idle.fence);
            }
            return;
        }
        let fps = if self_paced(s, window) { 0 } else { s.platform.fps_limit().min(1000) };
        let ust = s.platform.now_ns() / 1000;
        let msc = ust / (s.refresh_interval_ns() / 1000).max(1);
        s.content_changed(pixmap);
        let deadline = if fps > 0 { self.limit_deadline(s, window, fps) } else { 0 };
        // The replaced pixmap's idle (if already due) goes out before this one's completion.
        self.show(s, idle, deadline);
        self.send_complete(s, window, serial, ust, msc);
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
            // Vulkan's X11 WSI asks for the capabilities of the window on every swapchain it
            // creates. None are offered: no async tearing, no explicit-sync fences.
            QUERY_CAPABILITIES => {
                s.reply(c, 0, 0, |w| {
                    w.u32(0).pad(20);
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
        if !self.first_idle_timeout(s, token) {
            // An owed idle's deadline or release timeout (a stale token matches nothing).
            self.send_due(s);
        }
        // `send_idle` triggers the idle fence through `SyncState::trigger`, which runs no hook:
        // a present held for that fence goes ahead now, not at the next PresentPixmap.
        self.release_waiting(s);
    }

    fn on_hook(&mut self, s: &mut Server, hook: &Hook) {
        match *hook {
            Hook::WindowGeometry { window, .. } => self.send_configure(s, window, 0),
            Hook::WindowUnmapped(w) => {
                self.flush_window(s, w);
                self.release_waiting(s); // the flushed idles may trigger a held present's fence
            }
            Hook::FenceTriggered(_) => self.release_waiting(s),
            Hook::BufferReleased(handle) => {
                let mut any = false;
                for o in self.owed.iter_mut() {
                    if !o.released && handle.map_or(true, |h| h == o.handle) {
                        o.released = true;
                        any = true;
                    }
                }
                if any {
                    self.send_due(s);
                    self.release_waiting(s);
                }
            }
            Hook::PixmapFreed(p) => {
                self.drop_waiting(s, |w| w.idle.pixmap == p);
                // Nobody to tell, but a waiting idle fence must not stay untriggered.
                let mut fences: Vec<u32> = Vec::new();
                self.owed.retain(|o| {
                    if o.idle.pixmap == p {
                        fences.push(o.idle.fence);
                        false
                    } else {
                        true
                    }
                });
                self.shown.retain(|_, v| {
                    if v.idle.pixmap == p {
                        fences.push(v.idle.fence);
                        false
                    } else {
                        true
                    }
                });
                for f in fences.into_iter().filter(|f| *f != 0) {
                    s.sync.trigger(f);
                }
                self.single.retain(|_, v| *v != p);
                // The idle fences triggered above may be what other held presents wait for.
                self.release_waiting(s);
            }
            Hook::WindowFreed(w) => {
                self.drop_waiting(s, |q| q.idle.window == w);
                self.flush_window(s, w); // before the window's selections go away
                self.deadlines.remove(&w);
                self.multi.remove(&w);
                self.send_configure(s, w, PIXMAP_FLAG_WINDOW_DESTROYED);
                self.events.retain(|_, e| e.window != w);
                // Idle fences triggered by the drop and the flush: release what waited for them.
                self.release_waiting(s);
            }
            _ => {}
        }
    }

    fn on_client_disconnected(&mut self, s: &mut Server, client: ClientId) {
        self.drop_waiting(s, |w| w.client == client);
        self.events.retain(|_, e| e.client != client);
    }
}
