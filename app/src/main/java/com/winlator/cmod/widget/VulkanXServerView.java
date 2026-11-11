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
import com.winlator.cmod.xserver.ScreenInfo;
import com.winlator.cmod.xserver.XCore;
import com.winlator.cmod.xserver.XServer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import dalvik.annotation.optimization.FastNative;

/**
 * Draws the X server's screen with the native Vulkan renderer. It follows the server through
 * {@link XServer.Listener} (windows, their stacking, their pixels, the cursor) and mirrors what it
 * learns into the renderer's own window tree; the pixels themselves are the server's
 * AHardwareBuffers, imported by the renderer without a copy.
 *
 * <p>The listener methods run on the server's event thread, one after the other. Everything that
 * touches the native renderer holds {@link #lock}.
 */
@SuppressLint("ViewConstructor")
public class VulkanXServerView extends XServerRendererView implements SurfaceHolder.Callback,
        XServer.Listener, OutputFrameSource {

    /** WM_CLASS, a predefined atom of the X protocol. */
    private static final int ATOM_WM_CLASS = 67;

    private long nativeHandle = 0;
    private final Object lock = new Object();

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

    // The server's window ids (also the renderer's keys, and those of the windows' contents).
    private volatile int rootWindowId = 0;
    // Windows that are mapped, as far as the events told (event thread only).
    private final HashSet<Integer> mappedWindows = new HashSet<>();
    // The buffer last handed to the renderer for each drawable (a window, or a DRI3 pixmap shown in
    // one); only to give it back when the server drops the drawable. Guarded by lock.
    private final HashMap<Integer, Long> importedAhb = new HashMap<>();

    // The cursor image the renderer has (the server's serial of it; 0 = our default arrow).
    private long sentCursorSerial = -1;
    private ByteBuffer rootCursor;
    private int rootCursorWidth, rootCursorHeight;
    private long lastClockSeq = -1;
    private final long[] clockOut = new long[3];

    private int     pendingPresentMode    = 2;
    private int     pendingStretchMode    = 0;
    private int     pendingFilterMode     = 0;
    private int     pendingPostFXMode     = 0;
    private float   pendingSharpness      = 0.5f;
    private boolean pendingWfgEnabled     = false;
    private int     pendingWfgFlowSide    = 180;
    private boolean pendingWfgFastInterp  = true;
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

        loadRootCursor();
        xServer.setXServerView(this);
        effectiveScreen = xServer.getEffectiveScreenInfo();
        xServer.addListener(this);
    }

    /** The arrow shown where no window has an X cursor of its own. */
    private void loadRootCursor() {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            Bitmap bitmap = BitmapFactory.decodeResource(getContext().getResources(), R.drawable.cursor, options);
            if (bitmap == null) return;
            // An ARGB_8888 bitmap is R,G,B,A in memory: what the renderer's cursor texture wants.
            ByteBuffer buf = ByteBuffer.allocateDirect(bitmap.getWidth() * bitmap.getHeight() * 4).order(ByteOrder.nativeOrder());
            bitmap.copyPixelsToBuffer(buf);
            buf.rewind();
            rootCursor = buf;
            rootCursorWidth = bitmap.getWidth();
            rootCursorHeight = bitmap.getHeight();
        } catch (Exception e) {
            rootCursor = null;
        }
    }

    private native long nativeInit(Surface surface,
                                   int screenWidth, int screenHeight,
                                   String driverPath, String libraryName, String nativeLibDir,
                                   boolean validationEnabled, String cacheDir);
    private native void nativeResize(long handle, int width, int height);
    private native void nativeDestroy(long handle);
    private native void nativeDetachSurface(long handle);
    private native boolean nativeReattachSurface(long handle, Surface surface);

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
    private native void nativeSetWfg(long handle, boolean enabled, int flowSide, boolean fastInterp,
                                     int targetFps, int multiplier, float refreshHz);
    // The game's FPS limit (0 = none): WFG paces its presents to it while the game holds it.
    @FastNative private native void nativeSetWfgFpsLimit(long handle, int fps);
    @FastNative private native boolean nativeGetDisplayClock(long handle, long[] out);
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

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        onSurfaceCreated(holder.getSurface());
        if (pendingWfgEnabled || fpsLimit > 0) requestDisplayMode();
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
                        // game made invisible (empty mask), and the cursor is unchanged after the
                        // surface comes back, so nothing would hide it again.
                        applyCursorLocked(false);
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

                    // A fresh renderer has no buffers imported: nothing to give back later.
                    importedAhb.clear();
                    if (isColdInit && rootWindowId != 0) {
                        int root = rootWindowId;
                        nativeInitRootWindow(nativeHandle, root, root,
                            xServer.screenInfo.width, xServer.screenInfo.height);
                    }
                    // A fresh native renderer has no cursor image either: re-send the current
                    // X cursor (image + its own visibility), not just the global flag.
                    applyCursorLocked(true);
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
        xServer.removeListener(this);
        synchronized (lock) {
            if (nativeHandle != 0) {
                nativeDestroy(nativeHandle);
                nativeHandle = 0;
            }
        }
    }

    public void queueEvent(Runnable r) { xServer.runOnEventThread(r); }
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

    // ------------------------------------------------------------------ window tree

    /**
     * Refreshes the effective screen size and re-applies the transform when it changed. Runs on
     * the event thread.
     */
    private void refreshDesktopTransform() {
        final ScreenInfo inner = xServer.getEffectiveScreenInfo();
        final ScreenInfo old = effectiveScreen;
        if (old != null && old.width == inner.width && old.height == inner.height) return;
        synchronized (lock) {
            effectiveScreen = inner;
            if (surfaceWidth > 0 && surfaceHeight > 0) {
                viewTransformation.update(surfaceWidth, surfaceHeight, inner.width, inner.height);
            }
            updateTransform();
        }
        xServer.notifyEffectiveScreenChanged();
    }

    /**
     * Sends the server's whole window tree (geometry, stacking, map state, viewability and the
     * X-drawn content) to the native renderer. Runs on the event thread, in order with the window
     * events: a change made after the snapshot below is queued behind this and applied on top.
     */
    private void replayWindowTree() {
        final XCore core = xServer.core();
        if (core == null) return;
        final long[] tree = core.snapshotTree();
        final int stride = XCore.WINDOW_STRIDE;
        if (tree.length < stride) return;
        rootWindowId = (int) tree[XCore.W_ID];
        mappedWindows.clear();
        // Children of each window, bottom-most first (the snapshot lists them in that order).
        final HashMap<Integer, ArrayList<Long>> children = new HashMap<>();
        synchronized (lock) {
            if (nativeHandle == 0) return;
            nativeInitRootWindow(nativeHandle, rootWindowId, rootWindowId,
                xServer.screenInfo.width, xServer.screenInfo.height);
            for (int i = stride; i + stride <= tree.length; i += stride) {
                final int id = (int) tree[i + XCore.W_ID];
                final int parent = (int) tree[i + XCore.W_PARENT];
                final long flags = tree[i + XCore.W_FLAGS];
                final boolean hasContent = (flags & XCore.WF_HAS_CONTENT) != 0;
                nativeCreateWindow(nativeHandle, id, parent, hasContent ? id : 0,
                    (int) tree[i + XCore.W_X], (int) tree[i + XCore.W_Y],
                    (int) tree[i + XCore.W_WIDTH], (int) tree[i + XCore.W_HEIGHT]);
                nativeSetWindowViewable(nativeHandle, id, !isUnviewable(id));
                children.computeIfAbsent(parent, k -> new ArrayList<>()).add((long) id);
                if ((flags & XCore.WF_MAPPED) != 0) {
                    mappedWindows.add(id);
                    nativeMapWindow(nativeHandle, id);
                    if (hasContent) updateContentLocked(core, id, id);
                }
            }
            // Exact stacking order (also drops any duplicate child entries the creates may leave).
            for (java.util.Map.Entry<Integer, ArrayList<Long>> e : children.entrySet()) {
                ArrayList<Long> list = e.getValue();
                long[] ordered = new long[list.size()];
                for (int k = 0; k < ordered.length; k++) ordered[k] = list.get(k);
                nativeSyncChildOrder(nativeHandle, e.getKey(), ordered, ordered.length);
            }
        }
        refreshDesktopTransform();
    }

    /** The window's WM_CLASS names one of the classes that must not be drawn. */
    private boolean isUnviewable(int window) {
        if (unviewableWMClasses == null || window == rootWindowId) return false;
        String wc = xServer.getWindowClassName(window);
        for (String cls : unviewableWMClasses) if (wc.contains(cls)) return true;
        return false;
    }

    /** Hides what {@link #setUnviewableWMClasses} names, and stops sending it input. */
    private void checkViewable(int window) {
        if (unviewableWMClasses == null || window == rootWindowId) return;
        final boolean unviewable = isUnviewable(window);
        if (unviewable) {
            XCore core = xServer.core();
            if (core != null) core.disableSubtree(window);
        }
        synchronized (lock) {
            if (nativeHandle != 0) nativeSetWindowViewable(nativeHandle, window, !unviewable);
        }
    }

    @Override
    public void onServerStarted() {
        // Everything the renderer knows came from an earlier server (or nothing): start over.
        replayWindowTree();
        synchronized (lock) { applyCursorLocked(true); }
    }

    @Override
    public void onServerStopped() {
        mappedWindows.clear();
        synchronized (lock) { importedAhb.clear(); }
    }

    @Override
    public void onCreateWindow(int window, int parent) {
        final XCore core = xServer.core();
        if (core == null) return;
        final long[] info = core.windowInfo(window);
        if (info.length < XCore.WINDOW_STRIDE) return; // already gone: its destroy event follows
        final boolean hasContent = (info[XCore.W_FLAGS] & XCore.WF_HAS_CONTENT) != 0;
        synchronized (lock) {
            if (nativeHandle != 0) {
                nativeCreateWindow(nativeHandle, window, parent, hasContent ? window : 0,
                    (int) info[XCore.W_X], (int) info[XCore.W_Y],
                    (int) info[XCore.W_WIDTH], (int) info[XCore.W_HEIGHT]);
            }
        }
        checkViewable(window);
    }

    @Override
    public void onDestroyWindow(int window) {
        mappedWindows.remove(window);
        synchronized (lock) {
            // The renderer drops the window's buffers with it; nothing of ours to give back.
            importedAhb.remove(window);
            if (nativeHandle != 0) nativeDestroyWindow(nativeHandle, window);
        }
    }

    @Override
    public void onMapWindow(int window) {
        mappedWindows.add(window);
        synchronized (lock) {
            if (nativeHandle != 0) nativeMapWindow(nativeHandle, window);
        }
        refreshDesktopTransform();
        checkViewable(window);
    }

    @Override
    public void onUnmapWindow(int window) {
        mappedWindows.remove(window);
        synchronized (lock) {
            if (nativeHandle != 0) nativeUnmapWindow(nativeHandle, window);
        }
        refreshDesktopTransform();
    }

    @Override
    public void onReparentWindow(int window, int parent, int x, int y) {
        final XCore core = xServer.core();
        if (core == null) return;
        final long[] info = core.windowInfo(window);
        if (info.length < XCore.WINDOW_STRIDE) return;
        final boolean hasContent = (info[XCore.W_FLAGS] & XCore.WF_HAS_CONTENT) != 0;
        synchronized (lock) {
            if (nativeHandle != 0) {
                nativeReparentWindow(nativeHandle, window, parent);
                nativeUpdateWindowGeometry(nativeHandle, window, hasContent ? window : 0,
                    x, y, (int) info[XCore.W_WIDTH], (int) info[XCore.W_HEIGHT]);
            }
        }
    }

    @Override
    public void onChangeWindowZOrder(int stackMode, int window, int sibling) {
        final XCore core = xServer.core();
        if (core == null) return;
        final long[] info = core.windowInfo(window);
        if (info.length < XCore.WINDOW_STRIDE) return;
        final int parent = (int) info[XCore.W_PARENT];
        final long[] ordered = core.windowChildren(parent);
        synchronized (lock) {
            if (nativeHandle != 0) nativeSyncChildOrder(nativeHandle, parent, ordered, ordered.length);
        }
    }

    @Override
    public void onUpdateWindowGeometry(int window, boolean resized) {
        final XCore core = xServer.core();
        if (core == null) return;
        final long[] info = core.windowInfo(window);
        if (info.length < XCore.WINDOW_STRIDE) return;
        final boolean hasContent = (info[XCore.W_FLAGS] & XCore.WF_HAS_CONTENT) != 0;
        synchronized (lock) {
            if (nativeHandle != 0) {
                nativeUpdateWindowGeometry(nativeHandle, window, hasContent ? window : 0,
                    (int) info[XCore.W_X], (int) info[XCore.W_Y],
                    (int) info[XCore.W_WIDTH], (int) info[XCore.W_HEIGHT]);
                // A resized window has a new buffer under the same id; the old one was given back.
                if (resized && hasContent && mappedWindows.contains(window)) updateContentLocked(core, window, window);
            }
        }
        if (resized) refreshDesktopTransform();
    }

    @Override
    public void onModifyWindowProperty(int window, int atom) {
        // WM_CLASS is what marks Wine's desktop window and the windows that stay hidden; it may be
        // set after the window is mapped.
        if (atom == ATOM_WM_CLASS) {
            if (mappedWindows.contains(window)) refreshDesktopTransform();
            checkViewable(window);
        }
    }

    // ------------------------------------------------------------------ pixels

    /**
     * Points the renderer's content {@code window} at the buffer of {@code drawable} (the window
     * itself, or a DRI3 pixmap shown in it). The renderer takes a reference of its own when it
     * imports a buffer, so ours goes back at once. Caller holds {@link #lock}.
     */
    private void updateContentLocked(XCore core, int window, int drawable) {
        if (nativeHandle == 0) return;
        final long ahb = core.acquireBuffer(drawable);
        if (ahb == 0) return;
        try {
            importedAhb.put(drawable, ahb);
            nativeUpdateWindowContentAHB(nativeHandle, window, ahb, (short) 0, (short) 0, 0, 0);
        } finally {
            XCore.releaseBuffer(ahb);
        }
    }

    @Override
    public void onUpdateWindowContent(int window) {
        if (!mappedWindows.contains(window)) return;
        final XCore core = xServer.core();
        if (core == null) return;
        synchronized (lock) {
            updateContentLocked(core, window, window);
            pushDisplayClockLocked();
        }
    }

    @Override
    public void onUpdateWindowContentDirect(int window, int pixmap) {
        countFrameForHud(window);
        final XCore core = xServer.core();
        if (core == null) return;
        synchronized (lock) {
            updateContentLocked(core, window, pixmap);
            pushDisplayClockLocked();
        }
    }

    private void countFrameForHud(int window) {
        if (window == fpsWindowId) {
            if (hudRef != null) hudRef.onFrame();
            if (classicHudRef != null) classicHudRef.update();
        }
    }

    @Override
    public void onDrawableRemoved(int drawable) {
        // The server gives the buffer back: forget the renderer's import of it, now, under the
        // lock every content update takes, so no update can import it again after that.
        synchronized (lock) {
            Long ahb = importedAhb.remove(drawable);
            if (ahb != null && nativeHandle != 0) nativeReleaseBuffer(nativeHandle, ahb);
        }
    }

    /** The renderer's display clock goes to the server: Present paces the game's frames by it. */
    private void pushDisplayClockLocked() {
        if (nativeHandle == 0) return;
        if (nativeGetDisplayClock(nativeHandle, clockOut)) {
            if (clockOut[2] != lastClockSeq) {
                lastClockSeq = clockOut[2];
                XCore.setDisplayClock(clockOut[0], clockOut[1], clockOut[2]);
            }
        } else if (lastClockSeq != -1) {
            lastClockSeq = -1;
            XCore.setDisplayClock(0, 0, 0);
        }
    }

    // ------------------------------------------------------------------ pointer and cursor

    @Override
    public void onPointerMove(int x, int y) {
        onPointerMove((short) x, (short) y);
    }

    @Override
    public void onPointerMove(short x, short y) {
        synchronized (lock) {
            if (nativeHandle == 0) return;
            nativeSetPointerPos(nativeHandle, x, y);
            if (screenOffsetYRelativeToCursor || magnifierZoom != 1.0f) updateTransform();
        }
    }

    @Override
    public void onCursorChanged() {
        synchronized (lock) { applyCursorLocked(false); }
    }

    /**
     * Gives the renderer the X cursor under the pointer: its visibility (a game hides the cursor
     * with an empty mask) and, when it is a different one, its image. Caller holds {@link #lock}.
     *
     * @param force send the image even if the renderer is believed to have it (a new renderer)
     */
    private void applyCursorLocked(boolean force) {
        if (nativeHandle == 0) return;
        final long[] st = xServer.getCursorState();
        final boolean hasXCursor = st.length >= 6;
        final boolean visible = cursorVisible && (!hasXCursor || st[3] != 0);
        nativeSetCursorVisible(nativeHandle, visible);
        if (!visible) {
            if (force) sentCursorSerial = -1;
            return;
        }
        final long serial = hasXCursor ? st[0] : 0;
        if (!force && serial == sentCursorSerial) return;
        if (hasXCursor) {
            final int w = (int) st[4], h = (int) st[5];
            final byte[] rgba = xServer.getCursorImage();
            if (rgba == null || w <= 0 || h <= 0 || rgba.length < w * h * 4) return;
            // The renderer's cursor texture is R,G,B,A.
            ByteBuffer pixels = ByteBuffer.allocateDirect(w * h * 4);
            pixels.put(rgba, 0, w * h * 4).rewind();
            nativeUpdateCursorImage(nativeHandle, pixels, (short) w, (short) h, (short) w,
                (short) st[1], (short) st[2], true);
        } else if (rootCursor != null) {
            nativeUpdateCursorImage(nativeHandle, rootCursor, (short) rootCursorWidth,
                (short) rootCursorHeight, (short) rootCursorWidth, (short) 0, (short) 0, true);
        } else {
            return;
        }
        sentCursorSerial = serial;
    }

    @Override
    public void setCursorVisible(boolean visible) {
        cursorVisible = visible;
        synchronized (lock) {
            if (nativeHandle != 0) {
                nativeSetCursorVisible(nativeHandle, visible);
                if (visible) applyCursorLocked(false);
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
     * @param fastInterp the cheaper interpolation (bilinear warp, one source step) of the Fast
     *                  preset instead of the Catmull-Rom warp with two steps
     * @param targetFps output rate to reach; 0 follows the display refresh rate. Ignored while
     *                  multiplier is set.
     * @param multiplier 2..4 fixes the output at that many frames per game frame (x2 = one
     *                  generated frame); 0 chases targetFps instead
     */
    public void setWfg(boolean enabled, int flowSide, boolean fastInterp, int targetFps,
                       int multiplier) {
        pendingWfgEnabled = enabled;
        pendingWfgFlowSide = flowSide;
        pendingWfgFastInterp = fastInterp;
        pendingWfgTargetFps = targetFps;
        pendingWfgMultiplier = multiplier;
        requestDisplayMode();
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
     * The WFG output rate when it is known up front: the game's FPS limit times a fixed
     * multiplier, or the target of the target mode. 0 when it is not known (no limit with a
     * fixed multiplier, or a target of "the refresh rate").
     */
    private int wfgOutputFps() {
        if (pendingWfgMultiplier >= 2) {
            return fpsLimit > 0 ? fpsLimit * pendingWfgMultiplier : 0;
        }
        return Math.max(0, pendingWfgTargetFps);
    }

    /**
     * The panel mode for WFG at its current resolution. Each output frame should last a whole
     * number of refreshes, or the cadence alternates (60 fps on 165 Hz: 3-3-3-2 refreshes,
     * 18.2/12.1 ms). With the output rate known, the fastest mode it divides evenly (within 1%)
     * into two or more refreshes per frame is taken: 120 Hz for 60 fps on a 60/90/120/144/165 Hz
     * panel. One refresh per frame is not enough: a game frame that comes a little early or a
     * GPU batch that ends a little late has no spare refresh to land on, and generated frames
     * are dropped (30 fps x4 on 120 Hz showed ~105 fps). Otherwise, or when no mode qualifies,
     * the fastest mode: the finer the refresh, the smaller an uneven step, and the interpolation
     * phase follows where each frame really lands.
     */
    public static android.view.Display.Mode wfgMode(android.view.Display display, int outputFps) {
        return displayModeFor(display, outputFps, 2);
    }

    /**
     * The fastest mode at the current resolution in which a frame of outputFps lasts a whole
     * number (at least minRefreshes) of refreshes; the fastest mode when none does or the rate
     * is unknown. WFG needs two refreshes per frame (see wfgMode); a plain FPS limit is fine
     * with one (144 fps on 144 Hz), nothing is generated there that could miss a refresh.
     */
    public static android.view.Display.Mode displayModeFor(android.view.Display display,
                                                           int outputFps, int minRefreshes) {
        android.view.Display.Mode fastest = fastestMode(display);
        if (fastest == null || outputFps <= 0) return fastest;
        android.view.Display.Mode current = display.getMode();
        android.view.Display.Mode best = null;
        for (android.view.Display.Mode m : display.getSupportedModes()) {
            if (m.getPhysicalWidth() != current.getPhysicalWidth()
                    || m.getPhysicalHeight() != current.getPhysicalHeight()) continue;
            float ratio = m.getRefreshRate() / outputFps;
            long whole = Math.round(ratio);
            if (whole < minRefreshes || Math.abs(ratio - whole) > 0.01f * whole) continue;
            if (best == null || m.getRefreshRate() > best.getRefreshRate()) best = m;
        }
        return best != null ? best : fastest;
    }

    /**
     * Generated frames need display refresh slots above the game's rate: with WFG on, ask for the
     * mode wfgMode picks (the window otherwise stays at the system's default, often 60 Hz).
     * Called again whenever the output rate changes (FPS limit, multiplier, target).
     */
    private void requestDisplayMode() {
        final boolean on = pendingWfgEnabled || fpsLimit > 0;
        post(() -> {
            if (!(getContext() instanceof android.app.Activity)) return;
            android.view.Window window = ((android.app.Activity) getContext()).getWindow();
            android.view.Display display = getDisplay();
            if (window == null || display == null) return;
            android.view.WindowManager.LayoutParams lp = window.getAttributes();
            Surface surface = getHolder().getSurface();
            if (on) {
                // WFG: its output rate, two refreshes per frame at least. Plain FPS limit: the
                // limit itself, one refresh per frame is enough.
                final int outputFps = pendingWfgEnabled ? wfgOutputFps() : fpsLimit;
                android.view.Display.Mode best = pendingWfgEnabled
                        ? wfgMode(display, outputFps)
                        : displayModeFor(display, outputFps, 1);
                if (best == null) return;
                if (savedDisplayModeId < 0) savedDisplayModeId = lp.preferredDisplayModeId;
                if (lp.preferredDisplayModeId != best.getModeId()) {
                    lp.preferredDisplayModeId = best.getModeId();
                    window.setAttributes(lp);
                    android.util.Log.i("WinLite", (pendingWfgEnabled ? "WFG" : "FPS limit")
                            + " display mode " + best.getRefreshRate()
                            + " Hz for output " + outputFps + " fps");
                }
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
        nativeSetWfg(nativeHandle, pendingWfgEnabled, pendingWfgFlowSide, pendingWfgFastInterp,
                pendingWfgTargetFps, pendingWfgMultiplier, refreshHz);
        nativeSetWfgFpsLimit(nativeHandle, fpsLimit);
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

    @Override
    public boolean getDisplayClock(long[] out) {
        synchronized (lock) {
            return nativeHandle != 0 && nativeGetDisplayClock(nativeHandle, out);
        }
    }
    public void setFpsLimit(int limit) {
        int v = Math.max(0, Math.min(1000, limit));
        boolean changed = v != fpsLimit;
        this.fpsLimit = v;
        // Present releases the game's buffers at this rate.
        XCore.setFpsLimit(v);
        synchronized (lock) { if (nativeHandle != 0) nativeSetWfgFpsLimit(nativeHandle, fpsLimit); }
        // The panel mode follows the limit (and the WFG output rate, which depends on it).
        if (changed) requestDisplayMode();
    }
    public void setPipMode(boolean pip) { inPipMode = pip; }
    public void requestRender() {}
}
