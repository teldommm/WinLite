package com.winlator.cmod.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.winlator.cmod.R;
import java.io.File;
import com.winlator.cmod.renderer.GPUImage;
import com.winlator.cmod.xserver.Atom;
import com.winlator.cmod.xserver.Bitmask;
import com.winlator.cmod.xserver.Cursor;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Pixmap;
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
import java.util.concurrent.TimeUnit;

import dalvik.annotation.optimization.FastNative;

@SuppressLint("ViewConstructor")
public class VulkanXServerView extends XServerRendererView implements SurfaceHolder.Callback,
        WindowManager.OnWindowModificationListener, Pointer.OnPointerMotionListener,
        OutputFrameSource {

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
    private boolean pendingWfgEnabled     = false;
    private int     pendingWfgFlowSide    = 180;
    private int     pendingWfgTargetFps   = 0;
    private int     pendingWfgMultiplier  = 0;
    private int     savedDisplayModeId    = -1;   // the window's mode before WFG raised it
    private android.hardware.display.DisplayManager.DisplayListener displayListener;

    private WinlatorHUD hudRef = null;
    private FrameRating classicHudRef = null;
    private volatile int fpsWindowId = -1;
    private volatile int fpsLimit = 0;

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
                                   boolean validationEnabled, String cacheDir);
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
    private native void nativeUpdateWindowContentAHB(long handle, long id,
        long ahbPtr, short width, short height, int x, int y);
    private native void nativeSetTransformAndScissor(long handle,
        float ox, float oy, float sx, float sy,
        boolean hasScissor, int scX, int scY, int scW, int scH);
    @FastNative private native void nativeSetPointerPos(long handle, short x, short y);
    @FastNative private native void nativeSetCursorVisible(long handle, boolean visible);
    private native void nativeUpdateCursorImage(long handle,
        ByteBuffer pixels, short width, short height, short stride, short hotX, short hotY,
        boolean rgba);
    private native void nativeRemoveWindow(long handle, long id);
    private native void nativeReleaseBuffer(long handle, long ahbPtr);
    @FastNative private native void nativeSetVerboseLog(long handle, boolean v);
    @FastNative private native void nativeSetSharpness(long handle, float sharpness);
    private native void nativeSetWfg(long handle, boolean enabled, int flowSide, int targetFps,
                                     int multiplier, float refreshHz);
    private native void nativeGetFrameCounts(long handle, long[] out);

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
    // InputOnly window).
    private static long did(Drawable d) {
        return d != null ? d.uid : 0L;
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        onSurfaceCreated(holder.getSurface());
        if (pendingWfgEnabled) requestWfgRefreshRate(true);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        android.hardware.display.DisplayManager dm = (android.hardware.display.DisplayManager)
                getContext().getSystemService(Context.DISPLAY_SERVICE);
        if (dm != null && displayListener == null) {
            // WFG paces its generated frames to the panel's rate, which changes with the mode.
            displayListener = new android.hardware.display.DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int displayId) {}
                @Override public void onDisplayRemoved(int displayId) {}
                @Override public void onDisplayChanged(int displayId) {
                    synchronized (lock) { applyWfg(); }
                }
            };
            dm.registerDisplayListener(displayListener, getHandler());
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        android.hardware.display.DisplayManager dm = (android.hardware.display.DisplayManager)
                getContext().getSystemService(Context.DISPLAY_SERVICE);
        if (dm != null && displayListener != null) dm.unregisterDisplayListener(displayListener);
        displayListener = null;
        super.onDetachedFromWindow();
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        onSurfaceChanged(width, height);
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        onSurfaceDestroyed();
    }

    // Where the renderer keeps its Vulkan pipeline cache between runs (null: not persisted).
    private String pipelineCacheDir() {
        try {
            java.io.File dir = getContext().getCacheDir();
            return dir != null ? dir.getAbsolutePath() : null;
        } catch (Throwable t) {
            return null;
        }
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
        final String cacheDir = pipelineCacheDir();

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
                        applyWfg();
                        updateTransform();
                        // Not nativeSetCursorVisible(cursorVisible): that ignores an X cursor the
                        // game made invisible (empty mask), and lastCursor is unchanged after the
                        // surface comes back, so nothing would hide it again.
                        restoreCursorStateLocked();
                        initComplete = true;
                        return;
                    }
                }

                nativeHandle = nativeInit(
                    surface,
                    xServer.screenInfo.width, xServer.screenInfo.height,
                    driverPath, driverLibraryName, nativeLibDir, validationEnabled, cacheDir);

                if (nativeHandle != 0) {
                    nativeSetPresentMode(nativeHandle, pendingPresentMode);
                    nativeSetFilterMode(nativeHandle, pendingFilterMode);
                    nativeSetStretchMode(nativeHandle, pendingStretchMode);
                    nativeSetPostFXMode(nativeHandle, pendingPostFXMode);
                    nativeSetSharpness(nativeHandle, pendingSharpness);
                    applyWfg();
                    updateTransform();
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
            if (nativeHandle != 0) { nativeResize(nativeHandle, width, height); updateTransform(); }
        }
    }

    public void onSurfaceDestroyed() {
        initComplete = false;
        if (initExecutor != null) {
            initExecutor.shutdownNow();
            try { initExecutor.awaitTermination(3, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            initExecutor = null;
        }
        synchronized (lock) {
            if (nativeHandle != 0) nativeDetachSurface(nativeHandle);
        }
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
        synchronized (lock) {
            if (nativeHandle != 0) {
                nativeDestroy(nativeHandle);
                nativeHandle = 0;
            }
        }
    }

    public void queueEvent(Runnable r) { eventExecutor.execute(r); }
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
        if (effVis && cd != null && cd.backingAHB != 0) {
            synchronized (cd.renderLock) {
                ByteBuffer pixels = cd.lockBuffer(cd.backingAHB);
                if (pixels != null) {
                    try {
                        // The renderer's cursor texture is RGBA; the native side swaps R and B
                        // when the cursor drawable is BGRA.
                        nativeUpdateCursorImage(nativeHandle, pixels, cd.width, cd.height,
                            cd.getStride(), hotX, hotY, cd.format == SurfaceFormat.RGBA);
                    } finally {
                        cd.unlockBuffer(cd.backingAHB);
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
                        if (content.backingAHB != 0) {
                            nativeUpdateWindowContentAHB(nativeHandle, did(content),
                                content.backingAHB, content.width, content.height,
                                window.getRootX(), window.getRootY());
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
            final int rx = window.getRootX() + xOff, ry = window.getRootY() + yOff;
            synchronized (pixmap.renderLock) {
                if (pixmap.backingAHB != 0) {
                    nativeUpdateWindowContentAHB(nativeHandle, did(window.getContent()),
                        pixmap.backingAHB, pixmap.width, pixmap.height, rx, ry);
                }
            }
        }
    }

    private void countFrameForHud(Window window) {
        if (window.id == fpsWindowId) {
            if (hudRef != null) hudRef.onFrame();
            if (classicHudRef != null) classicHudRef.update();
        }
    }

    public void addDirectContent(int windowId, Drawable drawable, GPUImage gpuImage) {

    }

    @Override
    public boolean ownsDrawableBuffers() { return true; }

    @Override
    public void onBufferReleased(long ahbPtr) {
        if (ahbPtr == 0) return;
        // Synchronous, under the lock every content update takes: no update can import this
        // buffer again after the renderer has dropped it. Lock order as elsewhere: X locks first.
        synchronized (lock) {
            if (nativeHandle != 0) nativeReleaseBuffer(nativeHandle, ahbPtr);
        }
    }

    @Override
    public void nativeRemoveDirectContent(int windowId, int pixmapId) {
        Pixmap pixmap = xServer.pixmapManager.getPixmap(pixmapId);
        if (pixmap == null || pixmap.drawable == null) return;
        final long contentId = did(pixmap.drawable);
        queueEvent(() -> {
            synchronized (lock) {
                if (nativeHandle != 0) nativeRemoveWindow(nativeHandle, contentId);
            }
        });
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
                if (drawable.backingAHB != 0) {
                    nativeUpdateWindowContentAHB(nativeHandle, did(drawable),
                        drawable.backingAHB, drawable.width, drawable.height, rx, ry);
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
                nativeSetCursorVisible(nativeHandle, visible);
                if (visible) sendCursorToNative(lastCursor);
            }
        }
    }


    public void setDriverInfo(String driverPath, String libraryName, String nativeLibDir) {
        this.driverPath = driverPath;
        this.driverLibraryName = libraryName;
        this.nativeLibDir = nativeLibDir;
    }

    /** Opt-in Vulkan validation layer (needs libVkLayer_khronos_validation.so in the app's native libs). */
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

    /**
     * WFG (WinNative/Winlator Frame Generator) frame interpolation.
     * @param flowSide  pixels on the shorter side of the frame the motion is analysed at
     * @param targetFps output rate to reach; 0 follows the display refresh rate. Ignored while
     *                  multiplier is set.
     * @param multiplier 2..4 fixes the output at that many frames per game frame (x2 = one
     *                  generated frame); 0 chases targetFps instead
     */
    public void setWfg(boolean enabled, int flowSide, int targetFps, int multiplier) {
        pendingWfgEnabled = enabled;
        pendingWfgFlowSide = flowSide;
        pendingWfgTargetFps = targetFps;
        pendingWfgMultiplier = multiplier;
        requestWfgRefreshRate(enabled);
        synchronized (lock) { applyWfg(); }
    }

    /**
     * The panel's fastest mode at its current resolution: the one WFG asks for, and so the most
     * frames per second it can ever show. Null when the display is unknown.
     */
    public static android.view.Display.Mode fastestMode(android.view.Display display) {
        if (display == null) return null;
        android.view.Display.Mode current = display.getMode();
        android.view.Display.Mode best = current;
        for (android.view.Display.Mode m : display.getSupportedModes()) {
            if (m.getPhysicalWidth() == current.getPhysicalWidth()
                    && m.getPhysicalHeight() == current.getPhysicalHeight()
                    && m.getRefreshRate() > best.getRefreshRate()) best = m;
        }
        return best;
    }

    /**
     * Generated frames need display refresh slots above the game's rate: with WFG on, ask for the
     * panel's fastest mode (the window otherwise stays at the system's default, often 60 Hz).
     */
    private void requestWfgRefreshRate(boolean on) {
        post(() -> {
            if (!(getContext() instanceof android.app.Activity)) return;
            android.view.Window window = ((android.app.Activity) getContext()).getWindow();
            android.view.Display display = getDisplay();
            if (window == null || display == null) return;
            android.view.WindowManager.LayoutParams lp = window.getAttributes();
            Surface surface = getHolder().getSurface();
            if (on) {
                android.view.Display.Mode best = fastestMode(display);
                if (savedDisplayModeId < 0) savedDisplayModeId = lp.preferredDisplayModeId;
                lp.preferredDisplayModeId = best.getModeId();
                window.setAttributes(lp);
                setSurfaceFrameRate(surface, best.getRefreshRate());
            } else if (savedDisplayModeId >= 0) {
                lp.preferredDisplayModeId = savedDisplayModeId;
                savedDisplayModeId = -1;
                window.setAttributes(lp);
                setSurfaceFrameRate(surface, 0f);
            }
        });
    }

    private static void setSurfaceFrameRate(Surface surface, float hz) {
        if (surface == null || !surface.isValid()) return;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                surface.setFrameRate(hz, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                        Surface.CHANGE_FRAME_RATE_ALWAYS);
            } else if (android.os.Build.VERSION.SDK_INT >= 30) {
                surface.setFrameRate(hz, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
            }
        } catch (RuntimeException ignored) {
            // A surface released between the check and the call.
        }
    }


    private final long[] frameCounts = new long[2];

    private long frameCount(int which) {
        synchronized (lock) {
            if (nativeHandle == 0) return 0;
            nativeGetFrameCounts(nativeHandle, frameCounts);
            return frameCounts[which];
        }
    }

    @Override
    public long getPresentedFrameCount() { return frameCount(0); }

    @Override
    public long getGeneratedFrameCount() { return frameCount(1); }

    private void applyWfg() {
        if (nativeHandle == 0) return;
        float refreshHz = 60f;
        android.view.Display display = getDisplay();
        if (display != null && display.getRefreshRate() > 1f) refreshHz = display.getRefreshRate();
        nativeSetWfg(nativeHandle, pendingWfgEnabled, pendingWfgFlowSide, pendingWfgTargetFps,
                pendingWfgMultiplier, refreshHz);
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
        if (fr instanceof WinlatorHUD) {
            hudRef = (WinlatorHUD) fr;
            hudRef.setOutputFrameSource(this);
        } else if (fr instanceof FrameRating) {
            classicHudRef = (FrameRating) fr;
            classicHudRef.setOutputFrameSource(this);
        }
    }
    public int getFpsLimit() {
        return fpsLimit;
    }
    public void setFpsLimit(int limit) { this.fpsLimit = Math.max(0, Math.min(1000, limit)); }
    public void setPipMode(boolean pip) { inPipMode = pip; }
    public void requestRender() {}
}
