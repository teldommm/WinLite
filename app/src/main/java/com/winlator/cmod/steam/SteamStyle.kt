package com.winlator.cmod.steam

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.ui.theme.TablerIcons
import com.winlator.cmod.ui.theme.WinZShapes
import com.winlator.cmod.ui.theme.WinZTheme
import com.winlator.cmod.ui.theme.accentSwitchColors
import com.winlator.cmod.ui.theme.controlAccentColor
import com.winlator.cmod.ui.theme.destructiveColor
import com.winlator.cmod.ui.theme.dividerColor
import com.winlator.cmod.ui.theme.hairlineColor
import com.winlator.cmod.ui.uiStr

// Steam screens use WinLite's own look and nothing of their own: the WinZ theme (black / white /
// AMOLED), its shapes and the same card, row, header and control treatment as Settings.

@Composable internal fun StoreTheme(content: @Composable () -> Unit) = WinZTheme(content)

@Composable internal fun Modifier.storeBackground(): Modifier = background(MaterialTheme.colorScheme.background)

/** Bordered card, the same surface as Settings' group cards and navigation rows. */
@Composable internal fun SteamCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: androidx.compose.ui.graphics.Shape = WinZShapes.Medium,
    content: @Composable ColumnScope.() -> Unit
) {
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, hairlineColor())) { Column(content = content) }
    } else {
        Surface(modifier = modifier, shape = shape, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, hairlineColor())) { Column(content = content) }
    }
}

@Composable internal fun StoreSection(content: @Composable ColumnScope.() -> Unit) = SteamCard(Modifier.fillMaxWidth(), content = content)

@Composable internal fun SteamDivider() = HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = dividerColor())

@Composable internal fun SteamSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier = modifier.padding(start = 3.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Tinted rounded tile holding an icon: the leading element of every settings-style row. */
@Composable internal fun SteamIconTile(icon: ImageVector, modifier: Modifier = Modifier) {
    Surface(modifier.size(38.dp), shape = WinZShapes.Small, color = MaterialTheme.colorScheme.surfaceVariant) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(21.dp)) }
    }
}

/** Row inside a [SteamCard]: icon tile, title and subtitle, optional trailing content. */
@Composable internal fun SteamRow(
    icon: ImageVector, title: String, subtitle: String? = null, enabled: Boolean = true,
    onClick: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = { Icon(TablerIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SteamIconTile(icon)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) { Spacer(Modifier.width(8.dp)); trailing() }
    }
}

/** Switch row: the whole row toggles, the Switch itself is display-only (as in Settings). */
@Composable internal fun SteamToggleRow(icon: ImageVector, title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChecked)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SteamIconTile(icon)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, colors = accentSwitchColors())
    }
}

/** Header of a pushed screen: back arrow, title/subtitle and trailing actions. */
@Composable internal fun SteamTopBar(title: String, subtitle: String? = null, onBack: () -> Unit, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(TablerIcons.ArrowLeft, uiStr(R.string.steam_ui_back)) }
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

/** Selectable pill used for the library tabs; neutral fill when selected, hairline outline otherwise. */
@Composable internal fun SteamTab(selected: Boolean, label: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = WinZShapes.Small,
        color = if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, hairlineColor())
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(icon, null, Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable internal fun steamButtonColors() = ButtonDefaults.buttonColors(containerColor = controlAccentColor(), contentColor = Color.White)

@Composable internal fun steamDestructiveColors() = ButtonDefaults.buttonColors(containerColor = destructiveColor(), contentColor = Color.White)
