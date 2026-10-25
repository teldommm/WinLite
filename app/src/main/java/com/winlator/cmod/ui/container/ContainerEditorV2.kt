@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.winlator.cmod.ui.container

import com.winlator.cmod.ui.uiStr
import com.winlator.cmod.contentdialog.DXVKConfig
import com.winlator.cmod.directaudio.DirectAudioDriver
import com.winlator.cmod.ssbs.ForceSsbs
import com.winlator.cmod.ui.theme.ThemedDialogTitle
import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import com.winlator.cmod.ui.toast.WinToast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.box64.Box64Preset
import com.winlator.cmod.box64.Box64PresetManager
import com.winlator.cmod.container.Container
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.contents.ContentsManager
import com.winlator.cmod.core.DefaultVersion
import com.winlator.cmod.core.FileUtils
import com.winlator.cmod.core.GPUInformation
import com.winlator.cmod.core.ImageUtils
import com.winlator.cmod.core.StringUtils
import com.winlator.cmod.core.WineInfo
import com.winlator.cmod.core.WineRegistryEditor
import com.winlator.cmod.core.WineThemeManager
import com.winlator.cmod.fexcore.FEXCorePreset
import com.winlator.cmod.fexcore.FEXCorePresetManager
import com.winlator.cmod.midi.MidiManager
import com.winlator.cmod.ui.settings.CpuSelectorRow
import com.winlator.cmod.ui.shortcut.DriveLettersEditorV2
import com.winlator.cmod.ui.settings.DDrawWrapperChoice
import com.winlator.cmod.ui.settings.DriverOption
import com.winlator.cmod.ui.settings.DxvkAsyncMode
import com.winlator.cmod.ui.settings.EnvironmentVariablesEditor
import com.winlator.cmod.ui.settings.GpuSpoofSettingRow
import com.winlator.cmod.ui.settings.SettingChoice
import com.winlator.cmod.ui.settings.SettingDriverChoice
import com.winlator.cmod.ui.settings.SettingInstallChoice
import com.winlator.cmod.ui.settings.SettingMappedChoice
import com.winlator.cmod.ui.settings.ResolutionField
import com.winlator.cmod.ui.settings.SettingText
import com.winlator.cmod.ui.settings.SettingToggle
import com.winlator.cmod.ui.settings.SettingWineRuntimeChoice
import com.winlator.cmod.ui.settings.SettingsCard
import com.winlator.cmod.ui.settings.SettingsCatalog
import com.winlator.cmod.ui.settings.SettingsDivider
import com.winlator.cmod.ui.settings.WineRuntimeOption
import com.winlator.cmod.ui.settings.VulkanExtensionsDialog
import com.winlator.cmod.ui.settings.parseExtensionBlacklist
import com.winlator.cmod.ui.settings.dxvkAsyncMode
import com.winlator.cmod.ui.settings.envPut
import com.winlator.cmod.ui.settings.envValue
import com.winlator.cmod.ui.settings.filterDxvkForVkd3d
import com.winlator.cmod.ui.settings.installAdrenoDriver
import com.winlator.cmod.ui.settings.installRuntimeComponent
import com.winlator.cmod.ui.settings.installWineRuntimeComponent
import com.winlator.cmod.ui.settings.isDxvkCompatibleWithVkd3d
import com.winlator.cmod.ui.settings.pickCompatibleInstalledDxvk
import com.winlator.cmod.ui.settings.isTurnipDriver
import com.winlator.cmod.ui.settings.isVkd3dEnabled
import com.winlator.cmod.ui.settings.loadSettingsCatalog
import com.winlator.cmod.ui.settings.cachedWineRuntimeOptions
import com.winlator.cmod.ui.settings.initialSettingsCatalog
import com.winlator.cmod.ui.settings.loadWineRuntimeOptions
import com.winlator.cmod.ui.settings.localeDisplayValue
import com.winlator.cmod.ui.settings.normalizeLocaleValue
import com.winlator.cmod.ui.settings.normalizeResolution
import com.winlator.cmod.ui.settings.readConfig
import com.winlator.cmod.ui.settings.writeConfig
import com.winlator.cmod.ui.theme.ThemedDialog
import com.winlator.cmod.ui.theme.controlAccentColor
import com.winlator.cmod.winhandler.WinHandler
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import com.winlator.cmod.ui.theme.WinZShapes
import com.winlator.cmod.ui.theme.hairlineColor
import androidx.compose.material3.TopAppBarDefaults

private val containerComponentRowsV2 = listOf(
    "direct3d" to "Direct3D",
    "directsound" to "DirectSound",
    "directmusic" to "DirectMusic",
    "directshow" to "DirectShow",
    "directplay" to "DirectPlay",
    "xaudio" to "XAudio",
    "vcrun2010" to uiStr(R.string.vcrun2010)
)

