package com.winlator.cmod.ui.settings

import com.winlator.cmod.R
import com.winlator.cmod.ui.uiStr
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.winlator.cmod.ui.theme.ThemedDialog
import com.winlator.cmod.ui.theme.ThemedDialogTitle
import com.winlator.cmod.ui.theme.WinZShapes
import com.winlator.cmod.ui.theme.controlAccentColor
import com.winlator.cmod.ui.theme.hairlineColor

/** Parses the stored "A,B,C" blacklist. */
internal fun parseExtensionBlacklist(blacklisted: String): Set<String> =
    blacklisted.split(',').map(String::trim).filter(String::isNotBlank).toSet()

// "push desc", "VK_KHR_push", "khr push_descriptor" all match VK_KHR_push_descriptor:
// case-insensitive, '_' treated as a space, every word of the query must be present.
private fun matchesExtensionQuery(name: String, query: String): Boolean {
    val words = query.lowercase().replace('_', ' ').split(' ').filter(String::isNotEmpty)
    if (words.isEmpty()) return true
    val haystack = name.lowercase().replace('_', ' ')
    return words.all { haystack.contains(it) }
}

/**
 * Checklist of the driver's Vulkan extensions with search. Shared by the container and shortcut
 * editors. [onDone] receives the new blacklist string; names that were blacklisted but are not
 * reported by the current driver are kept (they used to be dropped on every Done).
 */
@Composable
internal fun VulkanExtensionsDialog(
    extensions: List<String>,
    blacklisted: String,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit
) {
    val accent = controlAccentColor()
    val focusManager = LocalFocusManager.current
    val initiallyDisabled = remember(blacklisted) { parseExtensionBlacklist(blacklisted) }
    // Keyed by name, so ticks survive filtering (indices change while searching).
    val enabled = remember(extensions, blacklisted) {
        mutableStateMapOf<String, Boolean>().apply { extensions.forEach { put(it, it !in initiallyDisabled) } }
    }
    var query by remember { mutableStateOf("") }
    val visible = remember(extensions, query) { extensions.filter { matchesExtensionQuery(it, query) } }

    ThemedDialog(onDismissRequest = onDismiss) {
        ThemedDialogTitle(uiStr(R.string.ui_vulkan_extensions))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            // Floating label (moves to the top border on focus), same as "Name" in Custom.
            label = { Text(uiStr(R.string.ui_name)) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, uiStr(R.string.ui_clear_search)) }
                }
            },
            singleLine = true,
            shape = WinZShapes.Small,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
        )
        Text(
            if (query.isBlank()) uiStr(R.string.ui_of_enabled, enabled.count { it.value }, extensions.size)
            else uiStr(R.string.ui_found, visible.size),
            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // weight(fill = false): only the height left after title, search and buttons, so the
        // buttons stay on-screen in landscape (also with the keyboard open); the list scrolls.
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 280.dp)) {
            if (visible.isEmpty()) {
                item {
                    Text(
                        uiStr(R.string.ui_no_extensions_match, query.trim()),
                        modifier = Modifier.padding(vertical = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(visible, key = { it }) { name ->
                val checked = enabled[name] == true
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { enabled[name] = !checked }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { enabled[name] = it },
                        colors = CheckboxDefaults.colors(checkedColor = accent)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(name, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onDismiss,
                colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
                border = BorderStroke(1.dp, hairlineColor())
            ) { Text(uiStr(R.string.cancel)) }
            Button(
                onClick = {
                    val known = extensions.toSet()
                    val disabledNow = extensions.filter { enabled[it] != true } +
                        initiallyDisabled.filter { it !in known }
                    onDone(disabledNow.joinToString(","))
                },
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White)
            ) { Text(uiStr(R.string.ui_done)) }
        }
    }
}
