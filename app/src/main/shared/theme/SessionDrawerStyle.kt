package com.winlator.cmod.shared.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object SessionDrawerStyle {
    const val SheetAlpha = 0.86f
    const val SurfaceAlpha = 0.72f
    const val PressedAlpha = 0.88f
    const val GradientLift = 0.014f

    val Accent = Color(0xFF2196F3)
    val ActiveAccent = Color(0xFF29B6F6)
    val FocusFill = Color(0xFF0E2438)
    val TextPrimary = WinLiteTextPrimary.copy(alpha = 0.88f)
    val TextSecondary = WinLiteTextSecondary.copy(alpha = 0.82f)
    val Outline = WinLiteOutline
    val Background = WinLiteBackground.copy(alpha = SheetAlpha)
    val PaneSurface = WinLiteBackground.copy(alpha = SheetAlpha)
    val PaneSurfacePressed = Color(0xFF232B3A).copy(alpha = PressedAlpha)
    val TopRailSurface = WinLiteSurface.copy(alpha = SheetAlpha)
    val TileResting = Color(0xFF20283A).copy(alpha = SurfaceAlpha)
    val TileExitResting = Color(0xFF3A2125).copy(alpha = SurfaceAlpha)
    val TileExitPressed = Color(0xFF4A2A30).copy(alpha = PressedAlpha)
    val PaneInnerResting = WinLitePanel.copy(alpha = SurfaceAlpha)
    val PaneInnerPressed = Color(0xFF242B3A).copy(alpha = PressedAlpha)
    val RestingCardBorder = WinLiteOutline.copy(alpha = 0.72f)
    val DisabledCardBorder = Color(0xFF202033).copy(alpha = 0.58f)
    val ActiveCardBorder = ActiveAccent
    val GlassExitTint = Color(0xFFE07B6B)
    val Divider = WinLiteOutline.copy(alpha = 0.6f)

    val Width = 300.dp
    val StartPadding = 6.dp
    val VerticalPadding = 6.dp
    const val PaneScaleMin = 0.78f
    const val PaneScaleReferenceHeightDp = 520f
}

object GameSettingsStyle {
    val BgDeep = Color(0xFF11111C)
    val SidebarBg = Color(0xFF11111C)
    val ContentBg = Color(0xFF11111C)
    val CardSurface = WinLiteSurface
    val CardBorder = WinLiteOutline
    val InputSurface = Color(0xFF171722)
    val InputBorder = WinLiteOutline
    val AccentBlue = WinLiteAccent
    val TextPrimary = WinLiteTextPrimary
    val TextSecondary = WinLiteTextSecondary
    val TextDim = Color(0xFF6E7681)
    val Divider = WinLiteOutline
    val CheckBorder = WinLiteOutline
    val SliderInactive = WinLiteSurfaceAlt
    val ChipSurface = Color(0xFF171722)
    val ChipBorder = WinLiteOutline
    val DangerRed = Color(0xFFFF6B6B)
    val WarningAmber = Color(0xFFFFB74D)
    val NavHighlight = Color(0xFF4FC3F7)
}
