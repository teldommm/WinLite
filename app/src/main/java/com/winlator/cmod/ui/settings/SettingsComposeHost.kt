package com.winlator.cmod.ui.settings

import com.winlator.cmod.ui.uiStr
import androidx.compose.material3.LocalTextStyle
import com.winlator.cmod.ui.theme.tabularNumbers
import com.winlator.cmod.ui.theme.ThemedDialogScrollBody
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import com.winlator.cmod.ui.theme.ThemedDialogTitle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.MainActivity
import com.winlator.cmod.R
import com.winlator.cmod.ui.LandscapeScreenHeader
import com.winlator.cmod.ui.PortraitMainHeader
import com.winlator.cmod.ui.theme.controlAccentColor
import com.winlator.cmod.ui.theme.ThemedDialog
import com.winlator.cmod.ui.theme.accentSwitchColors
import com.winlator.cmod.ui.theme.WinlatorThemePreferenceCard
import kotlin.math.roundToInt
import com.winlator.cmod.ui.theme.WinZShapes
import com.winlator.cmod.ui.theme.hairlineColor
import com.winlator.cmod.core.RemoteSources
import com.winlator.cmod.ui.theme.dividerColor

@Immutable
data class SettingChoice(val id: String, val name: String)

@Immutable
data class SettingsModel(
    val box64Presets: List<SettingChoice>,
    val selectedBox64Preset: String,
    val fexPresets: List<SettingChoice>,
    val selectedFexPreset: String,
    val soundFonts: List<SettingChoice>,
    val winlatorPath: String,
    val shortcutPath: String,
    val cursorSpeedPercent: Int, // Cursor speed (pref "cursor_speed"): touch and captured mouse
    val cursorLock: Boolean,
    val useDri3: Boolean,
    val highRefreshRate: Boolean,
    val fileProvider: Boolean,
    val openInBrowser: Boolean,
    val shareClipboard: Boolean,
    val pauseWine: Boolean,
    val autoDownloadArtwork: Boolean,
    val animatedArtwork: Boolean,
    val removeLoadingBar: Boolean,
    val wineDebug: Boolean,
    val wineDebugChannels: String,
    val winlatorLogs: Boolean,
    val box64Logs: Boolean,
    val customArtworkSources: Boolean,
    val steamGridApiKey: String,
    val steamGridUrl: String,
    val gamesDbSearchUrl: String,
    val gamesDbCdnUrl: String,
    val wineDebugOptions: List<String>
)

@Stable
interface SettingsCallbacks {
    fun onOpenComponents()
    fun onOpenContainers()
    fun onBox64PresetSelected(id: String)
    fun onFexPresetSelected(id: String)
    fun onInstallSoundFont()
    fun onRemoveSoundFont(name: String)
    fun onChooseWinlatorPath()
    fun onChooseShortcutPath()
    fun onBooleanChanged(key: String, value: Boolean)
    fun onCursorSpeedChanged(percent: Int)
    fun onArtworkSourcesSaved(steamGridApiKey: String, steamGridUrl: String, gamesDbSearchUrl: String, gamesDbCdnUrl: String)
    fun onWineDebugChannelsChanged(value: String)
    fun onReinstallImageFs()
    fun onPresetAction(kind: String, id: String, action: String)
}

