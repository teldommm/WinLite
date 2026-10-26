package com.winlator.cmod.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.winlator.cmod.R;
import com.winlator.cmod.renderer.GPUImage;
import com.winlator.cmod.xserver.Atom;
import com.winlator.cmod.xserver.Bitmask;
import com.winlator.cmod.xserver.Cursor;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.Property;
import com.winlator.cmod.xserver.ScreenInfo;
import com.winlator.cmod.xserver.ShmFramePacer;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.WindowAttributes;
import com.winlator.cmod.xserver.WindowManager;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XServer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * X server presentation through SurfaceFlinger (API 29+): every viewable X window is a layer
 * (ASurfaceControl) fed with the window's own AHardwareBuffer, and SurfaceFlinger composes them
 * - the app has no Vulkan swapchain and no composite pass of its own. An alternative to
 * {@link VulkanXServerView}; it cannot offer what needs the app's own shaders: frame
 * generation, upscalers, post-processing, the magnifier's resampling, stretch, R/B swap.
 *
 * The window tree is sent to the native side as one list (nativeSetLayout) whenever it changes;
 * frames from the Present extension go straight to their layer (nativePresent); the scene
 * transform, the cursor and CPU-drawn content are flushed once per vsync (nativeFlush).
 */
@SuppressLint("ViewConstructor")
public class ASurfaceXServerView extends XServerRendererView implements SurfaceHolder.Callback,
        WindowManager.OnWindowModificationListener, Pointer.OnPointerMotionListener {
    private static final String TAG = "ASurfaceXServerView";

    private static volatile boolean nativeLibLoaded = false;
    private static volatile boolean supportChecked = false;
    private static volatile boolean supported = false;
    private static volatile boolean gpuImageChecked = false;

    /** Whether this device can run the SurfaceFlinger path (API 29+, NDK entry points present). */
    public static synchronized boolean isSupported() {
        if (!supportChecked) {
            supportChecked = true;
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    System.loadLibrary("asurface_renderer");
                    nativeLibLoaded = true;
                    supported = nativeIsSupported();
                } catch (UnsatisfiedLinkError e) {
                    Log.e(TAG, "asurface_renderer is not available", e);
                    supported = false;
                }
            }
        }
        return supported;
    }

    private static native boolean nativeIsSupported();

    private long nativeHandle = 0;
    private final Object lock = new Object();
    private final ExecutorService eventExecutor = Executors.newSingleThreadExecutor();
    private final Set<Integer> compositeRedirectedWindows = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean layoutQueued = new AtomicBoolean();
    private final VsyncClock vsyncClock = new VsyncClock(this::onVsyncTick);

    // What each window shows when that is not its own content: the pixmap of its latest Present
    // (or direct copy). Guarded by `lock`. Entries stay after the pixmap is freed (the native
    // layer keeps showing the last frame); they go with the window.
    private static final class Shown {
        final Drawable drawable;
        final int xOff, yOff;
        Shown(Drawable drawable, int xOff, int yOff) {
            this.drawable = drawable; this.xOff = xOff; this.yOff = yOff;
        }
    }
    private final HashMap<Integer, Shown> shown = new HashMap<>();

    // Layout arrays, reused; guarded by `lock`.
    private long[] layoutL = new long[3 * 32];
    private int[] layoutV = new int[5 * 32];
    private int layoutCount;
    private final ArrayList<Window> collected = new ArrayList<>();

    private volatile ScreenInfo effectiveScreen;
    private boolean fullscreen = false;
    private float magnifierZoom = 1.0f;
    private boolean screenOffsetYRelativeToCursor = false;
    public int surfaceWidth;
    public int surfaceHeight;
    private String[] unviewableWMClasses = null;
    private boolean cursorVisible = false;
    private final Drawable rootCursorDrawable;
    private Cursor lastCursor = null;
    private volatile boolean inPipMode = false;

    /** Told (on the UI thread, outside every lock) that the native side could not start. */
    public interface OnStartFailedListener { void onStartFailed(); }
    private volatile OnStartFailedListener startFailedListener;

    public void setOnStartFailedListener(OnStartFailedListener listener) {
        startFailedListener = listener;
    }

    private WinlatorHUD hudRef = null;
    private FrameRating classicHudRef = null;
    private volatile int fpsWindowId = -1;
    private volatile int fpsLimit = 0;
    // FPS limiter state per window (System.nanoTime() base). Guarded by `lock`.
    private final java.util.HashMap<Integer, Pacer> pacers = new java.util.HashMap<>();

    private static final class Pacer {
        long idealNs;           // when the previous frame was meant to be presented (0 = no cadence yet)
        long lastReleaseNs;     // when the last buffer was planned to go back to the guest
        long lastPresentNs;
        double guestMeanNs, guestDevNs;   // time the guest needs from getting a buffer to presenting it
        boolean guestValid;
        long recent0, recent1;  // the last two buffers presented (to tell how many the guest cycles through)
    }

    public ASurfaceXServerView(Context context, XServer xServer) {
        super(context, xServer);
        // Loads the native library (the native methods below need it).
        if (!isSupported()) throw new IllegalStateException("SurfaceFlinger renderer is not supported here");
        setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        getHolder().addCallback(this);

        rootCursorDrawable = createRootCursorDrawable();
        xServer.setXServerView(this);
        effectiveScreen = xServer.getEffectiveScreenInfo();
        xServer.windowManager.addOnWindowModificationListener(this);
        xServer.pointer.addOnPointerMotionListener(this);
    }

    private Drawable createRootCursorDrawable() {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            Bitmap bitmap = BitmapFactory.decodeResource(getContext().getResources(), R.drawable.cursor, options);
            return Drawable.fromBitmap(bitmap);
        } catch (Exception e) {
            return null;
        }
    }

    private native long nativeInit(Surface surface, int screenWidth, int screenHeight);
    private native void nativeDestroy(long handle);
    private native void nativeDetachSurface(long handle);
    private native boolean nativeReattachSurface(long handle, Surface surface);
    private native void nativeResize(long handle, int width, int height);
    private native void nativeSetLayout(long handle, long[] l, int[] v, int count);
    private native void nativeSetTransform(long handle, float ox, float oy, float sx, float sy,
                                           boolean clip, int cx, int cy, int cw, int ch);
    private native boolean nativePresent(long handle, long windowId, long sourceUid, long ahb,
                                         int offX, int offY, boolean immediate, long desiredPresentNs);
    private native void nativeFlush(long handle);
    private native void nativeSetPointer(long handle, int x, int y);
    private native void nativeSetCursorVisible(long handle, boolean visible);
    private native void nativeSetCursorImage(long handle, ByteBuffer pixels, int width, int height,
                                             int stride, int hotX, int hotY, boolean bgra);
    private native void nativeReleaseDrawable(long handle, long uid);
    private native boolean nativeSourceBusy(long handle, long uid);
    private native long nativeBeginWait(long handle, long uid);
    private native void nativeEndWait(long handle, long uid, long serial, long timeoutNs);

    private static long did(Drawable d) {
        return d != null ? d.uid : 0L;
    }

    // ------------------------------------------------------------------ surface lifecycle

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
        boolean started;
        synchronized (lock) {
            if (nativeHandle != 0) {
                if (!nativeReattachSurface(nativeHandle, surface)) {
                    nativeDestroy(nativeHandle);
                    nativeHandle = 0;
                }
            }
            if (nativeHandle == 0) {
                nativeHandle = nativeInit(surface, xServer.screenInfo.width, xServer.screenInfo.height);
            }
            started = nativeHandle != 0;
            if (started) {
                pushDisplayRefreshRateLocked();
                vsyncClock.start();
                updateTransform();
                restoreCursorStateLocked();
            }
        }
        if (!started) {
            // Nothing would ever be drawn: tell the owner.
            Log.e(TAG, "SurfaceFlinger renderer could not be started");
            final OnStartFailedListener listener = startFailedListener;
            if (listener != null) listener.onStartFailed();
            return;
        }
        // The native side starts without layers after (re)creation: send the whole tree.
        requestWindowLayout();
    }

    public void onSurfaceChanged(int width, int height) {
        if (inPipMode) return;
        surfaceWidth = width;
        surfaceHeight = height;
        ScreenInfo inner = effectiveScreen;
        viewTransformation.update(width, height, inner.width, inner.height);
        synchronized (lock) {
            if (nativeHandle != 0) {
                nativeResize(nativeHandle, width, height);
                updateTransform();
                pushDisplayRefreshRateLocked();
            }
        }
        requestWindowLayout();
    }

    public void onSurfaceDestroyed() {
        vsyncClock.stop();
        synchronized (lock) {
            if (nativeHandle != 0) nativeDetachSurface(nativeHandle);
        }
    }

    private void pushDisplayRefreshRateLocked() {
        android.view.Display display = getDisplay();
        float hz = display != null ? display.getRefreshRate() : 0f;
        if (hz > 1f) vsyncClock.setNominalPeriod(Math.round(1.0e9 / hz));
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

    private void onVsyncTick(long frameTimeNs, long deadlineNs, long presentNs) {
        synchronized (lock) {
            if (nativeHandle != 0) nativeFlush(nativeHandle);
        }
    }

    @Override
    public boolean getVsync(long nowNs, long[] out) { return vsyncClock.sampleAt(nowNs, out); }

    // ------------------------------------------------------------------ buffer hand-back

    @Override
    public boolean isGuestBufferBusy(Drawable buffer) {
        synchronized (lock) {
            return nativeHandle != 0 && buffer != null && nativeSourceBusy(nativeHandle, did(buffer));
        }
    }

    @Override
    public void waitGuestBufferIdle(Drawable buffer, long timeoutNs) {
        final long handle, serial;
        final long uid = did(buffer);
        // Registered under the lock nativeDestroy runs under, waited for outside it: the native
        // side holds the context until a registered wait has returned.
        synchronized (lock) {
            if (nativeHandle == 0 || buffer == null) return;
            handle = nativeHandle;
            serial = nativeBeginWait(handle, uid);
        }
        if (serial != 0) nativeEndWait(handle, uid, serial, timeoutNs);
    }

    @Override
    public void onDrawableReleased(Drawable drawable) {
        if (drawable == null) return;
        synchronized (lock) {
            if (nativeHandle != 0) nativeReleaseDrawable(nativeHandle, did(drawable));
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onDestroy() {
        forceCleanup();
    }

    public void forceCleanup() {
        fpsWindowId = -1;
        hudRef = null;
        classicHudRef = null;
        compositeRedirectedWindows.clear();
        vsyncClock.stop();
        synchronized (lock) {
            if (nativeHandle != 0) {
                nativeDestroy(nativeHandle);
                nativeHandle = 0;
            }
            shown.clear();
        }
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
    public void onPause() {}
    @Override
    public void onResume() {}

    // ------------------------------------------------------------------ transform

    /**
     * Same scene transform as the Vulkan view, expressed in surface pixels for the native side:
     * the Vulkan renderer places X coordinate x at (ox + x * sx) / screenWidth of the surface.
     */
    private void updateTransform() {
        if (nativeHandle == 0 || surfaceWidth <= 0 || surfaceHeight <= 0) return;
        final float zoom = magnifierZoom;
        final float ptrX = xServer.pointer.getX();
        final float ptrY = xServer.pointer.getY();

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
                short halfH = (short) (inner.height / 2);
                py = Math.max(0, Math.min(ptrY - halfH / 2.0f, halfH));
            }
            ox = (viewTransformation.sceneOffsetX + ptrX * viewTransformation.sceneScaleX * (1f - zoom)) * fx;
            oy = (viewTransformation.sceneOffsetY - py + ptrY * viewTransformation.sceneScaleY * (1f - zoom)) * fy;
            sx = viewTransformation.sceneScaleX * zoom * fx;
            sy = viewTransformation.sceneScaleY * zoom * fy;
        }

        final float kx = (float) surfaceWidth / xServer.screenInfo.width;
        final float ky = (float) surfaceHeight / xServer.screenInfo.height;
        final boolean needClip = !(fullscreen && zoom == 1.0f);
        nativeSetTransform(nativeHandle, ox * kx, oy * ky, sx * kx, sy * ky,
                needClip,
                viewTransformation.viewOffsetX, viewTransformation.viewOffsetY,
                viewTransformation.viewWidth, viewTransformation.viewHeight);
    }

    private boolean isRootChild(Window window) {
        return window != null && window.getParent() == xServer.windowManager.rootWindow;
    }

    /** Re-reads the effective screen size and re-applies the transform (runs on eventExecutor). */
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

    // ------------------------------------------------------------------ cursor

    private void restoreCursorStateLocked() {
        if (nativeHandle == 0) return;
        Window pw = xServer.inputDeviceManager.getPointWindow();
        lastCursor = pw != null ? pw.attributes.getCursor() : null;
        sendCursorToNative(lastCursor);
    }

    private void sendCursorToNative(Cursor cursor) {
        if (nativeHandle == 0) return;
        Drawable cd;
        short hotX = 0, hotY = 0;
        boolean effVis = cursorVisible;
        if (cursor != null) {
            if (!cursor.isVisible()) effVis = false;
            cd = cursor.cursorImage;
            hotX = (short) cursor.hotSpotX;
            hotY = (short) cursor.hotSpotY;
        } else {
            cd = rootCursorDrawable;
        }
        nativeSetCursorVisible(nativeHandle, effVis);
        if (effVis && cd != null) {
            // Checked under renderLock: Drawable.release() zeroes backingAHB under it.
            synchronized (cd.renderLock) {
                final long ahb = cd.backingAHB;
                if (ahb == 0) return;
                ByteBuffer pixels = cd.lockBuffer(ahb);
                if (pixels != null) {
                    try {
                        nativeSetCursorImage(nativeHandle, pixels, cd.width, cd.height,
                                cd.getStride(), hotX, hotY,
                                cd.format == Drawable.HAL_PIXEL_FORMAT_BGRA_8888);
                    } finally {
                        cd.unlockBuffer(ahb);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ window tree

    private void requestWindowLayout() {
        if (layoutQueued.compareAndSet(false, true)) queueEvent(this::relayout);
    }

    /**
     * Sends the X server's window tree to the native side: the windows the Vulkan renderer would
     * draw, back to front, each with its root position and what it shows. Runs on eventExecutor;
     * the X server's locks come before {@code lock}, as everywhere else.
     */
    private void relayout() {
        layoutQueued.set(false);
        try (XLock xLock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
            synchronized (lock) {
                if (nativeHandle == 0) return;
                for (Iterator<Integer> it = shown.keySet().iterator(); it.hasNext(); ) {
                    if (xServer.windowManager.getWindow(it.next()) == null) it.remove();
                }
                collected.clear();
                collectLocked(xServer.windowManager.rootWindow, true);
                // Everything below the topmost window that covers the whole screen is hidden
                // behind it: no layers for those.
                final int screenW = xServer.screenInfo.width, screenH = xServer.screenInfo.height;
                int start = 0;
                for (int i = collected.size() - 1; i >= 0; i--) {
                    if (coversScreenLocked(collected.get(i), screenW, screenH)) { start = i; break; }
                }
                layoutCount = 0;
                for (int i = start; i < collected.size(); i++) {
                    final Window w = collected.get(i);
                    addLayerLocked(w, w.getContent());
                }
                collected.clear();
                nativeSetLayout(nativeHandle, layoutL, layoutV, layoutCount);
            }
        }
    }

    /**
     * Same rules as the Vulkan renderer's draw list: an unmapped window hides its subtree; a
     * window of an unviewable class, without content or 1x1 (a helper window) is not drawn
     * itself, its children still are. The root window is never drawn.
     */
    private void collectLocked(Window window, boolean isRoot) {
        if (!isRoot) {
            if (!window.attributes.isMapped()) return;
            final int w = window.getWidth(), h = window.getHeight();
            if (window.getContent() != null && isViewable(window) && w >= 1 && h >= 1 && !(w == 1 && h == 1)) {
                collected.add(window);
            }
        }
        final List<Window> children = window.getChildren();
        for (int i = 0; i < children.size(); i++) collectLocked(children.get(i), false);
    }

    /**
     * Whether the window's layer, as it will be drawn (its Present pixmap at its offset, or its
     * own content), covers the whole screen: what lies below it cannot be seen. Every layer is
     * opaque (see addLayerLocked), so coverage alone decides.
     */
    private boolean coversScreenLocked(Window window, int screenW, int screenH) {
        final Shown s = shown.get(window.id);
        final Drawable d = s != null ? s.drawable : window.getContent();
        if (d == null || d.backingAHB == 0) return false;     // nothing (known) to show: covers nothing
        final int x = window.getRootX() + (s != null ? s.xOff : 0);
        final int y = window.getRootY() + (s != null ? s.yOff : 0);
        return x <= 0 && y <= 0 && x + d.width >= screenW && y + d.height >= screenH;
    }

    /**
     * Windows are opaque, as the Vulkan renderer draws them (opaque pipeline, texture alpha
     * ignored; only the cursor blends). The visual cannot tell otherwise: the X server's only
     * displayable visual is depth 32, so every window and pixmap has it. Marking layers
     * translucent from it made SurfaceFlinger blend every layer and keep every window below the
     * game (and the background) in the composition, and showed whatever alpha the guest left in
     * its buffers.
     */
    private void addLayerLocked(Window window, Drawable content) {
        long uid, ahb;
        int offX = 0, offY = 0;
        final Shown s = shown.get(window.id);
        if (s != null) {
            synchronized (s.drawable.renderLock) { ahb = s.drawable.backingAHB; }
            if (ahb != 0) {
                uid = did(s.drawable);
                offX = s.xOff;
                offY = s.yOff;
            } else {
                // The pixmap is gone: the layer keeps the last frame it was given.
                uid = -1;
            }
        } else {
            synchronized (content.renderLock) { ahb = content.backingAHB; }
            uid = ahb != 0 ? did(content) : 0;
        }
        final boolean alpha = false;
        if (layoutCount * 3 + 3 > layoutL.length) {
            layoutL = java.util.Arrays.copyOf(layoutL, layoutL.length * 2);
            layoutV = java.util.Arrays.copyOf(layoutV, layoutV.length * 2);
        }
        final int i = layoutCount++;
        layoutL[i * 3] = window.id;
        layoutL[i * 3 + 1] = uid;
        layoutL[i * 3 + 2] = uid > 0 ? ahb : 0;
        layoutV[i * 5] = window.getRootX();
        layoutV[i * 5 + 1] = window.getRootY();
        layoutV[i * 5 + 2] = offX;
        layoutV[i * 5 + 3] = offY;
        layoutV[i * 5 + 4] = alpha ? 1 : 0;
    }

    private boolean isViewable(Window window) {
        if (unviewableWMClasses == null || window == xServer.windowManager.rootWindow) return true;
        String wc = window.getClassName();
        for (String cls : unviewableWMClasses) if (wc.contains(cls)) return false;
        return true;
    }

    private void checkViewable(Window window) {
        if (unviewableWMClasses == null || window == xServer.windowManager.rootWindow) return;
        String wc = window.getClassName();
        for (String cls : unviewableWMClasses) {
            if (wc.contains(cls)) {
                if (window.attributes.isEnabled()) window.disableAllDescendants();
                break;
            }
        }
        requestWindowLayout();
    }

    @Override
    public void onCreateWindow(Window window, Window parent) {
        checkViewable(window);
        requestWindowLayout();
    }

    @Override
    public void onDestroyWindow(Window window) {
        compositeRedirectedWindows.remove(window.id);
        requestWindowLayout();
    }

    @Override
    public void onMapWindow(Window window) {
        if (isRootChild(window)) queueEvent(this::refreshDesktopTransform);
        checkViewable(window);
        requestWindowLayout();
    }

    @Override
    public void onUnmapWindow(Window window) {
        if (isRootChild(window)) queueEvent(this::refreshDesktopTransform);
        requestWindowLayout();
    }

    @Override
    public void onReparentWindow(Window window, Window newParent, short x, short y) {
        requestWindowLayout();
    }

    @Override
    public void onChangeWindowZOrder(Window.StackMode stackMode, Window window, Window sibling) {
        requestWindowLayout();
    }

    @Override
    public void onUpdateWindowGeometry(Window window, boolean resized) {
        if (resized && isRootChild(window)) queueEvent(this::refreshDesktopTransform);
        requestWindowLayout();
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
                if (pw == window) {
                    lastCursor = window.attributes.getCursor();
                    sendCursorToNative(lastCursor);
                }
            }
        }
    }

    // ------------------------------------------------------------------ frames

    @Override
    public void onUpdateWindowContentDirect(Window window, Drawable drawable) {
        onUpdateWindowContentDirect(window, drawable, (short) 0, (short) 0);
    }

    public void onUpdateWindowContentDirect(Window window, Drawable pixmap, short xOff, short yOff) {
        countFrameForHud(window);
        synchronized (lock) {
            if (nativeHandle == 0 || pixmap == null) return;
            if (window.getContent() == null) return;
            synchronized (pixmap.renderLock) {
                final long ahb = pixmap.backingAHB;
                if (ahb != 0) {
                    shown.put(window.id, new Shown(pixmap, xOff, yOff));
                    nativePresent(nativeHandle, window.id, did(pixmap), ahb, xOff, yOff, false, 0);
                }
            }
        }
    }

    /**
     * PresentPixmap: the layer shows the pixmap's buffer at once (the frame is not held back).
     * Buffers go back to the guest when SurfaceFlinger has released them (isGuestBufferBusy), so
     * the plan only carries the FPS limit. Like the Vulkan renderer's limiter it hands a buffer
     * back JUST IN TIME - early enough that the guest, which needs about guestFrameNs from getting
     * a buffer to presenting it, delivers its next frame at that frame's slot - and not at the slot
     * itself, which would make CPU and GPU work of the guest run one after the other (a game that
     * manages 70 fps with them overlapped drops to 40 under a 60 fps limit that way). Slots are
     * on the display's vsync grid when the limit is a multiple of the refresh (or equals it),
     * so presents keep one phase against SurfaceFlinger's latch instead of beating against it.
     */
    @Override
    public void onGuestPresent(Window window, Drawable pixmap, long guestFrameNs, long[] plan) {
        countFrameForHud(window);
        synchronized (lock) {
            if (nativeHandle == 0 || pixmap == null) return;
            if (window.getContent() == null) return;
            final long desiredNs = planPresent(window.id, did(pixmap), guestFrameNs, plan);
            synchronized (pixmap.renderLock) {
                final long ahb = pixmap.backingAHB;
                if (ahb != 0) {
                    shown.put(window.id, new Shown(pixmap, 0, 0));
                    nativePresent(nativeHandle, window.id, did(pixmap), ahb, 0, 0, true, desiredNs);
                }
            }
        }
    }

    /**
     * Fills the hand-back plan (see PresentExtension.planReleasesLocked) and returns the time
     * (System.nanoTime() base = CLOCK_MONOTONIC) SurfaceFlinger should hold the frame back to, 0 =
     * show it as soon as possible. Called under `lock`.
     */
    private long planPresent(int windowId, long srcId, long guestFrameNs, long[] plan) {
        final long now = System.nanoTime();
        if (pacers.size() > 1) {
            java.util.Iterator<java.util.Map.Entry<Integer, Pacer>> it = pacers.entrySet().iterator();
            while (it.hasNext()) {   // windows that stopped presenting drop their state
                java.util.Map.Entry<Integer, Pacer> e = it.next();
                if (e.getKey() != windowId && now - e.getValue().lastPresentNs > 2_000_000_000L) it.remove();
            }
        }
        Pacer pc = pacers.get(windowId);
        if (pc == null) { pc = new Pacer(); pacers.put(windowId, pc); }
        pc.lastPresentNs = now;

        if (guestFrameNs >= 0 && guestFrameNs < 1_000_000_000L) {
            if (!pc.guestValid) {
                pc.guestMeanNs = guestFrameNs;
                pc.guestDevNs = guestFrameNs * 0.25;
            } else {
                final double d = guestFrameNs - pc.guestMeanNs;
                pc.guestMeanNs += 0.1 * d;
                pc.guestDevNs += 0.1 * (Math.abs(d) - pc.guestDevNs);
            }
            pc.guestValid = true;
        }

        // Two buffers may be out at once only if the guest has at least three (one stays here as
        // the newest frame); a constant 2 would time a two-buffer guest's only free buffer for
        // the slot after next.
        int distinct = 1;
        if (pc.recent0 != 0 && pc.recent0 != srcId) distinct++;
        if (pc.recent1 != 0 && pc.recent1 != srcId && pc.recent1 != pc.recent0) distinct++;
        pc.recent1 = pc.recent0;
        pc.recent0 = srcId;
        final int depth = distinct >= 3 ? 2 : 1;

        final int limit = fpsLimit;
        if (limit <= 0) {
            pc.idealNs = 0;
            pc.lastReleaseNs = 0;
            return 0;   // plan stays zero: buffers go back at once, Present completes at once
        }

        long spacing = 1_000_000_000L / limit;

        // Vsync grid: presents land half a refresh before a vsync, the most margin to either
        // side of SurfaceFlinger's latch. Multiples of the refresh (60 on 120 Hz) and a limit at
        // the refresh (within 5 %) use it; anything else (45 on 60 Hz) has no even cadence anyway.
        boolean grid = false;
        long period = 0, ust = 0;
        final long[] vs = new long[3];
        if (vsyncClock.sampleAt(now, vs) && vs[2] > 0) {
            period = vs[2];
            ust = vs[1];
            final long ratio = Math.round((double) spacing / period);
            if (ratio >= 1 && Math.abs(spacing - ratio * period) <= period / 20) {
                grid = true;
                spacing = ratio * period;
            }
        }
        final long half = period / 2;

        long ideal = pc.idealNs != 0 ? pc.idealNs + spacing : 0;
        // A present later than its slot by up to a quarter slot keeps the cadence (the next slot
        // comes sooner); only a real miss restarts it, from now.
        final long tolerance = spacing / 4;
        final boolean late = ideal != 0 && ideal + tolerance < now;
        if (ideal == 0 || late) {
            ideal = now;
            if (grid) {
                // First present time on the grid that is not in the past.
                final long k = (long) Math.ceil((double) (now - ust + half) / period);
                ideal = ust + k * period - half;
            }
        } else if (grid) {
            // Follow the clock's drift: back onto the grid point nearest to the ideal.
            final long k = Math.round((double) (ideal - ust + half) / period);
            ideal = ust + k * period - half;
        }
        pc.idealNs = ideal;

        final long guestEst = pc.guestValid
                ? (long) (pc.guestMeanNs + 3.0 * pc.guestDevNs) : spacing / 2;
        // The buffer handed back now is for the frame that is due at ideal + spacing * depth.
        final long target = ideal + spacing * depth;
        final long justInTime = target - guestEst - 2_000_000L;
        final long release = Math.max(Math.max(justInTime, pc.lastReleaseNs + spacing * 95 / 100), now);
        pc.lastReleaseNs = release;

        plan[0] = release;
        plan[1] = 0;        // the frame is shown at once; SurfaceFlinger's reads are isGuestBufferBusy's
        plan[2] = depth;
        // Present completes at once: the frame is not on screen until SurfaceFlinger latches it,
        // and a completion at a time of our own would stack on the guest's own vsync waits.
        plan[3] = 0;
        // Held for its slot: SurfaceFlinger latches it at the vsync after that time, whatever the
        // jitter of the guest's present. Frames that are late are shown at once; nothing is held
        // for longer than a few refreshes (a stale clock, a restarted cadence).
        if (late || ideal <= now) return 0;
        if (ideal - now > Math.max(4 * spacing, 100_000_000L)) return 0;
        return ideal;
    }

    private void countFrameForHud(Window window) {
        if (window.id == fpsWindowId) {
            if (hudRef != null) {
                hudRef.setFrameGenPresentedRate(0f);
                hudRef.onFrame();
            }
            if (classicHudRef != null) {
                classicHudRef.setFrameGenPresentedRate(0f);
                classicHudRef.update();
            }
        }
    }

    public void addDirectContent(int windowId, Drawable drawable, GPUImage gpuImage) {
    }

    @Override
    public void onUpdateWindowContent(Window window) {
        synchronized (lock) {
            if (nativeHandle == 0) return;
            Drawable drawable = window.getContent();
            if (drawable == null || !window.attributes.isMapped()) return;
            synchronized (drawable.renderLock) {
                final long ahb = drawable.backingAHB;
                if (ahb != 0) {
                    shown.remove(window.id);
                    nativePresent(nativeHandle, window.id, did(drawable), ahb, 0, 0, false, 0);
                }
            }
        }
    }

    @Override
    public void onPointerMove(short x, short y) {
        synchronized (lock) {
            if (nativeHandle == 0) return;
            nativeSetPointer(nativeHandle, x, y);
            Window pw = xServer.inputDeviceManager.getPointWindow();
            Cursor cursor = pw != null ? pw.attributes.getCursor() : null;
            if (cursor != lastCursor) {
                lastCursor = cursor;
                sendCursorToNative(cursor);
            }
            if (screenOffsetYRelativeToCursor || magnifierZoom != 1.0f) updateTransform();
        }
    }

    // ------------------------------------------------------------------ composite redirect

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
                sibling.setX((short) posX);
                sibling.setY((short) posY);
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

    // ------------------------------------------------------------------ settings

    @Override
    public void setCursorVisible(boolean visible) {
        cursorVisible = visible;
        synchronized (lock) {
            if (nativeHandle != 0) {
                if (visible) sendCursorToNative(lastCursor);
                else nativeSetCursorVisible(nativeHandle, false);
            }
        }
    }

    public boolean isCursorVisible() { return cursorVisible; }

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
    public void setUnviewableWMClasses(String... classes) {
        this.unviewableWMClasses = classes;
        requestWindowLayout();
    }

    public void setFpsWindowId(int id) { fpsWindowId = id; }

    public void setFrameRating(Object fr) {
        if (fr instanceof WinlatorHUD) hudRef = (WinlatorHUD) fr;
        else if (fr instanceof FrameRating) classicHudRef = (FrameRating) fr;
    }

    public int getFpsLimit() { return fpsLimit; }

    public void setFpsLimit(int limit) {
        this.fpsLimit = Math.max(0, Math.min(1000, limit));
        // Same cap for clients that draw with MIT-SHM blits and never reach the Present limiter.
        ShmFramePacer.setFrameRateLimit(this.fpsLimit);
        synchronized (lock) { pacers.clear(); }
    }

    public void setPipMode(boolean pip) { inPipMode = pip; }
    public int getSurfaceWidth() { return surfaceWidth; }
    public int getSurfaceHeight() { return surfaceHeight; }
    public void requestRender() {}
}
