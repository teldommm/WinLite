package com.winlator.cmod.ui

import androidx.compose.ui.res.stringResource
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.ui.theme.SlidingPillState
import com.winlator.cmod.ui.theme.SlidingPillHost
import com.winlator.cmod.ui.theme.WinZMotion
import com.winlator.cmod.ui.theme.WinZShapes
import com.winlator.cmod.ui.theme.hairlineColor
import com.winlator.cmod.ui.theme.rememberSlidingPillState
import com.winlator.cmod.ui.theme.slidingPillTarget
import com.winlator.cmod.ui.theme.TablerIcons
import androidx.compose.ui.graphics.vector.ImageVector

// Portrait bottom navigation (Library / Input Controls / Settings). Rendered by MainShell
// (ui/shell/MainShell.kt) as a floating bar over the tab content; it no longer has its own
// ComposeView or native container (the WinZBottomNavigationView FrameLayout and the
// PortraitBottomNavigationHost bridge are gone). Destinations are the same main_menu_* ids
// MainActivity.navigateToMainDestination() routes.
//
// Selection: one pill (SlidingPillHost) slides from the old item to the new one while the
// icon/label tint cross-fades alongside it, in step with the tab slide in MainShell. When the
// selected tab has no button here (File Manager) the pill fades out. A press gives a small
// scale-down of the icon so a tap is acknowledged immediately.

// Corner radius of the pill = WinZShapes.Medium.
private val NAV_PILL_RADIUS = 14.dp

@Composable
internal fun PortraitBottomNavigation(
    selected: Int,
    onNavigate: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val pill = rememberSlidingPillState()
    Surface(
        modifier = modifier.fillMaxWidth().height(64.dp),
        shape = WinZShapes.ExtraLarge,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, hairlineColor())
    ) {
        SlidingPillHost(
            state = pill,
            selectedKey = selected,
            color = MaterialTheme.colorScheme.primary.copy(alpha = .12f),
            cornerRadius = NAV_PILL_RADIUS,
            modifier = Modifier.fillMaxSize(),
            borderColor = MaterialTheme.colorScheme.primary.copy(alpha = .55f)
        ) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly) {
                PortraitNavItem(pill, R.id.main_menu_shortcuts, TablerIcons.Home, stringResource(R.string.ui_library), selected == R.id.main_menu_shortcuts) {
                    onNavigate(R.id.main_menu_shortcuts)
                }
                PortraitNavItem(pill, R.id.main_menu_input_controls, TablerIcons.DeviceGamepad2, stringResource(R.string.ui_input_controls), selected == R.id.main_menu_input_controls) {
                    onNavigate(R.id.main_menu_input_controls)
                }
                PortraitNavItem(pill, R.id.main_menu_settings, TablerIcons.Settings, stringResource(R.string.settings), selected == R.id.main_menu_settings) {
                    onNavigate(R.id.main_menu_settings)
                }
            }
        }
    }
}

@Composable
private fun RowScope.PortraitNavItem(
    pill: SlidingPillState,
    id: Int,
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val tint by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onBackground.copy(alpha = .68f),
        animationSpec = tween(WinZMotion.DurationSelectionTint),
        label = "navTint"
    )
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.92f else 1f, tween(100), label = "navPress")

    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // The pill's target is this outer box: it carries no graphicsLayer of its own, so the
        // press-scale below can't shift the measured bounds.
        Box(
            Modifier
                .height(28.dp)
                .width(52.dp)
                .slidingPillTarget(pill, id)
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = pressScale
                        scaleY = pressScale
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, label, tint = tint, modifier = Modifier.height(20.dp))
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            color = tint,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1
        )
    }
}