// The Settings screen. Hosted directly by MainShell through SettingsRoute (SettingsRoute.kt),
// which owns the state and actions SettingsFragment used to.
@Composable
internal fun SettingsScreen(model: SettingsModel, callbacks: SettingsCallbacks) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val activity = context as? MainActivity
    var confirmReinstallImageFs by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (landscape) {
            LandscapeScreenHeader(uiStr(R.string.settings))
        } else {
            PortraitMainHeader(uiStr(R.string.settings))
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 10.dp,
                // Portrait: BottomNavigation now floats over this list (64dp + 12dp margin)
                // instead of sitting in its own row, so the list needs room to scroll its last
                // item clear of the nav. Landscape's own nav sits in the flow above, not overlapping.
                bottom = if (landscape) 30.dp else 30.dp + 88.dp
            ),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            item("appearance-title") { SectionTitle("APPEARANCE") }
            item("theme") { WinlatorThemePreferenceCard() }

            item("environment-title") { SectionTitle("ENVIRONMENTS") }
            item("containers") {
                NavigationRow(Icons.Outlined.Dns, uiStr(R.string.ui_containers), uiStr(R.string.ui_create_and_manage_windows_environments), callbacks::onOpenContainers)
            }
            item("components") {
                NavigationRow(Icons.Outlined.Apps, uiStr(R.string.ui_components), uiStr(R.string.ui_wine_proton_dxvk_vkd3d_and_runtimes), callbacks::onOpenComponents)
            }

            item("presets-title") { SectionTitle("PRESETS") }
            item("presets") {
                GroupCard {
                    PresetChoiceRow(
                        icon = Icons.Outlined.Memory,
                        title = stringResource(R.string.box64_preset),
                        choices = model.box64Presets,
                        selectedId = model.selectedBox64Preset,
                        kind = "box64",
                        onSelected = callbacks::onBox64PresetSelected,
                        onAction = callbacks::onPresetAction
                    )
                    GroupDivider()
                    PresetChoiceRow(
                        icon = Icons.Outlined.Speed,
                        title = stringResource(R.string.fexcore_preset),
                        choices = model.fexPresets,
                        selectedId = model.selectedFexPreset,
                        kind = "fexcore",
                        onSelected = callbacks::onFexPresetSelected,
                        onAction = callbacks::onPresetAction
                    )
                }
            }

            item("sound-title") { SectionTitle(stringResource(R.string.sound)) }
            item("soundfonts") {
                SoundFontCard(model.soundFonts, callbacks::onInstallSoundFont, callbacks::onRemoveSoundFont)
            }

            item("paths-title") { SectionTitle(uiStr(R.string.ui_path_settings)) }
            item("winlator-path") { NavigationRow(Icons.Outlined.Storage, uiStr(R.string.ui_winlator_path), model.winlatorPath, callbacks::onChooseWinlatorPath) }
            item("shortcut-path") { NavigationRow(Icons.Outlined.FolderOpen, uiStr(R.string.ui_shortcut_export_path), model.shortcutPath, callbacks::onChooseShortcutPath) }

            item("cover-art-title") { SectionTitle(uiStr(R.string.ui_cover_art)) }
            item("cover-art") {
                // One card, like LOGS: the key toggle (with its field while on), then the two switches.
                GroupCard {
                    ToggleRow(uiStr(R.string.ui_custom_artwork_sources), model.customArtworkSources) { callbacks.onBooleanChanged("enable_custom_api_key", it) }
                    if (model.customArtworkSources) {
                        GroupDivider()
                        ArtworkSourcesEditor(model, callbacks)
                    }
                    GroupDivider()
                    ToggleRow(uiStr(R.string.ui_auto_download_artwork_from_the_internet), model.autoDownloadArtwork) { callbacks.onBooleanChanged("auto_download_artwork", it) }
                    GroupDivider()
                    ToggleRow(uiStr(R.string.ui_animated_artwork), model.animatedArtwork) { callbacks.onBooleanChanged("animated_artwork", it) }
                }
            }

            item("xserver-title") { SectionTitle(stringResource(R.string.xserver)) }
            item("xserver") {
                GroupCard {
                    SpeedRow(uiStr(R.string.ui_cursor_speed), model.cursorSpeedPercent, callbacks::onCursorSpeedChanged)
                    GroupDivider()
                    ToggleRow(uiStr(R.string.ui_capture_external_pointer), model.cursorLock) { callbacks.onBooleanChanged("cursor_lock", it) }
                    GroupDivider()
                    ToggleRow(stringResource(R.string.use_dri3_extension), model.useDri3) { callbacks.onBooleanChanged("use_dri3", it) }
                }
            }

            item("logs-title") { SectionTitle(stringResource(R.string.logs)) }
            item("logs") {
                GroupCard {
                    ToggleRow(stringResource(R.string.enable_wine_debug), model.wineDebug) { callbacks.onBooleanChanged("enable_wine_debug", it) }
                    if (model.wineDebug) {
                        GroupDivider()
                        WineDebugChannelsRow(
                            selectedValue = model.wineDebugChannels,
                            options = model.wineDebugOptions,
                            onSave = callbacks::onWineDebugChannelsChanged
                        )
                    }
                    GroupDivider()
                    ToggleRow(uiStr(R.string.ui_enable_winlator_logs), model.winlatorLogs) { callbacks.onBooleanChanged("enable_winlator_logs", it) }
                    GroupDivider()
                    ToggleRow(stringResource(R.string.enable_box64_logs), model.box64Logs) { callbacks.onBooleanChanged("enable_box64_logs", it) }
                }
            }

            item("experimental-title") { SectionTitle(stringResource(R.string.experimental)) }
            item("experimental") {
                GroupCard {
                    ToggleRow(stringResource(R.string.enable_file_provider), model.fileProvider) { callbacks.onBooleanChanged("enable_file_provider", it) }
                    GroupDivider()
                    ToggleRow(stringResource(R.string.open_with_android_browser), model.openInBrowser) { callbacks.onBooleanChanged("open_with_android_browser", it) }
                    GroupDivider()
                    ToggleRow(stringResource(R.string.share_android_clipboard), model.shareClipboard) { callbacks.onBooleanChanged("share_android_clipboard", it) }
                    GroupDivider()
                    ToggleRow(stringResource(R.string.pause_resume_wine), model.pauseWine) { callbacks.onBooleanChanged("pause_resume_wine", it) }
                    GroupDivider()
                    ToggleRow(stringResource(R.string.high_refresh_rate), model.highRefreshRate) { callbacks.onBooleanChanged("high_refresh_rate_mode", it) }
                    GroupDivider()
                    ToggleRow(stringResource(R.string.remove_loading_bar_when_booting_games), model.removeLoadingBar) { callbacks.onBooleanChanged("remove_loading_bar_when_booting_games", it) }
                }
            }

            item("imagefs-title") { SectionTitle(stringResource(R.string.imagefs)) }
            item("imagefs") { NavigationRow(Icons.Outlined.Refresh, stringResource(R.string.reinstall_imagefs), null) { confirmReinstallImageFs = true } }

            item("about-title") { SectionTitle("ABOUT") }
            item("about") {
                NavigationRow(Icons.Outlined.Info, uiStr(R.string.ui_about), null) { activity?.showAboutDialog() }
            }
        }
    }

    if (confirmReinstallImageFs) {
        ThemedDialog(onDismissRequest = { confirmReinstallImageFs = false }) {
            ThemedDialogTitle(stringResource(R.string.reinstall_imagefs))
            ThemedDialogScrollBody {
                Text(stringResource(R.string.do_you_want_to_reinstall_imagefs), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { confirmReinstallImageFs = false },
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
                    border = BorderStroke(1.dp, hairlineColor())
                ) { Text(stringResource(R.string.cancel)) }
                Button(
                    onClick = {
                        confirmReinstallImageFs = false
                        callbacks.onReinstallImageFs()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = controlAccentColor(), contentColor = androidx.compose.ui.graphics.Color.White)
                ) { Text(stringResource(R.string.ok)) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        modifier = Modifier.padding(start = 3.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun GroupCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = WinZShapes.Medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, hairlineColor())
    ) { Column { content() } }
}

@Composable
private fun GroupDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = dividerColor())
}

