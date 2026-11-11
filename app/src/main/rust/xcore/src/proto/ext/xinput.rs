//! A minimal X Input Extension (XI 2.2) for Wine 10+ / Proton 11 (Java `XInputExtension`).
//!
//! Wine takes raw input (WM_INPUT, and DirectInput which is built on it) only from XI2 raw events
//! selected on the root window. This implements exactly what libXi and winex11.drv use:
//!
//! * XI 1.x: GetExtensionVersion, ListInputDevices (none: wintab), OpenDevice,
//!   GetDeviceButtonMapping, CloseDevice.
//! * XI 2.x: XIQueryVersion, XISelectEvents, XIGetClientPointer, XIQueryDevice.
//! * Events: XI_RawMotion, XI_RawButtonPress, XI_RawButtonRelease, delivered to root selections.
//!
//! Device model, as on an Xorg server with a single mouse: 2 = virtual core pointer (master),
//! 3 = virtual core keyboard (master), 4 = slave pointer with two relative valuators (X, Y) and
//! 7 buttons; it is the sourceid of every raw event. The reported version is 2.2 although only a
//! subset exists: libXi strips sourceid from raw events below 2.2, and Wine ignores raw buttons
//! whose sourceid is 0. Requests outside the subset that expect a reply get BadRequest so
//! clients never wait for ever; those without a reply are ignored.

use super::reader;
use crate::proto::consts::MAX_BUTTONS;
use crate::proto::hooks::Hook;
use crate::proto::requests::Extension;
use crate::proto::wire::Writer;
use crate::proto::{bad, ClientId, Fail, Res, Server};
use std::collections::HashMap;

pub const MAJOR: u8 = 146; // Java: (byte)-110
/// XI 1.x events are never sent, but libXi registers wire handlers from here, so the range must
/// not overlap core events or other extensions (MIT-SHM 64, RANDR 65-66).
const FIRST_EVENT: u8 = 67;
/// IERRORS (5): BadDevice, BadEvent, BadMode, DeviceBusy, BadClass.
const FIRST_ERROR: u8 = 150;
const ERROR_BAD_DEVICE: u8 = FIRST_ERROR;
const ERROR_BAD_REQUEST: u8 = 1;
const SERVER_MAJOR: u16 = 2;
const SERVER_MINOR: u16 = 2;

const DEVICE_ALL: u16 = 0;
const DEVICE_ALL_MASTER: u16 = 1;
const DEVICE_MASTER_POINTER: u16 = 2;
const DEVICE_MASTER_KEYBOARD: u16 = 3;
const DEVICE_SLAVE_POINTER: u16 = 4;

const XI_RAW_BUTTON_PRESS: u16 = 15;
const XI_RAW_BUTTON_RELEASE: u16 = 16;
const XI_RAW_MOTION: u16 = 17;

const USE_MASTER_POINTER: u16 = 1;
const USE_MASTER_KEYBOARD: u16 = 2;
const USE_SLAVE_POINTER: u16 = 3;
const CLASS_BUTTON: u16 = 1;
const CLASS_VALUATOR: u16 = 2;
const NUM_BUTTONS: usize = MAX_BUTTONS as usize;
const GENERIC_EVENT: u8 = 35;
const VALUATOR_CLASS_LENGTH: usize = 44;

// XI 1.x
const GET_EXTENSION_VERSION: u8 = 1;
const LIST_INPUT_DEVICES: u8 = 2;
const OPEN_DEVICE: u8 = 3;
const CLOSE_DEVICE: u8 = 4;
const SELECT_EXTENSION_EVENT: u8 = 6;
const GET_DEVICE_BUTTON_MAPPING: u8 = 28;
// XI 2.x
const XI_WARP_POINTER: u8 = 41;
const XI_CHANGE_CURSOR: u8 = 42;
const XI_CHANGE_HIERARCHY: u8 = 43;
const XI_SET_CLIENT_POINTER: u8 = 44;
const XI_GET_CLIENT_POINTER: u8 = 45;
const XI_SELECT_EVENTS: u8 = 46;
const XI_QUERY_VERSION: u8 = 47;
const XI_QUERY_DEVICE: u8 = 48;
const XI_SET_FOCUS: u8 = 49;
const XI_UNGRAB_DEVICE: u8 = 52;
const XI_ALLOW_EVENTS: u8 = 53;
const XI_CHANGE_PROPERTY: u8 = 57;
const XI_DELETE_PROPERTY: u8 = 58;
const XI_BARRIER_RELEASE_POINTER: u8 = 61;

