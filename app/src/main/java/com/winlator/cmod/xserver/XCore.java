package com.winlator.cmod.xserver;

/**
 * The Rust X server (native crate {@code xcore}) as seen from Java: one instance owns one server
 * that listens on a Unix socket and serves its clients on its own thread.
 *
 * <p>Data crosses JNI as flat {@code long[]} arrays ({@link #drainEvents()},
 * {@link #snapshotTree()}), so one call moves many items. All methods are thread-safe and cheap to
 * call from the UI and input threads; after {@link #destroy()} they do nothing and return zeros.
 *
 * <p>This class is the whole Java-facing surface of the server; {@link XServer} wraps it for the
 * UI, input and renderer code.
 */
public final class XCore {
    /** Called when {@link #drainEvents()} has something to return. Any thread, never under a server lock. */
    public interface Listener {
        void onServerEvents();
    }

    static {
        System.loadLibrary("winlator");
    }

    /** Values per event in {@link #drainEvents()}: {@code kind, a, b, c, d}. */
    public static final int EVENT_STRIDE = 5;
    /** Values per window in {@link #snapshotTree()}. */
    public static final int WINDOW_STRIDE = 8;

    // Event kinds ({@code a, b, c, d} per kind). Mirrors bridge.rs.
    public static final long EV_CREATE_WINDOW = 1;          // window, parent
    public static final long EV_MAP_WINDOW = 2;             // window
    public static final long EV_UNMAP_WINDOW = 3;           // window
    public static final long EV_DESTROY_WINDOW = 4;         // window
    public static final long EV_CHANGE_Z_ORDER = 5;         // stackMode (0 above, 1 below), window, sibling
    public static final long EV_CONTENT_UPDATE = 6;         // window (coalesced: at most one per drain)
    public static final long EV_GEOMETRY = 7;               // window, resized (0/1)
    public static final long EV_ATTRIBUTES = 8;             // window, mask
    public static final long EV_PROPERTY = 9;               // window, atom
    public static final long EV_REPARENT = 10;              // window, parent, x, y
    public static final long EV_DRAWABLE_REMOVED = 11;      // drawable
    public static final long EV_POINTER_MOVE = 12;          // x, y
    public static final long EV_POINTER_BUTTON = 13;        // button, pressed (0/1)
    public static final long EV_KEY = 14;                   // keycode, keysym, pressed (0/1)
    public static final long EV_RELATIVE_MOUSE_BUTTON = 15; // button, pressed (0/1), wheelDelta
    public static final long EV_CONTENT_UPDATE_DIRECT = 16; // window, pixmap (DRI3 / Present frame of a direct content)
    public static final long EV_DIRECT_CONTENT_ADDED = 17;  // window, pixmap (acquireBuffer(pixmap) is the buffer)
    public static final long EV_DIRECT_CONTENT_REMOVED = 18; // window, pixmap
    public static final long EV_COMPOSITE_REDIRECT = 19;    // window, redirected (0/1)

    // Layout of one window in snapshotTree(): [id, parent (0 for the root), x, y, width, height, flags, borderWidth].
    public static final int W_ID = 0, W_PARENT = 1, W_X = 2, W_Y = 3, W_WIDTH = 4, W_HEIGHT = 5, W_FLAGS = 6, W_BORDER = 7;
    public static final long WF_MAPPED = 1;
    public static final long WF_VIEWABLE = 2;
    public static final long WF_OVERRIDE_REDIRECT = 4;
    public static final long WF_HAS_CONTENT = 8;
    public static final long WF_COMPOSITE_REDIRECTED = 16;

    private volatile long handle;

    private XCore(long handle) {
        this.handle = handle;
    }

    /**
     * Starts a server on the Unix socket {@code socketPath} (an existing file there is replaced).
     *
     * @return the running server, or null if the socket cannot be bound
     */
    public static XCore create(int width, int height, SurfaceFormat format, String socketPath) {
        SurfaceFormat f = format != null ? format : SurfaceFormat.DEFAULT;
        long h = nativeCreate(width, height, f.halFormat, socketPath);
        return h != 0 ? new XCore(h) : null;
    }

    public boolean isRunning() {
        return handle != 0;
    }

