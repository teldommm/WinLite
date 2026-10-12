@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.winlator.cmod.ui.shortcut

import com.winlator.cmod.directaudio.DirectAudioDriver
import com.winlator.cmod.ui.theme.ThemedDialogTitle
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.winlator.cmod.ui.toast.WinToast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Gamepad
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.appcompat.app.AppCompatActivity
import com.winlator.cmod.R
import com.winlator.cmod.XServerDisplayActivity
import com.winlator.cmod.box64.Box64PresetManager
import com.winlator.cmod.container.Container
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.container.SaveProfiles
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.ui.library.HomeShortcuts
import com.winlator.cmod.core.GameSaveManager
import com.winlator.cmod.core.DefaultVersion
import com.winlator.cmod.core.FileUtils
import com.winlator.cmod.core.GPUInformation
import com.winlator.cmod.core.OpenGLDriverDefaults
import com.winlator.cmod.core.StringUtils
import com.winlator.cmod.fexcore.FEXCorePresetManager
import com.winlator.cmod.inputcontrols.InputControlsManager
import com.winlator.cmod.midi.MidiManager
import com.winlator.cmod.ui.settings.CpuSelectorRow
import com.winlator.cmod.ui.settings.DriverOption
import com.winlator.cmod.ui.settings.DxvkAsyncMode
import com.winlator.cmod.ui.settings.EnvironmentVariablesEditor
import com.winlator.cmod.ui.settings.VulkanExtensionsDialog
import com.winlator.cmod.ui.settings.parseExtensionBlacklist
import com.winlator.cmod.ui.settings.DDrawWrapperChoice
import com.winlator.cmod.ui.settings.SettingChoice
import com.winlator.cmod.ui.settings.SettingDriverChoice
import com.winlator.cmod.ui.settings.SettingInstallChoice
import com.winlator.cmod.ui.settings.SettingMappedChoice
import com.winlator.cmod.ui.settings.SettingText
import com.winlator.cmod.ui.settings.SettingToggle
import com.winlator.cmod.ui.settings.SettingsCard
import com.winlator.cmod.ui.settings.SettingsCatalog
import com.winlator.cmod.ui.settings.SettingsDivider
import com.winlator.cmod.ui.settings.WineRuntimeOption
import com.winlator.cmod.ui.settings.dxvkAsyncMode
import com.winlator.cmod.ui.settings.envPut
import com.winlator.cmod.ui.settings.envValue
import com.winlator.cmod.ui.settings.filterDxvkForVkd3d
import com.winlator.cmod.ui.settings.installAdrenoDriver
import com.winlator.cmod.ui.settings.installRuntimeComponent
import com.winlator.cmod.ui.settings.isDxvkCompatibleWithVkd3d
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
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import com.winlator.cmod.ui.theme.WinZShapes
import com.winlator.cmod.ui.theme.hairlineColor
import androidx.compose.material3.TopAppBarDefaults

private val shortcutComponentRowsV2 = listOf(
    "direct3d" to "Direct3D",
    "directsound" to "DirectSound",
    "directmusic" to "DirectMusic",
    "directshow" to "DirectShow",
    "directplay" to "DirectPlay",
    "xaudio" to "XAudio",
    "vcrun2010" to "Visual C++ 2010"
)

private val execArgumentPresetsV2 = listOf(
    "-force-gfx-direct",
    "-force-d3d11-singlethreaded",
    "-force-dx9",
    "-force-d3d9",
    "-force-d3d11",
    "--force-gfx-direct",
    "--force-d3d11-singlethreaded",
    "--force-dx9",
    "--force-d3d9",
    "--force-d3d11",
    "/d3d9"
)

private data class ShortcutCategoryItemV2(val label: String, val icon: ImageVector)

private class ShortcutEditorStateV2(val shortcut: Shortcut) {
    val container: Container = shortcut.container
    val arm64 = container.getWineVersion().contains("arm64ec", true)

    var name by mutableStateOf(shortcut.name)
    var saveProfileMode by mutableStateOf(SaveProfiles.getMode(shortcut))
    var screen by mutableStateOf(normalizeResolution(shortcut.getExtra("screenSize", container.getScreenSize())))
    var renderer by mutableStateOf("Vulkan")
    var presentMode by mutableStateOf(shortcut.getRendererPresentMode())
    var rendererDriver by mutableStateOf(shortcut.getRendererDriverId())
    var filterMode by mutableIntStateOf(shortcut.getRendererFilterMode())
    var surfaceFormat by mutableStateOf(shortcut.getSurfaceFormat())
    var graphicsDriver by mutableStateOf(StringUtils.parseIdentifier(shortcut.getExtra("graphicsDriver", container.getGraphicsDriver())))
    var graphicsWrapper by mutableStateOf(Container.normalizeGraphicsWrapper(
        shortcut.getExtra("graphicsWrapper", container.getGraphicsWrapper())
    ))
    private val defaultDriverVersion = runCatching {
        val context = container.manager.context
        if (GPUInformation.isDriverSupported(DefaultVersion.WRAPPER_ADRENO, context)) DefaultVersion.WRAPPER_ADRENO else DefaultVersion.WRAPPER
    }.getOrDefault(DefaultVersion.WRAPPER)
    var graphicsConfig by mutableStateOf(
        shortcut.getExtra("graphicsDriverConfig", container.getGraphicsDriverConfig()).let { raw ->
            if (readConfig(raw, "version", ';').isBlank()) writeConfig(raw, "version", defaultDriverVersion, ';') else raw
        }
    )
    var driverVersion by mutableStateOf(readConfig(graphicsConfig, "version", ';').ifBlank { defaultDriverVersion })
    var vulkanVersion by mutableStateOf(readConfig(graphicsConfig, "vulkanVersion", ';').ifBlank { "1.3" })
    var maxMemory by mutableStateOf(readConfig(graphicsConfig, "maxDeviceMemory", ';').ifBlank { "0" })
    var graphicsPresentMode by mutableStateOf(readConfig(graphicsConfig, "presentMode", ';').ifBlank { "mailbox" })
    var syncFrame by mutableStateOf(readConfig(graphicsConfig, "syncFrame", ';') == "1")
    var disablePresentWait by mutableStateOf(readConfig(graphicsConfig, "disablePresentWait", ';') == "1")
    var resourceType by mutableStateOf(readConfig(graphicsConfig, "resourceType", ';').ifBlank { "auto" })
    var bcn by mutableStateOf(readConfig(graphicsConfig, "bcnEmulation", ';').ifBlank { "auto" })
    var bcnType by mutableStateOf(readConfig(graphicsConfig, "bcnEmulationType", ';').ifBlank { "compute" })
    var bcnCache by mutableStateOf(readConfig(graphicsConfig, "bcnEmulationCache", ';') == "1")
    var gpuName by mutableStateOf(readConfig(graphicsConfig, "gpuName", ';').ifBlank { "Device" })
    var blacklistedExtensions by mutableStateOf(readConfig(graphicsConfig, "blacklistedExtensions", ';'))

