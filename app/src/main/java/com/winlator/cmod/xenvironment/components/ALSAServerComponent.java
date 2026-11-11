package com.winlator.cmod.xenvironment.components;

import android.util.Log;

import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.xconnector.UnixSocketConfig;
import com.winlator.cmod.xenvironment.EnvironmentComponent;

/**
 * The ALSA server the guest's {@code android_aserver} PCM plugin talks to. It runs in native code
 * (xcore crate, {@code alsa.rs}): one thread per guest stream, output through AAudio.
 */
public class ALSAServerComponent extends EnvironmentComponent {
    private static final String TAG = "ALSAServerComponent";

    static {
        System.loadLibrary("winlator");
    }

    /** Tunables, read once per launch from the container/shortcut environment. */
    public static class Options {
        // AAUDIO_PERFORMANCE_MODE_* values, passed straight to the native stream builder.
        public static final int PERFORMANCE_MODE_NONE = 10;
        public static final int PERFORMANCE_MODE_POWER_SAVING = 11;
        public static final int PERFORMANCE_MODE_LOW_LATENCY = 12;

        // 0 = no floor: the device buffer starts at the guest's ALSA buffer size.
        public int latencyMillis = 0;
        public int performanceMode = PERFORMANCE_MODE_LOW_LATENCY;

        public static Options fromEnvVars(EnvVars envVars) {
            Options options = new Options();
            if (envVars == null) return options;

            String latency = envVars.get("ALSA_LATENCY_MS").trim();
            try {
                if (!latency.isEmpty()) options.latencyMillis = Math.max(0, Integer.parseInt(latency));
            }
            catch (NumberFormatException ignored) {}

            String mode = envVars.get("ALSA_PERFORMANCE_MODE").trim();
            if (mode.equalsIgnoreCase("none")) options.performanceMode = PERFORMANCE_MODE_NONE;
            else if (mode.equalsIgnoreCase("power_saving")) options.performanceMode = PERFORMANCE_MODE_POWER_SAVING;
            return options;
        }
    }

    private final UnixSocketConfig socketConfig;
    private final Options options;
    private long handle;

    public ALSAServerComponent(UnixSocketConfig socketConfig, Options options) {
        this.socketConfig = socketConfig;
        this.options = options != null ? options : new Options();
    }

    @Override
    public synchronized void start() {
        if (handle != 0) return;
        handle = nativeStart(socketConfig.path, options.latencyMillis, options.performanceMode);
        if (handle == 0) Log.e(TAG, "Cannot start the ALSA server on " + socketConfig.path);
    }

    @Override
    public synchronized void stop() {
        if (handle != 0) {
            nativeStop(handle);
            handle = 0;
        }
    }

    /**
     * Pauses (or resumes) the device output of every guest stream while the app is in the
     * background. Blocks for at most one device write per stream: never call it on the UI thread.
     */
    public static void setOutputSuspended(boolean suspend) {
        nativeSetOutputSuspended(suspend);
    }

    private static native long nativeStart(String socketPath, int latencyMillis, int performanceMode);

    private static native void nativeStop(long handle);

    private static native void nativeSetOutputSuspended(boolean suspended);
}
