//! The core request set, one handler per opcode of the Java `XClientRequestHandler` switch.
//!
//! Error conventions match the Java server: `Err(Fail::X)` becomes an X error packet,
//! `Err(Fail::Drop)` drops the request silently (Wine would die on an unexpected X error).

use super::consts::{err, ev, op};
use super::cursor_font;
use super::events::Event;
use super::gfx::{self, Gc};
use super::input::Keyboard;
use super::wire::{Reader, Writer};
use super::{bad, ClientId, Fail, Notify, Owned, Res, Server};

/// An X extension implemented outside the core (BIG-REQUESTS here, the rest in Phase 3).
pub trait Extension: Send {
    fn name(&self) -> &str;
    fn major_opcode(&self) -> u8;
    fn first_event(&self) -> u8 {
        0
    }
    fn first_error(&self) -> u8 {
        0
    }
    /// `data` is the second byte of the request header (the minor opcode), `body` the rest.
    fn handle(&mut self, server: &mut Server, client: ClientId, data: u8, body: &[u8]) -> Res;
    fn on_client_disconnected(&mut self, _server: &mut Server, _client: ClientId) {}
    /// Something happened in the core ([`super::hooks::Hook`]).
    fn on_hook(&mut self, _server: &mut Server, _hook: &super::hooks::Hook) {}
    /// A timer set with [`Server::schedule`] is due.
    fn on_timer(&mut self, _server: &mut Server, _token: u64) {}
}

pub const BIG_REQUESTS_MAJOR: u8 = 156; // Java: (byte)-100
const MAX_BIG_REQUEST_LENGTH: u32 = 4194303;

pub struct BigRequests;

impl Extension for BigRequests {
    fn name(&self) -> &str {
        "BIG-REQUESTS"
    }
    fn major_opcode(&self) -> u8 {
        BIG_REQUESTS_MAJOR
    }
    fn handle(&mut self, server: &mut Server, client: ClientId, _data: u8, _body: &[u8]) -> Res {
        server.reply(client, 0, 0, |w| {
            w.u32(MAX_BIG_REQUEST_LENGTH).pad(20);
        });
        Ok(())
    }
}

const A_BG_PIXMAP: u32 = 1 << 0;
const A_BG_PIXEL: u32 = 1 << 1;
const A_BIT_GRAVITY: u32 = 1 << 4;
const A_WIN_GRAVITY: u32 = 1 << 5;
const A_BACKING_STORE: u32 = 1 << 6;
const A_BACKING_PLANES: u32 = 1 << 7;
const A_BACKING_PIXEL: u32 = 1 << 8;
const A_OVERRIDE_REDIRECT: u32 = 1 << 9;
const A_SAVE_UNDER: u32 = 1 << 10;
const A_EVENT_MASK: u32 = 1 << 11;
const A_DO_NOT_PROPAGATE: u32 = 1 << 12;
const A_CURSOR: u32 = 1 << 14;

fn bit_iter(mask: u32) -> impl Iterator<Item = u32> {
    (0..32).map(|b| 1u32 << b).filter(move |f| mask & f != 0)
}

fn pad4(n: usize) -> usize {
    (4 - n % 4) % 4
}

impl Server {
    // ------------------------------------------------------------------ output helpers

    /// A reply: `1, data, seq, length` followed by what `f` writes.
    pub fn reply<F: FnOnce(&mut Writer)>(&mut self, client: ClientId, data: u8, length_words: u32, f: F) {
        let Some(c) = self.clients.get_mut(client).and_then(|c| c.as_mut()) else { return };
        let (seq, order) = (c.seq, c.order);
        let mut w = Writer::new(&mut c.out, order);
        w.u8(1).u8(data).u16(seq).u32(length_words);
        f(&mut w);
        self.notifications.push(Notify::Output(client));
    }

    /// Writes an event of an extension (any length) to the client: `f(writer, sequence)`.
    pub fn write_event<F: FnOnce(&mut Writer, u16)>(&mut self, client: ClientId, f: F) {
        let Some(c) = self.clients.get_mut(client).and_then(|c| c.as_mut()) else { return };
        let (seq, order) = (c.seq, c.order);
        f(&mut Writer::new(&mut c.out, order), seq);
        self.notifications.push(Notify::Output(client));
    }

    pub fn send_error(&mut self, client: ClientId, code: u8, data: u32, minor: u8, major: u8) {
        let Some(c) = self.clients.get_mut(client).and_then(|c| c.as_mut()) else { return };
        let (seq, order) = (c.seq, c.order);
        let mut w = Writer::new(&mut c.out, order);
        w.u8(0).u8(code).u16(seq).u32(data).u16(minor as u16).u8(major).pad(21);
        self.notifications.push(Notify::Output(client));
    }

    /// Registers an extension (names are listed ordered by major opcode as signed byte, as the Java `SparseArray` did).
    pub fn register_extension(&mut self, ext: Box<dyn Extension>) {
        let info = super::ExtInfo { name: ext.name().to_string(), major_opcode: ext.major_opcode(), first_event: ext.first_event(), first_error: ext.first_error() };
        self.extensions.retain(|e| e.major_opcode != info.major_opcode);
        self.extensions.push(info.clone());
        self.extensions.sort_by_key(|e| e.major_opcode as i8);
        self.ext_handlers.insert(info.major_opcode, ext);
    }

    // ------------------------------------------------------------------ dispatcher

