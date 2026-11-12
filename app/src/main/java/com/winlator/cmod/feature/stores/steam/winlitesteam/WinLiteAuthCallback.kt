package com.winlator.cmod.feature.stores.steam.winlitesteam


fun interface WinLiteAuthCallback {
    fun onAuthResult(result: WinLiteAuthResult)
}
