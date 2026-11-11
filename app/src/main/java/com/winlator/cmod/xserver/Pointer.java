package com.winlator.cmod.xserver;

/**
 * The X pointer as the UI sees it: its position and buttons, read from and written to the server
 * ({@link XCore}). The button codes are the X protocol's.
 */
public class Pointer {
    public enum Button {
        BUTTON_LEFT, BUTTON_MIDDLE, BUTTON_RIGHT, BUTTON_SCROLL_UP, BUTTON_SCROLL_DOWN, BUTTON_SCROLL_CLICK_LEFT, BUTTON_SCROLL_CLICK_RIGHT;

        /** The protocol's button number: 1 left, 2 middle, 3 right, 4/5 wheel up/down. */
        public byte code() {
            return (byte)(ordinal() + 1);
        }

        public static Button fromCode(int code) {
            Button[] all = values();
            return code >= 1 && code <= all.length ? all[code - 1] : null;
        }
    }
    public static final byte MAX_BUTTONS = 7;

    private final XServer xServer;

    Pointer(XServer xServer) {
        this.xServer = xServer;
    }

    public short getX() {
        XCore core = xServer.core();
        return core != null ? (short)core.pointerX() : 0;
    }

    public short getY() {
        XCore core = xServer.core();
        return core != null ? (short)core.pointerY() : 0;
    }

    /** Moves the pointer without telling the clients (the position the next event starts from). */
    public void setX(int x) {
        setPositionSilently(x, getY());
    }

    public void setY(int y) {
        setPositionSilently(getX(), y);
    }

    public void setPositionSilently(int x, int y) {
        XCore core = xServer.core();
        if (core != null) core.pointerMoveSilent(x, y);
    }

    public boolean isButtonPressed(Button button) {
        XCore core = xServer.core();
        return core != null && (core.pointerButtons() & (1 << button.code())) != 0;
    }
}
