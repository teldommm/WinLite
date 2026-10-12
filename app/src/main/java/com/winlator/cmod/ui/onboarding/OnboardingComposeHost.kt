package com.winlator.cmod.ui.onboarding

import android.content.Context
import android.widget.Toast
import com.winlator.cmod.ui.toast.WinToast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.winlator.cmod.box64.Box64Preset
import com.winlator.cmod.container.Container
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.contents.ContentsManager
import com.winlator.cmod.core.DefaultVersion
import com.winlator.cmod.core.GPUInformation
import com.winlator.cmod.core.WineInfo
import com.winlator.cmod.fexcore.FEXCorePreset
import com.winlator.cmod.ui.applyAppFullscreen
import com.winlator.cmod.ui.theme.WinZTheme
import org.json.JSONObject

@Immutable
data class OnboardingComponent @JvmOverloads constructor(
    val id: String,
    val type: String,
    val name: String,
    val installed: Boolean,
    val recommended: Boolean,
    val removable: Boolean,
    val runtimeIdentifier: String? = null,
    val inUse: Boolean = false,
    val bundled: Boolean = false,
    /** The component list itself marks this one as recommended (as opposed to the app guessing). */
    val pinnedRecommended: Boolean = false
)

interface OnboardingCallbacks {
    fun onInstall(componentId: String)
    fun onRemove(componentId: String)
    fun onInstallBundledRuntime()
    fun onRemoveBundledRuntime()
    fun onBrowseLocal()
    fun onOpenServers()
    fun onRuntimeSelected(runtimeIdentifier: String)
    fun onRequestPermissions()
    fun onRetryCore()
    fun onCloseComponents()
}

class OnboardingComposeController internal constructor(
    internal val coreReady: MutableState<Boolean>,
    internal val coreProgress: MutableState<Int>,
    internal val bundledWineInstalled: MutableState<Boolean>,
    internal val bundledWineInUse: MutableState<Boolean>,
    internal val components: MutableState<List<OnboardingComponent>>,
    internal val installingId: MutableState<String?>,
    internal val installingLabel: MutableState<String?>,
    internal val installingProgress: MutableState<Int>,
    internal val initialContainerPreparing: MutableState<Boolean>,
    internal val initialContainerReady: MutableState<Boolean>,
    // Component type ("DXVK", "Wine", "AdrenoTools", ...) the Components screen should switch to,
    // set after a local package installs; the screen clears it once applied.
    internal val revealType: MutableState<String?> = mutableStateOf(null)
) {
    fun updateCore(ready: Boolean, progress: Int) {
        coreReady.value = ready
        coreProgress.value = progress.coerceIn(0, 100)
    }

    fun updateBundledRuntime(installed: Boolean, inUse: Boolean) {
        bundledWineInstalled.value = installed
        bundledWineInUse.value = inUse
    }

    fun setComponents(value: List<OnboardingComponent>) {
        components.value = value.toList()
    }

    fun setInstallBusy(componentId: String?, busy: Boolean) {
        installingId.value = if (busy) componentId else null
        if (busy) {
            installingLabel.value = null
            installingProgress.value = -1
        } else {
            installingLabel.value = null
            installingProgress.value = -1
        }
    }

    fun revealCategory(type: String) {
        revealType.value = type
    }

    fun updateInstallProgress(label: String?, progress: Int) {
        installingLabel.value = label
        installingProgress.value = progress.coerceIn(-1, 100)
    }

    fun updateInitialContainer(preparing: Boolean, ready: Boolean) {
        initialContainerPreparing.value = preparing
        initialContainerReady.value = ready
    }
}

object OnboardingComposeHost {
    // UI state for the onboarding/components flow; ComponentCatalogController drives it.
    @JvmStatic
    fun createController(
        initialCoreReady: Boolean,
        initialCoreProgress: Int,
        initialBundledWineInstalled: Boolean,
        initialBundledWineInUse: Boolean
    ): OnboardingComposeController = OnboardingComposeController(
        mutableStateOf(initialCoreReady),
        mutableStateOf(initialCoreProgress),
        mutableStateOf(initialBundledWineInstalled),
        mutableStateOf(initialBundledWineInUse),
        mutableStateOf<List<OnboardingComponent>>(emptyList()),
        mutableStateOf<String?>(null),
        mutableStateOf<String?>(null),
        mutableStateOf(-1),
        mutableStateOf(false),
        mutableStateOf(false)
    )

