//! Frames straight from the server to the renderer, past Java.
//!
//! Before, every frame took the Java route: the I/O thread woke the `XServerEvents` executor,
//! which drained the events into a new `long[]`, asked for the buffer (`acquireBuffer`, another
//! JNI call that took the server lock again), handed it to the renderer and gave the reference
//! back, then read and pushed the display clock: five or six JNI calls, a thread hop and boxed
//! map entries per frame. With an [`Output`] installed, the [`Router`] takes the frame
//! notifications out of the stream instead:
//!
//! * under the server lock it queues an [`Item`] per frame, with a reference to the buffer taken
//!   right there (the drawable cannot go away meanwhile);
//! * the runtime's frame thread (`xcore-frames`) hands the queue to [`Output::deliver`], in
//!   server order and one batch at a time (see `Runtime`), so a frame never overtakes the one
//!   before it, and a renderer that is busy holds up only that thread, not the X server;
//! * what Java still wants to know about frames (the first frame of a window, which window the
//!   HUD rates, how many frames it shows) comes as [`Notify::ContentFrames`], at most every
//!   [`NOTIFY_INTERVAL`] per window, with the number of frames since the previous one.
//!
//! The renderer keeps the content of a window by the window's id, whether or not the window
//! itself is known to it yet, so a frame that reaches it before the window's create/map events
//! (which still go through Java) is not lost: the window shows it once it exists.
//!
//! Pure code: the buffer references and the renderer are behind [`Output`], so the routing and
//! its ordering rules are tested on the host.

use crate::proto::{Notify, Server};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::{Duration, Instant};

/// Java hears about a window's frames at most this often (the HUD shows 2-4 updates a second).
pub const NOTIFY_INTERVAL: Duration = Duration::from_millis(250);

/// One step for the renderer, in server order.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Item {
    /// Show the buffer of `drawable` (the window itself, or a DRI3 pixmap presented to it) as the
    /// content of `window`. `buf` is a reference taken by [`Output::acquire`]; the item owns it
    /// and [`Output::deliver`] gives it back.
    Show { window: u32, drawable: u32, buf: usize },
    /// The server dropped `drawable`: the renderer may forget its import of the buffer.
    Removed(u32),
}

/// The renderer end. All methods may be called from any thread.
pub trait Output: Send + Sync {
    /// A renderer is there to take frames. Called under the server lock: keep it to an atomic.
    fn active(&self) -> bool;
    /// Under the server lock: a reference to the buffer of `drawable`, 0 if it has none.
    fn acquire(&self, server: &Server, drawable: u32) -> usize;
    /// Outside the server lock, in server order, never two calls at a time. Must give back the
    /// reference of every [`Item::Show`], renderer or not.
    fn deliver(&self, items: Vec<Item>);
}

#[derive(Default)]
struct Rate {
    last: Option<Instant>,
    frames: u32,
}

/// The per-server routing state; lives under the server lock (in the runtime's state).
pub struct Router {
    output: Arc<dyn Output>,
    pending: Vec<Item>,
    /// Per (window, direct): when Java last heard, and the frames since.
    rates: HashMap<(u32, bool), Rate>,
}

impl Router {
    pub fn new(output: Arc<dyn Output>) -> Router {
        Router { output, pending: Vec::new(), rates: HashMap::new() }
    }

    pub fn output(&self) -> &Arc<dyn Output> {
        &self.output
    }

    pub fn has_pending(&self) -> bool {
        !self.pending.is_empty()
    }

    pub fn pending_len(&self) -> usize {
        self.pending.len()
    }

    pub fn take_pending(&mut self) -> Vec<Item> {
        std::mem::take(&mut self.pending)
    }

    /// Takes the frame notifications for the renderer; returns what goes on to the owner's queue.
    pub fn route(&mut self, server: &Server, n: Notify, now: Instant) -> Option<Notify> {
        match n {
            Notify::ContentUpdate(w) => {
                // Pixels of an unmapped window are not shown (Java skipped them too); mapping it
                // shows them (below).
                if is_mapped(server, w) {
                    self.show(server, w, w);
                }
                self.rate(w, 0, now)
            }
            Notify::ContentUpdateDirect { window, pixmap } => {
                self.show(server, window, pixmap);
                self.rate(window, pixmap, now)
            }
            Notify::DrawableRemoved(d) => {
                if self.output.active() {
                    self.pending.push(Item::Removed(d));
                }
                // Java only used it to give the renderer's imports back: nothing left to do there.
                None
            }
            Notify::MapWindow(w) => {
                // Drawn while unmapped: that content was not shown, and may not be drawn again.
                self.show_content(server, w);
                Some(n)
            }
            Notify::Geometry { window, resized: true } => {
                // A resized window has a new buffer under the same id (its DrawableRemoved came
                // first); the renderer needs it even if nothing is drawn into it right away.
                self.show_content(server, window);
                Some(n)
            }
            Notify::DestroyWindow(w) => {
                self.rates.remove(&(w, false));
                self.rates.remove(&(w, true));
                Some(n)
            }
            other => Some(other),
        }
    }