    /// Handles one request. `body` is everything after the request header (4 bytes, or 8 for a big request).
    pub fn handle_request(&mut self, c: ClientId, opcode: u8, data: u8, body: &[u8]) -> Res {
        let order = match self.client_mut(c) {
            Some(cl) => {
                cl.request_data = data;
                cl.order
            }
            None => return Ok(()),
        };
        let mut r = Reader::new(body, order);
        let r = &mut r;
        match opcode {
            op::CREATE_WINDOW => self.r_create_window(c, data, r),
            op::CHANGE_WINDOW_ATTRIBUTES => self.r_change_window_attributes(c, r),
            op::GET_WINDOW_ATTRIBUTES => self.r_get_window_attributes(c, r),
            op::DESTROY_WINDOW => {
                let id = r.u32()?;
                self.destroy_window(id);
                Ok(())
            }
            op::DESTROY_SUB_WINDOWS => {
                // Children, not the window itself, bottom to top.
                let id = r.u32()?;
                if let Some(w) = self.windows.get(&id) {
                    for child in w.children.clone() {
                        self.destroy_window(child);
                    }
                }
                Ok(())
            }
            op::REPARENT_WINDOW => {
                let (id, parent) = (r.u32()?, r.u32()?);
                let (x, y) = (r.i16()?, r.i16()?);
                self.need_window(id)?;
                self.need_window(parent)?;
                // A window cannot become its own descendant (and the root cannot move).
                let mut cur = Some(parent);
                while let Some(w) = cur {
                    if w == id {
                        return Err(bad(err::BAD_MATCH, parent));
                    }
                    cur = self.windows.get(&w).and_then(|w| w.parent);
                }
                if id == self.root {
                    return Err(bad(err::BAD_MATCH, id));
                }
                self.reparent_window(id, parent, x, y);
                Ok(())
            }
            op::MAP_WINDOW => {
                let id = r.u32()?;
                self.need_window(id)?;
                self.map_window(id);
                Ok(())
            }
            op::MAP_SUB_WINDOWS => {
                let id = r.u32()?;
                self.need_window(id)?;
                self.map_sub_windows(id)
            }
            op::UNMAP_WINDOW => {
                let id = r.u32()?;
                self.need_window(id)?;
                self.unmap_window(id);
                Ok(())
            }
            op::CONFIGURE_WINDOW => self.r_configure_window(r),
            op::GET_GEOMETRY => self.r_get_geometry(c, r),
            op::QUERY_TREE => self.r_query_tree(c, r),
            op::INTERN_ATOM => self.r_intern_atom(c, data, r),
            op::GET_ATOM_NAME => self.r_get_atom_name(c, r),
            op::CHANGE_PROPERTY => self.r_change_property(data, r),
            op::DELETE_PROPERTY => {
                let id = r.u32()?;
                self.need_window(id)?;
                let atom = r.u32()?;
                self.remove_property(id, atom);
                Ok(())
            }
            op::GET_PROPERTY => self.r_get_property(c, data, r),
            op::SET_SELECTION_OWNER => self.r_set_selection_owner(c, r),
            op::GET_SELECTION_OWNER => self.r_get_selection_owner(c, r),
            op::CONVERT_SELECTION => self.r_convert_selection(r),
            op::SEND_EVENT => self.r_send_event(r),
            op::GRAB_POINTER => self.r_grab_pointer(c, data, r),
            op::UNGRAB_POINTER => {
                self.deactivate_pointer_grab();
                Ok(())
            }
            op::GRAB_SERVER => {
                self.server_grab = Some(c);
                Ok(())
            }
            op::UNGRAB_SERVER => {
                if self.server_grab == Some(c) {
                    self.server_grab = None;
                }
                Ok(())
            }
            op::QUERY_POINTER => self.r_query_pointer(c, r),
            op::TRANSLATE_COORDINATES => self.r_translate_coordinates(c, r),
            op::WARP_POINTER => self.r_warp_pointer(r),
            op::SET_INPUT_FOCUS => self.r_set_input_focus(data, r),
            op::GET_INPUT_FOCUS => {
                let (focus, revert) = (self.focus.unwrap_or(0), self.focus_revert);
                self.reply(c, revert, 0, |w| {
                    w.u32(focus).pad(20);
                });
                Ok(())
            }
            op::QUERY_KEYMAP => {
                self.reply(c, 0, 2, |w| {
                    w.pad(32);
                });
                Ok(())
            }
            op::OPEN_FONT => {
                r.skip(4)?;
                let len = r.u16()? as usize;
                r.skip(2)?;
                let name = r.string8(len)?;
                if name != "cursor" {
                    return Err(Fail::Drop("OpenFont supports only the cursor font"));
                }
                Ok(())
            }
            op::LIST_FONTS => {
                self.reply(c, 0, 0, |w| {
                    w.u16(0).pad(22);
                });
                Ok(())
            }
            op::CREATE_PIXMAP => self.r_create_pixmap(c, data, r),
            op::FREE_PIXMAP => {
                let id = r.u32()?;
                self.free_pixmap(id);
                Ok(())
            }
            op::CREATE_GC => self.r_create_gc(c, r),
            op::CHANGE_GC => self.r_change_gc(r),
            op::COPY_GC => self.r_copy_gc(r),
            op::FREE_GC => {
                let id = r.u32()?;
                if self.gcs.remove(&id).is_some() {
                    self.forget_owned_pub(Owned::Gc(id));
                }
                Ok(())
            }
            op::COPY_AREA => self.r_copy_area(r),
            op::POLY_LINE => self.r_poly_line(data, r),
            op::POLY_FILL_RECTANGLE => self.r_poly_fill_rectangle(r),
            op::POLY_SEGMENT => self.r_poly_segment(r),
            op::POLY_RECTANGLE => self.r_poly_rectangle(r),
            op::PUT_IMAGE => self.r_put_image(data, r),
            op::GET_IMAGE => self.r_get_image(c, data, r),
            op::CREATE_CURSOR => self.r_create_cursor(c, r),
            op::CREATE_GLYPH_CURSOR => self.r_create_glyph_cursor(c, r),
            op::RECOLOR_CURSOR => self.r_recolor_cursor(r),
            op::CLEAR_AREA => self.r_clear_area(data, r),
            op::FREE_CURSOR => {
                let id = r.u32()?;
                self.free_cursor(id);
                Ok(())
            }
            op::QUERY_EXTENSION => self.r_query_extension(c, r),
            op::GET_KEYBOARD_MAPPING => self.r_get_keyboard_mapping(c, r),
            op::GET_SCREEN_SAVER => {
                self.reply(c, 0, 0, |w| {
                    w.u16(600).u16(600).u8(1).u8(1).pad(18);
                });
                Ok(())
            }
            op::GET_MODIFIER_MAPPING => {
                self.reply(c, 1, 2, |w| {
                    w.pad(24).pad(8);
                });
                Ok(())
            }
            op::GET_POINTER_MAPPING => {
                // Identity map for every button (Wine reads past a shorter table).
                let n = super::consts::MAX_BUTTONS;
                let pad = pad4(n as usize);
                self.reply(c, n, ((n as usize + pad) / 4) as u32, |w| {
                    w.pad(24);
                    for i in 1..=n {
                        w.u8(i);
                    }
                    w.pad(pad);
                });
                Ok(())
            }
            // No reply, nothing to do (SetDashes also lands here: opcode 58 is not CopyGC).
            op::SET_CLIP_RECTANGLES | op::CREATE_COLORMAP | op::FREE_COLORMAP | op::BELL | op::SET_SCREEN_SAVER | op::FORCE_SCREEN_SAVER | op::NO_OPERATION | 58 => Ok(()),
            o if o >= 128 => self.dispatch_extension(c, o, data, body),
            o => {
                if self.fallback_request(c, o, r)? {
                    return Ok(());
                }
                // Unknown request without a reply: ignored, never answered with an error.
                crate::log::warn(&format!("unsupported opcode {o}, ignored"));
                Ok(())
            }
        }
    }

    fn dispatch_extension(&mut self, c: ClientId, opcode: u8, data: u8, body: &[u8]) -> Res {
        match self.with_ext(opcode, |ext, s| ext.handle(s, c, data, body)) {
            Some(Ok(res)) => res,
            Some(Err(())) => Err(Fail::Drop("extension handler panicked")),
            None => {
                crate::log::warn(&format!("unsupported extension opcode {opcode}, ignored"));
                Ok(())
            }
        }
    }

    pub fn forget_owned_pub(&mut self, res: Owned) {
        for c in self.clients.iter_mut().flatten() {
            c.owned.retain(|o| *o != res);
        }
    }

    fn need_window(&self, id: u32) -> Res {
        if self.windows.contains_key(&id) {
            Ok(())
        } else {
            Err(bad(err::BAD_WINDOW, id))
        }
    }

    fn need_drawable(&self, id: u32) -> Res {
        if self.drawables.contains_key(&id) {
            Ok(())
        } else {
            Err(bad(err::BAD_DRAWABLE, id))
        }
    }

    fn need_gc(&self, id: u32) -> Res {
        if self.gcs.contains_key(&id) {
            Ok(())
        } else {
            Err(bad(err::BAD_GC, id))
        }
    }

    // ------------------------------------------------------------------ windows

