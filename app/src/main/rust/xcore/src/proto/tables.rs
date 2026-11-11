//! Generated from Atom.java, XKeycode.java and Keyboard.java by tools/gen_tables.py (kept verbatim
//! so the Rust keymap matches the Java one exactly).
#![allow(dead_code)]

/// Predefined atoms; index == atom id (index 0 is unused). Generated from Atom.java.
pub const PREDEFINED_ATOMS: &[&str] = &[
    "",
    "PRIMARY",
    "SECONDARY",
    "ARC",
    "ATOM",
    "BITMAP",
    "CARDINAL",
    "COLORMAP",
    "CURSOR",
    "CUT_BUFFER0",
    "CUT_BUFFER1",
    "CUT_BUFFER2",
    "CUT_BUFFER3",
    "CUT_BUFFER4",
    "CUT_BUFFER5",
    "CUT_BUFFER6",
    "CUT_BUFFER7",
    "DRAWABLE",
    "FONT",
    "INTEGER",
    "PIXMAP",
    "POINT",
    "RECTANGLE",
    "RESOURCE_MANAGER",
    "RGB_COLOR_MAP",
    "RGB_BEST_MAP",
    "RGB_BLUE_MAP",
    "RGB_DEFAULT_MAP",
    "RGB_GRAY_MAP",
    "RGB_GREEN_MAP",
    "RGB_RED_MAP",
    "STRING",
    "VISUALID",
    "WINDOW",
    "WM_COMMAND",
    "WM_HINTS",
    "WM_CLIENT_MACHINE",
    "WM_ICON_NAME",
    "WM_ICON_SIZE",
    "WM_NAME",
    "WM_NORMAL_HINTS",
    "WM_SIZE_HINTS",
    "WM_ZOOM_HINTS",
    "MIN_SPACE",
    "NORM_SPACE",
    "MAX_SPACE",
    "END_SPACE",
    "SUPERSC.LPT_X",
    "SUPERSC.LPT_Y",
    "SUBSC.LPT_X",
    "SUBSC.LPT_Y",
    "UNDERLINE_POSITION",
    "UNDERLINE_THICKNESS",
    "STRIKEOUT_ASCENT",
    "STRIKEOUT_DESCENT",
    "ITALIC_ANGLE",
    "X_HEIGHT",
    "QUAD_WIDTH",
    "WEIGHT",
    "POINT_SIZE",
    "RESOLUTION",
    "COPYRIGHT",
    "NOTICE",
    "FONT_NAME",
    "FAMILY_NAME",
    "FULL_NAME",
    "CAP_HEIGHT",
    "WM_CLASS",
    "WM_TRANSIENT_FOR",
    "WM_COLORMAP_WINDOWS",
    "_NET_WM_WINDOW_TYPE",
    "_NET_WM_WINDOW_TYPE_DIALOG",
    "_NET_WM_WINDOW_TYPE_NORMAL",
    "_NET_WM_WINDOW_TYPE_UTILITY",
    "_NET_WM_STATE",
    "_NET_WM_STATE_FULLSCREEN",
    "_NET_WM_STATE_MAXIMIZED_HORZ",
    "_NET_WM_STATE_MAXIMIZED_VERT",
    "WM_STATE",
];

