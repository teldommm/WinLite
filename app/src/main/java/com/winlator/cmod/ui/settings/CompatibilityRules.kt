package com.winlator.cmod.ui.settings

internal fun isVkd3dEnabled(version: String): Boolean =
    version.isNotBlank() && !version.equals("None", ignoreCase = true)

internal fun isDxvkCompatibleWithVkd3d(version: String): Boolean {
    val match = Regex("(\\d+)\\.(\\d+)").find(version) ?: return false
    return (match.groupValues.getOrNull(1)?.toIntOrNull() ?: 0) >= 2
}

/**
 * The DXVK to switch to when the chosen VKD3D needs DXVK 2.x: the newest installed compatible one.
 * Sarek builds (a separate fork for old GPUs) are only used when nothing else fits.
 */
internal fun pickCompatibleInstalledDxvk(catalog: VersionCatalog): String? {
    val candidates = catalog.all.filter { isDxvkCompatibleWithVkd3d(it) && it in catalog.installed }
    return candidates.firstOrNull { !it.contains("sarek", ignoreCase = true) } ?: candidates.firstOrNull()
}

internal fun filterDxvkForVkd3d(catalog: VersionCatalog, vkd3dVersion: String): VersionCatalog {
    if (!isVkd3dEnabled(vkd3dVersion)) return catalog
    val allowed = catalog.all.filter(::isDxvkCompatibleWithVkd3d)
    val installed = catalog.installed.filterTo(linkedSetOf(), ::isDxvkCompatibleWithVkd3d)
    return VersionCatalog(allowed, installed) // keeps the order of catalog.all
}

internal fun normalizeLocaleValue(value: String): String {
    val clean = value.trim()
    if (clean.isBlank() || clean.equals("Default", ignoreCase = true)) return ""
    return if (clean.contains('.')) clean else "$clean.UTF-8"
}

internal fun localeDisplayValue(value: String): String =
    value.trim().removeSuffix(".UTF-8").removeSuffix(".utf8").ifBlank { "Default" }
