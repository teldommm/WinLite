//! Frames, the window tree and the cursor straight from the server to the renderer, past Java.
//!
//! Before, every frame took the Java route: the I/O thread woke the `XServerEvents` executor,
//! which drained the events into a new `long[]`, asked for the buffer (`acquireBuffer`, another
//! JNI call that took the server lock again), handed it to the renderer and gave the reference
//! back, then read and pushed the display clock: five or six JNI calls, a thread hop and boxed
//! map entries per frame. With an [`Output`] installed, the [`Router`] takes the notifications
//! the renderer needs out of the stream instead:
//!
//! * under the server lock it queues an [`Item`] per frame, with a reference to the buffer taken
//!   right there (the drawable cannot go away meanwhile), and per change of the window tree, of
//!   the pointer position and of the cursor, with the state of that moment (Java used to read it
//!   back later, from a server that had moved on);
//! * the runtime's frame thread (`xcore-frames`) hands the queue to [`Output::deliver`], in
//!   server order and one batch at a time (see `Runtime`), so nothing overtakes what came before
//!   it, and a renderer that is busy holds up only that thread, not the X server;
//! * a new renderer (or a new server under the old one) gets the whole state again
//!   ([`Router::replay`]);
//! * what Java still wants to know (map / unmap / geometry for the shown part of the screen, the
//!   pointer for the magnifier, and of frames: the first frame of a window, which window the HUD
//!   rates, how many frames it shows) goes on to it; frames as [`Notify::ContentFrames`], at most
//!   every [`NOTIFY_INTERVAL`] per window, with the number of frames since the previous one.
//!
//! Windows whose WM_CLASS names one of [`set_hidden_classes`] (Wine's explorer.exe desktop, for
//! a shortcut) are not drawn and get no input.
//!
//! Pure code: the buffer references and the renderer are behind [`Output`], so the routing and
//! its ordering rules are tested on the host.

use crate::bridge;
use crate::proto::{Notify, Server};
use std::collections::HashMap;
#[cfg(not(test))]
use std::sync::Mutex;
use std::sync::Arc;
use std::time::{Duration, Instant};

/// WM_CLASS, a predefined atom of the X protocol.
const ATOM_WM_CLASS: u32 = 67;

#[cfg(not(test))]
static HIDDEN_CLASSES: Mutex<Vec<String>> = Mutex::new(Vec::new());

/// Windows whose WM_CLASS contains one of `classes` are not drawn and get no input (applied as
/// windows are mapped or get their WM_CLASS).
#[cfg(not(test))]
pub fn set_hidden_classes(classes: Vec<String>) {
    *HIDDEN_CLASSES.lock().unwrap_or_else(|e| e.into_inner()) = classes;
}

#[cfg(not(test))]
fn hidden_classes() -> Vec<String> {
    HIDDEN_CLASSES.lock().unwrap_or_else(|e| e.into_inner()).clone()
}

// Tests run in parallel: each thread has its own list.
#[cfg(test)]
thread_local! {
    static HIDDEN_CLASSES: std::cell::RefCell<Vec<String>> = const { std::cell::RefCell::new(Vec::new()) };
}

#[cfg(test)]
pub fn set_hidden_classes(classes: Vec<String>) {
    HIDDEN_CLASSES.with(|h| *h.borrow_mut() = classes);
}

#[cfg(test)]
fn hidden_classes() -> Vec<String> {
    HIDDEN_CLASSES.with(|h| h.borrow().clone())
}

/// The X cursor under the pointer, as the renderer shows it.
#[derive(Debug, PartialEq, Eq)]
pub struct CursorImage {
    /// A new serial means a new image.
    pub serial: u64,
    /// The client's own visibility (an empty mask hides it).
    pub visible: bool,
    pub hot_x: i16,
    pub hot_y: i16,
    pub width: i16,
    pub height: i16,
    /// R,G,B,A, `width * height * 4` bytes.
    pub rgba: Vec<u8>,
}

/// Java hears about a window's frames at most this often (the HUD shows 2-4 updates a second).
pub const NOTIFY_INTERVAL: Duration = Duration::from_millis(250);

