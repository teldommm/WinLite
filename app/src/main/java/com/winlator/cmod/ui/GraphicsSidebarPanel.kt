package com.winlator.cmod.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import com.winlator.cmod.R
import com.winlator.cmod.ui.theme.RevealSection
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// ---------- Graphics panel ----------

// Indices match the original Spinners' positions exactly (upscalerModeIndex: SGSR/FSR/
// Lanczos2/ColorBoost, postFxModeIndex: None/DLS/CRT/HDR/Natural, reshadeEffectIndex: the
// 12-entry ReShade list) so Java's existing mapping tables can be reused unchanged.
data class GraphicsPanelState(
    val fpsLimit: Int,
    val fsrEnabled: Boolean,
    val upscalerModeIndex: Int,
    val sharpnessPercent: Int,
    val postFxModeIndex: Int,
    val reshadeEffectIndex: Int,
    val reshadeStrengthPercent: Int,
    val wfgEnabled: Boolean,
    val wfgQualityIndex: Int,
    val wfgModeIndex: Int,
    val wfgTargetFps: Int,
    // Fastest refresh rate the panel can run at (0 = unknown): the ceiling for the target FPS.
    val wfgPanelHz: Int,
    // GPU Turbo row: shown only when the container/shortcut option is on and the GPU is an Adreno.
    val gpuTurboVisible: Boolean,
    val gpuTurboEnabled: Boolean
)

interface GraphicsPanelCallbacks {
    fun onFpsLimitChanged(fps: Int)
    fun onFsrToggled(enabled: Boolean)
    fun onUpscalerModeChanged(index: Int)
    fun onSharpnessChanged(percent: Int)
    fun onSharpnessCommitted(percent: Int)
    fun onPostFxModeChanged(index: Int)
    fun onReshadeEffectChanged(index: Int)
    fun onReshadeStrengthChanged(percent: Int)
    fun onWfgChanged(enabled: Boolean, qualityIndex: Int, modeIndex: Int, targetFps: Int)
    fun onGpuTurboChanged(enabled: Boolean)
}

object GraphicsSidebarPanelHost {
    @JvmStatic
    fun attach(
        sidebar: IngameSidebarController,
        panelId: Int,
        state: GraphicsPanelState,
        callbacks: GraphicsPanelCallbacks
    ) {
        sidebar.setPanel(panelId) { GraphicsSidebarPanel(state, callbacks) }
    }
}

// Index = filterMode - 2 (saved/native numbering, never reorder). NIS (mode 7) is appended here but
// shown right after SGSR (HQ) and after FSR (NIS) in the dropdown through UPSCALER_DISPLAY_ORDER.
private val UPSCALER_LABELS = listOf("SGSR", "FSR", "Lanczos 2", "Color Boost", "SGSR HQ", "NIS")
private val UPSCALER_DISPLAY_ORDER = listOf(0, 4, 1, 5, 2, 3)
private val POSTFX_LABELS = listOf("None", "DLS", "CRT", "HDR", "Natural")
// First 12 entries (indices 0-11) map 1:1 to window_postfx.frag effectId 0,10..20 via
// XServerDisplayActivity's effectModes[] table. The last 5 (indices 12-16) were previously
// implemented in the shader (applyVibrance/Curves/CASLite/Technicolor/Levels, effectId 5-9) but
// never wired to any picker - added here so they're reachable; effectModes[] must stay in sync.
private val RESHADE_EFFECTS = listOf(
    "Off", "Game Clarity", "Cinematic", "Vivid", "Competitive", "Adaptive Sharpen",
    "Filmic", "Arcade", "Retro CRT", "Upscale Sharp", "Pixel Clean", "Anime Edge",
    "Vibrance", "Curves", "CAS Lite", "Technicolor", "Levels"
)

// WFG frame generation. Positions match XServerDisplayActivity's WFG_FLOW_SIDES (saved as an
// index, never reorder): quality = pixels analysed on the frame's shorter side.
private val WFG_QUALITY_LABELS = listOf("Fast", "Balanced")

/**
 * What the Frame Generation card offers for the frame multiplier and the target FPS, and how it is
 * saved. XServerDisplayActivity takes the native values from here instead of keeping copies, so
 * the list, the saved indices and the multipliers handed to the renderer cannot drift apart.
 */
object WfgSettings {
    private class Mode(val label: String, val multiplier: Int)