    /** Stops the server, closes every client and removes the socket. Safe to call twice. */
    public void destroy() {
        long h = handle;
        handle = 0;
        if (h != 0) nativeDestroy(h);
    }

    public void setListener(Listener listener) {
        nativeSetListener(handle, listener);
    }

    /** Events queued since the last call, {@link #EVENT_STRIDE} values each, in order. Never null. */
    public long[] drainEvents() {
        return nativeDrainEvents(handle);
    }

    /** Every window in paint order (parents first, siblings bottom-most first). Never null. */
    public long[] snapshotTree() {
        return nativeSnapshotTree(handle);
    }

    // ------------------------------------------------------------------ input

    public void pointerMove(int x, int y) {
        nativePointerMove(handle, x, y);
    }

    /** Moves the pointer without events (cursor locking, warps requested by the application). */
    public void pointerMoveSilent(int x, int y) {
        nativePointerMoveSilent(handle, x, y);
    }

    public void pointerButton(int button, boolean pressed) {
        nativePointerButton(handle, button, pressed);
    }

    /** {@code keysym} is used when the keycode has no mapping yet; 0 if unknown. */
    public void key(int keycode, int keysym, boolean pressed) {
        nativeKey(handle, keycode, keysym, pressed);
    }

    public void setRelativeMouse(boolean on) {
        nativeSetRelativeMouse(handle, on);
    }

    /** Touch Mode "Off": no pointer input reaches the clients. */
    public void setMouseDisabled(boolean disabled) {
        nativeSetMouseDisabled(handle, disabled);
    }

    public int pointerX() {
        return (int) (nativePointerPosition(handle) >> 16);
    }

    public int pointerY() {
        return (short) nativePointerPosition(handle);
    }

    /** Raw relative motion of the input device, for XInput raw events (before it moves the pointer). */
    public void rawMotion(double dx, double dy) {
        nativeRawMotion(handle, dx, dy);
    }

    // ------------------------------------------------------------------ display, renderer, shared memory

    /** The refresh rates the display offers (RANDR) and the one in force. */
    public void setRefreshRates(int[] rates, int active) {
        if (rates != null && rates.length > 0) nativeSetRefreshRates(handle, rates, active);
    }

    public void setActiveRate(int active) {
        nativeSetActiveRate(handle, active);
    }

    /** The renderer's FPS limit (0 = none); Present releases the game's buffers at that rate. */
    public static void setFpsLimit(int fps) {
        nativeSetFpsLimit(fps);
    }

    /** The renderer's display clock: refresh period, last arrival-phase error, and a sequence number that changes with each measurement. */
    public static void setDisplayClock(long periodNs, long phaseErrorNs, long sequence) {
        nativeSetDisplayClock(periodNs, phaseErrorNs, sequence);
    }

    /** Where MIT-SHM finds the guest's SysV segments ({@code attach(int)}/{@code detach(int)}); null to remove. */
    public static void setShmProvider(com.winlator.cmod.sysvshm.SysVSharedMemory provider) {
        nativeSetShmProvider(provider);
    }

    // ------------------------------------------------------------------ screen and buffers

    /** The effective screen width (that of a fullscreen window if there is one). */
    public int screenWidth() {
        return (int) (nativeScreenSize(handle) >> 16);
    }

    public int screenHeight() {
        return (int) (nativeScreenSize(handle) & 0xffff);
    }

    /**
     * The AHardwareBuffer of a window or pixmap, with a reference for the caller, or 0 if there
     * is none. The caller gives the reference back with {@link #releaseBuffer}.
     */
    public long acquireBuffer(int drawableId) {
        return nativeAcquireBuffer(handle, drawableId);
    }

    /** Gives back a reference obtained with {@link #acquireBuffer}. */
    public static void releaseBuffer(long ahb) {
        if (ahb != 0) nativeReleaseBuffer(ahb);
    }

    public int connectionCount() {
        return nativeConnectionCount(handle);
    }

    // ------------------------------------------------------------------ queries

    /**
     * The X cursor under the pointer as {@code [serial, hotX, hotY, visible (0/1), width, height]};
     * empty if no window up to the root has one. A new serial means a new image.
     */
    public long[] cursorState() {
        return nativeCursorState(handle);
    }