    var audio by mutableStateOf(StringUtils.parseIdentifier(shortcut.getExtra("audioDriver", container.getAudioDriver())))
    var oboeProfile by mutableStateOf(shortcut.getExtra("oboeProfile", container.getExtra("oboeProfile", "low")))
    var oboeApi by mutableStateOf(shortcut.getExtra("oboeApi", container.getExtra("oboeApi", "auto")))
    var oboeAdaptive by mutableStateOf(shortcut.getExtra("oboeAdaptive", container.getExtra("oboeAdaptive", "1")) != "0")
    var oboeExclusive by mutableStateOf(shortcut.getExtra("oboeExclusive", container.getExtra("oboeExclusive", "0")) == "1")
    var directAudioMic by mutableStateOf(DirectAudioDriver.isMicEnabled(
        shortcut.getExtra(DirectAudioDriver.EXTRA_MIC, container.getExtra(DirectAudioDriver.EXTRA_MIC, "0"))))
    var wrapper by mutableStateOf(StringUtils.parseIdentifier(shortcut.getExtra("dxwrapper", container.getDXWrapper())))
    var wrapperConfig by mutableStateOf(shortcut.getExtra("dxwrapperConfig", container.getDXWrapperConfig()))
    var dxvkVersion by mutableStateOf(readConfig(wrapperConfig, "version", ',').ifBlank { DefaultVersion.DXVK })
    var vkd3dVersion by mutableStateOf(readConfig(wrapperConfig, "vkd3dVersion", ',').ifBlank { DefaultVersion.VKD3D })
    var vkd3dLevel by mutableStateOf(readConfig(wrapperConfig, "vkd3dLevel", ',').ifBlank { "12_1" })
    var frameRate by mutableStateOf(readConfig(wrapperConfig, "framerate", ',').ifBlank { "0" })
    var maxFrameLatency by mutableStateOf(readConfig(wrapperConfig, "maxFrameLatency", ',') == "1")
    var async by mutableStateOf(readConfig(wrapperConfig, "async", ',') == "1")
    var asyncCache by mutableStateOf(readConfig(wrapperConfig, "asyncCache", ',') == "1")
    var ddrawWrapper by mutableStateOf(readConfig(wrapperConfig, "ddrawrapper", ',').ifBlank { "wined3d" })
    var csmt by mutableStateOf(readConfig(wrapperConfig, "csmt", ',') != "0")
    var strictShaderMath by mutableStateOf(readConfig(wrapperConfig, "strict_shader_math", ',') == "1")
    var offscreenMode by mutableStateOf(readConfig(wrapperConfig, "OffscreenRenderingMode", ',').ifBlank { "fbo" })
    var wineRenderer by mutableStateOf(readConfig(wrapperConfig, "renderer", ',').ifBlank { "vulkan" })
    var videoMemory by mutableStateOf(readConfig(wrapperConfig, "videoMemorySize", ',').ifBlank { "2048" })

    private val emulatorRaw = StringUtils.parseIdentifier(shortcut.getExtra("emulator", container.getEmulator()))
    var emulator by mutableStateOf(if (!arm64) "Box64" else if (emulatorRaw.equals("FEXCore", true)) "FEXCore" else "WOWBox64")
    var fexVersion by mutableStateOf(shortcut.getExtra("fexcoreVersion", container.getFEXCoreVersion().orEmpty()))
    var boxVersion by mutableStateOf(shortcut.getExtra("box64Version", container.getBox64Version().orEmpty()))
    var fexPreset by mutableStateOf(shortcut.getExtra("fexcorePreset", container.getFEXCorePreset()))
    var boxPreset by mutableStateOf(shortcut.getExtra("box64Preset", container.getBox64Preset()))

    var controlsProfile by mutableStateOf(shortcut.getExtra("controlsProfile", "0"))
    var fullscreen by mutableStateOf(
        shortcut.getExtra("fullscreenStretched").let { if (it.isBlank()) container.isFullscreenStretched() else it == "1" }
    )
    private var inputType by mutableIntStateOf(shortcut.getExtra("inputType", container.getInputType().toString()).toIntOrNull() ?: container.getInputType())
    var exclusive by mutableStateOf(shortcut.getExtra("exclusiveXInput").let { if (it.isBlank()) container.isExclusiveXInput() else it == "1" })
    var xinput by mutableStateOf((inputType and WinHandler.FLAG_INPUT_TYPE_XINPUT.toInt()) != 0)
    var dinput by mutableStateOf((inputType and WinHandler.FLAG_INPUT_TYPE_DINPUT.toInt()) != 0)
    var relativeMouse by mutableStateOf(shortcut.getExtra("enableRelativeMouse", "0") == "1")
    // Touch Mode: 0 Trackpad, 1 Touchscreen, 2 Off. Stored in the existing keys so old shortcuts
    // (and older app versions) read the same thing: "disableMouse" = 1 means Off, otherwise
    // "simTouchScreen" = 1 means Touchscreen. Off leaves simTouchScreen alone.
    var touchMode by mutableIntStateOf(
        when {
            shortcut.getExtra("disableMouse", "0") == "1" -> 2
            shortcut.getExtra("simTouchScreen", "0") == "1" -> 1
            else -> 0
        }
    )
    // On by default; stored only when turned off, like the other mouse toggles.
    var tapToClick by mutableStateOf(shortcut.getExtra("tapToClick", "1") != "0")
    var syncCpu by mutableStateOf(shortcut.getExtra("syncCpuTopology", if (container.isSyncCpuTopology()) "1" else "0") == "1")
    val cpu = mutableStateListOf<Boolean>().apply {
        val count = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val selected = shortcut.getExtra("cpuList", container.getCPUList(true)).split(',').map(String::trim).toSet()
        addAll(List(count) { it.toString() in selected })
    }

    var startup by mutableIntStateOf(shortcut.getExtra("startupSelection", container.getStartupSelection().toString()).toIntOrNull()?.coerceIn(0, 2) ?: 0)
    var sharpnessEffect by mutableStateOf(shortcut.getExtra("sharpnessEffect", "None"))
    var sharpnessLevel by mutableStateOf(shortcut.getExtra("sharpnessLevel", "100"))
    var sharpnessDenoise by mutableStateOf(shortcut.getExtra("sharpnessDenoise", "100"))
    var lcAll by mutableStateOf(shortcut.getExtra("lc_all", container.getLC_ALL()))
    var midiSoundFont by mutableStateOf(shortcut.getExtra("midiSoundFont", container.getMIDISoundFont()))
    var execArgs by mutableStateOf(shortcut.getExtra("execArgs"))
    var autoMesaGlVersionOverride by mutableStateOf(
        shortcut.getExtra(
            "autoMesaGlVersionOverride",
            container.getExtra("autoMesaGlVersionOverride", "0")
        ) == "1"
    )
    // What the game actually gets: the shortcut's own list, or the container's one until the
    // shortcut is edited (then it is saved as the shortcut's own list).
    var envVars by mutableStateOf(shortcut.getEffectiveEnvVars(container))
    var hasOwnEnvironment by mutableStateOf(shortcut.hasOwnEnvVars())
    var winlatorHudMode by mutableIntStateOf(
        shortcut.getExtra("hudMode", container.getExtra("hudMode", if (container.isShowFPS) "1" else "0")).toIntOrNull() ?: 0
    )
    var dxvkHud by mutableStateOf(!envValue(envVars, "DXVK_HUD").isNullOrBlank())
    var renderingMode by mutableStateOf(renderingModeOf(envVars))
    val components = mutableStateMapOf<String, Int>().apply {
        putAll(parseShortcutComponentsV2(shortcut.getExtra("wincomponents", container.getWinComponents())))
    }

