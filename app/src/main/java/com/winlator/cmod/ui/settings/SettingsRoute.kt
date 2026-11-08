package com.winlator.cmod.ui.settings

import com.winlator.cmod.ui.uiStr
import android.net.Uri
import android.widget.Toast
import com.winlator.cmod.ui.toast.WinToast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.preference.PreferenceManager
import com.winlator.cmod.MainActivity
import com.winlator.cmod.R
import com.winlator.cmod.box64.Box64EditPresetDialog
import com.winlator.cmod.box64.Box64Preset
import com.winlator.cmod.box64.Box64PresetManager
import com.winlator.cmod.core.AppDefaults
import com.winlator.cmod.core.FileUtils
import com.winlator.cmod.core.PreloaderDialog
import com.winlator.cmod.core.RemoteSources
import com.winlator.cmod.fexcore.FEXCoreEditPresetDialog
import com.winlator.cmod.fexcore.FEXCorePreset
import com.winlator.cmod.fexcore.FEXCorePresetManager
import com.winlator.cmod.steamgrid.ArtworkRepository
import com.winlator.cmod.midi.MidiManager
import com.winlator.cmod.ui.ThemedAlertHost
import com.winlator.cmod.ui.filemanager.FilePickerDialog
import com.winlator.cmod.ui.theme.findActivity
import com.winlator.cmod.xenvironment.ImageFsInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import java.io.File
import java.io.FileNotFoundException
import kotlin.math.roundToInt

// Settings as a plain composable tab of MainShell (replaces SettingsFragment). Behaviour is the
// same; the differences are structural:
//  - File/folder pickers use the app's own picker (FilePickerDialog, plain java.io.File browsing)
//    instead of the system Storage Access Framework; a picked folder is stored as a file:// URI
//    under the same preference keys, which FileUtils.getFilePathFromUri resolves.
//  - The model is built off the main thread when the screen (re)appears; user-initiated changes
//    still rebuild synchronously so switches/sliders respond in the same frame.
//  - Refresh on resume matches the fragment's onResume (back from Components/Containers
//    activities, from a game, …); MainShell's re-show also refreshes.
//  - The static constants/helpers that other code used from SettingsFragment moved to
//    core/AppDefaults.java.

@Composable
fun SettingsRoute(shownSerial: Int) {
    val activity = LocalContext.current.findActivity() as AppCompatActivity
    val scope = rememberCoroutineScope()
    val state = remember { SettingsScreenState(activity, scope) }

    LifecycleResumeEffect(state) {
        state.reload()
        onPauseOrDispose { }
    }
    LaunchedEffect(shownSerial) {
        if (shownSerial > 0) state.reload()
    }

    val model = state.model
    if (model != null) {
        SettingsScreen(model, state)
    } else {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
    }
}

