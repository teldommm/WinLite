package com.winlator.cmod.xserver;

import android.hardware.HardwareBuffer;
import android.util.SparseArray;

import com.winlator.cmod.core.CursorLocker;
import com.winlator.cmod.widget.XServerRendererView;
import com.winlator.cmod.winhandler.WinHandler;
import com.winlator.cmod.xserver.extensions.BigReqExtension;
import com.winlator.cmod.xserver.extensions.DRI3Extension;
import com.winlator.cmod.xserver.extensions.Extension;
import com.winlator.cmod.xserver.extensions.GLXExtension;
import com.winlator.cmod.xserver.extensions.MITSHMExtension;
import com.winlator.cmod.xserver.extensions.PresentExtension;
import com.winlator.cmod.xserver.extensions.RandrExtension;
import com.winlator.cmod.xserver.extensions.SyncExtension;
import com.winlator.cmod.xserver.extensions.XInputExtension;

import com.winlator.cmod.xserver.extensions.XCompositeExtension;
import java.nio.charset.Charset;
import java.util.EnumMap;
import java.util.concurrent.locks.ReentrantLock;

public class XServer {
    public enum Lockable {WINDOW_MANAGER, PIXMAP_MANAGER, DRAWABLE_MANAGER, GRAPHIC_CONTEXT_MANAGER, INPUT_DEVICE, CURSOR_MANAGER, SHMSEGMENT_MANAGER}
    public static final short VERSION = 11;
    public static final String VENDOR_NAME = "Elbrus Technologies, LLC";
    public static final Charset LATIN1_CHARSET = Charset.forName("latin1");
    public final SparseArray<Extension> extensions = new SparseArray<>();
    public final ScreenInfo screenInfo;
    public final PixmapManager pixmapManager;
    public final ResourceIDs resourceIDs = new ResourceIDs(128);
    public final GraphicsContextManager graphicsContextManager = new GraphicsContextManager();
    public final SelectionManager selectionManager;
    public final DrawableManager drawableManager;
    public final WindowManager windowManager;
    public final CursorManager cursorManager;
    public final Keyboard keyboard = Keyboard.createKeyboard(this);
    public final Pointer pointer = new Pointer(this);
    public final InputDeviceManager inputDeviceManager;
    public final GrabManager grabManager;
    public final CursorLocker cursorLocker;
    private SHMSegmentManager shmSegmentManager;
    private WinHandler winHandler;
    private final EnumMap<Lockable, ReentrantLock> locks = new EnumMap<>(Lockable.class);
    private boolean relativeMouseMovement = false;
    private boolean simulateTouchScreen = false;
    private boolean disableMouse = false;
    private boolean isGrabbed = false;
    private int surfaceFormat = Drawable.HAL_PIXEL_FORMAT_BGRA_8888;
    private XServerRendererView xServerView;
    private XClient grabbingClient = null;
    private XInputExtension xInputExtension;
    /* Last absolute position reported by an absolute input source (touchscreen, stylus, uncaptured
     * mouse). Raw (XI2) motion for these sources is the device's own movement, measured from here,
     * so cursor warps by the application and CursorLocker never leak into raw input. */
    private boolean absolutePointerAnchored = false;
    private int absolutePointerX;
    private int absolutePointerY;

    public XServer(ScreenInfo screenInfo, String surfaceFormat) {
        this.screenInfo = screenInfo;
        if ("rgba8".equals(surfaceFormat))
            this.surfaceFormat = HardwareBuffer.RGBA_8888;
        else
            this.surfaceFormat = Drawable.HAL_PIXEL_FORMAT_BGRA_8888;

        /* locks first: CursorLocker's timer starts immediately and takes them */
        for (Lockable lockable : Lockable.values()) locks.put(lockable, new ReentrantLock());
        cursorLocker = new CursorLocker(this);

        pixmapManager = new PixmapManager();
        drawableManager = new DrawableManager(this);
        cursorManager = new CursorManager(drawableManager);
        windowManager = new WindowManager(screenInfo, drawableManager);
        selectionManager = new SelectionManager(windowManager);
        inputDeviceManager = new InputDeviceManager(this);
        grabManager = new GrabManager(this);

        DesktopHelper.attachTo(this);
        setupExtensions();
    }
    