    fn r_create_window(&mut self, c: ClientId, depth: u8, r: &mut Reader) -> Res {
        let (id, parent) = (r.u32()?, r.u32()?);
        if !self.is_valid_resource_id(c, id) {
            return Err(bad(err::BAD_ID_CHOICE, id));
        }
        self.need_window(parent)?;
        let (x, y, w, h, border) = (r.i16()?, r.i16()?, r.i16()?, r.i16()?, r.i16()?);
        let class = r.i16()? as i8;
        if !(0..=2).contains(&class) {
            return Err(Fail::Drop("bad window class"));
        }
        let visual = r.u32()?;
        let mask = r.u32()?;
        let visual = self.visual_by_id(visual).map(|v| v.id);
        self.create_window(c, id, parent, x, y, w, h, class as u8, visual, depth)?;
        self.windows.get_mut(&id).unwrap().border_width = border;
        if mask != 0 {
            if let Err(e) = self.update_window_attributes(c, id, mask, r) {
                // The window exists by now but nobody owns it yet: take it back, or it stays in
                // the tree forever and its id can never be used again.
                self.destroy_window(id);
                return Err(e);
            }
        }
        let em = self.windows[&id].attrs.event_mask;
        self.set_event_listener(c, id, em);
        self.register_owner(c, Owned::Window(id));
        let win = &self.windows[&id];
        let ev = Event::CreateNotify { parent, window: id, x: win.x, y: win.y, w: win.width, h: win.height, border: win.border_width, override_redirect: win.attrs.override_redirect };
        self.send_to_window(parent, ev::SUBSTRUCTURE_NOTIFY, &ev);
        Ok(())
    }

    /// WindowAttributes.update
    fn update_window_attributes(&mut self, c: ClientId, id: u32, mask: u32, r: &mut Reader) -> Res {
        for flag in bit_iter(mask & 0x7fff) {
            match flag {
                A_BG_PIXEL => {
                    let color = r.u32()? as i32;
                    let has = self.windows.get(&id).map(|w| w.has_content).unwrap_or(false);
                    if !has {
                        return Err(Fail::Drop("background pixel on an InputOnly window"));
                    }
                    if let Some(d) = self.drawables.get(&id) {
                        d.fill_color(color);
                    }
                    // ClearArea paints with it again.
                    self.windows.get_mut(&id).unwrap().attrs.background_pixel = Some(color as u32);
                    self.content_changed(id);
                }
                // A background pixmap replaces the pixel (ClearArea does not tile it).
                A_BG_PIXMAP => {
                    r.skip(4)?;
                    self.windows.get_mut(&id).unwrap().attrs.background_pixel = None;
                }
                A_BACKING_PIXEL => self.windows.get_mut(&id).unwrap().attrs.backing_pixel = r.u32()?,
                A_BACKING_PLANES => self.windows.get_mut(&id).unwrap().attrs.backing_planes = r.u32()?,
                A_BIT_GRAVITY => {
                    let v = r.u32()?;
                    if v > 10 {
                        return Err(Fail::Drop("bad bit gravity"));
                    }
                    self.windows.get_mut(&id).unwrap().attrs.bit_gravity = v as u8;
                }
                A_WIN_GRAVITY => {
                    let v = r.u32()?;
                    if v > 10 {
                        return Err(Fail::Drop("bad win gravity"));
                    }
                    self.windows.get_mut(&id).unwrap().attrs.win_gravity = v as u8;
                }
                A_BACKING_STORE => {
                    let v = r.u32()?;
                    if v > 2 {
                        return Err(Fail::Drop("bad backing store"));
                    }
                    self.windows.get_mut(&id).unwrap().attrs.backing_store = v as u8;
                }
                A_SAVE_UNDER => self.windows.get_mut(&id).unwrap().attrs.save_under = r.u32()? == 1,
                A_OVERRIDE_REDIRECT => self.windows.get_mut(&id).unwrap().attrs.override_redirect = r.u32()? == 1,
                A_EVENT_MASK => self.windows.get_mut(&id).unwrap().attrs.event_mask = r.u32()?,
                A_DO_NOT_PROPAGATE => self.windows.get_mut(&id).unwrap().attrs.do_not_propagate = r.u32()?,
                A_CURSOR => {
                    let cid = r.u32()?;
                    let new = if self.cursors.get(&cid).map(|cu| !cu.freed).unwrap_or(false) { Some(cid) } else { None };
                    let old = std::mem::replace(&mut self.windows.get_mut(&id).unwrap().attrs.cursor, new);
                    if let Some(o) = old {
                        self.gc_cursor(o);
                    }
                }
                // BACKGROUND_PIXMAP, BORDER_PIXMAP, BORDER_PIXEL, COLORMAP
                _ => r.skip(4)?,
            }
        }
        let _ = c;
        self.notifications.push(Notify::Attributes { window: id, mask });
        Ok(())
    }

    fn r_change_window_attributes(&mut self, c: ClientId, r: &mut Reader) -> Res {
        let id = r.u32()?;
        let mask = r.u32()?;
        self.need_window(id)?;
        if mask == 0 {
            return Ok(());
        }
        self.update_window_attributes(c, id, mask, r)?;
        if mask & (1 << 11) != 0 {
            // The attributes already hold the new mask here, as in the Java server.
            let can = |s: &Server, bit: u32| {
                let w = &s.windows[&id];
                w.attrs.event_mask & bit == 0 || !(s.has_listener_for(id, bit) && s.client_event_mask(c, id) & bit == 0)
            };
            if can(self, ev::SUBSTRUCTURE_REDIRECT) && can(self, ev::RESIZE_REDIRECT) && can(self, ev::BUTTON_PRESS) {
                let em = self.windows[&id].attrs.event_mask;
                self.set_event_listener(c, id, em);
            } else {
                return Err(bad(err::BAD_ACCESS, 0));
            }
        }
        Ok(())
    }

    fn r_get_window_attributes(&mut self, c: ClientId, r: &mut Reader) -> Res {
        let id = r.u32()?;
        self.need_window(id)?;
        let w = &self.windows[&id];
        let visual = if w.has_content { self.drawables.get(&id).and_then(|d| d.visual).ok_or(Fail::Drop("window without visual"))? } else { 0 };
        let a = w.attrs.clone();
        let map_state = self.map_state(id);
        let all = self.all_event_masks(id);
        let mine = self.client_event_mask(c, id);
        self.reply(c, a.backing_store, 3, |wr| {
            wr.u32(visual).u16(a.class as u16).u8(a.bit_gravity).u8(a.win_gravity).u32(a.backing_planes).u32(a.backing_pixel);
            wr.u8(a.save_under as u8).u8(1).u8(map_state).u8(a.override_redirect as u8).u32(0).u32(all).u32(mine).u16(a.do_not_propagate as u16).u16(0);
        });
        Ok(())
    }

    fn map_sub_windows(&mut self, id: u32) -> Res {
        // Every child of the window, top-most first; neither the window itself nor the
        // grandchildren (each child is mapped, and its own children are mapped or not as they were).
        let children = self.windows.get(&id).map(|w| w.children.clone()).unwrap_or_default();
        for ch in children.into_iter().rev() {
            self.map_window(ch);
        }
        Ok(())
    }

