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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import dalvik.annotation.optimization.FastNative;

/**
 * Draws the X server's screen with the native Vulkan renderer. The renderer is registered with
 * the server ({@link XCore#setRenderer}), which hands it everything straight from native code:
 * every frame (the server's AHardwareBuffers, imported without a copy), the window tree, the
 * pointer position and the cursor, and passes its display clock on to Present itself. What is
 * left here is the surface, the renderer's settings and the view transform: the part of the
 * screen shown (a Wine desktop smaller than the screen), fullscreen, the magnifier and the
 * screen offset that follow the cursor, for which it still listens to the server
 * ({@link XServer.Listener}).
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
    private String driverPath = null;
    private ExecutorService initExecutor = null;
    private volatile boolean initComplete = false;
    private volatile boolean inPipMode = false;
    private String driverLibraryName = null;
    private String nativeLibDir = null;
    private boolean validationEnabled = false;

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

    /** The arrow shown where no window has an X cursor of its own; the server keeps it. */
    private void loadRootCursor() {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            Bitmap bitmap = BitmapFactory.decodeResource(getContext().getResources(), R.drawable.cursor, options);
            if (bitmap == null) return;
            // An ARGB_8888 bitmap is R,G,B,A in memory: what the renderer's cursor texture wants.
            ByteBuffer buf = ByteBuffer.allocate(bitmap.getWidth() * bitmap.getHeight() * 4);
            bitmap.copyPixelsToBuffer(buf);
            XCore.setDefaultCursor(buf.array(), bitmap.getWidth(), bitmap.getHeight());
        } catch (Exception ignored) {
            // No arrow: where no window has a cursor of its own, none is drawn.
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
    private native void nativeSetTransformAndScissor(long handle,
        float ox, float oy, float sx, float sy,
        boolean hasScissor, int scX, int scY, int scW, int scH);
    /** The table of the renderer's entry points for the X server (see XCore.setRenderer). */
    private static native long nativeRendererApi();
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

        final String cacheDir = pipelineCacheDir();

        initExecutor.execute(() -> {
            synchronized (lock) {
                if (nativeHandle != 0) {
                    boolean ok = nativeReattachSurface(nativeHandle, surface);
                    if (!ok) {
                        // No frame may reach the renderer from here on.
                        XCore.setRenderer(0, 0);
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
                        // The renderer kept its window tree and cursor with the surface away.
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

                    // The server hands everything to this renderer from now on (and forgets what
                    // it gave a previous one): it starts by sending its whole state, the window
                    // tree, the contents, the pointer and the cursor, so a renderer re-created
                    // after a failed re-attach, or one that came up after the first windows,
                    // shows the screen as it is.
                    XCore.setRenderer(nativeRendererApi(), nativeHandle);
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
                // Returns once no frame is being handed to it: then it can go.
                XCore.setRenderer(0, 0);
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

    @Override
    public void onServerStarted() {
        // The server sends its window tree to the renderer itself; only the shown part is ours.
        refreshDesktopTransform();
    }

    @Override
    public void onMapWindow(int window) {
        refreshDesktopTransform();
    }

    @Override
    public void onUnmapWindow(int window) {
        refreshDesktopTransform();
    }

    @Override
    public void onUpdateWindowGeometry(int window, boolean resized) {
        if (resized) refreshDesktopTransform();
    }

    @Override
    public void onModifyWindowProperty(int window, int atom) {
        // WM_CLASS is what marks Wine's desktop window; it may be set after the window is mapped.
        if (atom == ATOM_WM_CLASS) refreshDesktopTransform();
    }

    // ------------------------------------------------------------------ frames

    /*
     * The frames themselves go from the server to the renderer directly (XCore.setRenderer), and
     * so do the drawables the server drops and the display clock for Present. What is left here
     * is counting the rated window's DRI3 frames for the HUD, from the server's periodic report.
     */
    @Override
    public void onUpdateWindowContentDirect(int window, int pixmap, int frames, long timeNs) {
        if (window == fpsWindowId) {
            if (hudRef != null) hudRef.onFrames(frames, timeNs, true);
            if (classicHudRef != null) classicHudRef.update(frames, timeNs, true);
        }
    }

    // ------------------------------------------------------------------ pointer and cursor

    @Override
    public void onPointerMove(int x, int y) {
        onPointerMove((short) x, (short) y);
    }

    /** The renderer draws the cursor where the server tells it; the transform may follow it. */
    @Override
    public void onPointerMove(short x, short y) {
        if (!screenOffsetYRelativeToCursor && magnifierZoom == 1.0f) return;
        synchronized (lock) { updateTransform(); }
    }

    @Override
    public void setCursorVisible(boolean visible) {
        // The server combines it with the X cursor's own visibility (an empty mask hides it).
        XCore.setCursorShown(visible);
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
    public void setUnviewableWMClasses(String... classes) { XCore.setHiddenWindowClasses(classes); }

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