    // First-run flow: OnboardingActivity's whole window.
    @JvmStatic
    fun attach(
        activity: ComponentActivity,
        initialCoreReady: Boolean,
        initialCoreProgress: Int,
        initialBundledWineInstalled: Boolean,
        initialBundledWineInUse: Boolean,
        componentManagerMode: Boolean,
        callbacks: OnboardingCallbacks
    ): OnboardingComposeController {
        val controller = createController(
            initialCoreReady,
            initialCoreProgress,
            initialBundledWineInstalled,
            initialBundledWineInUse
        )
        applyAppFullscreen(activity)
        activity.setContent {
            WinZTheme {
                OnboardingFlow(activity, controller, componentManagerMode, callbacks)
            }
        }
        return controller
    }
}

// Components screen (catalog / install / remove) as a plain composable — the flow in manager
// mode. MainShell shows it as a detail entry through ComponentManagerRoute; it used to need a
// ComposeView of its own (attachToView) embedded via AndroidView.
@Composable
fun ComponentManagerContent(controller: OnboardingComposeController, callbacks: OnboardingCallbacks) {
    OnboardingFlow(LocalContext.current, controller, managerMode = true, cb = callbacks)
}

private enum class OnboardingPage { Welcome, Theme, Components, Runtime, Access }

private fun prepareInitialContainer(
    activity: Context,
    runtimeIdentifier: String,
    preparing: MutableState<Boolean>,
    ready: MutableState<Boolean>
) {
    if (preparing.value || ready.value || runtimeIdentifier.isBlank()) return

    val manager = ContainerManager(activity)
    if (manager.containers.isNotEmpty()) {
        ready.value = true
        return
    }

    val contents = ContentsManager(activity).apply { syncContents() }
    val wineInfo = WineInfo.fromIdentifier(activity, contents, runtimeIdentifier)
    if (wineInfo.path.isNullOrBlank()) {
        WinToast.show(activity, "The selected Wine/Proton layer is no longer installed.", Toast.LENGTH_LONG)
        return
    }

    preparing.value = true
    ready.value = false
    try {
        val defaultDriver = if (GPUInformation.isDriverSupported(DefaultVersion.WRAPPER_ADRENO, activity)) {
            DefaultVersion.WRAPPER_ADRENO
        } else {
            DefaultVersion.WRAPPER
        }
        val graphicsConfig = Container.DEFAULT_GRAPHICSDRIVERCONFIG.replace(
            ";version=;",
            ";version=$defaultDriver;"
        )
        val data = JSONObject().apply {
            put("name", "Container-${manager.nextContainerId}")
            put("screenSize", Container.defaultScreenSizeFor(activity))
            put("envVars", Container.DEFAULT_ENV_VARS)
            put("graphicsDriver", Container.DEFAULT_GRAPHICS_DRIVER)
            put("graphicsDriverConfig", graphicsConfig)
            put("rendererPresentMode", "fifo")
            put("dxwrapper", Container.DEFAULT_DXWRAPPER)
            put("dxwrapperConfig", Container.DEFAULT_DXWRAPPERCONFIG)
            put("audioDriver", Container.DEFAULT_AUDIO_DRIVER)
            put("emulator", if (wineInfo.isArm64EC) "FEXCore" else "Box64")
            put("wincomponents", Container.DEFAULT_WINCOMPONENTS)
            put("drives", Container.DEFAULT_DRIVES)
            put("box64Version", if (wineInfo.isArm64EC) DefaultVersion.WOWBOX64 else DefaultVersion.BOX64)
            put("box64Preset", Box64Preset.COMPATIBILITY)
            put("fexcoreVersion", DefaultVersion.FEXCORE)
            put("fexcorePreset", FEXCorePreset.INTERMEDIATE)
            put("wineVersion", runtimeIdentifier)
        }

        manager.createContainerAsync(data, contents) { created ->
            preparing.value = false
            if (created == null) {
                ready.value = false
                WinToast.show(activity, "Unable to create the first container.", Toast.LENGTH_LONG)
            } else {
                ready.value = true
            }
        }
    } catch (_: Exception) {
        preparing.value = false
        ready.value = false
        WinToast.show(activity, "Unable to prepare the first container.", Toast.LENGTH_LONG)
    }
}

