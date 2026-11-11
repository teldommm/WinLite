//! The 32-byte events this server generates, encoded in the recipient's byte order.

use super::consts::code;
use super::wire::Writer;

#[derive(Clone, Debug)]
pub enum Event {
    /// KeyPress / KeyRelease / ButtonPress / ButtonRelease / MotionNotify.
    Input { code: u8, detail: u8, time: u32, root: u32, event: u32, child: u32, root_x: i16, root_y: i16, event_x: i16, event_y: i16, state: u16 },
    /// EnterNotify / LeaveNotify.
    Crossing { code: u8, detail: u8, time: u32, root: u32, event: u32, root_x: i16, root_y: i16, event_x: i16, event_y: i16, state: u16, mode: u8, same_screen_focus: bool },
    CreateNotify { parent: u32, window: u32, x: i16, y: i16, w: i16, h: i16, border: i16, override_redirect: bool },
    DestroyNotify { event: u32, window: u32 },
    UnmapNotify { event: u32, window: u32 },
    MapNotify { event: u32, window: u32, override_redirect: bool },
    MapRequest { parent: u32, window: u32 },
    ReparentNotify { event: u32, window: u32, parent: u32, x: i16, y: i16, override_redirect: bool },
    ConfigureNotify { event: u32, window: u32, above: u32, x: i16, y: i16, w: i16, h: i16, border: i16, override_redirect: bool },
    ConfigureRequest { stack_mode: u8, parent: u32, window: u32, sibling: u32, x: i16, y: i16, w: i16, h: i16, border: i16, value_mask: u16 },
    ResizeRequest { window: u32, w: i16, h: i16 },
    Expose { window: u32, w: i16, h: i16 },
    PropertyNotify { window: u32, atom: u32, time: u32, deleted: bool },
    SelectionClear { time: u32, owner: u32, selection: u32 },
    /// MIT-SHM `ShmCompletion` (`code` is the extension's first event number).
    ShmCompletion { code: u8, drawable: u32, shmseg: u32, offset: u32 },
    SelectionRequest { time: u32, owner: u32, requestor: u32, selection: u32, target: u32, property: u32 },
    SelectionNotify { time: u32, requestor: u32, selection: u32, target: u32, property: u32 },
    MappingNotify { request: u8, first_keycode: u8, count: u8 },
    /// SendEvent payload: forwarded as sent, except that the code carries the send-event flag
    /// (0x80) and the sequence number is the recipient's, as the protocol requires.
    Raw([u8; 32]),
}

impl Event {
    pub fn code(&self) -> u8 {
        match self {
            Event::Input { code, .. } | Event::Crossing { code, .. } => *code,
            Event::CreateNotify { .. } => code::CREATE_NOTIFY,
            Event::DestroyNotify { .. } => code::DESTROY_NOTIFY,
            Event::UnmapNotify { .. } => code::UNMAP_NOTIFY,
            Event::MapNotify { .. } => code::MAP_NOTIFY,
            Event::MapRequest { .. } => code::MAP_REQUEST,
            Event::ReparentNotify { .. } => code::REPARENT_NOTIFY,
            Event::ConfigureNotify { .. } => code::CONFIGURE_NOTIFY,
            Event::ConfigureRequest { .. } => code::CONFIGURE_REQUEST,
            Event::ResizeRequest { .. } => code::RESIZE_REQUEST,
            Event::Expose { .. } => code::EXPOSE,
            Event::PropertyNotify { .. } => code::PROPERTY_NOTIFY,
            Event::SelectionClear { .. } => code::SELECTION_CLEAR,
            Event::ShmCompletion { code, .. } => *code,
            Event::SelectionRequest { .. } => code::SELECTION_REQUEST,
            Event::SelectionNotify { .. } => code::SELECTION_NOTIFY,
            Event::MappingNotify { .. } => code::MAPPING_NOTIFY,
            Event::Raw(b) => b[0],
        }
    }

