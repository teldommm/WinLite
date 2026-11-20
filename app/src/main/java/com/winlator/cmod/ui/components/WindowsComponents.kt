@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.winlator.cmod.ui.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.components.ComponentInstaller
import com.winlator.cmod.components.LocalComponent
import com.winlator.cmod.components.LocalComponents
import com.winlator.cmod.container.Container
import com.winlator.cmod.ui.theme.TablerIcons
import com.winlator.cmod.ui.theme.ThemedDialog
import com.winlator.cmod.ui.theme.ThemedDialogTitle
import com.winlator.cmod.ui.theme.WinZProgressBar
import com.winlator.cmod.ui.theme.WinZShapes
import com.winlator.cmod.ui.theme.controlAccentColor
import com.winlator.cmod.ui.theme.hairlineColor
import com.winlator.cmod.ui.toast.WinToast
import com.winlator.cmod.ui.uiStr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Windows components" row for the container editor: opens the local component manager dialog.
 * Disabled with "Save first" until the container exists — entry points always operate on a saved
 * container's prefix.
 */
@Composable
fun WindowsComponentsRow(container: Container?) {
    val context = LocalContext.current
    var installedCount by remember(container?.id) { mutableStateOf<Int?>(null) }
    var showDialog by remember { mutableStateOf(false) }

    LaunchedEffect(container?.id) {
        installedCount = container?.let {
            withContext(Dispatchers.IO) { ComponentInstaller.installedIds(context, it).size }
        }
    }

    Surface(
        onClick = { showDialog = true },
        enabled = container != null,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp)) {
            Text(
                uiStr(R.string.ui_windows_components),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                when {
                    container == null -> uiStr(R.string.ui_save_first)
                    installedCount == null -> uiStr(R.string.ui_windows_components_manage)
                    else -> uiStr(R.string.ui_components_installed_count, installedCount ?: 0)
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }

    if (showDialog && container != null) {
        WindowsComponentsDialog(
            container = container,
            onChanged = { installedCount = it },
            onClose = { showDialog = false }
        )
    }
}

/** Exactly the same dialog shell and immediate dismissal path as Vulkan Extensions. */
@Composable
private fun WindowsComponentsDialog(container: Container, onChanged: (Int) -> Unit, onClose: () -> Unit) {
    var operationRunning by remember { mutableStateOf(false) }
    val dismiss: () -> Unit = { if (!operationRunning) onClose() }

    ThemedDialog(onDismissRequest = dismiss) {
        WindowsComponentsContent(
            container = container,
            onChanged = onChanged,
            onClose = dismiss,
            onOperationRunningChanged = { operationRunning = it }
        )
    }
}

// "d3dx9 43" matches "d3dx9_43.dll": case-insensitive, '_' treated as a space, every word of
// the query must be present — same semantics as the Vulkan extension search.
private fun matchesComponentQuery(component: LocalComponent, query: String): Boolean {
    val words = query.lowercase().replace('_', ' ').split(' ').filter(String::isNotEmpty)
    if (words.isEmpty()) return true
    val haystack = "${component.title} ${component.id} ${component.description}".lowercase().replace('_', ' ')
    return words.all { haystack.contains(it) }
}

@Composable
private fun ColumnScope.WindowsComponentsContent(
    container: Container,
    onChanged: (Int) -> Unit,
    onClose: () -> Unit,
    onOperationRunningChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val accent = controlAccentColor()
    val focusManager = LocalFocusManager.current
    val dialogView = LocalView.current
    val scope = rememberCoroutineScope()
    var components by remember { mutableStateOf<List<LocalComponent>>(emptyList()) }
    var installed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var query by remember { mutableStateOf("") }

    suspend fun refresh() {
        installed = withContext(Dispatchers.IO) { ComponentInstaller.installedIds(context, container) }
    }

    // Catalog and installed set are read together and published in the same frame. Before, the
    // list appeared first (sorted by title) and the installed ids arrived a moment later, which
    // re-sorted it and animateItem() slid the rows over each other right after the dialog opened.
    LaunchedEffect(Unit) {
        val (list, ids) = withContext(Dispatchers.IO) {
            LocalComponents.list(context) to ComponentInstaller.installedIds(context, container)
        }
        components = list
        installed = ids
        loading = false
    }

    fun operate(component: LocalComponent, remove: Boolean) {
        if (busy != null) return
        busy = component.id
        progress = 0f
        onOperationRunningChanged(true)
        scope.launch {
            try {
                val error = withContext(Dispatchers.IO) {
                    if (remove) ComponentInstaller.remove(context, container, component.id) { p ->
                        progress = p.coerceIn(0f, 1f)
                    } else ComponentInstaller.install(context, container, component,
                        onProgress = { p -> progress = p.coerceIn(0f, 1f) })
                }
                val message = when (error) {
                    null -> if (remove) uiStr(R.string.ui_components_removed, component.title)
                            else uiStr(R.string.ui_components_installed, component.title)
                    "noprefix" -> uiStr(R.string.ui_components_launch_first)
                    "session" -> uiStr(R.string.ui_components_close_session)
                    "bundle" -> uiStr(R.string.ui_components_bundle_error)
                    "nojournal" -> uiStr(R.string.ui_components_no_journal)
                    else -> error.removePrefix("missing:")
                }
                refresh()
                onChanged(installed.size)
                WinToast.show(
                    context,
                    message,
                    if (error == null) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
                    dialogView
                )
            } finally {
                busy = null
                onOperationRunningChanged(false)
            }
        }
    }

    ThemedDialogTitle(uiStr(R.string.ui_windows_components))
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        modifier = Modifier.fillMaxWidth(),
        // Floating label (moves to the top border on focus), same as the Vulkan extensions search.
        label = { Text(uiStr(R.string.ui_components_search)) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { query = "" }) { Icon(TablerIcons.X, uiStr(R.string.ui_clear_search)) }
            }
        },
        singleLine = true,
        shape = WinZShapes.Small,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
    )
    val shown = remember(components, installed, query) {
        components.filter { matchesComponentQuery(it, query) }
            .sortedWith(compareBy<LocalComponent> { it.id !in installed }.thenBy { it.title })
    }
    Text(
        if (query.isBlank()) uiStr(R.string.ui_components_shown, installed.size, components.size)
        else uiStr(R.string.ui_found, shown.size.toString()),
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    // Fixed-height slot: the bar appearing/disappearing must not shift the list below it.
    // No empty track at rest: indeterminate while the catalog loads, determinate during file work.
    Box(Modifier.fillMaxWidth().height(4.dp)) {
        if (loading || busy != null) WinZProgressBar(if (busy != null) progress else null)
    }
    if (loading) {
        // Reserve the list's height so the dialog does not grow from empty to full on open.
        Spacer(Modifier.fillMaxWidth().height(160.dp))
    }
    LazyColumn(
        Modifier
            .fillMaxWidth()
            .weight(1f, fill = false)
            .heightIn(max = if (loading) 0.dp else 320.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (!loading && shown.isEmpty()) {
            item(key = "no-match") {
                Text(
                    uiStr(R.string.ui_no_components_match, query.trim()),
                    modifier = Modifier.padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(shown, key = { it.id }) { component ->
            val have = component.id in installed
            Surface(
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth().animateItem()
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(component.title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        component.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (have) {
                            Text(
                                uiStr(R.string.ui_components_status_installed),
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(
                                onClick = { operate(component, true) },
                                enabled = busy == null,
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurface
                                ),
                                border = BorderStroke(1.dp, hairlineColor())
                            ) { Text(uiStr(R.string.ui_components_remove)) }
                            Button(
                                onClick = { operate(component, false) },
                                enabled = busy == null,
                                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White)
                            ) { Text(uiStr(R.string.ui_components_reinstall)) }
                        } else {
                            Spacer(Modifier.weight(1f))
                            Button(
                                onClick = { operate(component, false) },
                                enabled = busy == null,
                                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White)
                            ) { Text(uiStr(R.string.ui_components_install)) }
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
            onClick = onClose,
            enabled = busy == null,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            border = BorderStroke(1.dp, hairlineColor())
        ) { Text(uiStr(R.string.ui_close)) }
    }
}
