package com.winlator.cmod.app.shell

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.cmod.R
import com.winlator.cmod.shared.ui.focus.controllerFocusGlow
import com.winlator.cmod.shared.ui.nav.DialogPaneNav
import com.winlator.cmod.shared.ui.nav.LocalPaneNav
import com.winlator.cmod.shared.ui.nav.PaneNavRegistry
import com.winlator.cmod.shared.ui.nav.paneNavItem

private val LaunchBlack = Color.Black
private val LaunchCard = Color(0xFF12121B)
private val LaunchTextPrimary = Color(0xFFF0F4FF)
private val LaunchTextSecondary = Color(0xFF93A6BC)
private val LaunchDanger = Color(0xFFFF6B6B)

@Composable
internal fun LaunchDangerConfirmDialog(
    visible: Boolean,
    title: String,
    message: String,
    confirmLabel: String,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    icon: ImageVector = Icons.Outlined.Warning,
    titleTextAlign: TextAlign = TextAlign.Start,
    messageTextAlign: TextAlign = TextAlign.Start,
    accentColor: Color = LaunchDanger,
    cancelColor: Color = LaunchTextSecondary,
) {
    if (!visible) return

    val registry = remember { PaneNavRegistry() }
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        CompositionLocalProvider(LocalPaneNav provides registry) {
            DialogPaneNav(registry, onDismiss = onDismissRequest)
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(LaunchBlack.copy(alpha = 0.46f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismissRequest,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier =
                        Modifier
                            .width(286.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { },
                            ),
                    shape = RoundedCornerShape(12.dp),
                    color = LaunchCard,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                    shadowElevation = 14.dp,
                    tonalElevation = 0.dp,
                ) {
                    LaunchDangerConfirmContent(
                        title = title,
                        message = message,
                        confirmLabel = confirmLabel,
                        onDismissRequest = onDismissRequest,
                        onConfirm = onConfirm,
                        icon = icon,
                        titleTextAlign = titleTextAlign,
                        messageTextAlign = messageTextAlign,
                        accentColor = accentColor,
                        cancelColor = cancelColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun LaunchDangerConfirmContent(
    title: String,
    message: String,
    confirmLabel: String,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    icon: ImageVector,
    titleTextAlign: TextAlign,
    messageTextAlign: TextAlign,
    accentColor: Color,
    cancelColor: Color,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (titleTextAlign == TextAlign.Center) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier =
                        Modifier
                            .align(Alignment.CenterStart)
                            .size(18.dp),
                )
                Text(
                    title,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 28.dp),
                    color = LaunchTextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    title,
                    color = LaunchTextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            message,
            modifier = Modifier.fillMaxWidth(),
            color = LaunchTextSecondary,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            textAlign = messageTextAlign,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LaunchMenuTextAction(
                label = stringResource(R.string.common_ui_cancel),
                textColor = cancelColor,
                onClick = onDismissRequest,
                modifier = Modifier.paneNavItem(onActivate = onDismissRequest),
            )
            LaunchMenuTextAction(
                label = confirmLabel,
                textColor = accentColor,
                onClick = onConfirm,
                modifier = Modifier.paneNavItem(onActivate = onConfirm, isEntry = true),
            )
        }
    }
}

@Composable
private fun LaunchMenuTextAction(
    label: String,
    textColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(8.dp))
                .controllerFocusGlow(cornerRadius = 8.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}
