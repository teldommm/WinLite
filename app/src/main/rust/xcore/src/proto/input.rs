//! Pointer, keyboard, grabs and the input events that follow from them
//! (Java `Pointer`, `Keyboard`, `GrabManager`, `InputDeviceManager`, `DesktopHelper`).

use super::consts::{code, ev};
use super::events::Event;
use super::tables::{self, DEFAULT_KEYSYMS};
use super::{ClientId, Notify, Server};
use std::collections::BTreeSet;

pub const KEYSYMS_PER_KEYCODE: usize = 2;
pub const MIN_KEYCODE: u8 = 8;
pub const KEYS_COUNT: usize = 248;
pub const MOUSE_WHEEL_DELTA: i32 = 120;

/// Crossing event modes and details.
pub const MODE_NORMAL: u8 = 0;
pub const MODE_GRAB: u8 = 1;
pub const MODE_UNGRAB: u8 = 2;
pub const DETAIL_ANCESTOR: u8 = 0;
pub const DETAIL_INFERIOR: u8 = 2;
pub const DETAIL_NONLINEAR: u8 = 3;

#[derive(Clone, Debug)]
pub struct Grab {
    pub window: u32,
    pub owner_events: bool,
    pub release_with_buttons: bool,
    pub client: ClientId,
    pub mask: u32,
}

#[derive(Clone)]
pub struct Keyboard {
    pub keysyms: Vec<u32>,
    pub modifiers: u32,
    pressed: BTreeSet<u8>,
}

impl Keyboard {
    pub fn default_layout() -> Keyboard {
        let mut k = Keyboard { keysyms: vec![0; KEYS_COUNT * KEYSYMS_PER_KEYCODE], modifiers: 0, pressed: BTreeSet::new() };
        for &(keycode, min, maj) in DEFAULT_KEYSYMS {
            k.set_keysyms(keycode, min, maj);
        }
        k
    }

    pub fn set_keysyms(&mut self, keycode: u8, min: u32, maj: u32) {
        let i = (keycode as usize).wrapping_sub(MIN_KEYCODE as usize);
        if i < KEYS_COUNT {
            self.keysyms[i * KEYSYMS_PER_KEYCODE] = min;
            self.keysyms[i * KEYSYMS_PER_KEYCODE + 1] = maj;
        }
    }

    pub fn has_keysym(&self, keycode: u8, keysym: u32) -> bool {
        let i = (keycode as usize).wrapping_sub(MIN_KEYCODE as usize);
        i < KEYS_COUNT && (self.keysyms[i * KEYSYMS_PER_KEYCODE] == keysym || self.keysyms[i * KEYSYMS_PER_KEYCODE + 1] == keysym)
    }

    pub fn is_modifier(keycode: u8) -> bool {
        use tables::*;
        matches!(keycode, KEY_SHIFT_L | KEY_SHIFT_R | KEY_CTRL_L | KEY_CTRL_R | KEY_ALT_L | KEY_ALT_R | KEY_CAPS_LOCK | KEY_NUM_LOCK)
    }

    pub fn modifier_flag(keycode: u8) -> u32 {
        use tables::*;
        match keycode {
            KEY_SHIFT_L | KEY_SHIFT_R => 1,
            KEY_CAPS_LOCK => 2,
            KEY_CTRL_L | KEY_CTRL_R => 4,
            KEY_ALT_L | KEY_ALT_R => 8,
            KEY_NUM_LOCK => 16,
            _ => 0,
        }
    }

    pub fn is_sticky(keycode: u8) -> bool {
        keycode == tables::KEY_CAPS_LOCK || keycode == tables::KEY_NUM_LOCK
    }

    pub fn is_pressed(&self, keycode: u8) -> bool {
        self.pressed.contains(&keycode)
    }
}

