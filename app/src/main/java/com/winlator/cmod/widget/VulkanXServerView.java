package com.winlator.cmod.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.winlator.cmod.R;
import java.io.File;
import android.widget.Toast;
import com.winlator.cmod.ui.toast.WinToast;
import com.winlator.cmod.renderer.GPUImage;
import com.winlator.cmod.xserver.Atom;
import com.winlator.cmod.xserver.Bitmask;
import com.winlator.cmod.xserver.Cursor;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.Property;
import com.winlator.cmod.xserver.ScreenInfo;
import com.winlator.cmod.xserver.SurfaceFormat;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.WindowAttributes;
import com.winlator.cmod.xserver.WindowManager;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XServer;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import dalvik.annotation.optimization.FastNative;

@SuppressLint("ViewConstructor")
public class VulkanXServerView extends XServerRendererView implements SurfaceHolder.Callback,
        WindowManager.OnWindowModificationListener, Pointer.OnPointerMotionListener {

    private long nativeHandle = 0;
    private final Object lock = new Object();
    private final ExecutorService eventExecutor = Executors.newSingleThreadExecutor();
    private final Set<Integer> compositeRedirectedWindows = ConcurrentHashMap.newKeySet();

    // The part of the X screen that is shown (see XServer.getEffectiveScreenInfo): the Wine
    // desktop's size when a game shrank it below the container screen, else the screen itself.
    private volatile ScreenInfo effectiveScreen;
    private boolean fullscreen = false;
    private float magnifierZoom = 1.0f;
    private boolean screenOffsetYRelativeToCursor = false;
    public int surfaceWidth;
    public int surfaceHeight;
    private String[] unviewableWMClasses = null;
    private boolean cursorVisible = false;
    private String driverPath = null;
    private ExecutorService initExecutor = null;
    private volatile boolean initComplete = false;
    private volatile boolean inPipMode = false;
    private String driverLibraryName = null;
    private String nativeLibDir = null;
    private boolean validationEnabled = false;
    private Drawable rootCursorDrawable;
    private Cursor lastCursor = null;
    private static volatile boolean gpuImageChecked = false;

    private int     pendingPresentMode    = 2;
    private int     pendingStretchMode    = 0;
    private int     pendingFilterMode     = 0;
    private int     pendingPostFXMode     = 0;
    private float   pendingSharpness      = 0.5f;

    private WinlatorHUD hudRef = null;
    private FrameRating classicHudRef = null;
    private volatile int fpsWindowId = -1;
    private volatile int fpsLimit = 0;
    // ADPF switch (opt-in, off by default); applied to the renderer once it exists.
    private volatile boolean perfHint = false;
    private com.winlator.cmod.core.PerfHintThreads.Listener perfHintListener;
    // Display vsync for frame pacing and for Present's MSC/UST; runs while a renderer exists.
    private final VsyncClock vsyncClock = new VsyncClock(this::onVsyncTick);

    private static volatile boolean nativeLibLoaded = false;

    public static synchronized void loadNativeLibrary() {
        if (!nativeLibLoaded) {
            System.loadLibrary("vulkan_renderer");
            nativeLibLoaded = true;
        }
    }

    public VulkanXServerView(Context context, XServer xServer) {
        super(context, xServer);
        setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        getHolder().addCallback(this);

        rootCursorDrawable = createRootCursorDrawable(xServer.getSurfaceFormat());
        xServer.setXServerView(this);
        effectiveScreen = xServer.getEffectiveScreenInfo();
        xServer.windowManager.addOnWindowModificationListener(this);
        xServer.pointer.addOnPointerMotionListener(this);
    }

    private Drawable createRootCursorDrawable(SurfaceFormat format) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            Bitmap bitmap = BitmapFactory.decodeResource(getContext().getResources(), R.drawable.cursor, options);
            return Drawable.fromBitmap(bitmap, format);
        } catch (Exception e) { return null; }
    }

    private native long nativeInit(Surface surface,
                                   int screenWidth, int screenHeight,
                                   String driverPath, String libraryName, String nativeLibDir,
                                   boolean validationEnabled);
    private native void nativeResize(long handle, int width, int height);
    private native void nativeDestroy(long handle);
    private native void nativeDetachSurface(long handle);
    private native boolean nativeReattachSurface(long handle, Surface surface);
    private native int[] nativeGetSwapchainSize(long handle);

    /* @FastNative only where the native side never waits: a thread in a @FastNative call cannot
     * be suspended, so one that blocks (renderMutex is held across texture imports, and across
     * a fence wait when the cursor changes size; frameMutex across a whole frame) holds up every
     * garbage collection - and every thread, the UI thread included - until it returns. The
     * methods that lock renderMutex or frameMutex are plain native calls. */
    private native void nativeUpdateWindowContentAHB(long handle, long contentId,
        long sourceId, long ahbPtr, short width, short height, int x, int y);
    @FastNative private native void nativeSetDisplayRefreshRate(long handle, float hz);
    private native void nativePresentFrame(long handle, long contentId, long sourceId,
        long ahbPtr, long guestFrameNs, long[] plan);
    private native void nativeSetFpsLimit(long handle, int fps);
    // ADPF. Plain natives on purpose: they take the renderer's locks or a mutex.
    private native int nativePerformanceHintLevel();
    private native void nativeSetPerformanceHint(long handle, boolean enabled);
    private native void nativeSetPerformanceHintThreads(long handle, int[] tids);
    private native boolean nativeSourceBusy(long handle, long sourceId);
    private native long nativeBeginSourceIdleWait(long handle, long sourceId);
    private native void nativeEndSourceIdleWait(long handle, long serial, long timeoutNs);
    @FastNative private native void nativeOnVsync(long handle, long frameTimeNs, long deadlineNs, long presentNs);
    private native void nativeReleaseDrawable(long handle, long drawableId);
    private native void nativeSetTransformAndScissor(long handle,
        float ox, float oy, float sx, float sy,
        boolean hasScissor, int scX, int scY, int scW, int scH);
    @FastNative private native void nativeSetPointerPos(long handle, short x, short y);
    @FastNative private native void nativeSetCursorVisible(long handle, boolean visible);
    private native void nativeUpdateCursorImage(long handle,
        ByteBuffer pixels, short width, short height, short stride, short hotX, short hotY,
        boolean rgba);
    @FastNative private native void nativeSetVerboseLog(long handle, boolean v);
    @FastNative private native void nativeSetSharpness(long handle, float sharpness);

    private native void nativeDumpRendererInfo(long handle);
    private native void nativeSetFilterMode(long handle, int mode);
    private native void nativeSetStretchMode(long handle, int mode);
    private native void nativeSetPostFXMode(long handle, int mode);
    private native void nativeSetPresentMode(long handle, int mode);
    private native int[] nativeGetSupportedPresentModes(long handle);

    private native void nativeInitRootWindow(long handle, long rootId, long contentId, int width, int height);
    private native void nativeCreateWindow(long handle, long id, long parentId, long contentId,
                                           int x, int y, int width, int height);
    private native void nativeDestroyWindow(long handle, long id);
    private native void nativeMapWindow(long handle, long id);
    private native void nativeUnmapWindow(long handle, long id);
    private native void nativeReparentWindow(long handle, long id, long newParentId);
    private native void nativeUpdateWindowGeometry(long handle, long id, long contentId,
                                                   int x, int y, int width, int height);
    private native void nativeSyncChildOrder(long handle, long parentId, long[] orderedChildIds, int count);
    private native void nativeSetWindowViewable(long handle, long id, boolean viewable);

    // Renderer key of a drawable: its process-unique uid (0 for "no drawable", e.g. an
    // InputOnly window). identityHashCode() was used here before and is not unique.
    private static long did(Drawable d) {
        return d != null ? d.uid : 0L;
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        onSurfaceCreated(holder.getSurface());
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        onSurfaceChanged(width, height);
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        onSurfaceDestroyed();
    }

    public void onSurfaceCreated(Surface surface) {
        if (!gpuImageChecked) { GPUImage.checkIsSupported(); gpuImageChecked = true; }
        if (initExecutor != null) {
            initExecutor.shutdownNow();
            try { initExecutor.awaitTermination(3, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        initExecutor = Executors.newSingleThreadExecutor();

        final boolean isColdInit = (nativeHandle == 0);

        initExecutor.execute(() -> {
            synchronized (lock) {
                if (nativeHandle != 0) {
                    boolean ok = nativeReattachSurface(nativeHandle, surface);
                    if (!ok) {
                        nativeDestroy(nativeHandle);
                        nativeHandle = 0;
                    } else {

                        nativeSetPresentMode(nativeHandle, pendingPresentMode);
                        nativeSetFilterMode(nativeHandle, pendingFilterMode);
                        nativeSetStretchMode(nativeHandle, pendingStretchMode);
                        nativeSetPostFXMode(nativeHandle, pendingPostFXMode);
                        nativeSetSharpness(nativeHandle, pendingSharpness);
                        updateTransform();
                        // Not nativeSetCursorVisible(cursorVisible): that ignores an X cursor the
                        // game made invisible (empty mask), and lastCursor is unchanged after the
                        // surface comes back, so nothing would hide it again.
                        restoreCursorStateLocked();
                        pushDisplayRefreshRateLocked();
                        nativeSetFpsLimit(nativeHandle, fpsLimit);
                        vsyncClock.start();
                        initComplete = true;
                        return;
                    }
                }

                nativeHandle = nativeInit(
                    surface,
                    xServer.screenInfo.width, xServer.screenInfo.height,
                    driverPath, driverLibraryName, nativeLibDir, validationEnabled);

                if (nativeHandle != 0) {
                    nativeSetPresentMode(nativeHandle, pendingPresentMode);
                    nativeSetFilterMode(nativeHandle, pendingFilterMode);
                    nativeSetStretchMode(nativeHandle, pendingStretchMode);
                    nativeSetPostFXMode(nativeHandle, pendingPostFXMode);
                    nativeSetSharpness(nativeHandle, pendingSharpness);
                    updateTransform();
                    pushDisplayRefreshRateLocked();
                    nativeSetFpsLimit(nativeHandle, fpsLimit);
                    if (perfHint) {
                        // Threads first: the session is opened with them in one go.
                        nativeSetPerformanceHintThreads(nativeHandle,
                            com.winlator.cmod.core.PerfHintThreads.snapshot());
                        nativeSetPerformanceHint(nativeHandle, true);
                    }
                    vsyncClock.start();
                    nativeSetCursorVisible(nativeHandle, false);

                    if (isColdInit) {
                        Window root = xServer.windowManager.rootWindow;
                        nativeInitRootWindow(nativeHandle, root.id, did(root.getContent()),
                            xServer.screenInfo.width, xServer.screenInfo.height);
                    }
                    // A fresh native renderer has no cursor image either: re-send the current
                    // X cursor (image + its own visibility), not just the global flag.
                    restoreCursorStateLocked();
                    // ...and no window tree. Window events only ever reached the renderer that
                    // existed when they happened: after a failed re-attach (renderer re-created)
                    // the screen stayed black for good, and windows created before the first
                    // renderer was up were never shown. Queued behind every window event already
                    // sent, so it rebuilds the tree from the X server's current state.
                    queueEvent(this::replayWindowTree);
                }
            }
            synchronized (lock) {
                if (nativeHandle != 0) {
                    nativeSetVerboseLog(nativeHandle, validationEnabled);
                    nativeDumpRendererInfo(nativeHandle);
                }
            }
            initComplete = true;
        });
    }

    public void onSurfaceChanged(int width, int height) {
        if (inPipMode) return;
        surfaceWidth = width; surfaceHeight = height;
        ScreenInfo inner = effectiveScreen;
        viewTransformation.update(width, height, inner.width, inner.height);
        synchronized (lock) {
            if (nativeHandle != 0) {
                nativeResize(nativeHandle, width, height);
                updateTransform();
                pushDisplayRefreshRateLocked();
            }
        }
    }

    /**
     * The render thread paces cursor-only and back-to-back frames against the panel's refresh
     * period. Must be called with {@code lock} held; re-sent on every display mode change.
     */
    private void pushDisplayRefreshRateLocked() {
        if (nativeHandle == 0) return;
        android.view.Display display = getDisplay();
        float hz = display != null ? display.getRefreshRate() : 0f;
        if (hz > 1f) {
            nativeSetDisplayRefreshRate(nativeHandle, hz);
            vsyncClock.setNominalPeriod(Math.round(1.0e9 / hz));
        }
    }

    private final android.hardware.display.DisplayManager.DisplayListener displayListener =
            new android.hardware.display.DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) {}
        @Override public void onDisplayRemoved(int displayId) {}
        @Override public void onDisplayChanged(int displayId) {
            android.view.Display display = getDisplay();
            if (display == null || display.getDisplayId() != displayId) return;
            synchronized (lock) { pushDisplayRefreshRateLocked(); }
        }
    };

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        android.hardware.display.DisplayManager dm = (android.hardware.display.DisplayManager)
                getContext().getSystemService(Context.DISPLAY_SERVICE);
        if (dm != null) dm.registerDisplayListener(displayListener, getHandler());
    }

    @Override
    protected void onDetachedFromWindow() {
        android.hardware.display.DisplayManager dm = (android.hardware.display.DisplayManager)
                getContext().getSystemService(Context.DISPLAY_SERVICE);
        if (dm != null) dm.unregisterDisplayListener(displayListener);
        super.onDetachedFromWindow();
    }

    public void onSurfaceDestroyed() {
        initComplete = false;
        if (initExecutor != null) {
            initExecutor.shutdownNow();
            try { initExecutor.awaitTermination(3, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            initExecutor = null;
        }
        vsyncClock.stop();
        synchronized (lock) {
            if (nativeHandle != 0) nativeDetachSurface(nativeHandle);
        }
    }

    private void onVsyncTick(long frameTimeNs, long deadlineNs, long presentNs) {
        synchronized (lock) {
            if (nativeHandle != 0) nativeOnVsync(nativeHandle, frameTimeNs, deadlineNs, presentNs);
        }
    }

    @Override
    public boolean getVsync(long nowNs, long[] out) { return vsyncClock.sampleAt(nowNs, out); }

    @Override
    public boolean isGuestBufferBusy(Drawable buffer) {
        synchronized (lock) {
            return nativeHandle != 0 && buffer != null && nativeSourceBusy(nativeHandle, did(buffer));
        }
    }

    @Override
    public void waitGuestBufferIdle(Drawable buffer, long timeoutNs) {
        final long handle, serial;
        // The wait is registered under the lock nativeDestroy runs under, and happens outside it
        // (it blocks): the native side keeps the device alive until a registered wait returns.
        synchronized (lock) {
            if (nativeHandle == 0 || buffer == null) return;
            handle = nativeHandle;
            serial = nativeBeginSourceIdleWait(handle, did(buffer));
        }
        if (serial != 0) nativeEndSourceIdleWait(handle, serial, timeoutNs);
    }

    @Override
    public void onDestroy() {
        forceCleanup();
    }

    public void forceCleanup() {
        fpsWindowId = -1;
        hudRef = null;
        classicHudRef = null;
        initComplete = false;
        if (initExecutor != null) {
            initExecutor.shutdownNow();
            try { initExecutor.awaitTermination(3, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            initExecutor = null;
        }
        compositeRedirectedWindows.clear();
        vsyncClock.stop();
        if (perfHintListener != null)
            com.winlator.cmod.core.PerfHintThreads.clearListener(perfHintListener);
        synchronized (lock) {
            if (nativeHandle != 0) {
                nativeDestroy(nativeHandle);
                nativeHandle = 0;
            }
        }
        // Queued tasks still run (they see nativeHandle == 0 and do nothing); the thread then
        // exits instead of outliving the view.
        eventExecutor.shutdown();
    }

    public void queueEvent(Runnable r) {
        try {
            eventExecutor.execute(r);
        } catch (RejectedExecutionException ignored) {
            // The view was destroyed; there is no renderer left to update.
        }
    }
    @Override
    public void onPause()  {}
    @Override
    public void onResume() {}

    private void updateTransform() {
        if (nativeHandle == 0) return;
        final float zoom = magnifierZoom;
        final float ptrX = xServer.pointer.getX();
        final float ptrY = xServer.pointer.getY();

        // The native scene is laid out in container (screen) coordinates; when only the top-left
        // part of it (the shrunken Wine desktop) is shown, the scene scale and offset are
        // computed for that part and then stretched by screen/effective. fx = fy = 1 normally.
        final ScreenInfo inner = effectiveScreen;
        final float fx = (float) xServer.screenInfo.width / inner.width;
        final float fy = (float) xServer.screenInfo.height / inner.height;

        final float ox, oy, sx, sy;
        if (fullscreen) {
            viewTransformation.update(surfaceWidth, surfaceHeight, inner.width, inner.height);
            if (zoom != 1.0f) {
                ox = (viewTransformation.sceneOffsetX + ptrX * viewTransformation.sceneScaleX * (1f - zoom)) * fx;
                oy = (viewTransformation.sceneOffsetY + ptrY * viewTransformation.sceneScaleY * (1f - zoom)) * fy;
                sx = viewTransformation.sceneScaleX * zoom * fx;
                sy = viewTransformation.sceneScaleY * zoom * fy;
            } else {
                ox = 0f; oy = 0f; sx = fx; sy = fy;
            }
        } else {
            float py = 0;
            if (screenOffsetYRelativeToCursor) {
                short halfH = (short)(inner.height / 2);
                py = Math.max(0, Math.min(ptrY - halfH / 2.0f, halfH));
            }
            ox = (viewTransformation.sceneOffsetX + ptrX * viewTransformation.sceneScaleX * (1f - zoom)) * fx;
            oy = (viewTransformation.sceneOffsetY - py + ptrY * viewTransformation.sceneScaleY * (1f - zoom)) * fy;
            sx = viewTransformation.sceneScaleX * zoom * fx;
            sy = viewTransformation.sceneScaleY * zoom * fy;
        }

        final boolean needScissor = !(fullscreen && zoom == 1.0f);
        nativeSetTransformAndScissor(nativeHandle, ox, oy, sx, sy,
            needScissor,
            viewTransformation.viewOffsetX, viewTransformation.viewOffsetY,
            viewTransformation.viewWidth,   viewTransformation.viewHeight);
    }

    private boolean isRootChild(Window window) {
        return window != null && window.getParent() == xServer.windowManager.rootWindow;
    }

    /**
     * Re-reads the effective screen size and re-applies the transform. Runs on eventExecutor:
     * the X server locks are taken (inside getEffectiveScreenInfo) before {@code lock}, as
     * everywhere else.
     */
    private void refreshDesktopTransform() {
        final ScreenInfo inner = xServer.getEffectiveScreenInfo();
        final ScreenInfo old = effectiveScreen;
        final boolean changed = old == null || old.width != inner.width || old.height != inner.height;
        synchronized (lock) {
            effectiveScreen = inner;
            if (surfaceWidth > 0 && surfaceHeight > 0) {
                viewTransformation.update(surfaceWidth, surfaceHeight, inner.width, inner.height);
            }
            updateTransform();
        }
        if (changed) xServer.notifyEffectiveScreenChanged();
    }

    /**
     * Re-applies the cursor after the native side was re-attached or re-created (e.g. on return
     * from ControlsEditorActivity). Must be called with {@code lock} held. Takes the cursor of the
     * window under the pointer, so a game-hidden cursor (Cursor.isVisible() == false) stays hidden.
     */
    private void restoreCursorStateLocked() {
        if (nativeHandle == 0) return;
        Window pw = xServer.inputDeviceManager.getPointWindow();
        lastCursor = pw != null ? pw.attributes.getCursor() : null;
        sendCursorToNative(lastCursor);
    }

    private void sendCursorToNative(Cursor cursor) {
        if (nativeHandle == 0) return;
        Drawable cd; short hotX = 0, hotY = 0;
        boolean effVis = cursorVisible;
        if (cursor != null) {
            if (!cursor.isVisible()) effVis = false;
            cd = cursor.cursorImage; hotX = (short)cursor.hotSpotX; hotY = (short)cursor.hotSpotY;
        } else { cd = rootCursorDrawable; }
        nativeSetCursorVisible(nativeHandle, effVis);
        if (effVis && cd != null) {
            // Checked under renderLock: Drawable.release() zeroes backingAHB under it.
            synchronized (cd.renderLock) {
                final long ahb = cd.backingAHB;
                if (ahb == 0) return;
                ByteBuffer pixels = cd.lockBuffer(ahb);
                if (pixels != null) {
                    try {
                        // The renderer's cursor texture is always RGBA; the native side swaps
                        // R and B when the cursor drawable is BGRA.
                        nativeUpdateCursorImage(nativeHandle, pixels, cd.width, cd.height,
                            cd.getStride(), hotX, hotY, cd.format == SurfaceFormat.RGBA);
                    } finally {
                        cd.unlockBuffer(ahb);
                    }
                }
            }
        }
    }

    /**
     * Sends the X server's whole window tree (geometry, stacking, map state, viewability and the
     * X-drawn content) to the native renderer. Runs on eventExecutor, in order with the window
     * events: a change made after the snapshot below is queued behind this and applied on top.
     * Lock order is the X server's locks first, then {@code lock}, as everywhere else.
     */
    private void replayWindowTree() {
        try (XLock xLock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
            synchronized (lock) {
                if (nativeHandle == 0) return;
                Window root = xServer.windowManager.rootWindow;
                nativeInitRootWindow(nativeHandle, root.id, did(root.getContent()),
                    xServer.screenInfo.width, xServer.screenInfo.height);
                replayChildrenLocked(root);
            }
        }
    }

    private void replayChildrenLocked(Window parent) {
        List<Window> children = parent.getChildren();
        if (children.isEmpty()) return;
        final long[] orderedIds = new long[children.size()];
        for (int i = 0; i < orderedIds.length; i++) {
            Window window = children.get(i);
            orderedIds[i] = window.id;
            Drawable content = window.getContent();
            nativeCreateWindow(nativeHandle, window.id, parent.id, did(content),
                window.getX(), window.getY(), window.getWidth(), window.getHeight());
            nativeSetWindowViewable(nativeHandle, window.id, isViewable(window));
            if (window.attributes.isMapped()) {
                nativeMapWindow(nativeHandle, window.id);
                if (content != null) {
                    synchronized (content.renderLock) {
                        final long ahb = content.backingAHB;
                        if (ahb != 0) {
                            nativeUpdateWindowContentAHB(nativeHandle, did(content), did(content),
                                ahb, content.width, content.height, window.getRootX(), window.getRootY());
                        }
                    }
                }
            }
            replayChildrenLocked(window);
        }
        // Exact stacking order (also drops any duplicate child entries the creates may leave).
        nativeSyncChildOrder(nativeHandle, parent.id, orderedIds, orderedIds.length);
    }

    /** checkViewable()'s verdict without its side effects. */
    private boolean isViewable(Window window) {
        if (unviewableWMClasses == null || window == xServer.windowManager.rootWindow) return true;
        String wc = window.getClassName();
        for (String cls : unviewableWMClasses) if (wc.contains(cls)) return false;
        return true;
    }

    private void checkViewable(Window window) {
        if (unviewableWMClasses == null || window == xServer.windowManager.rootWindow) return;
        boolean viewable = true;
        String wc = window.getClassName();
        for (String cls : unviewableWMClasses) {
            if (wc.contains(cls)) {
                if (window.attributes.isEnabled()) window.disableAllDescendants();
                viewable = false;
                break;
            }
        }
        final long id = window.id;
        final boolean v = viewable;
        queueEvent(() -> {
            synchronized (lock) {
                if (nativeHandle != 0) nativeSetWindowViewable(nativeHandle, id, v);
            }
        });
    }

    @Override
    public void onCreateWindow(Window window, Window parent) {
        final long id = window.id;
        final long parentId = parent != null ? parent.id : 0;
        final long contentId = did(window.getContent());
        final int x = window.getX(), y = window.getY(), w = window.getWidth(), h = window.getHeight();
        queueEvent(() -> {
            synchronized (lock) {
                if (nativeHandle != 0) nativeCreateWindow(nativeHandle, id, parentId, contentId, x, y, w, h);
            }
        });
        checkViewable(window);
    }

    @Override
    public void onDestroyWindow(Window window) {
        final long id = window.id;
        compositeRedirectedWindows.remove(window.id);
        queueEvent(() -> {
            synchronized (lock) {
                if (nativeHandle != 0) nativeDestroyWindow(nativeHandle, id);
            }
        });
    }

    @Override
    public void onMapWindow(Window window) {
        if (isRootChild(window)) queueEvent(this::refreshDesktopTransform);
        final long id = window.id;
        queueEvent(() -> {
            synchronized (lock) {
                if (nativeHandle != 0) nativeMapWindow(nativeHandle, id);
            }
        });
        checkViewable(window);
    }

    @Override
    public void onUnmapWindow(Window window) {
        if (isRootChild(window)) queueEvent(this::refreshDesktopTransform);
        final long id = window.id;
        queueEvent(() -> {
            synchronized (lock) {
                if (nativeHandle != 0) nativeUnmapWindow(nativeHandle, id);
            }
        });
    }

    @Override
    public void onReparentWindow(Window window, Window newParent, short x, short y) {
        final long id = window.id;
        final long newParentId = newParent != null ? newParent.id : 0;
        final long contentId = did(window.getContent());
        final int w = window.getWidth(), h = window.getHeight();
        queueEvent(() -> {
            synchronized (lock) {
                if (nativeHandle != 0) {
                    nativeReparentWindow(nativeHandle, id, newParentId);
                    nativeUpdateWindowGeometry(nativeHandle, id, contentId, x, y, w, h);
                }
            }
        });
    }

    @Override
    public void onChangeWindowZOrder(Window.StackMode stackMode, Window window, Window sibling) {
        Window parent = window.getParent();
        if (parent == null) return;
        List<Window> children = parent.getChildren();
        final long parentId = parent.id;
        final long[] orderedIds = new long[children.size()];
        for (int i = 0; i < orderedIds.length; i++) orderedIds[i] = children.get(i).id;
        queueEvent(() -> {
            synchronized (lock) {
                if (nativeHandle != 0) nativeSyncChildOrder(nativeHandle, parentId, orderedIds, orderedIds.length);
            }
        });
    }

    @Override
    public void onUpdateWindowGeometry(Window window, boolean resized) {
        if (resized && isRootChild(window)) queueEvent(this::refreshDesktopTransform);
        final long id = window.id;
        final long contentId = did(window.getContent());
        final int x = window.getX(), y = window.getY(), w = window.getWidth(), h = window.getHeight();
        queueEvent(() -> {
            synchronized (lock) {
                if (nativeHandle != 0) nativeUpdateWindowGeometry(nativeHandle, id, contentId, x, y, w, h);
            }
        });
    }

    @Override
    public void onModifyWindowProperty(Window window, Property property) {
        // WM_CLASS is what marks Wine's desktop window; it may be set after the window is mapped.
        if (property != null && property.name == Atom.getId("WM_CLASS") && isRootChild(window)
            && window.attributes.isMapped()) {
            queueEvent(this::refreshDesktopTransform);
        }

        checkViewable(window);
    }

    @Override
    public void onUpdateWindowAttributes(Window window, Bitmask mask) {
        if (mask.isSet(WindowAttributes.FLAG_CURSOR)) {
            synchronized (lock) {
                Window pw = xServer.inputDeviceManager.getPointWindow();
                if (pw == window) { lastCursor = window.attributes.getCursor(); sendCursorToNative(lastCursor); }
            }
        }
    }

    @Override
    public void onUpdateWindowContentDirect(Window window, Drawable drawable) {
        onUpdateWindowContentDirect(window, drawable, (short) 0, (short) 0);
    }

    public void onUpdateWindowContentDirect(Window window, Drawable pixmap, short xOff, short yOff) {
        countFrameForHud(window);
        synchronized (lock) {
            if (nativeHandle == 0 || pixmap == null) return;
            final Drawable content = window.getContent();
            if (content == null) return;
            final int rx = window.getRootX() + xOff, ry = window.getRootY() + yOff;
            synchronized (pixmap.renderLock) {
                final long ahb = pixmap.backingAHB;
                if (ahb != 0) {
                    // The window shows the pixmap: the texture is keyed by the window's content,
                    // the imported buffer by the pixmap it came from.
                    nativeUpdateWindowContentAHB(nativeHandle, did(content), did(pixmap),
                        ahb, pixmap.width, pixmap.height, rx, ry);
                }
            }
        }
    }

    /**
     * PresentPixmap: one call into the renderer that swaps the window's content (at once, or at
     * the frame's display slot under the FPS limit), counts the present, wakes the render thread
     * once, and returns the plan for handing buffers back to the guest.
     */
    @Override
    public void onGuestPresent(Window window, Drawable pixmap, long guestFrameNs, long[] plan) {
        countFrameForHud(window);
        synchronized (lock) {
            if (nativeHandle == 0 || pixmap == null) return;
            final Drawable content = window.getContent();
            if (content == null) return;
            synchronized (pixmap.renderLock) {
                final long ahb = pixmap.backingAHB;
                if (ahb != 0) {
                    nativePresentFrame(nativeHandle, did(content), did(pixmap), ahb,
                        guestFrameNs, plan);
                }
            }
        }
    }

    private void countFrameForHud(Window window) {
        if (window.id == fpsWindowId) {
            // Under the lock, like every other native call: nativeDestroy runs under it, and this
            // used to read nativeHandle and call in without it - into a renderer that could be
            // being deleted on another thread. The HUD updates below stay outside.
            if (hudRef != null) hudRef.onFrame();
            if (classicHudRef != null) classicHudRef.update();
        }
    }

    public void addDirectContent(int windowId, Drawable drawable, GPUImage gpuImage) {

    }

    @Override
    public void onDrawableReleased(Drawable drawable) {
        if (drawable == null) return;
        // Synchronous (not queueEvent): the native side must stop resolving this drawable before
        // the X server can hand its id to a new one. The renderer keeps its own buffer reference
        // until the GPU has finished the frames that sampled it.
        synchronized (lock) {
            if (nativeHandle != 0) nativeReleaseDrawable(nativeHandle, did(drawable));
        }
    }

    @Override
    public void nativeSetCompositeRedirected(int windowId, boolean redirected) {
        if (xServer.windowManager.getWindow(windowId) == null) return;
        if (redirected) compositeRedirectedWindows.add(windowId);
        else compositeRedirectedWindows.remove(windowId);
    }

    private static boolean hasAncestor(Window window, Window ancestor) {
        Window current = window;
        while (current != null) {
            if (current == ancestor) return true;
            current = current.getParent();
        }
        return false;
    }

    private static Window getWindowSibling(Window window, Window other) {
        Window current = window;
        while (current != null) {
            if (current.getParent() == other.getParent()) return current;
            current = current.getParent();
        }
        return null;
    }

    @Override
    public boolean nativeCompositeRedirect(int srcDrawableId, int dstDrawableId, short dstX, short dstY) {
        Window srcWindow = xServer.windowManager.getWindow(srcDrawableId);
        Window dstWindow = xServer.windowManager.getWindow(dstDrawableId);
        // Pixmap destination or copy within the same window: needs a real pixel copy.
        if (srcWindow == null || dstWindow == null || srcWindow == dstWindow) return false;
        if (!hasAncestor(srcWindow, dstWindow)) {
            Window sibling = getWindowSibling(srcWindow, dstWindow);
            if (sibling == null) return false;

            Window parent = sibling.getParent();
            if (parent == null || parent != dstWindow.getParent()) return false;

            int posX = dstWindow.getX() + dstX;
            int posY = dstWindow.getY() + dstY;
            boolean positionChanged = sibling.getX() != posX || sibling.getY() != posY;

            if (positionChanged) {
                sibling.setX((short)posX);
                sibling.setY((short)posY);
                onUpdateWindowGeometry(sibling, false);
            }

            List<Window> children = parent.getChildren();
            int siblingIndex = children.indexOf(sibling);
            int dstIndex = children.indexOf(dstWindow);
            if (siblingIndex < 0 || dstIndex < 0) return false;

            if (siblingIndex <= dstIndex) {
                parent.moveChildAbove(sibling, dstWindow);
                onChangeWindowZOrder(Window.StackMode.ABOVE, sibling, dstWindow);
            }
        }

        return true;
    }

    @Override
    public void onUpdateWindowContent(Window window) {
        synchronized (lock) {
            if (nativeHandle == 0) return;
            Drawable drawable = window.getContent();
            if (drawable == null || !window.attributes.isMapped()) return;

            final int rx = window.getRootX(), ry = window.getRootY();
            synchronized (drawable.renderLock) {
                final long ahb = drawable.backingAHB;
                if (ahb != 0) {
                    nativeUpdateWindowContentAHB(nativeHandle, did(drawable), did(drawable),
                        ahb, drawable.width, drawable.height, rx, ry);
                }
            }
        }
    }

    @Override
    public void onPointerMove(short x, short y) {
        synchronized (lock) {
            if (nativeHandle == 0) return;
            nativeSetPointerPos(nativeHandle, x, y);
            Window pw = xServer.inputDeviceManager.getPointWindow();
            Cursor cursor = pw != null ? pw.attributes.getCursor() : null;
            if (cursor != lastCursor) { lastCursor = cursor; sendCursorToNative(cursor); }
            if (screenOffsetYRelativeToCursor || magnifierZoom != 1.0f) updateTransform();
        }
    }

    @Override
    public void setCursorVisible(boolean visible) {
        cursorVisible = visible;
        synchronized (lock) {
            if (nativeHandle != 0) {
                // When shown, go through sendCursorToNative so a game-hidden X cursor is not
                // flashed visible for a frame before being hidden again.
                if (visible) sendCursorToNative(lastCursor);
                else nativeSetCursorVisible(nativeHandle, false);
            }
        }
    }

    public boolean isCursorVisible() { return cursorVisible; }

    public void setDriverInfo(String driverPath, String libraryName, String nativeLibDir) {
        this.driverPath = driverPath;
        this.driverLibraryName = libraryName;
        this.nativeLibDir = nativeLibDir;
    }

    public void setValidationEnabled(boolean enabled, String nativeLibDir) {
        this.validationEnabled = enabled;
        this.nativeLibDir = nativeLibDir;
    }

    public void setVerboseLog(boolean v) {
        synchronized (lock) { if (nativeHandle != 0) nativeSetVerboseLog(nativeHandle, v); }
    }

    public void dumpRendererInfo() {
        synchronized (lock) { if (nativeHandle != 0) nativeDumpRendererInfo(nativeHandle); }
    }

    public void setStretchMode(int mode) {
        pendingStretchMode = mode;
        synchronized (lock) { if (nativeHandle != 0) nativeSetStretchMode(nativeHandle, mode); }
    }

    public void setPostFXMode(int mode) {
        pendingPostFXMode = mode;
        synchronized (lock) { if (nativeHandle != 0) nativeSetPostFXMode(nativeHandle, mode); }
    }

    public void setSharpness(float s) {
        pendingSharpness = s;
        synchronized (lock) { if (nativeHandle != 0) nativeSetSharpness(nativeHandle, s); }
    }

    public void setFilterMode(int mode) {
        pendingFilterMode = mode;
        synchronized (lock) { if (nativeHandle != 0) nativeSetFilterMode(nativeHandle, mode); }
    }

    public void setVkPresentMode(int mode) {
        pendingPresentMode = mode;
        synchronized (lock) { if (nativeHandle != 0) nativeSetPresentMode(nativeHandle, mode); }
    }

    public int[] getSupportedPresentModes() {
        synchronized (lock) {
            if (nativeHandle != 0) return nativeGetSupportedPresentModes(nativeHandle);
        }
        return new int[0];
    }

    @Override
    public boolean isFullscreen() { return fullscreen; }
    @Override
    public void toggleFullscreen() {
        fullscreen = !fullscreen;
        synchronized (lock) { updateTransform(); }
    }
    @Override
    public void setScreenOffsetYRelativeToCursor(boolean b) {
        screenOffsetYRelativeToCursor = b;
        synchronized (lock) { updateTransform(); }
    }
    public boolean isScreenOffsetYRelativeToCursor() { return screenOffsetYRelativeToCursor; }
    @Override
    public void setMagnifierZoom(float zoom) {
        magnifierZoom = zoom;
        synchronized (lock) { updateTransform(); }
    }
    @Override
    public float getMagnifierZoom() { return magnifierZoom; }
    @Override
    public void setUnviewableWMClasses(String... classes) { this.unviewableWMClasses = classes; }

    public void setFpsWindowId(int id) { fpsWindowId = id; }
    public void setFrameRating(Object fr) {
        if (fr instanceof WinlatorHUD) hudRef = (WinlatorHUD) fr;
        else if (fr instanceof FrameRating) classicHudRef = (FrameRating) fr;
    }
    public int getFpsLimit() {
        return fpsLimit;
    }
    public void setFpsLimit(int limit) {
        this.fpsLimit = Math.max(0, Math.min(1000, limit));
        // Same cap for clients that draw with MIT-SHM blits and never reach the Present limiter.
        com.winlator.cmod.xserver.ShmFramePacer.setFrameRateLimit(this.fpsLimit);
        synchronized (lock) { if (nativeHandle != 0) nativeSetFpsLimit(nativeHandle, fpsLimit); }
    }
    @Override
    public boolean isPerformanceHintSupported() {
        try {
            loadNativeLibrary();
            return nativePerformanceHintLevel() > 0;
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    @Override
    public void setPerformanceHint(boolean enabled) {
        perfHint = enabled;
        synchronized (lock) {
            if (nativeHandle != 0) nativeSetPerformanceHint(nativeHandle, enabled);
        }
        // Subscribe only while on: with the hint off nothing here is touched on a thread start.
        if (enabled) {
            if (perfHintListener == null) {
                // Hands the work to the event thread: the caller is a connector or limiter
                // thread and must not wait on this view's lock.
                perfHintListener = tids -> queueEvent(() -> {
                    synchronized (lock) {
                        if (nativeHandle != 0 && perfHint) nativeSetPerformanceHintThreads(nativeHandle, tids);
                    }
                });
            }
            com.winlator.cmod.core.PerfHintThreads.setListener(perfHintListener);
        } else if (perfHintListener != null) {
            com.winlator.cmod.core.PerfHintThreads.clearListener(perfHintListener);
        }
    }

    public void setPipMode(boolean pip) { inPipMode = pip; }
    public int getSurfaceWidth() { return surfaceWidth; }
    public int getSurfaceHeight() { return surfaceHeight; }
    public void requestRender() {}
}
