package com.winlator.cmod.feature.stores.steam.winlitesteam


data class WinLiteAuthResult(
    val success: Boolean,
    val errorCode: Int,
    val errorMessage: String,
    val accountName: String,
    val refreshToken: String,
    val accessToken: String,
    val newGuardData: String,
    val steamId: Long,
    val hadRemoteInteraction: Boolean,
    val agreementSessionUrl: String,
)
