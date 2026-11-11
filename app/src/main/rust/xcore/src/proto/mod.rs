//! The X11 protocol core of WinLite's X server (Phase 2): resources, window tree, events,
//! input and the core request set. Behaviour follows the Java implementation it replaces
//! (`com.winlator.cmod.xserver`) request for request, quirks included, because Wine is very
//! sensitive to the order and content of what the server sends.
//!
//! The core is platform independent. Pixels live behind [`gfx::PixelStore`]; everything the
//! renderer, the UI or the extensions need to hear about is queued as [`Notify`] values and
//! drained by the caller *after* the request has been handled, so the core never calls out while
//! it is being mutated (no re-entrancy into the state).

pub mod conn;
pub mod consts;
pub mod cursor_font;
pub mod events;
pub mod ext;
pub mod fallback;
pub mod gfx;
pub mod hooks;
pub mod input;
pub mod platform;
pub mod requests;
pub mod tables;
pub mod wire;

use consts::{ev, VENDOR_NAME};
use events::Event;
use gfx::{Drawable, Gc, PixelStore};
use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use wire::{Order, Writer};

pub type ClientId = usize;

/// What the rest of the application hears about (renderer, task manager, extensions, input
/// bridge). Mirrors the Java `OnWindowModificationListener` / pointer / keyboard listeners.
#[derive(Clone, Debug, PartialEq)]
pub enum Notify {
    CreateWindow { id: u32, parent: u32 },
    MapWindow(u32),
    UnmapWindow(u32),
    DestroyWindow(u32),
    /// `stack_mode`: 0 = ABOVE, 1 = BELOW.
    ChangeZOrder { stack_mode: u8, window: u32, sibling: u32 },
    /// Pixels of a window changed.
    ContentUpdate(u32),
    Geometry { window: u32, resized: bool },
    Attributes { window: u32, mask: u32 },
    Property { window: u32, atom: u32 },
    Reparent { window: u32, parent: u32, x: i16, y: i16 },
    /// A drawable (pixmap or window content) was removed; its buffer can be released.
    DrawableRemoved(u32),
    /// The pointer is somewhere else now: a movement (the clients got MotionNotify), or a silent
    /// move (a warp by a client, the edge pull-back), which the renderer must follow too.
    PointerMove { x: i16, y: i16 },
    PointerButton { button: u8, pressed: bool },
    Key { keycode: u8, keysym: u32, pressed: bool },
    /// Relative mouse mode: forward a button to the Windows side (WinHandler).
    RelativeMouseButton { button: u8, pressed: bool, wheel_delta: i32 },
    /// A DRI3 pixmap that belongs to `window` was drawn to or presented: show it.
    ContentUpdateDirect { window: u32, pixmap: u32 },
    /// A client handed the window a buffer of its own (DRI3): the renderer can import
    /// `XCore.acquireBuffer(pixmap)`.
    DirectContentAdded { window: u32, pixmap: u32 },
    DirectContentRemoved { window: u32, pixmap: u32 },
    /// Composite redirected or unredirected the window.
    CompositeRedirect { window: u32, redirected: bool },
    /// Only when a renderer takes the frames directly (`frames::Router`): `frames` content
    /// updates of `window` since the last of these, of its own pixels (`pixmap` 0) or of DRI3
    /// pixmaps (`pixmap` the latest). In place of [`Notify::ContentUpdate`] and
    /// [`Notify::ContentUpdateDirect`] for the owner, at most every `frames::NOTIFY_INTERVAL`.
    ContentFrames { window: u32, pixmap: u32, frames: u32 },
    /// The client's output has data to send.
    Output(ClientId),
    /// The connection must be closed (protocol violation the Java code also died on).
    Kill(ClientId),
}

#[derive(Clone, Debug)]
pub struct Visual {
    pub id: u32,
    pub displayable: bool,
    pub depth: u8,
    pub bits_per_rgb: u8,
    pub red_mask: u32,
    pub green_mask: u32,
    pub blue_mask: u32,
}

#[derive(Clone, Debug)]
pub struct Property {
    pub name: u32,
    pub type_: u32,
    /// 8, 16 or 32.
    pub format: u8,
    pub data: Vec<u8>,
}

#[derive(Clone, Copy, Debug)]
pub struct Listener {
    pub client: ClientId,
    pub mask: u32,
}

#[derive(Clone, Debug)]
pub struct WindowAttrs {
    pub mapped: bool,
    pub override_redirect: bool,
    pub event_mask: u32,
    pub do_not_propagate: u32,
    pub cursor: Option<u32>,
    pub backing_store: u8,
    pub bit_gravity: u8,
    pub win_gravity: u8,
    pub backing_planes: u32,
    pub backing_pixel: u32,
    /// The background pixel last set (ClearArea paints with it); `None` after a background pixmap.
    pub background_pixel: Option<u32>,
    pub save_under: bool,
    pub enabled: bool,
    /// 0 CopyFromParent, 1 InputOutput, 2 InputOnly.
    pub class: u8,
}

impl Default for WindowAttrs {
    fn default() -> Self {
        // CENTER (5) for both gravities is what the Java defaults reported.
        WindowAttrs {
            mapped: false,
            override_redirect: false,
            event_mask: 0,
            do_not_propagate: 0,
            cursor: None,
            backing_store: 0,
            bit_gravity: 5,
            win_gravity: 5,
            backing_planes: 1,
            backing_pixel: 0,
            background_pixel: None,
            save_under: false,
            enabled: true,
            class: 1,
        }
    }
}

pub struct Window {
    pub id: u32,
    pub parent: Option<u32>,
    /// Bottom-most first, like the Java list.
    pub children: Vec<u32>,
    pub x: i16,
    pub y: i16,
    pub width: i16,
    pub height: i16,
    pub border_width: i16,
    /// The client that created the window (`None` for the root).
    pub origin: Option<ClientId>,
    pub attrs: WindowAttrs,
    pub properties: BTreeMap<u32, Property>,
    pub listeners: Vec<Listener>,
    /// Window id of its content drawable (always the window's own id) when InputOutput.
    pub has_content: bool,
    pub composite_redirected: bool,
}