struct DeviceInfo {
    id: u16,
    use_: u16,
    attachment: u16,
    name: &'static str,
    has_pointer_classes: bool,
}

const DEVICES: [DeviceInfo; 3] = [
    DeviceInfo { id: DEVICE_MASTER_POINTER, use_: USE_MASTER_POINTER, attachment: DEVICE_MASTER_KEYBOARD, name: "Virtual core pointer", has_pointer_classes: true },
    DeviceInfo { id: DEVICE_MASTER_KEYBOARD, use_: USE_MASTER_KEYBOARD, attachment: DEVICE_MASTER_POINTER, name: "Virtual core keyboard", has_pointer_classes: false },
    DeviceInfo { id: DEVICE_SLAVE_POINTER, use_: USE_SLAVE_POINTER, attachment: DEVICE_MASTER_POINTER, name: "Winlator pointer", has_pointer_classes: true },
];

fn button_mask_len() -> usize {
    NUM_BUTTONS.div_ceil(32) * 4
}

fn button_class_len() -> usize {
    8 + button_mask_len() + 4 * NUM_BUTTONS
}

fn device_info_len(d: &DeviceInfo) -> usize {
    let mut len = 12 + ((d.name.len() + 3) & !3);
    if d.has_pointer_classes {
        len += button_class_len() + 2 * VALUATOR_CLASS_LENGTH;
    }
    len
}

/// FP3232: `integral + frac / 2^32` with an unsigned frac, so negative values need floor().
fn fp3232(w: &mut Writer, v: f64) {
    let integral = v.floor();
    let frac = ((v - integral) * 4294967296.0) as i64;
    w.u32(integral as i64 as u32).u32(frac as u32);
}

fn write_device_info(w: &mut Writer, d: &DeviceInfo) {
    w.u16(d.id).u16(d.use_).u16(d.attachment).u16(if d.has_pointer_classes { 3 } else { 0 });
    w.u16(d.name.len() as u16).u8(1).u8(0);
    w.string8(d.name);
    if !d.has_pointer_classes {
        return;
    }
    // XIButtonClass: header, state mask (all released), one label atom per button (None).
    w.u16(CLASS_BUTTON).u16((button_class_len() / 4) as u16).u16(DEVICE_SLAVE_POINTER).u16(NUM_BUTTONS as u16);
    w.pad(button_mask_len()).pad(4 * NUM_BUTTONS);
    // XIValuatorClass for X (0) and Y (1): relative axes, min == max so Wine uses a 1:1 scale.
    for number in 0..2u16 {
        w.u16(CLASS_VALUATOR).u16((VALUATOR_CLASS_LENGTH / 4) as u16).u16(DEVICE_SLAVE_POINTER).u16(number);
        w.u32(0); // label
        fp3232(w, 0.0);
        fp3232(w, 0.0);
        fp3232(w, 0.0);
        w.u32(1); // resolution
        w.u8(0).pad(3); // mode: relative
    }
}

type Masks = HashMap<u16, u64>;

#[derive(Default)]
pub struct XInput {
    /// client -> window -> device -> event mask (bit n = XI event type n)
    selections: HashMap<ClientId, HashMap<u32, Masks>>,
}

impl XInput {
    fn raw_active(s: &Server) -> bool {
        !s.input.relative_mouse && !s.input.mouse_disabled
    }

    /// Raw events go to root-window selections only, for XIAllDevices, XIAllMasterDevices or the
    /// master pointer itself; Wine selects them with XIAllMasterDevices.
    fn deliver(&self, s: &mut Server, evtype: u16, detail: u32, motion: Option<[f64; 4]>) {
        let root = s.root;
        let bit = 1u64 << evtype;
        let mut targets: Vec<ClientId> = self
            .selections
            .iter()
            .filter(|(_, windows)| {
                windows.get(&root).is_some_and(|m| {
                    let mask = m.get(&DEVICE_ALL).copied().unwrap_or(0) | m.get(&DEVICE_ALL_MASTER).copied().unwrap_or(0) | m.get(&DEVICE_MASTER_POINTER).copied().unwrap_or(0);
                    mask & bit != 0
                })
            })
            .map(|(c, _)| *c)
            .collect();
        targets.sort_unstable();
        let time = s.now();
        for c in targets {
            s.write_event(c, |w, seq| {
                let valuators_len = motion.is_some() as u16;
                let extra = if motion.is_some() { valuators_len as u32 + 4 * 2 } else { 0 };
                w.u8(GENERIC_EVENT).u8(MAJOR).u16(seq).u32(extra);
                w.u16(evtype).u16(DEVICE_MASTER_POINTER).u32(time).u32(detail);
                w.u16(DEVICE_SLAVE_POINTER).u16(valuators_len).u32(0).u32(0);
                if let Some(v) = motion {
                    w.u32(0x3);
                    for x in v {
                        fp3232(w, x);
                    }
                }
            });
        }
    }
}

