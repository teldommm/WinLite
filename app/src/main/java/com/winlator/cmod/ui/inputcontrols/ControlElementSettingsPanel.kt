package com.winlator.cmod.ui.inputcontrols

import com.winlator.cmod.R
import com.winlator.cmod.ui.uiStr
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.winlator.cmod.ui.SidebarCaption
import com.winlator.cmod.ui.SidebarDropdownField
import com.winlator.cmod.ui.theme.accentSwitchColors
import com.winlator.cmod.ui.theme.controlAccentColor
import com.winlator.cmod.ui.theme.destructiveColor
import kotlin.math.roundToInt
import com.winlator.cmod.ui.theme.hairlineColor

enum class ControlElementIconTint { NONE, INVERT, BLUE }

data class ControlElementIcon(
    val key: String,
    val bitmap: Bitmap,
    val selected: Boolean,
    val tint: ControlElementIconTint,
    val dimmed: Boolean = false,
    val longPressable: Boolean = false
)

data class ControlElementBindingRow(
    val label: String,
    val sourceTypeIndex: Int,
    val options: List<String>,
    val selectedOptionIndex: Int
)

data class ControlElementSettingsModel(
    val typeIndex: Int,
    val typeOptions: List<String>,
    val showShape: Boolean,
    val shapeIndex: Int,
    val shapeOptions: List<String>,
    val showRange: Boolean,
    val rangeIndex: Int,
    val rangeOptions: List<String>,
    // Orientation and Columns only mean anything for RANGE_BUTTON (bounding box / drawing /
    // JSON all check the type); Columns calls setBindingCount(), which wipes every binding, so
    // showing it for a Button/D-Pad/Stick/Trackpad silently destroyed their bindings.
    val showOrientation: Boolean,
    val verticalOrientation: Boolean,
    val showColumns: Boolean,
    val columns: Int,
    val columnsMin: Int,
    val columnsMax: Int,
    val scalePercent: Int,
    val opacityPercent: Int,
    val colorOptions: List<Int>,
    val selectedColor: Int,
    val showToggleSwitch: Boolean,
    val toggleSwitch: Boolean,
    // mouseMoveMode is only read in the BUTTON touch paths.
    val showMouseMoveMode: Boolean,
    val mouseMoveMode: Boolean,
    // Buttons and D-pads only. Shown but inert while Toggle Switch / Relative Mouse Move is on
    // (see ControlElement.isSwipeEnabled), with a note saying so.
    // STICK only.
    val showDynamicStick: Boolean,
    val dynamicStick: Boolean,
    val zoneScalePercent: Int,
    val followSpeedPercent: Int,
    val showSwipeable: Boolean,
    val swipeable: Boolean,
    val swipeableBlocked: Boolean,
    val showTextAndIcon: Boolean,
    val text: String,
    val icons: List<ControlElementIcon>,
    val hasCustomIcon: Boolean,
    val bindings: List<ControlElementBindingRow>
)

private val BINDING_SOURCE_TYPES = listOf(uiStr(R.string.keyboard), uiStr(R.string.ui_mouse), uiStr(R.string.ui_gamepad))

// Scale was 0..100%: it couldn't enlarge anything, 0% collapsed the element, and just
// touching it clamped bundled profiles' 1.2 / 1.25 scales down to 1.0.
private val SCALE_RANGE = 50f..200f

// Mirrors ControlElement.MIN_ZONE_SCALE / MAX_ZONE_SCALE (x100).
private val ZONE_SCALE_RANGE = 150f..500f

