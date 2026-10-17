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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.ui.theme.WinZShapes
import com.winlator.cmod.ui.theme.hairlineColor

// Landscape counterpart of PortraitBottomNavigation: Library / Input Controls / Settings as one
// floating group pinned top-end (MainShell places it in ShellChrome's header band). It used to be
// three icons inside every screen's own header, so it faded out and back in with each tab switch
// and sat at a slightly different spot on each screen; now it's drawn once and stays put.
//
// A translucent surface card (like the portrait bar) keeps it legible both on flat backgrounds
// and over Library's full-bleed artwork. Width: 3 × 44dp + 2 × 2dp spacing + 2 × 3dp padding =
// 142dp — keep ShellChrome.LandscapeNavReserve in sync.
@Composable
internal fun LandscapeNavGroup(selected: Int, onNavigate: (Int) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = WinZShapes.Large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, hairlineColor())
    ) {
        Row(Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            NavCell(Icons.Outlined.Home, stringResource(R.string.ui_library), selected == R.id.main_menu_shortcuts) {
                onNavigate(R.id.main_menu_shortcuts)
            }
            NavCell(Icons.Outlined.SportsEsports, stringResource(R.string.ui_input_controls), selected == R.id.main_menu_input_controls) {
                onNavigate(R.id.main_menu_input_controls)
            }
            NavCell(Icons.Outlined.Settings, stringResource(R.string.settings), selected == R.id.main_menu_settings) {
                onNavigate(R.id.main_menu_settings)
            }
        }
    }
}

// Same selection/press behaviour as the portrait nav items: 180ms pill + tint, press scale-down.
@Composable
private fun NavCell(icon: ImageVector, description: String, selected: Boolean, onClick: () -> Unit) {
    val tint by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onBackground.copy(alpha = .68f),
        animationSpec = tween(180),
        label = "landscapeNavTint"
    )
    val pill by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else MaterialTheme.colorScheme.primary.copy(alpha = 0f), // not Color.Transparent: that fades through black
        animationSpec = tween(180),
        label = "landscapeNavPill"
    )
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, tween(100), label = "landscapeNavPress")

    Box(
        modifier = Modifier
            .size(44.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(WinZShapes.Medium)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Surface(shape = WinZShapes.Medium, color = pill, modifier = Modifier.size(44.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, description, tint = tint, modifier = Modifier.size(23.dp))
            }
        }
    }
}