/// One step for the renderer, in server order. Window ids are the server's (also the renderer's
/// keys, and those of the windows' own contents).
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Item {
    /// Show the buffer of `drawable` (the window itself, or a DRI3 pixmap presented to it) as the
    /// content of `window`. `buf` is a reference taken by [`Output::acquire`]; the item owns it
    /// and [`Output::deliver`] gives it back.
    Show { window: u32, drawable: u32, buf: usize },
    /// The server dropped `drawable`: the renderer may forget its import of the buffer.
    Removed(u32),
    /// Starts the window tree over with just the root (a new renderer, a new server).
    InitRoot { root: u32, width: i32, height: i32 },
    /// `content` is the window's own id when it has content of its own, else 0.
    Create { id: u32, parent: u32, content: u32, x: i32, y: i32, width: i32, height: i32 },
    Destroy(u32),
    Map(u32),
    Unmap(u32),
    Reparent { id: u32, parent: u32 },
    Geometry { id: u32, content: u32, x: i32, y: i32, width: i32, height: i32 },
    /// The children of `parent`, bottom-most first.
    Children { parent: u32, ordered: Vec<u32> },
    Viewable { id: u32, viewable: bool },
    /// The pointer is here (only the latest of a run is kept).
    Pointer { x: i16, y: i16 },
    /// The X cursor under the pointer changed (`None`: no window up to the root has one, the
    /// renderer shows its default arrow).
    Cursor(Option<Arc<CursorImage>>),
    /// The embedder changed something of the cursor's own (shown or not, the default arrow):
    /// apply it, in order with the rest.
    CursorRefresh,
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
    /// The cursor last queued (serial, client visibility; `None` inside: no X cursor); `None`:
    /// nothing queued yet.
    cursor: Option<Option<(u64, bool)>>,
    /// Something happened that may change the cursor under the pointer.
    cursor_dirty: bool,
    /// Hidden windows whose input still has to be cut off (needs the server mutably).
    disables: Vec<u32>,
}

impl Router {
    pub fn new(output: Arc<dyn Output>) -> Router {
        Router {
            output,
            pending: Vec::new(),
            rates: HashMap::new(),
            cursor: None,
            cursor_dirty: false,
            disables: Vec::new(),
        }
    }

