package com.winlator.cmod.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.ui.theme.controlAccentColor
import com.winlator.cmod.ui.theme.destructiveColor
import com.winlator.cmod.ui.theme.dividerColor
import com.winlator.cmod.ui.theme.sidebarRailHighlightColor
import kotlinx.coroutines.launch

// One selectable rail entry (Graphics/Screen/Input/FPS/TaskManager). `id` reuses the
// panel's R.id.LLSubXxx id (res/values/ids_sidebar.xml) as its identity, so there's no separate
// parentId/subId pair to keep in sync the way the old native rail needed — selecting a
// rail item and showing its panel are now driven by the same int.
data class SidebarRailItemData(val id: Int, val iconRes: Int, val contentDescription: String)

// Callbacks are id-based (not per-item lambdas) so Java keeps one switch-like call site,
// matching how the existing panel Host objects (TaskManagerCallbacks, ScreenPanelCallbacks)
// are implemented anonymously from XServerDisplayActivity.
interface SidebarRailCallbacks {
    fun onSelect(itemId: Int)
    fun onPauseToggle()
    fun onExit()
}

// Exposed to Java so wireSidebarListeners()/openSidebarPanel() can drive selection/pause
// state without reaching back into a native View tree — same shape as TaskManagerPanelState.
class SidebarRailState(initialSelectedId: Int) {
    var selectedId: Int by mutableStateOf(initialSelectedId)
    var paused: Boolean by mutableStateOf(false)

    // Which face the combined Pause / Exit cell shows. Java resets it to false when the sidebar
    // closes, so the next open always starts on Pause and a habitual tap can't exit the game.
    var exitMode: Boolean by mutableStateOf(false)
}

// ---------- Rail metrics ----------
//
// The rail used to need ~436dp (5 sections + Pause + Exit at a fixed 48dp with 10dp gaps), more
// than most phones have in landscape (360-411dp), so Exit was pushed below the screen. Now
// Pause and Exit share one cell, and the rail fits itself to the height it gets, shrinking the
// air first and the buttons last, with no scrolling:
//   1. gaps 10 -> 4dp, 2. vertical padding 12 -> 6dp, 3. cells 48 -> 36dp (icons scale with them),
//   4. below that everything scales down proportionally, so the last cell is always on screen.
// Every step only takes what it needs: e.g. a small shortfall just tightens the gaps.
//
// Layout: sections, a divider (the same 1dp dividerColor() line as under "Version" in About),
// then the Pause / Exit cell right below it. The divider's own vertical padding is tied to the
// gap (14dp at a 10dp gap, as in About) so it compresses along with the gaps.

@Immutable
private data class RailMetrics(val padding: Dp, val gap: Dp, val cell: Dp)

private const val FULL_PAD = 12f
private const val MIN_PAD = 6f
private const val FULL_GAP = 10f
private const val MIN_GAP = 4f
private const val FULL_CELL = 48f
private const val MIN_CELL = 36f
private const val DIVIDER_PAD_PER_GAP = 1.4f
private const val DIVIDER_THICKNESS = 1f

private fun dividerPadding(gap: Dp) = gap * DIVIDER_PAD_PER_GAP

private fun railMetrics(available: Dp, cells: Int, bounded: Boolean): RailMetrics {
    val full = RailMetrics(FULL_PAD.dp, FULL_GAP.dp, FULL_CELL.dp)
    if (!bounded || cells <= 0) return full
    val h = available.value - DIVIDER_THICKNESS
    // Gap units: the plain gaps between sections plus the divider's padding above and below
    // (which replaces the gap between the last section and the Pause / Exit cell).
    val gaps = if (cells >= 2) (cells - 2) + 2 * DIVIDER_PAD_PER_GAP else 0f
    fun need(pad: Float, gap: Float, cell: Float) = 2 * pad + cells * cell + gaps * gap

    if (need(FULL_PAD, FULL_GAP, FULL_CELL) <= h) return full

    // 1. Gaps.
    if (gaps > 0f) {
        val gap = (h - 2 * FULL_PAD - cells * FULL_CELL) / gaps
        if (gap >= MIN_GAP) return RailMetrics(FULL_PAD.dp, gap.coerceAtMost(FULL_GAP).dp, FULL_CELL.dp)
    }
    // 2. Padding.
    val pad = (h - cells * FULL_CELL - gaps * MIN_GAP) / 2
    if (pad >= MIN_PAD) return RailMetrics(pad.coerceAtMost(FULL_PAD).dp, MIN_GAP.dp, FULL_CELL.dp)
    // 3. Cells.
    val cell = (h - 2 * MIN_PAD - gaps * MIN_GAP) / cells
    if (cell >= MIN_CELL) return RailMetrics(MIN_PAD.dp, MIN_GAP.dp, cell.dp)
    // 4. Proportional, no lower bound.
    val s = (h / need(MIN_PAD, MIN_GAP, MIN_CELL)).coerceIn(0f, 1f)
    return RailMetrics((MIN_PAD * s).dp, (MIN_GAP * s).dp, (MIN_CELL * s).dp)
}