private class ContainerEditorStateV2(
    context: Context,
    manager: ContainerManager,
    val editing: Container?
) {
    private val preferredDriver = if (GPUInformation.isDriverSupported(DefaultVersion.WRAPPER_ADRENO, context)) {
        DefaultVersion.WRAPPER_ADRENO
    } else DefaultVersion.WRAPPER

    var runtime by mutableStateOf(editing?.wineVersion.orEmpty())
    var name by mutableStateOf(editing?.name ?: "Container-${manager.nextContainerId}")
    var screen by mutableStateOf(editing?.screenSize ?: Container.defaultScreenSizeFor(context))
    var audio by mutableStateOf(editing?.audioDriver ?: Container.DEFAULT_AUDIO_DRIVER)
    var oboeProfile by mutableStateOf(editing?.getExtra("oboeProfile", "low") ?: "low")
    var oboeApi by mutableStateOf(editing?.getExtra("oboeApi", "auto") ?: "auto")
    var oboeAdaptive by mutableStateOf((editing?.getExtra("oboeAdaptive", "1") ?: "1") != "0")
    var oboeExclusive by mutableStateOf((editing?.getExtra("oboeExclusive", "0") ?: "0") == "1")
    var directAudioMic by mutableStateOf(DirectAudioDriver.isMicEnabled(editing?.getExtra(DirectAudioDriver.EXTRA_MIC, "0")))
    var hudMode by mutableIntStateOf(
        editing?.getExtra("hudMode", if (editing.isShowFPS) "1" else "0")?.toIntOrNull() ?: 0
    )
    var locale by mutableStateOf(
        editing?.getLC_ALL().orEmpty().ifBlank {
            if (editing == null) {
                val system = Locale.getDefault()
                normalizeLocaleValue("${system.language}_${system.country}")
            } else ""
        }
    )
    var soundFont by mutableStateOf(editing?.getMIDISoundFont().orEmpty())
    var fullscreen by mutableStateOf(editing?.isFullscreenStretched ?: false)
    var desktopTheme by mutableStateOf(if (editing?.desktopTheme.orEmpty().startsWith("LIGHT", true)) "Light" else "Dark")
    var desktopBackground by mutableStateOf(if (editing?.desktopTheme.orEmpty().contains(",COLOR,", true)) "Solid Color" else "Image")
    var wallpaperStamp by mutableStateOf(
        WineThemeManager.getUserWallpaperFile(context).takeIf { it.isFile }?.lastModified() ?: 0L
    )
    var mouseWarp by mutableStateOf(editing?.getExtra("mouseWarpOverride", "disable") ?: "disable")
    var drives by mutableStateOf(editing?.drives ?: Container.DEFAULT_DRIVES)

    var renderer by mutableStateOf(if (editing?.getRenderer() == Container.RENDERER_SURFACEFLINGER) "SurfaceFlinger" else "Vulkan")
    var rendererPresentMode by mutableStateOf(editing?.rendererPresentMode ?: "fifo")
    var rendererDriver by mutableStateOf(editing?.rendererDriverId ?: "system")
    var filterMode by mutableIntStateOf(editing?.rendererFilterMode ?: 0)
    var surfaceFormat by mutableStateOf(editing?.getSurfaceFormatSetting() ?: "bgra8")

    var graphicsDriver by mutableStateOf(
        editing?.graphicsDriver ?: if (preferredDriver == DefaultVersion.WRAPPER_ADRENO) {
            "freedreno"
        } else Container.DEFAULT_GRAPHICS_DRIVER
    )
    var graphicsWrapper by mutableStateOf(editing?.getGraphicsWrapper() ?: Container.DEFAULT_GRAPHICS_WRAPPER)
    var graphicsConfig by mutableStateOf(
        (editing?.graphicsDriverConfig ?: Container.DEFAULT_GRAPHICSDRIVERCONFIG).let { original ->
            if (readConfig(original, "version", ';').isBlank()) writeConfig(original, "version", preferredDriver, ';') else original
        }
    )
    var driverVersion by mutableStateOf(readConfig(graphicsConfig, "version", ';').ifBlank { preferredDriver })
    var blacklistedExtensions by mutableStateOf(readConfig(graphicsConfig, "blacklistedExtensions", ';'))
    var vulkanVersion by mutableStateOf(readConfig(graphicsConfig, "vulkanVersion", ';').ifBlank { "1.3" })
    var maxMemory by mutableStateOf(readConfig(graphicsConfig, "maxDeviceMemory", ';').ifBlank { "0" })
    var driverPresentMode by mutableStateOf(readConfig(graphicsConfig, "presentMode", ';').ifBlank { "mailbox" })
    var disablePresentWait by mutableStateOf(readConfig(graphicsConfig, "disablePresentWait", ';') == "1")
    var resourceType by mutableStateOf(readConfig(graphicsConfig, "resourceType", ';').ifBlank { "auto" })
    var bcn by mutableStateOf(readConfig(graphicsConfig, "bcnEmulation", ';').ifBlank { "auto" })
    var bcnType by mutableStateOf(readConfig(graphicsConfig, "bcnEmulationType", ';').ifBlank { "compute" })
    var bcnCache by mutableStateOf(readConfig(graphicsConfig, "bcnEmulationCache", ';') == "1")

    var wrapper by mutableStateOf(editing?.dxWrapper ?: Container.DEFAULT_DXWRAPPER)
    var wrapperConfig by mutableStateOf(editing?.dxWrapperConfig ?: Container.DEFAULT_DXWRAPPERCONFIG)
    var dxvkVersion by mutableStateOf(readConfig(wrapperConfig, "version", ',').ifBlank { DefaultVersion.DXVK })
    var vkd3dVersion by mutableStateOf(readConfig(wrapperConfig, "vkd3dVersion", ',').ifBlank { DefaultVersion.VKD3D })
    var vkd3dLevel by mutableStateOf(readConfig(wrapperConfig, "vkd3dLevel", ',').ifBlank { "12_1" })
    var frameRate by mutableStateOf(readConfig(wrapperConfig, "framerate", ',').ifBlank { "0" })
    var maxFrameLatency by mutableStateOf(readConfig(wrapperConfig, "maxFrameLatency", ',') == "1")
    var async by mutableStateOf(readConfig(wrapperConfig, "async", ',') == "1")
    var asyncCache by mutableStateOf(readConfig(wrapperConfig, "asyncCache", ',') == "1")
    var ddrawWrapper by mutableStateOf(readConfig(wrapperConfig, "ddrawrapper", ',').ifBlank { "none" })

    var emulator by mutableStateOf(editing?.emulator ?: "FEXCore")
    var fexVersion by mutableStateOf(editing?.fexCoreVersion ?: DefaultVersion.FEXCORE)
    var boxVersion by mutableStateOf(editing?.box64Version ?: DefaultVersion.BOX64)
    var fexPreset by mutableStateOf(editing?.fexCorePreset ?: FEXCorePreset.INTERMEDIATE)
    var forceSsbs by mutableStateOf(editing?.isForceSsbs ?: true)
    var boxPreset by mutableStateOf(editing?.box64Preset ?: Box64Preset.COMPATIBILITY)

    var exclusive by mutableStateOf(editing?.isExclusiveXInput ?: true)
    var xinput by mutableStateOf(editing?.let { (it.inputType and WinHandler.FLAG_INPUT_TYPE_XINPUT.toInt()) != 0 } ?: true)
    var dinput by mutableStateOf(editing?.let { (it.inputType and WinHandler.FLAG_INPUT_TYPE_DINPUT.toInt()) != 0 } ?: false)
    var syncCpu by mutableStateOf(editing?.isSyncCpuTopology ?: false)

    private val cpuCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    val cpu64 = mutableStateListOf<Boolean>().apply {
        val set = editing?.getCPUList(true)?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
        repeat(cpuCount) { add(set == null || set.isEmpty() || it.toString() in set) }
    }
    val cpu32 = mutableStateListOf<Boolean>().apply {
        val set = editing?.getCPUListWoW64(true)?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
        repeat(cpuCount) { add(set == null || set.isEmpty() || it.toString() in set) }
    }

    var startup by mutableIntStateOf(editing?.startupSelection?.toInt() ?: Container.STARTUP_SELECTION_ESSENTIAL.toInt())
    var openGlDefaultInitialized by mutableStateOf((editing?.getExtra("openGlDefaultInitialized", "0") ?: "0") == "1")
    private val initialEnvVars = editing?.envVars ?: Container.DEFAULT_ENV_VARS
    private val shouldAddMesaGlOverride = graphicsDriver.equals("freedreno", true) &&
        envValue(initialEnvVars, "MESA_GL_VERSION_OVERRIDE").isNullOrBlank()
    var autoMesaGlVersionOverride by mutableStateOf(
        (editing?.getExtra("autoMesaGlVersionOverride", "0") ?: "0") == "1" || shouldAddMesaGlOverride
    )
    var envVars by mutableStateOf(
        if (shouldAddMesaGlOverride) envPut(initialEnvVars, "MESA_GL_VERSION_OVERRIDE", "3.3") else initialEnvVars
    )
    val components = mutableStateMapOf<String, Int>().apply {
        putAll(parseContainerComponentsV2(editing?.winComponents ?: Container.DEFAULT_WINCOMPONENTS))
    }

    fun graphics(key: String, value: String) {
        graphicsConfig = writeConfig(graphicsConfig, key, value, ';')
        if (key == "blacklistedExtensions") blacklistedExtensions = value
    }

    fun selectGraphicsDriver(value: String) {
        graphicsDriver = value
        if (value.equals("freedreno", true)) {
            if (envValue(envVars, "MESA_GL_VERSION_OVERRIDE").isNullOrBlank()) {
                envVars = envPut(envVars, "MESA_GL_VERSION_OVERRIDE", "3.3")
                autoMesaGlVersionOverride = true
            }
        } else if (autoMesaGlVersionOverride) {
            if (envValue(envVars, "MESA_GL_VERSION_OVERRIDE") == "3.3") {
                envVars = envPut(envVars, "MESA_GL_VERSION_OVERRIDE", null)
            }
            autoMesaGlVersionOverride = false
        }
    }

    fun setEnvironment(value: String) {
        if (autoMesaGlVersionOverride && envValue(value, "MESA_GL_VERSION_OVERRIDE") != "3.3") {
            autoMesaGlVersionOverride = false
        }
        envVars = value
    }

    fun wrapperValue(key: String, value: String) {
        wrapperConfig = writeConfig(wrapperConfig, key, value, ',')
    }

    fun selectDxvkVersion(version: String) {
        dxvkVersion = version
        wrapperValue("version", version)
        when (dxvkAsyncMode(version)) {
            DxvkAsyncMode.NONE -> {
                async = false
                asyncCache = false
                wrapperValue("async", "0")
                wrapperValue("asyncCache", "0")
            }
            DxvkAsyncMode.ASYNC -> {
                async = true
                asyncCache = false
                wrapperValue("async", "1")
                wrapperValue("asyncCache", "0")
            }
            DxvkAsyncMode.GPL_ASYNC -> {
                async = true
                asyncCache = true
                wrapperValue("async", "1")
                wrapperValue("asyncCache", "1")
            }
        }
    }

    fun fingerprint(): String = listOf(
        name, screen, audio, oboeProfile, oboeApi, oboeAdaptive, oboeExclusive, directAudioMic, hudMode, locale, soundFont,
        fullscreen, desktopTheme, desktopBackground, wallpaperStamp, mouseWarp, drives,
        renderer, rendererPresentMode, rendererDriver, filterMode, surfaceFormat,
        graphicsDriver, graphicsWrapper, graphicsConfig,
        wrapper, wrapperConfig, emulator, fexVersion, boxVersion, fexPreset, forceSsbs, boxPreset, exclusive, xinput, dinput,
        syncCpu, startup, openGlDefaultInitialized, autoMesaGlVersionOverride, envVars,
        cpu64.joinToString(), cpu32.joinToString(), components.entries.sortedBy { it.key }.joinToString()
    ).joinToString("|")
}

