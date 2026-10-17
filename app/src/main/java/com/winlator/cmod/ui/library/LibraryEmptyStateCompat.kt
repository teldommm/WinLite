package com.winlator.cmod.ui.library

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.cmod.MainActivity
import com.winlator.cmod.R
import com.winlator.cmod.ui.theme.controlAccentColor
import com.winlator.cmod.ui.theme.WinZShapes
import com.winlator.cmod.ui.theme.hairlineColor

@Composable
internal fun LibraryRootWithoutEmptyDescription(
    items: List<LibraryItem>,
    grid: Boolean,
    query: String,
    selectedShortcutPath: MutableState<String?>,
    callbacks: LibraryCallbacks
) {
    if (items.isNotEmpty() || query.isNotBlank()) {
        LibraryRoot(items, grid, query, selectedShortcutPath, callbacks)
        return
    }

    val activity = LocalContext.current as? MainActivity
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 14.dp)
    ) {
        if (landscape) {
            LibraryLandscapeHeader(
                activity = activity,
                grid = grid,
                onArtwork = false,
                query = query,
                onGridViewChanged = callbacks::onGridViewChanged,
                onSearchQueryChanged = callbacks::onSearchQueryChanged
            )
        } else {
            LibraryPortraitHeader(
                activity = activity,
                grid = grid,
                query = query,
                onGridViewChanged = callbacks::onGridViewChanged,
                onSearchQueryChanged = callbacks::onSearchQueryChanged,
                onOpenFileManager = callbacks::onOpenFileManager
            )
        }
        Row(
            Modifier.padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            EmptyFilterChip(libraryFilterLabel(LibraryFilter.All), true)
            EmptyFilterChip(libraryFilterLabel(LibraryFilter.Favorites), false)
            EmptyFilterChip(libraryFilterLabel(LibraryFilter.Recent), false)
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                onClick = { activity?.navigateToMainDestination(R.id.main_menu_file_manager) },
                modifier = Modifier.fillMaxWidth(0.90f).widthIn(max = 320.dp),
                shape = WinZShapes.ExtraLarge,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, hairlineColor())
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        modifier = Modifier.size(72.dp),
                        shape = WinZShapes.ExtraLarge,
                        color = controlAccentColor(),
                        contentColor = androidx.compose.ui.graphics.Color.White
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Add, null, modifier = Modifier.size(34.dp))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.ui_add_games),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyFilterChip(label: String, selected: Boolean) {
    Surface(
        shape = WinZShapes.Small,
        color = if (selected) translucentControlFill() else androidx.compose.ui.graphics.Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, hairlineColor())
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge
        )
    }
}
