package com.winlator.cmod.ui

import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.cmod.ui.theme.accentSwitchColors
import com.winlator.cmod.ui.theme.controlAccentColor
import com.winlator.cmod.ui.theme.destructiveColor
import com.winlator.cmod.ui.theme.sidebarCardFillColor
import com.winlator.cmod.ui.theme.hairlineColor

// Single source of truth for every in-game sidebar panel (Graphics/Screen/Input/HUD/
// TaskManager). Each panel used to carry its own private copy of PanelCard/PanelActionRow/
// InlineToggleRow/dropdown, and those copies had already drifted apart (action rows at
// 48dp vs 56dp, bold-accent vs plain labels, dropdowns with and without the arrow, none of
// them using the app-wide accent+checkmark selected-item style). Everything here is
// `internal` so it stays inside the module and doesn't leak into the Java-facing API.
//
// Width budget these are sized for: drawer 344dp − card margins 16dp − rail 58dp −
// content padding 38dp ≈ 232dp of content, ≈ 204dp inside a card. That's why labeled
// dropdowns are stacked (caption above, full-width field below) rather than label-left /
// value-right — "Upscaler Mode" + "Color Boost" + arrow does not fit on one 204dp line
// at readable sizes.

// ---------- Metrics ----------

internal object SidebarDimens {
    val CardGap = 10.dp          // vertical gap between cards/rows in a panel
    val TitleGap = 14.dp         // gap under the panel title
    val CardPaddingH = 14.dp
    val CardPaddingV = 12.dp
    val StandaloneRowHeight = 52.dp // action rows and standalone toggle rows
    val InlineRowHeight = 48.dp     // toggle rows inside a PanelCard
    val FieldHeight = 44.dp
}

// Radii map 1:1 onto WinlatorShapes (small 10 / medium 14 / large 18 / extraLarge 22),
// so the sidebar no longer mixes in one-off 13/15dp values.
@Composable internal fun sidebarCardShape() = MaterialTheme.shapes.large
@Composable internal fun sidebarMenuShape() = MaterialTheme.shapes.medium
@Composable internal fun sidebarFieldShape() = MaterialTheme.shapes.small

// ---------- Text styles ----------
// Sidebar-specific sizes derived from the app's typography via copy(). (WinlatorTypography
// now defines labelMedium/labelSmall/bodySmall too, but these keep their own 13/12sp
// metrics tuned for the sidebar's ~204dp content width.)

internal object SidebarText {
    @Composable fun title(): TextStyle =
        MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)

    @Composable fun section(): TextStyle = MaterialTheme.typography.titleMedium

    @Composable fun row(): TextStyle = MaterialTheme.typography.bodyLarge

    @Composable fun value(): TextStyle =
        MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)

    @Composable fun caption(): TextStyle =
        MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp)

    @Composable fun small(): TextStyle =
        MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp)
}

// ---------- Building blocks ----------

@Composable
internal fun SidebarPanelTitle(text: String) {
    Text(
        text = text,
        style = SidebarText.title(),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    Spacer(Modifier.height(SidebarDimens.TitleGap))
}

@Composable
internal fun SidebarSectionTitle(text: String) {
    Text(text = text, style = SidebarText.section(), color = MaterialTheme.colorScheme.onSurface)
}

@Composable
internal fun SidebarCaption(text: String) {
    Text(text = text, style = SidebarText.caption(), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun SidebarGap() {
    Spacer(Modifier.height(SidebarDimens.CardGap))
}

@Composable
private fun Modifier.sidebarCardSurface(): Modifier {
    val shape = sidebarCardShape()
    return this
        .clip(shape)
        .background(sidebarCardFillColor())
        .border(BorderStroke(1.dp, hairlineColor()), shape)
}

@Composable
internal fun SidebarCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .sidebarCardSurface()
            .padding(horizontal = SidebarDimens.CardPaddingH, vertical = SidebarDimens.CardPaddingV),
        content = content
    )
}

// accent = the panel's primary action (Save Preset, + New Task) — bold accent label.
// Everything else (Reset HUD, Show Keyboard, PiP…) is a plain onSurface label.
@Composable
internal fun SidebarActionRow(label: String, accent: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SidebarDimens.StandaloneRowHeight)
            .sidebarCardSurface()
            .clickable(onClick = onClick)
            .padding(horizontal = SidebarDimens.CardPaddingH),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = if (accent) SidebarText.row().copy(fontWeight = FontWeight.SemiBold) else SidebarText.row(),
            color = if (accent) controlAccentColor() else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// Toggle row that lives inside a SidebarCard next to other rows.
@Composable
internal fun SidebarInlineToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    labelStyle: TextStyle = SidebarText.row()
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SidebarDimens.InlineRowHeight)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
            style = labelStyle,
            color = MaterialTheme.colorScheme.onSurface
        )
        Switch(checked = checked, onCheckedChange = null, colors = accentSwitchColors())
    }
}