    fn r_configure_window(&mut self, r: &mut Reader) -> Res {
        let id = r.u32()?;
        self.need_window(id)?;
        let mask = r.i16()? as i32 as u32;
        r.skip(2)?;
        if mask == 0 {
            return Ok(());
        }
        let (mut x, mut y, mut w, mut h, mut border) = {
            let win = &self.windows[&id];
            (win.x, win.y, win.width, win.height, win.border_width)
        };
        let mut sibling = None;
        let mut stack = None;
        for flag in bit_iter(mask) {
            match flag {
                1 => x = r.i32()? as i16,
                2 => y = r.i32()? as i16,
                4 => w = r.i32()? as i16,
                8 => h = r.i32()? as i16,
                16 => border = r.i32()? as i16,
                32 => {
                    let s = r.u32()?;
                    sibling = self.windows.contains_key(&s).then_some(s);
                }
                64 => {
                    let m = r.u32()?;
                    if m > 4 {
                        return Err(Fail::Drop("bad stack mode"));
                    }
                    stack = Some(m as u8);
                }
                _ => {}
            }
        }
        self.configure_window(id, mask, x, y, w, h, border, sibling, stack);
        Ok(())
    }

    fn r_get_geometry(&mut self, c: ClientId, r: &mut Reader) -> Res {
        let id = r.u32()?;
        let d = self.drawables.get(&id).ok_or(bad(err::BAD_DRAWABLE, id))?;
        // A depth the server has no visual for (a pixmap) still has a geometry.
        let (depth, dw, dh) = (d.depth, d.width, d.height);
        let (x, y, bw) = self.windows.get(&id).map(|w| (w.x, w.y, w.border_width)).unwrap_or((0, 0, 0));
        let root = self.root;
        self.reply(c, depth, 0, |w| {
            w.u32(root).i16(x).i16(y).i16(dw).i16(dh).i16(bw).pad(10);
        });
        Ok(())
    }

    fn r_query_tree(&mut self, c: ClientId, r: &mut Reader) -> Res {
        let id = r.u32()?;
        self.need_window(id)?;
        let win = &self.windows[&id];
        let parent = win.parent.unwrap_or(0);
        // Bottom-most first, as the protocol says (`children` is kept in that order).
        let children: Vec<u32> = win.children.clone();
        let root = self.root;
        self.reply(c, 0, children.len() as u32, |w| {
            w.u32(root).u32(parent).u16(children.len() as u16).pad(14);
            for ch in &children {
                w.u32(*ch);
            }
        });
        Ok(())
    }

    // ------------------------------------------------------------------ atoms, properties, selections

    fn r_intern_atom(&mut self, c: ClientId, only_if_exists: u8, r: &mut Reader) -> Res {
        let len = r.u16()? as usize;
        r.skip(2)?;
        let name = r.string8(len)?;
        let id = if only_if_exists == 1 {
            // Unknown name: the reply carries atom None (0), as the protocol says. The Java server
            // sent BadAtom here, which Wine's Xlib error handler treats as fatal.
            self.atom_id(&name).max(0) as u32
        } else {
            self.intern_atom(&name)
        };
        self.reply(c, 0, 0, |w| {
            w.u32(id).pad(20);
        });
        Ok(())
    }

    fn r_get_atom_name(&mut self, c: ClientId, r: &mut Reader) -> Res {
        let id = r.u32()?;
        if !self.atom_is_valid(id) {
            return Err(bad(err::BAD_ATOM, id));
        }
        let name = self.atoms[id as usize].clone();
        let len = name.chars().count();
        self.reply(c, 0, ((len + 3) / 4) as u32, |w| {
            w.u16(len as u16).pad(22).string8(&name);
        });
        Ok(())
    }

    fn r_change_property(&mut self, mode: u8, r: &mut Reader) -> Res {
        if mode > 2 {
            return Err(Fail::Drop("bad property mode"));
        }
        let id = r.u32()?;
        self.need_window(id)?;
        let atom = r.u32()?;
        let ty = r.u32()?;
        let format = r.u8()?;
        if !matches!(format, 8 | 16 | 32) {
            return Err(Fail::Drop("bad property format"));
        }
        r.skip(3)?;
        let len = r.u32()? as usize;
        let total = len.checked_mul((format >> 3) as usize).ok_or(Fail::Drop("property too large"))?;
        let data = if total > 0 { r.bytes(total)?.to_vec() } else { Vec::new() };
        if !self.modify_property(id, atom, ty, format, mode, data) {
            return Err(bad(err::BAD_MATCH, 0));
        }
        self.notifications.push(Notify::Property { window: id, atom });
        Ok(())
    }

    fn r_get_property(&mut self, c: ClientId, delete: u8, r: &mut Reader) -> Res {
        let id = r.u32()?;
        self.need_window(id)?;
        let (atom, ty, long_offset, long_length) = (r.u32()?, r.u32()?, r.u32()?, r.u32()?);
        let prop = self.windows[&id].properties.get(&atom).cloned();
        // The property is deleted only when this request returned all of it, and of the right type.
        let mut delete_it = false;
        match &prop {
            None => self.reply(c, 0, 0, |w| {
                w.u32(0).u32(0).u32(0).pad(12);
            }),
            Some(p) if p.type_ != ty && ty != 0 => {
                // bytes_after is the whole property size (the Java server always said 0).
                let (fmt, pty, size) = (p.format, p.type_, p.data.len() as u32);
                self.reply(c, fmt, 0, |w| {
                    w.u32(pty).u32(size).u32(0).pad(12);
                })
            }
            Some(p) => {
                // The request counts 32-bit units as unsigned; Java's int arithmetic wrapped for huge lengths.
                let offset = long_offset as u64 * 4;
                if offset > p.data.len() as u64 {
                    return Err(bad(err::BAD_VALUE, long_offset));
                }
                let offset = offset as usize;
                let length = (p.data.len() - offset).min((long_length as u64 * 4).min(usize::MAX as u64) as usize);
                let bytes_after = p.data.len() - (offset + length);
                delete_it = bytes_after == 0;
                let item = (p.format / 8) as usize;
                let (fmt, pty) = (p.format, p.type_);
                let slice = &p.data[offset..offset + length];
                self.reply(c, fmt, ((length + 3) / 4) as u32, |w| {
                    w.u32(pty).u32(bytes_after as u32).u32((length / item) as u32).pad(12).bytes(slice).pad(pad4(length));
                });
            }
        }
        if delete == 1 && delete_it {
            self.remove_property(id, atom);
        }
        Ok(())
    }

    fn r_set_selection_owner(&mut self, c: ClientId, r: &mut Reader) -> Res {
        let (win, atom, time) = (r.u32()?, r.u32()?, r.u32()?);
        // Window `None` (0) gives the selection up.
        if win != 0 {
            self.need_window(win)?;
        }
        if !self.atom_is_valid(atom) {
            return Err(bad(err::BAD_ATOM, atom));
        }
        let time = if time == 0 { self.now() } else { time };
        let sel = self.selections.entry(atom).or_default();
        let (old_owner, old_client) = (sel.owner, sel.client);
        sel.owner = (win != 0).then_some(win);
        sel.client = (win != 0).then_some(c);
        // The previous owner is told it lost the selection (as the reference server does: when
        // the selection is given up, or goes to another client); the event names *its* window.
        if let (Some(old_win), Some(old)) = (old_owner, old_client) {
            if win == 0 || old != c {
                self.send_to_client(old, &Event::SelectionClear { time, owner: old_win, selection: atom });
            }
        }
        Ok(())
    }