    // Saved as the index (never reorder). x2/x3/x4 fix the output at that many frames per game
    // frame; multiplier 0 means none is fixed and the target FPS decides (the "Target" entry,
    // which shows a numeric field).
    private val MODES = listOf(Mode("x2", 2), Mode("x3", 3), Mode("x4", 4), Mode("Target", 0))

    @JvmField
    val MODE_LABELS: List<String> = MODES.map { it.label }

    @JvmField
    val MODE_COUNT: Int = MODES.size

    @JvmStatic
    fun clampMode(index: Int): Int = index.coerceIn(0, MODES.lastIndex)

    /** Frames per game frame for a saved mode index; 0 when the target FPS decides instead. */
    @JvmStatic
    fun multiplierOf(index: Int): Int = MODES[clampMode(index)].multiplier

    @JvmStatic
    fun isTarget(index: Int): Boolean = multiplierOf(index) == 0

    // 0 follows the panel's refresh rate (the former "Max refresh"): the renderer aims at whatever
    // the display runs at, which WFG raises to the panel's fastest mode when it is on.
    const val TARGET_PANEL = 0

    // WFG only generates from a source of at least 24 FPS (DIS_MIN_SOURCE_FPS_ON) and a target at
    // least 1.45x the source (DIS_MIN_GEN_RATIO): 24 * 1.45 = ~35. A lower target could never
    // produce a frame, so it is not offered.
    const val TARGET_MIN = 40
    const val TARGET_DEFAULT = 60

    // When the panel's refresh rate is unknown. The planner never targets more than the display
    // runs at, so the real ceiling is the panel's own.
    private const val TARGET_FALLBACK_MAX = 240

    @JvmStatic
    fun targetMax(panelHz: Int): Int =
        if (panelHz > 0) maxOf(TARGET_MIN, panelHz) else TARGET_FALLBACK_MAX

    /** [TARGET_PANEL] stays as it is; anything else is pulled into TARGET_MIN..targetMax. */
    @JvmStatic
    fun clampTarget(fps: Int, panelHz: Int): Int =
        if (fps <= TARGET_PANEL) TARGET_PANEL else fps.coerceIn(TARGET_MIN, targetMax(panelHz))
}

// FPS Limit slider: position 0 = Off, positions 1..24 = 5..120 FPS in 5 FPS steps, position 25
// ("Custom") shows a numeric field for any other value (e.g. 40, 72, 144). 0 is what
// VulkanXServerView / PresentExtension treat as "no limit".
private const val FPS_STEP = 5
private const val FPS_SLIDER_MAX_FPS = 120
private const val FPS_CUSTOM_POSITION = FPS_SLIDER_MAX_FPS / FPS_STEP + 1
private const val FPS_CUSTOM_DEFAULT = 144
// Same ceiling VulkanXServerView.setFpsLimit() clamps to.
private const val FPS_CUSTOM_MAX = 1000