pub struct Cursor {
    pub id: u32,
    /// Unique for the life of the server (ids are reused by clients): consumers compare it to
    /// know whether the cursor image changed.
    pub serial: u64,
    /// FreeCursor was called; windows that still use it keep showing it (as in the Java server).
    pub freed: bool,
    pub hot_x: i16,
    pub hot_y: i16,
    pub image: Drawable,
    pub visible: bool,
    /// What RecolorCursor needs; `None` for ARGB (RENDER) cursors, which it does not recolor.
    pub shape: Option<CursorShape>,
}

/// A two-colour cursor: per pixel 0 transparent, 1 foreground, 2 background, and the 0xRRGGBB
/// colours it is painted with.
pub struct CursorShape {
    pub bits: Vec<u8>,
    pub fore: u32,
    pub back: u32,
}

#[derive(Default)]
pub struct Selection {
    pub owner: Option<u32>,
    pub client: Option<ClientId>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Owned {
    Window(u32),
    Pixmap(u32),
    Gc(u32),
    Cursor(u32),
}

pub struct Client {
    pub id: ClientId,
    pub order: Order,
    pub authenticated: bool,
    pub seq: u16,
    pub resource_base: u32,
    /// Bytes waiting to be written to the socket.
    pub out: Vec<u8>,
    pub owned: Vec<Owned>,
    /// Data byte (2nd byte) of the request being handled.
    pub request_data: u8,
    /// Received bytes that do not make a whole request yet.
    pub inbuf: Vec<u8>,
    /// Descriptors received with the requests and not taken by a handler yet.
    pub fds: std::collections::VecDeque<i32>,
    /// Requests are on hold (the bytes queue up in `inbuf`).
    pub blocked: Option<hooks::Blocked>,
    /// The socket is not read before this monotonic time (0 = not paused).
    pub pause_until_ns: i64,
}

/// Extension registered with the core (the request handlers themselves live elsewhere).
#[derive(Clone, Debug)]
pub struct ExtInfo {
    pub name: String,
    pub major_opcode: u8,
    pub first_event: u8,
    pub first_error: u8,
}

pub struct Config {
    pub width: i16,
    pub height: i16,
    /// Allocates the pixel memory of a drawable (an AHardwareBuffer on Android).
    pub alloc: Box<dyn FnMut(i16, i16) -> Option<Box<dyn PixelStore>> + Send>,
    /// Milliseconds clock for event timestamps.
    pub clock_ms: Box<dyn Fn() -> u32 + Send>,
    pub platform: Box<dyn platform::Platform>,
}

/// Why a request handler stopped.
#[derive(Debug)]
pub enum Fail {
    /// An X error to report to the client.
    X { code: u8, data: u32 },
    /// A bug-class failure: the request is dropped without an error (Wine hands unexpected X
    /// errors to Xlib's fatal handler, so the Java server never sent one for these).
    Drop(&'static str),
}

impl From<wire::Underflow> for Fail {
    fn from(_: wire::Underflow) -> Fail {
        Fail::Drop("request shorter than its handler expects")
    }
}

pub type Res<T = ()> = Result<T, Fail>;

pub fn bad(code: u8, data: u32) -> Fail {
    Fail::X { code, data }
}

pub struct Server {
    pub screen_w: i16,
    pub screen_h: i16,
    alloc: Box<dyn FnMut(i16, i16) -> Option<Box<dyn PixelStore>> + Send>,
    clock_ms: Box<dyn Fn() -> u32 + Send>,
    pub visuals: [Visual; 2],
    pub formats: [(u8, u8, u8); 3],
    pub root: u32,
    pub windows: HashMap<u32, Window>,
    pub drawables: HashMap<u32, Drawable>,
    pub pixmaps: HashSet<u32>,
    pub gcs: HashMap<u32, Gc>,
    pub cursors: HashMap<u32, Cursor>,
    pub cursor_serial: u64,
    pub selections: HashMap<u32, Selection>,
    pub atoms: Vec<String>,
    pub clients: Vec<Option<Client>>,
    id_bases: BTreeSet<u32>,
    pub id_mask: u32,
    pub focus: Option<u32>,
    /// 0 None, 1 PointerRoot, 2 Parent.
    pub focus_revert: u8,
    pub input: input::InputState,
    pub server_grab: Option<ClientId>,
    pub notifications: Vec<Notify>,
    pub extensions: Vec<ExtInfo>,
    pub ext_handlers: HashMap<u8, Box<dyn requests::Extension>>,
    pub platform: Box<dyn platform::Platform>,
    /// (deadline_ns, sequence, extension major opcode, token), earliest first.
    pub timers: std::collections::BinaryHeap<std::cmp::Reverse<(i64, u64, u8, u64)>>,
    pub(crate) timer_seq: u64,
    /// SYNC fences (shared with Present, which triggers them).
    pub sync: ext::sync::SyncState,
    pub randr: ext::randr::RandrState,
    pub frame_clock: ext::frame::FrameClock,
    pub(crate) in_wakes: bool,
    pub(crate) import_results: std::sync::Arc<hooks::ImportResults>,
    pub(crate) import_jobs: HashMap<u64, hooks::ImportJob>,
    pub(crate) import_seq: u64,
    pub(crate) waker: Option<hooks::Wake>,
}

impl Server {
    pub fn new(cfg: Config) -> Server {
        let mut next_xid = 0u32;
        let mut gen = || {
            next_xid += 1;
            next_xid
        };
        // Same order as the Java constructors: PixmapManager first (two visuals), then the root.
        let v1 = gen();
        let v2 = gen();
        let root = gen();
        let visuals = [
            Visual { id: v1, displayable: true, depth: 32, bits_per_rgb: 24, red_mask: 0xff0000, green_mask: 0x00ff00, blue_mask: 0x0000ff },
            Visual { id: v2, displayable: false, depth: 1, bits_per_rgb: 1, red_mask: 0, green_mask: 0, blue_mask: 0 },
        ];
        // maxClients = 128: 7 client bits, resource ids of 22 bits.
        let base_bits = 22;
        let id_mask = (1u32 << base_bits) - 1;
        let id_bases = (1u32..128).map(|i| i << base_bits).collect();

        let mut s = Server {
            screen_w: cfg.width,
            screen_h: cfg.height,
            alloc: cfg.alloc,
            clock_ms: cfg.clock_ms,
            visuals,
            formats: [(1, 1, 32), (24, 32, 32), (32, 32, 32)],
            root,
            windows: HashMap::new(),
            drawables: HashMap::new(),
            pixmaps: HashSet::new(),
            gcs: HashMap::new(),
            cursors: HashMap::new(),
            cursor_serial: 0,
            selections: HashMap::new(),
            atoms: tables::PREDEFINED_ATOMS.iter().map(|s| s.to_string()).collect(),
            clients: Vec::new(),
            id_bases,
            id_mask,
            focus: None,
            focus_revert: 0,
            input: input::InputState::new(root),
            server_grab: None,
            notifications: Vec::new(),
            extensions: Vec::new(),
            ext_handlers: HashMap::new(),
            platform: cfg.platform,
            timers: Default::default(),
            timer_seq: 0,
            sync: Default::default(),
            randr: Default::default(),
            frame_clock: Default::default(),
            in_wakes: false,
            import_results: Default::default(),
            import_jobs: HashMap::new(),
            import_seq: 0,
            waker: None,
        };
        s.create_root();
        s.input.keyboard = input::Keyboard::default_layout();
        s.setup_x_resources();
        s
    }