    fn r_convert_selection(&mut self, r: &mut Reader) -> Res {
        let (requestor, selection, target, property, time) = (r.u32()?, r.u32()?, r.u32()?, r.u32()?, r.u32()?);
        self.need_window(requestor)?;
        for atom in [selection, target] {
            if !self.atom_is_valid(atom) {
                return Err(bad(err::BAD_ATOM, atom));
            }
        }
        if property != 0 && !self.atom_is_valid(property) {
            return Err(bad(err::BAD_ATOM, property));
        }
        let time = if time == 0 { self.now() } else { time };
        let owner = self.selections.get(&selection).and_then(|s| s.owner.zip(s.client));
        match owner {
            // Somebody owns it: ask them to convert it.
            Some((owner, client)) => self.send_to_client(client, &Event::SelectionRequest { time, owner, requestor, selection, target, property }),
            // Nobody does: the requestor is told so (property None).
            None => {
                if let Some(client) = self.windows[&requestor].origin {
                    self.send_to_client(client, &Event::SelectionNotify { time, requestor, selection, target, property: 0 });
                }
            }
        }
        Ok(())
    }

    fn r_get_selection_owner(&mut self, c: ClientId, r: &mut Reader) -> Res {
        let atom = r.u32()?;
        if !self.atom_is_valid(atom) {
            return Err(bad(err::BAD_ATOM, atom));
        }
        let owner = self.selections.get(&atom).and_then(|s| s.owner).unwrap_or(0);
        self.reply(c, 0, 0, |w| {
            w.u32(owner).pad(20);
        });
        Ok(())
    }

    // ------------------------------------------------------------------ send event / pointer / focus

    fn r_send_event(&mut self, r: &mut Reader) -> Res {
        let win = r.u32()?;
        let mask = r.u32()?;
        let mut data = [0u8; 32];
        data.copy_from_slice(r.bytes(32)?);
        if self.apply_net_wm_state(&data) {
            return Ok(());
        }
        if win == 0 || win == 1 {
            return Ok(());
        }
        self.need_window(win)?;
        let event = Event::Raw(data);
        if mask == 0 {
            match self.windows[&win].origin {
                Some(o) => self.send_to_client(o, &event),
                None => return Err(Fail::Drop("SendEvent to a window without a creator")),
            }
        } else {
            self.send_to_window_mask(win, mask, &event);
        }
        Ok(())
    }

    /// There is no window manager: a `_NET_WM_STATE` ClientMessage is applied here.
    fn apply_net_wm_state(&mut self, data: &[u8; 32]) -> bool {
        if data[0] & 0x7f != super::consts::code::CLIENT_MESSAGE || data[1] != 32 {
            return false;
        }
        let rd = |i: usize| i32::from_le_bytes([data[i], data[i + 1], data[i + 2], data[i + 3]]);
        let state_atom = self.atom_id("_NET_WM_STATE");
        if rd(8) as i64 != state_atom {
            return false;
        }
        let state_atom = state_atom as u32;
        let win = rd(4) as u32;
        match self.windows.get(&win) {
            Some(w) if w.parent == Some(self.root) => {}
            _ => return false,
        }
        let action = rd(12);
        let mut states: Vec<i32> = Vec::new();
        if let Some(p) = self.windows[&win].properties.get(&state_atom) {
            if p.format == 32 {
                states = p.data.chunks_exact(4).map(|c| i32::from_le_bytes([c[0], c[1], c[2], c[3]])).collect();
            }
        }
        let fullscreen = self.atom_id("_NET_WM_STATE_FULLSCREEN") as i32;
        let was_fullscreen = states.contains(&fullscreen);
        for index in [16usize, 20] {
            let atom = rd(index);
            if atom == 0 {
                continue;
            }
            let present = states.contains(&atom);
            let wanted = action == 1 || (action == 2 && !present);
            if wanted && !present {
                states.push(atom);
            } else if !wanted && present {
                states.retain(|a| *a != atom);
            }
        }
        let bytes: Vec<u8> = states.iter().flat_map(|a| a.to_le_bytes()).collect();
        let atom_ty = self.atom_id("ATOM") as u32;
        self.modify_property(win, state_atom, atom_ty, 32, 0, bytes);
        if !was_fullscreen && states.contains(&fullscreen) {
            let (sw, sh) = (self.screen_w, self.screen_h);
            self.set_window_geometry(win, 0, 0, sw, sh);
        }
        true
    }

    fn r_grab_pointer(&mut self, c: ClientId, owner_events: u8, r: &mut Reader) -> Res {
        if self.input.relative_mouse {
            // ALREADY_GRABBED; the request is skipped.
            self.reply(c, 1, 0, |w| {
                w.pad(24);
            });
            return Ok(());
        }
        let id = r.u32()?;
        self.need_window(id)?;
        // Java built the Bitmask from a *signed* short.
        let mask = r.i16()? as i32 as u32;
        let status = if self.input.grab.as_ref().map(|g| g.client != c).unwrap_or(false) {
            1
        } else if self.map_state(id) != 2 {
            3
        } else {
            self.grab_pointer(id, owner_events == 1, mask, c);
            0
        };
        self.reply(c, status, 0, |w| {
            w.pad(24);
        });
        Ok(())
    }

    fn r_query_pointer(&mut self, c: ClientId, r: &mut Reader) -> Res {
        let id = r.u32()?;
        self.need_window(id)?;
        let (rx, ry) = self.clamped_pointer();
        let child = self.child_by_coords(id, rx, ry).unwrap_or(0);
        let (lx, ly) = self.root_to_local(id, rx, ry);
        let same_screen = !self.input.relative_mouse as u8;
        let mask = self.key_but_mask() as u16;
        let root = self.root;
        self.reply(c, same_screen, 0, |w| {
            w.u32(root).u32(child).i16(rx).i16(ry).i16(lx).i16(ly).u16(mask).pad(6);
        });
        Ok(())
    }

    fn r_translate_coordinates(&mut self, c: ClientId, r: &mut Reader) -> Res {
        let (src, dst) = (r.u32()?, r.u32()?);
        let (sx, sy) = (r.i16()?, r.i16()?);
        self.need_window(src)?;
        self.need_window(dst)?;
        let (rx, ry) = self.local_to_root(src, sx, sy);
        let (lx, ly) = self.root_to_local(dst, rx, ry);
        let child = self.child_by_coords(dst, rx, ry).unwrap_or(0);
        self.reply(c, 1, 0, |w| {
            w.u32(child).i16(lx).i16(ly).pad(16);
        });
        Ok(())
    }

