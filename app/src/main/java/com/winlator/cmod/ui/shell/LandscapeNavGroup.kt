package com.winlator.cmod.ui.shell

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.ui.theme.SlidingPillHost
import com.winlator.cmod.ui.theme.SlidingPillState
import com.winlator.cmod.ui.theme.WinZMotion
import com.winlator.cmod.ui.theme.WinZShapes
import com.winlator.cmod.ui.theme.hairlineColor
import com.winlator.cmod.ui.theme.rememberSlidingPillState
import com.winlator.cmod.ui.theme.slidingPillTarget
import com.winlator.cmod.ui.theme.TablerIcons
import androidx.compose.ui.graphics.vector.ImageVector

// Landscape counterpart of PortraitBottomNavigation: Library / Input Controls / Settings as one
// floating group pinned top-end (MainShell places it in ShellChrome's header band). It used to be
// three icons inside every screen's own header, so it faded out and back in with each tab switch
// and sat at a slightly different spot on each screen; now it's drawn once and stays put.
//
// A translucent surface card (like the portrait bar) keeps it legible both on flat backgrounds
// and over Library's full-bleed artwork. Width: 3 × 44dp + 2 × 2dp spacing + 2 × 3dp padding =
// 142dp — keep ShellChrome.LandscapeNavReserve in sync.
//
// Selection is one sliding pill (SlidingPillHost), same as the portrait bar.

// Corner radius of the pill = WinZShapes.Medium.
private val NAV_PILL_RADIUS = 14.dp

@Composable
internal fun LandscapeNavGroup(selected: Int, onNavigate: (Int) -> Unit, modifier: Modifier = Modifier) {
    val pill = rememberSlidingPillState()
    Surface(
        modifier = modifier,
        shape = WinZShapes.Large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, hairlineColor())
    ) {
        SlidingPillHost(
            state = pill,
            selectedKey = selected,
            color = MaterialTheme.colorScheme.primary.copy(alpha = .12f),
            cornerRadius = NAV_PILL_RADIUS,
            modifier = Modifier.padding(3.dp),
            borderColor = MaterialTheme.colorScheme.primary.copy(alpha = .55f)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                NavCell(pill, R.id.main_menu_shortcuts, TablerIcons.Home, stringResource(R.string.ui_library), selected == R.id.main_menu_shortcuts) {
                    onNavigate(R.id.main_menu_shortcuts)
                }
                NavCell(pill, R.id.main_menu_input_controls, TablerIcons.DeviceGamepad2, stringResource(R.string.ui_input_controls), selected == R.id.main_menu_input_controls) {
                    onNavigate(R.id.main_menu_input_controls)
                }
                NavCell(pill, R.id.main_menu_settings, TablerIcons.Settings, stringResource(R.string.settings), selected == R.id.main_menu_settings) {
                    onNavigate(R.id.main_menu_settings)
                }
            }
        }
    }
}

// Same selection/press behaviour as the portrait nav items: tint cross-fade, press scale-down.
// The pill itself belongs to the group (it slides between cells), not to a cell.
@Composable
private fun NavCell(
    pill: SlidingPillState,
    id: Int,
    icon: ImageVector,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val tint by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onBackground.copy(alpha = .68f),
        animationSpec = tween(WinZMotion.DurationSelectionTint),
        label = "landscapeNavTint"
    )
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, tween(100), label = "landscapeNavPress")

    // The pill's target is this outer box: no graphicsLayer on it, so the press-scale of the
    // inner box can't shift the measured bounds.
    Box(Modifier.size(44.dp).slidingPillTarget(pill, id)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(WinZShapes.Medium)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, description, tint = tint, modifier = Modifier.size(23.dp))
        }
    }
}