    public int getSurfaceFormat() {
        return this.surfaceFormat;
    }

    public boolean isRelativeMouseMovement() {
        return relativeMouseMovement;
    }

    public void setRelativeMouseMovement(boolean relativeMouseMovement) {
        cursorLocker.setEnabled(!relativeMouseMovement);
        this.relativeMouseMovement = relativeMouseMovement;
    }

    public boolean isSimulateTouchScreen() { return simulateTouchScreen; }

    public void setSimulateTouchScreen(boolean simulateTouchScreen) {
        this.simulateTouchScreen = simulateTouchScreen;
    }
    
    public void setXServerView(XServerRendererView view) {
        this.xServerView = view;
    }
    
    public XServerRendererView getXServerView() {
        return this.xServerView;
    }
    
    public void setMouseDisabled(boolean mouseDisabled) {
        this.disableMouse = mouseDisabled;
        xServerView.setCursorVisible(!mouseDisabled);
    }
    
    public boolean isMouseDisabled() {
        return this.disableMouse;
    }

    public WinHandler getWinHandler() {
        return winHandler;
    }

    public void setWinHandler(WinHandler winHandler) {
        this.winHandler = winHandler;
    }

    public SHMSegmentManager getSHMSegmentManager() {
        return shmSegmentManager;
    }

    public void setSHMSegmentManager(SHMSegmentManager shmSegmentManager) {
        this.shmSegmentManager = shmSegmentManager;
    }

    private class SingleXLock implements XLock {
        private final ReentrantLock lock;

        private SingleXLock(Lockable lockable) {
            this.lock = locks.get(lockable);
            lock.lock();
        }

        @Override
        public void close() {
            lock.unlock();
        }
    }

    private class MultiXLock implements XLock {
        private final Lockable[] lockables;

        private MultiXLock(Lockable[] lockables) {
            this.lockables = lockables;
            for (Lockable lockable : lockables) locks.get(lockable).lock();
        }

        @Override
        public void close() {
            for (int i = lockables.length - 1; i >= 0; i--) {
                locks.get(lockables[i]).unlock();
            }
        }
    }

    public XLock lock(Lockable lockable) {
        return new SingleXLock(lockable);
    }

    public XLock lock(Lockable... lockables) {
        return new MultiXLock(lockables);
    }

    public XLock lockAll() {
        return new MultiXLock(Lockable.values());
    }

    public Extension getExtensionByName(String name) {
        for (int i = 0; i < extensions.size(); i++) {
            Extension extension = extensions.valueAt(i);
            if (extension.getName().equals(name)) return extension;
        }
        return null;
    }

