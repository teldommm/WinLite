package com.winlator.cmod.xserver;

import android.graphics.Bitmap;

import com.winlator.cmod.core.Callback;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.renderer.GPUImage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicLong;

public class Drawable extends XResource {
    private static final AtomicLong UID_GENERATOR = new AtomicLong();

    /**
     * Process-unique, never reused key for this drawable. The renderer keys its textures by it
     * (System.identityHashCode() is not unique, so two drawables could end up sharing one).
     */
    public final long uid = UID_GENERATOR.incrementAndGet();

    public final short width;
    public short stride;
    /** The AHardwareBuffer this drawable owns; 0 once its last reference has been dropped. */
    public volatile long backingAHB;
    public final short height;
    public final Visual visual;
    /** Pixel order of this drawable's buffer. */
    public SurfaceFormat format;

    private GPUImage gpuImage = null;
    private boolean directScanout;
    private Runnable onDrawListener;
    private Callback<Drawable> onDestroyListener;
    private boolean offscreen = false;
    public final Object renderLock = new Object();
    /** Users of the buffer: the X resource itself, plus one per cursor built from it. Guarded by renderLock. */
    private int refCount = 1;

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

    public void setGPUImage(GPUImage texture) {
        final long allocated = this.backingAHB;
        this.gpuImage = texture;
        this.backingAHB = gpuImage.hardwareBufferPtr;
        this.format = SurfaceFormat.fromHalFormat(gpuImage.format);
        // The buffer allocated by the constructor is replaced by the client's one before anything
        // has seen it (no draw listener yet, never handed to the renderer): give it back instead
        // of leaking a full-size buffer per DRI3 pixmap.
        if (allocated != 0 && this.backingAHB != 0 && allocated != this.backingAHB) releaseBuffer(allocated);
    }

    /** Another user of the buffer (a cursor built from this pixmap); pair with releaseRef(). */
    public void retain() {
        synchronized (renderLock) {
            if (refCount > 0) refCount++;
        }
    }

    /**
     * Drops one reference. Returns the buffer to give back with {@link #dropBacking} when that was
     * the last one (backingAHB is cleared here, under renderLock - the lock every update of the
     * renderer's texture is made under - so nothing starts using the buffer afterwards), else 0.
     */
    public long releaseRef() {
        synchronized (renderLock) {
            if (refCount <= 0) return 0;
            if (--refCount > 0) return 0;
            final long ahb = backingAHB;
            backingAHB = 0;
            onDrawListener = null;
            return ahb;
        }
    }

    /** Gives back the reference the drawable held on its buffer (see {@link #releaseRef}). */
    public static void dropBacking(long ahb) {
        if (ahb != 0) releaseBuffer(ahb);
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