    fn create_root(&mut self) {
        let (w, h) = (self.screen_w, self.screen_h);
        let store = (self.alloc)(w, h).expect("root drawable");
        let depth = self.visuals[0].depth;
        let vid = self.visuals[0].id;
        self.drawables.insert(self.root, Drawable { id: self.root, width: w, height: h, depth, visual: Some(vid), store, offscreen: false, direct_window: None });
        let mut win = Window::new(self.root, None, 0, 0, w, h, None);
        win.has_content = true;
        win.attrs.mapped = true;
        self.windows.insert(self.root, win);
    }

    /// DesktopHelper.setupXResources: the Xcursor settings in the root's RESOURCE_MANAGER.
    fn setup_x_resources(&mut self) {
        let atom = self.atom_id("RESOURCE_MANAGER") as u32;
        let ty = self.atom_id("STRING") as u32;
        let text = "Xcursor.size:\t20\nXcursor.theme:\tdmz\nXcursor.theme_core:\ttrue\n";
        let root = self.root;
        self.modify_property(root, atom, ty, 8, 2, text.as_bytes().to_vec());
        self.notifications.clear();
    }

    pub fn now(&self) -> u32 {
        (self.clock_ms)()
    }

    // ---------------------------------------------------------------- atoms

    /// Atom id of `name`, or -1 (Java `Atom.getId`).
    pub fn atom_id(&self, name: &str) -> i64 {
        self.atoms.iter().position(|a| a == name && !a.is_empty()).map(|i| i as i64).unwrap_or(-1)
    }

    pub fn intern_atom(&mut self, name: &str) -> u32 {
        match self.atom_id(name) {
            -1 => {
                self.atoms.push(name.to_string());
                (self.atoms.len() - 1) as u32
            }
            id => id as u32,
        }
    }

    pub fn atom_is_valid(&self, id: u32) -> bool {
        id > 0 && (id as usize) < self.atoms.len()
    }

    // ---------------------------------------------------------------- clients

    /// Registers a new connection. `None` when all resource id ranges are taken.
    pub fn add_client(&mut self) -> Option<ClientId> {
        let base = *self.id_bases.iter().next()?;
        self.id_bases.remove(&base);
        let client = Client { id: 0, order: Order::Le, authenticated: false, seq: 0, resource_base: base, out: Vec::new(), owned: Vec::new(), request_data: 0, inbuf: Vec::new(), fds: Default::default(), blocked: None, pause_until_ns: 0 };
        let idx = match self.clients.iter().position(|c| c.is_none()) {
            Some(i) => i,
            None => {
                self.clients.push(None);
                self.clients.len() - 1
            }
        };
        self.clients[idx] = Some(Client { id: idx, ..client });
        Some(idx)
    }

    pub fn client(&self, id: ClientId) -> Option<&Client> {
        self.clients.get(id).and_then(|c| c.as_ref())
    }

    pub fn client_mut(&mut self, id: ClientId) -> Option<&mut Client> {
        self.clients.get_mut(id).and_then(|c| c.as_mut())
    }

    pub fn is_valid_resource_id(&self, client: ClientId, id: u32) -> bool {
        match self.client(client) {
            Some(c) => (id | self.id_mask) == (c.resource_base | self.id_mask),
            None => false,
        }
    }

    /// Frees everything a closing client owned (Java `XClient.freeResources`).
    pub fn remove_client(&mut self, id: ClientId) {
        let Some(c) = self.client(id) else { return };
        let owned: Vec<Owned> = c.owned.clone();
        // Fence ids come from the client's id range, which the next client is given.
        let (base, mask) = (c.resource_base, self.id_mask);
        self.sync.drop_in_range(base, mask);
        for res in owned.into_iter().rev() {
            match res {
                Owned::Window(w) => self.destroy_window(w),
                Owned::Pixmap(p) => self.free_pixmap(p),
                Owned::Gc(g) => {
                    self.gcs.remove(&g);
                }
                Owned::Cursor(cu) => {
                    self.cursors.remove(&cu);
                }
            }
        }
        for w in self.windows.values_mut() {
            w.listeners.retain(|l| l.client != id);
        }
        for sel in self.selections.values_mut() {
            if sel.client == Some(id) {
                sel.client = None;
            }
        }
        if self.server_grab == Some(id) {
            self.server_grab = None;
        }
        if self.input.grab.as_ref().map(|g| g.client) == Some(id) {
            // The grab's listener belonged to this client; Java left it dangling, which kept
            // delivering to a dead stream. Releasing it is the same without the leak.
            self.input.grab = None;
        }
        let base = self.client(id).map(|c| c.resource_base);
        if let Some(b) = base {
            self.id_bases.insert(b);
        }
        if let Some(c) = self.clients[id].take() {
            for fd in c.fds {
                unsafe { libc::close(fd) };
            }
        }
    }

    pub fn register_owner(&mut self, client: ClientId, res: Owned) {
        if let Some(c) = self.client_mut(client) {
            c.owned.push(res);
        }
    }