@Composable
private fun GraphicsSidebarPanel(
    state: GraphicsPanelState,
    callbacks: GraphicsPanelCallbacks
) {
    var fsrEnabled by remember { mutableStateOf(state.fsrEnabled) }
    var upscalerIndex by remember { mutableStateOf(state.upscalerModeIndex) }
    var sharpness by remember { mutableStateOf(state.sharpnessPercent) }
    var postFxIndex by remember { mutableStateOf(state.postFxModeIndex) }
    var reshadeIndex by remember { mutableStateOf(state.reshadeEffectIndex) }
    var reshadeStrength by remember { mutableStateOf(state.reshadeStrengthPercent) }
    var wfgEnabled by remember { mutableStateOf(state.wfgEnabled) }
    var gpuTurbo by remember { mutableStateOf(state.gpuTurboEnabled) }
    var wfgQualityIndex by remember { mutableStateOf(state.wfgQualityIndex) }
    var wfgModeIndex by remember { mutableStateOf(state.wfgModeIndex) }
    // The target FPS in effect (kept while a multiplier is selected, so switching back restores it)
    // and the text being typed, which is only applied once editing ends.
    var wfgTargetFps by remember { mutableStateOf(WfgSettings.clampTarget(state.wfgTargetFps, state.wfgPanelHz)) }
    var wfgTargetText by remember { mutableStateOf(wfgTargetFps.toString()) }

    // Pulls the typed target into the allowed range (e.g. 5000 -> the panel's maximum; 0 stays 0,
    // meaning the panel's own rate) and makes it the one in effect; true when that changed it. An
    // empty field falls back to the value in effect.
    fun commitWfgTarget(): Boolean {
        val typed = wfgTargetText.toIntOrNull()
            ?.let { WfgSettings.clampTarget(it, state.wfgPanelHz) }
        if (typed == null) {
            wfgTargetText = wfgTargetFps.toString()
            return false
        }
        wfgTargetText = typed.toString()
        if (typed == wfgTargetFps) return false
        wfgTargetFps = typed
        return true
    }

    // Every WFG control reports through here, so a typed target that is still pending when another
    // control acts (or its field goes away) is applied with it instead of being dropped.
    fun pushWfg() {
        commitWfgTarget()
        callbacks.onWfgChanged(wfgEnabled, wfgQualityIndex, wfgModeIndex, wfgTargetFps)
    }

    // Same visibility rule as the original updateSharpnessVis(): shown while Super Resolution
    // is on (any upscaler mode — SGSR/SGSR HQ/FSR/NIS/Lanczos2/Color Boost all read pc.sharpness
    // in their own shader) OR the Post Effect picker is set to DLS (index 1), since DLS also
    // reads it. Under an upscaler DLS no longer sharpens (contrast/saturation only), so the
    // slider drives a single sharpening stage.
    val sharpnessVisible = fsrEnabled || postFxIndex == 1

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        SidebarPanelTitle(uiStr(R.string.ui_rendering))

        FpsLimiterCard(
            initialFps = state.fpsLimit,
            onFpsChanged = callbacks::onFpsLimitChanged
        )

        SidebarGap()
        SidebarCard {
            SidebarInlineToggle(
                label = uiStr(R.string.ui_super_resolution),
                checked = fsrEnabled,
                onCheckedChange = {
                    fsrEnabled = it
                    if (it && reshadeIndex > 0) {
                        reshadeIndex = 0
                        callbacks.onReshadeEffectChanged(0)
                    }
                    callbacks.onFsrToggled(it)
                }
            )
            RevealSection(visible = fsrEnabled) {
                Spacer(Modifier.height(8.dp))
                SidebarDropdownField(
                    caption = uiStr(R.string.ui_upscaler_mode),
                    options = UPSCALER_DISPLAY_ORDER.map { UPSCALER_LABELS[it] },
                    selectedIndex = UPSCALER_DISPLAY_ORDER.indexOf(upscalerIndex).coerceAtLeast(0),
                    onSelect = {
                        val mode = UPSCALER_DISPLAY_ORDER[it]
                        upscalerIndex = mode
                        callbacks.onUpscalerModeChanged(mode)
                    }
                )
            }
            RevealSection(visible = sharpnessVisible) {
                Spacer(Modifier.height(12.dp))
                var sharpnessDraft by remember(sharpnessVisible) { mutableStateOf(sharpness.toFloat()) }
                SidebarSlider(
                    label = uiStr(R.string.ui_sharpness),
                    valueText = "$sharpness%",
                    value = sharpnessDraft,
                    onValueChange = {
                        sharpnessDraft = it
                        sharpness = it.toInt()
                        callbacks.onSharpnessChanged(it.toInt())
                    },
                    onValueChangeFinished = { callbacks.onSharpnessCommitted(sharpnessDraft.toInt()) },
                    valueRange = 0f..100f
                )
            }
            val postFxGap by animateDpAsState(if (sharpnessVisible) 4.dp else 12.dp, tween(260), label = "postFxGap")
            Spacer(Modifier.height(postFxGap))
            SidebarDropdownField(
                caption = uiStr(R.string.ui_post_effect),
                options = POSTFX_LABELS,
                selectedIndex = postFxIndex,
                onSelect = {
                    postFxIndex = it
                    if (reshadeIndex > 0) {
                        reshadeIndex = 0
                        callbacks.onReshadeEffectChanged(0)
                    }
                    callbacks.onPostFxModeChanged(it)
                }
            )
        }

        SidebarGap()
        SidebarCard {
            SidebarSectionTitle("ReShade")
            Spacer(Modifier.height(10.dp))
            SidebarDropdownField(
                caption = uiStr(R.string.ui_effect),
                options = RESHADE_EFFECTS,
                selectedIndex = reshadeIndex,
                onSelect = { index ->
                    reshadeIndex = index
                    // Picking a real effect (index > 0) force-disables Super Resolution and
                    // resets Post Effect to None, since all three drive the same renderer
                    // filter/post-fx state and only one can be active at a time.
                    if (index > 0) {
                        if (fsrEnabled) {
                            fsrEnabled = false
                            callbacks.onFsrToggled(false)
                        }
                        if (postFxIndex != 0) {
                            postFxIndex = 0
                            callbacks.onPostFxModeChanged(0)
                        }
                    }
                    callbacks.onReshadeEffectChanged(index)
                }
            )
            RevealSection(visible = reshadeIndex > 0) {
                Spacer(Modifier.height(12.dp))
                SidebarSlider(
                    label = uiStr(R.string.ui_strength),
                    valueText = "$reshadeStrength%",
                    value = reshadeStrength.toFloat(),
                    onValueChange = {
                        reshadeStrength = it.toInt()
                        callbacks.onReshadeStrengthChanged(it.toInt())
                    },
                    valueRange = 0f..100f
                )
            }
        }

        SidebarGap()
        SidebarCard {
            SidebarSectionTitle(uiStr(R.string.ui_frame_generation))
            Spacer(Modifier.height(10.dp))
            SidebarInlineToggle(
                label = uiStr(R.string.ui_wfg_frame_generator),
                checked = wfgEnabled,
                onCheckedChange = {
                    wfgEnabled = it
                    pushWfg()
                }
            )
            RevealSection(visible = wfgEnabled) {
                Spacer(Modifier.height(8.dp))
                SidebarDropdownField(
                    caption = uiStr(R.string.ui_wfg_motion_quality),
                    options = WFG_QUALITY_LABELS,
                    selectedIndex = wfgQualityIndex.coerceIn(0, WFG_QUALITY_LABELS.lastIndex),
                    onSelect = {
                        wfgQualityIndex = it
                        pushWfg()
                    }
                )
                Spacer(Modifier.height(12.dp))
                SidebarDropdownField(
                    caption = uiStr(R.string.ui_wfg_multiplier),
                    options = WfgSettings.MODE_LABELS,
                    selectedIndex = WfgSettings.clampMode(wfgModeIndex),
                    onSelect = {
                        wfgModeIndex = it
                        pushWfg()
                    }
                )
                RevealSection(visible = WfgSettings.isTarget(wfgModeIndex)) {
                    Spacer(Modifier.height(10.dp))
                    WfgTargetField(
                        text = wfgTargetText,
                        range = WfgSettings.TARGET_MIN..WfgSettings.targetMax(state.wfgPanelHz),
                        onTextChange = { wfgTargetText = it },
                        // Done, tap elsewhere or drawer closed: apply once, not per keystroke
                        // (typing "144" would otherwise pass through 1 and 14 and save each).
                        onCommit = {
                            if (commitWfgTarget()) {
                                callbacks.onWfgChanged(wfgEnabled, wfgQualityIndex, wfgModeIndex, wfgTargetFps)
                            }
                        }
                    )
                }
            }
        }

        if (state.gpuTurboVisible) {
            SidebarGap()
            SidebarCard {
                SidebarInlineToggle(
                    label = uiStr(R.string.ui_gpu_turbo),
                    checked = gpuTurbo,
                    onCheckedChange = {
                        gpuTurbo = it
                        callbacks.onGpuTurboChanged(it)
                    }
                )
            }
        }

        // Settings on this panel are saved as they change (per shortcut, or to the container when
        // launched without one) — there's no separate "Save Preset" step.
    }
}