    /** The image of that cursor as R,G,B,A bytes (width * height * 4), or null. */
    public byte[] cursorImage() {
        return nativeCursorImage(handle);
    }

    /** Pressed pointer buttons, bit {@code 1 << Pointer.Button.code()} each. */
    public int pointerButtons() {
        return nativePointerButtons(handle);
    }

    public String atomName(int atom) {
        return nativeAtomName(handle, atom);
    }

    /** A window property as text (what {@code Property.toString()} showed); "" if there is none. */
    public String propertyString(int window, String atomName) {
        return nativePropertyString(handle, window, atomName);
    }

    /** Value number {@code index} of a window property (a byte for an 8-bit one); 0 if there is none. */
    public int propertyInt(int window, String atomName, int index) {
        return nativePropertyInt(handle, window, atomName, index);
    }

    /** Mapped, its own window group and bigger than 1x1: a window of the game or program itself. */
    public boolean isApplicationWindow(int window) {
        return nativeIsApplicationWindow(handle, window);
    }

    /** The first window whose WM_CLASS contains the executable's name (without .exe), or 0. */
    public int findWindowByClass(String exeName) {
        return nativeFindWindowByClass(handle, exeName);
    }

    /** One window as in {@link #snapshotTree()} ({@link #WINDOW_STRIDE} values); empty if it is gone. */
    public long[] windowInfo(int window) {
        return nativeWindowInfo(handle, window);
    }

    /** The children of a window, bottom-most first. */
    public long[] windowChildren(int window) {
        return nativeWindowChildren(handle, window);
    }

    /** The window and everything below it stop receiving input events. */
    public void disableSubtree(int window) {
        nativeDisableSubtree(handle, window);
    }

    /** The icon of a window as {@code [width (int LE), height (int LE), R,G,B,A bytes...]}, or null. */
    public byte[] windowIcon(int window) {
        return nativeWindowIcon(handle, window);
    }

    private static native long nativeCreate(int width, int height, int halFormat, String socketPath);
    private static native void nativeDestroy(long handle);
    private static native void nativeSetListener(long handle, Listener listener);
    private static native long[] nativeDrainEvents(long handle);
    private static native long[] nativeSnapshotTree(long handle);
    private static native void nativePointerMove(long handle, int x, int y);
    private static native void nativePointerMoveSilent(long handle, int x, int y);
    private static native void nativePointerButton(long handle, int button, boolean pressed);
    private static native void nativeKey(long handle, int keycode, int keysym, boolean pressed);
    private static native void nativeSetRelativeMouse(long handle, boolean on);
    private static native void nativeSetMouseDisabled(long handle, boolean disabled);
    private static native long nativePointerPosition(long handle);
    private static native long nativeScreenSize(long handle);
    private static native long nativeAcquireBuffer(long handle, int drawableId);
    private static native void nativeReleaseBuffer(long ahb);
    private static native int nativeConnectionCount(long handle);
    private static native long[] nativeCursorState(long handle);
    private static native byte[] nativeCursorImage(long handle);
    private static native int nativePointerButtons(long handle);
    private static native String nativeAtomName(long handle, int atom);
    private static native String nativePropertyString(long handle, int window, String atomName);
    private static native int nativePropertyInt(long handle, int window, String atomName, int index);
    private static native boolean nativeIsApplicationWindow(long handle, int window);
    private static native int nativeFindWindowByClass(long handle, String exeName);
    private static native byte[] nativeWindowIcon(long handle, int window);
    private static native long[] nativeWindowInfo(long handle, int window);
    private static native long[] nativeWindowChildren(long handle, int window);
    private static native void nativeDisableSubtree(long handle, int window);
    private static native void nativeRawMotion(long handle, double dx, double dy);
    private static native void nativeSetRefreshRates(long handle, int[] rates, int active);
    private static native void nativeSetActiveRate(long handle, int active);
    private static native void nativeSetFpsLimit(int fps);
    private static native void nativeSetDisplayClock(long periodNs, long phaseNs, long seq);
    private static native void nativeSetShmProvider(Object provider);
}