    var revision by mutableIntStateOf(0)
    var installing by mutableStateOf<Set<String>>(emptySet())

    private fun save() = shortcut.saveData()

    // Environment edits arrive on every keystroke; writing the shortcut file each time is wasteful
    // (main-thread disk I/O), so it is written 500 ms after the last change. flushEnvironmentSave()
    // is called before anything that reads the file or moves it (launch, rename, container change).
    private val envSaveHandler = Handler(Looper.getMainLooper())
    private var envSavePending = false
    private val envSaveRunnable = Runnable {
        envSavePending = false
        save()
    }

    private fun persistEnvironment(debounced: Boolean) {
        shortcut.setOwnEnvVars(envVars)
        hasOwnEnvironment = true
        envSaveHandler.removeCallbacks(envSaveRunnable)
        if (debounced) {
            envSavePending = true
            envSaveHandler.postDelayed(envSaveRunnable, 500)
        } else {
            envSavePending = false
            save()
        }
    }

    fun flushEnvironmentSave() {
        envSaveHandler.removeCallbacks(envSaveRunnable)
        if (envSavePending) {
            envSavePending = false
            save()
        }
    }

    fun resetEnvironmentToContainer() {
        envSaveHandler.removeCallbacks(envSaveRunnable)
        envSavePending = false
        shortcut.resetEnvVarsToContainer()
        shortcut.putExtra("autoMesaGlVersionOverride", null)
        autoMesaGlVersionOverride = container.getExtra("autoMesaGlVersionOverride", "0") == "1"
        envVars = container.getEnvVars()
        dxvkHud = !envValue(envVars, "DXVK_HUD").isNullOrBlank()
        renderingMode = renderingModeOf(envVars)
        hasOwnEnvironment = false
        save()
    }

    fun extra(key: String, value: String?) {
        shortcut.putExtra(key, value)
        save()
    }

    fun graphics(key: String, value: String) {
        graphicsConfig = writeConfig(graphicsConfig, key, value, ';')
        shortcut.putExtra("graphicsDriverConfig", graphicsConfig)
        save()
    }

    fun wrapperValue(key: String, value: String) {
        wrapperConfig = writeConfig(wrapperConfig, key, value, ',')
        shortcut.putExtra("dxwrapperConfig", wrapperConfig)
        save()
    }

    fun selectDxvkVersion(version: String) {
        dxvkVersion = version
        wrapperValue("version", version)
        when (dxvkAsyncMode(version)) {
            DxvkAsyncMode.NONE -> {
                async = false; asyncCache = false
                wrapperValue("async", "0"); wrapperValue("asyncCache", "0")
            }
            DxvkAsyncMode.ASYNC -> {
                async = true; asyncCache = false
                wrapperValue("async", "1"); wrapperValue("asyncCache", "0")
            }
            DxvkAsyncMode.GPL_ASYNC -> {
                async = true; asyncCache = true
                wrapperValue("async", "1"); wrapperValue("asyncCache", "1")
            }
        }
    }

    fun setEnvironment(raw: String) {
        // Keep the dedicated toggles in sync when the same variables are edited in the
        // Environment tab (removing DXVK_HUD there used to leave the "DXVK HUD" switch on).
        dxvkHud = !envValue(raw, "DXVK_HUD").isNullOrBlank()
        renderingMode = renderingModeOf(raw)
        if (autoMesaGlVersionOverride && envValue(raw, "MESA_GL_VERSION_OVERRIDE") != "3.3") {
            autoMesaGlVersionOverride = false
            shortcut.putExtra("autoMesaGlVersionOverride", "0")
        }
        envVars = raw
        // Stored even when empty: "no variables" is a valid choice and must not fall back to the
        // container's list (putExtra(null) used to bring the deleted variables back).
        persistEnvironment(debounced = true)
    }

    fun selectGraphicsDriver(value: String) {
        graphicsDriver = value
        shortcut.putExtra("graphicsDriver", value)
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
        shortcut.putExtra("autoMesaGlVersionOverride", if (autoMesaGlVersionOverride) "1" else "0")
        persistEnvironment(debounced = false)
    }

    fun syncOpenGlEnvironment() {
        if (graphicsDriver.equals("freedreno", true) &&
            envValue(envVars, "MESA_GL_VERSION_OVERRIDE").isNullOrBlank()
        ) {
            envVars = envPut(envVars, "MESA_GL_VERSION_OVERRIDE", "3.3")
            autoMesaGlVersionOverride = true
            shortcut.putExtra("autoMesaGlVersionOverride", "1")
            persistEnvironment(debounced = false)
        }
    }

    fun updateDxvkHud(enabled: Boolean) {
        dxvkHud = enabled
        setEnvironment(envPut(envVars, "DXVK_HUD", if (enabled) "fps,devinfo,gpuload,version" else null))
    }

    fun applyRenderingMode(mode: String) {
        renderingMode = mode
        // Only swap the sysmem/gmem flag; other TU_DEBUG flags the user set (noconform, nolrz, ...)
        // used to be wiped whenever a rendering mode was picked.
        val otherFlags = envValue(envVars, "TU_DEBUG").orEmpty().split(',')
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.equals("sysmem", true) && !it.equals("gmem", true) }
        val flags = when (mode) {
            "Sysmem" -> otherFlags + "sysmem"
            "Gmem" -> otherFlags + "gmem"
            else -> otherFlags
        }
        var next = envPut(envVars, "TU_DEBUG", flags.joinToString(",").ifEmpty { null })
        next = envPut(next, "TU_AUTOTUNE_ALGO", if (mode == "Autotuner Profiled") "profiled" else null)
        setEnvironment(next)
    }

    fun saveRenderer() {
        shortcut.setRendererPresentMode(presentMode)
        shortcut.setRendererDriverId(rendererDriver)
        shortcut.setRendererFilterMode(filterMode)
        shortcut.setSurfaceFormat(surfaceFormat)
        save()
    }

    fun saveInput() {
        inputType = 0
        if (xinput) inputType = inputType or WinHandler.FLAG_INPUT_TYPE_XINPUT.toInt()
        if (dinput) inputType = inputType or WinHandler.FLAG_INPUT_TYPE_DINPUT.toInt()
        shortcut.putExtra("inputType", inputType.toString())
        shortcut.putExtra("exclusiveXInput", if (exclusive) "1" else "0")
        save()
    }

    fun saveCpu() {
        shortcut.putExtra("cpuList", cpu.indices.filter { cpu[it] }.joinToString(","))
        shortcut.putExtra("syncCpuTopology", if (syncCpu) "1" else "0")
        save()
    }

    fun saveComponents() {
        shortcut.putExtra("wincomponents", shortcutComponentRowsV2.joinToString(",") { (key, _) -> "$key=${components[key] ?: 0}" })
        save()
    }
}