@Composable
private fun FpsLimiterCard(
    initialFps: Int,
    onFpsChanged: (Int) -> Unit
) {
    val initialPosition = when {
        initialFps <= 0 -> 0
        initialFps > FPS_SLIDER_MAX_FPS || initialFps % FPS_STEP != 0 -> FPS_CUSTOM_POSITION
        else -> initialFps / FPS_STEP
    }
    var position by remember { mutableStateOf(initialPosition) }
    var customText by remember {
        mutableStateOf(if (initialPosition == FPS_CUSTOM_POSITION && initialFps > 0) initialFps.toString() else "")
    }
    // Last value handed to onFpsChanged, so the same limit isn't re-saved (shortcut.saveData()
    // writes to disk) on every slider release or keystroke that doesn't change it.
    var applied by remember { mutableStateOf(initialFps.coerceAtLeast(0)) }
    val focusManager = LocalFocusManager.current

    fun customValue(): Int? = customText.toIntOrNull()?.takeIf { it > 0 }?.coerceAtMost(FPS_CUSTOM_MAX)

    // null = nothing valid to apply yet (Custom selected with an empty field): keep the
    // current limit instead of silently jumping to some default.
    fun chosenLimit(): Int? = when {
        position <= 0 -> 0
        position < FPS_CUSTOM_POSITION -> position * FPS_STEP
        else -> customValue()
    }

    fun apply() {
        val limit = chosenLimit() ?: return
        if (limit != applied) {
            applied = limit
            onFpsChanged(limit)
        }
    }

    // While the field has focus the Activity must hand key events to the IME/text field instead
    // of the X server keyboard; reset on dispose so a torn-down panel can't leave it stuck.
    DisposableEffect(Unit) {
        onDispose { SidebarTextInputFocus.active = false }
    }

    SidebarCard {
        SidebarSlider(
            label = uiStr(R.string.ui_fps_limit),
            valueText = when {
                position <= 0 -> uiStr(R.string.ui_off)
                position >= FPS_CUSTOM_POSITION -> uiStr(R.string.ui_custom)
                else -> "${position * FPS_STEP} FPS"
            },
            value = position.toFloat(),
            onValueChange = {
                position = it.roundToInt().coerceIn(0, FPS_CUSTOM_POSITION)
                // Custom starts at 144 so dragging into it never shows an empty field; the value
                // is applied when the slider is released, so what's shown is what's in effect.
                if (position >= FPS_CUSTOM_POSITION && customText.isEmpty()) {
                    customText = FPS_CUSTOM_DEFAULT.toString()
                }
            },
            onValueChangeFinished = { apply() },
            valueRange = 0f..FPS_CUSTOM_POSITION.toFloat()
        )
        RevealSection(visible = position >= FPS_CUSTOM_POSITION) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = customText,
                // Only edit the text here. Applying per keystroke would briefly cap the game at
                // 1 FPS, then 14, ... while typing "144", and save to disk each time.
                onValueChange = { text -> customText = text.filter(Char::isDigit).take(4) },
                singleLine = true,
                label = { Text(uiStr(R.string.ui_max_fps)) },
                suffix = { Text("FPS") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focus ->
                        val wasActive = SidebarTextInputFocus.active
                        SidebarTextInputFocus.active = focus.isFocused
                        if (wasActive && !focus.isFocused) {
                            // Done, tap elsewhere, or drawer closed: normalize (e.g. 5000 -> 1000)
                            // and apply once.
                            customValue()?.let { customText = it.toString() }
                            apply()
                        }
                    }
            )
        }
    }
}