// Body of the floating element-settings panel (hosted by ControlsEditorOverlay). Every choice
// list is an in-place dropdown (SidebarDropdownField — the same field + menu the in-game
// sidebar uses) rather than a separate dialog.
@Composable
internal fun ColumnScope.ControlElementSettingsPanelContent(
    model: ControlElementSettingsModel,
    cb: ControlElementSettingsCallbacks,
    onClose: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            uiStr(R.string.ui_element_settings),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Outlined.Close, contentDescription = uiStr(R.string.ui_close), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
    Spacer(Modifier.height(10.dp))
    Column(
        Modifier
            .weight(1f, fill = false)
            .verticalScroll(rememberScrollState())
    ) {
        SidebarDropdownField(uiStr(R.string.ui_type), model.typeOptions, model.typeIndex, cb::onTypeChanged)
        if (model.showShape) {
            Spacer(Modifier.height(10.dp))
            SidebarDropdownField(uiStr(R.string.ui_shape), model.shapeOptions, model.shapeIndex, cb::onShapeChanged)
        }
        if (model.showRange) {
            Spacer(Modifier.height(10.dp))
            SidebarDropdownField(uiStr(R.string.ui_range), model.rangeOptions, model.rangeIndex, cb::onRangeChanged)
        }

        if (model.showOrientation) {
            Spacer(Modifier.height(14.dp))
            SidebarCaption(uiStr(R.string.orientation))
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SegmentButton(uiStr(R.string.horizontal), !model.verticalOrientation, Modifier.weight(1f)) { cb.onOrientationChanged(false) }
                SegmentButton(uiStr(R.string.vertical), model.verticalOrientation, Modifier.weight(1f)) { cb.onOrientationChanged(true) }
            }
        }

        if (model.showColumns) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SidebarCaption(uiStr(R.string.ui_columns)) }
                Stepper(model.columns, model.columnsMin, model.columnsMax, cb::onColumnsChanged)
            }
        }

        Spacer(Modifier.height(14.dp))
        PercentSlider(uiStr(R.string.ui_scale), model.scalePercent, SCALE_RANGE, cb::onScalePreview, cb::onScaleChanged)
        Spacer(Modifier.height(6.dp))
        PercentSlider(uiStr(R.string.ui_opacity), model.opacityPercent, 0f..100f, cb::onOpacityPreview, cb::onOpacityChanged)

        if (model.showDynamicStick) {
            Spacer(Modifier.height(6.dp))
            SettingSwitchRow(uiStr(R.string.ui_dynamic_stick), model.dynamicStick, cb::onDynamicStickChanged)
            if (model.dynamicStick) {
                Spacer(Modifier.height(8.dp))
                PercentSlider(
                    uiStr(R.string.ui_zone_size),
                    model.zoneScalePercent,
                    ZONE_SCALE_RANGE,
                    cb::onZoneScalePreview,
                    cb::onZoneScaleChanged,
                    valueLabel = { String.format("%.1f\u00D7", it / 100f) }
                )
                Spacer(Modifier.height(6.dp))
                PercentSlider(
                    uiStr(R.string.ui_follow_speed),
                    model.followSpeedPercent,
                    0f..100f,
                    onPreview = {},
                    onChange = cb::onFollowSpeedChanged
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        SidebarCaption(uiStr(R.string.ui_color))
        Spacer(Modifier.height(8.dp))
        ColorSwatchGrid(model.colorOptions, model.selectedColor, cb::onColorSelected)
        Spacer(Modifier.height(10.dp))
        HexColorField(model.selectedColor, cb::onColorSelected)

        if (model.showToggleSwitch) {
            Spacer(Modifier.height(10.dp))
            SettingSwitchRow(uiStr(R.string.ui_toggle_switch), model.toggleSwitch, cb::onToggleSwitchChanged)
        }
        if (model.showMouseMoveMode) {
            SettingSwitchRow(uiStr(R.string.ui_relative_mouse_move), model.mouseMoveMode, cb::onMouseMoveModeChanged)
        }
        if (model.showSwipeable) {
            if (!model.showToggleSwitch) Spacer(Modifier.height(10.dp))
            SettingSwitchRow(uiStr(R.string.ui_swipe_between_buttons), model.swipeable, cb::onSwipeableChanged)
        }

        if (model.showTextAndIcon) {
            Spacer(Modifier.height(10.dp))
            ThemedTextField(uiStr(R.string.ui_custom_text), model.text, cb::onTextChanged)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SidebarCaption(uiStr(R.string.ui_icon)) }
                if (model.hasCustomIcon) {
                    SmallOutlineButton(uiStr(R.string.remove), destructive = true, onClick = cb::onRemoveIcon)
                    Spacer(Modifier.width(8.dp))
                }
                SmallOutlineButton(uiStr(R.string.ui_browse_2026), onClick = cb::onBrowseIcon)
            }
            Spacer(Modifier.height(8.dp))
            IconRow(model.icons, cb)
        }

        if (model.bindings.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(uiStr(R.string.ui_bindings), style = MaterialTheme.typography.titleMedium)
            model.bindings.forEachIndexed { index, binding ->
                Spacer(Modifier.height(10.dp))
                BindingRow(
                    binding,
                    onSourceTypeChanged = { cb.onBindingSourceTypeChanged(index, it) },
                    onValueChanged = { cb.onBindingValueChanged(index, it) }
                )
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun SegmentButton(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier,
            shape = MaterialTheme.shapes.small,
            colors = ButtonDefaults.buttonColors(containerColor = controlAccentColor(), contentColor = Color.White)
        ) { Text(label) }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            shape = MaterialTheme.shapes.small,
            colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
            border = BorderStroke(1.dp, hairlineColor())
        ) { Text(label) }
    }
}