    fn forget_owned(&mut self, res: Owned) {
        for c in self.clients.iter_mut().flatten() {
            c.owned.retain(|o| *o != res);
        }
    }

    // ---------------------------------------------------------------- output

    fn push_to_client(&mut self, client: ClientId, event: &Event) {
        let Some(c) = self.clients.get_mut(client).and_then(|c| c.as_mut()) else { return };
        // MotionNotify collapse: a queued, unsent MotionNotify is replaced by the newer one.
        let n = c.out.len();
        if event.code() == consts::code::MOTION_NOTIFY && n >= 32 && (c.out[n - 32] & 0x7f) == consts::code::MOTION_NOTIFY && is_event_boundary(&c.out, n) {
            c.out.truncate(n - 32);
        }
        let seq = c.seq;
        let order = c.order;
        event.encode(&mut Writer::new(&mut c.out, order), seq);
        self.notifications.push(Notify::Output(client));
    }

    /// Events of listeners interested in one selection bit (Java `sendEvent(int eventId, ..)`).
    pub fn send_to_window(&mut self, window: u32, bit: u32, event: &Event) {
        let targets: Vec<ClientId> = match self.windows.get(&window) {
            Some(w) => w.listeners.iter().filter(|l| l.mask & bit != 0).map(|l| l.client).collect(),
            None => return,
        };
        for c in targets {
            self.push_to_client(c, event);
        }
    }

    pub fn send_to_window_for(&mut self, window: u32, bit: u32, event: &Event, client: ClientId) {
        let hit = self.windows.get(&window).map(|w| w.listeners.iter().any(|l| l.client == client && l.mask & bit != 0)).unwrap_or(false);
        if hit {
            self.push_to_client(client, event);
        }
    }

    /// Listeners whose mask intersects `mask` (Java `sendEvent(Bitmask, ..)`).
    pub fn send_to_window_mask(&mut self, window: u32, mask: u32, event: &Event) {
        self.send_to_window(window, mask, event)
    }

    pub fn send_to_window_mask_for(&mut self, window: u32, mask: u32, event: &Event, client: ClientId) {
        self.send_to_window_for(window, mask, event, client)
    }

    /// Every listener regardless of its mask (Java `Window.sendEvent(Event)`).
    pub fn send_to_all_listeners(&mut self, window: u32, event: &Event) {
        let targets: Vec<ClientId> = match self.windows.get(&window) {
            Some(w) => w.listeners.iter().map(|l| l.client).collect(),
            None => return,
        };
        for c in targets {
            self.push_to_client(c, event);
        }
    }

    pub fn send_to_client(&mut self, client: ClientId, event: &Event) {
        self.push_to_client(client, event);
    }

    // ---------------------------------------------------------------- geometry helpers

    pub fn window(&self, id: u32) -> Option<&Window> {
        self.windows.get(&id)
    }

    pub fn root_to_local(&self, window: u32, mut x: i16, mut y: i16) -> (i16, i16) {
        let mut cur = Some(window);
        while let Some(id) = cur {
            let Some(w) = self.windows.get(&id) else { break };
            x = x.wrapping_sub(w.x);
            y = y.wrapping_sub(w.y);
            cur = w.parent;
        }
        (x, y)
    }

    pub fn local_to_root(&self, window: u32, mut x: i16, mut y: i16) -> (i16, i16) {
        let mut cur = Some(window);
        while let Some(id) = cur {
            let Some(w) = self.windows.get(&id) else { break };
            x = x.wrapping_add(w.x);
            y = y.wrapping_add(w.y);
            cur = w.parent;
        }
        (x, y)
    }

    pub fn contains_point(&self, window: u32, root_x: i16, root_y: i16) -> bool {
        let (lx, ly) = self.root_to_local(window, root_x, root_y);
        let Some(w) = self.windows.get(&window) else { return false };
        lx >= 0 && ly >= 0 && lx < w.width && ly < w.height
    }

    pub fn child_by_coords(&self, window: u32, x: i16, y: i16) -> Option<u32> {
        let w = self.windows.get(&window)?;
        w.children.iter().rev().copied().find(|c| self.windows.get(c).map(|cw| cw.attrs.mapped).unwrap_or(false) && self.contains_point(*c, x, y))
    }

    pub fn find_point_window(&self, root_x: i16, root_y: i16) -> Option<u32> {
        self.find_point_in(self.root, root_x, root_y)
    }

    fn find_point_in(&self, window: u32, x: i16, y: i16) -> Option<u32> {
        let w = self.windows.get(&window)?;
        if !(w.attrs.mapped && self.contains_point(window, x, y)) {
            return None;
        }
        match self.child_by_coords(window, x, y) {
            Some(c) => self.find_point_in(c, x, y),
            None => Some(window),
        }
    }

    /// `ancestor` is a proper ancestor of `window`.
    pub fn is_ancestor_of(&self, ancestor: u32, mut window: u32) -> bool {
        if window == ancestor {
            return false;
        }
        loop {
            match self.windows.get(&window).and_then(|w| w.parent) {
                Some(p) if p == ancestor => return true,
                Some(p) => window = p,
                None => return false,
            }
        }
    }

    pub fn has_listener_for(&self, window: u32, bit: u32) -> bool {
        self.windows.get(&window).map(|w| w.listeners.iter().any(|l| l.mask & bit != 0)).unwrap_or(false)
    }

    pub fn all_event_masks(&self, window: u32) -> u32 {
        self.windows.get(&window).map(|w| w.listeners.iter().fold(0, |m, l| m | l.mask)).unwrap_or(0)
    }

    /// 0 Unmapped, 1 Unviewable, 2 Viewable.
    pub fn map_state(&self, window: u32) -> u8 {
        let Some(w) = self.windows.get(&window) else { return 0 };
        if !w.attrs.mapped {
            return 0;
        }
        let mut cur = w.parent;
        while let Some(p) = cur {
            let Some(pw) = self.windows.get(&p) else { return 2 };
            if !pw.attrs.mapped {
                return 1;
            }
            cur = pw.parent;
        }
        2
    }

    pub fn previous_sibling(&self, window: u32) -> Option<u32> {
        let parent = self.windows.get(&window)?.parent?;
        let siblings = &self.windows.get(&parent)?.children;
        let i = siblings.iter().position(|c| *c == window)?;
        if i > 0 {
            Some(siblings[i - 1])
        } else {
            None
        }
    }

