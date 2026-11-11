package com.winlator.cmod.xserver;

import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import com.winlator.cmod.inputcontrols.ExternalController;

/**
 * Android {@link KeyEvent} -> X key press/release. Only the translation lives here; the key
 * state (pressed keys, modifier mask, keysym table, lock keys) is kept by the Rust server.
 */
public class Keyboard {
    private final XKeycode[] keycodeMap = createKeycodeMap();
    private final XServer xServer;
    // Android keycode whose press added a synthetic Shift_L, or -1 (main thread only).
    private int syntheticShiftFor = -1;

    public Keyboard(XServer xServer) {
        this.xServer = xServer;
    }

    public boolean onKeyEvent(KeyEvent event) {
        if (ExternalController.isGameController(event.getDevice())) return false;

        int action = event.getAction();
        int keyCode = event.getKeyCode();
        if (keyCode < 0 || keyCode >= keycodeMap.length) return false;

        if (keyCode == KeyEvent.KEYCODE_TAB || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (action == KeyEvent.ACTION_DOWN) {
                xServer.injectKeyPress(keycodeMap[keyCode]);
                return true;
            } else if (action == KeyEvent.ACTION_UP) {
                xServer.injectKeyRelease(keycodeMap[keyCode]);
                return true;
            }
        } else if (action == KeyEvent.ACTION_DOWN || action == KeyEvent.ACTION_UP) {
            XKeycode xKeycode = keycodeMap[keyCode];
            if (xKeycode == null) return false;

            if (action == KeyEvent.ACTION_DOWN) {
                // A Shift is added only when none is held in X (soft keyboards report Shift as a
                // meta state without Shift key events, and @ * # + are shifted digits/=), and only
                // that synthetic Shift is released with this key. It used to release Shift_L on
                // every key-up, dropping a physically held Shift (e.g. sprint while tapping A).
                boolean needsShift = event.isShiftPressed() || keyCode == KeyEvent.KEYCODE_AT || keyCode == KeyEvent.KEYCODE_STAR || keyCode == KeyEvent.KEYCODE_POUND || keyCode == KeyEvent.KEYCODE_PLUS;
                if (needsShift && !isShiftHeld()) {
                    xServer.injectKeyPress(XKeycode.KEY_SHIFT_L);
                    syntheticShiftFor = keyCode;
                }
                xServer.injectKeyPress(xKeycode, xKeycode != XKeycode.KEY_ENTER ? keysymFor(event) : 0);
            } else if (action == KeyEvent.ACTION_UP) {
                xServer.injectKeyRelease(xKeycode);
                if (syntheticShiftFor == keyCode) {
                    syntheticShiftFor = -1;
                    xServer.injectKeyRelease(XKeycode.KEY_SHIFT_L);
                }
            }
        }
        return true;
    }

    private boolean isShiftHeld() {
        return xServer.isShiftHeld();
    }

    /**
     * Keysym to bind to the key for this press, or 0 to keep the static (US) keysyms. Only Latin-1
     * letters are passed, so a QWERTZ/AZERTY layout still types its letters. Anything else is
     * dropped: the Unicode char of a Cyrillic letter is not a valid keysym (0x446 for 'ц'), and
     * shifted symbols that differ from US (Shift+2 = '"' on a Russian layout) flipped the key's
     * keysyms back and forth. Each change also sends MappingNotify, on which Wine rebuilds its
     * keycode -> VK table and gave those keys OEM codes instead of VK_W / VK_2.
     */
    private static int keysymFor(KeyEvent event) {
        int c = event.getUnicodeChar();
        if (c <= 0 || (c & KeyCharacterMap.COMBINING_ACCENT) != 0) return 0;
        if (c < 0x41 || c > 0xFF || !Character.isLetter(c)) return 0;
        return c;
    }

