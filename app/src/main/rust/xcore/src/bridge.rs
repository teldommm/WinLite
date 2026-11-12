//! The data format between the server and its Java consumers (UI, input, renderer): flat `i64`
//! arrays, so one JNI call moves many items and nothing is allocated per item.
//!
//! Pure code (no JNI): the Android layer only copies these arrays into Java arrays, and the
//! format is unit tested on the host. The numeric constants are mirrored in `XCore.java`.

use crate::proto::{Notify, Server};

/// Values per event in [`encode_events`].
pub const EVENT_STRIDE: usize = 5;
/// Values per window in [`snapshot_tree`].
pub const WINDOW_STRIDE: usize = 8;

// Event kinds. Layout: [kind, a, b, c, d].
pub const EV_CREATE_WINDOW: i64 = 1; // window, parent
pub const EV_MAP_WINDOW: i64 = 2; // window
pub const EV_UNMAP_WINDOW: i64 = 3; // window
pub const EV_DESTROY_WINDOW: i64 = 4; // window
pub const EV_CHANGE_Z_ORDER: i64 = 5; // stack_mode (0 above, 1 below), window, sibling
pub const EV_CONTENT_UPDATE: i64 = 6; // window, 0, frames (0 = one)
pub const EV_GEOMETRY: i64 = 7; // window, resized (0/1)
pub const EV_ATTRIBUTES: i64 = 8; // window, mask
pub const EV_PROPERTY: i64 = 9; // window, atom
pub const EV_REPARENT: i64 = 10; // window, parent, x, y
pub const EV_DRAWABLE_REMOVED: i64 = 11; // drawable
pub const EV_POINTER_MOVE: i64 = 12; // x, y
pub const EV_POINTER_BUTTON: i64 = 13; // button, pressed (0/1)
pub const EV_KEY: i64 = 14; // keycode, keysym, pressed (0/1)
pub const EV_RELATIVE_MOUSE_BUTTON: i64 = 15; // button, pressed (0/1), wheel_delta
pub const EV_CONTENT_UPDATE_DIRECT: i64 = 16; // window, pixmap, frames (0 = one) (DRI3 / Present frame)
pub const EV_DIRECT_CONTENT_ADDED: i64 = 17; // window, pixmap
pub const EV_DIRECT_CONTENT_REMOVED: i64 = 18; // window, pixmap
pub const EV_COMPOSITE_REDIRECT: i64 = 19; // window, redirected (0/1)

// Window flags in [`snapshot_tree`].
pub const WF_MAPPED: i64 = 1;
pub const WF_VIEWABLE: i64 = 2;
pub const WF_OVERRIDE_REDIRECT: i64 = 4;
pub const WF_HAS_CONTENT: i64 = 8;
pub const WF_COMPOSITE_REDIRECTED: i64 = 16;

fn b(v: bool) -> i64 {
    v as i64
}

/// One event as `[kind, a, b, c, d]`; `None` for notifications that are not for consumers
/// (socket output and kills are handled by the runtime and never reach this point).
pub fn encode_event(n: &Notify) -> Option<[i64; EVENT_STRIDE]> {
    Some(match *n {
        Notify::CreateWindow { id, parent } => [EV_CREATE_WINDOW, id as i64, parent as i64, 0, 0],
        Notify::MapWindow(w) => [EV_MAP_WINDOW, w as i64, 0, 0, 0],
        Notify::UnmapWindow(w) => [EV_UNMAP_WINDOW, w as i64, 0, 0, 0],
        Notify::DestroyWindow(w) => [EV_DESTROY_WINDOW, w as i64, 0, 0, 0],
        Notify::ChangeZOrder { stack_mode, window, sibling } => [EV_CHANGE_Z_ORDER, stack_mode as i64, window as i64, sibling as i64, 0],
        Notify::ContentUpdate(w) => [EV_CONTENT_UPDATE, w as i64, 0, 0, 0],
        Notify::Geometry { window, resized } => [EV_GEOMETRY, window as i64, b(resized), 0, 0],
        Notify::Attributes { window, mask } => [EV_ATTRIBUTES, window as i64, mask as i64, 0, 0],
        Notify::Property { window, atom } => [EV_PROPERTY, window as i64, atom as i64, 0, 0],
        Notify::Reparent { window, parent, x, y } => [EV_REPARENT, window as i64, parent as i64, x as i64, y as i64],
        Notify::DrawableRemoved(d) => [EV_DRAWABLE_REMOVED, d as i64, 0, 0, 0],
        Notify::PointerMove { x, y } => [EV_POINTER_MOVE, x as i64, y as i64, 0, 0],
        Notify::PointerButton { button, pressed } => [EV_POINTER_BUTTON, button as i64, b(pressed), 0, 0],
        Notify::Key { keycode, keysym, pressed } => [EV_KEY, keycode as i64, keysym as i64, b(pressed), 0],
        Notify::RelativeMouseButton { button, pressed, wheel_delta } => [EV_RELATIVE_MOUSE_BUTTON, button as i64, b(pressed), wheel_delta as i64, 0],
        Notify::ContentUpdateDirect { window, pixmap } => [EV_CONTENT_UPDATE_DIRECT, window as i64, pixmap as i64, 0, 0],
        Notify::DirectContentAdded { window, pixmap } => [EV_DIRECT_CONTENT_ADDED, window as i64, pixmap as i64, 0, 0],
        Notify::DirectContentRemoved { window, pixmap } => [EV_DIRECT_CONTENT_REMOVED, window as i64, pixmap as i64, 0, 0],
        Notify::CompositeRedirect { window, redirected } => [EV_COMPOSITE_REDIRECT, window as i64, b(redirected), 0, 0],
        // The renderer got the frames already; this only tells how many there were, and when.
        Notify::ContentFrames { window, pixmap: 0, frames, time_ns } => [EV_CONTENT_UPDATE, window as i64, 0, frames as i64, time_ns],
        Notify::ContentFrames { window, pixmap, frames, time_ns } => [EV_CONTENT_UPDATE_DIRECT, window as i64, pixmap as i64, frames as i64, time_ns],
        // Only for frames::Router; Java counts the ContentUpdate that follows.
        Notify::Output(_) | Notify::Kill(_) | Notify::ContentFrame(_) => return None,
    })
}

