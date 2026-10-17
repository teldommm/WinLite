package com.winlator.cmod.ui.inputcontrols

import com.winlator.cmod.ui.uiStr
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.winlator.cmod.ui.toast.WinToast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.preference.PreferenceManager
import com.winlator.cmod.ControlsEditorActivity
import com.winlator.cmod.ExternalControllerBindingsActivity
import com.winlator.cmod.R
import com.winlator.cmod.core.FileUtils
import com.winlator.cmod.core.HttpUtils
import com.winlator.cmod.core.RemoteSources
import com.winlator.cmod.inputcontrols.ControlsProfile
import com.winlator.cmod.inputcontrols.ExternalController
import com.winlator.cmod.inputcontrols.InputControlsManager
import com.winlator.cmod.ui.ThemedAlertHost
import com.winlator.cmod.ui.ThemedLoadingOverlayHost
import com.winlator.cmod.ui.theme.findActivity
import com.winlator.cmod.widget.InputControlsView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

// Input Controls as a plain composable tab of MainShell — the first screen moved off Fragments.
// Everything InputControlsFragment did lives in InputControlsScreenState below, with the same
// dialogs, toasts, activities and download flow. What changed besides the host:
//
//  - Profiles are (re)loaded off the main thread into a fresh InputControlsManager that is then
//    swapped in on the main thread. The fragment built a new manager (and re-read every
//    profile JSON) synchronously on every tab switch, right when the transition started.
//  - The file picker uses the Activity Result API instead of startActivityFromFragment +
//    onActivityResult with MainActivity.OPEN_FILE_REQUEST_CODE.
//  - Refresh points are explicit: whenever the activity (re)starts — e.g. back from
//    ControlsEditorActivity / ExternalControllerBindingsActivity, same as the fragment's
//    onStart — and whenever MainShell re-shows the tab (shownSerial).

@Composable
fun InputControlsRoute(initialProfileId: Int, shownSerial: Int) {
    val activity = LocalContext.current.findActivity() as AppCompatActivity
    val scope = rememberCoroutineScope()
    val state = remember { InputControlsScreenState(activity, initialProfileId, scope) }

    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) state.importProfileFrom(uri)
    }
    SideEffect { state.launchProfilePicker = { openDocument.launch(arrayOf("*/*")) } }

    LifecycleStartEffect(state) {
        state.reload()
        onStopOrDispose { }
    }
    LaunchedEffect(shownSerial) {
        if (shownSerial > 0) state.reload()
    }

    val model = state.model
    if (model != null) {
        InputControlsScreen(model, state)
    } else {
        // First load is in flight (a few ms of disk IO off the main thread).
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
    }
}