    /// Queues an item for the renderer (nothing without one).
    pub fn push(&mut self, item: Item) {
        if self.output.active() {
            self.pending.push(item);
        }
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

    /// Takes the frame notifications for the renderer and queues what the renderer needs of the
    /// others; returns what goes on to the owner's queue.
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
                self.push(Item::Removed(d));
                // Java only used it to give the renderer's imports back: nothing left to do there.
                None
            }
            Notify::CreateWindow { id, parent } => {
                // Already gone again: its DestroyWindow follows, and the renderer never needs it.
                if let Some(item) = create_item(server, id, parent) {
                    self.push(item);
                    self.check_hidden(server, id);
                }
                self.cursor_dirty = true;
                Some(n)
            }
            Notify::DestroyWindow(w) => {
                self.push(Item::Destroy(w));
                self.rates.remove(&(w, false));
                self.rates.remove(&(w, true));
                self.cursor_dirty = true;
                Some(n)
            }
            Notify::MapWindow(w) => {
                self.push(Item::Map(w));
                // Drawn while unmapped: that content was not shown, and may not be drawn again.
                self.show_content(server, w);
                self.check_hidden(server, w);
                self.cursor_dirty = true;
                Some(n)
            }
            Notify::UnmapWindow(w) => {
                self.push(Item::Unmap(w));
                self.cursor_dirty = true;
                Some(n)
            }
            Notify::Reparent { window, parent, x, y } => {
                if let Some(win) = server.windows.get(&window) {
                    let content = if win.has_content { window } else { 0 };
                    self.push(Item::Reparent { id: window, parent });
                    self.push(Item::Geometry { id: window, content, x: x as i32, y: y as i32, width: win.width as i32, height: win.height as i32 });
                }
                self.cursor_dirty = true;
                Some(n)
            }
            Notify::ChangeZOrder { window, .. } => {
                if let Some(parent) = server.windows.get(&window).and_then(|w| w.parent) {
                    self.push_children(server, parent);
                }
                self.cursor_dirty = true;
                Some(n)
            }
            Notify::Geometry { window, resized } => {
                if let Some(item) = geometry_item(server, window) {
                    self.push(item);
                }
                if resized {
                    // A resized window has a new buffer under the same id (its DrawableRemoved
                    // came first); the renderer needs it even if nothing is drawn into it yet.
                    self.show_content(server, window);
                }
                self.cursor_dirty = true;
                Some(n)
            }
            Notify::Attributes { .. } => {
                // The window's cursor may be another now.
                self.cursor_dirty = true;
                Some(n)
            }
            Notify::Property { window, atom } => {
                // WM_CLASS may be set after the window is mapped.
                if atom == ATOM_WM_CLASS {
                    self.check_hidden(server, window);
                }
                Some(n)
            }
            Notify::PointerMove { x, y } => {
                if let Some(Item::Pointer { x: px, y: py }) = self.pending.last_mut() {
                    (*px, *py) = (x, y);
                } else {
                    self.push(Item::Pointer { x, y });
                }
                self.cursor_dirty = true;
                Some(n)
            }
            other => Some(other),
        }
    }

    /// After a run of notifications: the cursor, if it may have changed. Returns the hidden
    /// windows whose input the caller must cut off (`bridge::disable_subtree`).
    pub fn finish(&mut self, server: &Server) -> Vec<u32> {
        if std::mem::take(&mut self.cursor_dirty) {
            self.check_cursor(server);
        }
        std::mem::take(&mut self.disables)
    }

    /// The whole state for a renderer that has none of it (a new renderer, or a new server):
    /// the window tree, the contents, the pointer and the cursor.
    pub fn replay(&mut self, server: &Server) {
        if !self.output.active() {
            return;
        }
        let root = server.root;
        self.push(Item::InitRoot { root, width: server.screen_w as i32, height: server.screen_h as i32 });
        let hidden = hidden_classes();
        // Parents before their children, siblings bottom-most first (like snapshot_tree).
        let mut order: Vec<u32> = Vec::new();
        let mut stack = vec![root];
        while let Some(id) = stack.pop() {
            order.push(id);
            if let Some(w) = server.windows.get(&id) {
                stack.extend(w.children.iter().rev().copied());
            }
        }
        for &id in order.iter().filter(|&&id| id != root) {
            let Some(w) = server.windows.get(&id) else { continue };
            if let Some(item) = create_item(server, id, w.parent.unwrap_or(root)) {
                self.push(item);
            }
            self.push(Item::Viewable { id, viewable: !is_hidden(server, &hidden, id) });
            if w.attrs.mapped {
                self.push(Item::Map(id));
                self.show_content(server, id);
            }
        }
        for &id in &order {
            if server.windows.get(&id).map(|w| !w.children.is_empty()).unwrap_or(false) {
                self.push_children(server, id);
            }
        }
        self.push(Item::Pointer { x: server.input.x, y: server.input.y });
        self.cursor = None;
        self.check_cursor(server);
    }

    fn push_children(&mut self, server: &Server, parent: u32) {
        if let Some(w) = server.windows.get(&parent) {
            self.push(Item::Children { parent, ordered: w.children.clone() });
        }
    }

    /// Hides (and cuts the input of) a window whose WM_CLASS is one of the hidden classes.
    fn check_hidden(&mut self, server: &Server, window: u32) {
        let hidden = hidden_classes();
        if hidden.is_empty() || window == server.root {
            return;
        }
        let hide = is_hidden(server, &hidden, window);
        if hide {
            self.disables.push(window);
        }
        self.push(Item::Viewable { id: window, viewable: !hide });
    }

    /// Queues the cursor under the pointer when it is not the one queued last.
    fn check_cursor(&mut self, server: &Server) {
        if !self.output.active() {
            return;
        }
        let state = bridge::pointer_cursor(server);
        let key = state.as_ref().map(|c| (c.serial, c.visible));
        if self.cursor == Some(key) {
            return;
        }
        let item = match state {
            None => None,
            Some(c) => {
                let rgba = bridge::pointer_cursor_image(server).unwrap_or_default();
                if c.width <= 0 || c.height <= 0 || rgba.len() < c.width as usize * c.height as usize * 4 {
                    // No usable image: like no X cursor at all (the Java side did the same).
                    None
                } else {
                    Some(Arc::new(CursorImage { serial: c.serial, visible: c.visible, hot_x: c.hot_x, hot_y: c.hot_y, width: c.width, height: c.height, rgba }))
                }
            }
        };
        self.cursor = Some(key);
        self.push(Item::Cursor(item));
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

fn create_item(server: &Server, id: u32, parent: u32) -> Option<Item> {
    let w = server.windows.get(&id)?;
    let content = if w.has_content { id } else { 0 };
    Some(Item::Create { id, parent, content, x: w.x as i32, y: w.y as i32, width: w.width as i32, height: w.height as i32 })
}

fn geometry_item(server: &Server, id: u32) -> Option<Item> {
    let w = server.windows.get(&id)?;
    let content = if w.has_content { id } else { 0 };
    Some(Item::Geometry { id, content, x: w.x as i32, y: w.y as i32, width: w.width as i32, height: w.height as i32 })
}

fn is_hidden(server: &Server, classes: &[String], window: u32) -> bool {
    if classes.is_empty() || window == server.root {
        return false;
    }
    let class = server.property_string(window, "WM_CLASS");
    classes.iter().any(|c| class.contains(c.as_str()))
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
        let items = r.take_pending();
        assert!(matches!(items[..], [Item::Map(m), Item::Show { window, .. }] if m == w && window == w), "{items:?}");
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

    // ------------------------------------------------------------------ window tree, cursor

    /// CreateWindow `id` (a resource of the first client) in `parent`, mapped if asked.
    fn create(s: &mut Server, n: u32, parent: u32, geo: [i16; 4], map: bool) -> u32 {
        let c = s.clients.iter().position(|c| c.is_some()).unwrap();
        let id = s.client(c).unwrap().resource_base + n;
        let mut b = Vec::new();
        b.extend(id.to_le_bytes());
        b.extend(parent.to_le_bytes());
        for v in [geo[0] as u16, geo[1] as u16, geo[2] as u16, geo[3] as u16, 0, 1] {
            b.extend(v.to_le_bytes());
        }
        b.extend(0u32.to_le_bytes());
        b.extend(0u32.to_le_bytes());
        req(s, c, 1, 0, &b);
        if map {
            req(s, c, 8, 0, &id.to_le_bytes());
        }
        s.take_output(c);
        id
    }

    /// Routes what the server queued since the last call, as the runtime's pump does.
    fn pump(s: &mut Server, r: &mut Router) -> Vec<Notify> {
        let now = Instant::now();
        let notes = std::mem::take(&mut s.notifications);
        let rest: Vec<Notify> = notes.into_iter().filter_map(|n| r.route(s, n, now)).collect();
        for w in r.finish(s) {
            bridge::disable_subtree(s, w);
        }
        rest
    }

    fn set_wm_class(s: &mut Server, w: u32, class: &[u8]) {
        let c = s.clients.iter().position(|c| c.is_some()).unwrap();
        let mut b = Vec::new();
        b.extend(w.to_le_bytes());
        b.extend(ATOM_WM_CLASS.to_le_bytes());
        b.extend(31u32.to_le_bytes()); // STRING
        b.extend([8u8, 0, 0, 0]);
        b.extend((class.len() as u32).to_le_bytes());
        b.extend(class);
        while b.len() % 4 != 0 {
            b.push(0);
        }
        req(s, c, 18, 0, &b); // ChangeProperty, Replace
        s.take_output(c);
    }

    fn scene(items: &[Item]) -> Vec<Item> {
        items.iter().filter(|i| !matches!(i, Item::Show { .. } | Item::Removed(_) | Item::Cursor(_))).cloned().collect()
    }

    #[test]
    fn window_changes_reach_the_renderer_with_the_state_of_that_moment() {
        let (mut s, w) = server();
        let f = Arc::new(Fake::default());
        let mut r = router(&f);
        let child = create(&mut s, 2, w, [5, 6, 20, 10], false);
        let to_java = pump(&mut s, &mut r);
        assert!(to_java.contains(&Notify::CreateWindow { id: child, parent: w }), "Java still hears of it");
        assert_eq!(scene(&r.take_pending()), [Item::Create { id: child, parent: w, content: child, x: 5, y: 6, width: 20, height: 10 }]);

        // Map, move, restack, unmap, destroy.
        let c = s.clients.iter().position(|c| c.is_some()).unwrap();
        req(&mut s, c, 8, 0, &child.to_le_bytes());
        let mut b = child.to_le_bytes().to_vec();
        b.extend(0x3u16.to_le_bytes()); // x | y
        b.extend([0u8; 2]);
        b.extend(7u32.to_le_bytes());
        b.extend(9u32.to_le_bytes());
        req(&mut s, c, 12, 0, &b); // ConfigureWindow
        pump(&mut s, &mut r);
        let items = scene(&r.take_pending());
        assert_eq!(items[0], Item::Map(child));
        assert!(items.contains(&Item::Geometry { id: child, content: child, x: 7, y: 9, width: 20, height: 10 }), "{items:?}");

        req(&mut s, c, 10, 0, &child.to_le_bytes()); // UnmapWindow
        req(&mut s, c, 4, 0, &child.to_le_bytes()); // DestroyWindow
        pump(&mut s, &mut r);
        let items = scene(&r.take_pending());
        assert!(items.contains(&Item::Unmap(child)) && items.last() == Some(&Item::Destroy(child)), "{items:?}");
    }

    #[test]
    fn a_window_already_gone_when_routed_is_not_created() {
        let (mut s, w) = server();
        let f = Arc::new(Fake::default());
        let mut r = router(&f);
        let child = create(&mut s, 2, w, [0, 0, 8, 8], false);
        let c = s.clients.iter().position(|c| c.is_some()).unwrap();
        req(&mut s, c, 4, 0, &child.to_le_bytes());
        pump(&mut s, &mut r); // CreateWindow and DestroyWindow in one run
        assert_eq!(scene(&r.take_pending()), [Item::Destroy(child)]);
    }

    #[test]
    fn restacking_sends_the_parents_whole_order() {
        let (mut s, w) = server();
        let f = Arc::new(Fake::default());
        let mut r = router(&f);
        let a = create(&mut s, 2, w, [0, 0, 8, 8], true);
        let b = create(&mut s, 3, w, [0, 0, 8, 8], true);
        pump(&mut s, &mut r);
        r.take_pending();
        let c = s.clients.iter().position(|c| c.is_some()).unwrap();
        let mut body = a.to_le_bytes().to_vec();
        body.extend(0x40u16.to_le_bytes()); // stack-mode
        body.extend([0u8; 2]);
        body.extend(0u32.to_le_bytes()); // Above
        req(&mut s, c, 12, 0, &body); // ConfigureWindow: raise a over b
        pump(&mut s, &mut r);
        let items = scene(&r.take_pending());
        let order = s.windows[&w].children.clone();
        assert!(order == vec![b, a], "{order:x?}");
        assert!(items.contains(&Item::Children { parent: w, ordered: order }), "{items:?}");
    }

    #[test]
    fn pointer_runs_keep_only_the_latest_position_and_silent_moves_count() {
        let (mut s, _) = server();
        let f = Arc::new(Fake::default());
        let mut r = router(&f);
        for x in 0..5 {
            s.set_pointer_position(x * 10, 20);
        }
        s.set_pointer_silent(300, 200); // a warp
        pump(&mut s, &mut r);
        let ptrs: Vec<Item> = r.take_pending().into_iter().filter(|i| matches!(i, Item::Pointer { .. })).collect();
        assert_eq!(ptrs, [Item::Pointer { x: 300, y: 200 }]);
    }

    #[test]
    fn the_cursor_is_queued_only_when_it_changes() {
        let (mut s, _) = server();
        let f = Arc::new(Fake::default());
        let mut r = router(&f);
        s.set_pointer_position(10, 10);
        pump(&mut s, &mut r);
        let first: Vec<Item> = r.take_pending().into_iter().filter(|i| matches!(i, Item::Cursor(_))).collect();
        assert_eq!(first.len(), 1, "the first check queues the cursor in force");
        s.set_pointer_position(11, 10);
        s.set_pointer_position(12, 10);
        pump(&mut s, &mut r);
        assert!(!r.take_pending().iter().any(|i| matches!(i, Item::Cursor(_))), "same cursor: nothing again");
        // A property change says nothing about the cursor: no check at all.
        assert!(r.finish(&s).is_empty());
    }

    #[test]
    fn hidden_classes_hide_the_window_and_cut_its_input() {
        set_hidden_classes(vec!["explorer.exe".into()]);
        let (mut s, w) = server();
        let f = Arc::new(Fake::default());
        let mut r = router(&f);
        let desk = create(&mut s, 2, w, [0, 0, 8, 8], true);
        let child = create(&mut s, 3, desk, [0, 0, 4, 4], true);
        pump(&mut s, &mut r);
        r.take_pending();
        // WM_CLASS comes after the map, as with Wine.
        set_wm_class(&mut s, desk, b"explorer.exe\0Wine\0");
        pump(&mut s, &mut r);
        assert!(r.take_pending().contains(&Item::Viewable { id: desk, viewable: false }));
        assert!(!s.windows[&desk].attrs.enabled && !s.windows[&child].attrs.enabled, "no input for it or below");
        // Another class shows normally.
        set_wm_class(&mut s, child, b"game.exe\0Game\0");
        pump(&mut s, &mut r);
        assert!(r.take_pending().contains(&Item::Viewable { id: child, viewable: true }));
        set_hidden_classes(Vec::new());
    }

    #[test]
    fn replay_rebuilds_tree_contents_pointer_and_cursor_in_order() {
        let (mut s, w) = server();
        let a = create(&mut s, 2, w, [1, 2, 8, 8], true);
        let b = create(&mut s, 3, a, [3, 4, 4, 4], false);
        s.set_pointer_position(30, 40);
        s.notifications.clear();
        let f = Arc::new(Fake::default());
        let mut r = router(&f);
        r.replay(&s);
        let items = r.take_pending();
        let root = s.root;
        assert_eq!(items[0], Item::InitRoot { root, width: 640, height: 480 });
        let pos = |want: &Item| items.iter().position(|i| i == want).unwrap_or_else(|| panic!("{want:?} missing in {items:?}"));
        let cw = pos(&Item::Create { id: w, parent: root, content: w, x: 0, y: 0, width: 64, height: 64 });
        let ca = pos(&Item::Create { id: a, parent: w, content: a, x: 1, y: 2, width: 8, height: 8 });
        let cb = pos(&Item::Create { id: b, parent: a, content: b, x: 3, y: 4, width: 4, height: 4 });
        assert!(cw < ca && ca < cb, "parents first");
        assert!(pos(&Item::Map(a)) > ca);
        assert!(!items.contains(&Item::Map(b)), "b is not mapped");
        assert!(items.iter().any(|i| matches!(i, Item::Show { window, .. } if *window == w)), "mapped content shown again");
        assert!(items.contains(&Item::Children { parent: w, ordered: vec![a] }));
        assert!(items.contains(&Item::Viewable { id: a, viewable: true }));
        assert!(items.contains(&Item::Pointer { x: 30, y: 40 }));
        assert!(matches!(items.last(), Some(Item::Cursor(_))), "cursor last, in force");
        // A second replay sends the cursor again too (a renderer that has nothing).
        r.replay(&s);
        assert!(r.take_pending().iter().any(|i| matches!(i, Item::Cursor(_))));
    }

    #[test]
    fn without_a_renderer_nothing_of_the_scene_is_queued_but_java_hears_everything() {
        let (mut s, w) = server();
        let f = Arc::new(Fake::default());
        f.inactive.store(true, Ordering::SeqCst);
        let mut r = router(&f);
        let child = create(&mut s, 2, w, [0, 0, 8, 8], true);
        s.set_pointer_position(5, 5);
        let to_java = pump(&mut s, &mut r);
        assert!(!r.has_pending());
        assert!(to_java.contains(&Notify::MapWindow(child)) && to_java.contains(&Notify::PointerMove { x: 5, y: 5 }));
        r.replay(&s);
        assert!(!r.has_pending());
    }
}
