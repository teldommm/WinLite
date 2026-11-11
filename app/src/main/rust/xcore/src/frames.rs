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
//!   rates, how many frames it shows) goes on to it; frames as [`Notify::ContentFrames`], with
//!   the number of frames since the previous report of that window. A frame is reported at once,
//!   unless the window's previous report is closer than [`NOTIFY_INTERVAL`] (see there for why
//!   that must stay short: the HUDs derive the fps from these counts).
//!
//! Windows whose WM_CLASS names one of [`set_hidden_classes`] (Wine's explorer.exe desktop, for
//! a shortcut) are not drawn and get no input.
//!
//! Pure code: the buffer references and the renderer are behind [`Output`], so the routing and
//! its ordering rules do not depend on the platform.

use crate::bridge;
use crate::proto::{Notify, Server};
use std::collections::HashMap;
use std::sync::Mutex;
use std::sync::Arc;
use std::time::{Duration, Instant};

/// WM_CLASS, a predefined atom of the X protocol.
const ATOM_WM_CLASS: u32 = 67;

static HIDDEN_CLASSES: Mutex<Vec<String>> = Mutex::new(Vec::new());

/// Windows whose WM_CLASS contains one of `classes` are not drawn and get no input (applied as
/// windows are mapped or get their WM_CLASS).
pub fn set_hidden_classes(classes: Vec<String>) {
    *HIDDEN_CLASSES.lock().unwrap_or_else(|e| e.into_inner()) = classes;
}

fn hidden_classes() -> Vec<String> {
    HIDDEN_CLASSES.lock().unwrap_or_else(|e| e.into_inner()).clone()
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

/// A window's frames reach Java at once, unless the previous report of that window is closer than
/// this: then they are only counted, and go with the next report.
///
/// Keep it short. The HUDs turn the reports into fps by adding up what arrived in their own
/// sampling window (the modern one redraws, and samples, every 400 ms), so a report that holds
/// frames back for a quarter of a second makes the reading jump with the number of reports that
/// happen to fall into the window: one or two. A steady 60 fps then reads 37..81, and a limiter
/// set to 10 reads 7..15, although the game presents at an even pace. With 8 ms nothing is held
/// back up to 125 fps, and above that about `rate x 8 ms` frames at most (one at 125 fps, two at
/// 250), which the sampling window does not show.
pub const NOTIFY_INTERVAL: Duration = Duration::from_millis(8);

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

    /// Counts a frame of `window`; the summary for Java when it is due (the first frame, and
    /// every frame that comes [`NOTIFY_INTERVAL`] or more after the previous report). Frames held
    /// back are not lost: they go with the next report.
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