    /// Appends exactly 32 bytes.
    pub fn encode(&self, w: &mut Writer, seq: u16) {
        let start = w.out.len();
        match self {
            Event::Input { code, detail, time, root, event, child, root_x, root_y, event_x, event_y, state } => {
                w.u8(*code).u8(*detail).u16(seq).u32(*time).u32(*root).u32(*event).u32(*child);
                w.i16(*root_x).i16(*root_y).i16(*event_x).i16(*event_y).u16(*state).u8(1).u8(0);
            }
            Event::Crossing { code, detail, time, root, event, root_x, root_y, event_x, event_y, state, mode, same_screen_focus } => {
                // The Java PointerWindowEvent always wrote child = 0 (it was constructed with null).
                w.u8(*code).u8(*detail).u16(seq).u32(*time).u32(*root).u32(*event).u32(0);
                w.i16(*root_x).i16(*root_y).i16(*event_x).i16(*event_y).u16(*state).u8(*mode).u8(*same_screen_focus as u8);
            }
            Event::CreateNotify { parent, window, x, y, w: ww, h, border, override_redirect } => {
                w.u8(code::CREATE_NOTIFY).u8(0).u16(seq).u32(*parent).u32(*window);
                w.i16(*x).i16(*y).i16(*ww).i16(*h).i16(*border).u8(*override_redirect as u8).pad(9);
            }
            Event::DestroyNotify { event, window } => {
                w.u8(code::DESTROY_NOTIFY).u8(0).u16(seq).u32(*event).u32(*window).pad(20);
            }
            Event::UnmapNotify { event, window } => {
                w.u8(code::UNMAP_NOTIFY).u8(0).u16(seq).u32(*event).u32(*window).u8(0).pad(19);
            }
            Event::MapNotify { event, window, override_redirect } => {
                w.u8(code::MAP_NOTIFY).u8(0).u16(seq).u32(*event).u32(*window).u8(*override_redirect as u8).pad(19);
            }
            Event::MapRequest { parent, window } => {
                w.u8(code::MAP_REQUEST).u8(0).u16(seq).u32(*parent).u32(*window).pad(20);
            }
            Event::ReparentNotify { event, window, parent, x, y, override_redirect } => {
                w.u8(code::REPARENT_NOTIFY).u8(0).u16(seq).u32(*event).u32(*window).u32(*parent);
                w.i16(*x).i16(*y).u8(*override_redirect as u8).pad(11);
            }
            Event::ConfigureNotify { event, window, above, x, y, w: ww, h, border, override_redirect } => {
                w.u8(code::CONFIGURE_NOTIFY).u8(0).u16(seq).u32(*event).u32(*window).u32(*above);
                w.i16(*x).i16(*y).i16(*ww).i16(*h).i16(*border).u8(*override_redirect as u8).pad(5);
            }
            Event::ConfigureRequest { stack_mode, parent, window, sibling, x, y, w: ww, h, border, value_mask } => {
                w.u8(code::CONFIGURE_REQUEST).u8(*stack_mode).u16(seq).u32(*parent).u32(*window).u32(*sibling);
                w.i16(*x).i16(*y).i16(*ww).i16(*h).i16(*border).u16(*value_mask).pad(4);
            }
            Event::ResizeRequest { window, w: ww, h } => {
                w.u8(code::RESIZE_REQUEST).u8(0).u16(seq).u32(*window).i16(*ww).i16(*h).pad(20);
            }
            Event::Expose { window, w: ww, h } => {
                w.u8(code::EXPOSE).u8(0).u16(seq).u32(*window).i16(0).i16(0).i16(*ww).i16(*h).u16(0).pad(14);
            }
            Event::PropertyNotify { window, atom, time, deleted } => {
                w.u8(code::PROPERTY_NOTIFY).u8(0).u16(seq).u32(*window).u32(*atom).u32(*time).u8(*deleted as u8).pad(15);
            }
            Event::SelectionClear { time, owner, selection } => {
                w.u8(code::SELECTION_CLEAR).u8(0).u16(seq).u32(*time).u32(*owner).u32(*selection).pad(16);
            }
            Event::ShmCompletion { code, drawable, shmseg, offset } => {
                // minor opcode 3 (ShmPutImage) of major opcode 155.
                w.u8(*code).u8(0).u16(seq).u32(*drawable).u16(3).u8(155).u8(0).u32(*shmseg).u32(*offset).pad(12);
            }
            Event::SelectionRequest { time, owner, requestor, selection, target, property } => {
                w.u8(code::SELECTION_REQUEST).u8(0).u16(seq).u32(*time).u32(*owner).u32(*requestor).u32(*selection).u32(*target).u32(*property).pad(4);
            }
            Event::SelectionNotify { time, requestor, selection, target, property } => {
                w.u8(code::SELECTION_NOTIFY).u8(0).u16(seq).u32(*time).u32(*requestor).u32(*selection).u32(*target).u32(*property).pad(8);
            }
            Event::MappingNotify { request, first_keycode, count } => {
                w.u8(code::MAPPING_NOTIFY).u8(0).u16(seq).u8(*request).u8(*first_keycode).u8(*count).pad(25);
            }
            Event::Raw(b) => {
                w.u8(b[0] | 0x80).u8(b[1]).u16(seq).bytes(&b[4..]);
            }
        }
        debug_assert_eq!(w.out.len() - start, 32, "event {:?} is not 32 bytes", self.code());
    }
}
