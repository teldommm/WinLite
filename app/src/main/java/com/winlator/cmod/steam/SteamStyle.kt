package com.winlator.cmod.steam

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.winlator.cmod.ui.theme.SlidingPillHost
import com.winlator.cmod.ui.theme.SlidingPillState
import com.winlator.cmod.ui.theme.WinZMotion
import com.winlator.cmod.ui.theme.keepInViewWhen
import com.winlator.cmod.ui.theme.rememberSlidingPillState
import com.winlator.cmod.ui.theme.slidingPillTarget
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
import com.winlator.cmod.ui.theme.DialogSystemBarsPolicy
import com.winlator.cmod.ui.SidebarChoiceItem
import com.winlator.cmod.ui.library.translucentControlFill
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
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

/** One entry of a [SteamTabRow]. The icon is optional (the store-details tabs have none). */
internal class SteamTabItem(val key: Int, val label: String, val icon: ImageVector? = null)

/**
 * Library tabs: exactly WinLite's All / Favorites / Recent chips (same SlidingPillHost, translucent pill,
 * 14dp radius, 15x8dp padding, accent-tinted outline on the selected chip), with an optional icon. The
 * pill target sits on the chip's CONTENT, not on the clickable Surface: a clickable Surface pads itself to
 * the 48dp touch target outside its border, which used to make the pill taller than the chip.
 */
@Composable internal fun SteamTabRow(tabs: List<SteamTabItem>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val pill = rememberSlidingPillState()
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        SlidingPillHost(pill, selected, translucentControlFill(), 14.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tabs.forEach { tab -> SteamTabChip(pill, tab, tab.key == selected) { onSelect(tab.key) } }
            }
        }
    }
}