    pub fn ancestor_with_event_mask(&self, mut window: Option<u32>, mask: u32) -> Option<u32> {
        while let Some(id) = window {
            let w = self.windows.get(&id)?;
            if w.listeners.iter().any(|l| l.mask & mask != 0) {
                return Some(id);
            }
            if w.attrs.do_not_propagate & mask != 0 {
                return None;
            }
            window = w.parent;
        }
        None
    }

    pub fn ancestor_with_event_id(&self, window: u32, bit: u32, end: Option<u32>) -> Option<u32> {
        let mut cur = Some(window);
        while let Some(id) = cur {
            let w = self.windows.get(&id)?;
            if w.listeners.iter().any(|l| l.mask & bit != 0) {
                return Some(id);
            }
            if Some(id) == end || w.attrs.do_not_propagate & bit != 0 {
                return None;
            }
            cur = w.parent;
        }
        None
    }

    /// `getAttributes().getCursor()`: the window's own cursor or the nearest ancestor's.
    pub fn effective_cursor(&self, window: u32) -> Option<u32> {
        let mut cur = Some(window);
        while let Some(id) = cur {
            let w = self.windows.get(&id)?;
            if w.attrs.cursor.is_some() {
                return w.attrs.cursor;
            }
            cur = w.parent;
        }
        None
    }

    // ---------------------------------------------------------------- drawables

    pub fn new_drawable(&mut self, id: u32, w: i16, h: i16, depth: u8) -> Option<Drawable> {
        let visual = self.visual_for_depth(depth);
        self.new_drawable_with(id, w, h, depth, visual)
    }

    pub fn new_drawable_with(&mut self, id: u32, w: i16, h: i16, depth: u8, visual: Option<u32>) -> Option<Drawable> {
        let store = (self.alloc)(w, h)?;
        Some(Drawable { id, width: w, height: h, depth, visual, store, offscreen: false, direct_window: None })
    }

    pub fn visual_for_depth(&self, depth: u8) -> Option<u32> {
        self.visuals.iter().find(|v| v.depth == depth).map(|v| v.id)
    }

    pub fn visual_by_id(&self, id: u32) -> Option<&Visual> {
        self.visuals.iter().find(|v| v.id == id && v.displayable)
    }

    pub fn visual(&self, id: u32) -> Option<&Visual> {
        self.visuals.iter().find(|v| v.id == id)
    }

    pub fn free_pixmap(&mut self, id: u32) {
        if self.pixmaps.contains(&id) {
            self.run_hook(hooks::Hook::PixmapFreed(id));
            self.sync.drop_for_drawable(id);
            if let Some(w) = self.drawables.get(&id).and_then(|d| d.direct_window) {
                self.notifications.push(Notify::DirectContentRemoved { window: w, pixmap: id });
            }
            self.pixmaps.remove(&id);
            self.drawables.remove(&id);
            self.notifications.push(Notify::DrawableRemoved(id));
            self.forget_owned(Owned::Pixmap(id));
        }
    }

    /// The Java `onDrawListener` of a window's content: tell the renderer.
    pub fn content_changed(&mut self, drawable: u32) {
        if self.windows.get(&drawable).map(|w| w.has_content).unwrap_or(false) {
            self.notify_content(drawable);
        } else if let Some(window) = self.drawables.get(&drawable).and_then(|d| d.direct_window) {
            self.notifications.push(Notify::ContentUpdateDirect { window, pixmap: drawable });
        }
    }

    fn notify_content(&mut self, window: u32) {
        // Consecutive updates of the same window collapse into one notification.
        if self.notifications.last() != Some(&Notify::ContentUpdate(window)) {
            self.notifications.push(Notify::ContentUpdate(window));
        }
    }

    // ---------------------------------------------------------------- properties

    /// Window.modifyProperty. `mode`: 0 replace, 1 prepend, 2 append. Returns whether the
    /// property was modified (Java returned the property, `null` for a BadMatch).
    pub fn modify_property(&mut self, window: u32, atom: u32, type_: u32, format: u8, mode: u8, data: Vec<u8>) -> bool {
        let Some(w) = self.windows.get_mut(&window) else { return false };
        let mut modified = false;
        match w.properties.get_mut(&atom) {
            None => {
                w.properties.insert(atom, Property { name: atom, type_, format, data });
                modified = true;
            }
            Some(p) if mode == 0 => {
                // Replace: type, format and data are all the new ones.
                *p = Property { name: atom, type_, format, data };
                modified = true;
            }
            Some(p) if p.format == format && p.type_ == type_ => {
                if mode == 1 {
                    let mut d = data;
                    d.extend_from_slice(&p.data);
                    p.data = d;
                } else if mode == 2 {
                    p.data.extend_from_slice(&data);
                }
                modified = true;
            }
            Some(_) => {}
        }
        if modified {
            let ev = Event::PropertyNotify { window, atom, time: self.now(), deleted: false };
            self.send_to_window(window, ev::PROPERTY_CHANGE, &ev);
        }
        modified
    }

    pub fn remove_property(&mut self, window: u32, atom: u32) {
        if let Some(w) = self.windows.get_mut(&window) {
            w.properties.remove(&atom);
        } else {
            return;
        }
        let ev = Event::PropertyNotify { window, atom, time: self.now(), deleted: true };
        self.send_to_window(window, ev::PROPERTY_CHANGE, &ev);
    }

    pub fn property_string(&self, window: u32, atom_name: &str) -> String {
        let id = self.atom_id(atom_name);
        if id < 0 {
            return String::new();
        }
        match self.windows.get(&window).and_then(|w| w.properties.get(&(id as u32))) {
            Some(p) => self.property_to_string(p),
            None => String::new(),
        }
    }

