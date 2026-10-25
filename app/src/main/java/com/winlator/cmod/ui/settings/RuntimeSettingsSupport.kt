package com.winlator.cmod.ui.settings

import com.winlator.cmod.ui.uiStr
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.contents.AdrenotoolsManager
import com.winlator.cmod.contents.ContentProfile
import com.winlator.cmod.components.ComponentCatalog
import com.winlator.cmod.components.InstallOutcome
import com.winlator.cmod.core.DefaultVersion
import com.winlator.cmod.core.GPUInformation
import com.winlator.cmod.core.ProtonPackageManager
import com.winlator.cmod.core.WineInfo
import com.winlator.cmod.core.WineRuntimeGuard
import com.winlator.cmod.core.WineThemeManager
import com.winlator.cmod.ui.theme.controlAccentColor
import com.winlator.cmod.ui.theme.accentSwitchColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.winlator.cmod.ui.theme.WinZShapes
import com.winlator.cmod.ui.theme.hairlineColor
import com.winlator.cmod.ui.theme.dividerColor

internal data class VersionCatalog(val all: List<String>, val installed: Set<String>)

/** Newest version first, so every DXVK / VKD3D / FEXCore / Box64 / WOWBox64 list reads the same way. */
internal fun List<String>.newestFirst(): List<String> =
    sortedWith { left, right -> com.winlator.cmod.core.VersionOrder.compareNewestFirst(left, right) }

/**
 * What a version picker shows: the versions already on the device first, then the ones that still
 * have to be downloaded; newest first inside each group.
 */
internal fun versionCatalogOf(all: Iterable<String>, installed: Set<String>): VersionCatalog {
    val (have, missing) = all.toList().distinct().partition { version ->
        installed.any { it.equals(version, ignoreCase = true) }
    }
    // "None" (VKD3D off) is not a version: it stays the first row.
    val off = have.filter { it.equals("None", ignoreCase = true) }
    return VersionCatalog(off + (have - off.toSet()).newestFirst() + missing.newestFirst(), installed)
}
internal data class DriverOption(
    val id: String,
    val label: String,
    val installed: Boolean,
    val remoteUrl: String? = null,
    val remoteSha256: String? = null
)
internal data class WineRuntimeOption(
    val id: String,
    val label: String,
    val type: String,
    val version: String,
    val installed: Boolean
)
internal data class SettingsCatalog(
    val dxvk: VersionCatalog,
    val vkd3d: VersionCatalog,
    val fex: VersionCatalog,
    val box: VersionCatalog,
    val wow: VersionCatalog,
    val drivers: List<DriverOption>,
    val rendererDrivers: Map<String, String>
)

// Last loaded runtime list, process-wide. Screens start from it (produceState initial value) so a
// runtime label is right on the first frame instead of flashing the raw id
// ("proton-9.0-arm64ec-1") or nothing until the load — which includes a network fetch of the
// remote profiles — finishes. MainShell warms it once at startup.
@Volatile
private var cachedRuntimeOptions: List<WineRuntimeOption> = emptyList()

internal fun cachedWineRuntimeOptions(): List<WineRuntimeOption> = cachedRuntimeOptions

internal suspend fun loadWineRuntimeOptions(context: Context): List<WineRuntimeOption> =
    loadWineRuntimeOptionsUncached(context).also { cachedRuntimeOptions = it }

// The runtime picker lists exactly what the component manager lists (ComponentCatalog rows of type
// Wine / Proton, installed or not), plus the bundled main runtime, which the manager shows as its
// own card rather than as a row.
private suspend fun loadWineRuntimeOptionsUncached(context: Context): List<WineRuntimeOption> = withContext(Dispatchers.IO) {
    val catalog = ComponentCatalog(context)
    catalog.refreshQuietly(false)
    val protonType = ContentProfile.ContentType.CONTENT_TYPE_PROTON.toString()
    val wineType = ContentProfile.ContentType.CONTENT_TYPE_WINE.toString()
    val out = LinkedHashMap<String, WineRuntimeOption>()

    catalog.componentEntries().forEach { entry ->
        if (entry.type != wineType && entry.type != protonType) return@forEach
        val id = entry.runtimeId() ?: return@forEach
        // An installed row is never replaced by a not-installed twin.
        if (out[id]?.installed == true) return@forEach
        out[id] = WineRuntimeOption(
            id = id,
            label = entry.name,
            type = entry.type,
            version = entry.name,
            installed = entry.installed
        )
    }

    val mainId = WineInfo.MAIN_WINE_VERSION.identifier()
    if (out[mainId] == null) {
        val mainInstalled = WineRuntimeGuard.isBundledMainInstalled(context)
        val mainPackage = ProtonPackageManager.getPackage(mainId)
        if (mainPackage != null) {
            // Listed even when removed, so it can be downloaded again from the picker.
            out[mainId] = WineRuntimeOption(mainId, mainPackage.title, protonType, mainPackage.title, mainInstalled)
        } else if (mainInstalled) {
            out[mainId] = WineRuntimeOption(mainId, mainId, protonType, WineInfo.MAIN_WINE_VERSION.fullVersion(), true)
        }
    }

    out.values.sortedWith(
        compareByDescending<WineRuntimeOption> { it.id == WineInfo.MAIN_WINE_VERSION.identifier() }
            .thenByDescending { it.installed }
            .thenBy { it.type.lowercase() }
            .thenBy { it.label.lowercase() }
    )
}

