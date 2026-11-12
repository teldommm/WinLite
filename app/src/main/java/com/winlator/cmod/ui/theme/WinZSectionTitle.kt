package com.winlator.cmod.ui.theme

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Small upper-case label above a group of cards ("APPEARANCE", "EXTERNAL CONTROLLERS"). The one
// section heading of the app: Settings and Input controls both use it so they read the same.
@Composable
fun WinZSectionTitle(
    text: String,
    modifier: Modifier = Modifier.padding(start = 3.dp, top = 16.dp, bottom = 4.dp)
) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