// Numeric field for WFG's custom target FPS, shown while the multiplier list is on "Target"; the
// allowed range (and that 0 means the panel's rate) is shown under it and enforced when editing
// ends. Same focus handling as the FPS limiter's custom field: onCommit fires when focus is lost.
@Composable
private fun WfgTargetField(
    text: String,
    range: IntRange,
    onTextChange: (String) -> Unit,
    onCommit: () -> Unit
) {
    val focusManager = LocalFocusManager.current

    // Reset on dispose so a field torn down while focused (mode switched, WFG turned off) can't
    // leave the Activity handing every key to the IME.
    DisposableEffect(Unit) {
        onDispose { SidebarTextInputFocus.active = false }
    }

    OutlinedTextField(
        value = text,
        onValueChange = { onTextChange(it.filter(Char::isDigit).take(3)) },
        singleLine = true,
        label = { Text(uiStr(R.string.ui_wfg_target_fps)) },
        suffix = { Text("FPS") },
        supportingText = { Text(uiStr(R.string.ui_wfg_target_hint, range.first, range.last)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focus ->
                val wasActive = SidebarTextInputFocus.active
                SidebarTextInputFocus.active = focus.isFocused
                if (wasActive && !focus.isFocused) onCommit()
            }
    )
}

/**
 * True while a text field inside the in-game sidebar has focus. XServerDisplayActivity checks it
 * in dispatchKeyEvent: otherwise digit/backspace key events from the numeric IME are consumed by
 * the X server keyboard and never reach the field.
 */
object SidebarTextInputFocus {
    @JvmField
    @Volatile
    var active: Boolean = false
}