    /// Java `Property.toString()`.
    pub fn property_to_string(&self, p: &Property) -> String {
        let ty = self.atoms.get(p.type_ as usize).map(|s| s.as_str()).unwrap_or("");
        match ty {
            // Java `StringUtils.fromANSIString` cuts the text at the first NUL, so a
            // `WM_CLASS` of "game.exe\0Game\0" reads back as "game.exe".
            "UTF8_STRING" => {
                let end = p.data.iter().position(|&c| c == 0).unwrap_or(p.data.len());
                String::from_utf8_lossy(&p.data[..end]).into_owned()
            }
            "STRING" => p.data.iter().take_while(|&&c| c != 0).map(|&c| c as char).collect(),
            "ATOM" if p.data.len() >= 4 => {
                let id = u32::from_le_bytes([p.data[0], p.data[1], p.data[2], p.data[3]]);
                self.atoms.get(id as usize).cloned().unwrap_or_default()
            }
            _ => {
                let size = (p.format / 8) as usize;
                p.data
                    .chunks_exact(size.max(1))
                    .map(|c| match p.format {
                        8 => (c[0] as i8).to_string(),
                        16 => i16::from_le_bytes([c[0], c[1]]).to_string(),
                        _ => i32::from_le_bytes([c[0], c[1], c[2], c[3]]).to_string(),
                    })
                    .collect::<Vec<_>>()
                    .join(",")
            }
        }
    }

    // ---------------------------------------------------------------- window tree

    #[allow(clippy::too_many_arguments)]
    pub fn create_window(&mut self, client: ClientId, id: u32, parent: u32, x: i16, y: i16, w: i16, h: i16, class: u8, visual_id: Option<u32>, mut depth: u8) -> Res {
        if self.windows.contains_key(&id) {
            return Err(bad(consts::err::BAD_ID_CHOICE, id));
        }
        let (parent_io, parent_depth, parent_visual) = {
            let p = self.windows.get(&parent).ok_or(Fail::X { code: consts::err::BAD_WINDOW, data: parent })?;
            let d = self.drawables.get(&parent);
            (p.has_content, d.map(|d| d.depth), d.and_then(|d| d.visual))
        };
        let input_output;
        match class {
            0 => {
                depth = if depth != 0 || !parent_io { depth } else { parent_depth.ok_or(Fail::Drop("no parent drawable"))? };
                input_output = parent_io;
            }
            1 => {
                if parent_io {
                    depth = if depth == 0 { parent_depth.ok_or(Fail::Drop("no parent drawable"))? } else { depth };
                    input_output = true;
                } else {
                    return Err(bad(consts::err::BAD_MATCH, 0));
                }
            }
            _ => input_output = false,
        }

        let mut vis = visual_id;
        if input_output {
            let vid = match vis.or(parent_visual) {
                Some(v) => v,
                None => return Err(Fail::Drop("parent has no visual")),
            };
            let vdepth = self.visual(vid).map(|v| v.depth).ok_or(Fail::Drop("unknown visual"))?;
            if depth != vdepth {
                return Err(bad(consts::err::BAD_MATCH, 0));
            }
            vis = Some(vid);
        }

        if input_output {
            if self.drawables.contains_key(&id) {
                return Err(bad(consts::err::BAD_ID_CHOICE, id));
            }
            let d = self.new_drawable_with(id, w, h, depth, vis).ok_or(Fail::Drop("drawable allocation failed"))?;
            self.drawables.insert(id, d);
        }

        let mut win = Window::new(id, Some(parent), x, y, w, h, Some(client));
        win.attrs.class = class;
        win.has_content = input_output;
        self.windows.insert(id, win);
        self.windows.get_mut(&parent).unwrap().children.push(id);
        self.update_point_window();
        self.notifications.push(Notify::CreateWindow { id, parent });
        Ok(())
    }

    /// (Re)selects events for `client` on `window`; an empty mask removes the listener.
    pub fn set_event_listener(&mut self, client: ClientId, window: u32, mask: u32) {
        let Some(w) = self.windows.get_mut(&window) else { return };
        w.listeners.retain(|l| l.client != client);
        if mask != 0 {
            w.listeners.push(Listener { client, mask });
        }
    }

    pub fn client_event_mask(&self, client: ClientId, window: u32) -> u32 {
        self.windows.get(&window).and_then(|w| w.listeners.iter().find(|l| l.client == client)).map(|l| l.mask).unwrap_or(0)
    }

    pub fn destroy_window(&mut self, id: u32) {
        if id == self.root || !self.windows.contains_key(&id) {
            return;
        }
        self.unmap_window(id);
        let mut destroyed = Vec::new();
        self.collect_subtree(id, &mut destroyed);
        self.remove_subtree(id);
        for d in destroyed {
            self.notifications.push(Notify::DestroyWindow(d));
        }
    }

    fn collect_subtree(&self, id: u32, out: &mut Vec<u32>) {
        if let Some(w) = self.windows.get(&id) {
            for c in w.children.clone() {
                self.collect_subtree(c, out);
            }
        }
        out.push(id);
    }

    fn remove_subtree(&mut self, id: u32) {
        let Some(children) = self.windows.get(&id).map(|w| w.children.clone()) else { return };
        for c in children {
            self.remove_subtree(c);
        }
        let parent = self.windows.get(&id).and_then(|w| w.parent);
        self.run_hook(hooks::Hook::WindowFreed(id));
        self.sync.drop_for_drawable(id);
        self.send_to_window(id, ev::STRUCTURE_NOTIFY, &Event::DestroyNotify { event: id, window: id });
        if let Some(p) = parent {
            self.send_to_window(p, ev::SUBSTRUCTURE_NOTIFY, &Event::DestroyNotify { event: p, window: id });
        }
        let had_content = self.windows.get(&id).map(|w| w.has_content).unwrap_or(false);
        // The window leaves the table, then everything that referred to it is cleaned up.
        let focused = self.focus == Some(id);
        let cursor = self.windows.get(&id).and_then(|w| w.attrs.cursor);
        self.windows.remove(&id);
        if let Some(cu) = cursor {
            self.gc_cursor(cu);
        }
        if had_content {
            self.drawables.remove(&id);
            self.notifications.push(Notify::DrawableRemoved(id));
        }
        self.forget_owned(Owned::Window(id));
        for sel in self.selections.values_mut() {
            if sel.owner == Some(id) {
                sel.owner = None;
            }
        }
        self.update_point_window();
        if focused {
            self.revert_focus(Some(id), parent);
        }
        if let Some(p) = parent {
            if let Some(pw) = self.windows.get_mut(&p) {
                pw.children.retain(|c| *c != id);
            }
        }
    }

