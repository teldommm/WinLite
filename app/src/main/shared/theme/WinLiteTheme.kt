package com.winlator.cmod.shared.theme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.winlator.cmod.R

val WinLiteBackground = Color(0xFF18181D)
val WinLiteSurface = Color(0xFF1C1C2A)
val WinLiteSurfaceAlt = Color(0xFF21212A)
val WinLitePanel = Color(0xFF161622)
val WinLiteOutline = Color(0xFF2A2A3A)
val WinLiteAccent = Color(0xFF1A9FFF)
val WinLiteTextPrimary = Color(0xFFF0F4FF)
val WinLiteTextSecondary = Color(0xFF7A8FA8)
val WinLiteDanger = Color(0xFFFF7A88)

private val WinLiteColorScheme =
    darkColorScheme(
        primary = WinLiteAccent,
        background = WinLiteBackground,
        surface = WinLiteSurface,
        onSurface = WinLiteTextPrimary,
        onBackground = WinLiteTextPrimary,
    )

val WinLiteFontFamily =
    FontFamily(
        Font(R.font.inter_medium, FontWeight.Normal),
        Font(R.font.inter_medium, FontWeight.Medium),
        Font(R.font.inter_medium, FontWeight.SemiBold),
        Font(R.font.inter_medium, FontWeight.Bold),
    )

private val BaseTypography = Typography()

val WinLiteTypography =
    Typography(
        displayLarge = BaseTypography.displayLarge.copy(fontFamily = WinLiteFontFamily),
        displayMedium = BaseTypography.displayMedium.copy(fontFamily = WinLiteFontFamily),
        displaySmall = BaseTypography.displaySmall.copy(fontFamily = WinLiteFontFamily),
        headlineLarge = BaseTypography.headlineLarge.copy(fontFamily = WinLiteFontFamily),
        headlineMedium = BaseTypography.headlineMedium.copy(fontFamily = WinLiteFontFamily),
        headlineSmall = BaseTypography.headlineSmall.copy(fontFamily = WinLiteFontFamily),
        titleLarge = BaseTypography.titleLarge.copy(fontFamily = WinLiteFontFamily),
        titleMedium = BaseTypography.titleMedium.copy(fontFamily = WinLiteFontFamily),
        titleSmall = BaseTypography.titleSmall.copy(fontFamily = WinLiteFontFamily),
        bodyLarge = BaseTypography.bodyLarge.copy(fontFamily = WinLiteFontFamily),
        bodyMedium = BaseTypography.bodyMedium.copy(fontFamily = WinLiteFontFamily),
        bodySmall = BaseTypography.bodySmall.copy(fontFamily = WinLiteFontFamily),
        labelLarge = BaseTypography.labelLarge.copy(fontFamily = WinLiteFontFamily),
        labelMedium = BaseTypography.labelMedium.copy(fontFamily = WinLiteFontFamily),
        labelSmall = BaseTypography.labelSmall.copy(fontFamily = WinLiteFontFamily),
    )

@Composable
fun WinLiteTheme(
    colorScheme: ColorScheme = WinLiteColorScheme,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = colorScheme,
        typography = WinLiteTypography,
        content = content,
    )
}
