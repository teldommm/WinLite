@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.winlator.cmod.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.cmod.R
import com.winlator.cmod.components.ComponentInstaller
import com.winlator.cmod.components.LocalComponent
import com.winlator.cmod.components.LocalComponents
import com.winlator.cmod.container.Container
import com.winlator.cmod.ui.theme.DialogScrimColor
import com.winlator.cmod.ui.theme.ThemedDialogSurface
import com.winlator.cmod.ui.theme.ThemedDialogTitle
import com.winlator.cmod.ui.theme.TablerIcons
import com.winlator.cmod.ui.theme.WinZMotion
import com.winlator.cmod.ui.theme.WinZProgressBar
import com.winlator.cmod.ui.theme.WinZShapes
import com.winlator.cmod.ui.theme.controlAccentColor
import com.winlator.cmod.ui.theme.hairlineColor
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
        WindowsComponentsDialog(
            container = container,
            onChanged = { installedCount = ComponentInstaller.installedIds(context, container).size },
            onClose = { showDialog = false }
        )
    }
}

/**
 * The manager dialog with the shared WinZ pop: the scrim fades in, the card grows from 90% —
 * same motion tokens as every other WinLite dialog (WinZMotion.dialogScrimEnter/dialogCardEnter).
 */
@Composable
private fun WindowsComponentsDialog(container: Container, onChanged: () -> Unit, onClose: () -> Unit) {
    val visibleState = remember { MutableTransitionState(false) }
    LaunchedEffect(Unit) { visibleState.targetState = true }
    // The caller drops the dialog from composition once the exit animation has run out.
    LaunchedEffect(visibleState.currentState, visibleState.targetState) {
        if (!visibleState.currentState && !visibleState.targetState) onClose()
    }
    val dismiss = { visibleState.targetState = false }

    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        AnimatedVisibility(
            visibleState = visibleState,
            enter = WinZMotion.dialogScrimEnter(),
            exit = WinZMotion.dialogScrimExit()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DialogScrimColor)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { dismiss() },
                contentAlignment = Alignment.Center
            ) {
                AnimatedVisibility(
                    visibleState = visibleState,
                    enter = WinZMotion.dialogCardEnter(),
                    exit = WinZMotion.dialogCardExit()
                ) {
                    // Swallow taps inside the card so they don't reach the dismiss scrim.
                    ThemedDialogSurface(
                        modifier = Modifier.clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { }
                    ) {
                        WindowsComponentsContent(container, onChanged, dismiss)
                    }
                }
            }
        }
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
private fun ColumnScope.WindowsComponentsContent(container: Container, onChanged: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val accent = controlAccentColor()
    val focusManager = LocalFocusManager.current
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
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
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
    val shown = components.filter { matchesComponentQuery(it, query) }
        .sortedWith(compareBy<LocalComponent> { it.id !in installed }.thenBy { it.title })
    Text(
        if (query.isBlank()) uiStr(R.string.ui_components_shown, installed.size, components.size)
        else uiStr(R.string.ui_found, shown.size.toString()),
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    // Glides to the new value on the WinZMotion curve; indeterminate while the stage has no fraction.
    WinZProgressBar(progress.takeIf { busy != null })
    LazyColumn(
        Modifier
            .fillMaxWidth()
            .weight(1f, fill = false)
            .heightIn(max = 320.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (shown.isEmpty()) {
            item(key = "no-match") {
                Text(
                    uiStr(R.string.ui_components_no_match, query.trim()),
                    modifier = Modifier.animateItem().padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(shown, key = { it.id }) { component ->
            val have = component.id in installed
            Surface(
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp,
                modifier = Modifier.animateItem()
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

    message?.let { text ->
        MessageDialog(text) { message = null }
    }
}

/** One-line result notice (installed/removed/error), same pop as the manager itself. */
@Composable
private fun MessageDialog(text: String, onDismiss: () -> Unit) {
    val visibleState = remember { MutableTransitionState(false) }
    LaunchedEffect(Unit) { visibleState.targetState = true }
    LaunchedEffect(visibleState.currentState, visibleState.targetState) {
        if (!visibleState.currentState && !visibleState.targetState) onDismiss()
    }
    val dismiss = { visibleState.targetState = false }

    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        AnimatedVisibility(
            visibleState = visibleState,
            enter = WinZMotion.dialogScrimEnter(),
            exit = WinZMotion.dialogScrimExit()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DialogScrimColor)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { dismiss() },
                contentAlignment = Alignment.Center
            ) {
                AnimatedVisibility(
                    visibleState = visibleState,
                    enter = WinZMotion.dialogCardEnter(),
                    exit = WinZMotion.dialogCardExit()
                ) {
                    ThemedDialogSurface(
                        modifier = Modifier.clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { }
                    ) {
                        ThemedDialogTitle(uiStr(R.string.ui_windows_components))
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
                            Button(
                                onClick = dismiss,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = controlAccentColor(),
                                    contentColor = Color.White
                                )
                            ) { Text(uiStr(R.string.ok)) }
                        }
                    }
                }
            }
        }
    }
}