/// X keycodes by name (XKeycode.java).
pub const KEY_NONE: u8 = 0;
pub const KEY_ESC: u8 = 9;
pub const KEY_1: u8 = 10;
pub const KEY_2: u8 = 11;
pub const KEY_3: u8 = 12;
pub const KEY_4: u8 = 13;
pub const KEY_5: u8 = 14;
pub const KEY_6: u8 = 15;
pub const KEY_7: u8 = 16;
pub const KEY_8: u8 = 17;
pub const KEY_9: u8 = 18;
pub const KEY_0: u8 = 19;
pub const KEY_MINUS: u8 = 20;
pub const KEY_EQUAL: u8 = 21;
pub const KEY_BKSP: u8 = 22;
pub const KEY_TAB: u8 = 23;
pub const KEY_Q: u8 = 24;
pub const KEY_W: u8 = 25;
pub const KEY_E: u8 = 26;
pub const KEY_R: u8 = 27;
pub const KEY_T: u8 = 28;
pub const KEY_Y: u8 = 29;
pub const KEY_U: u8 = 30;
pub const KEY_I: u8 = 31;
pub const KEY_O: u8 = 32;
pub const KEY_P: u8 = 33;
pub const KEY_BRACKET_LEFT: u8 = 34;
pub const KEY_BRACKET_RIGHT: u8 = 35;
pub const KEY_ENTER: u8 = 36;
pub const KEY_CTRL_L: u8 = 37;
pub const KEY_A: u8 = 38;
pub const KEY_S: u8 = 39;
pub const KEY_D: u8 = 40;
pub const KEY_F: u8 = 41;
pub const KEY_G: u8 = 42;
pub const KEY_H: u8 = 43;
pub const KEY_J: u8 = 44;
pub const KEY_K: u8 = 45;
pub const KEY_L: u8 = 46;
pub const KEY_SEMICOLON: u8 = 47;
pub const KEY_APOSTROPHE: u8 = 48;
pub const KEY_GRAVE: u8 = 49;
pub const KEY_SHIFT_L: u8 = 50;
pub const KEY_BACKSLASH: u8 = 51;
pub const KEY_Z: u8 = 52;
pub const KEY_X: u8 = 53;
pub const KEY_C: u8 = 54;
pub const KEY_V: u8 = 55;
pub const KEY_B: u8 = 56;
pub const KEY_N: u8 = 57;
pub const KEY_M: u8 = 58;
pub const KEY_COMMA: u8 = 59;
pub const KEY_PERIOD: u8 = 60;
pub const KEY_SLASH: u8 = 61;
pub const KEY_SHIFT_R: u8 = 62;
pub const KEY_KP_MULTIPLY: u8 = 63;
pub const KEY_ALT_L: u8 = 64;
pub const KEY_SPACE: u8 = 65;
pub const KEY_CAPS_LOCK: u8 = 66;
pub const KEY_F1: u8 = 67;
pub const KEY_F2: u8 = 68;
pub const KEY_F3: u8 = 69;
pub const KEY_F4: u8 = 70;
pub const KEY_F5: u8 = 71;
pub const KEY_F6: u8 = 72;
pub const KEY_F7: u8 = 73;
pub const KEY_F8: u8 = 74;
pub const KEY_F9: u8 = 75;
pub const KEY_F10: u8 = 76;
pub const KEY_NUM_LOCK: u8 = 77;
pub const KEY_SCROLL_LOCK: u8 = 78;
pub const KEY_KP_7: u8 = 79;
pub const KEY_KP_8: u8 = 80;
pub const KEY_KP_9: u8 = 81;
pub const KEY_KP_SUBTRACT: u8 = 82;
pub const KEY_KP_4: u8 = 83;
pub const KEY_KP_5: u8 = 84;
pub const KEY_KP_6: u8 = 85;
pub const KEY_KP_ADD: u8 = 86;
pub const KEY_KP_1: u8 = 87;
pub const KEY_KP_2: u8 = 88;
pub const KEY_KP_3: u8 = 89;
pub const KEY_KP_0: u8 = 90;
pub const KEY_KP_DEL: u8 = 91;
pub const KEY_F11: u8 = 95;
pub const KEY_F12: u8 = 96;
pub const KEY_KP_ENTER: u8 = 104;
pub const KEY_CTRL_R: u8 = 105;
pub const KEY_KP_DIVIDE: u8 = 106;
pub const KEY_PRTSCN: u8 = 107;
pub const KEY_ALT_R: u8 = 108;
pub const KEY_HOME: u8 = 110;
pub const KEY_UP: u8 = 111;
pub const KEY_PRIOR: u8 = 112;
pub const KEY_LEFT: u8 = 113;
pub const KEY_RIGHT: u8 = 114;
pub const KEY_END: u8 = 115;
pub const KEY_DOWN: u8 = 116;
pub const KEY_NEXT: u8 = 117;
pub const KEY_INSERT: u8 = 118;
pub const KEY_DEL: u8 = 119;