@Composable
private fun SmallOutlineButton(label: String, destructive: Boolean = false, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (destructive) destructiveColor() else MaterialTheme.colorScheme.onSurface
        ),
        border = BorderStroke(1.dp, hairlineColor())
    ) { Text(label) }
}

@Composable
private fun Stepper(value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    val disabled = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { if (value > min) onChange(value - 1) }, enabled = value > min, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Outlined.Remove, uiStr(R.string.ui_fewer), tint = if (value > min) MaterialTheme.colorScheme.onSurface else disabled, modifier = Modifier.size(20.dp))
        }
        Text(value.toString(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(28.dp), textAlign = TextAlign.Center)
        IconButton(onClick = { if (value < max) onChange(value + 1) }, enabled = value < max, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Outlined.Add, uiStr(R.string.ui_more), tint = if (value < max) MaterialTheme.colorScheme.onSurface else disabled, modifier = Modifier.size(20.dp))
        }
    }
}

// Caption left, live value right, continuous slider below. While dragging, onPreview pushes
// each whole-percent step to the canvas (no save); onChange commits once on release.
@Composable
private fun PercentSlider(
    title: String,
    percent: Int,
    range: ClosedFloatingPointRange<Float>,
    onPreview: (Int) -> Unit,
    onChange: (Int) -> Unit,
    valueLabel: (Int) -> String = { "$it%" }
) {
    var live by remember(percent) { mutableStateOf(percent.toFloat().coerceIn(range)) }
    val accent = controlAccentColor()
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SidebarCaption(title) }
            Text(
                valueLabel(live.roundToInt()),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Slider(
            value = live,
            onValueChange = {
                val before = live.roundToInt()
                live = it
                val now = it.roundToInt()
                if (now != before) onPreview(now)
            },
            // Always reported, even when released back on the starting value: it also closes
            // the edit that the first preview opened (undo snapshot bookkeeping in the Activity).
            onValueChangeFinished = { onChange(live.roundToInt()) },
            modifier = Modifier.fillMaxWidth(),
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent)
        )
    }
}

// Also used by the toolbar's scheme-color dropdown (ControlsEditorOverlay).
@Composable
internal fun ColorSwatchGrid(colors: List<Int>, selectedColor: Int, onSelected: (Int) -> Unit) {
    val perRow = 8
    val accent = controlAccentColor()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        colors.chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { color ->
                    val isSelected = color == selectedColor
                    // Selected = accent ring with a gap around the swatch, so it stays visible on
                    // white/light swatches too (the old plain white border vanished on them).
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .then(if (isSelected) Modifier.border(2.dp, accent, CircleShape) else Modifier)
                            .padding(if (isSelected) 4.dp else 1.dp)
                            .clip(CircleShape)
                            .background(if (color != 0) Color(color) else Color.White)
                            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onSelected(color) }
                    )
                }
            }
        }
    }
}