internal suspend fun installWineRuntimeComponent(context: Context, option: WineRuntimeOption): InstallOutcome =
    withContext(Dispatchers.IO) {
        if (option.installed) InstallOutcome.ok(option.id)
        else ComponentCatalog(context).installRuntime(option.id, option.type, option.version)
    }

// Last full catalog per architecture (arm64 / x86_64 containers), process-wide.
private val cachedCatalogs = java.util.concurrent.ConcurrentHashMap<Boolean, SettingsCatalog>()

// What a settings screen shows before loadSettingsCatalog() returns. That load syncs remote
// profiles over the network and probes every graphics driver through Vulkan, so it takes a
// while; screens used to show nothing (catalog == null) meanwhile, then the DXVK/VKD3D/FEXCore/
// Box64 rows and driver labels popped in and the Compatibility / Video tabs jumped.
// Returns the last full catalog for this architecture, or a local stand-in with the same rows:
// bundled versions, the container's current selections (as installed — they are in use), and
// locally installed Adreno drivers. No network, no GPU probing.
internal fun initialSettingsCatalog(
    context: Context,
    arm64: Boolean,
    selectedDxvk: String = "",
    selectedVkd3d: String = "",
    selectedFex: String = "",
    selectedBox: String = "",
    selectedDriver: String = ""
): SettingsCatalog {
    cachedCatalogs[arm64]?.let { cached ->
        // The cache may come from another container; make sure this one's selections are listed.
        fun VersionCatalog.with(selected: String): VersionCatalog =
            if (selected.isBlank() || selected in all) this
            else versionCatalogOf(all + selected, installed + selected)
        return cached.copy(
            dxvk = cached.dxvk.with(selectedDxvk),
            vkd3d = cached.vkd3d.with(selectedVkd3d),
            fex = cached.fex.with(selectedFex),
            box = cached.box.with(selectedBox),
            wow = cached.wow.with(selectedBox)
        )
    }

    fun versions(bundled: Iterable<String>, selected: String, filterArm: Boolean = true): VersionCatalog {
        val allowed: (String) -> Boolean = { value ->
            !filterArm || arm64 || !value.contains("arm64ec", ignoreCase = true)
        }
        val installed = linkedSetOf<String>()
        bundled.filter { it.isNotBlank() && allowed(it) }.forEach(installed::add)
        if (selected.isNotBlank()) installed.add(selected)
        return versionCatalogOf(installed, installed)
    }

    val rendererDrivers = linkedMapOf("system" to "System")
    val drivers = linkedMapOf("system" to DriverOption("System", "System", true))
    runCatching {
        val adreno = AdrenotoolsManager(context)
        adreno.enumerateRendererDrivers().forEach { id ->
            val label = listOf(adreno.getDriverName(id), adreno.getDriverVersion(id))
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { id }
            rendererDrivers[id] = label
            drivers[id.lowercase()] = DriverOption(id, label, true)
        }
    }
    if (selectedDriver.isNotBlank() && drivers.values.none { it.id.equals(selectedDriver, ignoreCase = true) }) {
        drivers["selected:${selectedDriver.lowercase()}"] = DriverOption(selectedDriver, selectedDriver, true)
    }

    return SettingsCatalog(
        dxvk = versions(context.resources.getStringArray(R.array.dxvk_version_entries).toList(), selectedDxvk),
        vkd3d = versions(context.resources.getStringArray(R.array.vkd3d_version_entries).toList(), selectedVkd3d),
        fex = versions(emptyList(), selectedFex, false),
        box = versions(emptyList(), selectedBox, false),
        wow = versions(emptyList(), selectedBox, false),
        drivers = drivers.values.toList(),
        rendererDrivers = rendererDrivers
    )
}