@Composable
private fun NavigationRow(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = WinZShapes.Medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, hairlineColor())
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallIcon(icon)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SmallIcon(icon: ImageVector) {
    Surface(Modifier.size(38.dp), shape = WinZShapes.Small, color = MaterialTheme.colorScheme.surfaceVariant) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, modifier = Modifier.size(21.dp)) }
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, subtitle: String? = null, onChecked: (Boolean) -> Unit) {
    // The whole row is the toggle (it used to react only on the switch itself); the Switch is
    // display-only so the row is one accessible "switch" and a tap isn't handled twice.
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChecked)
            .padding(start = 15.dp, end = 11.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = accentSwitchColors()
        )
    }
}

@Composable
private fun SpeedRow(title: String, value: Int, onChanged: (Int) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value.coerceIn(10, 200).toFloat()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f))
            Text("${draft.roundToInt()}%", color = MaterialTheme.colorScheme.onSurfaceVariant, style = LocalTextStyle.current.tabularNumbers())
        }
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { onChanged(draft.roundToInt()) },
            valueRange = 10f..200f,
            colors = SliderDefaults.colors(thumbColor = controlAccentColor(), activeTrackColor = controlAccentColor())
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetChoiceRow(
    icon: ImageVector,
    title: String,
    choices: List<SettingChoice>,
    selectedId: String,
    kind: String,
    onSelected: (String) -> Unit,
    onAction: (String, String, String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var actionsOpen by remember { mutableStateOf(false) }
    val selected = choices.firstOrNull { it.id == selectedId } ?: choices.firstOrNull()

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            onClick = { expanded = true },
            modifier = Modifier.weight(1f),
            color = androidx.compose.ui.graphics.Color.Transparent
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, top = 11.dp, bottom = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmallIcon(icon)
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Medium)
                    Text(selected?.name.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Outlined.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box {
            IconButton(onClick = { actionsOpen = true }) {
                Icon(Icons.Outlined.MoreVert, uiStr(R.string.ui_preset_actions))
            }
            DropdownMenu(
                expanded = actionsOpen,
                onDismissRequest = { actionsOpen = false },
                shape = WinZShapes.Medium,
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                DropdownMenuItem(
                    text = { Text(uiStr(R.string.ui_create_new)) },
                    leadingIcon = { Icon(Icons.Outlined.Add, null) },
                    onClick = {
                        actionsOpen = false
                        onAction(kind, "", "add")
                    }
                )
                if (selectedId.isNotBlank()) {
                    DropdownMenuItem(
                        text = { Text(uiStr(R.string.ui_clone)) },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                        onClick = {
                            actionsOpen = false
                            onAction(kind, selectedId, "duplicate")
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(uiStr(R.string.ui_edit)) },
                        leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                        onClick = {
                            actionsOpen = false
                            onAction(kind, selectedId, "edit")
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(uiStr(R.string.ui_delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            actionsOpen = false
                            onAction(kind, selectedId, "remove")
                        }
                    )
                }
            }
        }
    }

    if (expanded) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { expanded = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 20.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                )
                Text(
                    uiStr(R.string.ui_choose_an_option),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp).padding(bottom = 10.dp)
                )
                HorizontalDivider(color = dividerColor())
                Spacer(Modifier.size(12.dp))
                val accent = controlAccentColor()
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                    items(choices, key = { it.id }) { choice ->
                        val isSelected = choice.id == selectedId
                        Surface(
                            onClick = {
                                onSelected(choice.id)
                                expanded = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = WinZShapes.Small,
                            color = if (isSelected) accent.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    choice.name,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (isSelected) accent else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                )
                                if (isSelected) Icon(Icons.Outlined.Check, null, tint = accent)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WineDebugChannelsRow(
    selectedValue: String,
    options: List<String>,
    onSave: (String) -> Unit
) {
    var dialogOpen by remember { mutableStateOf(false) }
    val selectedChannels = remember(selectedValue) {
        selectedValue.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
    val summary = when {
        selectedChannels.isEmpty() -> uiStr(R.string.ui_no_channels_selected)
        selectedChannels.size <= 3 -> selectedChannels.joinToString(", ")
        else -> selectedChannels.take(3).joinToString(", ") + uiStr(R.string.ui, selectedChannels.size - 3)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { dialogOpen = true }
            .padding(horizontal = 15.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(uiStr(R.string.ui_wine_debug_channels), style = MaterialTheme.typography.bodyLarge)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (dialogOpen) {
        WineDebugChannelsDialog(
            selectedValue = selectedValue,
            options = options,
            onDismiss = { dialogOpen = false },
            onApply = {
                dialogOpen = false
                onSave(it)
            }
        )
    }
}

@Composable
private fun WineDebugChannelsDialog(
    selectedValue: String,
    options: List<String>,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit
) {
    val selectedAtOpen = remember(selectedValue) {
        selectedValue.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
    val allOptions = remember(options, selectedValue) { (selectedAtOpen + options).distinct() }
    var selected by remember(selectedValue) { mutableStateOf(selectedAtOpen.toSet()) }
    var query by remember { mutableStateOf("") }
    val filtered = remember(allOptions, query) {
        if (query.isBlank()) allOptions else allOptions.filter { it.contains(query.trim(), ignoreCase = true) }
    }

    ThemedDialog(onDismissRequest = onDismiss) {
        ThemedDialogTitle(uiStr(R.string.ui_wine_debug_channels))
        val accent = controlAccentColor()
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(uiStr(R.string.ui_search_channels)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 420.dp)) {
                items(filtered, key = { it }) { channel ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = selected.toMutableSet().apply {
                                    if (!add(channel)) remove(channel)
                                }
                            }
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = channel in selected,
                            onCheckedChange = { checked ->
                                selected = selected.toMutableSet().apply {
                                    if (checked) add(channel) else remove(channel)
                                }
                            },
                            colors = CheckboxDefaults.colors(checkedColor = accent)
                        )
                        Text(channel, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onDismiss,
                colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
                border = BorderStroke(1.dp, hairlineColor())
            ) { Text(uiStr(R.string.cancel)) }
            Button(
                onClick = { onApply(allOptions.filter { it in selected }.joinToString(",")) },
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = androidx.compose.ui.graphics.Color.White)
            ) { Text(uiStr(R.string.ui_apply)) }
        }
    }
}

@Composable
private fun SoundFontCard(choices: List<SettingChoice>, onInstall: () -> Unit, onRemove: (String) -> Unit) {
    GroupCard {
        choices.forEachIndexed { index, choice ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.MusicNote, null)
                Spacer(Modifier.width(10.dp))
                Text(choice.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { onRemove(choice.name) }) { Icon(Icons.Outlined.DeleteOutline, uiStr(R.string.ui_remove, choice.name)) }
            }
            if (index != choices.lastIndex) GroupDivider()
        }
        Button(
            onClick = onInstall,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = controlAccentColor(), contentColor = androidx.compose.ui.graphics.Color.White)
        ) {
            Icon(Icons.Outlined.Add, null)
            Spacer(Modifier.width(7.dp))
            Text(uiStr(R.string.ui_install_soundfont))
        }
    }
}

// The key and the four addresses artwork is fetched with, all visible at once and showing the
// default until changed. There is no Save: a value is stored when its field loses focus (Next, Done,
// a tap elsewhere) and when the screen is left. Restoring the defaults is turning the switch off.
@Composable
private fun ArtworkSourcesEditor(model: SettingsModel, callbacks: SettingsCallbacks) {
    var apiKey by remember(model.steamGridApiKey) { mutableStateOf(model.steamGridApiKey) }
    var steamGridUrl by remember(model.steamGridUrl) { mutableStateOf(model.steamGridUrl) }
    var gamesSearch by remember(model.gamesDbSearchUrl) { mutableStateOf(model.gamesDbSearchUrl) }
    var gamesCdn by remember(model.gamesDbCdnUrl) { mutableStateOf(model.gamesDbCdnUrl) }
    val searchInvalid = !gamesSearch.contains(RemoteSources.QUERY_PLACEHOLDER)

    fun commit() {
        // An emptied field means "the default": show it as such, and store nothing for it.
        if (apiKey.isBlank()) apiKey = RemoteSources.DEFAULT_STEAMGRID_API_KEY
        if (steamGridUrl.isBlank()) steamGridUrl = RemoteSources.DEFAULT_STEAMGRID
        if (gamesSearch.isBlank()) gamesSearch = RemoteSources.DEFAULT_GAMESDB_SEARCH
        if (gamesCdn.isBlank()) gamesCdn = RemoteSources.DEFAULT_GAMESDB_CDN
        // A search address without the placeholder is not stored; the field keeps showing the error.
        val search = if (gamesSearch.contains(RemoteSources.QUERY_PLACEHOLDER)) gamesSearch else model.gamesDbSearchUrl
        if (apiKey.trim() == model.steamGridApiKey && steamGridUrl.trim() == model.steamGridUrl &&
            search.trim() == model.gamesDbSearchUrl && gamesCdn.trim() == model.gamesDbCdnUrl) return
        callbacks.onArtworkSourcesSaved(apiKey, steamGridUrl, search, gamesCdn)
    }
    val commitLatest = rememberUpdatedState { commit() }
    // Leaving the screen with a field still focused (back, another tab) must not drop the edit.
    DisposableEffect(Unit) { onDispose { commitLatest.value() } }

    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ArtworkField(uiStr(R.string.ui_steamgriddb_api_key), apiKey, { apiKey = it }, ImeAction.Next, ::commit)
        ArtworkField(uiStr(R.string.ui_steamgriddb_api_url), steamGridUrl, { steamGridUrl = it }, ImeAction.Next, ::commit)
        ArtworkField(
            uiStr(R.string.ui_thegamesdb_search_url), gamesSearch, { gamesSearch = it }, ImeAction.Next, ::commit,
            error = if (searchInvalid) uiStr(R.string.ui_must_contain, RemoteSources.QUERY_PLACEHOLDER) else null
        )
        ArtworkField(uiStr(R.string.ui_thegamesdb_images_url), gamesCdn, { gamesCdn = it }, ImeAction.Done, ::commit)
        Text(
            uiStr(R.string.ui_turn_the_switch_off_to_go_back_to_the_def),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// One address / key field: [onCommit] runs when it loses focus after having had it (so not on the
// first composition). Done clears the focus, which is what commits.
@Composable
private fun ArtworkField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    imeAction: ImeAction,
    onCommit: () -> Unit,
    error: String? = null
) {
    var hadFocus by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        isError = error != null,
        label = { Text(label) },
        supportingText = if (error != null) ({ Text(error) }) else null,
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { state ->
                if (state.isFocused) {
                    hadFocus = true
                } else if (hadFocus) {
                    hadFocus = false
                    onCommit()
                }
            }
    )
}
