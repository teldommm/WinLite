@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.winlator.cmod.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.components.ComponentInstaller
import com.winlator.cmod.components.LocalComponent
import com.winlator.cmod.components.LocalComponents
import com.winlator.cmod.container.Container
import com.winlator.cmod.ui.theme.ThemedDialog
import com.winlator.cmod.ui.theme.ThemedDialogTitle
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
        installedCount = container?.let { ComponentInstaller.installedIds(context, it).size }
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
        ThemedDialog(onDismissRequest = { showDialog = false }) {
            WindowsComponentsContent(
                container = container,
                onChanged = { installedCount = ComponentInstaller.installedIds(context, container).size },
                onClose = { showDialog = false }
            )
        }
    }
}

@Composable
private fun WindowsComponentsContent(container: Container, onChanged: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var components by remember { mutableStateOf<List<LocalComponent>>(emptyList()) }
    var installed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var query by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        installed = ComponentInstaller.installedIds(context, container)
    }

    LaunchedEffect(Unit) {
        components = withContext(Dispatchers.IO) { LocalComponents.list(context) }
        refresh()
        loading = false
    }

    fun operate(component: LocalComponent, remove: Boolean) {
        if (busy != null) return
        busy = component.id
        progress = 0f
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                if (remove) ComponentInstaller.remove(context, container, component.id) { p ->
                    scope.launch { progress = p.coerceIn(0f, 1f) }
                } else ComponentInstaller.install(context, container, component,
                    onProgress = { p -> scope.launch { progress = p.coerceIn(0f, 1f) } })
            }
            message = when (error) {
                null -> if (remove) uiStr(R.string.ui_components_removed, component.title)
                        else uiStr(R.string.ui_components_installed, component.title)
                "noprefix" -> uiStr(R.string.ui_components_launch_first)
                "session" -> uiStr(R.string.ui_components_close_session)
                "bundle" -> uiStr(R.string.ui_components_bundle_error)
                "nojournal" -> uiStr(R.string.ui_components_no_journal)
                else -> error.removePrefix("missing:")
            }
            refresh()
            onChanged()
            busy = null
        }
    }

    ThemedDialogTitle(uiStr(R.string.ui_windows_components))
    Text(
        uiStr(R.string.ui_components_applies, container.name),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        placeholder = { Text(uiStr(R.string.ui_components_search)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
    )
    if (busy != null) {
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    }
    if (loading) {
        Text(uiStr(R.string.loading), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
    }
    val shown = components.filter {
        query.isBlank() || it.title.contains(query, true) || it.description.contains(query, true)
    }.sortedWith(compareBy<LocalComponent> { it.id !in installed }.thenBy { it.title })
    LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(shown, key = { it.id }) { component ->
            val have = component.id in installed
            Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(component.title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        component.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (have) uiStr(R.string.ui_components_status_installed) else "",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (have) {
                            TextButton(onClick = { operate(component, true) }, enabled = busy == null) {
                                Text(uiStr(R.string.ui_components_remove))
                            }
                        }
                        TextButton(onClick = { operate(component, false) }, enabled = busy == null) {
                            Text(uiStr(if (have) R.string.ui_components_reinstall else R.string.ui_components_install))
                        }
                    }
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onClose, enabled = busy == null) { Text(uiStr(R.string.ui_close)) }
    }

    message?.let { text ->
        ThemedDialog(onDismissRequest = { message = null }) {
            ThemedDialogTitle(uiStr(R.string.ui_windows_components))
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { message = null }) { Text(uiStr(R.string.ok)) }
            }
        }
    }
}