// Toggle that stands on its own — same 52dp footprint as SidebarActionRow so a column
// mixing actions and toggles (Screen, Input mouse rows, Enable HUD) lines up.
@Composable
internal fun SidebarToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SidebarDimens.StandaloneRowHeight)
            .sidebarCardSurface()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(start = SidebarDimens.CardPaddingH, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
            style = SidebarText.row(),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = accentSwitchColors()
        )
    }
}

// Accent label + accent value header, then the slider. onValueChangeFinished is optional
// so both "live" sliders (HUD size) and "commit on release" ones (FPS limit) fit.
@Composable
internal fun SidebarSlider(
    label: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChangeFinished: (() -> Unit)? = null
) {
    val accent = controlAccentColor()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = SidebarText.value(),
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(text = valueText, style = SidebarText.value(), color = accent)
    }
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent)
    )
}

// ---------- Menus ----------

// Same container as every other menu in the app (medium radius, surface fill).
@Composable
internal fun SidebarMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = Modifier.widthIn(min = 160.dp, max = 300.dp).heightIn(max = 360.dp),
        // Opens just below its anchor with a small gap instead of touching it.
        offset = DpOffset(0.dp, 4.dp),
        shape = sidebarMenuShape(),
        containerColor = MaterialTheme.colorScheme.surface,
        content = content
    )
}

// App-wide choice-list item style (see CompactDropdown / OrientationToggleMenuItem):
// selected = accent SemiBold text + trailing accent checkmark.
@Composable
internal fun SidebarChoiceItem(label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = controlAccentColor()
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        trailingIcon = { if (selected) Icon(Icons.Outlined.Check, contentDescription = null, tint = accent) },
        onClick = onClick
    )
}

// Plain action item (Task Manager's per-process menu). destructive = red text + icon.
@Composable
internal fun SidebarActionItem(
    label: String,
    leadingIcon: (@Composable () -> Unit)? = null,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    val tint = if (destructive) destructiveColor() else MaterialTheme.colorScheme.onSurface
    DropdownMenuItem(
        text = { Text(text = label, color = tint) },
        leadingIcon = leadingIcon,
        onClick = onClick
    )
}

// Full-width picker field: current value + arrow, opens the shared choice menu.
// Optional caption sits above it (stacked layout, see width budget note at the top).
@Composable
internal fun SidebarDropdownField(
    caption: String?,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        if (caption != null) {
            SidebarCaption(caption)
            Spacer(Modifier.height(6.dp))
        }
        Box(Modifier.fillMaxWidth()) {
            val shape = sidebarFieldShape()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SidebarDimens.FieldHeight)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(BorderStroke(1.dp, hairlineColor()), shape)
                    .clickable { expanded = true }
                    .padding(start = 12.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = options.getOrElse(selectedIndex) { options.firstOrNull().orEmpty() },
                    modifier = Modifier.weight(1f),
                    style = SidebarText.row(),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            SidebarMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEachIndexed { index, label ->
                    SidebarChoiceItem(label = label, selected = index == selectedIndex) {
                        expanded = false
                        onSelect(index)
                    }
                }
            }
        }
    }
}