pub struct InputState {
    pub x: i16,
    pub y: i16,
    /// Pressed pointer buttons as `1 << (code + 7)` flags.
    pub buttons: u32,
    pub keyboard: Keyboard,
    pub grab: Option<Grab>,
    pub point_window: u32,
    pub relative_mouse: bool,
    pub mouse_disabled: bool,
    /// When the next edge pull-back step is due (see [`Server::settle_pointer`]); 0 = none.
    pub settle_at_ns: i64,
}

impl InputState {
    pub fn new(root: u32) -> InputState {
        InputState {
            x: 0,
            y: 0,
            buttons: 0,
            keyboard: Keyboard::default_layout(),
            grab: None,
            point_window: root,
            relative_mouse: false,
            mouse_disabled: false,
            settle_at_ns: 0,
        }
    }
}

/// Edge pull-back step period (the Java CursorLocker's 1000 / 60 ms tick).
pub const SETTLE_PERIOD_NS: i64 = 16_000_000;
/// Share of the overshoot kept per step.
const SETTLE_DAMPING: f32 = 0.25;

/// One pull-back step of a coordinate on an axis of `size` pixels: an overshoot beyond the edge is
/// first limited to `max_over`, then shrunk to a quarter. The far edge is `size - 1` (the Java
/// code used `size`, which left a pointer sitting exactly one pixel past the screen for good).
pub fn settle_step(p: i16, size: i16, max_over: i16) -> i16 {
    let last = size as i32 - 1;
    let c = (p as i32).clamp(-(max_over as i32), last + max_over as i32);
    if c < 0 {
        (c as f32 * SETTLE_DAMPING).ceil() as i16
    } else if c > last {
        (last as f32 + (c - last) as f32 * SETTLE_DAMPING).floor() as i16
    } else {
        p
    }
}

/// Button flag in the pointer button mask (`Pointer.Button.flag()`).
pub fn button_flag(code: u8) -> u32 {
    1 << (code as u32 + super::consts::MAX_BUTTONS as u32)
}

impl Server {
    pub fn clamped_pointer(&self) -> (i16, i16) {
        (self.input.x.clamp(0, self.screen_w - 1), self.input.y.clamp(0, self.screen_h - 1))
    }

    /// InputDeviceManager.updatePointWindow
    pub fn update_point_window(&mut self) {
        let (x, y) = self.clamped_pointer();
        self.input.point_window = self.find_point_window(x, y).unwrap_or(self.root);
    }

    pub fn key_but_mask(&self) -> u32 {
        self.input.buttons | self.input.keyboard.modifiers
    }

    /// Key and button masks are 16 bit on the wire.
    fn state16(mask: u32) -> u16 {
        mask as u16
    }

    // ------------------------------------------------------------------ sending with grabs

    fn window_enabled(&self, id: u32) -> bool {
        self.windows.get(&id).map(|w| w.attrs.enabled).unwrap_or(false)
    }

