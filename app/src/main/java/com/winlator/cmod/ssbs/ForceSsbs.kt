package com.winlator.cmod.ssbs

/**
 * Force SSBS (keep PSTATE.SSBS set across Wine's arm64 signal returns) exists only in the Proton 11
 * arm64ec build, which carries the ntdll patch. Other Wine builds (Proton 9 included, unlike DirectAudio)
 * neither show the switch nor get WINE_FORCE_SSBS / libssbs.so.
 */
object ForceSsbs {
    // Wine identifiers are "proton-<version>-<arch>"; installed contents may append "-<code>".
    private val PROTON_11_ARM64EC = Regex("^proton-11([.-].*)?-arm64ec(-.*)?$", RegexOption.IGNORE_CASE)

    @JvmStatic
    fun isSupportedWine(wineVersion: String?): Boolean =
        wineVersion != null && PROTON_11_ARM64EC.matches(wineVersion.trim())
}
