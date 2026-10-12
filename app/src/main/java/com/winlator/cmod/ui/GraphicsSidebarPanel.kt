package com.winlator.cmod.ui

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
// 12-entry ReShade list, frameGenMultiplierIndex: Off/2x/3x/4x, plus 4 = LSFG Adaptive
// FPS) so Java's existing mapping tables can be reused unchanged.
data class GraphicsPanelState(
    val fpsLimit: Int,
    val fsrEnabled: Boolean,
    val upscalerModeIndex: Int,
    val sharpnessPercent: Int,
    val postFxModeIndex: Int,
    val reshadeEffectIndex: Int,
    val reshadeStrengthPercent: Int,
    val frameGenAvailable: Boolean,
    val frameGenMultiplierIndex: Int,
    val frameGenFlowScale: Float,
    val frameGenTargetFps: Int       // Adaptive target FPS shown in the field (last entered/saved)
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
    fun onFrameGenChanged(multiplierIndex: Int)
    fun onFrameGenFlowScaleChanged(scale: Float)
    fun onFrameGenTargetFpsChanged(fps: Int)
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
// shown right after FSR in the dropdown through UPSCALER_DISPLAY_ORDER.
private val UPSCALER_LABELS = listOf("SGSR", "FSR", "Lanczos 2", "Color Boost", "SGSR HQ", "NIS")
private val UPSCALER_DISPLAY_ORDER = listOf(0, 1, 5, 2, 3, 4)
private val POSTFX_LABELS = listOf("None", "DLS", "CRT", "HDR", "Natural")
private val FRAMEGEN_LABELS = listOf("Off", "LSFG 2x", "LSFG 3x", "LSFG 4x", "LSFG Adaptive FPS")
// Index of "LSFG Adaptive FPS": presents paced to a typed-in rate instead of a fixed multiplier.
const val FRAMEGEN_TARGET_INDEX = 4
private const val TARGET_FPS_MAX = 1000
private val RESHADE_EFFECTS = listOf(
    "Off", "Game Clarity", "Cinematic", "Vivid", "Competitive", "Adaptive Sharpen",
    "Filmic", "Arcade", "Retro CRT", "Upscale Sharp", "Pixel Clean", "Anime Edge"
)

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
    var frameGenIndex by remember { mutableStateOf(state.frameGenMultiplierIndex) }
    // Last Adaptive target. Kept here (state is a one-shot snapshot) so switching to a fixed
    // multiplier and back doesn't forget what was typed.
    var targetFps by remember { mutableStateOf(state.frameGenTargetFps) }

    // Same visibility rule as the original updateSharpnessVis(): shown while FSR is on OR
    // the Post Effect picker is set to DLS (index 1) — Sharpness feeds whichever is active.
    val sharpnessVisible = fsrEnabled || postFxIndex == 1

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        SidebarPanelTitle("Rendering")

        FpsLimiterCard(
            initialFps = state.fpsLimit,
            onFpsChanged = callbacks::onFpsLimitChanged
        )

        SidebarGap()
        SidebarCard {
            SidebarInlineToggle(
                label = "Super Resolution",
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
            if (fsrEnabled) {
                Spacer(Modifier.height(8.dp))
                SidebarDropdownField(
                    caption = "Upscaler Mode",
                    options = UPSCALER_DISPLAY_ORDER.map { UPSCALER_LABELS[it] },
                    selectedIndex = UPSCALER_DISPLAY_ORDER.indexOf(upscalerIndex).coerceAtLeast(0),
                    onSelect = {
                        val mode = UPSCALER_DISPLAY_ORDER[it]
                        upscalerIndex = mode
                        callbacks.onUpscalerModeChanged(mode)
                    }
                )
            }
            if (sharpnessVisible) {
                Spacer(Modifier.height(12.dp))
                var sharpnessDraft by remember(sharpnessVisible) { mutableStateOf(sharpness.toFloat()) }
                SidebarSlider(
                    label = "Sharpness",
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
            Spacer(Modifier.height(if (sharpnessVisible) 4.dp else 12.dp))
            SidebarDropdownField(
                caption = "Post Effect",
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
                caption = "Effect",
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
            if (reshadeIndex > 0) {
                Spacer(Modifier.height(12.dp))
                SidebarSlider(
                    label = "Strength",
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

        if (state.frameGenAvailable) {
            SidebarGap()
            SidebarCard {
                SidebarSectionTitle("Frame Generation")
                Spacer(Modifier.height(10.dp))
                SidebarDropdownField(
                    caption = null,
                    options = FRAMEGEN_LABELS,
                    selectedIndex = frameGenIndex,
                    onSelect = {
                        frameGenIndex = it
                        callbacks.onFrameGenChanged(it)
                    }
                )
                if (frameGenIndex == FRAMEGEN_TARGET_INDEX) {
                    Spacer(Modifier.height(10.dp))
                    TargetFpsField(
                        initialFps = targetFps,
                        onFpsChanged = {
                            targetFps = it
                            callbacks.onFrameGenTargetFpsChanged(it)
                        }
                    )
                }
                if (frameGenIndex > 0) {
                    Spacer(Modifier.height(12.dp))
                    // Dragging only updates the label; the (expensive) frame-gen pipeline rebuild and
                    // the shortcut/container save happen once, when the finger is lifted.
                    var flowScale by remember { mutableStateOf(state.frameGenFlowScale) }
                    var appliedFlowScale by remember { mutableStateOf(state.frameGenFlowScale) }
                    SidebarSlider(
                        label = "Flow Scale",
                        valueText = "${(flowScale * 100).roundToInt()}%",
                        value = flowScale,
                        onValueChange = { flowScale = (it * 20f).roundToInt() / 20f },
                        onValueChangeFinished = {
                            if (flowScale != appliedFlowScale) {
                                appliedFlowScale = flowScale
                                callbacks.onFrameGenFlowScaleChanged(flowScale)
                            }
                        },
                        valueRange = 0.25f..1.0f
                    )
                }
            }
        }

        // Settings on this panel are saved as they change (per shortcut, or to the container when
        // launched without one) — there's no separate "Save Preset" step.
    }
}

// Target FPS for LSFG adaptive pacing. Like the FPS limiter's Custom field, the value is applied
// once - on Done or focus loss - not per keystroke (typing "90" must not pace to 9 first).
@Composable
private fun TargetFpsField(initialFps: Int, onFpsChanged: (Int) -> Unit) {
    var text by remember { mutableStateOf(if (initialFps > 0) initialFps.toString() else "") }
    var applied by remember { mutableStateOf(initialFps.coerceAtLeast(0)) }
    val focusManager = LocalFocusManager.current

    fun value(): Int? = text.toIntOrNull()?.takeIf { it > 0 }?.coerceAtMost(TARGET_FPS_MAX)

    DisposableEffect(Unit) {
        onDispose { SidebarTextInputFocus.active = false }
    }

    OutlinedTextField(
        value = text,
        onValueChange = { t -> text = t.filter(Char::isDigit).take(4) },
        singleLine = true,
        label = { Text("Target FPS") },
        placeholder = { Text("e.g. 60") },
        suffix = { Text("FPS") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focus ->
                val wasActive = SidebarTextInputFocus.active
                SidebarTextInputFocus.active = focus.isFocused
                if (wasActive && !focus.isFocused) {
                    val v = value() ?: return@onFocusChanged
                    text = v.toString()
                    if (v != applied) {
                        applied = v
                        onFpsChanged(v)
                    }
                }
            }
    )
}

@Composable
private fun FpsLimiterCard(initialFps: Int, onFpsChanged: (Int) -> Unit) {
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
            label = "FPS Limit",
            valueText = when {
                position <= 0 -> "Off"
                position >= FPS_CUSTOM_POSITION -> "Custom"
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
        if (position >= FPS_CUSTOM_POSITION) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = customText,
                // Only edit the text here. Applying per keystroke would briefly cap the game at
                // 1 FPS, then 14, ... while typing "144", and save to disk each time.
                onValueChange = { text -> customText = text.filter(Char::isDigit).take(4) },
                singleLine = true,
                label = { Text("Max FPS") },
                placeholder = { Text("e.g. 144") },
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