internal class InputControlsScreenState(
    private val activity: AppCompatActivity,
    initialProfileId: Int,
    private val scope: CoroutineScope
) : InputControlsCallbacks {

    private val preferences = PreferenceManager.getDefaultSharedPreferences(activity)
    private var manager: InputControlsManager? = null
    private var currentProfile: ControlsProfile? = null
    private var visibleControllers: List<ExternalController> = emptyList()

    // Profile to select on the first load (from MainActivity's edit_input_controls intent).
    private var pendingProfileId = initialProfileId
    private var reloadJob: Job? = null

    var model by mutableStateOf<InputControlsModel?>(null)
        private set

    // Set by InputControlsRoute each composition (the launcher belongs to the composition).
    var launchProfilePicker: (() -> Unit)? = null

    // ---------- Loading ----------

    fun reload() {
        val keepProfileId = currentProfile?.id ?: pendingProfileId
        reloadJob?.cancel()
        reloadJob = scope.launch {
            val fresh = withContext(Dispatchers.IO) {
                InputControlsManager(activity.applicationContext).also { it.getProfiles() }
            }
            manager = fresh
            currentProfile = if (keepProfileId > 0) fresh.getProfile(keepProfileId) else null
            pendingProfileId = 0
            rebuild()
        }
    }

    private fun rebuild() {
        val manager = manager ?: return
        val profileItems = manager.getProfiles().map { InputProfileItem(it.id, it.name) }

        visibleControllers = collectVisibleControllers()
        val controllerItems = visibleControllers.mapIndexed { index, controller ->
            InputControllerItem(
                index,
                controller.name,
                controller.controllerBindingCount,
                controller.isConnected
            )
        }

        val opacity = (preferences.getFloat(
            "overlay_opacity",
            InputControlsView.DEFAULT_OVERLAY_OPACITY
        ) * 100f).roundToInt()

        model = InputControlsModel(
            profileItems,
            currentProfile?.id ?: 0,
            opacity,
            controllerItems
        )
    }

    private fun collectVisibleControllers(): List<ExternalController> {
        val controllers = currentProfile?.loadControllers() ?: ArrayList()
        for (connected in ExternalController.getControllers()) {
            if (!controllers.contains(connected)) controllers.add(connected)
        }
        return controllers
    }

    // ---------- InputControlsCallbacks ----------

    override fun onProfileSelected(profileId: Int) {
        val manager = manager ?: return
        currentProfile = if (profileId > 0) manager.getProfile(profileId) else null
        rebuild()
    }

    override fun onOpacityChanged(percent: Int) {
        val snapped = ((percent / 5.0f).roundToInt() * 5).coerceIn(0, 100)
        preferences.edit().putFloat("overlay_opacity", snapped / 100.0f).apply()
        rebuild()
    }

    override fun onAddProfile() {
        ThemedAlertHost.prompt(activity, uiStr(R.string.ui_profile_name), "", uiStr(R.string.ui_add_2)) { name ->
            val trimmed = name.trim()
            val manager = manager
            if (trimmed.isEmpty() || manager == null) return@prompt
            currentProfile = manager.createProfile(trimmed)
            rebuild()
            WinToast.show(activity, uiStr(R.string.ui_profile_created, trimmed), Toast.LENGTH_SHORT)
        }
    }

    override fun onEditProfile() {
        val profile = requireProfile() ?: return
        ThemedAlertHost.prompt(activity, uiStr(R.string.ui_profile_name), profile.name, uiStr(R.string.ui_save)) { name ->
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return@prompt
            profile.name = trimmed
            profile.save()
            rebuild()
            WinToast.show(activity, uiStr(R.string.ui_profile_renamed_to, trimmed), Toast.LENGTH_SHORT)
        }
    }

    override fun onDuplicateProfile() {
        val profile = requireProfile() ?: return
        ThemedAlertHost.confirm(
            activity,
            uiStr(R.string.ui_duplicate_profile_2),
            uiStr(R.string.ui_do_you_want_to_duplicate_this_profile),
            uiStr(R.string.duplicate),
            {
                val manager = manager ?: return@confirm
                currentProfile = manager.duplicateProfile(profile)
                rebuild()
                WinToast.show(activity, uiStr(R.string.ui_profile_duplicated), Toast.LENGTH_SHORT)
            }
        )
    }

    override fun onRemoveProfile() {
        val profile = requireProfile() ?: return
        ThemedAlertHost.confirm(
            activity,
            uiStr(R.string.ui_remove_profile_2),
            uiStr(R.string.ui_do_you_want_to_remove_this_profile),
            uiStr(R.string.remove),
            {
                val manager = manager ?: return@confirm
                manager.removeProfile(profile)
                currentProfile = null
                rebuild()
                WinToast.show(activity, uiStr(R.string.ui_profile_removed, profile.name), Toast.LENGTH_SHORT)
            },
            true
        )
    }

    override fun onImportProfile() {
        ThemedAlertHost.actions(
            activity,
            uiStr(R.string.ui_import_profile),
            listOf(
                ThemedAlertHost.ActionItem(uiStr(R.string.ui_open_local_profile), { launchProfilePicker?.invoke() }),
                ThemedAlertHost.ActionItem(uiStr(R.string.ui_download_profiles), { downloadProfileList() })
            )
        )
    }

    override fun onExportProfile() {
        val profile = requireProfile() ?: return
        val exportedFile = manager?.exportProfile(profile) ?: return
        WinToast.show(activity, uiStr(R.string.ui_profile_exported_to) + exportedFile.path, Toast.LENGTH_LONG)
    }

    override fun onOpenEditor() {
        val profile = requireProfile() ?: return
        val intent = Intent(activity, ControlsEditorActivity::class.java)
        intent.putExtra("profile_id", profile.id)
        activity.startActivity(intent)
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(R.anim.shared_axis_enter, R.anim.shared_axis_exit)
    }

    override fun onOpenController(index: Int) {
        val profile = requireProfile() ?: return
        val controller = visibleControllers.getOrNull(index) ?: return
        val intent = Intent(activity, ExternalControllerBindingsActivity::class.java)
        intent.putExtra("profile_id", profile.id)
        intent.putExtra("controller_id", controller.id)
        activity.startActivity(intent)
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(R.anim.shared_axis_enter, R.anim.shared_axis_exit)
    }

    override fun onRemoveController(index: Int) {
        val profile = currentProfile ?: return
        val controller = visibleControllers.getOrNull(index) ?: return
        ThemedAlertHost.confirm(
            activity,
            uiStr(R.string.ui_remove_controller_2),
            uiStr(R.string.ui_do_you_want_to_remove_this_controller),
            uiStr(R.string.remove),
            {
                profile.removeController(controller)
                profile.save()
                rebuild()
                WinToast.show(activity, uiStr(R.string.ui_controller_removed), Toast.LENGTH_SHORT)
            },
            true
        )
    }

    // ---------- Import / download ----------

    fun importProfileFrom(uri: Uri) {
        val manager = manager ?: return
        try {
            currentProfile = manager.importProfile(JSONObject(FileUtils.readString(activity, uri)))
            rebuild()
            WinToast.show(activity, uiStr(R.string.ui_profile_imported), Toast.LENGTH_SHORT)
        } catch (e: Exception) {
            WinToast.show(activity, uiStr(R.string.ui_unable_to_import_profile), Toast.LENGTH_SHORT)
        }
    }

    private fun downloadProfileList() {
        val loadingOverlay = ThemedLoadingOverlayHost.show(activity, activity.getString(R.string.loading))
        HttpUtils.download(RemoteSources.inputControlsUrl(activity, "index.txt")) { content ->
            activity.runOnUiThread {
                ThemedLoadingOverlayHost.dismiss(loadingOverlay)
                if (content == null) {
                    WinToast.show(activity, uiStr(R.string.ui_unable_to_load_profile_list), Toast.LENGTH_SHORT)
                    return@runOnUiThread
                }
                val items = content.split("\n")
                ThemedAlertHost.multiChoice(activity, uiStr(R.string.ui_import_profile), items, uiStr(R.string.ui_download_2), { positions ->
                    if (positions.isNotEmpty()) {
                        ThemedAlertHost.confirm(
                            activity,
                            uiStr(R.string.ui_download_profiles_2),
                            uiStr(R.string.ui_do_you_want_to_download_the_selected_prof),
                            uiStr(R.string.ui_download_2),
                            { downloadSelectedProfiles(items, positions) }
                        )
                    }
                })
            }
        }
    }

    private fun downloadSelectedProfiles(items: List<String>, positions: List<Int>) {
        val manager = manager ?: return
        val downloadOverlay = ThemedLoadingOverlayHost.show(activity, activity.getString(R.string.downloading_file))
        currentProfile = null
        val processed = AtomicInteger()
        val imported = AtomicInteger()
        for (position in positions) {
            HttpUtils.download(RemoteSources.inputControlsUrl(activity, items[position])) { content ->
                try {
                    if (content != null) {
                        manager.importProfile(JSONObject(content))
                        imported.incrementAndGet()
                    }
                } catch (ignored: JSONException) {
                }
                if (processed.incrementAndGet() == positions.size) {
                    activity.runOnUiThread {
                        ThemedLoadingOverlayHost.dismiss(downloadOverlay)
                        rebuild()
                        val ok = imported.get()
                        val failed = positions.size - ok
                        when {
                            ok == 0 -> WinToast.show(activity, uiStr(R.string.ui_unable_to_download_profiles), Toast.LENGTH_LONG)
                            failed > 0 -> WinToast.show(
                                activity,
                                uiStr(R.string.ui_downloaded_of_profiles_failed, ok, positions.size, failed),
                                Toast.LENGTH_LONG
                            )
                            ok == 1 -> WinToast.show(activity, uiStr(R.string.ui_profile_downloaded), Toast.LENGTH_SHORT)
                            else -> WinToast.show(activity, uiStr(R.string.ui_profiles_downloaded, ok), Toast.LENGTH_SHORT)
                        }
                    }
                }
            }
        }
    }

    private fun requireProfile(): ControlsProfile? {
        val profile = currentProfile
        if (profile == null) WinToast.show(activity, uiStr(R.string.ui_no_profile_selected), Toast.LENGTH_SHORT)
        return profile
    }
}
