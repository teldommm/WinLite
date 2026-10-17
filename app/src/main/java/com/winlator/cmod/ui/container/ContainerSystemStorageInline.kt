package com.winlator.cmod.ui.container

import com.winlator.cmod.R
import com.winlator.cmod.ui.uiStr
import android.graphics.Color as AndroidColor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.core.FileUtils
import com.winlator.cmod.core.WineRegistryEditor
import com.winlator.cmod.core.WineThemeManager
import com.winlator.cmod.ui.theme.controlAccentColor
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import com.winlator.cmod.ui.theme.WinZShapes
import com.winlator.cmod.ui.theme.hairlineColor
import com.winlator.cmod.ui.theme.dividerColor

private data class InlineDrive(val letter: String, val path: String)

@Composable
internal fun ContainerSystemInline(containerId: Int, callbacks: ContainerInlineCallbacks) {
    val context = LocalContext.current
    val container = remember(containerId) { ContainerManager(context).getContainerById(containerId) } ?: return
    val themeInfo = remember(container.getDesktopTheme()) {
        runCatching { WineThemeManager.ThemeInfo(container.getDesktopTheme()) }
            .getOrElse { WineThemeManager.ThemeInfo(WineThemeManager.DEFAULT_DESKTOP_THEME) }
    }
    var name by remember { mutableStateOf(container.getName()) }
    var hudMode by remember {
        mutableStateOf(container.getExtra("hudMode").toIntOrNull() ?: if (container.isShowFPS()) 1 else 0)
    }
    var desktopTheme by remember {
        mutableStateOf(if (themeInfo.theme == WineThemeManager.Theme.LIGHT) "Light" else "Dark")
    }
    var backgroundType by remember {
        mutableStateOf(if (themeInfo.backgroundType == WineThemeManager.BackgroundType.COLOR) "Color" else "Image")
    }
    var backgroundColor by remember {
        mutableStateOf(String.format(Locale.US, "#%06X", 0xFFFFFF and themeInfo.backgroundColor))
    }
    val userReg = remember(container.getRootDir()) { File(container.getRootDir(), ".wine/user.reg") }
    var mouseWarp by remember {
        mutableStateOf(runCatching {
            WineRegistryEditor(userReg).use {
                it.getStringValue("Software\\Wine\\DirectInput", "MouseWarpOverride", "disable")
            }
        }.getOrDefault("disable").lowercase(Locale.ENGLISH))
    }
    var wallpaperLabel by remember {
        mutableStateOf(if (WineThemeManager.getUserWallpaperFile(context).isFile) uiStr(R.string.ui_custom_image) else uiStr(R.string.ui_default_image))
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) runCatching {
            val target = WineThemeManager.getUserWallpaperFile(context)
            target.parentFile?.mkdirs()
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            wallpaperLabel = uiStr(R.string.ui_custom_image)
            backgroundType = "Image"
        }
    }

    SSPanel {
        SSReadOnly(uiStr(R.string.ui_wine_2), container.getWineVersion())
        SSDivider()
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
            label = { Text(uiStr(R.string.ui_container_name)) },
            singleLine = true,
            shape = WinZShapes.Small
        )
        SSDivider()
        SSChoice("HUD", arrayOf(uiStr(R.string.ui_off), uiStr(R.string.ui_classic), uiStr(R.string.ui_modern))[hudMode.coerceIn(0, 2)], arrayOf(uiStr(R.string.ui_off), uiStr(R.string.ui_classic), uiStr(R.string.ui_modern))) {
            hudMode = arrayOf(uiStr(R.string.ui_off), uiStr(R.string.ui_classic), uiStr(R.string.ui_modern)).indexOf(it).coerceAtLeast(0)
        }
        SSDivider()
        SSChoice(uiStr(R.string.ui_theme), desktopTheme, arrayOf("Light", "Dark")) { desktopTheme = it }
        SSDivider()
        SSChoice(uiStr(R.string.ui_background), backgroundType, arrayOf("Image", "Color")) { backgroundType = it }
        if (backgroundType == "Image") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(wallpaperLabel, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { imagePicker.launch("image/*") }) { Text(uiStr(R.string.ui_choose_image)) }
            }
        } else {
            OutlinedTextField(
                value = backgroundColor,
                onValueChange = { backgroundColor = it.take(7) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
                label = { Text(uiStr(R.string.ui_background_color)) },
                singleLine = true,
                shape = WinZShapes.Small
            )
        }
        SSDivider()
        SSChoice(
            uiStr(R.string.ui_mouse_warp_override),
            mouseWarp.replaceFirstChar { it.uppercase() },
            arrayOf("Disable", "Enable", "Force")
        ) { mouseWarp = it.lowercase(Locale.ENGLISH) }
        SSSave {
            name.trim().takeIf { it.isNotEmpty() }?.let(container::setName)
            container.setShowFPS(hudMode != 0)
            container.putExtra("hudMode", hudMode.toString())
            container.setDesktopTheme(
                "${desktopTheme.uppercase(Locale.ENGLISH)},${backgroundType.uppercase(Locale.ENGLISH)},${normalizeDesktopColor(backgroundColor)}"
            )
            runCatching {
                WineRegistryEditor(userReg).use {
                    it.setStringValue("Software\\Wine\\DirectInput", "MouseWarpOverride", mouseWarp)
                }
            }
            container.saveData()
            callbacks.onSaved()
        }
    }
}