/// Default keysyms (keycode, keysym0, keysym1) from Keyboard.createKeyboard.
pub const DEFAULT_KEYSYMS: &[(u8, u32, u32)] = &[
    (KEY_ESC, 65307, 0),
    (KEY_ENTER, 65293, 0),
    (KEY_RIGHT, 65363, 0),
    (KEY_UP, 65362, 0),
    (KEY_LEFT, 65361, 0),
    (KEY_DOWN, 65364, 0),
    (KEY_DEL, 65535, 0),
    (KEY_BKSP, 65288, 0),
    (KEY_INSERT, 65379, 0),
    (KEY_PRIOR, 65365, 0),
    (KEY_NEXT, 65366, 0),
    (KEY_HOME, 65360, 0),
    (KEY_END, 65367, 0),
    (KEY_SHIFT_L, 65505, 0),
    (KEY_SHIFT_R, 65506, 0),
    (KEY_CTRL_L, 65507, 0),
    (KEY_CTRL_R, 65508, 0),
    (KEY_ALT_L, 65511, 0),
    (KEY_ALT_R, 65512, 0),
    (KEY_TAB, 65289, 0),
    (KEY_SPACE, 32, 32),
    (KEY_A, 97, 65),
    (KEY_B, 98, 66),
    (KEY_C, 99, 67),
    (KEY_D, 100, 68),
    (KEY_E, 101, 69),
    (KEY_F, 102, 70),
    (KEY_G, 103, 71),
    (KEY_H, 104, 72),
    (KEY_I, 105, 73),
    (KEY_J, 106, 74),
    (KEY_K, 107, 75),
    (KEY_L, 108, 76),
    (KEY_M, 109, 77),
    (KEY_N, 110, 78),
    (KEY_O, 111, 79),
    (KEY_P, 112, 80),
    (KEY_Q, 113, 81),
    (KEY_R, 114, 82),
    (KEY_S, 115, 83),
    (KEY_T, 116, 84),
    (KEY_U, 117, 85),
    (KEY_V, 118, 86),
    (KEY_W, 119, 87),
    (KEY_X, 120, 88),
    (KEY_Y, 121, 89),
    (KEY_Z, 122, 90),
    (KEY_1, 49, 33),
    (KEY_2, 50, 64),
    (KEY_3, 51, 35),
    (KEY_4, 52, 36),
    (KEY_5, 53, 37),
    (KEY_6, 54, 94),
    (KEY_7, 55, 38),
    (KEY_8, 56, 42),
    (KEY_9, 57, 40),
    (KEY_0, 48, 41),
    (KEY_COMMA, 44, 60),
    (KEY_PERIOD, 46, 62),
    (KEY_SEMICOLON, 59, 58),
    (KEY_APOSTROPHE, 39, 34),
    (KEY_BRACKET_LEFT, 91, 123),
    (KEY_BRACKET_RIGHT, 93, 125),
    (KEY_GRAVE, 96, 126),
    (KEY_MINUS, 45, 95),
    (KEY_EQUAL, 61, 43),
    (KEY_SLASH, 47, 63),
    (KEY_BACKSLASH, 92, 124),
    (KEY_KP_DIVIDE, 65455, 65455),
    (KEY_KP_MULTIPLY, 65450, 65450),
    (KEY_KP_SUBTRACT, 65453, 65453),
    (KEY_KP_ADD, 65451, 65451),
    (KEY_KP_0, 65456, 65438),
    (KEY_KP_1, 65457, 65436),
    (KEY_KP_2, 65458, 65433),
    (KEY_KP_3, 65459, 65459),
    (KEY_KP_4, 65460, 65430),
    (KEY_KP_5, 65461, 65461),
    (KEY_KP_6, 65462, 65432),
    (KEY_KP_7, 65463, 65429),
    (KEY_KP_8, 65464, 65431),
    (KEY_KP_9, 65465, 65465),
    (KEY_KP_DEL, 65439, 0),
    (KEY_F1, 65470, 0),
    (KEY_F2, 65471, 0),
    (KEY_F3, 65472, 0),
    (KEY_F4, 65473, 0),
    (KEY_F5, 65474, 0),
    (KEY_F6, 65475, 0),
    (KEY_F7, 65476, 0),
    (KEY_F8, 65477, 0),
    (KEY_F9, 65478, 0),
    (KEY_F10, 65479, 0),
    (KEY_F11, 65480, 0),
    (KEY_F12, 65481, 0),
];
