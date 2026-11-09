package com.winlator.cmod.ui.theme

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Category navigation shared by the container editor and the shortcut editor (General / Video /
// Compatibility / Input / ...): a vertical list in landscape, a horizontally scrolling row in
// portrait. Selection is one sliding pill (SlidingPillHost) instead of each item snapping its
// own fill, and AnimatedCategoryContent animates the page that changes with it.
//
// The pill host sits INSIDE the scrolling content (the caller scrolls the column, the row scrolls
// itself), so the pill scrolls along with the items and its target never changes while scrolling.
//
// `key` is the editor's page key ("General", "Video", ...), which stays English in code; only
// `label` is shown.

@Immutable
data class CategoryNavEntry(val key: String, val label: String, val icon: ImageVector? = null)

// Corner radius of the pill = WinZShapes.Small.
private val CATEGORY_PILL_RADIUS = 10.dp

// Landscape: the list inside the side card. The caller puts it in a vertically scrolling column.
@Composable
fun CategoryNavColumn(
    entries: List<CategoryNavEntry>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val pill = rememberSlidingPillState()
    SlidingPillHost(
        state = pill,
        selectedKey = selected,
        color = MaterialTheme.colorScheme.surfaceVariant,
        cornerRadius = CATEGORY_PILL_RADIUS,
        modifier = modifier
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            entries.forEach { entry ->
                CategoryNavItem(
                    pill = pill,
                    entry = entry,
                    selected = entry.key == selected,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                ) { onSelect(entry.key) }
            }
        }
    }
}

// Portrait: a row that scrolls horizontally (it was a LazyRow; there are only a handful of
// categories, and a plain scrolling row lets the pill live in the scrolled content).
@Composable
fun CategoryNavRow(
    entries: List<CategoryNavEntry>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val pill = rememberSlidingPillState()
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        SlidingPillHost(
            state = pill,
            selectedKey = selected,
            color = MaterialTheme.colorScheme.surfaceVariant,
            cornerRadius = CATEGORY_PILL_RADIUS
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                entries.forEach { entry ->
                    CategoryNavItem(
                        pill = pill,
                        entry = entry,
                        selected = entry.key == selected,
                        modifier = Modifier.padding(vertical = 2.dp)
                    ) { onSelect(entry.key) }
                }
            }
        }
    }
}

@Composable
private fun CategoryNavItem(
    pill: SlidingPillState,
    entry: CategoryNavEntry,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val contentColor by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(WinZMotion.DurationSelectionTint),
        label = "categoryNavTint"
    )

    // Keep the selected item on screen (a chip scrolled half out of view in the portrait row, or
    // an item below the fold in the landscape list). Waits one frame so the node is laid out.
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(selected) {
        if (selected) {
            withFrameNanos { }
            try {
                requester.bringIntoView()
            } catch (ignored: IllegalStateException) {
                // Not attached any more (the page was left while waiting): nothing to scroll to.
            }
        }
    }

    Surface(
        onClick = onClick,
        // Target bounds are measured after the caller's padding, so the pill covers exactly the
        // item's own surface.
        modifier = modifier.slidingPillTarget(pill, entry.key).bringIntoViewRequester(requester),
        shape = WinZShapes.Small,
        color = Color.Transparent,
        contentColor = contentColor
    ) {
        val weight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        if (entry.icon != null) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(entry.icon, null, modifier = Modifier.size(16.dp))
                Text(entry.label, fontWeight = weight)
            }
        } else {
            Text(entry.label, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), fontWeight = weight)
        }
    }
}

// The page that belongs to the selected category. The old page fades out quickly, the new one
// fades in a beat later while sliding a few dp in the direction of travel (down/right when the
// new category is further along the list), and the height follows. `vertical` matches the nav:
// true for the landscape side list, false for the portrait row.
//
// IMPORTANT: draw the page from the `category` this lambda receives, not from the caller's
// state — while the old page animates out it must still show the OLD category.
@Composable
fun AnimatedCategoryContent(
    target: String,
    order: List<String>,
    vertical: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (String) -> Unit
) {
    val shiftPx = with(LocalDensity.current) { 18.dp.roundToPx() }
    AnimatedContent(
        targetState = target,
        modifier = modifier.fillMaxWidth(),
        transitionSpec = {
            val sign = if (order.indexOf(targetState) >= order.indexOf(initialState)) 1 else -1
            val fade = fadeIn(tween(WinZMotion.DurationM, delayMillis = 70, easing = LinearOutSlowInEasing))
            val slide = if (vertical) {
                slideInVertically(tween(WinZMotion.DurationTabSlide, easing = FastOutSlowInEasing)) { sign * shiftPx }
            } else {
                slideInHorizontally(tween(WinZMotion.DurationTabSlide, easing = FastOutSlowInEasing)) { sign * shiftPx }
            }
            (fade + slide).togetherWith(fadeOut(tween(90, easing = FastOutLinearInEasing)))
                .using(SizeTransform(clip = false) { _, _ -> tween(WinZMotion.DurationM, easing = FastOutSlowInEasing) })
        },
        label = "categoryContent"
    ) { category -> content(category) }
}