@Composable
internal fun ContainerEditorV2(editId: Int?, onBack: () -> Unit, onCreated: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { ContainerManager(context) }
    val contents = remember { ContentsManager(context).apply { syncContents() } }
    val editing = remember(editId) { editId?.let(manager::getContainerById) }
    val state = remember(editing?.id) { ContainerEditorStateV2(context, manager, editing) }
    val scope = rememberCoroutineScope()

    var category by remember { mutableStateOf("General") }
    val categories = listOf("General", "Video", "Compatibility", "Input", "Storage", "Environment", "Advanced")
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    var revision by remember { mutableIntStateOf(0) }
    var installing by remember { mutableStateOf<Set<String>>(emptySet()) }
    var creating by remember { mutableStateOf(false) }

    val runtimeOptions by produceState(cachedWineRuntimeOptions(), revision) {
        value = loadWineRuntimeOptions(context)
    }
    LaunchedEffect(runtimeOptions, editing?.id) {
        if (state.runtime.isBlank()) state.runtime = runtimeOptions.firstOrNull { it.installed }?.id.orEmpty()
    }
    LaunchedEffect(editing?.id, state.driverVersion) {
        if (!state.openGlDefaultInitialized) {
            if (isTurnipDriver(state.driverVersion)) state.selectGraphicsDriver("freedreno")
            state.openGlDefaultInitialized = true
        }
    }

    val selectedRuntimeLabel = runtimeOptions.firstOrNull { it.id == state.runtime }?.label.orEmpty()
    val arm64 = selectedRuntimeLabel.contains("arm64ec", true) || runCatching {
        state.runtime.isNotBlank() && WineInfo.fromIdentifier(context, contents, state.runtime).isArm64EC
    }.getOrDefault(false)

    LaunchedEffect(arm64, editing?.id) {
        if (editing == null) {
            if (arm64) {
                if (state.emulator != "FEXCore" && state.emulator != "WOWBox64") state.emulator = "FEXCore"
                if (state.boxVersion == DefaultVersion.BOX64) state.boxVersion = DefaultVersion.WOWBOX64
            } else {
                state.emulator = "Box64"
                if (state.boxVersion == DefaultVersion.WOWBOX64) state.boxVersion = DefaultVersion.BOX64
            }
        }
    }

    val screenEntries = remember { context.resources.getStringArray(R.array.screen_size_entries).toList() }
    val graphicsEntries = remember { context.resources.getStringArray(R.array.graphics_driver_entries).toList() }
    val graphicsWrapperEntries = remember { context.resources.getStringArray(R.array.graphics_wrapper_entries).toList() }
    val wrapperEntries = remember { context.resources.getStringArray(R.array.dxwrapper_entries).toList() }
    val audioEntries = remember { context.resources.getStringArray(R.array.audio_driver_entries).toList() }
    val localeEntries = remember { listOf("Default") + context.resources.getStringArray(R.array.some_lc_all).toList() }
    val soundFonts = remember(revision) { loadContainerSoundFontsV2(context) }
    val fexPresets = remember { FEXCorePresetManager.getPresets(context).associate { it.id to it.name } }
    val boxPresets = remember { Box64PresetManager.getPresets("box64", context).associate { it.id to it.name } }

    // Never null: starts from the cached/local catalog so the Compatibility and Video rows are
    // there from the first frame (see initialSettingsCatalog); the full load then refines it.
    val initialCatalog = remember(arm64) {
        initialSettingsCatalog(
            context, arm64, state.dxvkVersion, state.vkd3dVersion,
            state.fexVersion, state.boxVersion, state.driverVersion
        )
    }
    val catalog by produceState<SettingsCatalog?>(initialCatalog, arm64, revision, state.dxvkVersion, state.vkd3dVersion, state.driverVersion) {
        value = loadSettingsCatalog(
            context, arm64, state.dxvkVersion, state.vkd3dVersion,
            state.fexVersion, state.boxVersion, state.driverVersion
        )
    }

    fun installRuntime(type: String, version: String, done: (String) -> Unit) {
        val key = "$type:$version"
        if (key in installing) return
        installing = installing + key
        scope.launch {
            val outcome = installRuntimeComponent(context, type, version)
            val installed = outcome.id
            installing = installing - key
            if (installed == null) WinToast.show(context, outcome.failureText(uiStr(R.string.ui_unable_to_install, version)), Toast.LENGTH_SHORT)
            else {
                done(installed)
                revision++
                WinToast.show(context, uiStr(R.string.ui_installed, version), Toast.LENGTH_SHORT)
            }
        }
    }

    fun installWine(option: WineRuntimeOption) {
        val key = "wine:${option.id}"
        if (key in installing) return
        installing = installing + key
        scope.launch {
            val outcome = installWineRuntimeComponent(context, option)
            val installedId = outcome.id
            installing = installing - key
            if (installedId == null) WinToast.show(context, outcome.failureText(uiStr(R.string.ui_unable_to_install, option.label)), Toast.LENGTH_LONG)
            else {
                state.runtime = installedId
                contents.syncContents()
                revision++
                WinToast.show(context, uiStr(R.string.ui_installed, option.label), Toast.LENGTH_SHORT)
            }
        }
    }

    fun installDriver(option: DriverOption) {
        val key = "driver:${option.remoteUrl ?: option.id}"
        if (key in installing) return
        installing = installing + key
        scope.launch {
            val outcome = installAdrenoDriver(context, option)
            val installed = outcome.id
            installing = installing - key
            if (installed == null) WinToast.show(context, outcome.failureText(uiStr(R.string.ui_unable_to_install, option.label)), Toast.LENGTH_SHORT)
            else {
                state.driverVersion = installed
                state.graphics("version", installed)
                revision++
                WinToast.show(context, uiStr(R.string.ui_installed, option.label), Toast.LENGTH_SHORT)
            }
        }
    }

    fun applyMouseWarp(container: Container) {
        runCatching {
            WineRegistryEditor(File(container.rootDir, ".wine/user.reg")).use { registry ->
                registry.setStringValue("Software\\Wine\\DirectInput", "MouseWarpOverride", state.mouseWarp)
            }
        }
    }

    fun saveExisting(container: Container) {
        container.setName(state.name.trim().ifBlank { container.name })
        container.setScreenSize(normalizeResolution(state.screen))
        container.setEnvVars(state.envVars)
        container.setDrives(state.drives)
        container.setCPUList(state.cpu64.indices.filter { state.cpu64[it] }.joinToString(","))
        container.setCPUListWoW64(state.cpu32.indices.filter { state.cpu32[it] }.joinToString(","))
        container.setSyncCpuTopology(state.syncCpu)
        container.setGraphicsDriver(state.graphicsDriver)
        container.setGraphicsWrapper(state.graphicsWrapper)
        container.setGraphicsDriverConfig(state.graphicsConfig)
        container.setRenderer(state.renderer)
        container.setRendererPresentMode(state.rendererPresentMode)
        container.setRendererDriverId(state.rendererDriver)
        container.setRendererFilterMode(state.filterMode)
        container.setSurfaceFormat(state.surfaceFormat)
        container.setDXWrapper(state.wrapper)
        container.setDXWrapperConfig(state.wrapperConfig)
        container.setAudioDriver(state.audio)
        container.putExtra("oboeProfile", state.oboeProfile)
        container.putExtra("oboeApi", state.oboeApi)
        container.putExtra("oboeAdaptive", if (state.oboeAdaptive) "1" else "0")
        container.putExtra("oboeExclusive", if (state.oboeExclusive) "1" else "0")
        container.putExtra(DirectAudioDriver.EXTRA_MIC, if (state.directAudioMic) "1" else "0")
        container.setEmulator(if (arm64) if (state.emulator == "FEXCore") "FEXCore" else "Box64" else "Box64")
        container.setWinComponents(serializeContainerComponentsV2(state.components))
        container.setShowFPS(state.hudMode != 0)
        container.setFullscreenStretched(state.fullscreen)
        container.setExclusiveXInput(state.exclusive)
        var inputType = 0
        if (state.xinput) inputType = inputType or WinHandler.FLAG_INPUT_TYPE_XINPUT.toInt()
        if (state.dinput) inputType = inputType or WinHandler.FLAG_INPUT_TYPE_DINPUT.toInt()
        container.setInputType(inputType)
        container.setStartupSelection(state.startup.toByte())
        container.setBox64Version(state.boxVersion)
        container.setBox64Preset(state.boxPreset)
        container.setFEXCoreVersion(state.fexVersion)
        container.setFEXCorePreset(state.fexPreset)
        container.setForceSsbs(state.forceSsbs)
        container.setDesktopTheme(containerDesktopThemeValueV2(state.desktopTheme, state.desktopBackground, state.wallpaperStamp))
        container.setMidiSoundFont(state.soundFont)
        container.setLC_ALL(state.locale)
        container.putExtra("hudMode", state.hudMode.toString())
        container.putExtra("mouseWarpOverride", state.mouseWarp)
        container.putExtra("openGlDefaultInitialized", if (state.openGlDefaultInitialized) "1" else "0")
        container.putExtra("autoMesaGlVersionOverride", if (state.autoMesaGlVersionOverride) "1" else "0")
        container.saveData()
        applyMouseWarp(container)
    }

    val fingerprint = state.fingerprint()
    LaunchedEffect(editing?.id, fingerprint, arm64) {
        if (editing != null) runCatching { saveExisting(editing) }
    }

    fun createContainer() {
        if (creating || state.runtime.isBlank()) return
        val wineInfo = WineInfo.fromIdentifier(context, contents, state.runtime)
        if (wineInfo.path.isNullOrBlank()) {
            WinToast.show(context, uiStr(R.string.ui_selected_wine_proton_is_not_installed), Toast.LENGTH_LONG)
            return
        }
        creating = true
        try {
            val data = JSONObject().apply {
                put("name", state.name.trim().ifBlank { "Container-${manager.nextContainerId}" })
                put("screenSize", normalizeResolution(state.screen))
                put("envVars", state.envVars)
                put("cpuList", state.cpu64.indices.filter { state.cpu64[it] }.joinToString(","))
                put("cpuListWoW64", state.cpu32.indices.filter { state.cpu32[it] }.joinToString(","))
                if (state.syncCpu) put("syncCpuTopology", true)
                put("graphicsDriver", state.graphicsDriver)
                put("graphicsDriverConfig", state.graphicsConfig)
                put("rendererPresentMode", state.rendererPresentMode)
                if (state.rendererDriver.isNotBlank()) put("rendererDriverId", state.rendererDriver)
                if (state.filterMode != 0) put("rendererFilterMode", state.filterMode)
                put("dxwrapper", state.wrapper)
                put("dxwrapperConfig", state.wrapperConfig)
                put("audioDriver", state.audio)
                put("emulator", if (arm64) if (state.emulator == "FEXCore") "FEXCore" else "Box64" else "Box64")
                put("wincomponents", serializeContainerComponentsV2(state.components))
                put("drives", state.drives)
                put("showFPS", state.hudMode != 0)
                put("fullscreenStretched", state.fullscreen)
                put("exclusiveXInput", state.exclusive)
                var inputType = 0
                if (state.xinput) inputType = inputType or WinHandler.FLAG_INPUT_TYPE_XINPUT.toInt()
                if (state.dinput) inputType = inputType or WinHandler.FLAG_INPUT_TYPE_DINPUT.toInt()
                put("inputType", inputType)
                put("startupSelection", state.startup)
                put("box64Version", state.boxVersion)
                put("box64Preset", state.boxPreset)
                put("fexcoreVersion", state.fexVersion)
                put("fexcorePreset", state.fexPreset)
                put("forceSsbs", state.forceSsbs)
                put("desktopTheme", containerDesktopThemeValueV2(state.desktopTheme, state.desktopBackground, state.wallpaperStamp))
                put("wineVersion", state.runtime)
                put("midiSoundFont", state.soundFont)
                put("lc_all", state.locale)
                put("extraData", JSONObject()
                    .put("graphicsWrapper", state.graphicsWrapper)
                    .put("hudMode", state.hudMode.toString())
                    .put("mouseWarpOverride", state.mouseWarp)
                    .put("surfaceFormat", state.surfaceFormat)
                    .put("renderer", if (state.renderer == "SurfaceFlinger") Container.RENDERER_SURFACEFLINGER else Container.RENDERER_VULKAN)
                    .put("oboeProfile", state.oboeProfile)
                    .put("oboeApi", state.oboeApi)
                    .put("oboeAdaptive", if (state.oboeAdaptive) "1" else "0")
                    .put("oboeExclusive", if (state.oboeExclusive) "1" else "0")
                    .put(DirectAudioDriver.EXTRA_MIC, if (state.directAudioMic) "1" else "0")
                    .put("openGlDefaultInitialized", if (state.openGlDefaultInitialized) "1" else "0")
                    .put("autoMesaGlVersionOverride", if (state.autoMesaGlVersionOverride) "1" else "0"))
            }
            manager.createContainerAsync(data, contents) { created ->
                creating = false
                if (created == null) WinToast.show(context, uiStr(R.string.ui_unable_to_create_container), Toast.LENGTH_LONG)
                else {
                    applyMouseWarp(created)
                    onCreated()
                    WinToast.show(context, uiStr(R.string.ui_container_created, created.name), Toast.LENGTH_SHORT)
                }
            }
        } catch (_: Exception) {
            creating = false
            WinToast.show(context, uiStr(R.string.ui_unable_to_create_container), Toast.LENGTH_LONG)
        }
    }

    val runtimeChoices = if (editing == null) runtimeOptions else runtimeOptions.filter { it.id == state.runtime }

    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (editing == null) uiStr(R.string.ui_new_container) else state.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, uiStr(R.string.ui_back)) } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            if (editing == null) {
                // Transparent holder: only the button is drawn. The list scrolls under it (its
                // bottom padding is the bar's height), so no rectangle is painted around the button.
                Surface(color = Color.Transparent) {
                    Button(
                        onClick = ::createContainer,
                        enabled = state.runtime.isNotBlank() && !creating,
                        modifier = Modifier.navigationBarsPadding().fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp).height(50.dp),
                        shape = WinZShapes.Medium,
                        colors = ButtonDefaults.buttonColors(containerColor = controlAccentColor(), contentColor = Color.White)
                    ) {
                        Text(if (creating) uiStr(R.string.ui_creating) else uiStr(R.string.ui_create_container), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    ) { padding ->
        // The bar's height is not applied as content padding: the lists run behind the floating
        // button and reserve that height inside their own content padding instead.
        val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
        val bottomInset = padding.calculateBottomPadding()
        val contentInsets = PaddingValues.Absolute(
            left = padding.calculateLeftPadding(layoutDirection),
            top = padding.calculateTopPadding(),
            right = padding.calculateRightPadding(layoutDirection)
        )
        if (landscape) {
            Row(
                Modifier.fillMaxSize().padding(contentInsets).padding(start = 18.dp, end = 18.dp, top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    Modifier.width(220.dp).fillMaxHeight().padding(bottom = bottomInset + 10.dp),
                    shape = WinZShapes.Medium,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, hairlineColor())
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        item {
                            Text(
                                uiStr(R.string.ui_container_settings),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                        items(categories) { item -> ContainerNavItemV2(containerCategoryLabel(item), category == item) { category = item } }
                    }
                }
                LazyColumn(
                    Modifier.weight(1f).fillMaxHeight(),
                    contentPadding = PaddingValues(bottom = bottomInset + 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(category) {
                        ContainerCategoryV2(
                            category, state, runtimeChoices, arm64, screenEntries, graphicsEntries, graphicsWrapperEntries,
                            wrapperEntries, audioEntries, localeEntries, soundFonts, fexPresets,
                            boxPresets, catalog, installing, ::installWine, ::installDriver, ::installRuntime
                        )
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(contentInsets)) {
                LazyRow(
                    Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(categories) { item -> ContainerNavItemV2(containerCategoryLabel(item), category == item) { category = item } }
                }
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 14.dp),
                    contentPadding = PaddingValues(bottom = bottomInset + 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(category) {
                        ContainerCategoryV2(
                            category, state, runtimeChoices, arm64, screenEntries, graphicsEntries, graphicsWrapperEntries,
                            wrapperEntries, audioEntries, localeEntries, soundFonts, fexPresets,
                            boxPresets, catalog, installing, ::installWine, ::installDriver, ::installRuntime
                        )
                    }
                }
            }
        }
    }
}

// The categories are also the editor's page keys ("General" -> ...), so they stay English in
// code; only the name shown in the side list comes from resources.
private fun containerCategoryLabel(category: String): String = when (category) {
    "General" -> uiStr(R.string.ui_cat_general)
    "Video" -> uiStr(R.string.ui_cat_video)
    "Compatibility" -> uiStr(R.string.ui_cat_compatibility)
    "Input" -> uiStr(R.string.ui_cat_input)
    "Storage" -> uiStr(R.string.ui_cat_storage)
    "Environment" -> uiStr(R.string.ui_cat_environment)
    "Advanced" -> uiStr(R.string.ui_cat_advanced)
    else -> category
}

@Composable
private fun ContainerNavItemV2(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = WinZShapes.Small,
        color = if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun ContainerCategoryV2(
    category: String,
    s: ContainerEditorStateV2,
    runtimes: List<WineRuntimeOption>,
    arm64: Boolean,
    screenEntries: List<String>,
    graphicsEntries: List<String>,
    graphicsWrapperEntries: List<String>,
    wrapperEntries: List<String>,
    audioEntries: List<String>,
    localeEntries: List<String>,
    soundFonts: List<String>,
    fexPresets: Map<String, String>,
    boxPresets: Map<String, String>,
    catalog: SettingsCatalog?,
    installing: Set<String>,
    installWine: (WineRuntimeOption) -> Unit,
    installDriver: (DriverOption) -> Unit,
    installRuntime: (String, String, (String) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val wallpaperPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            runCatching {
                val bitmap = ImageUtils.getBitmapFromUri(context, uri, 1280) ?: error("Unable to decode image")
                val target = WineThemeManager.getUserWallpaperFile(context)
                target.parentFile?.mkdirs()
                if (!ImageUtils.save(bitmap, target, Bitmap.CompressFormat.PNG, 100)) error("Unable to save image")
                val stamp = target.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
                s.desktopBackground = "Image"
                s.wallpaperStamp = stamp
            }.onFailure {
                WinToast.show(context, uiStr(R.string.ui_unable_to_set_wallpaper_image), Toast.LENGTH_SHORT)
            }
        }
    }

    when (category) {
        "General" -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsCard {
                SettingText(uiStr(R.string.ui_name), s.name) { s.name = it }
                SettingsDivider()
                SettingWineRuntimeChoice(uiStr(R.string.ui_wine_proton), s.runtime, runtimes, installing, installWine) { s.runtime = it }
            }
            // DirectAudio exists only for Proton 11 arm64ec; switching away clears the choice.
            LaunchedEffect(s.runtime) {
                if (DirectAudioDriver.isSelected(s.audio) && !DirectAudioDriver.isSupportedWine(s.runtime)) {
                    s.audio = Container.DEFAULT_AUDIO_DRIVER
                }
            }
            val visibleAudioEntries = DirectAudioDriver.audioEntriesFor(audioEntries, s.runtime)
            SettingsCard {
                SettingChoice(
                    uiStr(R.string.ui_audio_driver),
                    visibleAudioEntries.firstOrNull { StringUtils.parseIdentifier(it).equals(s.audio, true) } ?: s.audio,
                    visibleAudioEntries
                ) { s.audio = StringUtils.parseIdentifier(it) }
                if (DirectAudioDriver.isSelected(s.audio)) {
                    val micContext = LocalContext.current
                    SettingsDivider()
                    SettingToggle(uiStr(R.string.ui_microphone), s.directAudioMic) {
                        s.directAudioMic = it
                        if (it) DirectAudioDriver.requestMicPermission(micContext)
                    }
                }
                if (s.audio == "oboe") {
                    SettingsDivider()
                    val latencyLabel = when (s.oboeProfile) {
                        "ultra" -> uiStr(R.string.ui_low_latency)
                        "stable" -> uiStr(R.string.ui_stable)
                        else -> uiStr(R.string.ui_automatic)
                    }
                    SettingChoice(uiStr(R.string.ui_oboe_latency), latencyLabel, listOf(uiStr(R.string.ui_automatic), uiStr(R.string.ui_low_latency), uiStr(R.string.ui_stable))) {
                        s.oboeProfile = when (it) {
                            uiStr(R.string.ui_low_latency) -> "ultra"
                            uiStr(R.string.ui_stable) -> "stable"
                            else -> "low"
                        }
                    }
                    SettingsDivider()
                    val apiLabel = when (s.oboeApi) {
                        "aaudio" -> "AAudio"
                        "opensles" -> "OpenSL ES"
                        else -> uiStr(R.string.ui_automatic)
                    }
                    SettingChoice(uiStr(R.string.ui_oboe_backend), apiLabel, listOf(uiStr(R.string.ui_automatic), "AAudio", "OpenSL ES")) {
                        s.oboeApi = when (it) {
                            "AAudio" -> "aaudio"
                            "OpenSL ES" -> "opensles"
                            else -> "auto"
                        }
                    }
                }
                SettingsDivider()
                val hudEntries = listOf(uiStr(R.string.ui_off), uiStr(R.string.ui_classic), uiStr(R.string.ui_modern))
                SettingChoice(uiStr(R.string.ui_winlator_hud), hudEntries.getOrElse(s.hudMode) { uiStr(R.string.ui_off) }, hudEntries) {
                    s.hudMode = hudEntries.indexOf(it).coerceAtLeast(0)
                }
            }
            SettingsCard {
                SettingChoice(uiStr(R.string.ui_locale_lc_all), localeDisplayValue(s.locale), localeEntries) {
                    s.locale = normalizeLocaleValue(it)
                }
                SettingsDivider()
                SettingChoice(uiStr(R.string.ui_midi_soundfont), s.soundFont.ifBlank { uiStr(R.string.disabled) }, soundFonts) {
                    s.soundFont = if (it == uiStr(R.string.disabled)) "" else it
                }
                SettingsDivider()
                SettingToggle(uiStr(R.string.ui_fullscreen_stretched), s.fullscreen) { s.fullscreen = it }
            }
            SettingsCard {
                SettingChoice(uiStr(R.string.ui_desktop_theme), s.desktopTheme, listOf("Dark", "Light")) { s.desktopTheme = it }
                SettingsDivider()
                SettingChoice("Desktop Background", s.desktopBackground, listOf("Image", "Solid Color")) { s.desktopBackground = it }
                if (s.desktopBackground == "Image") {
                    SettingsDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                uiStr(R.string.ui_wallpaper_image),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                if (s.wallpaperStamp > 0L) uiStr(R.string.ui_custom_image) else uiStr(R.string.ui_default_wallpaper),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        TextButton(onClick = { wallpaperPicker.launch("image/*") }) {
                            Text(if (s.wallpaperStamp > 0L) uiStr(R.string.ui_change) else uiStr(R.string.ui_choose))
                        }
                    }
                }
                SettingsDivider()
                SettingChoice(uiStr(R.string.ui_mouse_warp_override), s.mouseWarp, listOf("disable", "enable", "force")) { s.mouseWarp = it }
            }
        }

        "Video" -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsCard {
                var customScreenSelected by remember {
                    mutableStateOf(screenEntries.none { normalizeResolution(it).equals(s.screen, true) })
                }
                val shownScreen = if (customScreenSelected) "Custom"
                else screenEntries.firstOrNull { normalizeResolution(it).equals(s.screen, true) } ?: "Custom"
                SettingChoice(uiStr(R.string.ui_screen_size), shownScreen, screenEntries) {
                    if (it.equals("Custom", true)) {
                        customScreenSelected = true
                    } else {
                        customScreenSelected = false
                        s.screen = normalizeResolution(it)
                    }
                }
                if (shownScreen == "Custom") {
                    SettingsDivider()
                    ResolutionField(s.screen) { s.screen = it }
                }
                SettingsDivider()
                SettingChoice(uiStr(R.string.ui_renderer), s.renderer, if (android.os.Build.VERSION.SDK_INT >= 29) listOf("Vulkan", "SurfaceFlinger") else listOf("Vulkan")) {
                    s.renderer = it
                }
                // The SurfaceFlinger renderer only takes RGBA buffers: its surface format is fixed to
                // rgba8 (Container.getSurfaceFormat()), so the choice only exists for Vulkan.
                if (s.renderer != "SurfaceFlinger") {
                    SettingsDivider()
                    SettingChoice(
                        uiStr(R.string.ui_surface_format),
                        if (s.surfaceFormat == "bgra8") "BGRA" else "RGBA",
                        listOf("BGRA", "RGBA")
                    ) { s.surfaceFormat = if (it == "BGRA") "bgra8" else "rgba8" }
                }
                // Present mode, renderer driver and the upscaling filter belong to the Vulkan renderer only.
                if (s.renderer != "SurfaceFlinger") {
                    SettingsDivider()
                    SettingChoice(uiStr(R.string.ui_present_mode), s.rendererPresentMode, listOf("fifo", "mailbox")) {
                        s.rendererPresentMode = it
                    }
                    catalog?.let { c ->
                        SettingsDivider()
                        SettingMappedChoice(uiStr(R.string.ui_renderer_driver), s.rendererDriver, c.rendererDrivers) {
                            s.rendererDriver = it
                        }
                    }
                    SettingsDivider()
                    // Saved filterMode ids: 0 Bilinear, 1 Nearest, 2 SGSR, 3 FSR, 4 Lanczos2, 5 Color Boost, 6 SGSR HQ, 7 NIS.
                    // Display order: SGSR HQ right after SGSR, NIS right after FSR.
                    val filterIds = listOf(0, 1, 2, 6, 3, 7, 4, 5)
                    val filters = listOf(
                        uiStr(R.string.ui_bilinear),
                        uiStr(R.string.ui_nearest_neighbor),
                        uiStr(R.string.ui_snapdragon_super_resolution),
                        uiStr(R.string.ui_snapdragon_super_resolution_hq),
                        uiStr(R.string.ui_amd_fidelityfx_super_resolution),
                        uiStr(R.string.ui_nvidia_image_scaling_nis),
                        uiStr(R.string.ui_lanczos_2_16_tap),
                        uiStr(R.string.ui_color_boost)
                    )
                    SettingChoice(uiStr(R.string.ui_texture_filter), filters[filterIds.indexOf(s.filterMode).coerceAtLeast(0)], filters) {
                        s.filterMode = filterIds[filters.indexOf(it).coerceAtLeast(0)]
                    }
                }
            }
            SettingsCard {
                SettingChoice(
                    "Graphics Driver",
                    graphicsEntries.firstOrNull { StringUtils.parseIdentifier(it).equals(s.graphicsDriver, true) } ?: s.graphicsDriver,
                    graphicsEntries
                ) { s.selectGraphicsDriver(StringUtils.parseIdentifier(it)) }
                SettingsDivider()
                SettingChoice(
                    uiStr(R.string.ui_vulkan_wrapper),
                    graphicsWrapperEntries.firstOrNull {
                        Container.normalizeGraphicsWrapper(StringUtils.parseIdentifier(it)).equals(s.graphicsWrapper, true)
                    } ?: s.graphicsWrapper,
                    graphicsWrapperEntries
                ) { s.graphicsWrapper = Container.normalizeGraphicsWrapper(StringUtils.parseIdentifier(it)) }
                catalog?.let { c ->
                    SettingsDivider()
                    SettingDriverChoice("Driver Version", s.driverVersion, c.drivers, installing, installDriver) {
                        s.driverVersion = it
                        s.graphics("version", it)
                    }
                    SettingsDivider()
                    ContainerVulkanExtensionsV2(context, s.driverVersion, s.blacklistedExtensions) {
                        s.graphics("blacklistedExtensions", it)
                    }
                    SettingsDivider()
                }
                SettingChoice(uiStr(R.string.ui_vulkan_version), s.vulkanVersion, listOf("1.1", "1.2", "1.3", "1.4")) {
                    s.vulkanVersion = it; s.graphics("vulkanVersion", it)
                }
                SettingsDivider()
                GpuSpoofSettingRow(s.graphicsConfig) { s.graphicsConfig = it }
                SettingsDivider()
                SettingChoice(uiStr(R.string.ui_max_device_memory), s.maxMemory, listOf("0", "512", "1024", "2048", "4096", "8192", "12288", "16384")) {
                    s.maxMemory = it; s.graphics("maxDeviceMemory", it)
                }
                SettingsDivider()
                SettingChoice(uiStr(R.string.ui_driver_present_mode), s.driverPresentMode, listOf("mailbox", "fifo", "immediate", "relaxed")) {
                    s.driverPresentMode = it; s.graphics("presentMode", it)
                }
                SettingsDivider()
                SettingToggle(uiStr(R.string.ui_disable_present_wait), s.disablePresentWait) {
                    s.disablePresentWait = it; s.graphics("disablePresentWait", if (it) "1" else "0")
                }
                SettingsDivider()
                SettingChoice(uiStr(R.string.ui_resource_type), s.resourceType, listOf("auto", "dmabuf", "ahb", "opaque")) {
                    s.resourceType = it; s.graphics("resourceType", it)
                }
                SettingsDivider()
                SettingChoice(uiStr(R.string.ui_bcn_emulation), s.bcn, listOf("none", "partial", "full", "auto")) {
                    s.bcn = it; s.graphics("bcnEmulation", it)
                }
                SettingsDivider()
                SettingChoice(uiStr(R.string.ui_bcn_emulation_type), s.bcnType, listOf("software", "compute")) {
                    s.bcnType = it; s.graphics("bcnEmulationType", it)
                }
                SettingsDivider()
                SettingToggle(uiStr(R.string.ui_bcn_emulation_cache), s.bcnCache) {
                    s.bcnCache = it; s.graphics("bcnEmulationCache", if (it) "1" else "0")
                }
            }
        }

        "Compatibility" -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsCard {
                val shownWrapper = wrapperEntries.firstOrNull { StringUtils.parseIdentifier(it).equals(s.wrapper, true) } ?: s.wrapper
                SettingChoice(uiStr(R.string.dxwrapper), shownWrapper, wrapperEntries) { s.wrapper = StringUtils.parseIdentifier(it) }
                if (s.wrapper.contains("dxvk", true)) catalog?.let { c ->
                    val dxvkCatalog = filterDxvkForVkd3d(c.dxvk, s.vkd3dVersion)
                    SettingsDivider()
                    SettingInstallChoice(uiStr(R.string.ui_dxvk_version), s.dxvkVersion, dxvkCatalog, installing, "DXVK", { v ->
                        installRuntime("DXVK", v) { installed -> s.selectDxvkVersion(installed) }
                    }) { s.selectDxvkVersion(it) }
                    SettingsDivider()
                    SettingInstallChoice(uiStr(R.string.ui_vkd3d_version), s.vkd3dVersion, c.vkd3d, installing, "VKD3D", { v ->
                        installRuntime("VKD3D", v) { installed ->
                            s.vkd3dVersion = installed
                            s.wrapperValue("vkd3dVersion", installed)
                            if (isVkd3dEnabled(installed) && !isDxvkCompatibleWithVkd3d(s.dxvkVersion)) {
                                pickCompatibleInstalledDxvk(c.dxvk)?.let(s::selectDxvkVersion)
                            }
                        }
                    }) {
                        s.vkd3dVersion = it
                        s.wrapperValue("vkd3dVersion", it)
                        if (isVkd3dEnabled(it) && !isDxvkCompatibleWithVkd3d(s.dxvkVersion)) {
                            pickCompatibleInstalledDxvk(c.dxvk)?.let(s::selectDxvkVersion)
                        }
                    }
                    SettingsDivider()
                    SettingChoice(uiStr(R.string.ui_vkd3d_feature_level), s.vkd3dLevel, listOf("12_0", "12_1", "12_2", "11_1", "11_0", "10_1", "10_0", "9_3", "9_2", "9_1")) {
                        s.vkd3dLevel = it; s.wrapperValue("vkd3dLevel", it)
                    }
                    SettingsDivider()
                    SettingText(uiStr(R.string.ui_frame_rate), s.frameRate) {
                        s.frameRate = it.filter(Char::isDigit).take(4); s.wrapperValue("framerate", s.frameRate.ifBlank { "0" })
                    }
                    SettingsDivider()
                    SettingToggle(uiStr(R.string.ui_dxvk_max_frame_latency), s.maxFrameLatency) {
                        s.maxFrameLatency = it; s.wrapperValue("maxFrameLatency", if (it) "1" else "0")
                    }
                    SettingsDivider()
                    SettingChoice(uiStr(R.string.ui_dxvk_anisotropic_filtering), DXVKConfig.ANISOTROPY_LABELS.getOrElse(DXVKConfig.ANISOTROPY_VALUES.indexOf(readConfig(s.wrapperConfig, "anisotropy", ','))) { DXVKConfig.ANISOTROPY_LABELS[0] }, DXVKConfig.ANISOTROPY_LABELS.toList()) {
                        s.wrapperValue("anisotropy", DXVKConfig.ANISOTROPY_VALUES[DXVKConfig.ANISOTROPY_LABELS.indexOf(it).coerceAtLeast(0)])
                    }
                    SettingsDivider()
                    SettingChoice(uiStr(R.string.ui_dxvk_texture_sharpness), DXVKConfig.LOD_BIAS_LABELS.getOrElse(DXVKConfig.LOD_BIAS_VALUES.indexOf(readConfig(s.wrapperConfig, "lodBias", ','))) { DXVKConfig.LOD_BIAS_LABELS[0] }, DXVKConfig.LOD_BIAS_LABELS.toList()) {
                        s.wrapperValue("lodBias", DXVKConfig.LOD_BIAS_VALUES[DXVKConfig.LOD_BIAS_LABELS.indexOf(it).coerceAtLeast(0)])
                    }
                    val asyncMode = dxvkAsyncMode(s.dxvkVersion)
                    if (asyncMode != DxvkAsyncMode.NONE) {
                        SettingsDivider()
                        SettingToggle(uiStr(R.string.ui_async), s.async) { s.async = it; s.wrapperValue("async", if (it) "1" else "0") }
                    }
                    if (asyncMode == DxvkAsyncMode.GPL_ASYNC) {
                        SettingsDivider()
                        SettingToggle(uiStr(R.string.ui_async_cache), s.asyncCache) { s.asyncCache = it; s.wrapperValue("asyncCache", if (it) "1" else "0") }
                    }
                    SettingsDivider()
                    DDrawWrapperChoice(s.ddrawWrapper) {
                        s.ddrawWrapper = it; s.wrapperValue("ddrawrapper", it)
                    }
                }
            }
            SettingsCard {
                if (arm64) {
                    SettingChoice(uiStr(R.string.ui_32_bit_emulator), s.emulator, listOf("FEXCore", "WOWBox64")) { s.emulator = it }
                    catalog?.let { c ->
                        SettingsDivider()
                        SettingInstallChoice(uiStr(R.string.ui_fexcore_version), s.fexVersion, c.fex, installing, "FEXCore", { v ->
                            installRuntime("FEXCore", v) { installed -> s.fexVersion = installed }
                        }) { s.fexVersion = it }
                    }
                    SettingsDivider()
                    SettingMappedChoice(uiStr(R.string.fexcore_preset), s.fexPreset, fexPresets) { s.fexPreset = it }
                    if (ForceSsbs.isSupportedWine(s.runtime)) {
                        SettingsDivider()
                        SettingToggle(uiStr(R.string.ui_force_ssbs), s.forceSsbs) { s.forceSsbs = it }
                    }
                }
                if (!arm64 || s.emulator == "WOWBox64") {
                    catalog?.let { c ->
                        val type = if (arm64) "WOWBox64" else "Box64"
                        val versions = if (arm64) c.wow else c.box
                        if (arm64) SettingsDivider()
                        SettingInstallChoice(uiStr(R.string.ui_version, type), s.boxVersion, versions, installing, type, { v ->
                            installRuntime(type, v) { installed -> s.boxVersion = installed }
                        }) { s.boxVersion = it }
                    }
                    SettingsDivider()
                    SettingMappedChoice(uiStr(R.string.box64_preset), s.boxPreset, boxPresets) { s.boxPreset = it }
                }
            }
        }

        "Input" -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsCard {
                SettingToggle(uiStr(R.string.ui_exclusive_input), s.exclusive) { enabled ->
                    s.exclusive = enabled
                    if (!enabled) {
                        s.xinput = true; s.dinput = true
                    } else if (s.xinput && s.dinput) s.dinput = false
                }
                SettingsDivider()
                SettingToggle(uiStr(R.string.ui_enable_xinput), s.xinput, s.exclusive) {
                    s.xinput = it; if (s.exclusive && it && s.dinput) s.dinput = false
                }
                SettingsDivider()
                SettingToggle(uiStr(R.string.ui_enable_dinput), s.dinput, s.exclusive) {
                    s.dinput = it; if (s.exclusive && it && s.xinput) s.xinput = false
                }
            }
        }

        "Storage" -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DriveLettersEditorV2(
                initialDrives = s.drives,
                subtitle = uiStr(R.string.ui_changes_are_saved_with_this_container)
            ) { s.drives = it }
        }

        "Environment" -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            EnvironmentVariablesEditor(s.envVars, onChanged = { s.setEnvironment(it) })
        }

        else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsCard {
                val startupEntries = listOf(
                    uiStr(R.string.ui_load_all_services),
                    uiStr(R.string.ui_load_essential_services),
                    uiStr(R.string.ui_stop_all_services)
                )
                SettingChoice(uiStr(R.string.ui_startup_selection), startupEntries[s.startup.coerceIn(0, 2)], startupEntries) {
                    s.startup = startupEntries.indexOf(it).coerceAtLeast(0)
                }
            }
            SettingsCard {
                SettingToggle(uiStr(R.string.ui_sync_cpu_topology), s.syncCpu) { s.syncCpu = it }
                SettingsDivider()
                CpuSelectorRow(uiStr(R.string.processor_affinity), s.cpu64) { index, checked -> s.cpu64[index] = checked }
                SettingsDivider()
                CpuSelectorRow(uiStr(R.string.ui_processor_affinity_32_bit_apps), s.cpu32) { index, checked -> s.cpu32[index] = checked }
            }
            SettingsCard {
                containerComponentRowsV2.forEachIndexed { index, (key, label) ->
                    val entries = listOf(uiStr(R.string.ui_builtin_wine), uiStr(R.string.ui_native_windows))
                    val selected = entries[(s.components[key] ?: 0).coerceIn(0, 1)]
                    SettingChoice(label, selected, entries) { s.components[key] = entries.indexOf(it).coerceAtLeast(0) }
                    if (index != containerComponentRowsV2.lastIndex) SettingsDivider()
                }
            }
        }
    }
}

