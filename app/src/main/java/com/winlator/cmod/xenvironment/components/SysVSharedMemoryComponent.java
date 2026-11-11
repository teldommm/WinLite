package com.winlator.cmod.xenvironment.components;

import android.util.Log;

import com.winlator.cmod.xconnector.UnixSocketConfig;
import com.winlator.cmod.xenvironment.EnvironmentComponent;

/**
 * The SysV shared memory server the guest's {@code libandroid-sysvshm.so} talks to. It runs in
 * native code (xcore crate, {@code sysv.rs}); the X server's MIT-SHM reaches the same segment
 * table directly, without going through Java.
 */
public class SysVSharedMemoryComponent extends EnvironmentComponent {
    private static final String TAG = "SysVSharedMemory";

    static {
        System.loadLibrary("winlator");
    }

    public final UnixSocketConfig socketConfig;
    private long handle;

    public SysVSharedMemoryComponent(UnixSocketConfig socketConfig) {
        this.socketConfig = socketConfig;
    }

    @Override
    public synchronized void start() {
        if (handle != 0) return;
        handle = nativeStart(socketConfig.path);
        if (handle == 0) Log.e(TAG, "Cannot start the SysV SHM server on " + socketConfig.path);
    }

    /** Also deletes the guest's segments; those the X server still has attached go with their last detach. */
    @Override
    public synchronized void stop() {
        if (handle != 0) {
            nativeStop(handle);
            handle = 0;
        }
    }

    private static native long nativeStart(String socketPath);

    private static native void nativeStop(long handle);
}
