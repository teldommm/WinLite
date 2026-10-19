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
import com.winlator.cmod.core.LsfgNative;
import java.io.File;
import android.widget.Toast;
import com.winlator.cmod.ui.toast.WinToast;
import com.winlator.cmod.renderer.GPUImage;
import com.winlator.cmod.xserver.Bitmask;
import com.winlator.cmod.xserver.Cursor;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.Property;
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
    private boolean pendingSwapRB         = false;
    private int pendingLsfgMultiplier = 0;
    private float pendingLsfgFlowScale = 0.80f;
    private int pendingLsfgTargetRate = 0;
    private File pendingLsfgDll;
    private float pendingFrameGenRefreshHz;
    private volatile String frameGenError = "";

    private WinlatorHUD hudRef = null;
    private FrameRating classicHudRef = null;
    private volatile int fpsWindowId = -1;
    private volatile int fpsLimit = 0;
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

        rootCursorDrawable = createRootCursorDrawable();
        xServer.setXServerView(this);
        xServer.windowManager.addOnWindowModificationListener(this);
        xServer.pointer.addOnPointerMotionListener(this);
    }

    private Drawable createRootCursorDrawable() {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            Bitmap bitmap = BitmapFactory.decodeResource(getContext().getResources(), R.drawable.cursor, options);
            return Drawable.fromBitmap(bitmap);
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
        ByteBuffer pixels, short width, short height, short stride, short hotX, short hotY);
    @FastNative private native void nativeSetVerboseLog(long handle, boolean v);
    @FastNative private native void nativeSetSharpness(long handle, float sharpness);

    private native String nativeConfigureFrameGen(long handle, String cachePath,
                                                   int multiplier, float flowScale, float refreshHz,
                                                   int targetRate);
    private native float nativeGetFrameGenPresentedRate(long handle);
    private native boolean nativeFrameGenSupportsFp16(long handle);

    private native void nativeDumpRendererInfo(long handle);
    private native void nativeSetFilterMode(long handle, int mode);
    private native void nativeSetStretchMode(long handle, int mode);
    private native void nativeSetPostFXMode(long handle, int mode);
    private native void nativeSetSwapRB(long handle, boolean enabled);
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
                        nativeSetSwapRB(nativeHandle, pendingSwapRB);
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
                        // Frame gen setup may build/verify the shader cache: never under lock.
                        queueEvent(this::applyFrameGenNative);
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
                    nativeSetSwapRB(nativeHandle, pendingSwapRB);
                    nativeSetStretchMode(nativeHandle, pendingStretchMode);
                    nativeSetPostFXMode(nativeHandle, pendingPostFXMode);
                    nativeSetSharpness(nativeHandle, pendingSharpness);
                    updateTransform();
                    pushDisplayRefreshRateLocked();
                    nativeSetFpsLimit(nativeHandle, fpsLimit);
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
                    nativeSetVerboseLog(nativeHandle, true);
                    nativeDumpRendererInfo(nativeHandle);
                    queueEvent(this::applyFrameGenNative);
                }
            }
            initComplete = true;
        });
    }

    public void onSurfaceChanged(int width, int height) {
        if (inPipMode) return;
        surfaceWidth = width; surfaceHeight = height;
        viewTransformation.update(width, height, xServer.screenInfo.width, xServer.screenInfo.height);
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
        initComplete = false;
        if (initExecutor != null) {
            initExecutor.shutdownNow();
            try { initExecutor.awaitTermination(3, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            initExecutor = null;
        }
        compositeRedirectedWindows.clear();
        vsyncClock.stop();
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

        final float ox, oy, sx, sy;
        if (fullscreen) {
            viewTransformation.update(surfaceWidth, surfaceHeight,
                xServer.screenInfo.width, xServer.screenInfo.height);
            if (zoom != 1.0f) {
                ox = viewTransformation.sceneOffsetX + ptrX * viewTransformation.sceneScaleX * (1f - zoom);
                oy = viewTransformation.sceneOffsetY + ptrY * viewTransformation.sceneScaleY * (1f - zoom);
                sx = viewTransformation.sceneScaleX * zoom;
                sy = viewTransformation.sceneScaleY * zoom;
            } else {
                ox = 0f; oy = 0f; sx = 1f; sy = 1f;
            }
        } else {
            float py = 0;
            if (screenOffsetYRelativeToCursor) {
                short halfH = (short)(xServer.screenInfo.height / 2);
                py = Math.max(0, Math.min(ptrY - halfH / 2.0f, halfH));
            }
            ox = viewTransformation.sceneOffsetX + ptrX * viewTransformation.sceneScaleX * (1f - zoom);
            oy = viewTransformation.sceneOffsetY - py + ptrY * viewTransformation.sceneScaleY * (1f - zoom);
            sx = viewTransformation.sceneScaleX * zoom;
            sy = viewTransformation.sceneScaleY * zoom;
        }

        final boolean needScissor = !(fullscreen && zoom == 1.0f);
        nativeSetTransformAndScissor(nativeHandle, ox, oy, sx, sy,
            needScissor,
            viewTransformation.viewOffsetX, viewTransformation.viewOffsetY,
            viewTransformation.viewWidth,   viewTransformation.viewHeight);
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
                        nativeUpdateCursorImage(nativeHandle, pixels, cd.width, cd.height,
                            cd.getStride(), hotX, hotY);
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
            float frameGenRate = 0f;
            if (pendingLsfgMultiplier >= 2 && frameGenError.isEmpty()) {
                synchronized (lock) {
                    if (nativeHandle != 0) frameGenRate = nativeGetFrameGenPresentedRate(nativeHandle);
                }
            }
            if (hudRef != null) {
                hudRef.setFrameGenPresentedRate(frameGenRate);
                hudRef.onFrame();
            }
            if (classicHudRef != null) {
                classicHudRef.setFrameGenPresentedRate(frameGenRate);
                classicHudRef.update();
            }
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

    public void setSwapRB(boolean enabled) {
        pendingSwapRB = enabled;
        synchronized (lock) { if (nativeHandle != 0) nativeSetSwapRB(nativeHandle, enabled); }
    }

    /**
     * @param targetRate adaptive target in Hz; 0 keeps the fixed multiplier. In
     *                   adaptive mode the multiplier only sets the ceiling (pass 4).
     */
    public void setFrameGenNative(File dll, int multiplier, float flowScale, int targetRate) {
        synchronized (lock) {
            pendingLsfgDll = dll;
            pendingLsfgMultiplier = multiplier < 2 ? 0 : Math.min(multiplier, 4);
            pendingLsfgFlowScale = flowScale;
            pendingLsfgTargetRate = pendingLsfgMultiplier < 2 ? 0 : Math.max(0, targetRate);
        }
        queueEvent(this::applyFrameGenNative);
    }

    public void setFrameGenRefreshRate(float refreshHz) {
        pendingFrameGenRefreshHz = refreshHz;
        synchronized (lock) { pushDisplayRefreshRateLocked(); }
        queueEvent(this::applyFrameGenNative);
    }

    // Last successful cache check, so a refresh-rate change or surface re-attach does not hash
    // the DLL again. Event-executor thread only.
    private String verifiedCacheKey = null;
    private String verifiedCachePath = null;

    /**
     * Runs on eventExecutor (serial: a later request always applies after an earlier one), and
     * NOT under {@code lock}: building or verifying the shader cache can take from a DLL hash up
     * to a full translation, and every X request that shows content, plus pointer motion from the
     * UI thread, needs {@code lock}. Only the snapshot and the native configure call hold it.
     */
    private void applyFrameGenNative() {
        final long handle;
        final int requestedMultiplier;
        final File dll;
        final float flowScale;
        final int targetRate;
        final boolean preferFp16;
        synchronized (lock) {
            handle = nativeHandle;
            if (handle == 0) return;
            requestedMultiplier = pendingLsfgMultiplier;
            dll = pendingLsfgDll;
            flowScale = pendingLsfgFlowScale;
            targetRate = pendingLsfgTargetRate;
            // Prefer the fp16 shader set when the device enabled shaderFloat16.
            // Only DLLs with precompiled SPIR-V carry it; others fall back to fp32 or
            // the DXBC-translated set on their own (see lsfg_dll selectVariant).
            preferFp16 = requestedMultiplier >= 2 && nativeFrameGenSupportsFp16(handle);
        }

        int multiplier = requestedMultiplier;
        String error = null;
        String cachePath = null;
        if (multiplier >= 2) {
            String key = dll == null ? null : dll.getAbsolutePath() + '|' + dll.length() + '|'
                    + dll.lastModified() + '|' + preferFp16;
            if (key != null && key.equals(verifiedCacheKey)) {
                cachePath = verifiedCachePath;
            } else {
                int status = LsfgNative.ensureCache(getContext(), dll, preferFp16);
                if (status == LsfgNative.STATUS_OK) {
                    cachePath = LsfgNative.cacheFile(getContext(), preferFp16).getAbsolutePath();
                    verifiedCacheKey = key;
                    verifiedCachePath = cachePath;
                } else {
                    error = LsfgNative.explain(status);
                    multiplier = 0;
                }
            }
        }
        float refreshHz = pendingFrameGenRefreshHz > 0f ? pendingFrameGenRefreshHz
                : getDisplay() != null ? getDisplay().getRefreshRate() : 0f;

        String nativeError;
        synchronized (lock) {
            // The renderer may have been destroyed or re-created while the cache was checked.
            if (nativeHandle != handle) return;
            nativeError = nativeConfigureFrameGen(handle, cachePath,
                    multiplier, flowScale, refreshHz, targetRate);
        }
        if (error == null) error = nativeError;
        if (error != null) {
            boolean oldDriver = error.contains("Vulkan version below") || error.contains("storage images")
                    || error.contains("shader") || error.contains("feature") || error.contains("vulkanMemoryModel");
            // Vulkan 1.2 / vulkanMemoryModel are only needed by the DXBC-translated
            // shaders (DXBC-only DLLs); DLLs with precompiled SPIR-V do not need them.
            boolean translationOnly = error.contains("Vulkan version below") || error.contains("vulkanMemoryModel");
            frameGenError = "LSFG Native can't run: "
                    + (oldDriver ? (error.contains("Vulkan version below")
                            ? "this Renderer Driver lacks Vulkan 1.2. " : "this Renderer Driver lacks a required Vulkan feature. ")
                            + (translationOnly
                                    ? "Import a Lossless.dll with precompiled SPIR-V shaders, or set Renderer Driver to a Turnip driver, then relaunch the game."
                                    : "Set Renderer Driver to a Turnip driver, then relaunch the game.")
                            : error + (error.endsWith(".") ? "" : "."));
            final String message = frameGenError;
            post(() -> WinToast.show(getContext(), message, Toast.LENGTH_LONG));
        } else {
            frameGenError = "";
        }
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
        synchronized (lock) { if (nativeHandle != 0) nativeSetFpsLimit(nativeHandle, fpsLimit); }
    }
    public void setPipMode(boolean pip) { inPipMode = pip; }
    public int getSurfaceWidth() { return surfaceWidth; }
    public int getSurfaceHeight() { return surfaceHeight; }
    public void requestRender() {}
}
