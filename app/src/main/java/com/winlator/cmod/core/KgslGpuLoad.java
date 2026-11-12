package com.winlator.cmod.core;

import android.os.SystemClock;
import android.system.OsConstants;
import android.util.Log;

import java.io.File;

/**
 * GPU load of an Adreno GPU without root, for the HUD when the kgsl sysfs nodes are blocked for apps.
 *
 * A native thread (gpu_busy.c) samples the timestamps the GPU leaves in the device's read-only
 * memstore; this class turns the running counters it keeps into a percentage over the time between
 * two calls. Nothing is submitted to the GPU and no setting is changed. Without /dev/kgsl-3d0 (not an
 * Adreno) {@link #busyPercent()} is simply -1.
 *
 * Everything is synchronized: the HUD calls it from its stats thread and from view hide / detach.
 */
public final class KgslGpuLoad {
    private static final String TAG = "KgslGpuLoad";
    private static final String KGSL_NODE = "/dev/kgsl-3d0";

    /** After a failed open, or stamps that never moved, the next try waits this long. */
    private static final long RETRY_MS = 30_000L;
    /** Samples (1 ms each) without any stamp moving, before the first movement, that count as "not working". */
    private static final long STALL_LIMIT_SAMPLES = 5_000L;

    private static final boolean LIB_LOADED = loadNative();
    private static Boolean eligible;

    private static long handle;
    private static long retryAtMs;
    private static String status = "not tried";

    // The counters at the previous call, and what has been seen since the session was opened.
    private static boolean havePrevious;
    private static long prevBusy, prevSamples, prevMoves;
    private static boolean sawMovement;
    private static long stalledSamples;
    private static int lastPercent = -1;

    private KgslGpuLoad() {}

    private static native long nativeOpen(int[] error);
    private static native boolean nativeIsContextMode(long handle);
    private static native boolean nativeRead(long handle, long[] out);
    private static native void nativeClose(long handle);

    private static boolean loadNative() {
        try {
            System.loadLibrary("winlator");
            return true;
        } catch (UnsatisfiedLinkError | SecurityException e) {
            Log.w(TAG, "libwinlator not loadable, no kgsl GPU load", e);
            return false;
        }
    }

    /**
     * The GPU's busy share (0-100) over the time since the previous call, or -1 when it is not known
     * (not an Adreno, node not usable, first call of a session, stamps not moving). The first call opens
     * the session; keep calling it at the metric's pace, and {@link #stop()} when the metric is hidden.
     */
    public static synchronized int busyPercent() {
        if (!isEligible()) return -1;
        long now = SystemClock.elapsedRealtime();

        if (handle == 0L) {
            if (now < retryAtMs) return -1;
            if (!openSession()) {
                retryAtMs = now + RETRY_MS;
                return -1;
            }
        }

        long[] counts = new long[3];
        boolean read;
        try {
            read = nativeRead(handle, counts);
        } catch (UnsatisfiedLinkError e) {
            read = false;
        }
        if (!read) {
            closeSession();
            retryAtMs = now + RETRY_MS;
            return -1;
        }

        if (!havePrevious) {
            remember(counts);
            havePrevious = true;
            return -1;
        }

        long newSamples = counts[1] - prevSamples;
        boolean moved = counts[2] > prevMoves;
        long newBusy = counts[0] - prevBusy;
        remember(counts);
        if (newSamples <= 0L) return lastPercent;   // the sampler was parked or has not run yet

        if (!sawMovement) {
            if (!moved) {
                stalledSamples += newSamples;
                if (stalledSamples >= STALL_LIMIT_SAMPLES) {
                    status = "timestamps did not move; retry in 30 seconds";
                    Log.i(TAG, "memstore stamps never moved, giving up for now");
                    closeSession();
                    retryAtMs = now + RETRY_MS;
                }
                return -1;
            }
            sawMovement = true;
        }

        lastPercent = (int) Math.max(0L, Math.min(100L, newBusy * 100L / newSamples));
        return lastPercent;
    }

    /** Ends the session (and its sampler thread). Safe to call at any time, also when none is open. */
    public static synchronized void stop() {
        closeSession();
    }

    /** What the last open found, for diagnostics. */
    public static synchronized String status() {
        return status;
    }

    private static boolean isEligible() {
        if (eligible == null) eligible = LIB_LOADED && new File(KGSL_NODE).exists();
        return eligible;
    }

    private static boolean openSession() {
        int[] error = new int[1];
        long opened;
        try {
            opened = nativeOpen(error);
        } catch (UnsatisfiedLinkError e) {
            status = "native library has no gpu_busy";
            return false;
        }
        if (opened == 0L) {
            status = "open failed: " + describe(error[0]);
            Log.i(TAG, "no GPU load from the kgsl memstore (" + describe(error[0]) + ")");
            return false;
        }
        handle = opened;
        havePrevious = false;
        sawMovement = false;
        stalledSamples = 0L;
        lastPercent = -1;
        status = nativeIsContextMode(opened)
                ? "per-context stamps (hardware-scheduled kernel)" : "ringbuffer stamps";
        Log.i(TAG, "kgsl memstore sampling: " + status);
        return true;
    }

    private static void closeSession() {
        if (handle != 0L) {
            try {
                nativeClose(handle);
            } catch (UnsatisfiedLinkError ignored) {
            }
            handle = 0L;
        }
        havePrevious = false;
        sawMovement = false;
        stalledSamples = 0L;
        lastPercent = -1;
    }

    private static void remember(long[] counts) {
        prevBusy = counts[0];
        prevSamples = counts[1];
        prevMoves = counts[2];
    }

    private static String describe(int negativeErrno) {
        if (negativeErrno == 0) return "no error code";
        String name = OsConstants.errnoName(-negativeErrno);
        return name != null ? name + " (" + negativeErrno + ")" : "error " + negativeErrno;
    }
}