    fn r_warp_pointer(&mut self, r: &mut Reader) -> Res {
        if self.input.relative_mouse {
            return Ok(());
        }
        let (src, dst) = (r.u32()?, r.u32()?);
        let (sx, sy, mut sw, mut sh) = (r.i16()?, r.i16()?, r.i16()?, r.i16()?);
        let (dx, dy) = (r.i16()?, r.i16()?);
        if let Some(sw_) = self.windows.get(&src) {
            if sw == 0 {
                sw = sw_.width.wrapping_sub(sx);
            }
            if sh == 0 {
                sh = sw_.height.wrapping_sub(sy);
            }
            let (lx, ly) = self.root_to_local(src, self.input.x, self.input.y);
            let inside = lx >= sx && ly >= sy && (lx as i32) < sx as i32 + sw as i32 && (ly as i32) < sy as i32 + sh as i32;
            if !inside {
                return Ok(());
            }
        }
        // setX/setY: the pointer moves without producing events.
        if self.windows.contains_key(&dst) {
            let (rx, ry) = self.local_to_root(dst, dx, dy);
            self.set_pointer_silent(rx as i32, ry as i32);
        } else {
            let (x, y) = (self.input.x as i32 + dx as i32, self.input.y as i32 + dy as i32);
            self.set_pointer_silent(x, y);
        }
        Ok(())
    }

    fn r_set_input_focus(&mut self, revert: u8, r: &mut Reader) -> Res {
        if revert > 2 {
            return Err(Fail::Drop("bad revert-to"));
        }
        // `revert` says what happens when the window goes away; the focus itself is the window
        // field: None (0), PointerRoot (1, taken as the root) or a window.
        let id = r.u32()?;
        r.skip(4)?;
        let focus = match id {
            0 => None,
            1 => Some(self.root),
            w => {
                self.need_window(w)?;
                Some(w)
            }
        };
        self.focus = focus;
        self.focus_revert = revert;
        Ok(())
    }

    // ------------------------------------------------------------------ pixmaps, GCs, drawing

    fn r_create_pixmap(&mut self, c: ClientId, depth: u8, r: &mut Reader) -> Res {
        let (id, drawable) = (r.u32()?, r.u32()?);
        let (w, h) = (r.i16()?, r.i16()?);
        if !self.is_valid_resource_id(c, id) {
            return Err(bad(err::BAD_ID_CHOICE, id));
        }
        self.need_drawable(drawable)?;
        if self.drawables.contains_key(&id) {
            return Err(bad(err::BAD_ID_CHOICE, id));
        }
        let d = self.new_drawable(id, w, h, depth).ok_or(Fail::Drop("pixmap allocation failed"))?;
        self.drawables.insert(id, d);
        self.pixmaps.insert(id);
        self.register_owner(c, Owned::Pixmap(id));
        Ok(())
    }

    fn update_gc_from_stream(&mut self, id: u32, mask: u32, r: &mut Reader) -> Res {
        for flag in bit_iter(mask) {
            match flag {
                gfx::FLAG_FUNCTION => {
                    let f = r.u32()?;
                    if f > 15 {
                        return Err(Fail::Drop("bad GC function"));
                    }
                    self.gcs.get_mut(&id).unwrap().function = f as i32;
                }
                gfx::FLAG_PLANE_MASK => self.gcs.get_mut(&id).unwrap().plane_mask = r.i32()?,
                gfx::FLAG_FOREGROUND => self.gcs.get_mut(&id).unwrap().foreground = r.u32()?,
                gfx::FLAG_BACKGROUND => self.gcs.get_mut(&id).unwrap().background = r.u32()?,
                gfx::FLAG_LINE_WIDTH => self.gcs.get_mut(&id).unwrap().line_width = r.i32()?,
                gfx::FLAG_SUBWINDOW_MODE => {
                    let m = r.u32()?;
                    if m > 1 {
                        return Err(Fail::Drop("bad subwindow mode"));
                    }
                    self.gcs.get_mut(&id).unwrap().subwindow_mode = m as i32;
                }
                _ => r.skip(4)?,
            }
        }
        Ok(())
    }

    fn r_create_gc(&mut self, c: ClientId, r: &mut Reader) -> Res {
        let (id, drawable, mask) = (r.u32()?, r.u32()?, r.u32()?);
        if !self.is_valid_resource_id(c, id) {
            return Err(bad(err::BAD_ID_CHOICE, id));
        }
        self.need_drawable(drawable)?;
        if self.gcs.contains_key(&id) {
            return Err(bad(err::BAD_ID_CHOICE, id));
        }
        self.gcs.insert(id, Gc::new(id, drawable));
        self.register_owner(c, Owned::Gc(id));
        if mask != 0 {
            self.update_gc_from_stream(id, mask, r)?;
        }
        Ok(())
    }

    fn r_change_gc(&mut self, r: &mut Reader) -> Res {
        let (id, mask) = (r.u32()?, r.u32()?);
        self.need_gc(id)?;
        if mask != 0 {
            self.update_gc_from_stream(id, mask, r)?;
        }
        Ok(())
    }

    fn r_copy_gc(&mut self, r: &mut Reader) -> Res {
        let (src, dst, mask) = (r.u32()?, r.u32()?, r.u32()?);
        self.need_gc(src)?;
        self.need_gc(dst)?;
        let s = &self.gcs[&src];
        let (function, plane_mask, foreground, background, line_width, subwindow_mode) = (s.function, s.plane_mask, s.foreground, s.background, s.line_width, s.subwindow_mode);
        let d = self.gcs.get_mut(&dst).unwrap();
        for flag in bit_iter(mask) {
            match flag {
                gfx::FLAG_FUNCTION => d.function = function,
                gfx::FLAG_PLANE_MASK => d.plane_mask = plane_mask,
                gfx::FLAG_FOREGROUND => d.foreground = foreground,
                gfx::FLAG_BACKGROUND => d.background = background,
                gfx::FLAG_LINE_WIDTH => d.line_width = line_width,
                gfx::FLAG_SUBWINDOW_MODE => d.subwindow_mode = subwindow_mode,
                _ => {}
            }
        }
        Ok(())
    }

    fn r_copy_area(&mut self, r: &mut Reader) -> Res {
        let (src, dst, gc) = (r.u32()?, r.u32()?, r.u32()?);
        let (sx, sy, dx, dy, w, h) = (r.i16()?, r.i16()?, r.i16()?, r.i16()?, r.i16()?, r.i16()?);
        self.need_drawable(src)?;
        self.need_drawable(dst)?;
        self.need_gc(gc)?;
        let func = self.gcs[&gc].function;
        let (s, d) = (&self.drawables[&src], &self.drawables[&dst]);
        let (Some(_), Some(_)) = (s.visual, d.visual) else { return Err(Fail::Drop("drawable without visual")) };
        if s.depth != d.depth {
            return Err(bad(err::BAD_MATCH, 0));
        }
        let drew = gfx::copy_area(s, d, sx, sy, dx, dy, w, h, func);
        if drew {
            self.content_changed(dst);
        }
        Ok(())
    }

    fn r_poly_line(&mut self, mode: u8, r: &mut Reader) -> Res {
        if mode > 1 {
            return Err(Fail::Drop("bad coordinate mode"));
        }
        let (dr, gc) = (r.u32()?, r.u32()?);
        self.need_drawable(dr)?;
        self.need_gc(gc)?;
        let len = r.remaining();
        let mut pts = Vec::with_capacity(len / 2);
        for _ in 0..len / 4 {
            pts.push(r.i16()?);
            pts.push(r.i16()?);
        }
        let g = &self.gcs[&gc];
        if mode == 0 && g.line_width > 0 {
            let (fg, lw) = (g.foreground as i32, g.line_width);
            if self.drawables[&dr].draw_lines(fg, lw, &pts) {
                self.content_changed(dr);
            }
        }
        Ok(())
    }

