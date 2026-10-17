package com.winlator.cmod.ui

import com.winlator.cmod.R
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.ui.theme.controlAccentColor
import com.winlator.cmod.widget.WinlatorHUD
import kotlin.math.roundToInt

// What the panel needs to render one frame. The scale/alpha percents must be the actually-persisted
// values (WinlatorHUD / FrameRating .getSavedScalePercent/getSavedAlphaPercent) — they used to be
// seeded at 0 regardless of the saved value, causing the sliders to always open at 0%. Fixed at the
// call site in XServerDisplayActivity.setupSidebarHudControls(). Modern and Classic keep separate
// size/opacity settings, so each has its own pair.
data class HudPanelState(
    val hudOn: Boolean,
    val isModernStyle: Boolean,
    val hudScalePercent: Int,
    val hudAlphaPercent: Int,
    val classicScalePercent: Int,
    val classicAlphaPercent: Int,
    val showLogsRow: Boolean
)

interface HudPanelCallbacks {
    fun onHudMasterToggled(enabled: Boolean, styleIsModern: Boolean)
    fun onStyleChanged(isModern: Boolean)
    /** commit = false while dragging (live preview only), true once on release (persist). */
    fun onHudScale(percent: Int, commit: Boolean, isModern: Boolean)
    fun onHudAlpha(percent: Int, commit: Boolean, isModern: Boolean)
    fun onResetHudLayout(isModern: Boolean)
    fun onShowLogs()
}

object HudSidebarPanelHost {
    @JvmStatic
    fun attach(sidebar: IngameSidebarController, panelId: Int, state: HudPanelState, callbacks: HudPanelCallbacks) {
        sidebar.setPanel(panelId) { HudSidebarPanel(state, callbacks) }
    }
}

private val HUD_STYLE_LABELS = listOf("Classic", "Modern")

// Slider positions for the default HUD layout (WinlatorHUD / FrameRating .resetLayout): size 50% =
// 1.0x, opacity 100%.
private const val HUD_DEFAULT_SCALE_PERCENT = 50f
private const val HUD_DEFAULT_ALPHA_PERCENT = 100f

@Composable
private fun HudSidebarPanel(state: HudPanelState, callbacks: HudPanelCallbacks) {
    var hudOn by remember { mutableStateOf(state.hudOn) }
    var isModern by remember { mutableStateOf(state.isModernStyle) }
    // Hoisted (not inside the cards) so "Reset HUD Layout" can snap the sliders back and they keep
    // their value while the HUD is toggled off and on, or the style is switched back and forth.
    var modernScale by remember { mutableStateOf(state.hudScalePercent.toFloat()) }
    var modernAlpha by remember { mutableStateOf(state.hudAlphaPercent.toFloat()) }
    var classicScale by remember { mutableStateOf(state.classicScalePercent.toFloat()) }
    var classicAlpha by remember { mutableStateOf(state.classicAlphaPercent.toFloat()) }
    val scale = if (isModern) modernScale else classicScale
    val alpha = if (isModern) modernAlpha else classicAlpha
    // Bumped by Reset: the metric checkboxes and the dual-cell toggle keep their own copy of the
    // prefs, so they are re-created (and re-read) when the prefs are put back to defaults.
    var metricsGeneration by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        SidebarPanelTitle("HUD")

        SidebarToggleRow(
            label = uiStr(R.string.ui_enable_hud),
            checked = hudOn,
            onCheckedChange = {
                hudOn = it
                callbacks.onHudMasterToggled(it, isModern)
            }
        )

        if (hudOn) {
            SidebarGap()
            SidebarCard {
                SidebarDropdownField(
                    caption = uiStr(R.string.ui_style),
                    options = HUD_STYLE_LABELS,
                    selectedIndex = if (isModern) 1 else 0,
                    onSelect = { index ->
                        val modern = index == 1
                        isModern = modern
                        callbacks.onStyleChanged(modern)
                    }
                )
            }
        }

        // Metrics only exist on the Modern HUD. Everything below is shown only while the HUD is on
        // (previously gated on isModern alone, so it stayed visible under a disabled HUD while the
        // Style picker right above was already hidden).
        if (hudOn && isModern) {
            SidebarGap()
            SidebarCard {
                SidebarSectionTitle(uiStr(R.string.ui_hud_metrics))
                Spacer(Modifier.height(4.dp))
                key(metricsGeneration) { HudMetricsGrid() }
            }
        }

        // Size and opacity: both styles, each with its own saved values.
        if (hudOn) {
            SidebarGap()
            SidebarCard {
                SidebarSlider(
                    label = uiStr(R.string.ui_hud_size),
                    valueText = "${scale.roundToInt()}%",
                    value = scale,
                    onValueChange = {
                        if (isModern) modernScale = it else classicScale = it
                        callbacks.onHudScale(it.toInt(), false, isModern)
                    },
                    onValueChangeFinished = { callbacks.onHudScale(scale.toInt(), true, isModern) },
                    valueRange = 0f..100f
                )
                Spacer(Modifier.height(6.dp))
                SidebarSlider(
                    label = uiStr(R.string.ui_hud_opacity),
                    valueText = "${alpha.roundToInt()}%",
                    value = alpha,
                    onValueChange = {
                        if (isModern) modernAlpha = it else classicAlpha = it
                        callbacks.onHudAlpha(it.toInt(), false, isModern)
                    },
                    onValueChangeFinished = { callbacks.onHudAlpha(alpha.toInt(), true, isModern) },
                    valueRange = 0f..100f
                )
            }

            // Puts the current style's HUD back to its defaults: 1.0x size and full opacity for
            // both; for Modern also the top-left corner, horizontal layout, all metrics shown and
            // dual-cell correction off. Doesn't change whether the HUD is on.
            SidebarGap()
            SidebarActionRow(label = uiStr(R.string.ui_reset_hud_layout)) {
                callbacks.onResetHudLayout(isModern)
                if (isModern) {
                    modernScale = HUD_DEFAULT_SCALE_PERCENT
                    modernAlpha = HUD_DEFAULT_ALPHA_PERCENT
                    metricsGeneration++
                } else {
                    classicScale = HUD_DEFAULT_SCALE_PERCENT
                    classicAlpha = HUD_DEFAULT_ALPHA_PERCENT
                }
            }
        }

        if (state.showLogsRow) {
            SidebarGap()
            SidebarActionRow(label = uiStr(R.string.ui_show_logs), onClick = callbacks::onShowLogs)
        }
    }
}

