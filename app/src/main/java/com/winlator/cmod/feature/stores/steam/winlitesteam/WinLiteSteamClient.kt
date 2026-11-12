// JNI symbols depend on this package path and class name.
package com.winlator.cmod.feature.stores.steam.winlitesteam

import timber.log.Timber

// JVM-side entry point to the native winlite-steam-client library.
object WinLiteSteamClient {

    @Volatile
    private var loaded: Boolean = false

    // Load libwinlitesteam.so once; failed loads can be retried.
    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            System.loadLibrary("winlitesteam")
            loaded = true
            Timber.tag(TAG).i("libwinlitesteam.so loaded, native version = %s", nativeVersion())
        }
    }

    // Native library semver string.
    fun version(): String {
        ensureLoaded()
        return nativeVersion()
    }

    @JvmStatic
    private external fun nativeVersion(): String

    private const val TAG = "WinLiteSteamClient"
}