internal suspend fun loadSettingsCatalog(
    context: Context,
    arm64: Boolean,
    selectedDxvk: String = "",
    selectedVkd3d: String = "",
    selectedFex: String = "",
    selectedBox: String = "",
    selectedDriver: String = ""
): SettingsCatalog = loadSettingsCatalogUncached(
    context, arm64, selectedDxvk, selectedVkd3d, selectedFex, selectedBox, selectedDriver
).also { cachedCatalogs[arm64] = it }

private suspend fun loadSettingsCatalogUncached(
    context: Context,
    arm64: Boolean,
    selectedDxvk: String,
    selectedVkd3d: String,
    selectedFex: String,
    selectedBox: String,
    selectedDriver: String
): SettingsCatalog = withContext(Dispatchers.IO) {
    // One refresh (catalog, driver repositories) and one snapshot of the rows;
    // the version lists and the remote drivers below are read from it.
    val catalog = ComponentCatalog(context)
    catalog.refreshQuietly(true)
    val rows = catalog.entries()

    fun versions(
        type: ContentProfile.ContentType,
        bundled: Iterable<String>,
        selected: String,
        filterArm: Boolean = true
    ): VersionCatalog {
        val allowed: (String) -> Boolean = { value ->
            !filterArm || arm64 || !value.contains("arm64ec", ignoreCase = true)
        }
        val ofType = rows.filter { it.type == type.toString() }
        val all = linkedSetOf<String>()
        bundled.filter { it.isNotBlank() && allowed(it) }.forEach(all::add)
        ofType.map { it.name }.filter(allowed).forEach(all::add)
        if (selected.isNotBlank()) all.add(selected)

        val installed = linkedSetOf<String>()
        bundled.filter { it.isNotBlank() && allowed(it) }.forEach(installed::add)
        ofType.filter { it.installed }.map { it.name }.filter(allowed).forEach(installed::add)
        return versionCatalogOf(all, installed)
    }

    val adreno = catalog.adrenotools()
    val rendererDrivers = linkedMapOf("system" to "System")
    val driverOptions = linkedMapOf<String, DriverOption>()

    context.resources.getStringArray(R.array.wrapper_graphics_driver_version_entries).forEach { version ->
        if (version.equals("System", ignoreCase = true) || GPUInformation.isDriverSupported(version, context)) {
            driverOptions[version.lowercase()] = DriverOption(version, version, true)
        }
    }
    runCatching { adreno.enumerateRendererDrivers() }.getOrNull().orEmpty().forEach { id ->
        val label = listOf(adreno.getDriverName(id), adreno.getDriverVersion(id))
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { id }
        rendererDrivers[id] = label
        driverOptions[id.lowercase()] = DriverOption(id, label, true)
    }
    rows.filter { it.isDriver() && !it.installed }.forEach { remote ->
        val url = remote.downloadUrl() ?: return@forEach
        val alreadyInstalled = driverOptions.values.any {
            it.id.equals(remote.name, ignoreCase = true) || it.label.equals(remote.name, ignoreCase = true)
        }
        if (!alreadyInstalled) {
            driverOptions["remote:${remote.name}:$url"] =
                DriverOption(remote.name, remote.name, false, url, remote.sha256())
        }
    }
    if (selectedDriver.isNotBlank() && driverOptions.values.none { it.id.equals(selectedDriver, ignoreCase = true) }) {
        driverOptions["selected:${selectedDriver.lowercase()}"] = DriverOption(selectedDriver, selectedDriver, true)
    }

    SettingsCatalog(
        dxvk = versions(
            ContentProfile.ContentType.CONTENT_TYPE_DXVK,
            context.resources.getStringArray(R.array.dxvk_version_entries).toList(),
            selectedDxvk
        ),
        vkd3d = versions(
            ContentProfile.ContentType.CONTENT_TYPE_VKD3D,
            context.resources.getStringArray(R.array.vkd3d_version_entries).toList(),
            selectedVkd3d
        ),
        fex = versions(
            ContentProfile.ContentType.CONTENT_TYPE_FEXCORE,
            DefaultVersion.FEXCORE_BUNDLED.toList(), selectedFex, false
        ),
        box = versions(
            ContentProfile.ContentType.CONTENT_TYPE_BOX64,
            listOf(DefaultVersion.BOX64), selectedBox, false
        ),
        wow = versions(
            ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64,
            listOf(DefaultVersion.WOWBOX64), selectedBox, false
        ),
        drivers = driverOptions.values.toList(),
        rendererDrivers = rendererDrivers
    )
}