    private static XKeycode[] createKeycodeMap() {
        XKeycode[] keycodeMap = new XKeycode[(KeyEvent.getMaxKeyCode() + 1)];
        keycodeMap[KeyEvent.KEYCODE_ESCAPE] = XKeycode.KEY_ESC;
        keycodeMap[KeyEvent.KEYCODE_ENTER] = XKeycode.KEY_ENTER;
        keycodeMap[KeyEvent.KEYCODE_DPAD_LEFT] = XKeycode.KEY_LEFT;
        keycodeMap[KeyEvent.KEYCODE_DPAD_RIGHT] = XKeycode.KEY_RIGHT;
        keycodeMap[KeyEvent.KEYCODE_DPAD_UP] = XKeycode.KEY_UP;
        keycodeMap[KeyEvent.KEYCODE_DPAD_DOWN] = XKeycode.KEY_DOWN;
        keycodeMap[KeyEvent.KEYCODE_DEL] = XKeycode.KEY_BKSP;
        keycodeMap[KeyEvent.KEYCODE_INSERT] = XKeycode.KEY_INSERT;
        keycodeMap[KeyEvent.KEYCODE_FORWARD_DEL] = XKeycode.KEY_DEL;
        keycodeMap[KeyEvent.KEYCODE_MOVE_HOME] = XKeycode.KEY_HOME;
        keycodeMap[KeyEvent.KEYCODE_MOVE_END] = XKeycode.KEY_END;
        keycodeMap[KeyEvent.KEYCODE_PAGE_UP] = XKeycode.KEY_PRIOR;
        keycodeMap[KeyEvent.KEYCODE_PAGE_DOWN] = XKeycode.KEY_NEXT;
        keycodeMap[KeyEvent.KEYCODE_SHIFT_LEFT] = XKeycode.KEY_SHIFT_L;
        keycodeMap[KeyEvent.KEYCODE_SHIFT_RIGHT] = XKeycode.KEY_SHIFT_R;
        keycodeMap[KeyEvent.KEYCODE_CTRL_LEFT] = XKeycode.KEY_CTRL_L;
        keycodeMap[KeyEvent.KEYCODE_CTRL_RIGHT] = XKeycode.KEY_CTRL_R;
        keycodeMap[KeyEvent.KEYCODE_ALT_LEFT] = XKeycode.KEY_ALT_L;
        keycodeMap[KeyEvent.KEYCODE_ALT_RIGHT] = XKeycode.KEY_ALT_R;
        keycodeMap[KeyEvent.KEYCODE_TAB] = XKeycode.KEY_TAB;
        keycodeMap[KeyEvent.KEYCODE_SPACE] = XKeycode.KEY_SPACE;
        keycodeMap[KeyEvent.KEYCODE_A] = XKeycode.KEY_A;
        keycodeMap[KeyEvent.KEYCODE_B] = XKeycode.KEY_B;
        keycodeMap[KeyEvent.KEYCODE_C] = XKeycode.KEY_C;
        keycodeMap[KeyEvent.KEYCODE_D] = XKeycode.KEY_D;
        keycodeMap[KeyEvent.KEYCODE_E] = XKeycode.KEY_E;
        keycodeMap[KeyEvent.KEYCODE_F] = XKeycode.KEY_F;
        keycodeMap[KeyEvent.KEYCODE_G] = XKeycode.KEY_G;
        keycodeMap[KeyEvent.KEYCODE_H] = XKeycode.KEY_H;
        keycodeMap[KeyEvent.KEYCODE_I] = XKeycode.KEY_I;
        keycodeMap[KeyEvent.KEYCODE_J] = XKeycode.KEY_J;
        keycodeMap[KeyEvent.KEYCODE_K] = XKeycode.KEY_K;
        keycodeMap[KeyEvent.KEYCODE_L] = XKeycode.KEY_L;
        keycodeMap[KeyEvent.KEYCODE_M] = XKeycode.KEY_M;
        keycodeMap[KeyEvent.KEYCODE_N] = XKeycode.KEY_N;
        keycodeMap[KeyEvent.KEYCODE_O] = XKeycode.KEY_O;
        keycodeMap[KeyEvent.KEYCODE_P] = XKeycode.KEY_P;
        keycodeMap[KeyEvent.KEYCODE_Q] = XKeycode.KEY_Q;
        keycodeMap[KeyEvent.KEYCODE_R] = XKeycode.KEY_R;
        keycodeMap[KeyEvent.KEYCODE_S] = XKeycode.KEY_S;
        keycodeMap[KeyEvent.KEYCODE_T] = XKeycode.KEY_T;
        keycodeMap[KeyEvent.KEYCODE_U] = XKeycode.KEY_U;
        keycodeMap[KeyEvent.KEYCODE_V] = XKeycode.KEY_V;
        keycodeMap[KeyEvent.KEYCODE_W] = XKeycode.KEY_W;
        keycodeMap[KeyEvent.KEYCODE_X] = XKeycode.KEY_X;
        keycodeMap[KeyEvent.KEYCODE_Y] = XKeycode.KEY_Y;
        keycodeMap[KeyEvent.KEYCODE_Z] = XKeycode.KEY_Z;
        keycodeMap[KeyEvent.KEYCODE_0] = XKeycode.KEY_0;
        keycodeMap[KeyEvent.KEYCODE_1] = XKeycode.KEY_1;
        keycodeMap[KeyEvent.KEYCODE_2] = XKeycode.KEY_2;
        keycodeMap[KeyEvent.KEYCODE_3] = XKeycode.KEY_3;
        keycodeMap[KeyEvent.KEYCODE_4] = XKeycode.KEY_4;
        keycodeMap[KeyEvent.KEYCODE_5] = XKeycode.KEY_5;
        keycodeMap[KeyEvent.KEYCODE_6] = XKeycode.KEY_6;
        keycodeMap[KeyEvent.KEYCODE_7] = XKeycode.KEY_7;
        keycodeMap[KeyEvent.KEYCODE_8] = XKeycode.KEY_8;
        keycodeMap[KeyEvent.KEYCODE_9] = XKeycode.KEY_9;
        keycodeMap[KeyEvent.KEYCODE_STAR] = XKeycode.KEY_8;
        keycodeMap[KeyEvent.KEYCODE_POUND] = XKeycode.KEY_3;
        keycodeMap[KeyEvent.KEYCODE_COMMA] = XKeycode.KEY_COMMA;
        keycodeMap[KeyEvent.KEYCODE_PERIOD] = XKeycode.KEY_PERIOD;
        keycodeMap[KeyEvent.KEYCODE_SEMICOLON] = XKeycode.KEY_SEMICOLON;
        keycodeMap[KeyEvent.KEYCODE_APOSTROPHE] = XKeycode.KEY_APOSTROPHE;
        keycodeMap[KeyEvent.KEYCODE_LEFT_BRACKET] = XKeycode.KEY_BRACKET_LEFT;
        keycodeMap[KeyEvent.KEYCODE_RIGHT_BRACKET] = XKeycode.KEY_BRACKET_RIGHT;
        keycodeMap[KeyEvent.KEYCODE_GRAVE] = XKeycode.KEY_GRAVE;
        keycodeMap[KeyEvent.KEYCODE_MINUS] = XKeycode.KEY_MINUS;
        keycodeMap[KeyEvent.KEYCODE_PLUS] = XKeycode.KEY_EQUAL;
        keycodeMap[KeyEvent.KEYCODE_EQUALS] = XKeycode.KEY_EQUAL;
        keycodeMap[KeyEvent.KEYCODE_SLASH] = XKeycode.KEY_SLASH;
        keycodeMap[KeyEvent.KEYCODE_AT] = XKeycode.KEY_2;
        keycodeMap[KeyEvent.KEYCODE_BACKSLASH] = XKeycode.KEY_BACKSLASH;
        keycodeMap[KeyEvent.KEYCODE_NUMPAD_DIVIDE] = XKeycode.KEY_KP_DIVIDE;
        keycodeMap[KeyEvent.KEYCODE_NUMPAD_MULTIPLY] = XKeycode.KEY_KP_MULTIPLY;
        keycodeMap[KeyEvent.KEYCODE_NUMPAD_SUBTRACT] = XKeycode.KEY_KP_SUBTRACT;
        keycodeMap[KeyEvent.KEYCODE_NUMPAD_ADD] = XKeycode.KEY_KP_ADD;
        keycodeMap[KeyEvent.KEYCODE_NUMPAD_DOT] = XKeycode.KEY_KP_DEL;
        keycodeMap[KeyEvent.KEYCODE_NUMPAD_0] = XKeycode.KEY_KP_0;
        keycodeMap[KeyEvent.KEYCODE_NUMPAD_1] = XKeycode.KEY_KP_1;
        keycodeMap[KeyEvent.KEYCODE_NUMPAD_2] = XKeycode.KEY_KP_2;
        keycodeMap[KeyEvent.KEYCODE_NUMPAD_3] = XKeycode.KEY_KP_3;
        keycodeMap[KeyEvent.KEYCODE_NUMPAD_4] = XKeycode.KEY_KP_4;
        keycodeMap[KeyEvent.KEYCODE_NUMPAD_5] = XKeycode.KEY_KP_5;
        keycodeMap[KeyEvent.KEYCODE_NUMPAD_6] = XKeycode.KEY_KP_6;
        keycodeMap[KeyEvent.KEYCODE_NUMPAD_7] = XKeycode.KEY_KP_7;
        keycodeMap[KeyEvent.KEYCODE_NUMPAD_8] = XKeycode.KEY_KP_8;
        keycodeMap[KeyEvent.KEYCODE_NUMPAD_9] = XKeycode.KEY_KP_9;
        keycodeMap[KeyEvent.KEYCODE_F1] = XKeycode.KEY_F1;
        keycodeMap[KeyEvent.KEYCODE_F2] = XKeycode.KEY_F2;
        keycodeMap[KeyEvent.KEYCODE_F3] = XKeycode.KEY_F3;
        keycodeMap[KeyEvent.KEYCODE_F4] = XKeycode.KEY_F4;
        keycodeMap[KeyEvent.KEYCODE_F5] = XKeycode.KEY_F5;
        keycodeMap[KeyEvent.KEYCODE_F6] = XKeycode.KEY_F6;
        keycodeMap[KeyEvent.KEYCODE_F7] = XKeycode.KEY_F7;
        keycodeMap[KeyEvent.KEYCODE_F8] = XKeycode.KEY_F8;
        keycodeMap[KeyEvent.KEYCODE_F9] = XKeycode.KEY_F9;
        keycodeMap[KeyEvent.KEYCODE_F10] = XKeycode.KEY_F10;
        keycodeMap[KeyEvent.KEYCODE_F11] = XKeycode.KEY_F11;
        keycodeMap[KeyEvent.KEYCODE_F12] = XKeycode.KEY_F12;
        keycodeMap[KeyEvent.KEYCODE_NUM_LOCK] = XKeycode.KEY_NUM_LOCK;
        keycodeMap[KeyEvent.KEYCODE_CAPS_LOCK] = XKeycode.KEY_CAPS_LOCK;
        return keycodeMap;
    }
}