    /// WindowManager.revertFocus. `gone`/`gone_parent` describe the focus window when it was just removed.
    fn revert_focus(&mut self, _gone: Option<u32>, gone_parent: Option<u32>) {
        match self.focus_revert {
            0 => self.focus = None,
            1 => self.focus = Some(self.root),
            _ => {
                if let Some(cur) = self.focus {
                    let parent = self.windows.get(&cur).map(|w| w.parent).unwrap_or(gone_parent);
                    if let Some(p) = parent {
                        self.focus = Some(p);
                    }
                }
            }
        }
    }

    pub fn revert_focus_current(&mut self) {
        let cur = self.focus;
        let parent = cur.and_then(|c| self.windows.get(&c).and_then(|w| w.parent));
        self.revert_focus(cur, parent);
    }

    pub fn map_window(&mut self, id: u32) {
        let (mapped, parent, override_redirect) = match self.windows.get(&id) {
            Some(w) => (w.attrs.mapped, w.parent, w.attrs.override_redirect),
            None => return,
        };
        if mapped {
            return;
        }
        let Some(parent) = parent else { return };
        if !self.has_listener_for(parent, ev::SUBSTRUCTURE_REDIRECT) || override_redirect {
            self.windows.get_mut(&id).unwrap().attrs.mapped = true;
            self.send_to_window(id, ev::STRUCTURE_NOTIFY, &Event::MapNotify { event: id, window: id, override_redirect });
            self.send_to_window(parent, ev::SUBSTRUCTURE_NOTIFY, &Event::MapNotify { event: parent, window: id, override_redirect });
            let (w, h) = {
                let w = &self.windows[&id];
                (w.width, w.height)
            };
            self.send_to_window(id, ev::EXPOSURE, &Event::Expose { window: id, w, h });
            self.notifications.push(Notify::MapWindow(id));
            self.update_point_window();
        } else {
            self.send_to_window(parent, ev::SUBSTRUCTURE_REDIRECT, &Event::MapRequest { parent, window: id });
        }
    }

    pub fn unmap_window(&mut self, id: u32) {
        if id == self.root {
            return;
        }
        let parent = match self.windows.get(&id) {
            Some(w) if w.attrs.mapped => w.parent,
            _ => return,
        };
        self.windows.get_mut(&id).unwrap().attrs.mapped = false;
        if let Some(parent) = parent {
            self.send_to_window(id, ev::STRUCTURE_NOTIFY, &Event::UnmapNotify { event: id, window: id });
            self.send_to_window(parent, ev::SUBSTRUCTURE_NOTIFY, &Event::UnmapNotify { event: parent, window: id });
        }
        if self.focus == Some(id) {
            self.revert_focus_current();
        }
        self.notifications.push(Notify::UnmapWindow(id));
        // Listener order of the Java server: pointer grab first (it looks at the not yet
        // refreshed point window), then the input device manager.
        self.grab_on_unmap(id);
        self.update_point_window();
        self.run_hook(hooks::Hook::WindowUnmapped(id));
    }

    pub fn reparent_window(&mut self, id: u32, new_parent: u32, x: i16, y: i16) {
        let Some(old_parent) = self.windows.get(&id).and_then(|w| w.parent) else { return };
        // A mapped window is unmapped first and mapped again in its new place.
        let was_mapped = self.windows[&id].attrs.mapped;
        if was_mapped {
            self.unmap_window(id);
        }
        if let Some(op) = self.windows.get_mut(&old_parent) {
            op.children.retain(|c| *c != id);
        }
        if let Some(np) = self.windows.get_mut(&new_parent) {
            if !np.children.contains(&id) {
                np.children.push(id);
            }
        }
        let w = self.windows.get_mut(&id).unwrap();
        w.parent = Some(new_parent);
        w.x = x;
        w.y = y;
        let override_redirect = w.attrs.override_redirect;
        self.send_to_window(id, ev::STRUCTURE_NOTIFY, &Event::ReparentNotify { event: id, window: id, parent: new_parent, x, y, override_redirect });
        self.send_to_window(old_parent, ev::SUBSTRUCTURE_NOTIFY, &Event::ReparentNotify { event: old_parent, window: id, parent: new_parent, x, y, override_redirect });
        self.send_to_window(new_parent, ev::SUBSTRUCTURE_NOTIFY, &Event::ReparentNotify { event: new_parent, window: id, parent: new_parent, x, y, override_redirect });
        self.notifications.push(Notify::Reparent { window: id, parent: new_parent, x, y });
        self.update_point_window();
        if was_mapped {
            self.map_window(id);
        }
    }

    /// Moves/resizes on the server's own initiative (fullscreen requests) and reports it.
    pub fn set_window_geometry(&mut self, id: u32, x: i16, y: i16, w: i16, h: i16) {
        let Some(win) = self.windows.get(&id) else { return };
        let (ox, oy, ow, oh) = (win.x, win.y, win.width, win.height);
        self.change_window_geometry(id, x, y, w, h);
        let win = &self.windows[&id];
        let (x, y, w, h) = (win.x, win.y, win.width, win.height);
        if (x, y, w, h) == (ox, oy, ow, oh) {
            return;
        }
        let parent = win.parent;
        let border = win.border_width;
        let or = win.attrs.override_redirect;
        let above = self.previous_sibling(id).unwrap_or(0);
        self.send_to_window(id, ev::STRUCTURE_NOTIFY, &Event::ConfigureNotify { event: id, window: id, above, x, y, w, h, border, override_redirect: or });
        if let Some(p) = parent {
            self.send_to_window(p, ev::SUBSTRUCTURE_NOTIFY, &Event::ConfigureNotify { event: p, window: id, above, x, y, w, h, border, override_redirect: or });
        }
    }

