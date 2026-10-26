package com.winlator.cmod.ssbs

/**
 * Force SSBS (keep PSTATE.SSBS set across Wine's arm64 signal returns) is offered for the Proton 9 and
 * Proton 11 arm64ec builds. Layers carrying the ntdll patch (Wine MR 12262) honour WINE_FORCE_SSBS; for
 * layers without it libssbs.so does the same from outside. Other Wine builds neither show the switch nor
 * get WINE_FORCE_SSBS / libssbs.so.
 */
object ForceSsbs {
    // Wine identifiers are "proton-<version>-<arch>"; installed contents may append "-<code>".
    private val PROTON_9_11_ARM64EC = Regex("^proton-(9|11)([.-].*)?-arm64ec(-.*)?$", RegexOption.IGNORE_CASE)

    @JvmStatic
    fun isSupportedWine(wineVersion: String?): Boolean =
        wineVersion != null && PROTON_9_11_ARM64EC.matches(wineVersion.trim())
}