@Composable
internal fun ShortcutEditorV2(
    activity: AppCompatActivity,
    shortcut: Shortcut,
    onShortcutsChanged: () -> Unit,
    close: () -> Unit,
    onMoved: ((String) -> Unit)? = null
) {
    val context: android.content.Context = activity
    val dialogView = LocalView.current
    val state = remember(shortcut.file.path) {
        OpenGLDriverDefaults.initialize(context, shortcut.container)
        ShortcutEditorStateV2(shortcut)
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(shortcut.file.path) {
        state.syncOpenGlEnvironment()
    }
    DisposableEffect(state) {
        onDispose { state.flushEnvironmentSave() }
    }
    val manager = remember { ContainerManager(context) }
    val containers = remember { manager.containers.toList() }

    val runtimeOptions by produceState(cachedWineRuntimeOptions()) {
        value = loadWineRuntimeOptions(context)
    }
    fun environmentLabel(container: Container): String {
        val runtime = runtimeOptions.firstOrNull { it.id == container.wineVersion }?.label
            ?: container.wineVersion
        return "${container.name} · $runtime"
    }

    val screenEntries = remember { context.resources.getStringArray(R.array.screen_size_entries).toList() }
    val graphicsEntries = remember { context.resources.getStringArray(R.array.graphics_driver_entries).toList() }
    val graphicsWrapperEntries = remember { context.resources.getStringArray(R.array.graphics_wrapper_entries).toList() }
    val audioEntries = remember { context.resources.getStringArray(R.array.audio_driver_entries).toList() }
    val wrapperEntries = remember { context.resources.getStringArray(R.array.dxwrapper_entries).toList() }
    val localeEntries = remember { listOf("Default") + context.resources.getStringArray(R.array.some_lc_all).toList() }
    val soundFonts = remember { loadShortcutSoundFontsV2(context) }
    val gpuNames = remember { loadShortcutGpuNamesV2(context) }
    val fexPresets = remember { FEXCorePresetManager.getPresets(context).associate { it.id to it.name } }
    val boxPresets = remember { Box64PresetManager.getPresets("box64", context).associate { it.id to it.name } }
    val profiles = remember {
        linkedMapOf("0" to "None").apply {
            InputControlsManager(context).getProfiles(true).forEach { put(it.id.toString(), it.name) }
        }
    }
    // Starts from the cached/local catalog so rows don't pop in (see initialSettingsCatalog).
    val initialCatalog = remember(state.arm64) {
        initialSettingsCatalog(
            context, state.arm64, state.dxvkVersion, state.vkd3dVersion,
            state.fexVersion, state.boxVersion, state.driverVersion
        )
    }
    val catalog by produceState<SettingsCatalog?>(initialCatalog, state.arm64, state.revision, state.dxvkVersion, state.vkd3dVersion, state.driverVersion) {
        value = loadSettingsCatalog(
            context, state.arm64, state.dxvkVersion, state.vkd3dVersion,
            state.fexVersion, state.boxVersion, state.driverVersion
        )
    }

    fun installRuntime(type: String, version: String, done: (String) -> Unit) {
        val key = "$type:$version"
        if (key in state.installing) return
        state.installing = state.installing + key
        scope.launch {
            val outcome = installRuntimeComponent(context, type, version)
            val installed = outcome.id
            state.installing = state.installing - key
            if (installed == null) WinToast.show(context, outcome.failureText("Unable to install $version"), Toast.LENGTH_SHORT, dialogView)
            else {
                done(installed)
                state.revision++
                WinToast.show(context, "$version installed", Toast.LENGTH_SHORT, dialogView)
            }
        }
    }

    fun installDriver(option: DriverOption) {
        val key = "driver:${option.remoteUrl ?: option.id}"
        if (key in state.installing) return
        state.installing = state.installing + key
        scope.launch {
            val outcome = installAdrenoDriver(context, option)
            val installed = outcome.id
            state.installing = state.installing - key
            if (installed == null) WinToast.show(context, outcome.failureText("Unable to install ${option.label}"), Toast.LENGTH_SHORT, dialogView)
            else {
                state.driverVersion = installed
                state.graphics("version", installed)
                state.revision++
                WinToast.show(context, "${option.label} installed", Toast.LENGTH_SHORT, dialogView)
            }
        }
    }

    fun closeEditor() {
        state.flushEnvironmentSave()
        val rename = renameShortcutV2(shortcut, state.name)
        if (rename is ShortcutRenameResult.Renamed) {
            // The save profile folder follows the shortcut's name (it is found by id, not by name).
            val newName = rename.newName
            val oldName = shortcut.name
            Thread {
                SaveProfiles.onShortcutRenamed(shortcut, newName)
                GameSaveManager.onShortcutRenamed(oldName, newName)
            }.start()
        }
        onShortcutsChanged()
        close()
        when (rename) {
            is ShortcutRenameResult.Renamed ->
                WinToast.show(context, "Renamed to \"${rename.newName}\"", Toast.LENGTH_SHORT)
            ShortcutRenameResult.NameTaken ->
                WinToast.show(context, "Couldn't rename: that name is already used", Toast.LENGTH_LONG)
            ShortcutRenameResult.Failed ->
                WinToast.show(context, "Couldn't rename the shortcut", Toast.LENGTH_LONG)
            ShortcutRenameResult.Unchanged -> Unit
        }
    }

    fun enterContainer() {
        state.flushEnvironmentSave()
        activity.startActivity(Intent(activity, XServerDisplayActivity::class.java).putExtra("container_id", state.container.id))
    }

    // Switches the game to another container (moves the shortcut; a copy is the Library's Clone).
    fun changeContainer(targetId: Int) {
        val target = containers.firstOrNull { it.id == targetId } ?: return
        if (target.id == state.container.id) return
        state.flushEnvironmentSave()
        if (File(target.desktopDir, shortcut.file.name).exists()) {
            WinToast.show(context, "${target.name} already has a shortcut named \"${shortcut.name}\"", Toast.LENGTH_LONG, dialogView)
            return
        }
        val moved = shortcut.moveToContainer(target)
        if (moved == null) {
            WinToast.show(context, "Couldn't move the shortcut to ${target.name}", Toast.LENGTH_LONG, dialogView)
            return
        }
        HomeShortcuts.updatePinned(context, Shortcut(target, moved))
        onShortcutsChanged()
        close()
        WinToast.show(context, "Moved to ${target.name}", Toast.LENGTH_SHORT)
        onMoved?.invoke(moved.path)
    }

    var category by remember { mutableStateOf("General") }
    val categories = listOf(
        ShortcutCategoryItemV2("General", Icons.Outlined.Settings),
        ShortcutCategoryItemV2("Video", Icons.Outlined.Monitor),
        ShortcutCategoryItemV2("Compatibility", Icons.Outlined.Tune),
        ShortcutCategoryItemV2("Input", Icons.Outlined.Gamepad),
        ShortcutCategoryItemV2("Environment", Icons.Outlined.Terminal),
        ShortcutCategoryItemV2("Advanced", Icons.Outlined.Terminal)
    )
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp

    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(state.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = ::closeEditor) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null) } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        if (landscape) {
            Row(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    Modifier.width(220.dp).fillMaxHeight(),
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
                                "Shortcut settings",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                        items(categories) { item ->
                            ShortcutNavItemV2(item.label, item.icon, category == item.label) { category = item.label }
                        }
                    }
                }
                LazyColumn(
                    Modifier.weight(1f).fillMaxHeight(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(category) {
                        ShortcutCategoryV2(
                            category, state, catalog, screenEntries, graphicsEntries, graphicsWrapperEntries, audioEntries,
                            wrapperEntries, localeEntries, soundFonts, gpuNames, fexPresets, boxPresets,
                            profiles, containers, ::environmentLabel, ::changeContainer, ::enterContainer,
                            ::installRuntime, ::installDriver, context
                        )
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(padding)) {
                LazyRow(
                    Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(categories) { item ->
                        ShortcutNavItemV2(item.label, item.icon, category == item.label) { category = item.label }
                    }
                }
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 14.dp),
                    contentPadding = PaddingValues(bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(category) {
                        ShortcutCategoryV2(
                            category, state, catalog, screenEntries, graphicsEntries, graphicsWrapperEntries, audioEntries,
                            wrapperEntries, localeEntries, soundFonts, gpuNames, fexPresets, boxPresets,
                            profiles, containers, ::environmentLabel, ::changeContainer, ::enterContainer,
                            ::installRuntime, ::installDriver, context
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShortcutNavItemV2(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        shape = WinZShapes.Small,
        color = if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(icon, null, modifier = Modifier.size(16.dp))
            Text(
                label,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun ShortcutCategoryV2(
    category: String,
    s: ShortcutEditorStateV2,
    catalog: SettingsCatalog?,
    screenEntries: List<String>,
    graphicsEntries: List<String>,
    graphicsWrapperEntries: List<String>,
    audioEntries: List<String>,
    wrapperEntries: List<String>,
    localeEntries: List<String>,
    soundFonts: List<String>,
    gpuNames: List<String>,
    fexPresets: Map<String, String>,
    boxPresets: Map<String, String>,
    profiles: Map<String, String>,
    containers: List<Container>,
    environmentLabel: (Container) -> String,
    changeContainer: (Int) -> Unit,
    enterContainer: () -> Unit,
    installRuntime: (String, String, (String) -> Unit) -> Unit,
    installDriver: (DriverOption) -> Unit,
    context: Context
) {
    when (category) {
        "General" -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsCard { SettingText("Name", s.name) { s.name = it } }
            SettingsCard {
                val currentLabel = environmentLabel(s.container)
                if (containers.size > 1) {
                    val labels = containers.associate { it.id to environmentLabel(it) }
                    SettingChoice("Environment", currentLabel, labels.values.toList()) { selected ->
                        labels.entries.firstOrNull { it.value == selected }?.key?.let(changeContainer)
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Environment", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(currentLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                SettingsDivider()
                // Off: saves stay in the container. Own: Winlator/saves/<Game>. Shared: Winlator/saves/_Common.
                val saveProfileLabels = linkedMapOf(
                    SaveProfiles.MODE_OFF to "Off",
                    SaveProfiles.MODE_OWN to "Own folder",
                    SaveProfiles.MODE_SHARED to "Shared (_Common)"
                )
                SettingChoice("Save profile", saveProfileLabels[s.saveProfileMode] ?: "Off", saveProfileLabels.values.toList()) { selected ->
                    val mode = saveProfileLabels.entries.firstOrNull { it.value == selected }?.key ?: SaveProfiles.MODE_OFF
                    s.saveProfileMode = mode
                    SaveProfiles.setMode(s.shortcut, mode)
                }
                SettingsDivider()
                Button(
                    onClick = enterContainer,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = controlAccentColor(), contentColor = Color.White)
                ) {
                    Icon(Icons.Outlined.PlayArrow, null)
                    Spacer(Modifier.size(7.dp))
                    Text("Enter container")
                }
            }
            SettingsCard {
                val visibleAudioEntries = DirectAudioDriver.audioEntriesFor(audioEntries, s.container.getWineVersion())
                SettingChoice("Audio Driver", visibleAudioEntries.firstOrNull { StringUtils.parseIdentifier(it).equals(s.audio, true) } ?: s.audio, visibleAudioEntries) {
                    s.audio = StringUtils.parseIdentifier(it); s.extra("audioDriver", s.audio)
                }
                if (DirectAudioDriver.isSelected(s.audio)) {
                    SettingsDivider()
                    SettingToggle("Microphone", s.directAudioMic) {
                        s.directAudioMic = it
                        s.extra(DirectAudioDriver.EXTRA_MIC, if (it) "1" else "0")
                        if (it) DirectAudioDriver.requestMicPermission(context)
                    }
                }
                if (s.audio == "oboe") {
                    SettingsDivider()
                    val latencyLabel = when (s.oboeProfile) {
                        "ultra" -> "Low Latency"
                        "stable" -> "Stable"
                        else -> "Automatic"
                    }
                    SettingChoice("Oboe latency", latencyLabel, listOf("Automatic", "Low Latency", "Stable")) {
                        s.oboeProfile = when (it) {
                            "Low Latency" -> "ultra"
                            "Stable" -> "stable"
                            else -> "low"
                        }
                        s.extra("oboeProfile", s.oboeProfile)
                    }
                    SettingsDivider()
                    val apiLabel = when (s.oboeApi) {
                        "aaudio" -> "AAudio"
                        "opensles" -> "OpenSL ES"
                        else -> "Automatic"
                    }
                    SettingChoice("Oboe backend", apiLabel, listOf("Automatic", "AAudio", "OpenSL ES")) {
                        s.oboeApi = when (it) {
                            "AAudio" -> "aaudio"
                            "OpenSL ES" -> "opensles"
                            else -> "auto"
                        }
                        s.extra("oboeApi", s.oboeApi)
                    }
                }
                SettingsDivider()
                SettingToggle("Fullscreen Stretched", s.fullscreen) {
                    s.fullscreen = it; s.extra("fullscreenStretched", if (it) "1" else "0")
                }
            }
            SettingsCard {
                val hudEntries = listOf("Off", "Classic", "Modern")
                SettingChoice("Winlator HUD", hudEntries.getOrElse(s.winlatorHudMode) { "Off" }, hudEntries) {
                    s.winlatorHudMode = hudEntries.indexOf(it).coerceAtLeast(0)
                    s.extra("hudMode", s.winlatorHudMode.toString())
                }
                SettingsDivider()
                SettingToggle("DXVK HUD", s.dxvkHud) { s.updateDxvkHud(it) }
            }
            SettingsCard {
                val locale = localeDisplayValue(s.lcAll)
                SettingChoice("Locale (LC_ALL)", locale, localeEntries) {
                    s.lcAll = normalizeLocaleValue(it)
                    s.extra("lc_all", s.lcAll.ifBlank { null })
                }
                SettingsDivider()
                val sound = s.midiSoundFont.ifBlank { "Disabled" }
                SettingChoice("MIDI SoundFont", sound, soundFonts) {
                    s.midiSoundFont = if (it == "Disabled") "" else it; s.extra("midiSoundFont", s.midiSoundFont)
                }
            }
        }

        "Video" -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsCard {
                var customScreenSelected by remember {
                    mutableStateOf(screenEntries.none { normalizeResolution(it).equals(s.screen, true) })
                }
                val screenChoice = if (customScreenSelected) "Custom"
                else screenEntries.firstOrNull { normalizeResolution(it).equals(s.screen, true) } ?: "Custom"
                SettingChoice("Screen Size", screenChoice, screenEntries) {
                    if (it.equals("Custom", true)) {
                        customScreenSelected = true
                    } else {
                        customScreenSelected = false
                        s.screen = normalizeResolution(it)
                        s.extra("screenSize", s.screen)
                    }
                }
                if (screenChoice == "Custom") {
                    SettingsDivider()
                    SettingText("Custom resolution", s.screen) { value ->
                        s.screen = value
                        if (Regex("\\d{2,5}x\\d{2,5}").matches(value.trim())) s.extra("screenSize", normalizeResolution(value))
                    }
                }
                SettingsDivider()
                SettingChoice("Renderer", s.renderer, listOf("Vulkan")) {
                    s.renderer = it
                    s.saveRenderer()
                }
                SettingsDivider()
                SettingChoice(
                    "Surface format",
                    if (s.surfaceFormat == "bgra8") "BGRA" else "RGBA",
                    listOf("RGBA", "BGRA")
                ) {
                    s.surfaceFormat = if (it == "BGRA") "bgra8" else "rgba8"
                    s.saveRenderer()
                }
                SettingsDivider()
                SettingChoice("Present Mode", s.presentMode, listOf("mailbox", "fifo")) {
                    s.presentMode = it
                    s.saveRenderer()
                }
                catalog?.let { c ->
                    SettingsDivider()
                    SettingMappedChoice("Renderer Driver", s.rendererDriver, c.rendererDrivers) {
                        s.rendererDriver = it
                        s.saveRenderer()
                    }
                }
                SettingsDivider()
                val filters = listOf("Bilinear", "Nearest neighbor", "Snapdragon Super Resolution", "AMD FidelityFX Super Resolution", "Lanczos 2 (16-tap)", "Color Boost", "Snapdragon Super Resolution HQ")
                SettingChoice("Texture Filter", filters.getOrElse(s.filterMode) { filters.first() }, filters) {
                    s.filterMode = filters.indexOf(it).coerceAtLeast(0)
                    s.saveRenderer()
                }
            }
            SettingsCard {
                SettingChoice("Graphics Driver", graphicsEntries.firstOrNull { StringUtils.parseIdentifier(it).equals(s.graphicsDriver, true) } ?: s.graphicsDriver, graphicsEntries) {
                    s.selectGraphicsDriver(StringUtils.parseIdentifier(it))
                }
                SettingsDivider()
                SettingChoice(
                    "Vulkan Wrapper",
                    graphicsWrapperEntries.firstOrNull {
                        Container.normalizeGraphicsWrapper(StringUtils.parseIdentifier(it)).equals(s.graphicsWrapper, true)
                    } ?: s.graphicsWrapper,
                    graphicsWrapperEntries
                ) {
                    s.graphicsWrapper = Container.normalizeGraphicsWrapper(StringUtils.parseIdentifier(it))
                    s.extra("graphicsWrapper", s.graphicsWrapper)
                }
                catalog?.let { c ->
                    SettingsDivider()
                    SettingDriverChoice("Driver Version", s.driverVersion, c.drivers, s.installing, installDriver) {
                        s.driverVersion = it; s.graphics("version", it)
                    }
                }
                SettingsDivider()
                ShortcutVulkanExtensionsV2(context, s.driverVersion, s.blacklistedExtensions) {
                    s.blacklistedExtensions = it; s.graphics("blacklistedExtensions", it)
                }
                if (isTurnipDriver(s.driverVersion)) {
                    SettingsDivider()
                    SettingChoice("Rendering Mode", s.renderingMode, listOf("None", "Sysmem", "Gmem", "Autotuner Profiled")) { s.applyRenderingMode(it) }
                }
                SettingsDivider()
                SettingChoice("Vulkan Version", s.vulkanVersion, listOf("1.1", "1.2", "1.3", "1.4")) { s.vulkanVersion = it; s.graphics("vulkanVersion", it) }
                SettingsDivider()
                SettingChoice("GPU Name", s.gpuName, gpuNames) { s.gpuName = it; s.graphics("gpuName", it) }
                SettingsDivider()
                SettingChoice("Max Device Memory", s.maxMemory, listOf("0", "512", "1024", "2048", "4096", "8192", "12288", "16384")) {
                    s.maxMemory = it; s.graphics("maxDeviceMemory", it)
                }
                SettingsDivider()
                SettingChoice("Driver Present Mode", s.graphicsPresentMode, listOf("mailbox", "fifo", "immediate", "relaxed")) {
                    s.graphicsPresentMode = it; s.graphics("presentMode", it)
                }
                SettingsDivider()
                SettingToggle("Sync Frame", s.syncFrame) { s.syncFrame = it; s.graphics("syncFrame", if (it) "1" else "0") }
                SettingsDivider()
                SettingToggle("Disable Present Wait", s.disablePresentWait) { s.disablePresentWait = it; s.graphics("disablePresentWait", if (it) "1" else "0") }
                SettingsDivider()
                SettingChoice("Resource Type", s.resourceType, listOf("auto", "dmabuf", "ahb", "opaque")) { s.resourceType = it; s.graphics("resourceType", it) }
                SettingsDivider()
                SettingChoice("BCN Emulation", s.bcn, listOf("none", "partial", "full", "auto")) { s.bcn = it; s.graphics("bcnEmulation", it) }
                SettingsDivider()
                SettingChoice("BCN Emulation Type", s.bcnType, listOf("software", "compute")) { s.bcnType = it; s.graphics("bcnEmulationType", it) }
                SettingsDivider()
                SettingToggle("BCN Emulation Cache", s.bcnCache) { s.bcnCache = it; s.graphics("bcnEmulationCache", if (it) "1" else "0") }
            }
        }

        "Compatibility" -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsCard {
                val shown = wrapperEntries.firstOrNull { StringUtils.parseIdentifier(it).equals(s.wrapper, true) } ?: s.wrapper
                SettingChoice("DX Wrapper", shown, wrapperEntries) { s.wrapper = StringUtils.parseIdentifier(it); s.extra("dxwrapper", s.wrapper) }
                if (s.wrapper.contains("dxvk", true)) {
                    catalog?.let { c ->
                        val dxvkCatalog = filterDxvkForVkd3d(c.dxvk, s.vkd3dVersion)
                        SettingsDivider()
                        SettingInstallChoice("DXVK Version", s.dxvkVersion, dxvkCatalog, s.installing, "DXVK",
                            { v -> installRuntime("DXVK", v) { installed -> s.selectDxvkVersion(installed) } }) { s.selectDxvkVersion(it) }
                        SettingsDivider()
                        SettingInstallChoice("VKD3D Version", s.vkd3dVersion, c.vkd3d, s.installing, "VKD3D",
                            { v -> installRuntime("VKD3D", v) { installed ->
                                s.vkd3dVersion = installed
                                s.wrapperValue("vkd3dVersion", installed)
                                if (isVkd3dEnabled(installed) && !isDxvkCompatibleWithVkd3d(s.dxvkVersion)) {
                                    c.dxvk.all.firstOrNull { candidate -> isDxvkCompatibleWithVkd3d(candidate) && candidate in c.dxvk.installed }?.let(s::selectDxvkVersion)
                                }
                            } }) {
                            s.vkd3dVersion = it
                            s.wrapperValue("vkd3dVersion", it)
                            if (isVkd3dEnabled(it) && !isDxvkCompatibleWithVkd3d(s.dxvkVersion)) {
                                c.dxvk.all.firstOrNull { candidate -> isDxvkCompatibleWithVkd3d(candidate) && candidate in c.dxvk.installed }?.let(s::selectDxvkVersion)
                            }
                        }
                    }
                    SettingsDivider()
                    SettingChoice("VKD3D Feature Level", s.vkd3dLevel, listOf("12_0", "12_1", "12_2", "11_1", "11_0", "10_1", "10_0", "9_3", "9_2", "9_1")) { s.vkd3dLevel = it; s.wrapperValue("vkd3dLevel", it) }
                    SettingsDivider()
                    SettingText("Frame Rate", s.frameRate) { s.frameRate = it.filter(Char::isDigit).take(4); s.wrapperValue("framerate", s.frameRate.ifBlank { "0" }) }
                    SettingsDivider()
                    SettingToggle("Max Frame Latency", s.maxFrameLatency) { s.maxFrameLatency = it; s.wrapperValue("maxFrameLatency", if (it) "1" else "0") }
                    val asyncMode = dxvkAsyncMode(s.dxvkVersion)
                    if (asyncMode != DxvkAsyncMode.NONE) {
                        SettingsDivider()
                        SettingToggle("Async", s.async) { s.async = it; s.wrapperValue("async", if (it) "1" else "0") }
                    }
                    if (asyncMode == DxvkAsyncMode.GPL_ASYNC) {
                        SettingsDivider()
                        SettingToggle("Async Cache", s.asyncCache) { s.asyncCache = it; s.wrapperValue("asyncCache", if (it) "1" else "0") }
                    }
                    SettingsDivider()
                    DDrawWrapperChoice(s.ddrawWrapper) { s.ddrawWrapper = it; s.wrapperValue("ddrawrapper", it) }
                } else {
                    SettingsDivider()
                    SettingToggle("CSMT", s.csmt) { s.csmt = it; s.wrapperValue("csmt", if (it) "3" else "0") }
                    SettingsDivider()
                    SettingToggle("Strict Shader Math", s.strictShaderMath) { s.strictShaderMath = it; s.wrapperValue("strict_shader_math", if (it) "1" else "0") }
                    SettingsDivider()
                    SettingChoice("Offscreen Rendering Mode", s.offscreenMode, listOf("fbo", "backbuffer")) { s.offscreenMode = it; s.wrapperValue("OffscreenRenderingMode", it) }
                    SettingsDivider()
                    SettingChoice("Wine Renderer", s.wineRenderer, listOf("vulkan", "gl")) { s.wineRenderer = it; s.wrapperValue("renderer", it) }
                    SettingsDivider()
                    SettingText("Video Memory", s.videoMemory) { s.videoMemory = it.filter(Char::isDigit).take(6); s.wrapperValue("videoMemorySize", s.videoMemory) }
                }
            }
            SettingsCard {
                if (s.arm64) {
                    SettingChoice("32-bit Emulator", s.emulator, listOf("FEXCore", "WOWBox64")) {
                        s.emulator = it; s.extra("emulator", if (it == "FEXCore") "FEXCore" else "Box64")
                    }
                    catalog?.let { c ->
                        SettingsDivider()
                        SettingInstallChoice("FEXCore Version", s.fexVersion, c.fex, s.installing, "FEXCore",
                            { v -> installRuntime("FEXCore", v) { installed -> s.fexVersion = installed; s.extra("fexcoreVersion", installed) } }) {
                            s.fexVersion = it; s.extra("fexcoreVersion", it)
                        }
                    }
                    SettingsDivider()
                    SettingMappedChoice("FEXCore Preset", s.fexPreset, fexPresets) { s.fexPreset = it; s.extra("fexcorePreset", it) }
                }
                if (!s.arm64 || s.emulator == "WOWBox64") {
                    catalog?.let { c ->
                        val versionCatalog = if (s.arm64) c.wow else c.box
                        val type = if (s.arm64) "WOWBox64" else "Box64"
                        SettingsDivider()
                        SettingInstallChoice("$type Version", s.boxVersion, versionCatalog, s.installing, type,
                            { v -> installRuntime(type, v) { installed -> s.boxVersion = installed; s.extra("box64Version", installed) } }) {
                            s.boxVersion = it; s.extra("box64Version", it)
                        }
                    }
                    SettingsDivider()
                    SettingMappedChoice("Box64 Preset", s.boxPreset, boxPresets) { s.boxPreset = it; s.extra("box64Preset", it) }
                }
            }
        }

        "Input" -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsCard {
                SettingMappedChoice("Controls Profile", s.controlsProfile, profiles) { s.controlsProfile = it; s.extra("controlsProfile", it.takeUnless { id -> id == "0" }) }
                SettingsDivider()
                SettingToggle("Exclusive Input", s.exclusive) {
                    s.exclusive = it
                    if (!it) { s.xinput = true; s.dinput = true } else if (s.xinput && s.dinput) s.dinput = false
                    s.saveInput()
                }
                SettingsDivider()
                SettingToggle("Enable XInput", s.xinput, s.exclusive) { s.xinput = it; if (s.exclusive && it && s.dinput) s.dinput = false; s.saveInput() }
                SettingsDivider()
                SettingToggle("Enable DInput", s.dinput, s.exclusive) { s.dinput = it; if (s.exclusive && it && s.xinput) s.xinput = false; s.saveInput() }
                SettingsDivider()
                val touchModes = listOf("Trackpad", "Touchscreen", "Off")
                SettingChoice("Touch Mode", touchModes[s.touchMode.coerceIn(0, 2)], touchModes) { selected ->
                    s.touchMode = touchModes.indexOf(selected).coerceAtLeast(0)
                    if (s.touchMode == 2) {
                        s.extra("disableMouse", "1")
                    } else {
                        s.extra("disableMouse", null)
                        s.extra("simTouchScreen", if (s.touchMode == 1) "1" else "0")
                    }
                }
                SettingsDivider()
                SettingToggle("Relative Mouse", s.relativeMouse) { s.relativeMouse = it; s.extra("enableRelativeMouse", if (it) "1" else null) }
                // Touch-surface only, so hidden while Touch Mode is Off (value kept).
                if (s.touchMode != 2) {
                    SettingsDivider()
                    SettingToggle("Tap to Click", s.tapToClick) { s.tapToClick = it; s.extra("tapToClick", if (it) null else "0") }
                }
            }
        }

        "Environment" -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            EnvironmentVariablesEditor(s.envVars, onChanged = { s.setEnvironment(it) })
            if (s.hasOwnEnvironment) {
                // The shortcut keeps its own copy of the variables; this makes it follow the
                // container's list again (and pick up later changes made there).
                TextButton(
                    onClick = { s.resetEnvironmentToContainer() },
                    modifier = Modifier.align(Alignment.End)
                ) { Text("Reset to container variables") }
            }
        }

        else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsCard {
                val startupEntries = listOf(
                    "Load all services",
                    "Load essential services",
                    "Stop all services"
                )
                SettingChoice("Startup Selection", startupEntries[s.startup], startupEntries) {
                    s.startup = startupEntries.indexOf(it).coerceAtLeast(0); s.extra("startupSelection", s.startup.toString())
                }
            }
            SettingsCard {
                SettingToggle("Sync CPU Topology", s.syncCpu) { s.syncCpu = it; s.saveCpu() }
                SettingsDivider()
                CpuSelectorRow("Processor Affinity", s.cpu) { index, checked -> s.cpu[index] = checked; s.saveCpu() }
            }
            SettingsCard {
                SettingChoice("Sharpness Effect", s.sharpnessEffect, listOf("None", "CAS", "DLS")) {
                    s.sharpnessEffect = it; s.extra("sharpnessEffect", it)
                }
                if (s.sharpnessEffect != "None") {
                    SettingsDivider()
                    SharpnessSliderV2("Sharpness Level", s.sharpnessLevel) {
                        s.sharpnessLevel = it; s.extra("sharpnessLevel", it)
                    }
                    SettingsDivider()
                    SharpnessSliderV2("Sharpness Denoise", s.sharpnessDenoise) {
                        s.sharpnessDenoise = it; s.extra("sharpnessDenoise", it)
                    }
                }
            }
            ExecArgumentsEditorV2(s.execArgs) {
                s.execArgs = it; s.extra("execArgs", it.ifBlank { null })
            }
            SettingsCard {
                shortcutComponentRowsV2.forEachIndexed { index, (key, label) ->
                    val entries = listOf("Builtin (Wine)", "Native (Windows)")
                    val selected = entries[(s.components[key] ?: 0).coerceIn(0, 1)]
                    SettingChoice(label, selected, entries) {
                        s.components[key] = entries.indexOf(it).coerceAtLeast(0); s.saveComponents()
                    }
                    if (index != shortcutComponentRowsV2.lastIndex) SettingsDivider()
                }
            }
        }
    }
}

@Composable
private fun SharpnessSliderV2(label: String, value: String, onChanged: (String) -> Unit) {
    val numeric = value.toFloatOrNull()?.coerceIn(0f, 100f) ?: 100f
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(numeric.roundToInt().toString(), fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = numeric,
            onValueChange = { onChanged(it.roundToInt().coerceIn(0, 100).toString()) },
            valueRange = 0f..100f,
            colors = SliderDefaults.colors(thumbColor = controlAccentColor(), activeTrackColor = controlAccentColor())
        )
    }
}

@Composable
private fun ExecArgumentsEditorV2(value: String, onChanged: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    SettingsCard {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onChanged,
                label = { Text("Exec Arguments") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = WinZShapes.Small
            )
            Box {
                TextButton(onClick = { open = true }) { Text("⋮", style = MaterialTheme.typography.headlineSmall) }
                DropdownMenu(
                    expanded = open,
                    onDismissRequest = { open = false },
                    shape = WinZShapes.Medium,
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    val accent = controlAccentColor()
                    execArgumentPresetsV2.forEach { arg ->
                        val alreadyAdded = value.split(' ').contains(arg)
                        DropdownMenuItem(
                            text = {
                                Text(
                                    arg,
                                    color = if (alreadyAdded) accent else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (alreadyAdded) FontWeight.SemiBold else FontWeight.Normal
                                )
                            },
                            trailingIcon = { if (alreadyAdded) Icon(Icons.Outlined.Check, null, tint = accent) },
                            onClick = {
                                if (!alreadyAdded) onChanged(listOf(value.trim(), arg).filter(String::isNotBlank).joinToString(" "))
                                open = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShortcutVulkanExtensionsV2(context: Context, driver: String, blacklisted: String, onChanged: (String) -> Unit) {
    val extensions = remember(driver) { runCatching { GPUInformation.enumerateExtensions(driver, context).toList().sorted() }.getOrDefault(emptyList()) }
    if (extensions.isEmpty()) {
        SettingText("Disabled Vulkan Extensions", blacklisted, 2) { onChanged(it.replace(" ", "")) }
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
            Text("Vulkan Extensions", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("$enabledCount of ${extensions.size} enabled", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        }
    }
    // Was ThemedAlertHost.multiChoice: that inserts its overlay into the hosting Activity's
    // android.R.id.content, which sits *behind* the separate full-screen ComponentDialog window
    // this editor is shown in (see ShortcutSettingsComposeDialog) — so tapping this row never
    // showed anything. ThemedDialog uses Compose's own Dialog(), which attaches to whichever
    // window is currently hosting the composition, so it renders correctly here too.
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

private fun loadShortcutGpuNamesV2(context: Context): List<String> {
    val result = linkedSetOf("Device")
    runCatching {
        val data = FileUtils.readString(context, "gpu_cards.json")
        val array = JSONArray(data)
        for (index in 0 until array.length()) {
            val name = array.optJSONObject(index)?.optString("name").orEmpty()
            if (name.isNotBlank()) result.add(name)
        }
    }
    return result.toList()
}

private fun loadShortcutSoundFontsV2(context: Context): List<String> {
    val result = linkedSetOf("Disabled", MidiManager.DEFAULT_SF2_FILE)
    MidiManager.getSoundFontDir(context).listFiles()?.filter { it.isFile }?.sortedBy { it.name.lowercase() }?.forEach { result.add(it.name) }
    return result.toList()
}

private fun parseShortcutComponentsV2(raw: String): Map<String, Int> {
    val result = mutableMapOf<String, Int>()
    raw.split(',').forEach { token ->
        val split = token.indexOf('=')
        if (split > 0) result[token.substring(0, split)] = token.substring(split + 1).toIntOrNull()?.coerceIn(0, 1) ?: 0
    }
    shortcutComponentRowsV2.forEach { (key, _) -> result.putIfAbsent(key, 0) }
    return result
}

private sealed class ShortcutRenameResult {
    object Unchanged : ShortcutRenameResult()
    object NameTaken : ShortcutRenameResult()
    object Failed : ShortcutRenameResult()
    class Renamed(val newName: String) : ShortcutRenameResult()
}

private fun renameShortcutV2(shortcut: Shortcut, requested: String): ShortcutRenameResult {
    val clean = requested.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
    if (clean.isBlank() || clean == shortcut.name) return ShortcutRenameResult.Unchanged

    val parent = shortcut.file.parentFile
    val oldName = shortcut.name
    val ext = shortcut.file.extension.takeIf { it.isNotBlank() } ?: "desktop"
    val target = File(parent, "$clean.$ext")
    if (target.exists()) return ShortcutRenameResult.NameTaken
    if (!shortcut.file.renameTo(target)) return ShortcutRenameResult.Failed

    val oldLink = File(parent, "$oldName.lnk")
    if (oldLink.isFile) {
        val newLink = File(parent, "$clean.lnk")
        if (!newLink.exists()) oldLink.renameTo(newLink)
    }
    return ShortcutRenameResult.Renamed(clean)
}

private fun renderingModeOf(env: String): String {
    val tuDebug = envValue(env, "TU_DEBUG")?.split(',').orEmpty()
    return when {
        envValue(env, "TU_AUTOTUNE_ALGO").equals("profiled", true) -> "Autotuner Profiled"
        tuDebug.any { it.trim().equals("gmem", true) } -> "Gmem"
        tuDebug.any { it.trim().equals("sysmem", true) } -> "Sysmem"
        else -> "None"
    }
}