impl Extension for XInput {
    fn name(&self) -> &str {
        "XInputExtension"
    }
    fn major_opcode(&self) -> u8 {
        MAJOR
    }
    fn first_event(&self) -> u8 {
        FIRST_EVENT
    }
    fn first_error(&self) -> u8 {
        FIRST_ERROR
    }

    fn handle(&mut self, s: &mut Server, c: ClientId, minor: u8, body: &[u8]) -> Res {
        let mut r = reader(s, c, body);
        match minor {
            GET_EXTENSION_VERSION => {
                s.reply(c, GET_EXTENSION_VERSION, 0, |w| {
                    w.u16(SERVER_MAJOR).u16(SERVER_MINOR).u8(1).pad(19);
                });
            }
            LIST_INPUT_DEVICES => {
                s.reply(c, LIST_INPUT_DEVICES, 0, |w| {
                    w.u8(0).pad(23);
                });
            }
            OPEN_DEVICE => {
                let id = r.u8()?;
                if id as u16 != DEVICE_SLAVE_POINTER {
                    return Err(bad(ERROR_BAD_DEVICE, id as u32));
                }
                s.reply(c, OPEN_DEVICE, 0, |w| {
                    w.u8(0).pad(23);
                });
            }
            GET_DEVICE_BUTTON_MAPPING => {
                let id = r.u8()?;
                if id as u16 != DEVICE_SLAVE_POINTER {
                    return Err(bad(ERROR_BAD_DEVICE, id as u32));
                }
                let map_len = (NUM_BUTTONS + 3) & !3;
                s.reply(c, GET_DEVICE_BUTTON_MAPPING, (map_len / 4) as u32, |w| {
                    w.u8(NUM_BUTTONS as u8).pad(23);
                    for i in 1..=NUM_BUTTONS {
                        w.u8(i as u8);
                    }
                    w.pad(map_len - NUM_BUTTONS);
                });
            }
            XI_QUERY_VERSION => {
                let (mut major, mut minor_v) = (r.u16()?, r.u16()?);
                // The lower of the client's and the server's version, as Xorg does.
                if major > SERVER_MAJOR || (major == SERVER_MAJOR && minor_v > SERVER_MINOR) {
                    major = SERVER_MAJOR;
                    minor_v = SERVER_MINOR;
                }
                s.reply(c, XI_QUERY_VERSION, 0, |w| {
                    w.u16(major).u16(minor_v).pad(20);
                });
            }
            XI_GET_CLIENT_POINTER => {
                s.reply(c, XI_GET_CLIENT_POINTER, 0, |w| {
                    w.u8(1).u8(0).u16(DEVICE_MASTER_POINTER).pad(20);
                });
            }
            XI_SELECT_EVENTS => {
                let window = r.u32()?;
                let num_masks = r.u16()?;
                r.skip(2)?;
                let mut parsed: Masks = HashMap::new();
                for _ in 0..num_masks {
                    if r.remaining() < 4 {
                        break;
                    }
                    let device = r.u16()?;
                    let mask_bytes = r.u16()? as usize * 4;
                    if mask_bytes > r.remaining() {
                        break;
                    }
                    let mut mask = 0u64;
                    for (i, b) in r.bytes(mask_bytes)?.iter().enumerate() {
                        if i < 8 {
                            mask |= (*b as u64) << (8 * i);
                        }
                    }
                    parsed.insert(device, mask);
                }
                if !s.windows.contains_key(&window) {
                    return Err(bad(crate::proto::consts::err::BAD_WINDOW, window));
                }
                let windows = self.selections.entry(c).or_default();
                let masks = windows.entry(window).or_default();
                // Each XISelectEvents replaces the mask of the given (window, device).
                for (device, mask) in parsed {
                    if mask == 0 {
                        masks.remove(&device);
                    } else {
                        masks.insert(device, mask);
                    }
                }
                if masks.is_empty() {
                    windows.remove(&window);
                }
                if windows.is_empty() {
                    self.selections.remove(&c);
                }
            }
            XI_QUERY_DEVICE => {
                let id = r.u16()?;
                let devices: Vec<&DeviceInfo> = DEVICES
                    .iter()
                    .filter(|d| id == DEVICE_ALL || d.id == id || (id == DEVICE_ALL_MASTER && (d.use_ == USE_MASTER_POINTER || d.use_ == USE_MASTER_KEYBOARD)))
                    .collect();
                if devices.is_empty() {
                    return Err(bad(ERROR_BAD_DEVICE, id as u32));
                }
                let len: usize = devices.iter().map(|d| device_info_len(d)).sum();
                s.reply(c, XI_QUERY_DEVICE, (len / 4) as u32, |w| {
                    w.u16(devices.len() as u16).pad(22);
                    for d in &devices {
                        write_device_info(w, d);
                    }
                });
            }
            // XIWarpPointer: like core WarpPointer, but with 16.16 fixed-point coordinates, and
            // for the master (2) or slave (4) pointer only. The pointer moves without events.
            XI_WARP_POINTER => {
                let (src, dst) = (r.u32()?, r.u32()?);
                let fixed = |v: u32| (v as i32) >> 16;
                let (sx, sy) = (fixed(r.u32()?), fixed(r.u32()?));
                let (src_w, src_h) = (r.u16()? as i32, r.u16()? as i32);
                let (dx, dy) = (fixed(r.u32()?), fixed(r.u32()?));
                let device = r.u16()?;
                if device != 2 && device != 4 {
                    return Err(bad(ERROR_BAD_DEVICE, device as u32));
                }
                let (src_size, dst_ok) = (s.windows.get(&src).map(|w| (w.width as i32, w.height as i32)), s.windows.contains_key(&dst));
                if (src != 0 && src_size.is_none()) || (dst != 0 && !dst_ok) {
                    let missing = if src != 0 && src_size.is_none() { src } else { dst };
                    return Err(bad(crate::proto::consts::err::BAD_WINDOW, missing));
                }
                // Relative mouse mode (a game owns the pointer): the client's warp is ignored.
                if s.input.relative_mouse {
                    return Ok(());
                }
                if let Some((ww, wh)) = src_size {
                    let (sw, sh) = (if src_w == 0 { ww - sx } else { src_w }, if src_h == 0 { wh - sy } else { src_h });
                    let (lx, ly) = s.root_to_local(src, s.input.x, s.input.y);
                    let (lx, ly) = (lx as i32, ly as i32);
                    if lx < sx || ly < sy || lx >= sx + sw || ly >= sy + sh {
                        return Ok(());
                    }
                }
                let clamp = |v: i32| v.clamp(i16::MIN as i32, i16::MAX as i32) as i16;
                if dst != 0 {
                    let (rx, ry) = s.local_to_root(dst, clamp(dx), clamp(dy));
                    s.set_pointer_silent(rx as i32, ry as i32);
                } else {
                    let (x, y) = (s.input.x as i32 + dx, s.input.y as i32 + dy);
                    s.set_pointer_silent(x, y);
                }
            }
            // Requests without a reply: accepted and ignored.
            CLOSE_DEVICE | SELECT_EXTENSION_EVENT | XI_CHANGE_CURSOR | XI_CHANGE_HIERARCHY | XI_SET_CLIENT_POINTER | XI_SET_FOCUS | XI_UNGRAB_DEVICE
            | XI_ALLOW_EVENTS | XI_CHANGE_PROPERTY | XI_DELETE_PROPERTY | XI_BARRIER_RELEASE_POINTER => {}
            // Anything else may expect a reply: an error, so the client never hangs.
            _ => return Err(Fail::X { code: ERROR_BAD_REQUEST, data: 0 }),
        }
        Ok(())
    }

    fn on_hook(&mut self, s: &mut Server, hook: &Hook) {
        match *hook {
            Hook::PointerButton { button, pressed } => {
                if Self::raw_active(s) {
                    let evtype = if pressed { XI_RAW_BUTTON_PRESS } else { XI_RAW_BUTTON_RELEASE };
                    self.deliver(s, evtype, button as u32, None);
                }
            }
            Hook::RawMotion { dx, dy } => {
                if (dx != 0.0 || dy != 0.0) && Self::raw_active(s) {
                    // Normal values, then the raw (unaccelerated) ones: the same here.
                    self.deliver(s, XI_RAW_MOTION, 0, Some([dx, dy, dx, dy]));
                }
            }
            Hook::WindowFreed(w) => {
                self.selections.retain(|_, windows| {
                    windows.remove(&w);
                    !windows.is_empty()
                });
            }
            _ => {}
        }
    }

    fn on_client_disconnected(&mut self, _s: &mut Server, client: ClientId) {
        self.selections.remove(&client);
    }
}