// Fully self-contained: reads/writes WinlatorHUD's own static prefs directly — no Java
// round-trip needed since WinlatorHUD's rendering already reacts to the same prefs key
// through its own SharedPreferences.OnSharedPreferenceChangeListener.
@Composable
private fun HudMetricsGrid() {
    val context = LocalContext.current
    MetricCheckboxRow(context, "FPS", WinlatorHUD.SHOW_FPS, uiStr(R.string.ui_renderer), WinlatorHUD.SHOW_RENDERER)
    MetricCheckboxRow(context, uiStr(R.string.ui_gpu_usage), WinlatorHUD.SHOW_GPU_USAGE, uiStr(R.string.ui_gpu_name), WinlatorHUD.SHOW_GPU_NAME)
    MetricCheckboxRow(context, uiStr(R.string.ui_cpu_usage), WinlatorHUD.SHOW_CPU_USAGE, uiStr(R.string.ui_cpu_temp), WinlatorHUD.SHOW_CPU_TEMP)
    MetricCheckboxRow(context, "RAM", WinlatorHUD.SHOW_RAM, uiStr(R.string.ui_power), WinlatorHUD.SHOW_POWER)
    MetricCheckboxRow(context, uiStr(R.string.ui_battery_temp), WinlatorHUD.SHOW_BATTERY_TEMP, uiStr(R.string.ui_charge_state), WinlatorHUD.SHOW_CHARGE_STATE)
    Spacer(Modifier.height(6.dp))
    DualCellRow(context)
}

@Composable
private fun MetricCheckboxRow(context: Context, leftLabel: String, leftBit: Int, rightLabel: String, rightBit: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MetricCheckbox(context, leftLabel, leftBit, Modifier.weight(1f))
        MetricCheckbox(context, rightLabel, rightBit, Modifier.weight(1f))
    }
}

@Composable
private fun MetricCheckbox(context: Context, label: String, bit: Int, modifier: Modifier) {
    var checked by remember { mutableStateOf(WinlatorHUD.isOptionEnabled(context, bit)) }
    // The whole cell is the touch target, so the Checkbox itself takes no click handler: that
    // drops Material's forced 48dp touch box around it (it drew only ~20dp but reserved 48),
    // which is what squeezed "Battery Temp" / "CPU Usage" into "…" in half the sidebar width.
    Row(
        modifier = modifier
            .heightIn(min = 40.dp)
            .toggleable(value = checked, role = Role.Checkbox) {
                checked = it
                WinlatorHUD.setOptionPreference(context, bit, it)
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(checkedColor = controlAccentColor())
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            modifier = Modifier.padding(end = 4.dp),
            style = SidebarText.small(),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2
        )
    }
}

@Composable
private fun DualCellRow(context: Context) {
    val prefs = remember { context.getSharedPreferences(WinlatorHUD.PREFS, Context.MODE_PRIVATE) }
    var checked by remember { mutableStateOf(prefs.getBoolean(WinlatorHUD.KEY_DUAL_CELL, false)) }
    SidebarInlineToggle(
        label = uiStr(R.string.ui_dual_cell_correction),
        checked = checked,
        onCheckedChange = {
            checked = it
            prefs.edit().putBoolean(WinlatorHUD.KEY_DUAL_CELL, it).apply()
        }
    )
}
