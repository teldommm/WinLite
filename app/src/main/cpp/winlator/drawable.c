#include <jni.h>
#include <string.h>
#include <malloc.h>
#include <stdbool.h>
#include <stdlib.h>
#include <math.h>

#include <android/hardware_buffer.h>
#include <android/bitmap.h>
#include <android/log.h>

#define WHITE 0xffffff
#define BLACK 0x000000
#define printf(...) __android_log_print(ANDROID_LOG_DEBUG, "Drawable", __VA_ARGS__);
#define HAL_PIXEL_FORMAT_BGRA_8888 5

enum GCFunction {GCF_CLEAR, GCF_AND, GCF_AND_REVERSE, GCF_COPY, GCF_AND_INVERTED, GCF_NO_OP, GCF_XOR, GCF_OR, GCF_NOR, GCF_EQUIV, GCF_INVERT, GCF_OR_REVERSE, GCF_COPY_INVERTED, GCF_OR_INVERTED, GCF_NAND, GCF_SET};

static int packColor(int8_t r, int8_t g, int8_t b) {
    return ((r & 0xff) << 16) | ((g & 0xff) << 8) | (b & 0xff);
}

static int8_t getBit(uint8_t *line, int x) {
    uint8_t mask = (1 << (x & 7));
    line += (x >> 3);
    return (*line & mask) ? 1 : 0;
}

static int getBitmapBytePad(int width) {
    return ((width + 32 - 1) >> 5) << 2;
}

static int setPixelOp(int srcColor, int dstColor, enum GCFunction gcFunction) {
    switch (gcFunction) {
        case GCF_CLEAR:
            return BLACK;
        case GCF_AND:
            return srcColor & dstColor;
        case GCF_AND_REVERSE:
            return srcColor & ~dstColor;
        case GCF_COPY:
            return srcColor;
        case GCF_AND_INVERTED:
            return ~srcColor & dstColor;
        case GCF_XOR:
            return srcColor ^ dstColor;
        case GCF_OR:
            return srcColor | dstColor;
        case GCF_NOR:
            return ~srcColor & ~dstColor;
        case GCF_EQUIV:
            return ~srcColor ^ dstColor;
        case GCF_INVERT:
            return ~dstColor;
        case GCF_OR_REVERSE:
            return srcColor | ~dstColor;
        case GCF_COPY_INVERTED:
            return ~srcColor;
        case GCF_OR_INVERTED:
            return ~srcColor | dstColor;
        case GCF_NAND:
            return ~srcColor | ~dstColor;
        case GCF_SET:
            return WHITE;
        case GCF_NO_OP:
        default:
            return dstColor;
    }
}

/* Solid ARGB pixel for an X color (0xRRGGBB): the buffers are BGRA in memory, i.e. this value
 * stored little-endian - as BGRA bytes with alpha 255. */
static inline uint32_t solidPixel(int color) {
    return ((uint32_t)color & 0xffffffu) | 0xff000000u;
}

/*
 * Channel order of a drawable's buffer.
 *
 * The X visual is fixed (red 0xff0000, green 0x00ff00, blue 0x0000ff), so everything the X
 * protocol hands us - PutImage data, pixel values, colors - is B,G,R,A in memory. A drawable
 * allocated as BGRA_8888 takes those bytes as they are. With the "rgba8" surface format the
 * server's drawables are RGBA_8888 buffers (R first in memory), and the same bytes would show
 * with red and blue exchanged: Wine's desktop, wallpaper and window colors (all CPU drawn).
 * So every place where pixels cross between the X protocol and a buffer converts when the
 * buffer is RGBA. Copies between two buffers of the same order stay plain copies.
 */
static inline bool isRgbaOrder(AHardwareBuffer *hardwareBuffer) {
    AHardwareBuffer_Desc d;
    AHardwareBuffer_describe(hardwareBuffer, &d);
    return d.format == AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM ||
           d.format == AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM;
}

/* Exchanges the first and the third byte of a 32-bit pixel (B,G,R,A <-> R,G,B,A in memory). */
static inline uint32_t swapRB32(uint32_t p) {
    return (p & 0xff00ff00u) | ((p & 0x000000ffu) << 16) | ((p >> 16) & 0x000000ffu);
}

