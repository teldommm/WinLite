package com.winlator.cmod.core;

/**
 * Per-thread CPU affinity by TID (see thread_affinity.c). Masks are 64-bit words, bit N = CPU N.
 * Works on any thread of the app's own uid, which covers the Wine processes it launches.
 */
public final class ThreadAffinity {
    private static final boolean AVAILABLE = loadNative();

    private ThreadAffinity() {}

    private static boolean loadNative() {
        try {
            System.loadLibrary("winlator");
            return true;
        } catch (UnsatisfiedLinkError | SecurityException e) {
            return false;
        }
    }

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /** Sets the thread's affinity; returns 0 on success or -errno. */
    public static int setAffinity(int tid, long mask) {
        return AVAILABLE ? nativeSetAffinity(tid, mask) : -38; // -ENOSYS
    }

    /** The thread's current affinity mask, or -1 if it cannot be read. */
    public static long getAffinity(int tid) {
        return AVAILABLE ? nativeGetAffinity(tid) : -1L;
    }

    private static native int nativeSetAffinity(int tid, long mask);

    private static native long nativeGetAffinity(int tid);
}