@Composable private fun SteamTabChip(pill: SlidingPillState, tab: SteamTabItem, selected: Boolean, click: () -> Unit) {
    val content by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        tween(WinZMotion.DurationSelectionTint), label = "steamTabTint"
    )
    val outline by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .55f) else hairlineColor(),
        tween(WinZMotion.DurationSelectionTint), label = "steamTabOutline"
    )
    Surface(onClick = click, shape = WinZShapes.Medium, color = Color.Transparent, contentColor = content, border = BorderStroke(1.dp, outline)) {
        Box(Modifier.slidingPillTarget(pill, tab.key).keepInViewWhen(selected)) {
            Row(Modifier.padding(horizontal = 15.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                tab.icon?.let { Icon(it, null, Modifier.size(18.dp)) }
                Text(tab.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

/**
 * Exclusive switch with a sliding accent pill: the Steam counterpart of the Preset / Custom switch in
 * Add environment variable. The pill glides between options and the label tint cross-fades.
 */
@Composable internal fun SteamSegmented(options: List<SteamTabItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val accent = controlAccentColor()
    val index = options.indexOfFirst { it.key == selected }.coerceAtLeast(0)
    Surface(modifier.fillMaxWidth(), shape = WinZShapes.Medium, color = MaterialTheme.colorScheme.surfaceVariant, border = BorderStroke(1.dp, hairlineColor())) {
        val slide by animateFloatAsState(index.toFloat(), tween(WinZMotion.DurationPill, easing = FastOutSlowInEasing), label = "steamSegmentSlide")
        val radius = with(androidx.compose.ui.platform.LocalDensity.current) { 10.dp.toPx() }
        Row(Modifier.padding(3.dp).drawBehind {
            val w = size.width / options.size
            drawRoundRect(accent, topLeft = Offset(slide * w, 0f), size = Size(w, size.height), cornerRadius = CornerRadius(radius))
        }) {
            options.forEach { option ->
                val on = option.key == selected
                val tint by animateColorAsState(if (on) Color.White else MaterialTheme.colorScheme.onSurface, tween(WinZMotion.DurationSelectionTint), label = "steamSegmentTint")
                Surface(onClick = { onSelect(option.key) }, modifier = Modifier.weight(1f), shape = WinZShapes.Small, color = Color.Transparent, contentColor = tint) {
                    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        option.icon?.let { Icon(it, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
                        Text(option.label, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Progress bar that glides to its new value instead of stepping. */
@Composable internal fun SteamProgress(progress: Float, modifier: Modifier = Modifier) {
    com.winlator.cmod.ui.theme.WinZProgressBar(progress, modifier)
}

@Composable internal fun steamButtonColors() = ButtonDefaults.buttonColors(containerColor = controlAccentColor(), contentColor = Color.White)

@Composable internal fun steamDestructiveColors() = ButtonDefaults.buttonColors(containerColor = destructiveColor(), contentColor = Color.White)

/** Button row of a dialog: secondary on the left of the primary, right-aligned like the app's own dialogs. */
@Composable internal fun SteamDialogButtons(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically, content = content)

@Composable private fun steamSecondaryColors() =
    ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface)

/** Cancel / back / close: the outlined button every WinLite dialog uses for the non-primary action. */
@Composable internal fun SteamSecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null, onClick: () -> Unit) =
    OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled, colors = steamSecondaryColors(), border = BorderStroke(1.dp, hairlineColor())) {
        icon?.let { Icon(it, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }

/** The confirming action: accent fill, or the destructive red for removals. */
@Composable internal fun SteamPrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, destructive: Boolean = false, icon: ImageVector? = null, onClick: () -> Unit) =
    Button(onClick = onClick, modifier = modifier, enabled = enabled, colors = if (destructive) steamDestructiveColors() else steamButtonColors()) {
        icon?.let { Icon(it, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }

/** In-page primary action (Play / Download / Resume): accent fill, white content, WinLite's 14dp button radius. */
@Composable internal fun SteamAccentButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null, height: Dp = 48.dp, onClick: () -> Unit) =
    Button(onClick = onClick, modifier = modifier.heightIn(min = height), enabled = enabled, shape = WinZShapes.Medium, colors = steamButtonColors()) {
        icon?.let { Icon(it, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }

/** Outlined in-page action on the theme surface (Pause, Cancel download, ...), 14dp radius like [SteamAccentButton]. */
@Composable internal fun SteamOutlineButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null, onClick: () -> Unit) =
    OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled, shape = WinZShapes.Medium, colors = steamSecondaryColors(), border = BorderStroke(1.dp, hairlineColor())) {
        icon?.let { Icon(it, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }

/** Field-like button that opens a choice menu: current value plus a chevron (branch, language, container, exe...). */
@Composable internal fun SteamDropdownButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, maxLines: Int = 1, onClick: () -> Unit) =
    OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled, shape = WinZShapes.Medium, colors = steamSecondaryColors(),
        border = BorderStroke(1.dp, hairlineColor()), contentPadding = PaddingValues(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp)) {
        Text(text, Modifier.weight(1f, fill = false), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(6.dp))
        Icon(TablerIcons.ChevronDown, null, Modifier.size(18.dp))
    }

/** Dropdown menu container every WinLite menu uses: 14dp radius, themed (translucent) surface fill. */
@Composable internal fun SteamMenu(expanded: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = modifier, shape = WinZShapes.Medium, containerColor = MaterialTheme.colorScheme.surface, content = content)

/** Choice-list item of WinLite's menus: selected = accent semibold text with a trailing accent check. */
@Composable internal fun SteamChoiceItem(label: String, selected: Boolean, onClick: () -> Unit) = SidebarChoiceItem(label, selected, onClick)

/**
 * Bottom sheet exactly like WinLite's own (Appearance, drive picker, game menu): the translucent theme surface,
 * no tonal tint, a small drag handle, and [DialogSystemBarsPolicy] first, so opening the sheet no longer slides
 * the Android status bar in and shoves the layout.
 */
@Composable internal fun SteamSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        dragHandle = {
            Surface(
                modifier = Modifier.padding(top = 10.dp, bottom = 8.dp).size(width = 36.dp, height = 4.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .42f)
            ) {}
        }
    ) {
        DialogSystemBarsPolicy()
        Column(
            Modifier.fillMaxWidth()
                .widthIn(max = if (landscape) 920.dp else 760.dp)
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                .verticalScroll(rememberScrollState())
                .padding(bottom = if (landscape) 10.dp else 24.dp)
                .align(Alignment.CenterHorizontally),
            content = content
        )
    }
}

/** Tile of a sheet's action row: the same fill and outline as the tiles of WinLite's game menu. */
@Composable internal fun SteamActionTile(icon: ImageVector, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Surface(
        onClick = onClick, enabled = enabled, modifier = modifier, shape = WinZShapes.Medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .42f), border = BorderStroke(1.dp, hairlineColor())
    ) {
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(23.dp), tint = tint)
            Spacer(Modifier.width(9.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Compact search field of WinLite's Library header (44dp, surface fill, hairline outline, clear button). */
@Composable internal fun SteamSearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val shape = WinZShapes.Medium
    androidx.compose.foundation.text.BasicTextField(
        value = query, onValueChange = onQueryChange, singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(controlAccentColor()),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
        modifier = modifier.focusRequester(focusRequester),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().height(44.dp).clip(shape).background(MaterialTheme.colorScheme.surface)
                    .border(BorderStroke(1.dp, hairlineColor()), shape).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(TablerIcons.Search, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    if (query.isEmpty()) Text(uiStr(R.string.steam_ui_search_games), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    inner()
                }
                if (query.isNotEmpty()) Box(
                    Modifier.offset(x = 8.dp).size(width = 36.dp, height = 44.dp).clip(WinZShapes.Small).clickable { onQueryChange("") },
                    contentAlignment = Alignment.Center
                ) { Icon(TablerIcons.X, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    )
}