/* Copies count pixels, exchanging red and blue. memcpy-sized accesses: the source may be client
 * memory with no 4-byte alignment guarantee. */
static void copyRowSwapRB(uint8_t *dst, const uint8_t *src, size_t count) {
    for (size_t i = 0; i < count; i++) {
        uint32_t p;
        memcpy(&p, src + i * 4, 4);
        p = swapRB32(p);
        memcpy(dst + i * 4, &p, 4);
    }
}

/*
 * Every function below locks the buffers it needs and, on every path out - including the
 * failure of a later lock - unlocks exactly the ones it locked. A buffer left locked by an early
 * return made every later lock of it fail, so the window it backs never updated again.
 *
 * The Java side clips every rectangle to the drawables involved before calling in here.
 */

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xserver_Drawable_drawBitmap(JNIEnv *env, jclass obj,
                                              jshort dstX, jshort dstY,
                                              jshort width, jshort height,
                                              jobject srcData, jshort dstStride,
                                              jshort dstWidth, jshort dstHeight,
                                              jlong dstData) {
    uint8_t *srcDataAddr = (*env)->GetDirectBufferAddress(env, srcData);
    if (!srcDataAddr || width <= 0 || height <= 0) return;

    /* The bitmap's rows are padded to 32 bits; never read past what the client sent. */
    const int srcRowBytes = getBitmapBytePad(width);
    const jlong capacity = (*env)->GetDirectBufferCapacity(env, srcData);
    if (capacity >= 0 && (jlong)srcRowBytes * height > capacity) height = (jshort)(capacity / srcRowBytes);
    if (height <= 0) return;

    int *dstDataAddr;
    AHardwareBuffer *hardwareBuffer = (AHardwareBuffer *)dstData;
    if (AHardwareBuffer_lock(hardwareBuffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, NULL, (void **)&dstDataAddr) != 0)
        return;
    if (!dstDataAddr) {
        printf("Error: NULL buffer address in drawBitmap\n");
        AHardwareBuffer_unlock(hardwareBuffer, NULL);
        return;
    }

    /* Placed at (dstX, dstY) and clipped to the drawable. This used to ignore the destination
     * position and the drawable's size, writing past the end of the buffer for a bitmap larger
     * than the pixmap. */
    for (int y = 0; y < height; y++) {
        const int dy = dstY + y;
        if (dy < 0) continue;
        if (dy >= dstHeight) break;
        const uint8_t *srcRow = srcDataAddr + (size_t)y * srcRowBytes;
        int *dst = dstDataAddr + (size_t)dy * dstStride;
        for (int x = 0; x < width; x++) {
            const int dx = dstX + x;
            if (dx < 0) continue;
            if (dx >= dstWidth) break;
            dst[dx] = getBit((uint8_t *)srcRow, x) ? WHITE : BLACK;
        }
    }

    AHardwareBuffer_unlock(hardwareBuffer, NULL);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xserver_Drawable_copyArea1(JNIEnv *env, jclass obj, jshort srcX,
                                            jshort srcY, jshort dstX, jshort dstY,
                                            jshort width, jshort height,
                                            jshort srcStride, jshort dstStride,
                                            jobject srcData, jlong dstData) {
    uint8_t *srcDataAddr = (*env)->GetDirectBufferAddress(env, srcData);
    if (!srcDataAddr || width <= 0 || height <= 0 || srcX < 0 || srcY < 0) return;

    /* The source is client memory (the request, or a MIT-SHM segment): never read past it. */
    if (srcX + width > srcStride) return;
    const jlong capacity = (*env)->GetDirectBufferCapacity(env, srcData);
    const jlong needed = ((jlong)(srcY + height - 1) * srcStride + srcX + width) * 4;
    if (capacity >= 0 && needed > capacity) {
        printf("Error: PutImage source out of bounds (%lld > %lld)\n", (long long)needed, (long long)capacity);
        return;
    }

    uint8_t *dstDataAddr;
    AHardwareBuffer *hardwareBuffer = (AHardwareBuffer *)dstData;
    if (AHardwareBuffer_lock(hardwareBuffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, NULL, (void **)&dstDataAddr) != 0)
        return;
    if (!dstDataAddr) {
        printf("Error: NULL buffer address in copyArea\n");
        AHardwareBuffer_unlock(hardwareBuffer, NULL);
        return;
    }

    /* Client data is B,G,R,A: an RGBA buffer needs red and blue exchanged on the way in. */
    const bool swap = isRgbaOrder(hardwareBuffer);
    const size_t copyAmount = (size_t)width * 4;
    for (int y = 0; y < height; y++) {
        uint8_t *dstRow = dstDataAddr + ((size_t)dstX + (size_t)(y + dstY) * dstStride) * 4;
        const uint8_t *srcRow = srcDataAddr + ((size_t)srcX + (size_t)(y + srcY) * srcStride) * 4;
        if (swap) copyRowSwapRB(dstRow, srcRow, (size_t)width);
        else memcpy(dstRow, srcRow, copyAmount);
    }

    AHardwareBuffer_unlock(hardwareBuffer, NULL);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xserver_Drawable_copyArea2(JNIEnv *env, jclass obj, jshort srcX,
                                            jshort srcY, jshort dstX, jshort dstY,
                                            jshort width, jshort height, jshort srcStride,
                                            jshort dstStride, jlong srcData,
                                            jobject dstData) {
    uint8_t *dstDataAddr = (*env)->GetDirectBufferAddress(env, dstData);
    if (!dstDataAddr || width <= 0 || height <= 0) return;

    uint8_t *srcDataAddr;
    AHardwareBuffer *hardwareBuffer = (AHardwareBuffer *)srcData;
    if (AHardwareBuffer_lock(hardwareBuffer, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, NULL, (void **)&srcDataAddr) != 0)
        return;
    if (!srcDataAddr) {
        printf("Error: NULL buffer address in copyArea\n");
        AHardwareBuffer_unlock(hardwareBuffer, NULL);
        return;
    }

    /* GetImage reply data is B,G,R,A: an RGBA buffer needs red and blue exchanged on the way out. */
    const bool swap = isRgbaOrder(hardwareBuffer);
    const size_t copyAmount = (size_t)width * 4;
    for (int y = 0; y < height; y++) {
        uint8_t *dstRow = dstDataAddr + ((size_t)dstX + (size_t)(y + dstY) * dstStride) * 4;
        const uint8_t *srcRow = srcDataAddr + ((size_t)srcX + (size_t)(y + srcY) * srcStride) * 4;
        if (swap) copyRowSwapRB(dstRow, srcRow, (size_t)width);
        else memcpy(dstRow, srcRow, copyAmount);
    }

    AHardwareBuffer_unlock(hardwareBuffer, NULL);
}

/*
 * Locks the source for reading and the destination for writing. When both are the same buffer
 * (CopyArea within one window or pixmap, e.g. scrolling) it is locked once, read+write: locking
 * a buffer that is already locked is not something every gralloc allows. Returns false with
 * nothing left locked.
 */
static bool lockSrcDst(AHardwareBuffer *src, AHardwareBuffer *dst, uint8_t **srcAddr, uint8_t **dstAddr) {
    *srcAddr = NULL; *dstAddr = NULL;
    if (src == dst) {
        if (AHardwareBuffer_lock(dst, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN | AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN,
                                 -1, NULL, (void **)dstAddr) != 0) return false;
        if (!*dstAddr) { AHardwareBuffer_unlock(dst, NULL); return false; }
        *srcAddr = *dstAddr;
        return true;
    }
    if (AHardwareBuffer_lock(src, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, NULL, (void **)srcAddr) != 0)
        return false;
    if (AHardwareBuffer_lock(dst, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, NULL, (void **)dstAddr) != 0) {
        AHardwareBuffer_unlock(src, NULL);
        return false;
    }
    if (!*srcAddr || !*dstAddr) {
        AHardwareBuffer_unlock(dst, NULL);
        AHardwareBuffer_unlock(src, NULL);
        return false;
    }
    return true;
}

static void unlockSrcDst(AHardwareBuffer *src, AHardwareBuffer *dst) {
    AHardwareBuffer_unlock(dst, NULL);
    if (src != dst) AHardwareBuffer_unlock(src, NULL);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xserver_Drawable_copyArea3(JNIEnv *env, jclass obj, jshort srcX,
                                            jshort srcY, jshort dstX, jshort dstY,
                                            jshort width, jshort height, jshort srcStride,
                                            jshort dstStride, jlong srcData,
                                            jlong dstData) {
    if (width <= 0 || height <= 0) return;
    AHardwareBuffer *srcHardwareBuffer = (AHardwareBuffer *)srcData;
    AHardwareBuffer *dstHardwareBuffer = (AHardwareBuffer *)dstData;
    uint8_t *srcDataAddr, *dstDataAddr;
    if (!lockSrcDst(srcHardwareBuffer, dstHardwareBuffer, &srcDataAddr, &dstDataAddr)) {
        printf("Error: could not lock buffers in copyArea\n");
        return;
    }

    /* Source and destination may overlap (same buffer): copy rows bottom-up when moving down,
     * and memmove within a row, so no row is overwritten before it has been read. */
    const size_t copyAmount = (size_t)width * 4;
    const bool bottomUp = srcHardwareBuffer == dstHardwareBuffer && dstY > srcY;
    /* Two buffers of different channel order (never the same buffer, so no overlap) convert. */
    const bool swap = srcHardwareBuffer != dstHardwareBuffer &&
                      isRgbaOrder(srcHardwareBuffer) != isRgbaOrder(dstHardwareBuffer);
    for (int i = 0; i < height; i++) {
        const int y = bottomUp ? height - 1 - i : i;
        uint8_t *dstRow = dstDataAddr + ((size_t)dstX + (size_t)(y + dstY) * dstStride) * 4;
        const uint8_t *srcRow = srcDataAddr + ((size_t)srcX + (size_t)(y + srcY) * srcStride) * 4;
        if (swap) copyRowSwapRB(dstRow, srcRow, (size_t)width);
        else memmove(dstRow, srcRow, copyAmount);
    }

    unlockSrcDst(srcHardwareBuffer, dstHardwareBuffer);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xserver_Drawable_copyAreaOp(JNIEnv *env, jclass obj, jshort srcX,
                                              jshort srcY, jshort dstX, jshort dstY,
                                              jshort width, jshort height, jshort srcStride,
                                              jshort dstStride, jlong srcData,
                                              jlong dstData, int gcFunction) {
    if (width <= 0 || height <= 0) return;
    AHardwareBuffer *srcHardwareBuffer = (AHardwareBuffer *)srcData;
    AHardwareBuffer *dstHardwareBuffer = (AHardwareBuffer *)dstData;
    uint8_t *srcDataAddr, *dstDataAddr;
    if (!lockSrcDst(srcHardwareBuffer, dstHardwareBuffer, &srcDataAddr, &dstDataAddr)) {
        printf("Error: could not lock buffers in copyAreaOp\n");
        return;
    }

    /* Overlap-safe order within one buffer: walk away from the direction of the move. */
    const bool same = srcHardwareBuffer == dstHardwareBuffer;
    const bool bottomUp = same && dstY > srcY;
    const bool rightToLeft = same && dstY == srcY && dstX > srcX;
    /* The raster operation combines colors, so the source must be in the destination's order. */
    const bool swapSrc = !same && isRgbaOrder(srcHardwareBuffer) != isRgbaOrder(dstHardwareBuffer);
    for (int i = 0; i < height; i++) {
        const int y = bottomUp ? height - 1 - i : i;
        for (int k = 0; k < width; k++) {
            const int x = rightToLeft ? width - 1 - k : k;
            const size_t si = ((size_t)x + srcX + (size_t)(y + srcY) * srcStride) * 4;
            const size_t di = ((size_t)x + dstX + (size_t)(y + dstY) * dstStride) * 4;
            int srcColor = swapSrc ? (srcDataAddr[si+2] << 16) | (srcDataAddr[si+1] << 8) | srcDataAddr[si]
                                   : (srcDataAddr[si] << 16) | (srcDataAddr[si+1] << 8) | srcDataAddr[si+2];
            int dstColor = (dstDataAddr[di] << 16) | (dstDataAddr[di+1] << 8) | dstDataAddr[di+2];

            dstColor = setPixelOp(srcColor, dstColor, gcFunction);

            dstDataAddr[di] = (dstColor >> 16) & 0xff;
            dstDataAddr[di+1] = (dstColor >> 8) & 0xff;
            dstDataAddr[di+2] = dstColor & 0xff;
        }
    }

    unlockSrcDst(srcHardwareBuffer, dstHardwareBuffer);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xserver_Drawable_fillRect(JNIEnv *env, jclass obj, jshort x, jshort y,
                                            jshort width, jshort height, jint color, jshort stride,
                                            jlong data) {
    if (width <= 0 || height <= 0) return;
    uint8_t *dataAddr;
    AHardwareBuffer *hardwareBuffer = (AHardwareBuffer *)data;
    if (AHardwareBuffer_lock(hardwareBuffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, NULL, (void **)&dataAddr) != 0)
        return;
    if (!dataAddr) {
        printf("Error: NULL buffer address in fillRect\n");
        AHardwareBuffer_unlock(hardwareBuffer, NULL);
        return;
    }

    /* Written in place: the per-call malloc'ed row is gone, and with it the path that returned
     * on a failed malloc with the buffer still locked. */
    const uint32_t pixel = isRgbaOrder(hardwareBuffer) ? swapRB32(solidPixel(color)) : solidPixel(color);
    for (int i = 0; i < height; i++) {
        uint32_t *row = (uint32_t *)(dataAddr + ((size_t)x + (size_t)(i + y) * stride) * 4);
        for (int k = 0; k < width; k++) row[k] = pixel;
    }

    AHardwareBuffer_unlock(hardwareBuffer, NULL);
}

/* Bresenham line with a square pen of lineWidth pixels; the caller has clamped both endpoints
 * so the pen stays inside the buffer. */
static void plotLine(uint8_t *dataAddr, int x0, int y0, int x1, int y1, uint32_t pixel,
                     int lineWidth, int stride) {
    int dx =  abs(x1 - x0);
    int dy = -abs(y1 - y0);
    int sx = x0 < x1 ? 1 : -1;
    int sy = y0 < y1 ? 1 : -1;
    int e1 = dx + dy, e2;
    int px = x0, py = y0;

    while (true) {
        for (int i = 0; i < lineWidth; i++) {
            uint32_t *row = (uint32_t *)(dataAddr + ((size_t)px + (size_t)(i + py) * stride) * 4);
            for (int k = 0; k < lineWidth; k++) row[k] = pixel;
        }
        if (px == x1 && py == y1) break;

        e2 = e1 * 2;
        if (e2 >= dy) {
            e1 += dy;
            px += sx;
        }
        if (e2 <= dx) {
            e1 += dx;
            py += sy;
        }
    }
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xserver_Drawable_drawLine(JNIEnv *env, jclass obj, jshort x0, jshort y0,
                                            jshort x1, jshort y1, jint color, jshort lineWidth,
                                            jshort stride, jlong data) {
    if (lineWidth <= 0) return;
    uint8_t *dataAddr;
    AHardwareBuffer *hardwareBuffer = (AHardwareBuffer *)data;
    if (AHardwareBuffer_lock(hardwareBuffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, NULL, (void **)&dataAddr) != 0)
        return;
    if (!dataAddr) {
        printf("Error: NULL buffer address in drawLine\n");
        AHardwareBuffer_unlock(hardwareBuffer, NULL);
        return;
    }

    plotLine(dataAddr, x0, y0, x1, y1,
             isRgbaOrder(hardwareBuffer) ? swapRB32(solidPixel(color)) : solidPixel(color),
             lineWidth, stride);

    AHardwareBuffer_unlock(hardwareBuffer, NULL);
}

/* Copies a Java short[] (count values) to native memory: the buffer is then locked with no JNI
 * critical section open (a lock may wait in the driver). Returns NULL on failure. */
static jshort *copyShorts(JNIEnv *env, jshortArray array, jint count, jshort *stackBuf, int stackCap) {
    if (count <= 0 || (*env)->GetArrayLength(env, array) < count) return NULL;
    jshort *values = count <= stackCap ? stackBuf : malloc((size_t)count * sizeof(jshort));
    if (!values) return NULL;
    (*env)->GetShortArrayRegion(env, array, 0, count, values);
    if ((*env)->ExceptionCheck(env)) {
        if (values != stackBuf) free(values);
        return NULL;
    }
    return values;
}

/* PolyFillRectangle in one go: rects holds (x, y, width, height) quadruples, already clipped to
 * the drawable. One lock/unlock (one cache flush) for the whole request instead of one per
 * rectangle. */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_xserver_Drawable_fillRects(JNIEnv *env, jclass obj, jshortArray rects,
                                             jint count, jint color, jshort stride, jlong data) {
    jshort stackBuf[256];
    jshort *r = copyShorts(env, rects, count * 4, stackBuf, 256);
    if (!r) return;

    uint8_t *dataAddr;
    AHardwareBuffer *hardwareBuffer = (AHardwareBuffer *)data;
    if (AHardwareBuffer_lock(hardwareBuffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, NULL, (void **)&dataAddr) == 0) {
        if (dataAddr) {
            const uint32_t pixel = isRgbaOrder(hardwareBuffer) ? swapRB32(solidPixel(color)) : solidPixel(color);
            for (int n = 0; n < count; n++) {
                const int x = r[n*4], y = r[n*4+1], width = r[n*4+2], height = r[n*4+3];
                for (int i = 0; i < height; i++) {
                    uint32_t *row = (uint32_t *)(dataAddr + ((size_t)x + (size_t)(i + y) * stride) * 4);
                    for (int k = 0; k < width; k++) row[k] = pixel;
                }
            }
        }
        else printf("Error: NULL buffer address in fillRects\n");
        AHardwareBuffer_unlock(hardwareBuffer, NULL);
    }
    if (r != stackBuf) free(r);
}

/* PolyLine in one go: points holds (x, y) pairs, already clamped so the pen fits; one segment
 * between each consecutive pair, one lock/unlock for the whole request. */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_xserver_Drawable_drawPolyLine(JNIEnv *env, jclass obj, jshortArray points,
                                                jint count, jint color, jshort lineWidth,
                                                jshort stride, jlong data) {
    if (lineWidth <= 0 || count < 2) return;
    jshort stackBuf[256];
    jshort *p = copyShorts(env, points, count * 2, stackBuf, 256);
    if (!p) return;

    uint8_t *dataAddr;
    AHardwareBuffer *hardwareBuffer = (AHardwareBuffer *)data;
    if (AHardwareBuffer_lock(hardwareBuffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, NULL, (void **)&dataAddr) == 0) {
        if (dataAddr) {
            const uint32_t pixel = isRgbaOrder(hardwareBuffer) ? swapRB32(solidPixel(color)) : solidPixel(color);
            for (int n = 1; n < count; n++)
                plotLine(dataAddr, p[n*2-2], p[n*2-1], p[n*2], p[n*2+1], pixel, lineWidth, stride);
        }
        else printf("Error: NULL buffer address in drawPolyLine\n");
        AHardwareBuffer_unlock(hardwareBuffer, NULL);
    }
    if (p != stackBuf) free(p);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xserver_Drawable_drawAlphaMaskedBitmap(JNIEnv *env, jclass obj,
                                                         jbyte foreRed, jbyte foreGreen,
                                                         jbyte foreBlue, jbyte backRed,
                                                         jbyte backGreen, jbyte backBlue,
                                                         jlong srcData, jshort srcStride,
                                                         jlong maskData, jshort maskStride,
                                                         jshort width, jshort height,
                                                         jshort stride, jlong dstData) {
    int *srcDataAddr;
    int *maskDataAddr;
    int *dstDataAddr;

    AHardwareBuffer *srcHardwareBuffer = (AHardwareBuffer *)srcData;
    AHardwareBuffer *maskHardwareBuffer = (AHardwareBuffer *)maskData;
    AHardwareBuffer *dstHardwareBuffer = (AHardwareBuffer *)dstData;

    if (AHardwareBuffer_lock(srcHardwareBuffer, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, NULL, (void **)&srcDataAddr) != 0)
        return;
    if (AHardwareBuffer_lock(maskHardwareBuffer, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, NULL, (void **)&maskDataAddr) != 0) {
        AHardwareBuffer_unlock(srcHardwareBuffer, NULL);
        return;
    }
    if (AHardwareBuffer_lock(dstHardwareBuffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, NULL, (void **)&dstDataAddr) != 0) {
        AHardwareBuffer_unlock(maskHardwareBuffer, NULL);
        AHardwareBuffer_unlock(srcHardwareBuffer, NULL);
        return;
    }

    if (srcDataAddr && maskDataAddr && dstDataAddr) {
        int foreColor = packColor(foreRed, foreGreen, foreBlue);
        int backColor = packColor(backRed, backGreen, backBlue);
        if (isRgbaOrder(dstHardwareBuffer)) {
            foreColor = (int)swapRB32((uint32_t)foreColor);
            backColor = (int)swapRB32((uint32_t)backColor);
        }

        for (int y = 0; y < height; y++) {
            int rowStart = y * stride;
            int srcStart = y * srcStride;
            int maskStart = y * maskStride;
            for (int x = 0; x < width; x++) {
                int srcIdx = x + srcStart;
                int dstIdx = x + rowStart;
                int maskIdx = x + maskStart;
                dstDataAddr[dstIdx] = maskDataAddr[maskIdx] == WHITE ? (srcDataAddr[srcIdx] == WHITE ? foreColor : backColor) | 0xff000000 : 0x00000000;
            }
        }
    }
    else printf("Error: NULL buffer address in drawAlphaMaskedBitmap\n");

    AHardwareBuffer_unlock(dstHardwareBuffer, NULL);
    AHardwareBuffer_unlock(maskHardwareBuffer, NULL);
    AHardwareBuffer_unlock(srcHardwareBuffer, NULL);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xserver_Pixmap_toBitmap(JNIEnv *env, jclass obj,
                                               jshort colorStride, jlong colorData,
                                               jshort maskStride, jlong maskData,
                                               jobject bitmap) {
    char *colorDataAddr = NULL;
    char *maskDataAddr = NULL;
    uint8_t *pixels;
    AndroidBitmapInfo info;

    AHardwareBuffer *colorHardwareBuffer = (AHardwareBuffer *)colorData;
    AHardwareBuffer *maskHardwareBuffer = (AHardwareBuffer *)maskData;

    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) {
        printf("Error: Failed to get bitmap info in toBitmap\n");
        return;
    }
    if (AHardwareBuffer_lock(colorHardwareBuffer, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, NULL, (void **)&colorDataAddr) != 0)
        return;
    if (!colorDataAddr) {
        printf("Error: NULL color data address in toBitmap\n");
        goto unlock_color;
    }
    if (maskHardwareBuffer) {
        if (AHardwareBuffer_lock(maskHardwareBuffer, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, NULL, (void **)&maskDataAddr) != 0) {
            maskHardwareBuffer = NULL;   /* not locked: nothing to unlock */
            goto unlock_color;
        }
    }
    if (AndroidBitmap_lockPixels(env, bitmap, (void**)&pixels) < 0) {
        printf("Error: Failed to lock bitmap pixels in toBitmap\n");
        goto unlock_mask;
    }

    /* The Bitmap is R,G,B,A in memory. A BGRA source needs red and blue exchanged, an RGBA one
     * (rgba8 surface format) is already in that order. */
    const bool srcRgba = isRgbaOrder(colorHardwareBuffer);
    for (uint32_t y = 0; y < info.height; y++) {
        size_t srcStart = (size_t)y * colorStride;
        size_t dstStart = (size_t)y * info.width;
        size_t maskStart = (size_t)y * maskStride;
        for (uint32_t x = 0; x < info.width; x++) {
            size_t srcIdx = (x + srcStart) * 4;
            size_t dstIdx = (x + dstStart) * 4;
            size_t maskIdx = (x + maskStart) * 4;
            pixels[dstIdx + 2] = colorDataAddr[srcIdx + (srcRgba ? 2 : 0)];
            pixels[dstIdx + 1] = colorDataAddr[srcIdx + 1];
            pixels[dstIdx + 0] = colorDataAddr[srcIdx + (srcRgba ? 0 : 2)];
            pixels[dstIdx + 3] = maskDataAddr ? maskDataAddr[maskIdx + 0] : colorDataAddr[srcIdx + 3];
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
unlock_mask:
    if (maskHardwareBuffer) AHardwareBuffer_unlock(maskHardwareBuffer, NULL);
unlock_color:
    AHardwareBuffer_unlock(colorHardwareBuffer, NULL);
}

JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_xserver_Drawable_allocate(JNIEnv *env, jclass obj, jint width, jint height, jint format) {
    int ret;
    AHardwareBuffer *hardwareBuffer;
    
    AHardwareBuffer_Desc desc = {};
    desc.width = width;
    desc.height = height;
    desc.format = format;
    desc.layers = 1;
    desc.usage = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN |
                 AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN |
                 AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
    
    ret = AHardwareBuffer_allocate(&desc, &hardwareBuffer);
    if (ret != 0) {
        printf("Failed to allocate hardwareBuffer");
        return 0;
    }
    
    AHardwareBuffer_Desc outDesc;
    AHardwareBuffer_describe(hardwareBuffer, &outDesc);
    
    /* The result of this lock used to be ignored: on failure addr was garbage and the memset
     * wrote through it. A buffer that cannot be cleared is not handed out. */
    void *addr = NULL;
    const int lockRet = AHardwareBuffer_lock(hardwareBuffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, NULL, &addr);
    if (lockRet != 0 || !addr) {
        printf("Failed to lock new hardwareBuffer");
        if (lockRet == 0) AHardwareBuffer_unlock(hardwareBuffer, NULL);
        AHardwareBuffer_release(hardwareBuffer);
        return 0;
    }

    memset(addr, 0, (size_t)outDesc.stride * outDesc.height * 4);

    AHardwareBuffer_unlock(hardwareBuffer, NULL);
    
    jclass cls = (*env)->GetObjectClass(env, obj);
    if (!cls) {
        printf("Failed to find Drawable class");
        AHardwareBuffer_release(hardwareBuffer);
        return 0;
    }    
    
    jfieldID strideField = (*env)->GetFieldID(env, cls, "stride", "S");
    (*env)->SetShortField(env, obj, strideField, outDesc.stride);
    
    return (jlong)hardwareBuffer;
}

JNIEXPORT jobject JNICALL
Java_com_winlator_cmod_xserver_Drawable_lockBuffer(JNIEnv *env, jclass obj, jlong ahb) {
    int ret;
    void *addr;
    
    AHardwareBuffer *hardwareBuffer = (AHardwareBuffer *)ahb;
    if (!ahb)
        return NULL;
    
    AHardwareBuffer_Desc outDesc;
    AHardwareBuffer_describe(hardwareBuffer, &outDesc);
    
    ret = AHardwareBuffer_lock(hardwareBuffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN | 
        AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, NULL, (void **)&addr);
    if (ret != 0)    
        return NULL;
        
    jlong size = outDesc.stride * outDesc.height * 4;
    jobject buffer = (*env)->NewDirectByteBuffer(env, addr, size);
    
    return buffer;
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xserver_Drawable_unlockBuffer(JNIEnv *env, jclass obj, jlong ahb) {
    int ret;
    void *addr;
    
    AHardwareBuffer *hardwareBuffer = (AHardwareBuffer *)ahb;
    if (!ahb)
        return;
    
    AHardwareBuffer_unlock(hardwareBuffer, NULL);
}
JNIEXPORT void JNICALL
Java_com_winlator_cmod_xserver_Drawable_releaseBuffer(JNIEnv *env, jclass obj, jlong ahb) {
    // Drops the reference the Drawable owns (from AHardwareBuffer_allocate, or the one received
    // over the DRI3 socket). Renderer imports hold references of their own.
    if (ahb) AHardwareBuffer_release((AHardwareBuffer *)ahb);
}