// Rendered by IngameSidebar (IngameSidebar.kt) as the left column of the single sidebar
// card; no longer attached to its own ComposeView.
@Composable
internal fun SidebarRail(
    state: SidebarRailState,
    items: List<SidebarRailItemData>,
    callbacks: SidebarRailCallbacks,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            // Same translucent surface token every card/container in the app already
            // uses — composited over the panel's #151515 background this renders as
            // #1F1F1E, matching the rest of the UI without a dedicated color.
            .background(MaterialTheme.colorScheme.surface)
    ) {
        val metrics = railMetrics(maxHeight, items.size + 1, constraints.hasBoundedHeight)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 5.dp, vertical = metrics.padding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            items.forEachIndexed { index, item ->
                if (index > 0) Spacer(Modifier.height(metrics.gap))
                RailNavButton(
                    iconRes = item.iconRes,
                    contentDescription = item.contentDescription,
                    selected = state.selectedId == item.id,
                    cell = metrics.cell,
                    onClick = { callbacks.onSelect(item.id) }
                )
            }
            // Session control sits right under the sections, set apart by a divider.
            HorizontalDivider(
                modifier = Modifier
                    .padding(vertical = dividerPadding(metrics.gap))
                    .width(metrics.cell),
                thickness = DIVIDER_THICKNESS.dp,
                color = dividerColor()
            )
            RailSessionButton(state = state, callbacks = callbacks, cell = metrics.cell)
            // Whatever height is left stays empty below.
            Spacer(Modifier.weight(1f))
        }
    }
}

// Corner radius follows the cell: 14dp (the theme's medium shape) at 48dp.
private fun cellShape(cell: Dp) = RoundedCornerShape(cell * (14f / 48f))

private fun iconSize(cell: Dp) = cell * 0.5f