@Composable
private fun OnboardingFlow(
    activity: Context,
    state: OnboardingComposeController,
    managerMode: Boolean,
    cb: OnboardingCallbacks
) {
    val ready: State<Boolean> = state.coreReady
    val progress: State<Int> = state.coreProgress
    val bundledInstalled: State<Boolean> = state.bundledWineInstalled
    val bundledInUse: State<Boolean> = state.bundledWineInUse
    val components: State<List<OnboardingComponent>> = state.components
    val installing: State<String?> = state.installingId
    val installingLabel: State<String?> = state.installingLabel
    val installingProgress: State<Int> = state.installingProgress
    val revealType: MutableState<String?> = state.revealType
    val containerPreparing: MutableState<Boolean> = state.initialContainerPreparing
    val containerReady: MutableState<Boolean> = state.initialContainerReady

    var page by rememberSaveable(managerMode) {
        mutableStateOf(if (managerMode) OnboardingPage.Components else OnboardingPage.Theme)
    }

    val hasInstalledRuntime = bundledInstalled.value || components.value.any {
        it.installed && (it.type == "Wine" || it.type == "Proton") && !it.runtimeIdentifier.isNullOrBlank()
    }

    // Advance to Access when the first container *becomes* ready while on Runtime. Keyed on
    // the ready flag only: it used to be keyed on the page too, so pressing Back on the
    // permissions screen (page = Runtime, container already ready) bounced straight back.
    LaunchedEffect(containerReady.value) {
        if (!managerMode && page == OnboardingPage.Runtime && containerReady.value) {
            page = OnboardingPage.Access
        }
    }

    when (page) {
        OnboardingPage.Welcome -> ClassicWinlatorWelcome(
            ready,
            progress,
            start = { page = OnboardingPage.Theme },
            skip = { page = OnboardingPage.Runtime },
            retry = { cb.onRetryCore() }
        )

        OnboardingPage.Theme -> OnboardingThemeScreen(
            onContinue = { page = OnboardingPage.Components }
        )

        OnboardingPage.Components -> OnboardingComponentsScreen(
            ready = ready,
            progress = progress,
            bundledInstalled = bundledInstalled,
            bundledInUse = bundledInUse,
            all = components.value,
            installing = installing.value,
            installingLabel = installingLabel.value,
            installingProgress = installingProgress.value,
            revealType = revealType.value,
            onRevealHandled = { revealType.value = null },
            managerMode = managerMode,
            onBack = {
                if (managerMode) cb.onCloseComponents()
                else page = OnboardingPage.Theme
            },
            onContinue = {
                if (managerMode) cb.onCloseComponents()
                else if (ready.value && hasInstalledRuntime) page = OnboardingPage.Runtime
            },
            cb = cb
        )

        OnboardingPage.Runtime -> OnboardingRuntimeSelectionScreen(
            components = components.value,
            bundledInstalled = bundledInstalled.value,
            preparing = containerPreparing.value,
            onBack = { page = OnboardingPage.Components },
            onContinue = { runtime ->
                if (ready.value && hasInstalledRuntime && !containerPreparing.value) {
                    cb.onRuntimeSelected(runtime)
                    if (containerReady.value) {
                        // Came back here from Access: the container already exists, so
                        // Continue just goes forward again (the effect above won't re-fire).
                        page = OnboardingPage.Access
                    } else {
                        prepareInitialContainer(activity, runtime, containerPreparing, containerReady)
                    }
                }
            }
        )

        OnboardingPage.Access -> OnboardingAccessScreen(
            back = { page = OnboardingPage.Runtime },
            next = { cb.onRequestPermissions() }
        )
    }
}