    /// Shows the window's own (X-drawn) content if it has one and is mapped: after a new renderer
    /// came up, after a resize, on map.
    pub fn show_content(&mut self, server: &Server, window: u32) {
        let has_content = server.windows.get(&window).map(|w| w.has_content).unwrap_or(false);
        if has_content && is_mapped(server, window) {
            self.show(server, window, window);
        }
    }

    fn show(&mut self, server: &Server, window: u32, drawable: u32) {
        if !self.output.active() {
            return;
        }
        // Already queued as the window's latest content, and the drawable not dropped since: the
        // renderer will look at the same buffer anyway (many PutImages make one frame).
        for item in self.pending.iter().rev() {
            match *item {
                Item::Show { window: w, drawable: d, .. } if w == window => {
                    if d == drawable {
                        return;
                    }
                    break;
                }
                Item::Removed(d) if d == drawable => break,
                _ => {}
            }
        }
        let buf = self.output.acquire(server, drawable);
        if buf != 0 {
            self.pending.push(Item::Show { window, drawable, buf });
        }
    }

    /// Counts a frame of `window`; the summary for Java when it is due.
    fn rate(&mut self, window: u32, pixmap: u32, now: Instant) -> Option<Notify> {
        let r = self.rates.entry((window, pixmap != 0)).or_default();
        r.frames = r.frames.saturating_add(1);
        if let Some(last) = r.last {
            if now.saturating_duration_since(last) < NOTIFY_INTERVAL {
                return None;
            }
        }
        r.last = Some(now);
        let frames = std::mem::take(&mut r.frames);
        Some(Notify::ContentFrames { window, pixmap, frames })
    }
}

impl Drop for Router {
    /// References still queued go back (the output releases them, renderer or not).
    fn drop(&mut self) {
        if !self.pending.is_empty() {
            let items = self.take_pending();
            self.output.deliver(items);
        }
    }
}