/// Flat array of the events, in order. A content update for a window that already has one
/// pending is dropped: a game updates its window every frame, and the consumer only needs to know
/// that the pixels changed since it last looked. Everything else keeps its order and count.
pub fn encode_events(events: &[Notify]) -> Vec<i64> {
    let mut out = Vec::with_capacity(events.len() * EVENT_STRIDE);
    let mut pending: std::collections::HashSet<u32> = std::collections::HashSet::new();
    for n in events {
        if let Notify::ContentUpdate(w) = n {
            if !pending.insert(*w) {
                continue;
            }
        }
        if let Some(e) = encode_event(n) {
            out.extend_from_slice(&e);
        }
    }
    out
}

fn window_flags(server: &Server, id: u32, w: &crate::proto::Window) -> i64 {
    (b(w.attrs.mapped) * WF_MAPPED)
        | (b(server.map_state(id) == 2) * WF_VIEWABLE)
        | (b(w.attrs.override_redirect) * WF_OVERRIDE_REDIRECT)
        | (b(w.has_content) * WF_HAS_CONTENT)
        | (b(w.composite_redirected) * WF_COMPOSITE_REDIRECTED)
}

/// Every window, parents before children, children bottom-most first (paint order):
/// `[id, parent (0 for the root), x, y, width, height, flags, border_width]` per window.
pub fn snapshot_tree(server: &Server) -> Vec<i64> {
    let mut out = Vec::with_capacity(server.windows.len() * WINDOW_STRIDE);
    let mut stack = vec![server.root];
    // An explicit stack keeps paint order without recursion (client-built trees can be deep).
    let mut order: Vec<u32> = Vec::new();
    while let Some(id) = stack.pop() {
        order.push(id);
        if let Some(w) = server.windows.get(&id) {
            for &c in w.children.iter().rev() {
                stack.push(c);
            }
        }
    }
    for id in order {
        let Some(w) = server.windows.get(&id) else { continue };
        let flags = window_flags(server, id, w);
        out.extend_from_slice(&[
            id as i64,
            w.parent.unwrap_or(0) as i64,
            w.x as i64,
            w.y as i64,
            w.width as i64,
            w.height as i64,
            flags,
            w.border_width as i64,
        ]);
    }
    out
}

// ---------------------------------------------------------------------------- queries
//
// Read-only questions the Java side asks about the server's state (window properties, the cursor
// under the pointer, a window's icon). Pure code over `Server`, so they are unit tested on the host.

/// Index of the fields of the ICCCM `WM_HINTS` property (32-bit values).
const WM_HINTS_ICON_PIXMAP: usize = 3;
const WM_HINTS_ICON_MASK: usize = 7;
const WM_HINTS_WINDOW_GROUP: usize = 8;

/// 32-bit value number `index` of a window property (Java `Property.getInt`); a byte for an 8-bit
/// property (`index` counts bytes then). 0 if the window or property does not exist or is too short.
pub fn property_int(server: &Server, window: u32, atom_name: &str, index: usize) -> i32 {
    let id = server.atom_id(atom_name);
    if id < 0 {
        return 0;
    }
    let Some(p) = server.windows.get(&window).and_then(|w| w.properties.get(&(id as u32))) else { return 0 };
    if p.format == 8 {
        return p.data.get(index).map(|b| *b as i32).unwrap_or(0);
    }
    match p.data.get(index * 4..index * 4 + 4) {
        Some(b) => i32::from_le_bytes([b[0], b[1], b[2], b[3]]),
        None => 0,
    }
}