@Composable
internal fun ContainerStorageInline(containerId: Int, callbacks: ContainerInlineCallbacks) {
    val context = LocalContext.current
    val container = remember(containerId) { ContainerManager(context).getContainerById(containerId) } ?: return
    val drives = remember(container.getDrives()) {
        mutableStateListOf<InlineDrive>().apply {
            container.drivesIterator().forEach { entry ->
                if (entry.size >= 2) add(InlineDrive(entry[0].uppercase(Locale.ENGLISH) + ":", entry[1]))
            }
        }
    }
    var browseIndex by remember { mutableStateOf(-1) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && browseIndex in drives.indices) {
            val resolved = FileUtils.getFilePathFromUri(context, uri)
            val path = if (resolved.isNullOrBlank()) uri.path.orEmpty() else resolved
            if (path.isNotBlank()) drives[browseIndex] = drives[browseIndex].copy(path = path)
        }
        browseIndex = -1
    }
    val letters = remember { ('A'..'Z').filter { it != 'C' }.map { "$it:" }.toTypedArray() }

    SSPanel(indented = false) {
        Text(
            uiStr(R.string.drives),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        drives.forEachIndexed { index, drive ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Column(Modifier.width(68.dp)) {
                    Text(
                        uiStr(R.string.ui_letter),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 2.dp, bottom = 3.dp)
                    )
                    SSCompactChoice(drive.letter, letters) { drives[index] = drive.copy(letter = it) }
                }
                OutlinedTextField(
                    value = drive.path,
                    onValueChange = { drives[index] = drive.copy(path = it) },
                    modifier = Modifier.weight(1f),
                    label = { Text(uiStr(R.string.ui_target_path)) },
                    singleLine = true,
                    shape = WinZShapes.Small
                )
                OutlinedButton(
                    onClick = { browseIndex = index; picker.launch(null) },
                    modifier = Modifier.size(42.dp),
                    contentPadding = PaddingValues(0.dp)
                ) { Icon(Icons.Outlined.Folder, uiStr(R.string.browse)) }
                OutlinedButton(
                    onClick = { if (index in drives.indices) drives.removeAt(index) },
                    modifier = Modifier.size(42.dp),
                    contentPadding = PaddingValues(0.dp)
                ) { Icon(Icons.Outlined.Delete, uiStr(R.string.ui_delete)) }
            }
        }
        OutlinedButton(
            onClick = {
                val used = drives.map { it.letter }.toSet()
                drives.add(InlineDrive(letters.firstOrNull { it !in used } ?: "E:", "/storage/emulated/0"))
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp)
        ) { Text(uiStr(R.string.ui_add_2)) }
        SSSave {
            val serialized = drives.filter { it.path.isNotBlank() }.joinToString("") {
                "${it.letter.removeSuffix(":")}:${it.path}"
            }
            container.setDrives(serialized)
            container.saveData()
            callbacks.onSaved()
        }
    }
}

@Composable
private fun SSPanel(indented: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(
            start = if (indented) 58.dp else 12.dp,
            end = 12.dp,
            bottom = 10.dp
        ),
        shape = WinZShapes.Medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .20f),
        border = BorderStroke(1.dp, hairlineColor())
    ) { Column(Modifier.padding(vertical = 4.dp), content = content) }
}

@Composable
private fun SSReadOnly(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SSChoice(label: String, selected: String, entries: Array<String>, onSelected: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(onClick = { open = true }, color = Color.Transparent) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(selected, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Outlined.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = WinZShapes.Medium,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            val accent = controlAccentColor()
            entries.forEach { value ->
                val isSelected = value.equals(selected, true)
                DropdownMenuItem(
                    text = { Text(value, color = if (isSelected) accent else MaterialTheme.colorScheme.onSurface, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal) },
                    trailingIcon = { if (isSelected) Icon(Icons.Outlined.Check, null, tint = accent) },
                    onClick = { open = false; onSelected(value) }
                )
            }
        }
    }
}

@Composable
private fun SSCompactChoice(selected: String, entries: Array<String>, onSelected: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth(),
            shape = WinZShapes.Small,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, hairlineColor())
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(selected, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                Icon(Icons.Outlined.KeyboardArrowDown, null, modifier = Modifier.size(18.dp))
            }
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = WinZShapes.Medium,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            val accent = controlAccentColor()
            entries.forEach { value ->
                val isSelected = value == selected
                DropdownMenuItem(
                    text = { Text(value, color = if (isSelected) accent else MaterialTheme.colorScheme.onSurface, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal) },
                    trailingIcon = { if (isSelected) Icon(Icons.Outlined.Check, null, tint = accent) },
                    onClick = { open = false; onSelected(value) }
                )
            }
        }
    }
}

@Composable
private fun SSSave(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
    ) { Text(uiStr(R.string.ui_save), fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun SSDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 12.dp),
        color = dividerColor()
    )
}

private fun normalizeDesktopColor(value: String): String = runCatching {
    val parsed = AndroidColor.parseColor(value)
    String.format(Locale.US, "#%06X", 0xFFFFFF and parsed)
}.getOrDefault("#0277BD")
