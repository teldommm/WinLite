package com.winlator.cmod.feature.stores.steam.winlitesteam


fun interface WinLitePrepareAppCallback {
    fun onPrepareResult(ok: Boolean, error: String)
}
