package com.winlator.cmod.xserver;

import android.graphics.Bitmap;
import android.util.Log;

import com.winlator.cmod.widget.XServerRendererView;
import com.winlator.cmod.winhandler.MouseEventFlags;
import com.winlator.cmod.winhandler.WinHandler;

import java.nio.ByteBuffer;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The X server as the app's UI, input and renderer code use it: a thin Java front for the Rust
 * server ({@link XCore}). It owns the server's life ({@link #start}/{@link #stop}), turns the
 * server's event queue into calls on {@link Listener}s (in order, on one thread of its own), and
 * keeps the state that belongs to the app rather than to the X protocol: relative mouse mode,
 * touch-mode "off", the connection to {@link WinHandler}.
 *
 * <p>Everything here is safe to call before {@link #start} and after {@link #stop}: input is
 * dropped and queries answer with defaults.
 */
public class XServer {
    private static final String TAG = "XServer";

    /** What happened in the server, in order. Called on the event thread; do not block it for long. */
    public interface Listener {
        /** The server is up (also for a listener added later: see {@link #addListener}). */
        default void onServerStarted() {}
        default void onServerStopped() {}
        default void onCreateWindow(int window, int parent) {}
        default void onDestroyWindow(int window) {}
        default void onMapWindow(int window) {}
        default void onUnmapWindow(int window) {}
        default void onReparentWindow(int window, int parent, int x, int y) {}
        /** {@code stackMode}: 0 above, 1 below. */
        default void onChangeWindowZOrder(int stackMode, int window, int sibling) {}
        default void onUpdateWindowGeometry(int window, boolean resized) {}
        /** {@code mask}: the changed attributes (X protocol CW* bits). */
        default void onUpdateWindowAttributes(int window, long mask) {}
        default void onModifyWindowProperty(int window, int atom) {}
        /**
         * The window's own pixels changed, {@code frames} times since the last call. The renderer
         * already has them (the server hands frames to it directly); this comes at most every
         * 250 ms per window, for what only needs to know that and how often (first frame, HUD).
         */
        default void onUpdateWindowContent(int window, int frames) {}
        /** A game presented DRI3 buffers to {@code window}, {@code frames} times, the latest {@code pixmap}; as above. */
        default void onUpdateWindowContentDirect(int window, int pixmap, int frames) {}
        default void onDirectContentAdded(int window, int pixmap) {}
        default void onDirectContentRemoved(int window, int pixmap) {}
        default void onCompositeRedirect(int window, boolean redirected) {}
        /** The pointer moved, or was moved silently (a warp, the edge pull-back). The renderer follows it by itself. */
        default void onPointerMove(int x, int y) {}
    }

    public final ScreenInfo screenInfo;
    public final Pointer pointer = new Pointer(this);
    private final SurfaceFormat surfaceFormat;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Runnable> effectiveScreenListeners = new CopyOnWriteArrayList<>();
    private final ExecutorService eventThread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "XServerEvents");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean drainScheduled = new AtomicBoolean(false);

    private volatile XCore core;
    private volatile WinHandler winHandler;
    private volatile XServerRendererView xServerView;
    private volatile boolean relativeMouseMovement = false;
    private volatile boolean simulateTouchScreen = false;
    private volatile boolean disableMouse = false;
    private volatile short[] refreshRates;
    private volatile short activeRate;
    private final Object inputLock = new Object();

    /** Android KeyEvent -> X keys (the key state itself is in the Rust server). */
    public final Keyboard keyboard = new Keyboard(this);
    private volatile int shiftDown; // bit 0 = Shift_L, bit 1 = Shift_R, as injected through here

    /* Last absolute position reported by an absolute input source (touchscreen, stylus, uncaptured
     * mouse). Raw (XI2) motion for these sources is the device's own movement, measured from here,
     * so cursor warps by the application and the edge pull-back never leak into raw input. */
    private boolean absolutePointerAnchored = false;
    private int absolutePointerX;
    private int absolutePointerY;

    public XServer(ScreenInfo screenInfo, SurfaceFormat surfaceFormat) {
        this.screenInfo = screenInfo;
        this.surfaceFormat = surfaceFormat != null ? surfaceFormat : SurfaceFormat.DEFAULT;
    }

    // ------------------------------------------------------------------ life

    /**
     * Starts serving clients on the Unix socket {@code socketPath}.
     *
     * @return false if the socket could not be bound
     */
    public synchronized boolean start(String socketPath) {
        if (core != null) return true;
        XCore c = XCore.create(screenInfo.width, screenInfo.height, surfaceFormat, socketPath);
        if (c == null) {
            Log.e(TAG, "cannot start the X server on " + socketPath);
            return false;
        }
        c.setRelativeMouse(relativeMouseMovement);
        c.setMouseDisabled(disableMouse);
        short[] rates = refreshRates;
        if (rates != null) applyRefreshRates(c, rates, activeRate);
        core = c;
        c.setListener(this::onServerEvents);
        // Told on the event thread, ahead of the first events.
        runOnEventThread(() -> {
            for (Listener l : listeners) l.onServerStarted();
        });
        onServerEvents();
        return true;
    }

    /** Stops the server and closes every client. */
    public synchronized void stop() {
        XCore c = core;
        if (c == null) return;
        core = null;
        c.setListener(null);
        c.destroy();
        runOnEventThread(() -> {
            for (Listener l : listeners) l.onServerStopped();
        });
    }

    public boolean isRunning() {
        return core != null;
    }

    /** The running server, or null. */
    public XCore core() {
        return core;
    }

    public SurfaceFormat getSurfaceFormat() {
        return surfaceFormat;
    }

    // ------------------------------------------------------------------ events

    /** Listeners are told about a running server at once with {@link Listener#onServerStarted}. */
    public void addListener(Listener listener) {
        listeners.addIfAbsent(listener);
        if (core != null) runOnEventThread(listener::onServerStarted);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** Runs {@code r} on the event thread, after every event already queued. */
    public void runOnEventThread(Runnable r) {
        try {
            eventThread.execute(r);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    private void onServerEvents() {
        if (drainScheduled.compareAndSet(false, true)) runOnEventThread(this::drainEvents);
    }

    private void drainEvents() {
        drainScheduled.set(false);
        XCore c = core;
        if (c == null) return;
        long[] ev = c.drainEvents();
        for (int i = 0; i + XCore.EVENT_STRIDE <= ev.length; i += XCore.EVENT_STRIDE) {
            final long kind = ev[i];
            final int a = (int) ev[i + 1], b = (int) ev[i + 2], cc = (int) ev[i + 3], d = (int) ev[i + 4];
            try {
                switch ((int) kind) {
                    case (int) XCore.EV_CREATE_WINDOW:
                        for (Listener l : listeners) l.onCreateWindow(a, b);
                        break;
                    case (int) XCore.EV_MAP_WINDOW:
                        for (Listener l : listeners) l.onMapWindow(a);
                        break;
                    case (int) XCore.EV_UNMAP_WINDOW:
                        for (Listener l : listeners) l.onUnmapWindow(a);
                        break;
                    case (int) XCore.EV_DESTROY_WINDOW:
                        for (Listener l : listeners) l.onDestroyWindow(a);
                        break;
                    case (int) XCore.EV_CHANGE_Z_ORDER:
                        for (Listener l : listeners) l.onChangeWindowZOrder(a, b, cc);
                        break;
                    case (int) XCore.EV_CONTENT_UPDATE:
                        for (Listener l : listeners) l.onUpdateWindowContent(a, Math.max(1, cc));
                        break;
                    case (int) XCore.EV_GEOMETRY:
                        for (Listener l : listeners) l.onUpdateWindowGeometry(a, b != 0);
                        break;
                    case (int) XCore.EV_ATTRIBUTES:
                        for (Listener l : listeners) l.onUpdateWindowAttributes(a, ev[i + 2]);
                        break;
                    case (int) XCore.EV_PROPERTY:
                        for (Listener l : listeners) l.onModifyWindowProperty(a, b);
                        break;
                    case (int) XCore.EV_REPARENT:
                        for (Listener l : listeners) l.onReparentWindow(a, b, cc, d);
                        break;
                    case (int) XCore.EV_POINTER_MOVE:
                        for (Listener l : listeners) l.onPointerMove(a, b);
                        break;
                    case (int) XCore.EV_RELATIVE_MOUSE_BUTTON: {
                        // Relative mode: the button goes to the Windows side instead of the X clients.
                        Pointer.Button button = Pointer.Button.fromCode(a);
                        WinHandler wh = winHandler;
                        if (button != null && wh != null) {
                            wh.mouseEvent(MouseEventFlags.getFlagFor(button, b != 0), 0, 0, cc);
                        }
                        break;
                    }
                    case (int) XCore.EV_CONTENT_UPDATE_DIRECT:
                        for (Listener l : listeners) l.onUpdateWindowContentDirect(a, b, Math.max(1, cc));
                        break;
                    case (int) XCore.EV_DIRECT_CONTENT_ADDED:
                        for (Listener l : listeners) l.onDirectContentAdded(a, b);
                        break;
                    case (int) XCore.EV_DIRECT_CONTENT_REMOVED:
                        for (Listener l : listeners) l.onDirectContentRemoved(a, b);
                        break;
                    case (int) XCore.EV_COMPOSITE_REDIRECT:
                        for (Listener l : listeners) l.onCompositeRedirect(a, b != 0);
                        break;
                    default:
                        // Buttons and keys are for the clients; nothing in the app listens to them.
                        break;
                }
            } catch (RuntimeException e) {
                // One listener's bug must not stop the others or lose the rest of the batch.
                Log.e(TAG, "listener failed on event " + kind, e);
            }
        }
    }

    // ------------------------------------------------------------------ view, WinHandler

    public void setXServerView(XServerRendererView view) {
        this.xServerView = view;
    }

    public XServerRendererView getXServerView() {
        return this.xServerView;
    }

    public WinHandler getWinHandler() {
        return winHandler;
    }

    public void setWinHandler(WinHandler winHandler) {
        this.winHandler = winHandler;
        if (winHandler != null) winHandler.setMouseDisabled(disableMouse);
    }

    // ------------------------------------------------------------------ input state

    public boolean isRelativeMouseMovement() {
        return relativeMouseMovement;
    }

    public void setRelativeMouseMovement(boolean relativeMouseMovement) {
        this.relativeMouseMovement = relativeMouseMovement;
        XCore c = core;
        if (c != null) c.setRelativeMouse(relativeMouseMovement);
    }

    /**
     * Touch Mode "Touchscreen": the pointer is driven as a touchscreen. The Java server only kept
     * the flag (the activity decides what it means), and so does this one.
     */
    public void setSimulateTouchScreen(boolean simulateTouchScreen) {
        this.simulateTouchScreen = simulateTouchScreen;
    }

    public boolean isSimulateTouchScreen() {
        return simulateTouchScreen;
    }

    /**
     * Touch Mode "Off": no mouse input reaches the game from any source (finger, physical mouse,
     * layout elements, captured pointer, relative mouse). The X pointer is blocked in the server,
     * the Windows-side mouse in {@link WinHandler#mouseEvent}. The drawn cursor is the caller's
     * business (the activity hides it), so this does not touch the view.
     */
    public void setMouseDisabled(boolean mouseDisabled) {
        XCore c = core;
        if (mouseDisabled && !this.disableMouse) {
            // Release what is held first: once the flag is set these releases would be dropped
            // and a button would stay pressed in the game.
            if (c != null) {
                int held = c.pointerButtons();
                for (Pointer.Button button : new Pointer.Button[]{Pointer.Button.BUTTON_LEFT,
                        Pointer.Button.BUTTON_MIDDLE, Pointer.Button.BUTTON_RIGHT}) {
                    if ((held & (1 << button.code())) != 0) injectPointerButtonRelease(button);
                }
            }
            WinHandler wh = winHandler;
            if (wh != null && relativeMouseMovement) {
                wh.mouseEvent(MouseEventFlags.LEFTUP, 0, 0, 0);
                wh.mouseEvent(MouseEventFlags.RIGHTUP, 0, 0, 0);
                wh.mouseEvent(MouseEventFlags.MIDDLEUP, 0, 0, 0);
            }
        }
        this.disableMouse = mouseDisabled;
        if (c != null) c.setMouseDisabled(mouseDisabled);
        WinHandler wh = winHandler;
        if (wh != null) wh.setMouseDisabled(mouseDisabled);
    }

    public boolean isMouseDisabled() {
        return this.disableMouse;
    }

    // ------------------------------------------------------------------ input injection

    /** Continuous movement of an absolute input source: also reported as raw (XI2) motion. */
    public void injectPointerMove(int x, int y) {
        XCore c = core;
        if (c == null) return;
        synchronized (inputLock) {
            if (absolutePointerAnchored) c.rawMotion(x - absolutePointerX, y - absolutePointerY);
            setAbsolutePointerAnchor(x, y);
            c.pointerMove(x, y);
        }
    }

    /**
     * Jump of an absolute input source to a new position (a finger or the stylus touching down
     * somewhere else): moves the cursor without raw (XI2) motion, so games reading raw input do
     * not see the jump as a sudden flick.
     */
    public void injectPointerWarp(int x, int y) {
        XCore c = core;
        if (c == null) return;
        synchronized (inputLock) {
            setAbsolutePointerAnchor(x, y);
            c.pointerMove(x, y);
        }
    }

    /** Relative movement (touchpad, captured mouse, stick): the delta is the raw (XI2) motion. */
    public void injectPointerMoveDelta(int dx, int dy) {
        XCore c = core;
        if (c == null) return;
        synchronized (inputLock) {
            c.rawMotion(dx, dy);
            absolutePointerAnchored = false;
            c.pointerMove(c.pointerX() + dx, c.pointerY() + dy);
        }
    }

    private void setAbsolutePointerAnchor(int x, int y) {
        absolutePointerAnchored = true;
        absolutePointerX = x;
        absolutePointerY = y;
    }

    public void injectPointerButtonPress(Pointer.Button button) {
        XCore c = core;
        if (c != null) c.pointerButton(button.code(), true);
    }

    public void injectPointerButtonRelease(Pointer.Button button) {
        XCore c = core;
        if (c != null) c.pointerButton(button.code(), false);
    }

    public void injectKeyPress(XKeycode xKeycode) {
        injectKeyPress(xKeycode, 0);
    }

    public void injectKeyPress(XKeycode xKeycode, int keysym) {
        trackShift(xKeycode, true);
        XCore c = core;
        if (c != null) c.key(xKeycode.id & 0xff, keysym, true);
    }

    public void injectKeyRelease(XKeycode xKeycode) {
        trackShift(xKeycode, false);
        XCore c = core;
        if (c != null) c.key(xKeycode.id & 0xff, 0, false);
    }

    private void trackShift(XKeycode k, boolean down) {
        int bit = k == XKeycode.KEY_SHIFT_L ? 1 : k == XKeycode.KEY_SHIFT_R ? 2 : 0;
        if (bit == 0) return;
        shiftDown = down ? (shiftDown | bit) : (shiftDown & ~bit);
    }

    boolean isShiftHeld() {
        return shiftDown != 0;
    }

    // ------------------------------------------------------------------ display

    /** The refresh rates the display offers (RANDR, first = preferred) and the one in force. */
    public void setRefreshRates(short[] rates, short active) {
        this.refreshRates = rates;
        this.activeRate = active;
        XCore c = core;
        if (c != null && rates != null) applyRefreshRates(c, rates, active);
    }

    public void setActiveRate(short active) {
        this.activeRate = active;
        XCore c = core;
        if (c != null) c.setActiveRate(active);
    }

    private static void applyRefreshRates(XCore c, short[] rates, short active) {
        int[] r = new int[rates.length];
        for (int i = 0; i < r.length; i++) r[i] = rates[i];
        c.setRefreshRates(r, active);
    }

    /**
     * The size of the screen as it is actually shown. When a game sets a mode smaller than the
     * container screen, Wine resizes its virtual desktop window and the rest of the root window
     * stays empty; renderers and touch mapping then use the desktop's size so the desktop fills
     * the display instead of sitting in a corner. {@link #screenInfo} when there is no such window.
     */
    public ScreenInfo getEffectiveScreenInfo() {
        XCore c = core;
        if (c != null) {
            int w = c.screenWidth(), h = c.screenHeight();
            if (w > 0 && h > 0 && (w != screenInfo.width || h != screenInfo.height)) {
                return new ScreenInfo(w, h);
            }
        }
        return screenInfo;
    }

    // Told by the renderer when the effective screen size changed, so input mapping follows it.
    public void addOnEffectiveScreenChangedListener(Runnable listener) {
        effectiveScreenListeners.addIfAbsent(listener);
    }

    public void removeOnEffectiveScreenChangedListener(Runnable listener) {
        effectiveScreenListeners.remove(listener);
    }

    public void notifyEffectiveScreenChanged() {
        for (Runnable listener : effectiveScreenListeners) listener.run();
    }

    // ------------------------------------------------------------------ window queries

    public String getAtomName(int atom) {
        XCore c = core;
        return c != null ? c.atomName(atom) : "";
    }

    /** A window property as text, "" if there is none. */
    public String getPropertyString(int window, String atomName) {
        XCore c = core;
        return c != null ? c.propertyString(window, atomName) : "";
    }

    public int getPropertyInt(int window, String atomName, int index) {
        XCore c = core;
        return c != null ? c.propertyInt(window, atomName, index) : 0;
    }

    /** WM_CLASS of the window ("instance\0class\0"), "" if it has none. */
    public String getWindowClassName(int window) {
        return getPropertyString(window, "WM_CLASS");
    }

    public String getWindowName(int window) {
        return getPropertyString(window, "WM_NAME");
    }

    public int getWindowProcessId(int window) {
        return getPropertyInt(window, "_NET_WM_PID", 0);
    }

    public boolean isWindowWoW64(int window) {
        return getPropertyInt(window, "_NET_WM_WOW64", 0) == 1;
    }

    /** Mapped, its own window group and bigger than 1x1: a window of the game or program itself. */
    public boolean isApplicationWindow(int window) {
        XCore c = core;
        return c != null && c.isApplicationWindow(window);
    }

    /** The first window whose WM_CLASS contains the executable's name, or 0. */
    public int findWindowByProcessName(String exeName) {
        XCore c = core;
        return c != null && exeName != null ? c.findWindowByClass(exeName) : 0;
    }

    /** The icon a window sets for itself, or null. */
    public Bitmap getWindowIcon(int window) {
        XCore c = core;
        byte[] data = c != null ? c.windowIcon(window) : null;
        if (data == null || data.length < 8) return null;
        ByteBuffer buf = ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int w = buf.getInt(0), h = buf.getInt(4);
        if (w <= 0 || h <= 0 || data.length < 8 + w * h * 4) return null;
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(data, 8, w * h * 4).slice());
        return bitmap;
    }
}