@Composable
private fun ContainerVulkanExtensionsV2(
    context: Context,
    driver: String,
    blacklisted: String,
    onChanged: (String) -> Unit
) {
    val extensions = remember(driver) {
        runCatching { GPUInformation.enumerateExtensions(driver, context).toList().sorted() }.getOrDefault(emptyList())
    }
    if (extensions.isEmpty()) {
        SettingText(uiStr(R.string.ui_disabled_vulkan_extensions), blacklisted, 2) { onChanged(it.replace(" ", "")) }
        return
    }
    val disabled = parseExtensionBlacklist(blacklisted)
    val enabledCount = extensions.count { it !in disabled }
    var showDialog by remember { mutableStateOf(false) }
    Surface(
        onClick = { showDialog = true },
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp)) {
            Text(uiStr(R.string.ui_vulkan_extensions), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(uiStr(R.string.ui_of_enabled, enabledCount, extensions.size), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        }
    }
    if (showDialog) {
        VulkanExtensionsDialog(
            extensions = extensions,
            blacklisted = blacklisted,
            onDismiss = { showDialog = false },
            onDone = {
                showDialog = false
                onChanged(it)
            }
        )
    }
}

private fun loadContainerSoundFontsV2(context: Context): List<String> {
    val result = linkedSetOf(uiStr(R.string.disabled), MidiManager.DEFAULT_SF2_FILE)
    MidiManager.getSoundFontDir(context).listFiles()?.filter(File::isFile)?.sortedBy { it.name.lowercase() }?.forEach { result.add(it.name) }
    return result.toList()
}

private fun parseContainerComponentsV2(raw: String): Map<String, Int> {
    val result = mutableMapOf<String, Int>()
    raw.split(',').forEach { token ->
        val split = token.indexOf('=')
        if (split > 0) result[token.substring(0, split)] = token.substring(split + 1).toIntOrNull()?.coerceIn(0, 1) ?: 0
    }
    containerComponentRowsV2.forEach { (key, _) -> result.putIfAbsent(key, 0) }
    return result
}

private fun serializeContainerComponentsV2(values: Map<String, Int>): String = containerComponentRowsV2.joinToString(",") { (key, _) ->
    "$key=${values[key] ?: 0}"
}

private fun containerDesktopThemeValueV2(theme: String, background: String, wallpaperStamp: Long): String {
    val themeId = if (theme.equals("Light", true)) WineThemeManager.Theme.LIGHT else WineThemeManager.Theme.DARK
    val backgroundId = if (background.equals("Solid Color", true)) WineThemeManager.BackgroundType.COLOR else WineThemeManager.BackgroundType.IMAGE
    return if (backgroundId == WineThemeManager.BackgroundType.IMAGE)
        "$themeId,$backgroundId,#0277bd,$wallpaperStamp"
    else
        "$themeId,$backgroundId,#0277bd"
}