internal suspend fun installRuntimeComponent(
    context: Context,
    typeName: String,
    version: String
): InstallOutcome = withContext(Dispatchers.IO) {
    ComponentCatalog(context).installContent(typeName, version)
}

internal suspend fun installAdrenoDriver(context: Context, option: DriverOption): InstallOutcome =
    withContext(Dispatchers.IO) {
        if (option.remoteUrl != null) {
            ComponentCatalog(context).installDriver(option.remoteUrl, option.remoteSha256)
        } else if (option.installed) {
            InstallOutcome.ok(option.id)
        } else {
            InstallOutcome.failed(uiStr(R.string.ui_is_not_available, option.label))
        }
    }

internal fun readConfig(config: String?, key: String, separator: Char): String {
    config.orEmpty().split(separator).forEach { token ->
        val index = token.indexOf('=')
        if (index > 0 && token.substring(0, index).trim() == key) {
            return token.substring(index + 1).trim()
        }
    }
    return ""
}

internal fun writeConfig(config: String?, key: String, value: String, separator: Char): String {
    val out = ArrayList<String>()
    var found = false
    config.orEmpty().split(separator).filter { it.isNotBlank() }.forEach { token ->
        val index = token.indexOf('=')
        if (index > 0 && token.substring(0, index).trim() == key) {
            out += "$key=$value"
            found = true
        } else {
            out += token.trim()
        }
    }
    if (!found) out += "$key=$value"
    return out.joinToString(separator.toString())
}

internal fun normalizeResolution(value: String): String =
    value.replace(Regex("\\s*\\(.*\\)$"), "").trim()

private fun settingDisplayLabel(value: String): String =
    if (value == "Lanczos 2 (16-tap)") "Lanczos 2" else value

// The screens pass these three labels in English on purpose: they also act as keys here
// (settingChoiceEntries / settingChoiceSelected / the wallpaper preview). Only what is shown is
// taken from resources; every other label arrives already localized.
private fun settingFieldLabel(label: String): String = when (label) {
    "Graphics Driver" -> uiStr(R.string.ui_opengl_driver)
    "Driver Version" -> uiStr(R.string.ui_vulkan_driver)
    "Desktop Background" -> uiStr(R.string.ui_desktop_background)
    else -> label
}

private fun settingChoiceEntries(label: String, entries: List<String>): List<String> =
    if (label == "Graphics Driver") listOf("Zink", "Freedreno") else entries
private fun settingChoiceSelected(label: String, selected: String): String =
    if (label == "Graphics Driver") {
        when {
            selected.equals("wrapper", ignoreCase = true) -> "Zink"
            selected.equals("zink", ignoreCase = true) -> "Zink"
            selected.equals("freedreno", ignoreCase = true) -> "Freedreno"
            else -> selected
        }
    } else selected

@Composable
internal fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = WinZShapes.Medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, hairlineColor())
    ) {
        Column { content() }
    }
}

@Composable
internal fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 14.dp),
        color = dividerColor()
    )
}