    /** Continuous movement of an absolute input source: also reported as raw (XI2) motion. */
    public void injectPointerMove(int x, int y) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            if (absolutePointerAnchored) xInputExtension.sendRawMotion(x - absolutePointerX, y - absolutePointerY);
            setAbsolutePointerAnchor(x, y);
            pointer.setPosition(x, y);
        }
    }

    /**
     * Jump of an absolute input source to a new position (a finger or the stylus touching down
     * somewhere else): moves the cursor without raw (XI2) motion, so games reading raw input do
     * not see the jump as a sudden flick.
     */
    public void injectPointerWarp(int x, int y) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            setAbsolutePointerAnchor(x, y);
            pointer.setPosition(x, y);
        }
    }

    /** Relative movement (touchpad, captured mouse, stick): the delta is the raw (XI2) motion. */
    public void injectPointerMoveDelta(int dx, int dy) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            xInputExtension.sendRawMotion(dx, dy);
            absolutePointerAnchored = false;
            pointer.setPosition(pointer.getX() + dx, pointer.getY() + dy);
        }
    }

    private void setAbsolutePointerAnchor(int x, int y) {
        absolutePointerAnchored = true;
        absolutePointerX = x;
        absolutePointerY = y;
    }

    public void injectPointerButtonPress(Pointer.Button buttonCode) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setButton(buttonCode, true);
        }
    }

    public void injectPointerButtonRelease(Pointer.Button buttonCode) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setButton(buttonCode, false);
        }
    }

    public void injectKeyPress(XKeycode xKeycode) {
        injectKeyPress(xKeycode, 0);
    }

    public void injectKeyPress(XKeycode xKeycode, int keysym) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            keyboard.setKeyPress(xKeycode.id, keysym);
        }
    }

    public void injectKeyRelease(XKeycode xKeycode) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            keyboard.setKeyRelease(xKeycode.id);
        }
    }

    private void setupExtensions() {
        extensions.put(BigReqExtension.MAJOR_OPCODE, new BigReqExtension());
        extensions.put(MITSHMExtension.MAJOR_OPCODE, new MITSHMExtension());
        extensions.put(DRI3Extension.MAJOR_OPCODE, new DRI3Extension(this));
        extensions.put(PresentExtension.MAJOR_OPCODE, new PresentExtension(this));
        extensions.put(RandrExtension.MAJOR_OPCODE, new RandrExtension(screenInfo));
        extensions.put(SyncExtension.MAJOR_OPCODE, new SyncExtension(this));
        extensions.put(GLXExtension.MAJOR_OPCODE, new GLXExtension(this));
        extensions.put(XCompositeExtension.MAJOR_OPCODE, new XCompositeExtension(this));
        xInputExtension = new XInputExtension(this);
        extensions.put(XInputExtension.MAJOR_OPCODE, xInputExtension);
    }

    public XInputExtension getXInputExtension() {
        return xInputExtension;
    }

    public <T extends Extension> T getExtension(int opcode) {
        return (T)extensions.get(opcode);
    }

    public synchronized void setGrabbed(boolean grabbed, XClient client) {
        this.isGrabbed = grabbed;
        this.grabbingClient = client;
    }

    public synchronized boolean isGrabbedBy(XClient client) {
        return isGrabbed && grabbingClient == client;
    }

    /** Wine's virtual desktop: a top-level, non-override-redirect window of explorer.exe. */
    public boolean isDesktopWindow(Window window) {
        return window != null && window.getParent() == windowManager.rootWindow
            && !window.attributes.isOverrideRedirect()
            && window.getClassName().contains("explorer.exe");
    }

    /**
     * The size of the screen as it is actually shown. When a game sets a mode smaller than the
     * container screen, Wine resizes its virtual desktop window and the rest of the root window
     * stays empty; renderers and touch mapping then use the desktop's size so the desktop fills
     * the display instead of sitting in a corner. {@link #screenInfo} when there is no such window.
     */
    public ScreenInfo getEffectiveScreenInfo() {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER)) {
            for (Window child : windowManager.rootWindow.getChildren()) {
                if (!child.attributes.isMapped() || !isDesktopWindow(child)) continue;
                short width = child.getWidth(), height = child.getHeight();
                boolean fits = width >= 320 && height >= 200
                    && width <= screenInfo.width && height <= screenInfo.height;
                if (fits && (width < screenInfo.width || height < screenInfo.height)) {
                    return new ScreenInfo(width, height);
                }
                break;
            }
        }
        return screenInfo;
    }

    // Told by the renderer when the effective screen size changed, so input mapping follows it.
    private final java.util.concurrent.CopyOnWriteArrayList<Runnable> effectiveScreenListeners =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    public void addOnEffectiveScreenChangedListener(Runnable listener) {
        effectiveScreenListeners.addIfAbsent(listener);
    }

    public void removeOnEffectiveScreenChangedListener(Runnable listener) {
        effectiveScreenListeners.remove(listener);
    }

    public void notifyEffectiveScreenChanged() {
        for (Runnable listener : effectiveScreenListeners) listener.run();
    }
}
