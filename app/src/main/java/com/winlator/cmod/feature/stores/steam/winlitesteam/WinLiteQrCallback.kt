package com.winlator.cmod.feature.stores.steam.winlitesteam

/**
 * Fires whenever the QR auth flow has a new challenge URL to render.
 * Steam rotates the URL every ~30 s; re-render the QR each time.
 */
fun interface WinLiteQrCallback {
    fun onQrChallengeUrl(url: String)
}
