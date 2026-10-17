package com.winlator.cmod.ui.theme

import com.winlator.cmod.R
import com.winlator.cmod.ui.uiStr
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun WinlatorThemeChoices(
    selected: WinlatorThemeType,
    onSelected: (WinlatorThemeType) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(9.dp)) {
        WinlatorThemeType.values().forEach { theme ->
            ThemeChoiceRow(
                theme = theme,
                selected = theme == selected,
                onClick = { onSelected(theme) }
            )
        }
    }
}

@Composable
private fun ThemeChoiceRow(
    theme: WinlatorThemeType,
    selected: Boolean,
    onClick: () -> Unit
) {
    val preview = winlatorThemePreview(theme)
    val accent = controlAccentColor()
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = WinZShapes.Medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.90f) else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            1.dp,
            if (selected) accent else MaterialTheme.colorScheme.outlineVariant
        ),
        tonalElevation = 0.dp
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ThemePreview(preview)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(theme.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    theme.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selected) {
                Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(22.dp), tint = accent)
            }
        }
    }
}

@Composable
private fun ThemePreview(colors: ThemePreviewColors) {
    Surface(
        modifier = Modifier.size(48.dp),
        shape = WinZShapes.Medium,
        color = colors.background,
        border = BorderStroke(1.dp, colors.outline)
    ) {
        Box(Modifier.padding(8.dp)) {
            // Inner "surface" swatch. The real colorScheme.surface is ~90% translucent and almost
            // equal to the background in every theme (AMOLED: #050505 on #000), so it used to
            // vanish into the tile. It now uses an opaque, clearly lighter/darker tone plus its
            // own outline so it stays readable in White, Dark and AMOLED.
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .size(17.dp)
                    .background(colors.surface, WinZShapes.ExtraSmall)
                    .border(1.dp, colors.outline, WinZShapes.ExtraSmall)
            )
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(14.dp)
                    .background(colors.accent, CircleShape)
                    .border(1.dp, colors.outline, CircleShape)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WinlatorThemePreferenceCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val current = WinlatorThemeManager.currentTheme()
    var expanded by remember { mutableStateOf(false) }

    Surface(
        onClick = { expanded = true },
        modifier = modifier.fillMaxWidth(),
        shape = WinZShapes.Medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, hairlineColor()),
        tonalElevation = 0.dp
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f), WinZShapes.Small),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Palette, contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(uiStr(R.string.ui_theme), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(current.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (expanded) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { expanded = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 24.dp)
            ) {
                Text(uiStr(R.string.ui_appearance), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    uiStr(R.string.ui_choose_the_winlator_theme_changes_are_app),
                    modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider(color = dividerColor())
                Spacer(Modifier.size(12.dp))
                WinlatorThemeChoices(
                    selected = current,
                    onSelected = { WinlatorThemeManager.setTheme(context, it) }
                )
            }
        }
    }
}

internal data class ThemePreviewColors(
    val background: Color,
    val surface: Color,
    val accent: Color,
    val outline: Color
)

internal fun winlatorThemePreview(theme: WinlatorThemeType): ThemePreviewColors {
    val scheme = winlatorColorScheme(theme)
    return ThemePreviewColors(
        background = scheme.background,
        // Opaque token with real contrast against `background` (surfaceContainerHighest:
        // White #E1E3E8, Dark #313130, AMOLED #191919) instead of the translucent `surface`.
        surface = scheme.surfaceContainerHighest,
        accent = scheme.primary,
        outline = scheme.outline
    )
}