@Composable
private fun RailCell(
    selected: Boolean,
    description: String?,
    cell: Dp,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    // Fade the highlight by alpha only. It used to animate the colour to Color.Transparent,
    // which is transparent *black*: the colour interpolation passed through dark grey, so the
    // cell you left flashed a black square for a moment.
    val fillColor = sidebarRailHighlightColor()
    val highlight by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(150),
        label = "railCellHighlight"
    )
    Box(
        modifier = Modifier
            .size(cell)
            .clip(cellShape(cell))
            .background(fillColor.copy(alpha = fillColor.alpha * highlight))
            .clickable(onClick = onClick)
            .semantics { if (description != null) contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun RailNavButton(iconRes: Int, contentDescription: String?, selected: Boolean, cell: Dp, onClick: () -> Unit) {
    val tint by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "railItemTint"
    )
    RailCell(selected = selected, description = contentDescription, cell = cell, onClick = onClick) {
        Icon(painterResource(iconRes), contentDescription = null, tint = tint, modifier = Modifier.size(iconSize(cell)))
    }
}

// ---------- Combined Pause / Exit cell ----------
//
// One cell, two faces: Pause (or Resume while paused) and Exit. A horizontal swipe slides
// between them (left = Exit, right = Pause); a tap runs the face that is showing. The cell is
// always filled so it reads as a distinct control even at a glance, with two dots showing
// which face is up. The faces also differ by colour: Exit tints the fill red, and the paused
// state (which used to be shown by the fill) is now an accent play icon plus an accent border.
// D-pad left/right switches faces for gamepad / keyboard focus, and both actions are exposed
// as accessibility custom actions, so the swipe is never the only way.

private const val SESSION_SWIPE_THRESHOLD_DP = 16f

@Composable
private fun RailSessionButton(state: SidebarRailState, callbacks: SidebarRailCallbacks, cell: Dp) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val cellPx = with(density) { cell.toPx() }.coerceAtLeast(1f)
    val thresholdPx = with(density) { SESSION_SWIPE_THRESHOLD_DP.dp.toPx() }

    // 0 = Pause face, 1 = Exit face; follows the finger while dragging.
    val progress = remember { Animatable(if (state.exitMode) 1f else 0f) }
    val exitMode = state.exitMode
    val currentState by rememberUpdatedState(state)

    // External changes (reset on sidebar close) and settled swipes both land here.
    LaunchedEffect(exitMode) {
        val target = if (exitMode) 1f else 0f
        if (progress.value != target) progress.animateTo(target, tween(160))
    }

    fun setMode(exit: Boolean) {
        if (currentState.exitMode != exit) {
            currentState.exitMode = exit
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        } else {
            scope.launch { progress.animateTo(if (exit) 1f else 0f, tween(160)) }
        }
    }

    val paused = state.paused
    val destructive = destructiveColor()
    val accent = controlAccentColor()
    val baseFill = sidebarRailHighlightColor()
    val fill by animateColorAsState(
        if (exitMode) destructive.copy(alpha = 0.22f) else baseFill,
        label = "sessionFill"
    )
    // Same trap as the rail highlight: fade the accent itself, not towards transparent black.
    val borderColor by animateColorAsState(
        if (paused && !exitMode) accent else accent.copy(alpha = 0f),
        label = "sessionBorder"
    )
    val pauseTint by animateColorAsState(
        if (paused) accent else MaterialTheme.colorScheme.onSurface,
        label = "sessionPauseTint"
    )

    val onTap = {
        if (currentState.exitMode) callbacks.onExit() else callbacks.onPauseToggle()
    }
    val pauseLabel = if (paused) uiStr(R.string.ui_resume) else uiStr(R.string.ui_pause)
    val shape = cellShape(cell)

    Box(
        modifier = Modifier
            .size(cell)
            .clip(shape)
            .background(fill)
            .border(BorderStroke(1.5.dp, borderColor), shape)
            .pointerInput(cellPx, thresholdPx) {
                var start = 0f
                var dragged = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        start = progress.value
                        dragged = 0f
                    },
                    onDragEnd = {
                        // Past the threshold in either direction flips to that side.
                        val exit = when {
                            dragged <= -thresholdPx -> true
                            dragged >= thresholdPx -> false
                            else -> start >= 0.5f
                        }
                        setMode(exit)
                    },
                    onDragCancel = { setMode(start >= 0.5f) },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        dragged += amount
                        // Content moves with the finger: dragging left reveals Exit.
                        val p = (start - dragged / cellPx).coerceIn(0f, 1f)
                        scope.launch { progress.snapTo(p) }
                    }
                )
            }
            .clickable(onClick = onTap)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionRight -> if (!currentState.exitMode) { setMode(true); true } else false
                    Key.DirectionLeft -> if (currentState.exitMode) { setMode(false); true } else false
                    else -> false
                }
            }
            .semantics {
                role = Role.Button
                contentDescription = if (exitMode) uiStr(R.string.exit) else pauseLabel
                customActions = listOf(
                    CustomAccessibilityAction(pauseLabel) { callbacks.onPauseToggle(); true },
                    CustomAccessibilityAction(uiStr(R.string.exit)) { callbacks.onExit(); true }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        val iconModifier = Modifier.size(iconSize(cell))
        // Two faces side by side, shifted by progress: 0 shows Pause, 1 shows Exit.
        Box(Modifier.graphicsLayer { translationX = -progress.value * cellPx }, contentAlignment = Alignment.Center) {
            Icon(
                painterResource(if (paused) R.drawable.ic_sidebar_play else R.drawable.ic_sidebar_pause),
                contentDescription = null,
                tint = pauseTint,
                modifier = iconModifier
            )
        }
        Box(Modifier.graphicsLayer { translationX = (1f - progress.value) * cellPx }, contentAlignment = Alignment.Center) {
            Icon(
                painterResource(R.drawable.ic_sidebar_power),
                contentDescription = null,
                tint = destructive,
                modifier = iconModifier
            )
        }

        // Face indicator.
        val dot = cell * (4f / 48f)
        val active = MaterialTheme.colorScheme.onSurface
        val inactive = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = cell * (4f / 48f)),
            horizontalArrangement = Arrangement.spacedBy(cell * (3f / 48f))
        ) {
            val p = progress.value
            Box(Modifier.size(dot).clip(CircleShape).background(if (p < 0.5f) active else inactive))
            Box(Modifier.size(dot).clip(CircleShape).background(if (p >= 0.5f) active else inactive))
        }
    }
}