@Composable
private fun WallpaperPreview() {
    val context = LocalContext.current
    val file = WineThemeManager.getUserWallpaperFile(context)
    val stamp = if (file.isFile) file.lastModified() else 0L
    val bitmap = remember(stamp) {
        if (file.isFile) BitmapFactory.decodeFile(file.path) else null
    }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = WinZShapes.Medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, hairlineColor())
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = uiStr(R.string.ui_wallpaper_preview),
                modifier = Modifier.fillMaxWidth().height(132.dp).clip(WinZShapes.Medium),
                contentScale = ContentScale.Crop
            )
        } else {
            Image(
                painter = painterResource(R.drawable.wallpaper),
                contentDescription = uiStr(R.string.ui_wallpaper_preview),
                modifier = Modifier.fillMaxWidth().height(132.dp).clip(WinZShapes.Medium),
                contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
internal fun SettingChoice(
    label: String,
    selected: String,
    entries: List<String>,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val displayLabel = settingFieldLabel(label)
    val displaySelected = settingChoiceSelected(label, selected)
    val displayEntries = settingChoiceEntries(label, entries)
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth()) {
            Surface(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                color = Color.Transparent
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(displayLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            settingDisplayLabel(displaySelected),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(Icons.Outlined.KeyboardArrowDown, null)
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.widthIn(min = 220.dp, max = 420.dp).heightIn(max = 480.dp),
                shape = WinZShapes.Medium,
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                val accent = controlAccentColor()
                displayEntries.distinct().forEach { value ->
                    val isSelected = value == displaySelected
                    DropdownMenuItem(
                        text = {
                            Text(
                                settingDisplayLabel(value),
                                color = if (isSelected) accent else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        trailingIcon = { if (isSelected) Icon(Icons.Outlined.Check, null, tint = accent) },
                        onClick = {
                            expanded = false
                            onSelected(value)
                        }
                    )
                }
            }
        }
        if (label == "Desktop Background" && selected.equals("Image", ignoreCase = true)) {
            WallpaperPreview()
        }
    }
}

@Composable
internal fun SettingMappedChoice(
    label: String,
    selectedId: String,
    entries: Map<String, String>,
    onSelectedId: (String) -> Unit
) {
    val shown = entries[selectedId] ?: selectedId
    SettingChoice(label, shown, entries.values.toList()) { selectedLabel ->
        onSelectedId(entries.entries.firstOrNull { it.value == selectedLabel }?.key ?: selectedId)
    }
}

@Composable
internal fun SettingWineRuntimeChoice(
    label: String,
    selectedId: String,
    options: List<WineRuntimeOption>,
    installing: Set<String>,
    onInstall: (WineRuntimeOption) -> Unit,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedOption = options.firstOrNull { it.id == selectedId }
    Box(Modifier.fillMaxWidth()) {
        Surface(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), color = Color.Transparent) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        selectedOption?.label ?: selectedId.ifBlank { uiStr(R.string.ui_choose_a_version) },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(Icons.Outlined.KeyboardArrowDown, null)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 260.dp, max = 460.dp).heightIn(max = 500.dp),
            shape = WinZShapes.Medium,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            val accent = controlAccentColor()
            options.forEach { option ->
                val busy = "wine:${option.id}" in installing
                val isSelected = option.installed && option.id == selectedId
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                option.label,
                                color = if (isSelected) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = if (option.installed) 1f else .52f),
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                            )
                            Text(
                                if (option.installed) option.type else if (busy) uiStr(R.string.ui_downloading, option.type) else uiStr(R.string.ui_download, option.type),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    trailingIcon = {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else if (isSelected) Icon(Icons.Outlined.Check, null, tint = accent)
                    },
                    onClick = {
                        if (option.installed) {
                            expanded = false
                            onSelected(option.id)
                        } else if (!busy) {
                            onInstall(option)
                        }
                    }
                )
            }
        }
    }
}

@Composable
internal fun SettingInstallChoice(
    label: String,
    selected: String,
    catalog: VersionCatalog,
    installing: Set<String>,
    prefix: String,
    onInstall: (String) -> Unit,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Surface(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), color = Color.Transparent) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(selected.ifBlank { uiStr(R.string.ui_choose_a_version) }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                }
                Icon(Icons.Outlined.KeyboardArrowDown, null)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 240.dp, max = 440.dp).heightIn(max = 480.dp),
            shape = WinZShapes.Medium,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            val accent = controlAccentColor()
            catalog.all.forEach { value ->
                val available = catalog.installed.any { it.equals(value, ignoreCase = true) } || value == selected
                val busy = "$prefix:$value" in installing
                val isSelected = value == selected
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                value,
                                color = if (isSelected) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = if (available) 1f else .52f),
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                            )
                            if (!available) Text(if (busy) uiStr(R.string.ui_downloading_2) else uiStr(R.string.ui_download_2), style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    trailingIcon = {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else if (isSelected) Icon(Icons.Outlined.Check, null, tint = accent)
                    },
                    onClick = {
                        if (available) {
                            expanded = false
                            onSelected(value)
                        } else if (!busy) {
                            onInstall(value)
                        }
                    }
                )
            }
        }
    }
}