    fn r_poly_fill_rectangle(&mut self, r: &mut Reader) -> Res {
        let (dr, gc) = (r.u32()?, r.u32()?);
        self.need_drawable(dr)?;
        self.need_gc(gc)?;
        let n = r.remaining() / 8;
        let mut rects = Vec::with_capacity(n * 4);
        for _ in 0..n * 4 {
            rects.push(r.i16()?);
        }
        let color = self.gcs[&gc].foreground as i32;
        if self.drawables[&dr].fill_rects(&rects, color) {
            self.content_changed(dr);
        }
        Ok(())
    }

    fn r_poly_segment(&mut self, r: &mut Reader) -> Res {
        let (dr, gc) = (r.u32()?, r.u32()?);
        self.need_drawable(dr)?;
        self.need_gc(gc)?;
        let n = r.remaining() / 8;
        let mut segs = Vec::with_capacity(n * 4);
        for _ in 0..n * 4 {
            segs.push(r.i16()?);
        }
        let g = &self.gcs[&gc];
        let (fg, lw) = (g.foreground as i32, g.line_width);
        if self.drawables[&dr].draw_segments(fg, lw, &segs) {
            self.content_changed(dr);
        }
        Ok(())
    }

    fn r_poly_rectangle(&mut self, r: &mut Reader) -> Res {
        let (dr, gc) = (r.u32()?, r.u32()?);
        self.need_drawable(dr)?;
        self.need_gc(gc)?;
        let n = r.remaining() / 8;
        let mut rects = Vec::with_capacity(n);
        for _ in 0..n {
            // x, y signed; width, height unsigned
            rects.push((r.i16()? as i32, r.i16()? as i32, r.u16()? as i32, r.u16()? as i32));
        }
        let g = &self.gcs[&gc];
        let (fg, lw) = (g.foreground as i32, g.line_width);
        if self.drawables[&dr].stroke_rects(&rects, fg, lw) {
            self.content_changed(dr);
        }
        Ok(())
    }

    fn r_put_image(&mut self, format: u8, r: &mut Reader) -> Res {
        if format > 2 {
            return Err(Fail::Drop("bad image format"));
        }
        let (dr, gc) = (r.u32()?, r.u32()?);
        let (w, h, dx, dy) = (r.i16()?, r.i16()?, r.i16()?, r.i16()?);
        let left_pad = r.u8()?;
        let depth = r.u8()?;
        r.skip(2)?;
        let data = r.rest();
        self.need_drawable(dr)?;
        self.need_gc(gc)?;
        if !(self.gcs[&gc].function == gfx::GX_COPY || format == 2) {
            return Err(Fail::Drop("GC function other than COPY is not supported"));
        }
        let d = &self.drawables[&dr];
        let drew = match format {
            0 => {
                if left_pad != 0 {
                    return Err(Fail::Drop("PutImage left pad must be 0"));
                }
                if depth != 1 {
                    return Err(bad(err::BAD_MATCH, 0));
                }
                d.draw_image(0, 0, dx, dy, w, h, 1, data, w)
            }
            1 => {
                if d.visual.is_none() {
                    return Err(Fail::Drop("drawable without visual"));
                }
                if d.depth != depth {
                    return Err(bad(err::BAD_MATCH, 0));
                }
                false
            }
            _ => {
                if left_pad != 0 {
                    return Err(bad(err::BAD_MATCH, 0));
                }
                d.draw_image(0, 0, dx, dy, w, h, depth, data, w)
            }
        };
        if drew {
            self.content_changed(dr);
        }
        Ok(())
    }

    // GetImage has a reply: failing silently would leave the client waiting for it forever, so
    // every failure is an X error (BadValue / BadMatch, as the protocol says).
    fn r_get_image(&mut self, c: ClientId, format: u8, r: &mut Reader) -> Res {
        let dr = r.u32()?;
        let (x, y, w, h) = (r.i16()?, r.i16()?, r.i16()?, r.i16()?);
        r.skip(4)?;
        if format == 0 || format > 2 {
            return Err(bad(err::BAD_VALUE, format as u32));
        }
        if format != 2 {
            return Err(bad(err::BAD_MATCH, 0)); // XYPixmap is not supported
        }
        self.need_drawable(dr)?;
        let d = &self.drawables[&dr];
        let visual = d.visual.ok_or(bad(err::BAD_MATCH, dr))?;
        let depth = d.depth;
        let visual_id = if self.pixmaps.contains(&dr) { 0 } else { visual };
        let data = d.get_image(x, y, w, h).ok_or(bad(err::BAD_MATCH, dr))?;
        let len = data.len();
        self.reply(c, depth, ((len + 3) / 4) as u32, |wr| {
            wr.u32(visual_id).pad(20).bytes(&data).pad(pad4(len));
        });
        Ok(())
    }

    // ------------------------------------------------------------------ cursors

    fn r_create_cursor(&mut self, c: ClientId, r: &mut Reader) -> Res {
        let (id, src, mask) = (r.u32()?, r.u32()?, r.u32()?);
        if !self.is_valid_resource_id(c, id) {
            return Err(bad(err::BAD_ID_CHOICE, id));
        }
        if !self.pixmaps.contains(&src) {
            return Err(bad(err::BAD_PIXMAP, src));
        }
        let sd = &self.drawables[&src];
        let (sw, sh) = (sd.width, sd.height);
        let mask_pm = self.pixmaps.contains(&mask).then_some(mask);
        if let Some(m) = mask_pm {
            let md = &self.drawables[&m];
            if md.depth != 1 || md.width != sw || md.height != sh {
                return Err(bad(err::BAD_MATCH, 0));
            }
        }
        let col = |r: &mut Reader| -> Res<u32> { Ok(r.u16()? as u32 & 0xff) };
        let (fr, fg, fb) = (col(r)?, col(r)?, col(r)?);
        let (br, bg, bb) = (col(r)?, col(r)?, col(r)?);
        let (fore, back) = ((fr << 16) | (fg << 8) | fb, (br << 16) | (bg << 8) | bb);
        let (hx, hy) = (r.i16()?, r.i16()?);
        if self.cursors.contains_key(&id) {
            return Err(bad(err::BAD_ID_CHOICE, id));
        }
        let (src_depth, src_visual) = (self.drawables[&src].depth, self.drawables[&src].visual);
        let image = self.new_drawable_with(0, sw, sh, src_depth, src_visual).ok_or(Fail::Drop("cursor allocation failed"))?;
        let mut visible = true;
        if let Some(m) = mask_pm {
            let md = &self.drawables[&m];
            visible = md.get_image(0, 0, md.width, md.height).map(|v| v.iter().any(|b| *b != 0)).unwrap_or(false);
            if visible {
                let (sd, md) = (&self.drawables[&src], &self.drawables[&m]);
                gfx::draw_alpha_masked(&image, sd, md, fore, back);
            }
        }
        // RecolorCursor needs to know which pixel is foreground: read it back from the image.
        let shape = if visible {
            image.get_image(0, 0, sw, sh).map(|px| super::CursorShape { bits: cursor_font::classify(&px, fore, back), fore, back })
        } else {
            None
        };
        self.cursor_serial += 1;
        let serial = self.cursor_serial;
        self.cursors.insert(id, super::Cursor { id, serial, freed: false, hot_x: hx, hot_y: hy, image, visible, shape });
        self.register_owner(c, Owned::Cursor(id));
        Ok(())
    }

