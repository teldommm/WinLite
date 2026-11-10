package com.winlator.cmod.core;

import android.util.Log;

import java.io.File;

/**
 * Adreno turbo: keeps the GPU at its top clock by switching the KGSL driver's power scaling off
 * (see gpu_turbo.c). Takes effect immediately and can be switched at any time, no game restart.
 *
 * It is a kernel-wide property that outlives the process, so callers must {@link #revert()} when the
 * game is left and {@link #clearStale()} at the start of a session. The kernel's thermal limits still
 * apply. Adreno only: without /dev/kgsl-3d0 {@link #isSupported()} is false and everything is a no-op.
 */
public final class GpuTurbo {
    private static final String TAG = "GpuTurbo";
    private static final String KGSL_NODE = "/dev/kgsl-3d0";

    private static final boolean LIB_LOADED = loadNative();
    private static Boolean supported;
    private static boolean applied;

    private GpuTurbo() {}

    private static boolean loadNative() {
        try {
            System.loadLibrary("winlator");
            return true;
        } catch (UnsatisfiedLinkError | SecurityException e) {
            Log.w(TAG, "libwinlator not loadable, GPU turbo unavailable", e);
            return false;
        }
    }

    public static synchronized boolean isSupported() {
        if (supported == null) supported = LIB_LOADED && new File(KGSL_NODE).exists();
        return supported;
    }

    /** Turns turbo on or off. Only what was asked for is tracked: the driver reports no result. */
    public static synchronized void apply(boolean on) {
        if (!isSupported() || applied == on) return;
        try {
            nativeSetTurbo(on);
            applied = on;
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "set turbo " + on + " failed", e);
        }
    }

    /** Gives the power scaling back if turbo was turned on. */
    public static synchronized void revert() {
        if (applied) apply(false);
    }

    /**
     * Clears turbo whatever this process believes: a killed or crashed session cannot have undone its
     * own, and the property would otherwise stay on.
     */
    public static synchronized void clearStale() {
        if (!isSupported()) return;
        try {
            nativeSetTurbo(false);
            applied = false;
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "clearing turbo failed", e);
        }
    }

    private static native void nativeSetTurbo(boolean on);
}