    /// InputDeviceManager.sendEvent (both Java overloads: a single event id is just a mask with one bit).
    fn input_send(&mut self, window: Option<u32>, mask: u32, event: &Event) {
        if let Some(g) = &self.input.grab {
            if !self.windows.contains_key(&g.window) {
                self.input.grab = None;
            }
        }
        let grab = match &self.input.grab {
            Some(g) if self.window_enabled(g.window) => Some(g.clone()),
            _ => None,
        };
        match grab {
            Some(g) => match window {
                Some(w) if g.owner_events => self.send_to_window_mask_for(w, mask, event, g.client),
                _ => {
                    if g.mask & mask != 0 {
                        self.send_to_client(g.client, event);
                    }
                }
            },
            None => {
                if let Some(w) = window {
                    if self.window_enabled(w) {
                        self.send_to_window_mask(w, mask, event);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ enter / leave

    /// InputDeviceManager.sendEnterLeaveNotify
    pub fn send_enter_leave(&mut self, a: u32, b: u32, mode: u8) {
        if a == b || !self.windows.contains_key(&a) || !self.windows.contains_key(&b) {
            return;
        }
        let (x, y) = (self.input.x, self.input.y);
        let (ax, ay) = self.root_to_local(a, x, y);
        let (bx, by) = self.root_to_local(b, x, y);
        let same_screen_focus = self.focus.map(|f| self.is_ancestor_of(b, f)).unwrap_or(false);
        let (mut detail_a, mut detail_b) = (DETAIL_NONLINEAR, DETAIL_NONLINEAR);
        if self.is_ancestor_of(a, b) {
            detail_a = DETAIL_ANCESTOR;
            detail_b = DETAIL_INFERIOR;
        } else if self.is_ancestor_of(b, a) {
            detail_b = DETAIL_ANCESTOR;
            detail_a = DETAIL_INFERIOR;
        }
        let state = Self::state16(self.key_but_mask());
        let time = self.now();
        let root = self.root;
        let leave = Event::Crossing { code: code::LEAVE_NOTIFY, detail: detail_a, time, root, event: a, root_x: x, root_y: y, event_x: ax, event_y: ay, state, mode, same_screen_focus };
        self.input_send(Some(a), ev::LEAVE_WINDOW, &leave);
        let enter = Event::Crossing { code: code::ENTER_NOTIFY, detail: detail_b, time, root, event: b, root_x: x, root_y: y, event_x: bx, event_y: by, state, mode, same_screen_focus };
        self.input_send(Some(b), ev::ENTER_WINDOW, &enter);
    }

    // ------------------------------------------------------------------ grabs

    pub fn deactivate_pointer_grab(&mut self) {
        if let Some(g) = self.input.grab.clone() {
            let pw = self.input.point_window;
            self.send_enter_leave(g.window, pw, MODE_UNGRAB);
            self.input.grab = None;
        }
    }

    /// GrabManager.onUnmapWindow: the Java check looks at the *unmapped* window, which is never
    /// viewable at that point, so any unmap ends the grab.
    pub fn grab_on_unmap(&mut self, _window: u32) {
        self.deactivate_pointer_grab();
    }

    fn activate_pointer_grab(&mut self, window: u32, client: ClientId, mask: u32, owner_events: bool, release_with_buttons: bool) {
        if self.input.grab.is_none() {
            let pw = self.input.point_window;
            self.send_enter_leave(pw, window, MODE_GRAB);
        }
        self.input.grab = Some(Grab { window, owner_events, release_with_buttons, client, mask });
    }

    /// GrabPointer request.
    pub fn grab_pointer(&mut self, window: u32, owner_events: bool, mask: u32, client: ClientId) {
        self.activate_pointer_grab(window, client, mask, owner_events, false);
    }

    // ------------------------------------------------------------------ pointer

    fn child_of_point(&self, event_window: u32) -> u32 {
        let pw = self.input.point_window;
        if self.is_ancestor_of(event_window, pw) {
            pw
        } else {
            0
        }
    }

    fn create_pointer_event_mask(&self) -> u32 {
        let mut m = ev::POINTER_MOTION;
        let b = self.input.buttons;
        if b != 0 {
            m |= ev::BUTTON_MOTION;
            for (code, bit) in [(1u8, ev::BUTTON1_MOTION), (2, ev::BUTTON2_MOTION), (3, ev::BUTTON3_MOTION), (4, ev::BUTTON4_MOTION), (5, ev::BUTTON5_MOTION)] {
                if b & button_flag(code) != 0 {
                    m |= bit;
                }
            }
        }
        m
    }

    pub fn set_pointer_position(&mut self, x: i32, y: i32) {
        if self.input.mouse_disabled {
            return;
        }
        self.input.x = x as i16;
        self.input.y = y as i16;
        self.on_pointer_move();
        self.arm_settle();
    }

    /// Moves the pointer without producing events (Java `setX`/`setY`).
    pub fn set_pointer_silent(&mut self, x: i32, y: i32) {
        if !self.input.mouse_disabled {
            self.move_silently(x as i16, y as i16);
            self.arm_settle();
        }
    }

    /// No events for the clients, but the embedder (the renderer's cursor, the magnifier) is told
    /// where the pointer is now: a warp by the game or the edge pull-back used to leave the drawn
    /// cursor where it was until the next real movement.
    fn move_silently(&mut self, x: i16, y: i16) {
        if (x, y) != (self.input.x, self.input.y) {
            self.input.x = x;
            self.input.y = y;
            self.notifications.push(Notify::PointerMove { x, y });
        }
    }

    /// Relative (captured) mouse: the pointer is the game's business, it is not pulled back.
    pub fn set_relative_mouse(&mut self, on: bool) {
        self.input.relative_mouse = on;
        self.arm_settle();
    }

    fn pointer_outside(&self) -> bool {
        let (x, y) = (self.input.x, self.input.y);
        x < 0 || y < 0 || x >= self.screen_w || y >= self.screen_h
    }

    /// Schedules the edge pull-back when the pointer is past the screen edge (and cancels it when
    /// it is not, or cannot be moved). Nothing runs while the pointer is on the screen.
    fn arm_settle(&mut self) {
        if self.input.relative_mouse || self.input.mouse_disabled || !self.pointer_outside() {
            self.input.settle_at_ns = 0;
        } else if self.input.settle_at_ns == 0 {
            self.input.settle_at_ns = self.platform.now_ns() + SETTLE_PERIOD_NS;
        }
    }

    /// Edge pull-back (was the Java `CursorLocker`, a 60 Hz timer that ran for the whole session
    /// while the mouse was not captured): a pointer pushed past the edge (touchpad deltas, a warp)
    /// is drawn back to it a quarter per step, without events for the clients, so the next
    /// movement starts at the edge.
    /// Runs only while the pointer is outside.
    pub fn settle_pointer(&mut self, now_ns: i64) {
        self.input.settle_at_ns = 0;
        if self.input.relative_mouse || self.input.mouse_disabled {
            return;
        }
        let max_over = (self.screen_w as f32 * 0.05) as i16;
        let nx = settle_step(self.input.x, self.screen_w, max_over);
        let ny = settle_step(self.input.y, self.screen_h, max_over);
        self.move_silently(nx, ny);
        if self.pointer_outside() {
            self.input.settle_at_ns = now_ns + SETTLE_PERIOD_NS;
        }
    }

    /// `button` is 1-based (`Pointer.Button.code()`).
    pub fn set_pointer_button(&mut self, button: u8, pressed: bool) {
        if self.input.mouse_disabled || button == 0 || button > super::consts::MAX_BUTTONS {
            return;
        }
        let flag = button_flag(button);
        let old = self.input.buttons & flag != 0;
        if pressed {
            self.input.buttons |= flag;
        } else {
            self.input.buttons &= !flag;
        }
        if old != pressed {
            self.notifications.push(Notify::PointerButton { button, pressed });
            self.run_hook(super::hooks::Hook::PointerButton { button, pressed });
            if pressed {
                self.on_button_press(button);
            } else {
                self.on_button_release(button);
            }
        }
    }

    fn on_button_press(&mut self, button: u8) {
        // Listeners run newest first: DesktopHelper, then the input device manager.
        self.desktop_helper_button_press();
        if self.input.relative_mouse {
            let wheel = match button {
                4 => MOUSE_WHEEL_DELTA,
                5 => -MOUSE_WHEEL_DELTA,
                _ => 0,
            };
            self.notifications.push(Notify::RelativeMouseButton { button, pressed: true, wheel_delta: wheel });
            return;
        }
        let mut grab_window = self.input.grab.as_ref().map(|g| g.window);
        if grab_window.is_none() {
            let pw = self.input.point_window;
            if let Some(w) = self.ancestor_with_event_id(pw, ev::BUTTON_PRESS, None) {
                let (client, mask) = self.windows.get(&w).and_then(|win| win.listeners.iter().find(|l| l.mask & ev::BUTTON_PRESS != 0)).map(|l| (l.client, l.mask)).unwrap();
                self.activate_pointer_grab(w, client, mask, mask & ev::OWNER_GRAB_BUTTON != 0, true);
                grab_window = Some(w);
            }
        }
        let Some(gw) = grab_window else { return };
        if !self.window_enabled(gw) {
            return;
        }
        let mask = self.create_pointer_event_mask() & !button_flag(button);
        let (x, y) = (self.input.x, self.input.y);
        let (lx, ly) = self.root_to_local(gw, x, y);
        let child = self.child_of_point(gw);
        let event = Event::Input { code: code::BUTTON_PRESS, detail: button, time: self.now(), root: self.root, event: gw, child, root_x: x, root_y: y, event_x: lx, event_y: ly, state: Self::state16(mask) };
        self.send_to_window(gw, ev::BUTTON_PRESS, &event);
    }

    fn on_button_release(&mut self, button: u8) {
        if self.input.relative_mouse {
            self.notifications.push(Notify::RelativeMouseButton { button, pressed: false, wheel_delta: 0 });
            return;
        }
        let mask = self.create_pointer_event_mask();
        let grab = self.input.grab.clone();
        let owner = grab.as_ref().map(|g| g.owner_events).unwrap_or(false);
        let pw = self.input.point_window;
        let window = if grab.is_none() || owner { self.ancestor_with_event_mask(Some(pw), mask) } else { None };
        if grab.is_some() || window.is_some() {
            let event_window = window.or(grab.as_ref().map(|g| g.window)).unwrap();
            let (x, y) = (self.input.x, self.input.y);
            let (lx, ly) = self.root_to_local(event_window, x, y);
            let child = self.child_of_point(event_window);
            let event = Event::Input { code: code::BUTTON_RELEASE, detail: button, time: self.now(), root: self.root, event: event_window, child, root_x: x, root_y: y, event_x: lx, event_y: ly, state: Self::state16(mask) };
            self.input_send(window, mask, &event);
        }
        if self.input.buttons == 0 && self.input.grab.as_ref().map(|g| g.release_with_buttons).unwrap_or(false) {
            self.deactivate_pointer_grab();
        }
    }

    fn on_pointer_move(&mut self) {
        let (x, y) = (self.input.x, self.input.y);
        self.notifications.push(Notify::PointerMove { x, y });
        self.update_point_window();
        let mask = self.create_pointer_event_mask();
        let grab = self.input.grab.clone();
        let owner = grab.as_ref().map(|g| g.owner_events).unwrap_or(false);
        let pw = self.input.point_window;
        let window = if grab.is_none() || owner { self.ancestor_with_event_mask(Some(pw), mask) } else { None };
        if grab.is_some() || window.is_some() {
            let event_window = window.or(grab.as_ref().map(|g| g.window)).unwrap();
            let (lx, ly) = self.root_to_local(event_window, x, y);
            let child = self.child_of_point(event_window);
            let event = Event::Input { code: code::MOTION_NOTIFY, detail: 0, time: self.now(), root: self.root, event: event_window, child, root_x: x, root_y: y, event_x: lx, event_y: ly, state: Self::state16(self.key_but_mask()) };
            self.input_send(window, mask, &event);
        }
    }

    /// DesktopHelper: a click outside every window gives the focus back to the root.
    fn desktop_helper_button_press(&mut self) {
        let (x, y) = self.clamped_pointer();
        if self.find_point_window(x, y).is_none() && self.focus != Some(self.root) {
            self.focus = Some(self.root);
            self.focus_revert = 0;
        }
    }

    // ------------------------------------------------------------------ keyboard

    pub fn key_press(&mut self, keycode: u8, keysym: u32) {
        let kb = &mut self.input.keyboard;
        if Keyboard::is_sticky(keycode) {
            if kb.pressed.remove(&keycode) {
                kb.modifiers &= !Keyboard::modifier_flag(keycode);
                self.notifications.push(Notify::Key { keycode, keysym: 0, pressed: false });
                self.on_key_release(keycode);
            } else {
                kb.pressed.insert(keycode);
                kb.modifiers |= Keyboard::modifier_flag(keycode);
                self.notifications.push(Notify::Key { keycode, keysym, pressed: true });
                self.on_key_press(keycode, keysym);
            }
        } else if !kb.pressed.contains(&keycode) {
            kb.pressed.insert(keycode);
            if Keyboard::is_modifier(keycode) {
                kb.modifiers |= Keyboard::modifier_flag(keycode);
            }
            self.notifications.push(Notify::Key { keycode, keysym, pressed: true });
            self.on_key_press(keycode, keysym);
        }
    }

    pub fn key_release(&mut self, keycode: u8) {
        let kb = &mut self.input.keyboard;
        if !Keyboard::is_sticky(keycode) && kb.pressed.remove(&keycode) {
            if Keyboard::is_modifier(keycode) {
                kb.modifiers &= !Keyboard::modifier_flag(keycode);
            }
            self.notifications.push(Notify::Key { keycode, keysym: 0, pressed: false });
            self.on_key_release(keycode);
        }
    }

    /// The window that receives a key event, with its `child` field.
    fn key_event_window(&mut self, bit: u32) -> Option<(u32, u32)> {
        let focused = self.focus?;
        if !self.windows.contains_key(&focused) {
            return None;
        }
        self.update_point_window();
        let pw = self.input.point_window;
        let mut event_window = None;
        let mut child = 0;
        if self.is_ancestor_of(focused, pw) {
            // Java dereferenced the result unconditionally; a missing listener cannot happen on a
            // path that also passes the focus window, so None falls through to the focus window.
            if let Some(w) = self.ancestor_with_event_id(pw, bit, Some(focused)) {
                child = if self.is_ancestor_of(w, pw) { pw } else { 0 };
                event_window = Some(w);
            }
        }
        let w = match event_window {
            Some(w) => w,
            None => {
                if !self.has_listener_for(focused, bit) {
                    return None;
                }
                focused
            }
        };
        if !self.window_enabled(w) {
            return None;
        }
        Some((w, child))
    }

    fn on_key_press(&mut self, keycode: u8, keysym: u32) {
        let Some((w, child)) = self.key_event_window(ev::KEY_PRESS) else { return };
        let (x, y) = (self.input.x, self.input.y);
        let (lx, ly) = self.root_to_local(w, x, y);
        if keysym != 0 && !self.input.keyboard.has_keysym(keycode, keysym) {
            self.input.keyboard.set_keysyms(keycode, keysym, keysym);
            self.send_to_all_listeners(w, &Event::MappingNotify { request: 1, first_keycode: keycode, count: 1 });
        }
        let state = Self::state16(self.key_but_mask());
        let event = Event::Input { code: code::KEY_PRESS, detail: keycode, time: self.now(), root: self.root, event: w, child, root_x: x, root_y: y, event_x: lx, event_y: ly, state };
        self.send_to_window(w, ev::KEY_PRESS, &event);
    }

    fn on_key_release(&mut self, keycode: u8) {
        let Some((w, child)) = self.key_event_window(ev::KEY_RELEASE) else { return };
        let (x, y) = (self.input.x, self.input.y);
        let (lx, ly) = self.root_to_local(w, x, y);
        let state = Self::state16(self.key_but_mask());
        let event = Event::Input { code: code::KEY_RELEASE, detail: keycode, time: self.now(), root: self.root, event: w, child, root_x: x, root_y: y, event_x: lx, event_y: ly, state };
        self.send_to_window(w, ev::KEY_RELEASE, &event);
    }
}