@Composable
internal fun SettingDriverChoice(
    label: String,
    selected: String,
    options: List<DriverOption>,
    installing: Set<String>,
    onInstall: (DriverOption) -> Unit,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedOption = options.firstOrNull { it.id.equals(selected, ignoreCase = true) }
    Box(Modifier.fillMaxWidth()) {
        Surface(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), color = Color.Transparent) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(settingFieldLabel(label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        selectedOption?.label ?: selected,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(Icons.Outlined.KeyboardArrowDown, null)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 260.dp, max = 460.dp).heightIn(max = 500.dp),
            shape = WinZShapes.Medium,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            val accent = controlAccentColor()
            options.forEach { option ->
                val busy = "driver:${option.remoteUrl ?: option.id}" in installing
                val isSelected = option.installed && option.id.equals(selected, ignoreCase = true)
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                option.label,
                                color = if (isSelected) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = if (option.installed) 1f else .52f),
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                            )
                            if (!option.installed) Text(if (busy) uiStr(R.string.ui_downloading_2) else uiStr(R.string.ui_download_2), style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    trailingIcon = {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else if (isSelected) Icon(Icons.Outlined.Check, null, tint = accent)
                    },
                    onClick = {
                        if (option.installed) {
                            expanded = false
                            onSelected(option.id)
                        } else if (!busy) {
                            onInstall(option)
                        }
                    }
                )
            }
        }
    }
}

@Composable
internal fun SettingToggle(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChanged: (Boolean) -> Unit
) {
    // Whole row toggles (see ToggleRow in SettingsComposeHost); the Switch only shows state.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChanged)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = accentSwitchColors()
        )
    }
}

@Composable
internal fun SettingText(
    label: String,
    value: String,
    minLines: Int = 1,
    onChanged: (String) -> Unit
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onChanged,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        minLines = minLines,
        maxLines = if (minLines > 1) 5 else 1,
        shape = WinZShapes.Small,
        keyboardOptions = KeyboardOptions(imeAction = if (minLines > 1) ImeAction.Default else ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
    )
}

private const val RESOLUTION_MIN = 64
private const val RESOLUTION_MAX = 7680

private fun resolutionPart(value: String, index: Int): String =
    value.lowercase().split('x', '×', '*').getOrNull(index)?.filter(Char::isDigit)?.take(4).orEmpty()

private fun isValidResolutionPart(value: String): Boolean =
    value.toIntOrNull()?.let { it in RESOLUTION_MIN..RESOLUTION_MAX } == true

/**
 * Custom resolution editor: two numeric fields with a fixed "×" between them,
 * so the separator cannot be removed. [onValid] receives "WxH" only when both
 * parts are valid; intermediate (incomplete) input stays local to the field.
 */
@Composable
internal fun ResolutionField(
    value: String,
    onValid: (String) -> Unit
) {
    val focusManager = LocalFocusManager.current
    var width by remember(value) { mutableStateOf(resolutionPart(value, 0)) }
    var height by remember(value) { mutableStateOf(resolutionPart(value, 1)) }

    fun push() {
        if (isValidResolutionPart(width) && isValidResolutionPart(height)) onValid("${width}x${height}")
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = width,
            onValueChange = { width = it.filter(Char::isDigit).take(4); push() },
            label = { Text(uiStr(R.string.ui_width)) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            isError = !isValidResolutionPart(width),
            shape = WinZShapes.Small,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)
        )
        Text(
            "×",
            modifier = Modifier.padding(horizontal = 10.dp),
            style = MaterialTheme.typography.titleMedium
        )
        OutlinedTextField(
            value = height,
            onValueChange = { height = it.filter(Char::isDigit).take(4); push() },
            label = { Text(uiStr(R.string.ui_height)) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            isError = !isValidResolutionPart(height),
            shape = WinZShapes.Small,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
        )
    }
}

@Composable
internal fun CpuSelectorRow(
    title: String,
    selected: List<Boolean>,
    onToggle: (Int, Boolean) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            items(selected.indices.toList(), key = { it }) { index ->
                val accent = controlAccentColor()
                Surface(
                    onClick = { onToggle(index, !selected[index]) },
                    shape = WinZShapes.Small,
                    color = if (selected[index]) accent else Color.Transparent,
                    contentColor = if (selected[index]) Color.White else MaterialTheme.colorScheme.onSurface,
                    border = BorderStroke(1.dp, if (selected[index]) accent else MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Text(
                        "CPU$index",
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                        color = if (selected[index]) Color.White else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (selected[index]) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}