fn is_mapped(server: &Server, window: u32) -> bool {
    server.windows.get(&window).map(|w| w.attrs.mapped).unwrap_or(false)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
    use std::sync::Mutex;

    /// Buffers are `drawable + 1000`; acquired references are counted.
    #[derive(Default)]
    struct Fake {
        inactive: AtomicBool,
        refs: AtomicUsize,
        log: Mutex<Vec<Item>>,
    }

    impl Output for Fake {
        fn active(&self) -> bool {
            !self.inactive.load(Ordering::SeqCst)
        }
        fn acquire(&self, server: &Server, drawable: u32) -> usize {
            if server.drawables.contains_key(&drawable) {
                self.refs.fetch_add(1, Ordering::SeqCst);
                drawable as usize + 1000
            } else {
                0
            }
        }
        fn deliver(&self, items: Vec<Item>) {
            for it in &items {
                if let Item::Show { .. } = it {
                    self.refs.fetch_sub(1, Ordering::SeqCst);
                }
            }
            self.log.lock().unwrap().extend(items);
        }
    }

    fn req(s: &mut Server, c: crate::proto::ClientId, opcode: u8, data: u8, body: &[u8]) {
        let mut v = vec![opcode, data];
        v.extend_from_slice(&((body.len() / 4 + 1) as u16).to_le_bytes());
        v.extend_from_slice(body);
        s.feed(c, &v);
    }

    /// A server with one client and its mapped 64x64 window (which has content of its own).
    fn server() -> (Server, u32) {
        let mut s = crate::host::new_server(640, 480);
        let c = s.add_client().unwrap();
        s.feed(c, &[0x6c, 0, 11, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
        let w = s.client(c).unwrap().resource_base + 1;
        let mut b = Vec::new();
        for v in [w, s.root] {
            b.extend(v.to_le_bytes());
        }
        for v in [0u16, 0, 64, 64, 0, 1] {
            b.extend(v.to_le_bytes()); // x, y, width, height, border, InputOutput
        }
        b.extend(0u32.to_le_bytes()); // visual: CopyFromParent
        b.extend(0u32.to_le_bytes()); // no attributes
        req(&mut s, c, 1, 0, &b);
        req(&mut s, c, 8, 0, &w.to_le_bytes());
        assert!(s.windows[&w].has_content && s.windows[&w].attrs.mapped);
        s.notifications.clear();
        s.take_output(c);
        (s, w)
    }

    /// A 4x4 pixmap of the window's client, number `n` of its resources.
    fn add_pixmap(s: &mut Server, window: u32, n: u32) -> u32 {
        let c = s.clients.iter().position(|c| c.is_some()).unwrap();
        let pm = (window - 1) + n;
        let mut b = Vec::new();
        b.extend(pm.to_le_bytes());
        b.extend(window.to_le_bytes());
        b.extend(4u16.to_le_bytes());
        b.extend(4u16.to_le_bytes());
        req(s, c, 53, 32, &b);
        assert!(s.drawables.contains_key(&pm), "pixmap {pm:#x} was not created");
        s.notifications.clear();
        s.take_output(c);
        pm
    }

    fn router(f: &Arc<Fake>) -> Router {
        Router::new(f.clone() as Arc<dyn Output>)
    }

    #[test]
    fn repeated_updates_of_the_same_buffer_are_queued_once() {
        let (s, w) = server();
        let f = Arc::new(Fake::default());
        let mut r = router(&f);
        let now = Instant::now();
        for _ in 0..5 {
            r.route(&s, Notify::ContentUpdate(w), now);
        }
        assert_eq!(r.take_pending().len(), 1);
        assert_eq!(f.refs.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn alternating_pixmaps_keep_every_switch_in_order() {
        let (mut s, w) = server();
        let (p1, p2) = (add_pixmap(&mut s, w, 5), add_pixmap(&mut s, w, 6));
        let f = Arc::new(Fake::default());
        let mut r = router(&f);
        let now = Instant::now();
        for p in [p1, p2, p1] {
            r.route(&s, Notify::ContentUpdateDirect { window: w, pixmap: p }, now);
        }
        let shown: Vec<u32> = r
            .take_pending()
            .iter()
            .map(|i| match i {
                Item::Show { drawable, .. } => *drawable,
                _ => 0,
            })
            .collect();
        // The last one shown must be p1: skipping it as "already queued" would leave p2 up.
        assert_eq!(shown, vec![p1, p2, p1]);
    }

    #[test]
    fn a_removed_drawable_is_queued_again_after_its_removal() {
        let (s, w) = server();
        let f = Arc::new(Fake::default());
        let mut r = router(&f);
        let now = Instant::now();
        r.route(&s, Notify::ContentUpdate(w), now);
        assert!(r.route(&s, Notify::DrawableRemoved(w), now).is_none(), "not for Java");
        r.route(&s, Notify::ContentUpdate(w), now);
        let items = r.take_pending();
        assert!(matches!(items[..], [Item::Show { .. }, Item::Removed(_), Item::Show { .. }]), "{items:?}");
    }

    #[test]
    fn unmapped_windows_are_not_shown_until_mapped() {
        let (mut s, w) = server();
        s.windows.get_mut(&w).unwrap().attrs.mapped = false;
        let f = Arc::new(Fake::default());
        let mut r = router(&f);
        let now = Instant::now();
        r.route(&s, Notify::ContentUpdate(w), now);
        assert!(!r.has_pending());
        s.windows.get_mut(&w).unwrap().attrs.mapped = true;
        assert_eq!(r.route(&s, Notify::MapWindow(w), now), Some(Notify::MapWindow(w)));
        assert_eq!(r.take_pending().len(), 1);
    }

    #[test]
    fn nothing_is_queued_without_a_renderer() {
        let (s, w) = server();
        let f = Arc::new(Fake::default());
        f.inactive.store(true, Ordering::SeqCst);
        let mut r = router(&f);
        let now = Instant::now();
        r.route(&s, Notify::ContentUpdate(w), now);
        r.route(&s, Notify::DrawableRemoved(w), now);
        assert!(!r.has_pending());
        assert_eq!(f.refs.load(Ordering::SeqCst), 0);
    }

    #[test]
    fn java_hears_about_frames_at_most_every_interval_with_the_count() {
        let (s, w) = server();
        let f = Arc::new(Fake::default());
        let mut r = router(&f);
        let t0 = Instant::now();
        assert_eq!(r.route(&s, Notify::ContentUpdate(w), t0), Some(Notify::ContentFrames { window: w, pixmap: 0, frames: 1 }));
        for i in 1..10 {
            assert_eq!(r.route(&s, Notify::ContentUpdate(w), t0 + Duration::from_millis(i * 20)), None);
        }
        let t1 = t0 + NOTIFY_INTERVAL;
        assert_eq!(r.route(&s, Notify::ContentUpdate(w), t1), Some(Notify::ContentFrames { window: w, pixmap: 0, frames: 10 }));
    }

    #[test]
    fn dropping_the_router_gives_queued_references_back() {
        let (s, w) = server();
        let f = Arc::new(Fake::default());
        {
            let mut r = router(&f);
            r.route(&s, Notify::ContentUpdate(w), Instant::now());
            assert_eq!(f.refs.load(Ordering::SeqCst), 1);
        }
        assert_eq!(f.refs.load(Ordering::SeqCst), 0);
    }
}