    fn change_window_geometry(&mut self, id: u32, x: i16, y: i16, mut w: i16, mut h: i16) {
        let (cw, ch) = {
            let win = &self.windows[&id];
            (win.width, win.height)
        };
        let mut resized = cw != w || ch != h;
        if resized && self.has_listener_for(id, ev::RESIZE_REDIRECT) {
            self.send_to_window(id, ev::RESIZE_REDIRECT, &Event::ResizeRequest { window: id, w, h });
            let win = &self.windows[&id];
            w = win.width;
            h = win.height;
            resized = false;
        }

        let has_content = self.windows[&id].has_content;
        if resized && has_content {
            // New buffer of the new size under the same id; the offscreen flag carries over.
            let old = self.drawables.remove(&id);
            self.notifications.push(Notify::DrawableRemoved(id));
            if let Some(old) = old {
                match self.new_drawable_with(id, w, h, old.depth, old.visual) {
                    Some(mut nd) => {
                        nd.offscreen = old.offscreen;
                        self.drawables.insert(id, nd);
                    }
                    None => {
                        // Allocation failed: keep the old buffer rather than lose the window's content.
                        self.drawables.insert(id, old);
                        resized = false;
                        w = cw;
                        h = ch;
                    }
                }
            }
        }

        let win = self.windows.get_mut(&id).unwrap();
        if resized || win.x != x || win.y != y {
            win.x = x;
            win.y = y;
            win.width = w;
            win.height = h;
            self.notifications.push(Notify::Geometry { window: id, resized });
            self.run_hook(hooks::Hook::WindowGeometry { window: id, resized });
            self.update_point_window();
        }
        let win = &self.windows[&id];
        if resized && has_content && win.attrs.mapped {
            let ev = Event::Expose { window: id, w: win.width, h: win.height };
            self.send_to_window(id, ev::EXPOSURE, &ev);
        }
    }

    /// ConfigureWindow with its value list already parsed.
    #[allow(clippy::too_many_arguments)]
    pub fn configure_window(&mut self, id: u32, value_mask: u32, x: i16, y: i16, w: i16, h: i16, border: i16, sibling: Option<u32>, stack_mode: Option<u8>) {
        let parent = self.windows[&id].parent;
        let or = self.windows[&id].attrs.override_redirect;
        let redirect = parent.map(|p| self.has_listener_for(p, ev::SUBSTRUCTURE_REDIRECT)).unwrap_or(false);
        let Some(parent) = parent else { return };
        if !redirect || or {
            self.change_window_geometry(id, x, y, w, h);
            self.windows.get_mut(&id).unwrap().border_width = border;
            if let Some(mode) = stack_mode {
                self.change_window_z_order(mode, id, sibling);
            }
            let above = self.previous_sibling(id).unwrap_or(0);
            self.send_to_window(id, ev::STRUCTURE_NOTIFY, &Event::ConfigureNotify { event: id, window: id, above, x, y, w, h, border, override_redirect: or });
            self.send_to_window(parent, ev::SUBSTRUCTURE_NOTIFY, &Event::ConfigureNotify { event: parent, window: id, above, x, y, w, h, border, override_redirect: or });
        } else {
            let above = self.previous_sibling(id).unwrap_or(0);
            let ev = Event::ConfigureRequest { stack_mode: stack_mode.unwrap_or(0), parent, window: id, sibling: above, x, y, w, h, border, value_mask: value_mask as u16 };
            self.send_to_window(parent, ev::SUBSTRUCTURE_REDIRECT, &ev);
        }
    }

    fn change_window_z_order(&mut self, mode: u8, id: u32, sibling: Option<u32>) {
        let Some(parent) = self.windows.get(&id).and_then(|w| w.parent) else { return };
        let pw = self.windows.get_mut(&parent).unwrap();
        match mode {
            0 => {
                pw.children.retain(|c| *c != id);
                match sibling.and_then(|s| pw.children.iter().position(|c| *c == s)) {
                    Some(i) => pw.children.insert(i + 1, id),
                    None => pw.children.push(id),
                }
            }
            1 => {
                pw.children.retain(|c| *c != id);
                match sibling.and_then(|s| pw.children.iter().position(|c| *c == s)) {
                    Some(i) => pw.children.insert(i, id),
                    None => pw.children.insert(0, id),
                }
            }
            _ => {}
        }
        self.notifications.push(Notify::ChangeZOrder { stack_mode: mode, window: id, sibling: sibling.unwrap_or(0) });
        self.update_point_window();
    }

    pub fn is_desktop_window(&self, id: u32) -> bool {
        let Some(w) = self.windows.get(&id) else { return false };
        w.parent == Some(self.root) && !w.attrs.override_redirect && self.property_string(id, "WM_CLASS").contains("explorer.exe")
    }

    /// Size of the screen as shown (a smaller Wine virtual desktop fills the display).
    pub fn effective_screen(&self) -> (i16, i16) {
        if let Some(root) = self.windows.get(&self.root) {
            for &c in &root.children {
                let Some(w) = self.windows.get(&c) else { continue };
                if !w.attrs.mapped || !self.is_desktop_window(c) {
                    continue;
                }
                let fits = w.width >= 320 && w.height >= 200 && w.width <= self.screen_w && w.height <= self.screen_h;
                if fits && (w.width < self.screen_w || w.height < self.screen_h) {
                    return (w.width, w.height);
                }
                break;
            }
        }
        (self.screen_w, self.screen_h)
    }
}

impl Window {
    pub fn new(id: u32, parent: Option<u32>, x: i16, y: i16, width: i16, height: i16, origin: Option<ClientId>) -> Window {
        Window {
            id,
            parent,
            children: Vec::new(),
            x,
            y,
            width,
            height,
            border_width: 0,
            origin,
            attrs: WindowAttrs::default(),
            properties: BTreeMap::new(),
            listeners: Vec::new(),
            has_content: false,
            composite_redirected: false,
        }
    }
}

/// True if `n` falls on a 32-byte event boundary of the unsent output, i.e. the last 32 bytes
/// really are one event and not the tail of a longer reply.
fn is_event_boundary(out: &[u8], n: usize) -> bool {
    // Replies and events alternate arbitrarily; walk the buffer from the start.
    let mut i = 0;
    while i < n {
        let code = out[i];
        // Replies and GenericEvents (35) carry extra length words.
        let size = if (code == 1 || code & 0x7f == 35) && i + 8 <= n {
            let len = u32::from_le_bytes([out[i + 4], out[i + 5], out[i + 6], out[i + 7]]) as usize;
            32 + len * 4
        } else {
            32
        };
        if i + size == n {
            return size == 32 && code != 1;
        }
        i += size;
    }
    false
}

pub const _VENDOR: &str = VENDOR_NAME;
