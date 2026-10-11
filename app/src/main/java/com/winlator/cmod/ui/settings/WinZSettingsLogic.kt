package com.winlator.cmod.ui.settings

internal enum class DxvkAsyncMode { NONE, ASYNC, GPL_ASYNC }

internal fun dxvkAsyncMode(version: String): DxvkAsyncMode = when {
    version.contains("gplasync", ignoreCase = true) -> DxvkAsyncMode.GPL_ASYNC
    version.contains("async", ignoreCase = true) -> DxvkAsyncMode.ASYNC
    else -> DxvkAsyncMode.NONE
}

internal fun envValue(raw: String, key: String): String? = raw
    .split(' ')
    .firstOrNull { it.startsWith("$key=") }
    ?.substringAfter('=')

internal fun envPut(raw: String, key: String, value: String?): String {
    val prefix = "$key="
    val items = raw.split(' ').filter(String::isNotBlank).filterNot { it.startsWith(prefix) }.toMutableList()
    if (!value.isNullOrBlank()) items.add("$key=${value.replace(" ", "")}")
    return items.joinToString(" ")
}

/** Variables never stored on a container: they are driven per shortcut (DXVK HUD toggle, Rendering Mode). */
internal val containerManagedEnvNames: Set<String> = setOf("DXVK_HUD", "TU_DEBUG")

internal fun cleanContainerEnvironment(raw: String): String =
    containerManagedEnvNames.fold(raw) { acc, name -> envPut(acc, name, null) }

internal fun isTurnipDriver(version: String): Boolean {
    val value = version.lowercase()
    return value.contains("turnip") || value.startsWith("tu-") || value.startsWith("mesa-turnip")
}