// "#RRGGBB" entry next to the palette (from WinLite's color picker). Applies as soon as six
// valid hex digits are in; anything shorter or invalid just waits. Empty = the default color
// (same as the palette's first swatch). Resyncs whenever the color changes elsewhere.
@Composable
internal fun HexColorField(color: Int, onColor: (Int) -> Unit) {
    val accent = controlAccentColor()
    val formatted = if (color == 0) "" else String.format("%06X", color and 0xFFFFFF)
    var text by remember(color) { mutableStateOf(formatted) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(if (color != 0) Color(color) else Color.White)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
        )
        Spacer(Modifier.width(10.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                val cleaned = input.removePrefix("#").filter { it.isLetterOrDigit() }.take(6).uppercase()
                text = cleaned
                when {
                    cleaned.isEmpty() -> if (color != 0) onColor(0)
                    cleaned.length == 6 && cleaned.all { it in '0'..'9' || it in 'A'..'F' } -> {
                        val value = (0xFF000000.toInt()) or cleaned.toInt(16)
                        if (value != color) onColor(value)
                    }
                }
            },
            singleLine = true,
            prefix = { Text("#", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            placeholder = { Text(uiStr(R.string.ui_default)) },
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.weight(1f),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = accent,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                cursorColor = accent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
}

@Composable
private fun SettingSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Switch(checked = checked, onCheckedChange = null, colors = accentSwitchColors())
    }
}

@Composable
private fun ThemedTextField(label: String, value: String, onValueChange: (String) -> Unit) {
    val accent = controlAccentColor()
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        label = { Text(label) },
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = accent,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            focusedLabelColor = accent,
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            cursorColor = accent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    )
}

// One horizontally scrolling row, like WinLite's icon strip (was a vertical grid capped at
// 220dp, which ate most of the panel's height). The "no icon" tile (built-in 0) comes first, then
// the custom icons, then the other built-ins, in the order the Activity builds the list.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IconRow(icons: List<ControlElementIcon>, cb: ControlElementSettingsCallbacks) {
    val shape = MaterialTheme.shapes.small
    val accent = controlAccentColor()
    val listState = rememberLazyListState()
    val selectedIndex = icons.indexOfFirst { it.selected }
    // Only scroll when the selected icon is off-screen (panel opened, or re-targeted to another
    // element) — tapping a visible icon must not shift the row under the finger.
    LaunchedEffect(selectedIndex) {
        if (selectedIndex < 0) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == selectedIndex }
        if (!visible) listState.scrollToItem((selectedIndex - 1).coerceAtLeast(0))
    }
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(icons, key = { it.key }) { icon ->
            val colorFilter = when (icon.tint) {
                ControlElementIconTint.NONE -> null
                // Not a theme color: this previews how the built-in icon is actually drawn on the
                // control in-game (InputControlsView's icon ColorFilter uses the same #2184FF).
                ControlElementIconTint.BLUE -> ColorFilter.tint(Color(0xff2184ff))
                ControlElementIconTint.INVERT -> ColorFilter.colorMatrix(
                    ColorMatrix(
                        floatArrayOf(
                            -1f, 0f, 0f, 0f, 255f,
                            0f, -1f, 0f, 0f, 255f,
                            0f, 0f, -1f, 0f, 255f,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                )
            }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .then(if (icon.selected) Modifier.border(2.dp, accent, shape) else Modifier)
                    .combinedClickable(
                        onClick = { cb.onIconSelected(icon.key) },
                        onLongClick = if (icon.longPressable) ({ cb.onIconLongPress(icon.key) }) else null
                    )
                    .alpha(if (icon.dimmed) 0.75f else 1f),
                contentAlignment = Alignment.Center
            ) {
                Image(icon.bitmap.asImageBitmap(), null, modifier = Modifier.size(30.dp), colorFilter = colorFilter)
            }
        }
    }
}

@Composable
private fun BindingRow(binding: ControlElementBindingRow, onSourceTypeChanged: (Int) -> Unit, onValueChanged: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SidebarCaption(binding.label)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(0.42f)) {
                SidebarDropdownField(null, BINDING_SOURCE_TYPES, binding.sourceTypeIndex, onSourceTypeChanged)
            }
            Box(Modifier.weight(0.58f)) {
                SidebarDropdownField(null, binding.options, binding.selectedOptionIndex, onValueChanged)
            }
        }
    }
}