/// Java `Window.isApplicationWindow`: mapped, its own window group, bigger than 1x1.
pub fn is_application_window(server: &Server, window: u32) -> bool {
    let Some(w) = server.windows.get(&window) else { return false };
    w.attrs.mapped && property_int(server, window, "WM_HINTS", WM_HINTS_WINDOW_GROUP) as u32 == window && w.width > 1 && w.height > 1
}

/// Java `WindowManager.findWindowByProcessName`: the first window (lowest id) whose WM_CLASS
/// contains the executable name without `.exe`, case-insensitively.
pub fn find_window_by_class(server: &Server, exe_name: &str) -> Option<u32> {
    let base = exe_name.to_lowercase().replace(".exe", "");
    let mut ids: Vec<u32> = server.windows.keys().copied().collect();
    ids.sort_unstable();
    ids.into_iter().find(|&id| {
        let class = server.property_string(id, "WM_CLASS").to_lowercase();
        !class.is_empty() && class.contains(&base)
    })
}

/// The pixels of a drawable as tightly packed R,G,B,A bytes (what an Android Bitmap holds).
pub fn drawable_rgba(d: &crate::proto::gfx::Drawable, mask: Option<&crate::proto::gfx::Drawable>) -> Option<Vec<u8>> {
    let (w, h) = (d.width as usize, d.height as usize);
    if w == 0 || h == 0 {
        return None;
    }
    let mut out = vec![0u8; w * h * 4];
    let mut ok = false;
    d.store.with_surface(&mut |cs| match mask {
        Some(m) => {
            m.store.with_surface(&mut |ms| ok = crate::pixels::to_bitmap(cs, Some(ms), &mut out, w * 4, w, h));
        }
        None => ok = crate::pixels::to_bitmap(cs, None, &mut out, w * 4, w, h),
    });
    ok.then_some(out)
}

/// The icon of a window (its WM_HINTS icon pixmap and mask): `(width, height, RGBA bytes)`.
pub fn window_icon(server: &Server, window: u32) -> Option<(i32, i32, Vec<u8>)> {
    let pixmap = |id: i32| -> Option<&crate::proto::gfx::Drawable> {
        let id = id as u32;
        if id != 0 && server.pixmaps.contains(&id) {
            server.drawables.get(&id)
        } else {
            None
        }
    };
    let color = pixmap(property_int(server, window, "WM_HINTS", WM_HINTS_ICON_PIXMAP))?;
    let mask = pixmap(property_int(server, window, "WM_HINTS", WM_HINTS_ICON_MASK));
    // A mask of another size cannot be applied: show the icon without it.
    let rgba = drawable_rgba(color, mask).or_else(|| drawable_rgba(color, None))?;
    Some((color.width as i32, color.height as i32, rgba))
}

/// The X cursor under the pointer. `None`: no window up to the root has one (the consumer shows
/// its own default arrow).
#[derive(Debug, PartialEq, Eq)]
pub struct CursorState {
    pub serial: u64,
    pub hot_x: i16,
    pub hot_y: i16,
    pub visible: bool,
    pub width: i16,
    pub height: i16,
}

pub fn pointer_cursor(server: &Server) -> Option<CursorState> {
    let id = server.effective_cursor(server.input.point_window)?;
    let c = server.cursors.get(&id)?;
    Some(CursorState { serial: c.serial, hot_x: c.hot_x, hot_y: c.hot_y, visible: c.visible, width: c.image.width, height: c.image.height })
}

/// Image of the cursor under the pointer, R,G,B,A bytes.
pub fn pointer_cursor_image(server: &Server) -> Option<Vec<u8>> {
    let id = server.effective_cursor(server.input.point_window)?;
    drawable_rgba(&server.cursors.get(&id)?.image, None)
}

/// One window as in [`snapshot_tree`] (`WINDOW_STRIDE` values), or empty if it does not exist.
pub fn window_info(server: &Server, id: u32) -> Vec<i64> {
    let Some(w) = server.windows.get(&id) else { return Vec::new() };
    vec![
        id as i64,
        w.parent.unwrap_or(0) as i64,
        w.x as i64,
        w.y as i64,
        w.width as i64,
        w.height as i64,
        window_flags(server, id, w),
        w.border_width as i64,
    ]
}

/// The children of a window, bottom-most first.
pub fn window_children(server: &Server, id: u32) -> Vec<i64> {
    server.windows.get(&id).map(|w| w.children.iter().map(|&c| c as i64).collect()).unwrap_or_default()
}

/// Java `Window.disableAllDescendants`: the window and everything below it stop receiving input
/// events (the renderer hides them; a client must not see input aimed at something invisible).
pub fn disable_subtree(server: &mut Server, id: u32) {
    let mut stack = vec![id];
    while let Some(w) = stack.pop() {
        if let Some(win) = server.windows.get_mut(&w) {
            win.attrs.enabled = false;
            stack.extend(win.children.iter().copied());
        }
    }
}

/// Pointer position packed as `(x << 16) | (y & 0xffff)` (x is signed).
pub fn pack_point(x: i16, y: i16) -> i64 {
    ((x as i64) << 16) | (y as u16 as i64)
}
