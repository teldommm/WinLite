//! Protocol constants: core opcodes, error codes, event selection masks, event codes.
#![allow(dead_code)]

pub mod op {
    pub const CREATE_WINDOW: u8 = 1;
    pub const CHANGE_WINDOW_ATTRIBUTES: u8 = 2;
    pub const GET_WINDOW_ATTRIBUTES: u8 = 3;
    pub const DESTROY_WINDOW: u8 = 4;
    pub const DESTROY_SUB_WINDOWS: u8 = 5;
    pub const REPARENT_WINDOW: u8 = 7;
    pub const MAP_WINDOW: u8 = 8;
    pub const MAP_SUB_WINDOWS: u8 = 9;
    pub const UNMAP_WINDOW: u8 = 10;
    pub const CONFIGURE_WINDOW: u8 = 12;
    pub const GET_GEOMETRY: u8 = 14;
    pub const QUERY_TREE: u8 = 15;
    pub const INTERN_ATOM: u8 = 16;
    pub const GET_ATOM_NAME: u8 = 17;
    pub const CHANGE_PROPERTY: u8 = 18;
    pub const DELETE_PROPERTY: u8 = 19;
    pub const GET_PROPERTY: u8 = 20;
    pub const LIST_PROPERTIES: u8 = 21;
    pub const SET_SELECTION_OWNER: u8 = 22;
    pub const GET_SELECTION_OWNER: u8 = 23;
    pub const CONVERT_SELECTION: u8 = 24;
    pub const SEND_EVENT: u8 = 25;
    pub const GRAB_POINTER: u8 = 26;
    pub const UNGRAB_POINTER: u8 = 27;
    pub const GRAB_KEYBOARD: u8 = 31;
    pub const GRAB_SERVER: u8 = 36;
    pub const UNGRAB_SERVER: u8 = 37;
    pub const QUERY_POINTER: u8 = 38;
    pub const GET_MOTION_EVENTS: u8 = 39;
    pub const TRANSLATE_COORDINATES: u8 = 40;
    pub const WARP_POINTER: u8 = 41;
    pub const SET_INPUT_FOCUS: u8 = 42;
    pub const GET_INPUT_FOCUS: u8 = 43;
    pub const QUERY_KEYMAP: u8 = 44;
    pub const OPEN_FONT: u8 = 45;
    pub const QUERY_FONT: u8 = 47;
    pub const QUERY_TEXT_EXTENTS: u8 = 48;
    pub const LIST_FONTS: u8 = 49;
    pub const LIST_FONTS_WITH_INFO: u8 = 50;
    pub const GET_FONT_PATH: u8 = 52;
    pub const CREATE_PIXMAP: u8 = 53;
    pub const FREE_PIXMAP: u8 = 54;
    pub const CREATE_GC: u8 = 55;
    pub const CHANGE_GC: u8 = 56;
    pub const COPY_GC: u8 = 57;
    pub const SET_CLIP_RECTANGLES: u8 = 59;
    pub const FREE_GC: u8 = 60;
    pub const COPY_AREA: u8 = 62;
    pub const POLY_LINE: u8 = 65;
    pub const POLY_SEGMENT: u8 = 66;
    pub const POLY_RECTANGLE: u8 = 67;
    pub const POLY_FILL_RECTANGLE: u8 = 70;
    pub const PUT_IMAGE: u8 = 72;
    pub const GET_IMAGE: u8 = 73;
    pub const CREATE_COLORMAP: u8 = 78;
    pub const FREE_COLORMAP: u8 = 79;
    pub const LIST_INSTALLED_COLORMAPS: u8 = 83;
    pub const ALLOC_COLOR: u8 = 84;
    pub const ALLOC_NAMED_COLOR: u8 = 85;
    pub const ALLOC_COLOR_CELLS: u8 = 86;
    pub const ALLOC_COLOR_PLANES: u8 = 87;
    pub const QUERY_COLORS: u8 = 91;
    pub const LOOKUP_COLOR: u8 = 92;
    pub const CREATE_CURSOR: u8 = 93;
    pub const CREATE_GLYPH_CURSOR: u8 = 94;
    pub const FREE_CURSOR: u8 = 95;
    pub const QUERY_BEST_SIZE: u8 = 97;
    pub const QUERY_EXTENSION: u8 = 98;
    pub const LIST_EXTENSIONS: u8 = 99;
    pub const GET_KEYBOARD_MAPPING: u8 = 101;
    pub const GET_KEYBOARD_CONTROL: u8 = 103;
    pub const BELL: u8 = 104;
    pub const GET_POINTER_CONTROL: u8 = 106;
    pub const SET_SCREEN_SAVER: u8 = 107;
    pub const GET_SCREEN_SAVER: u8 = 108;
    pub const LIST_HOSTS: u8 = 110;
    pub const FORCE_SCREEN_SAVER: u8 = 115;
    pub const SET_POINTER_MAPPING: u8 = 116;
    pub const GET_POINTER_MAPPING: u8 = 117;
    pub const SET_MODIFIER_MAPPING: u8 = 118;
    pub const GET_MODIFIER_MAPPING: u8 = 119;
    pub const NO_OPERATION: u8 = 127;
}

