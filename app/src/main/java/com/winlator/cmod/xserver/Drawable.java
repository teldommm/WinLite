package com.winlator.cmod.xserver;

import android.graphics.Bitmap;

import com.winlator.cmod.core.Callback;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.renderer.GPUImage;

import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class Drawable extends XResource {
    private static final AtomicLong UID_GENERATOR = new AtomicLong();

    /**
     * Process-unique, never reused key for this drawable. The renderer keys its textures by it
     * (System.identityHashCode() was used before: not unique, so two drawables could share one).
     */
    public final long uid = UID_GENERATOR.incrementAndGet();

    public final short width;
    public short stride;
    /** The AHardwareBuffer this drawable owns; 0 once {@link #release()} has run. */
    public volatile long backingAHB;
    public final short height;
    public final Visual visual;
    /** Pixel order of this drawable's buffer. */
    public final SurfaceFormat format;

    private GPUImage gpuImage = null;
    private boolean directScanout;
    private Runnable onDrawListener;
    private Callback<Drawable> onDestroyListener;
    private boolean offscreen = false;
    public final Object renderLock = new Object();
    private final Backing backing;

    static {
        System.loadLibrary("winlator");
    }

    public Drawable(int id, int width, int height, Visual visual, SurfaceFormat format) {
        super(id);
        this.width = (short)width;
        this.height = (short)height;
        this.visual = visual;
        this.format = format;
        long ahb = allocate(width, height, format.halFormat);
        if (ahb == 0) {
            throw new IllegalStateException("Drawable data initialized as null!");
        }
        this.backingAHB = ahb;
        this.backing = Backing.track(this, ahb);
    }

    /**
     * A drawable backed by a buffer the client handed over (DRI3). Nothing is allocated here:
     * the buffer received from the client becomes the backing, and this drawable owns its
     * reference from now on.
     */
    public Drawable(int id, int width, int height, Visual visual, GPUImage gpuImage) {
        super(id);
        if (gpuImage == null || gpuImage.hardwareBufferPtr == 0) {
            throw new IllegalStateException("Drawable created from an empty GPUImage");
        }
        this.width = (short)width;
        this.height = (short)height;
        this.visual = visual;
        this.gpuImage = gpuImage;
        this.format = SurfaceFormat.fromHalFormat(gpuImage.format);
        this.backingAHB = gpuImage.hardwareBufferPtr;
        this.backing = Backing.track(this, gpuImage.hardwareBufferPtr);
    }

    public static Drawable fromBitmap(Bitmap bitmap, SurfaceFormat format) {
        if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) return null;

        Drawable drawable = new Drawable(0, bitmap.getWidth(), bitmap.getHeight(), null, format);
        ByteBuffer dst = drawable.lockBuffer(drawable.backingAHB);
        if (dst == null) return null;

        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int dstStride = drawable.getStride();
            final boolean rgba = format == SurfaceFormat.RGBA;
            int[] row = new int[width];
            for (int y = 0; y < height; y++) {
                bitmap.getPixels(row, 0, width, 0, y, width, 1);
                int dstOffset = y * dstStride * 4;
                for (int x = 0; x < width; x++) {
                    int color = row[x];
                    int offset = dstOffset + x * 4;
                    // Bitmap pixels are ARGB: R at bits 16..23, B at bits 0..7.
                    byte r = (byte)((color >> 16) & 0xff);
                    byte b = (byte)(color & 0xff);
                    dst.put(offset, rgba ? r : b);
                    dst.put(offset + 1, (byte)((color >> 8) & 0xff));
                    dst.put(offset + 2, rgba ? b : r);
                    dst.put(offset + 3, (byte)((color >>> 24) & 0xff));
                }
            }
        } finally {
            drawable.unlockBuffer(drawable.backingAHB);
        }
        return drawable;
    }

    /**
     * Gives the backing buffer back. Called by DrawableManager when the X resource is gone
     * (pixmap freed, window destroyed, window content replaced on resize): all of those run
     * under the DRAWABLE_MANAGER lock, the same lock every draw request holds, so no draw can
     * be using the buffer here. Readers outside the X locks (the cursor upload) take renderLock
     * and check for 0. The renderer holds its own reference on everything it imported, so the
     * GPU may still be sampling this buffer; it is freed when the last reference goes.
     */
    public void release() {
        synchronized (renderLock) {
            if (backingAHB == 0) return;
            backingAHB = 0;
            onDrawListener = null;
            backing.free();
        }
    }

    public boolean isReleased() {
        return backingAHB == 0;
    }

    public GPUImage getGPUImage() {
        return this.gpuImage;
    }

    public void setDirectScanout(boolean directScanout) {
        this.directScanout = directScanout;
    }

    public boolean isDirectScanout() {
        return directScanout;
    }

    public short getStride() {
        return gpuImage != null ? gpuImage.getStride() : stride;
    }

    public Runnable getOnDrawListener() {
        return onDrawListener;
    }

    public void setOnDrawListener(Runnable onDrawListener) {
        this.onDrawListener = onDrawListener;
    }

    public Callback<Drawable> getOnDestroyListener() {
        return onDestroyListener;
    }

    public void setOnDestroyListener(Callback<Drawable> onDestroyListener) {
        this.onDestroyListener = onDestroyListener;
    }

    public void drawImage(short srcX, short srcY, short dstX, short dstY, short width, short height, byte depth, ByteBuffer data, short totalWidth, short totalHeight) {
        final long ahb = backingAHB;
        if (ahb == 0) return;
        if (depth == 1) {
            // Placed at (dstX, dstY) and clipped by the native side (it used to be drawn at 0,0
            // and not clipped at all).
            drawBitmap(dstX, dstY, width, height, data, this.getStride(), this.width, this.height, ahb);
        }
        else if (depth == 24 || depth == 32) {
            // Clip to the drawable, moving the source origin along: clamping the destination
            // alone shifted the image (negative dstX) and could read past the source.
            int sx = srcX, sy = srcY, dx = dstX, dy = dstY, w = width, h = height;
            if (dx < 0) { sx -= dx; w += dx; dx = 0; }
            if (dy < 0) { sy -= dy; h += dy; dy = 0; }
            if (dx + w > this.width) w = this.width - dx;
            if (dy + h > this.height) h = this.height - dy;
            if (w <= 0 || h <= 0 || sx < 0 || sy < 0) { data.rewind(); return; }

            copyArea1((short)sx, (short)sy, (short)dx, (short)dy, (short)w, (short)h, totalWidth, this.getStride(), data, ahb);
        }

        data.rewind();

        Runnable listener = onDrawListener;
        if (listener != null) listener.run();
    }

    public ByteBuffer getImage(short x, short y, short width, short height) {
        ByteBuffer dstData = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
        final long ahb = backingAHB;
        if (ahb == 0) return dstData;

        x = (short)Mathf.clamp(x, 0, this.width-1);
        y = (short)Mathf.clamp(y, 0, this.height-1);
        if ((x + width) > this.width) width = (short)(this.width - x);
        if ((y + height) > this.height) height = (short)(this.height - y);

        copyArea2(x, y, (short)0, (short)0, width, height, this.getStride(), width, ahb, dstData);

        dstData.rewind();
        return dstData;
    }

    public void copyArea(short srcX, short srcY, short dstX, short dstY, short width, short height, Drawable drawable) {
        copyArea(srcX, srcY, dstX, dstY, width, height, drawable, GraphicsContext.Function.COPY);
    }

    public void copyArea(short srcX, short srcY, short dstX, short dstY, short width, short height, Drawable drawable, GraphicsContext.Function gcFunction) {
        final long dstAhb = backingAHB;
        final long srcAhb = drawable.backingAHB;
        if (dstAhb == 0 || srcAhb == 0) return;
        // Clip to both drawables: the source rectangle was not checked at all, so a copy from
        // beyond the source's edge read past its buffer.
        int sx = srcX, sy = srcY, dx = dstX, dy = dstY, w = width, h = height;
        if (sx < 0) { dx -= sx; w += sx; sx = 0; }
        if (sy < 0) { dy -= sy; h += sy; sy = 0; }
        if (dx < 0) { sx -= dx; w += dx; dx = 0; }
        if (dy < 0) { sy -= dy; h += dy; dy = 0; }
        w = Math.min(w, Math.min(drawable.width - sx, this.width - dx));
        h = Math.min(h, Math.min(drawable.height - sy, this.height - dy));
        if (w <= 0 || h <= 0) return;

        if (gcFunction == GraphicsContext.Function.COPY) {
            copyArea3((short)sx, (short)sy, (short)dx, (short)dy, (short)w, (short)h, drawable.getStride(), this.getStride(), srcAhb, dstAhb);
        }
        else copyAreaOp((short)sx, (short)sy, (short)dx, (short)dy, (short)w, (short)h, drawable.getStride(), this.getStride(), srcAhb, dstAhb, gcFunction.ordinal());
        Runnable listener = onDrawListener;
        if (listener != null) listener.run();
    }

    public void fillColor(int color) {
        fillRect(0, 0, width, height, color);
    }

    public void fillRect(int x, int y, int width, int height, int color) {
        final long ahb = backingAHB;
        if (ahb == 0) return;
        // Clipped, not clamped: a rectangle starting left of / above the drawable used to keep
        // its full size from the edge.
        if (x < 0) { width += x; x = 0; }
        if (y < 0) { height += y; y = 0; }
        if ((x + width) > this.width) width = this.width - x;
        if ((y + height) > this.height) height = this.height - y;
        if (width <= 0 || height <= 0) return;

        fillRect((short)x, (short)y, (short)width, (short)height, color, this.getStride(), ahb);

        Runnable listener = onDrawListener;
        if (listener != null) listener.run();
    }

    /**
     * PolyFillRectangle: rects holds (x, y, width, height) quadruples. Every rectangle is clipped
     * like {@link #fillRect}, then the whole request is drawn with one buffer lock and one content
     * update - it used to be one lock/unlock (a cache flush of the whole buffer on many gralloc
     * implementations) and one renderer wake-up per rectangle.
     */
    public void fillRects(short[] rects, int color) {
        final long ahb = backingAHB;
        if (ahb == 0 || rects.length < 4) return;
        short[] clipped = new short[rects.length - rects.length % 4];
        int count = 0;
        for (int i = 0; i + 3 < rects.length; i += 4) {
            int x = rects[i], y = rects[i+1], w = rects[i+2], h = rects[i+3];
            if (x < 0) { w += x; x = 0; }
            if (y < 0) { h += y; y = 0; }
            if (x + w > this.width) w = this.width - x;
            if (y + h > this.height) h = this.height - y;
            if (w <= 0 || h <= 0) continue;
            clipped[count*4] = (short)x; clipped[count*4+1] = (short)y;
            clipped[count*4+2] = (short)w; clipped[count*4+3] = (short)h;
            count++;
        }
        if (count == 0) return;

        fillRects(clipped, count, color, this.getStride(), ahb);

        Runnable listener = onDrawListener;
        if (listener != null) listener.run();
    }

    /** PolyLine: the segments between consecutive points, with one buffer lock and one content
     *  update for the whole request (it was one per segment). Endpoints are clamped as in
     *  {@link #drawLine}. */
    public void drawLines(int color, int lineWidth, short... points) {
        final long ahb = backingAHB;
        if (ahb == 0 || points.length < 4) return;
        if (lineWidth <= 0 || lineWidth > width || lineWidth > height) return;
        final int count = points.length / 2;
        short[] clamped = new short[count * 2];
        for (int i = 0; i < count; i++) {
            clamped[i*2] = (short)Mathf.clamp(points[i*2], 0, width-lineWidth);
            clamped[i*2+1] = (short)Mathf.clamp(points[i*2+1], 0, height-lineWidth);
        }

        drawPolyLine(clamped, count, color, (short)lineWidth, this.getStride(), ahb);

        Runnable listener = onDrawListener;
        if (listener != null) listener.run();
    }

    public void drawLine(int x0, int y0, int x1, int y1, int color, int lineWidth) {
        final long ahb = backingAHB;
        if (ahb == 0) return;
        // The square pen must fit the drawable, or the clamps below go negative.
        if (lineWidth <= 0 || lineWidth > width || lineWidth > height) return;
        x0 = Mathf.clamp(x0, 0, width-lineWidth);
        y0 = Mathf.clamp(y0, 0, height-lineWidth);
        x1 = Mathf.clamp(x1, 0, width-lineWidth);
        y1 = Mathf.clamp(y1, 0, height-lineWidth);

        drawLine((short)x0, (short)y0, (short)x1, (short)y1, color, (short)lineWidth, this.getStride(), ahb);

        Runnable listener = onDrawListener;
        if (listener != null) listener.run();
    }

    public void drawAlphaMaskedBitmap(byte foreRed, byte foreGreen, byte foreBlue, byte backRed, byte backGreen, byte backBlue, Drawable srcDrawable, Drawable maskDrawable) {
        final long ahb = backingAHB;
        final long srcAhb = srcDrawable.backingAHB;
        final long maskAhb = maskDrawable.backingAHB;
        if (ahb == 0 || srcAhb == 0 || maskAhb == 0) return;
        drawAlphaMaskedBitmap(foreRed, foreGreen, foreBlue, backRed, backGreen, backBlue, srcAhb, srcDrawable.getStride(), maskAhb, maskDrawable.getStride(), this.width, this.height, this.getStride(), ahb);

        Runnable listener = onDrawListener;
        if (listener != null) listener.run();
    }

    public void updateDirect() {
        Runnable listener = onDrawListener;
        if (listener != null) listener.run();
    }

    public void setOffscreen(boolean offscreen) {
        this.offscreen = offscreen;
    }

    public boolean isOffscreen() {
        return this.offscreen;
    }

    /**
     * Owns the drawable's AHardwareBuffer reference. Freed explicitly by {@link #release()};
     * a drawable that is never released (cursor images, which a window may keep using after
     * FreeCursor, or one lost to an exception) is freed when it becomes unreachable instead.
     */
    private static final class Backing extends PhantomReference<Drawable> {
        private static final ReferenceQueue<Drawable> QUEUE = new ReferenceQueue<>();
        // Phantom references must stay reachable until they are enqueued.
        private static final Set<Backing> LIVE = ConcurrentHashMap.newKeySet();
        private static volatile boolean reaperStarted = false;

        private final AtomicLong ahb;

        private Backing(Drawable owner, long ahb) {
            super(owner, QUEUE);
            this.ahb = new AtomicLong(ahb);
        }

        static Backing track(Drawable owner, long ahb) {
            startReaper();
            Backing backing = new Backing(owner, ahb);
            LIVE.add(backing);
            return backing;
        }

        void free() {
            LIVE.remove(this);
            clear();
            long ptr = ahb.getAndSet(0);
            if (ptr != 0) releaseBuffer(ptr);
        }

        private static void startReaper() {
            if (reaperStarted) return;
            synchronized (Backing.class) {
                if (reaperStarted) return;
                Thread reaper = new Thread(() -> {
                    while (true) {
                        try {
                            ((Backing) QUEUE.remove()).free();
                        } catch (InterruptedException e) {
                            return;
                        } catch (Throwable ignored) {
                        }
                    }
                }, "DrawableReaper");
                reaper.setDaemon(true);
                reaper.start();
                reaperStarted = true;
            }
        }
    }

    private static native void drawBitmap(short dstX, short dstY, short width, short height, ByteBuffer srcData, short stride, short dstWidth, short dstHeight, long dstAHB);

    private static native void drawAlphaMaskedBitmap(byte foreRed, byte foreGreen, byte foreBlue, byte backRed, byte backGreen, byte backBlue, long srcAHB, short srcStride, long maskAHB, short maskStride, short width, short height, short stride, long dstAHB);

    private static native void copyArea1(short srcX, short srcY, short dstX, short dstY, short width, short height, short srcStride, short dstStride, ByteBuffer srcData, long dstAHB);

    private static native void copyArea2(short srcX, short srcY, short dstX, short dstY, short width, short height, short srcStride, short dstStride, long srcAHB, ByteBuffer dstData);

    private static native void copyArea3(short srcX, short srcY, short dstX, short dstY, short width, short height, short srcStride, short dstStride, long srcAHB, long dstAHB);

    private static native void copyAreaOp(short srcX, short srcY, short dstX, short dstY, short width, short height, short srcStride, short dstStride, long srcAHB, long dstAHB, int gcFunction);

    private static native void fillRect(short x, short y, short width, short height, int color, short stride, long dstAHB);

    private static native void fillRects(short[] rects, int count, int color, short stride, long dstAHB);

    private static native void drawPolyLine(short[] points, int count, int color, short lineWidth, short stride, long dstAHB);

    private static native void drawLine(short x0, short y0, short x1, short y1, int color, short lineWidth, short stride, long dstAHB);

    private static native void releaseBuffer(long ahb);

    private native long allocate(int width, int height, int format);

    public native ByteBuffer lockBuffer(long ahb);

    public native void unlockBuffer(long ahb);
}