internal class SettingsScreenState(
    private val activity: AppCompatActivity,
    private val scope: CoroutineScope
) : SettingsCallbacks {

    private val preferences = PreferenceManager.getDefaultSharedPreferences(activity)
    private var reloadJob: Job? = null

    var model by mutableStateOf<SettingsModel?>(null)
        private set

    // ---------- Model ----------

    fun reload() {
        reloadJob?.cancel()
        reloadJob = scope.launch {
            model = withContext(Dispatchers.IO) { buildModel() }
        }
    }

    private fun rebuild() {
        reloadJob?.cancel()
        model = buildModel()
    }

    private fun buildModel(): SettingsModel {
        val box64Choices = Box64PresetManager.getPresets("box64", activity).map { SettingChoice(it.id, it.name) }
        val fexChoices = FEXCorePresetManager.getPresets(activity).map { SettingChoice(it.id, it.name) }

        val soundFontChoices = ArrayList<SettingChoice>()
        soundFontChoices.add(SettingChoice(MidiManager.DEFAULT_SF2_FILE, MidiManager.DEFAULT_SF2_FILE))
        MidiManager.getSoundFontDir(activity).listFiles()
            ?.filter { it.isFile }
            ?.sortedWith { left, right -> left.name.compareTo(right.name, ignoreCase = true) }
            ?.forEach { soundFontChoices.add(SettingChoice(it.name, it.name)) }

        val wineDebugOptions = ArrayList<String>()
        try {
            val channels = JSONArray(FileUtils.readString(activity, "wine_debug_channels.json"))
            for (i in 0 until channels.length()) {
                val channel = channels.optString(i, "").trim()
                if (channel.isNotEmpty()) wineDebugOptions.add(channel)
            }
        } catch (ignored: JSONException) {
        }
        if (wineDebugOptions.isEmpty()) {
            wineDebugOptions.addAll(AppDefaults.DEFAULT_WINE_DEBUG_CHANNELS.split(","))
        }

        return SettingsModel(
            box64Choices,
            preferences.getString("box64_preset", Box64Preset.COMPATIBILITY) ?: Box64Preset.COMPATIBILITY,
            fexChoices,
            preferences.getString("fexcore_preset", FEXCorePreset.COMPATIBILITY) ?: FEXCorePreset.COMPATIBILITY,
            soundFontChoices,
            resolveStoredPath("winlator_path_uri", AppDefaults.DEFAULT_WINLATOR_PATH),
            resolveStoredPath("shortcuts_export_path_uri", AppDefaults.DEFAULT_SHORTCUT_EXPORT_PATH),
            (preferences.getFloat("cursor_speed", 1.0f) * 100.0f).roundToInt(),
            preferences.getBoolean("cursor_lock", true),
            preferences.getBoolean("use_dri3", true),
            preferences.getBoolean("high_refresh_rate_mode", false),
            preferences.getBoolean("enable_file_provider", true),
            preferences.getBoolean("open_with_android_browser", false),
            preferences.getBoolean("share_android_clipboard", false),
            preferences.getBoolean("pause_resume_wine", true),
            preferences.getBoolean("auto_download_artwork", true),
            preferences.getBoolean("animated_artwork", true),
            preferences.getBoolean("remove_loading_bar_when_booting_games", false),
            preferences.getBoolean("enable_wine_debug", false),
            preferences.getString("wine_debug_channels", AppDefaults.DEFAULT_WINE_DEBUG_CHANNELS)
                ?: AppDefaults.DEFAULT_WINE_DEBUG_CHANNELS,
            preferences.getBoolean("enable_winlator_logs", false),
            preferences.getBoolean("enable_box64_logs", false),
            // On when the person turned it on, or when an override is already stored (an address set
            // before these settings moved here must not sit hidden behind an "off" switch).
            preferences.getBoolean("enable_custom_api_key", false) || RemoteSources.hasArtworkOverrides(activity),
            RemoteSources.steamGridApiKey(activity),
            RemoteSources.get(activity, RemoteSources.KEY_STEAMGRID, RemoteSources.DEFAULT_STEAMGRID),
            RemoteSources.get(activity, RemoteSources.KEY_GAMESDB_SEARCH, RemoteSources.DEFAULT_GAMESDB_SEARCH),
            RemoteSources.get(activity, RemoteSources.KEY_GAMESDB_CDN, RemoteSources.DEFAULT_GAMESDB_CDN),
            wineDebugOptions
        )
    }

    private fun resolveStoredPath(key: String, fallback: String): String {
        val stored = preferences.getString(key, null) ?: return fallback
        return FileUtils.getFilePathFromUri(activity, Uri.parse(stored)) ?: stored
    }

    // ---------- Pickers ----------

    // The picked file arrives as a file:// Uri, which the existing readers (ContentResolver streams,
    // FileUtils.getUriFileName) handle like any other.
    private fun pickDocument(title: String, extensions: List<String>, action: (Uri) -> Unit) {
        FilePickerDialog.show(activity, title, extensions, null) { file -> action(Uri.fromFile(file)) }
    }

    private fun pickTreeFor(preferenceKey: String) {
        val label = if (preferenceKey == "shortcuts_export_path_uri") uiStr(R.string.ui_shortcut_export_folder) else uiStr(R.string.ui_winlator_folder)
        val fallback = if (preferenceKey == "shortcuts_export_path_uri") AppDefaults.DEFAULT_SHORTCUT_EXPORT_PATH else AppDefaults.DEFAULT_WINLATOR_PATH
        // Open on the current folder, or the nearest existing parent when it was not created yet.
        var start: File? = File(resolveStoredPath(preferenceKey, fallback))
        while (start != null && !start.isDirectory) start = start.parentFile
        FilePickerDialog.showFolder(activity, label, start) { dir -> onTreePicked(preferenceKey, dir) }
    }

    private fun onTreePicked(key: String, dir: File) {
        // Stored as a file:// URI under the same key the SAF tree URI used; getFilePathFromUri turns
        // it back into the path. No persistable URI permission is involved any more.
        preferences.edit().putString(key, Uri.fromFile(dir).toString()).apply()
        rebuild()
        val label = if (key == "shortcuts_export_path_uri") uiStr(R.string.ui_shortcut_export_folder) else uiStr(R.string.ui_winlator_folder)
        val path = resolveStoredPath(key, "")
        WinToast.show(activity, if (path.isEmpty()) uiStr(R.string.ui_updated, label) else uiStr(R.string.ui_2, label, path), Toast.LENGTH_SHORT)
    }

    // ---------- Detail screens (MainShell detail stack) ----------

    override fun onOpenComponents() {
        (activity as? MainActivity)?.openComponentManager()
    }

    override fun onOpenContainers() {
        (activity as? MainActivity)?.openContainersSettings()
    }

    // ---------- SettingsCallbacks ----------

    override fun onBox64PresetSelected(id: String) {
        preferences.edit().putString("box64_preset", id).apply()
        rebuild()
    }

    override fun onFexPresetSelected(id: String) {
        preferences.edit().putString("fexcore_preset", id).apply()
        rebuild()
    }

    override fun onInstallSoundFont() {
        pickDocument(uiStr(R.string.ui_select_soundfont), listOf("sf2")) { uri ->
            try {
                val dialog = PreloaderDialog(activity)
                dialog.showOnUiThread(R.string.installing_content)
                MidiManager.installSF2File(activity, uri, object : MidiManager.OnSoundFontInstalledCallback {
                    override fun onSuccess() {
                        dialog.closeOnUiThread()
                        activity.runOnUiThread {
                            WinToast.show(activity, uiStr(R.string.ui_soundfont_installed), Toast.LENGTH_SHORT)
                            rebuild()
                        }
                    }

                    override fun onFailed(reason: Int) {
                        dialog.closeOnUiThread()
                        val message = when (reason) {
                            MidiManager.ERROR_BADFORMAT -> uiStr(R.string.ui_bad_soundfont_format)
                            MidiManager.ERROR_EXIST -> uiStr(R.string.ui_soundfont_you_want_to_install_already_exi)
                            else -> uiStr(R.string.ui_soundfont_installation_failed)
                        }
                        activity.runOnUiThread {
                            ThemedAlertHost.info(activity, uiStr(R.string.ui_soundfont_install_failed), message)
                        }
                    }
                })
            } catch (e: Exception) {
                WinToast.show(activity, uiStr(R.string.ui_unable_to_install_soundfont), Toast.LENGTH_SHORT)
            }
        }
    }

    override fun onRemoveSoundFont(name: String) {
        if (MidiManager.DEFAULT_SF2_FILE == name) {
            WinToast.show(activity, R.string.cannot_remove_default_sound_font, Toast.LENGTH_SHORT)
            return
        }
        ThemedAlertHost.confirm(
            activity,
            uiStr(R.string.ui_remove_soundfont),
            uiStr(R.string.ui_do_you_want_to_remove_this_soundfont),
            uiStr(R.string.remove),
            {
                if (MidiManager.removeSF2File(activity, name)) {
                    WinToast.show(activity, R.string.sound_font_removed_success, Toast.LENGTH_SHORT)
                    rebuild()
                } else {
                    WinToast.show(activity, R.string.sound_font_removed_failed, Toast.LENGTH_SHORT)
                }
            },
            true
        )
    }

    override fun onChooseWinlatorPath() = pickTreeFor("winlator_path_uri")

    override fun onChooseShortcutPath() = pickTreeFor("shortcuts_export_path_uri")

    override fun onBooleanChanged(key: String, value: Boolean) {
        val editor = preferences.edit().putBoolean(key, value)
        editor.apply()
        if ("enable_custom_api_key" == key && !value) {
            // Off means the defaults: the key and every artwork address go back to them.
            RemoteSources.resetArtwork(activity)
            ArtworkRepository.clearBlock()
        }
        if ("enable_file_provider" == key) {
            WinToast.show(activity, uiStr(R.string.ui_this_option_will_take_effect_at_the_next), Toast.LENGTH_SHORT)
        }
        rebuild()
    }

    override fun onCursorSpeedChanged(percent: Int) {
        val clamped = percent.coerceIn(10, 200)
        preferences.edit().putFloat("cursor_speed", clamped / 100.0f).apply()
        rebuild()
    }

    override fun onArtworkSourcesSaved(steamGridApiKey: String, steamGridUrl: String, gamesDbSearchUrl: String, gamesDbCdnUrl: String) {
        // The editor also saves as it leaves the screen; if the switch was just turned off (which put
        // the defaults back) that must not write the old edits over them.
        if (!preferences.getBoolean("enable_custom_api_key", false) && !RemoteSources.hasArtworkOverrides(activity)) return
        RemoteSources.set(activity, RemoteSources.KEY_STEAMGRID_API_KEY, steamGridApiKey, RemoteSources.DEFAULT_STEAMGRID_API_KEY)
        RemoteSources.set(activity, RemoteSources.KEY_STEAMGRID, steamGridUrl, RemoteSources.DEFAULT_STEAMGRID)
        RemoteSources.set(activity, RemoteSources.KEY_GAMESDB_SEARCH, gamesDbSearchUrl, RemoteSources.DEFAULT_GAMESDB_SEARCH)
        RemoteSources.set(activity, RemoteSources.KEY_GAMESDB_CDN, gamesDbCdnUrl, RemoteSources.DEFAULT_GAMESDB_CDN)
        // A key that was rejected (or a rate-limit pause) may be fixed now: try again right away.
        ArtworkRepository.clearBlock()
        rebuild()
    }

    override fun onWineDebugChannelsChanged(value: String) {
        val normalized = value.trim().replace(" ", "").ifEmpty { AppDefaults.DEFAULT_WINE_DEBUG_CHANNELS }
        preferences.edit().putString("wine_debug_channels", normalized).apply()
        rebuild()
    }

    override fun onReinstallImageFs() {
        val mainActivity = activity as? MainActivity ?: return
        ImageFsInstaller.installFromAssets(mainActivity, null)
    }

    override fun onPresetAction(kind: String, id: String, action: String) {
        val box64 = "box64" == kind
        when (action) {
            "add", "edit" -> {
                val presetId = if (action == "edit") id else null
                if (box64) {
                    val dialog = Box64EditPresetDialog(activity, "box64", presetId)
                    dialog.setOnConfirmCallback {
                        rebuild()
                        WinToast.show(activity, if (presetId == null) uiStr(R.string.ui_preset_added) else uiStr(R.string.ui_preset_saved), Toast.LENGTH_SHORT)
                    }
                    dialog.show()
                } else {
                    val dialog = FEXCoreEditPresetDialog(activity, presetId)
                    dialog.setOnConfirmCallback {
                        rebuild()
                        WinToast.show(activity, if (presetId == null) uiStr(R.string.ui_preset_added) else uiStr(R.string.ui_preset_saved), Toast.LENGTH_SHORT)
                    }
                    dialog.show()
                }
            }
            "duplicate" -> ThemedAlertHost.confirm(
                activity,
                uiStr(R.string.ui_clone_preset),
                uiStr(R.string.do_you_want_to_duplicate_this_preset),
                uiStr(R.string.ui_clone),
                {
                    if (box64) Box64PresetManager.duplicatePreset("box64", activity, id)
                    else FEXCorePresetManager.duplicatePreset(activity, id)
                    rebuild()
                    WinToast.show(activity, uiStr(R.string.ui_preset_duplicated), Toast.LENGTH_SHORT)
                }
            )
            "remove" -> {
                val custom = if (box64) id.startsWith(Box64Preset.CUSTOM) else id.startsWith(FEXCorePreset.CUSTOM)
                if (!custom) {
                    WinToast.show(activity, uiStr(R.string.you_cannot_remove_this_preset), Toast.LENGTH_SHORT)
                    return
                }
                ThemedAlertHost.confirm(
                    activity,
                    uiStr(R.string.ui_remove_preset),
                    uiStr(R.string.do_you_want_to_remove_this_preset),
                    uiStr(R.string.remove),
                    {
                        if (box64) {
                            Box64PresetManager.removePreset("box64", activity, id)
                            if (id == preferences.getString("box64_preset", "")) {
                                preferences.edit().putString("box64_preset", Box64Preset.COMPATIBILITY).apply()
                            }
                        } else {
                            FEXCorePresetManager.removePreset(activity, id)
                            if (id == preferences.getString("fexcore_preset", "")) {
                                preferences.edit().putString("fexcore_preset", FEXCorePreset.COMPATIBILITY).apply()
                            }
                        }
                        rebuild()
                        WinToast.show(activity, uiStr(R.string.ui_preset_removed), Toast.LENGTH_SHORT)
                    },
                    true
                )
            }
            "import" -> pickDocument(uiStr(R.string.ui_select_preset_file), listOf("wbp")) { uri ->
                try {
                    val stream = activity.contentResolver.openInputStream(uri)
                    val imported = stream != null && (
                        if (box64) Box64PresetManager.importPreset("box64", activity, stream)
                        else FEXCorePresetManager.importPreset(activity, stream)
                    )
                    rebuild()
                    if (imported) WinToast.show(activity, uiStr(R.string.ui_preset_imported), Toast.LENGTH_SHORT)
                    else WinToast.show(activity, uiStr(R.string.ui_unable_to_import_preset), Toast.LENGTH_SHORT)
                } catch (ignored: FileNotFoundException) {
                    WinToast.show(activity, uiStr(R.string.ui_unable_to_import_preset), Toast.LENGTH_SHORT)
                }
            }
            "export" -> {
                val exportable = if (box64) id.startsWith(Box64Preset.CUSTOM) else id.startsWith(FEXCorePreset.CUSTOM)
                if (!exportable) {
                    WinToast.show(activity, uiStr(R.string.ui_cannot_export_this_preset), Toast.LENGTH_SHORT)
                    return
                }
                if (box64) Box64PresetManager.exportPreset("box64", activity, id)
                else FEXCorePresetManager.exportPreset(activity, id)
            }
        }
    }
}