    /// CreateGlyphCursor: a cursor from the built-in X cursor font (the only font OpenFont accepts).
    fn r_create_glyph_cursor(&mut self, c: ClientId, r: &mut Reader) -> Res {
        let (id, _source_font, mask_font) = (r.u32()?, r.u32()?, r.u32()?);
        let (source_char, mask_char) = (r.u16()? as u32, r.u16()? as u32);
        let col = |r: &mut Reader| -> Res<u32> { Ok(r.u16()? as u32 >> 8) };
        let (fr, fg, fb) = (col(r)?, col(r)?, col(r)?);
        let (br, bg, bb) = (col(r)?, col(r)?, col(r)?);
        let (fore, back) = ((fr << 16) | (fg << 8) | fb, (br << 16) | (bg << 8) | bb);
        if !self.is_valid_resource_id(c, id) || self.cursors.contains_key(&id) {
            return Err(bad(err::BAD_ID_CHOICE, id));
        }
        let source = cursor_font::glyph(source_char).ok_or_else(|| bad(err::BAD_VALUE, source_char))?;
        // No mask font: everything the glyph draws is visible.
        let mask = if mask_font == 0 { None } else { Some(cursor_font::glyph(mask_char).ok_or_else(|| bad(err::BAD_VALUE, mask_char))?) };
        let bits = cursor_font::shape(&source, mask.as_deref());
        let (w, h) = (cursor_font::WIDTH, cursor_font::HEIGHT);
        let image = self.shape_image(&bits, w, h, fore, back).ok_or(Fail::Drop("cursor allocation failed"))?;
        let visible = bits.iter().any(|b| *b != 0);
        self.cursor_serial += 1;
        let serial = self.cursor_serial;
        let shape = Some(super::CursorShape { bits, fore, back });
        self.cursors.insert(id, super::Cursor { id, serial, freed: false, hot_x: cursor_font::HOT, hot_y: cursor_font::HOT, image, visible, shape });
        self.register_owner(c, Owned::Cursor(id));
        Ok(())
    }

    /// RecolorCursor: repaints a two-colour cursor. ARGB cursors are left as they are.
    fn r_recolor_cursor(&mut self, r: &mut Reader) -> Res {
        let id = r.u32()?;
        let col = |r: &mut Reader| -> Res<u32> { Ok(r.u16()? as u32 >> 8) };
        let (fr, fg, fb) = (col(r)?, col(r)?, col(r)?);
        let (br, bg, bb) = (col(r)?, col(r)?, col(r)?);
        let (fore, back) = ((fr << 16) | (fg << 8) | fb, (br << 16) | (bg << 8) | bb);
        let (bits, w, h) = match self.cursors.get(&id) {
            None => return Err(bad(err::BAD_CURSOR, id)),
            Some(cu) => match &cu.shape {
                None => return Ok(()),
                Some(s) => (s.bits.clone(), cu.image.width, cu.image.height),
            },
        };
        let image = self.shape_image(&bits, w, h, fore, back).ok_or(Fail::Drop("cursor allocation failed"))?;
        // A new serial makes the renderer fetch the new image.
        self.cursor_serial += 1;
        let serial = self.cursor_serial;
        if let Some(cu) = self.cursors.get_mut(&id) {
            cu.image = image;
            cu.serial = serial;
            cu.shape = Some(super::CursorShape { bits, fore, back });
        }
        Ok(())
    }

    /// A 32-bit drawable holding a two-colour cursor shape.
    fn shape_image(&mut self, bits: &[u8], w: i16, h: i16, fore: u32, back: u32) -> Option<gfx::Drawable> {
        let px = cursor_font::paint(bits, fore, back);
        let visual = self.visual_for_depth(32);
        let image = self.new_drawable_with(0, w, h, 32, visual)?;
        image.draw_image(0, 0, 0, 0, w, h, 32, &px, w);
        Some(image)
    }

    /// ClearArea: paints the area with the window's background pixel; `exposures` also sends Expose.
    fn r_clear_area(&mut self, exposures: u8, r: &mut Reader) -> Res {
        let id = r.u32()?;
        let (x, y, w, h) = (r.i16()? as i32, r.i16()? as i32, r.u16()? as i32, r.u16()? as i32);
        let Some(win) = self.windows.get(&id) else { return Err(bad(err::BAD_WINDOW, id)) };
        if !win.has_content {
            return Err(bad(err::BAD_MATCH, 0));
        }
        let (ww, wh, mapped, pixel) = (win.width as i32, win.height as i32, win.attrs.mapped, win.attrs.background_pixel);
        // A zero size means "up to the window's edge".
        let w = if w == 0 { ww - x } else { w };
        let h = if h == 0 { wh - y } else { h };
        if let Some(pixel) = pixel {
            if w > 0 && h > 0 {
                if let Some(d) = self.drawables.get(&id) {
                    d.fill_rect(x, y, w, h, pixel as i32);
                }
                self.content_changed(id);
            }
        }
        if exposures != 0 && mapped {
            // The Expose event has no rectangle: the whole window is reported, as on MapWindow.
            self.send_to_window(id, ev::EXPOSURE, &Event::Expose { window: id, w: ww as i16, h: wh as i16 });
        }
        Ok(())
    }

    pub fn free_cursor(&mut self, id: u32) {
        if let Some(cu) = self.cursors.get_mut(&id) {
            cu.freed = true;
        } else {
            return;
        }
        self.forget_owned_pub(Owned::Cursor(id));
        self.gc_cursor(id);
    }

    /// Drops a freed cursor once no window shows it any more.
    pub fn gc_cursor(&mut self, id: u32) {
        let freed = self.cursors.get(&id).map(|c| c.freed).unwrap_or(false);
        if freed && !self.windows.values().any(|w| w.attrs.cursor == Some(id)) {
            self.cursors.remove(&id);
        }
    }

    // ------------------------------------------------------------------ extensions, keyboard

    fn r_query_extension(&mut self, c: ClientId, r: &mut Reader) -> Res {
        let len = r.u16()? as usize;
        r.skip(2)?;
        let name = r.string8(len)?;
        let ext = self.extensions.iter().find(|e| e.name == name).cloned();
        self.reply(c, 0, 0, |w| match ext {
            Some(e) => {
                w.u8(1).u8(e.major_opcode).u8(e.first_event).u8(e.first_error).pad(20);
            }
            None => {
                w.u8(0).pad(23);
            }
        });
        Ok(())
    }

    fn r_get_keyboard_mapping(&mut self, c: ClientId, r: &mut Reader) -> Res {
        let first = r.u8()?;
        let count = r.u8()? as usize;
        r.skip(2)?;
        let per = super::input::KEYSYMS_PER_KEYCODE;
        let kb: &Keyboard = &self.input.keyboard;
        let start = (first as usize).wrapping_sub(super::input::MIN_KEYCODE as usize).wrapping_mul(per);
        let syms: Vec<u32> = (0..count * per).map(|i| start.wrapping_add(i)).map(|i| kb.keysyms.get(i).copied().unwrap_or(0)).collect();
        // Reply length is count * keysyms-per-keycode words (the Java server wrote half of that).
        self.reply(c, per as u8, (count * per) as u32, |w| {
            w.pad(24);
            for s in &syms {
                w.u32(*s);
            }
        });
        Ok(())
    }
}