pub mod err {
    pub const BAD_VALUE: u8 = 2;
    pub const BAD_WINDOW: u8 = 3;
    pub const BAD_PIXMAP: u8 = 4;
    pub const BAD_ATOM: u8 = 5;
    pub const BAD_CURSOR: u8 = 6;
    pub const BAD_MATCH: u8 = 8;
    pub const BAD_DRAWABLE: u8 = 9;
    pub const BAD_ACCESS: u8 = 10;
    pub const BAD_GC: u8 = 13;
    pub const BAD_ID_CHOICE: u8 = 14;
}

/// Event selection mask bits (also used as "event id" in the Java code).
pub mod ev {
    pub const KEY_PRESS: u32 = 1 << 0;
    pub const KEY_RELEASE: u32 = 1 << 1;
    pub const BUTTON_PRESS: u32 = 1 << 2;
    pub const BUTTON_RELEASE: u32 = 1 << 3;
    pub const ENTER_WINDOW: u32 = 1 << 4;
    pub const LEAVE_WINDOW: u32 = 1 << 5;
    pub const POINTER_MOTION: u32 = 1 << 6;
    pub const BUTTON1_MOTION: u32 = 1 << 8;
    pub const BUTTON2_MOTION: u32 = 1 << 9;
    pub const BUTTON3_MOTION: u32 = 1 << 10;
    pub const BUTTON4_MOTION: u32 = 1 << 11;
    pub const BUTTON5_MOTION: u32 = 1 << 12;
    pub const BUTTON_MOTION: u32 = 1 << 13;
    pub const EXPOSURE: u32 = 1 << 15;
    pub const STRUCTURE_NOTIFY: u32 = 1 << 17;
    pub const RESIZE_REDIRECT: u32 = 1 << 18;
    pub const SUBSTRUCTURE_NOTIFY: u32 = 1 << 19;
    pub const SUBSTRUCTURE_REDIRECT: u32 = 1 << 20;
    pub const FOCUS_CHANGE: u32 = 1 << 21;
    pub const PROPERTY_CHANGE: u32 = 1 << 22;
    pub const OWNER_GRAB_BUTTON: u32 = 1 << 24;
}

/// Event codes (first byte of a 32-byte event).
pub mod code {
    pub const KEY_PRESS: u8 = 2;
    pub const KEY_RELEASE: u8 = 3;
    pub const BUTTON_PRESS: u8 = 4;
    pub const BUTTON_RELEASE: u8 = 5;
    pub const MOTION_NOTIFY: u8 = 6;
    pub const ENTER_NOTIFY: u8 = 7;
    pub const LEAVE_NOTIFY: u8 = 8;
    pub const EXPOSE: u8 = 12;
    pub const CREATE_NOTIFY: u8 = 16;
    pub const DESTROY_NOTIFY: u8 = 17;
    pub const UNMAP_NOTIFY: u8 = 18;
    pub const MAP_NOTIFY: u8 = 19;
    pub const MAP_REQUEST: u8 = 20;
    pub const REPARENT_NOTIFY: u8 = 21;
    pub const CONFIGURE_NOTIFY: u8 = 22;
    pub const CONFIGURE_REQUEST: u8 = 23;
    pub const RESIZE_REQUEST: u8 = 25;
    pub const PROPERTY_NOTIFY: u8 = 28;
    pub const SELECTION_CLEAR: u8 = 29;
    pub const SELECTION_REQUEST: u8 = 30;
    pub const SELECTION_NOTIFY: u8 = 31;
    pub const CLIENT_MESSAGE: u8 = 33;
    pub const MAPPING_NOTIFY: u8 = 34;
}

pub const VERSION: u16 = 11;
pub const VENDOR_NAME: &str = "Elbrus Technologies, LLC";
pub const MAX_REQUEST_LENGTH: u16 = 65535;
pub const MIN_KEYCODE: u8 = 8;
pub const MAX_KEYCODE: u8 = 255;
pub const KEYSYMS_PER_KEYCODE: u8 = 2;
pub const MAX_BUTTONS: u8 = 7;
