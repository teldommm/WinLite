package com.winlator.cmod.ui.settings

import com.winlator.cmod.ui.theme.ThemedDialogScrollBody
import com.winlator.cmod.ui.theme.ThemedDialogTitle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.cmod.ui.theme.controlAccentColor
import com.winlator.cmod.ui.theme.ThemedDialog
import com.winlator.cmod.ui.theme.WinZShapes
import com.winlator.cmod.ui.theme.hairlineColor

enum class EnvValueKind { CHECKBOX, SELECT, MULTI, TEXT, NUMBER }

data class EnvVariableSpec(
    val name: String,
    val kind: EnvValueKind,
    val options: List<String> = emptyList()
)

data class EditableEnvVariable(val name: String, val value: String)

val knownEnvironmentVariables = listOf(
    EnvVariableSpec("ZINK_DESCRIPTORS", EnvValueKind.SELECT, listOf("auto", "lazy", "cached", "notemplates")),
    EnvVariableSpec("ZINK_DEBUG", EnvValueKind.MULTI, listOf("nir", "spirv", "tgsi", "validation", "sync", "compact", "noreorder")),
    EnvVariableSpec("MESA_SHADER_CACHE_DISABLE", EnvValueKind.CHECKBOX, listOf("false", "true")),
    EnvVariableSpec("MESA_SHADER_CACHE_MAX_SIZE", EnvValueKind.TEXT),
    EnvVariableSpec("mesa_glthread", EnvValueKind.CHECKBOX, listOf("false", "true")),
    EnvVariableSpec("WINEESYNC", EnvValueKind.CHECKBOX, listOf("0", "1")),
    EnvVariableSpec("TU_DEBUG", EnvValueKind.MULTI, listOf("forcecb", "nocb", "deck_emu", "startup", "nir", "nobin", "sysmem", "gmem", "forcebin", "layout", "noubwc", "nomultipos", "nolrz", "nolrzfc", "perf", "perfc", "flushall", "syncdraw", "push_consts_per_stage", "rast_order", "unaligned_store", "log_skip_gmem_ops", "dynamic", "bos", "3d_load", "fdm", "noconform", "rd")),
    EnvVariableSpec("DXVK_HUD", EnvValueKind.MULTI, listOf("scale=0.5", "scale=0.7", "opacity=0.5", "opacity=0.7", "devinfo", "fps", "frametimes", "submissions", "drawcalls", "pipelines", "descriptors", "memory", "gpuload", "version", "api", "cs", "compiler", "samplers")),
    EnvVariableSpec("DXVK_DISABLE_TIMELINE_SEMAPHORES", EnvValueKind.CHECKBOX, listOf("0", "1")),
    EnvVariableSpec("MESA_EXTENSION_MAX_YEAR", EnvValueKind.TEXT),
    EnvVariableSpec("VKD3D_SHADER_MODEL", EnvValueKind.TEXT),
    EnvVariableSpec("WRAPPER_BLIT", EnvValueKind.TEXT),
    EnvVariableSpec("FD_DEV_FEATURES", EnvValueKind.TEXT),
    EnvVariableSpec("IR3_SHADER_DEBUG", EnvValueKind.MULTI, listOf("nouboopt", "nopreamble", "noearlypreamble", "nofp16", "nocache", "spillall", "fullsync", "fullnop", "nodescprefetch", "expandrpt", "noaliastex", "noaliasrt")),
    EnvVariableSpec("WRAPPER_MAX_IMAGE_COUNT", EnvValueKind.TEXT),
    EnvVariableSpec("MESA_GL_VERSION_OVERRIDE", EnvValueKind.TEXT),
    EnvVariableSpec("PULSE_LATENCY_MSEC", EnvValueKind.NUMBER),
    EnvVariableSpec("WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER", EnvValueKind.CHECKBOX, listOf("0", "1")),
    EnvVariableSpec("WINE_NEW_MEDIASOURCE", EnvValueKind.CHECKBOX, listOf("0", "1")),
    EnvVariableSpec("GALLIUM_HUD", EnvValueKind.MULTI, listOf("simple", "fps", "frametime")),
    EnvVariableSpec("WINE_LARGE_ADDRESS_AWARE", EnvValueKind.CHECKBOX, listOf("0", "1")),
    EnvVariableSpec("WINEDLLOVERRIDES", EnvValueKind.TEXT)
)

// Storage format (EnvVars): space-separated NAME=value tokens with no quoting, so neither names
// nor values may contain whitespace, and a name may not contain '='.
private val ENV_NAME_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

fun isValidEnvironmentVariableName(name: String): Boolean = ENV_NAME_REGEX.matches(name)

private fun stripWhitespace(text: String): String = text.filterNot(Char::isWhitespace)

fun parseEnvironmentVariables(raw: String): List<EditableEnvVariable> {
    if (raw.isBlank()) return emptyList()
    val result = LinkedHashMap<String, String>()
    raw.trim().split(Regex("\\s+")).forEach { token ->
        val split = token.indexOf('=')
        if (split > 0) result[token.substring(0, split)] = token.substring(split + 1)
    }
    return result.map { (name, value) -> EditableEnvVariable(name, value) }
}

fun serializeEnvironmentVariables(rows: List<EditableEnvVariable>): String = rows
    .map { EditableEnvVariable(stripWhitespace(it.name), stripWhitespace(it.value)) }
    .filter { isValidEnvironmentVariableName(it.name) }
    .distinctBy { it.name }
    .joinToString(" ") { "${it.name}=${it.value}" }

private fun specFor(name: String): EnvVariableSpec? = knownEnvironmentVariables.firstOrNull { it.name == name }

private fun defaultValueFor(spec: EnvVariableSpec?): String = when (spec?.kind) {
    EnvValueKind.CHECKBOX, EnvValueKind.SELECT -> spec.options.firstOrNull().orEmpty()
    else -> ""
}

/** Result of parsing what was typed/pasted into the custom-name field. */
private data class CustomInput(
    val entries: List<EditableEnvVariable>,
    val invalid: List<String>,
    val bulk: Boolean
)

// "NAME" -> single entry (value comes from the value field).
// Anything containing '=' is treated as pasted pairs: "A=1 B=2", one per line, "export A=1",
// values wrapped in matching quotes are unwrapped.
private fun parseCustomInput(nameField: String, valueField: String): CustomInput {
    val text = nameField.trim()
    if (text.isEmpty()) return CustomInput(emptyList(), emptyList(), false)
    if (!text.contains('=')) {
        val name = stripWhitespace(text)
        return if (isValidEnvironmentVariableName(name)) {
            CustomInput(listOf(EditableEnvVariable(name, stripWhitespace(valueField))), emptyList(), false)
        } else CustomInput(emptyList(), listOf(name), false)
    }
    val entries = LinkedHashMap<String, String>()
    val invalid = ArrayList<String>()
    text.split(Regex("\\s+")).filter { it.isNotEmpty() && it != "export" }.forEach { token ->
        val split = token.indexOf('=')
        val name = if (split > 0) token.substring(0, split) else ""
        if (!isValidEnvironmentVariableName(name)) { invalid.add(token); return@forEach }
        var value = token.substring(split + 1)
        if (value.length >= 2 && (value.first() == '"' || value.first() == '\'') && value.last() == value.first()) {
            value = value.substring(1, value.length - 1)
        }
        entries[name] = value
    }
    return CustomInput(entries.map { (n, v) -> EditableEnvVariable(n, v) }, invalid, true)
}

@Composable
fun EnvironmentVariablesEditor(
    value: String,
    onChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val rows = remember(value) {
        mutableStateListOf<EditableEnvVariable>().apply { addAll(parseEnvironmentVariables(value)) }
    }
    var addOpen by remember { mutableStateOf(false) }

    fun commit() = onChanged(serializeEnvironmentVariables(rows))

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (rows.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = WinZShapes.Medium,
                border = BorderStroke(1.dp, hairlineColor()),
                color = MaterialTheme.colorScheme.surface
            ) {
                Text(
                    "No environment variables added.",
                    modifier = Modifier.padding(14.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            SettingsCard {
                rows.forEachIndexed { index, row ->
                    // Keyed by name so text-field state follows its row when another row is removed.
                    key(row.name) {
                        EnvironmentVariableRow(
                            row = row,
                            onValue = { newValue ->
                                val at = rows.indexOfFirst { it.name == row.name }
                                if (at >= 0) {
                                    rows[at] = rows[at].copy(value = newValue)
                                    commit()
                                }
                            },
                            onRemove = {
                                val at = rows.indexOfFirst { it.name == row.name }
                                if (at >= 0) {
                                    rows.removeAt(at)
                                    commit()
                                }
                            }
                        )
                    }
                    if (index != rows.lastIndex) SettingsDivider()
                }
            }
        }
        Button(
            onClick = { addOpen = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = controlAccentColor(), contentColor = Color.White)
        ) {
            Icon(Icons.Outlined.Add, null)
            Spacer(Modifier.width(7.dp))
            Text("Add variable")
        }
    }

    if (addOpen) {
        AddEnvironmentVariableDialog(
            existing = rows.associate { it.name to it.value },
            onDismiss = { addOpen = false },
            onAdd = { added ->
                added.forEach { entry ->
                    val at = rows.indexOfFirst { it.name == entry.name }
                    if (at >= 0) rows[at] = entry else rows.add(entry)
                }
                commit()
                addOpen = false
            }
        )
    }
}

@Composable
private fun EnvironmentVariableRow(
    row: EditableEnvVariable,
    onValue: (String) -> Unit,
    onRemove: () -> Unit
) {
    val known = specFor(row.name)
    val spec = known ?: EnvVariableSpec(row.name, EnvValueKind.TEXT)
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Name + tag take all the free space as ONE weighted child, so the delete button is
            // always pinned to the right edge. (Two weighted siblings — the name with fill=false
            // and a weight(1f) Spacer — split the free space in half, and the half the short name
            // doesn't use is never given back, so the button drifted with the name's length.)
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.name,
                    modifier = Modifier.weight(1f, fill = false),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (known == null) {
                    Spacer(Modifier.width(8.dp))
                    CustomTag()
                }
            }
            IconButton(onClick = onRemove) { Icon(Icons.Outlined.DeleteOutline, "Remove") }
        }
        EnvValueEditor(spec, row.value, onValue)
    }
}

@Composable
private fun CustomTag() {
    Surface(
        shape = WinZShapes.Small,
        color = controlAccentColor().copy(alpha = 0.16f)
    ) {
        Text(
            "Custom",
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = controlAccentColor()
        )
    }
}

/** Value editor matching the variable's kind; shared by the list rows and the add dialog. */
@Composable
private fun EnvValueEditor(spec: EnvVariableSpec, value: String, onValue: (String) -> Unit) {
    when (spec.kind) {
        EnvValueKind.CHECKBOX -> {
            val off = spec.options.getOrElse(0) { "0" }
            val on = spec.options.getOrElse(1) { "1" }
            SettingToggle(
                label = if (value == on) "Enabled" else "Disabled",
                checked = value == on,
                onChanged = { onValue(if (it) on else off) }
            )
        }
        EnvValueKind.SELECT -> SettingChoice(
            label = "Value",
            selected = value.ifBlank { spec.options.firstOrNull().orEmpty() },
            entries = spec.options
        ) { onValue(it) }
        EnvValueKind.MULTI -> MultiEnvironmentChoice(spec.options, value, onValue)
        EnvValueKind.NUMBER -> SettingText("Value", value) { onValue(it.filter(Char::isDigit)) }
        EnvValueKind.TEXT -> SettingText("Value", value) { onValue(stripWhitespace(it)) }
    }
}

@Composable
private fun MultiEnvironmentChoice(options: List<String>, value: String, onChanged: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val selectedText = value.ifBlank { "None" }
    Surface(onClick = { open = true }, color = androidx.compose.ui.graphics.Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp)) {
            Text("Value", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(selectedText, style = MaterialTheme.typography.bodyLarge)
        }
    }
    if (open) {
        val selected = remember(value, open) {
            mutableStateListOf<String>().apply {
                addAll(value.split(',').map(String::trim).filter(String::isNotEmpty))
            }
        }
        ThemedDialog(onDismissRequest = { open = false }) {
            ThemedDialogTitle("Select values")
            val accent = controlAccentColor()
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 420.dp)) {
                items(options) { option ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = option in selected,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    // "key=value" options (scale=0.5 / scale=0.7, opacity=...) are
                                    // alternatives of one setting: picking one drops its siblings.
                                    val key = option.substringBefore('=', missingDelimiterValue = "")
                                    if (key.isNotEmpty()) selected.removeAll { it.startsWith("$key=") }
                                    if (option !in selected) selected.add(option)
                                } else selected.remove(option)
                            },
                            colors = CheckboxDefaults.colors(checkedColor = accent)
                        )
                        Text(option, modifier = Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { open = false },
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
                    border = BorderStroke(1.dp, hairlineColor())
                ) { Text("Cancel") }
                Button(
                    onClick = {
                        // Keep the options' canonical order (not click order); values that aren't
                        // in the option list (typed by hand earlier) are kept at the end.
                        val ordered = options.filter { it in selected } + selected.filter { it !in options }
                        onChanged(ordered.joinToString(","))
                        open = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White)
                ) { Text("Done") }
            }
        }
    }
}

private enum class AddMode { PRESET, CUSTOM }

@Composable
private fun AddEnvironmentVariableDialog(
    existing: Map<String, String>,
    onDismiss: () -> Unit,
    onAdd: (List<EditableEnvVariable>) -> Unit
) {
    val accent = controlAccentColor()
    val available = remember(existing) { knownEnvironmentVariables.filter { it.name !in existing } }
    var mode by remember { mutableStateOf(if (available.isEmpty()) AddMode.CUSTOM else AddMode.PRESET) }

    var presetName by remember { mutableStateOf(available.firstOrNull()?.name.orEmpty()) }
    val presetSpec = available.firstOrNull { it.name == presetName }
    var presetValue by remember(presetName) { mutableStateOf(defaultValueFor(presetSpec)) }

    var customName by remember { mutableStateOf("") }
    var customValue by remember { mutableStateOf("") }
    val custom = parseCustomInput(customName, customValue)
    val customSpec = if (!custom.bulk) custom.entries.firstOrNull()?.let { specFor(it.name) } else null

    val toAdd: List<EditableEnvVariable> = when (mode) {
        AddMode.PRESET -> presetSpec?.let { listOf(EditableEnvVariable(it.name, presetValue)) } ?: emptyList()
        AddMode.CUSTOM -> if (custom.invalid.isEmpty() || custom.bulk) custom.entries else emptyList()
    }
    val replacing = toAdd.count { it.name in existing }

    ThemedDialog(onDismissRequest = onDismiss) {
        ThemedDialogTitle("Add environment variable")
        ModeSwitch(mode, presetEnabled = available.isNotEmpty()) { mode = it }
        Spacer(Modifier.height(10.dp))
        ThemedDialogScrollBody(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (mode) {
                AddMode.PRESET -> {
                    SettingChoice("Variable", presetName, available.map { it.name }) { presetName = it }
                    if (presetSpec != null) EnvValueEditor(presetSpec, presetValue) { presetValue = it }
                }
                AddMode.CUSTOM -> {
                    val customMessage: String? = when {
                        custom.bulk -> "Pasted list: ${custom.entries.size} variable(s)" +
                            if (custom.invalid.isNotEmpty()) ", ${custom.invalid.size} invalid will be skipped" else ""
                        custom.invalid.isNotEmpty() -> "Use letters, digits and _, not starting with a digit"
                        customSpec != null -> "Known variable: it will get its own editor"
                        else -> null
                    }
                    OutlinedTextField(
                        value = customName,
                        onValueChange = { customName = it },
                        label = { Text("Name") },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        maxLines = 4,
                        shape = WinZShapes.Small,
                        isError = custom.invalid.isNotEmpty() && !custom.bulk,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                        // Only shown when there is something to say (no idle tip line).
                        supportingText = if (customMessage != null) { { Text(customMessage) } } else null
                    )
                    if (!custom.bulk) {
                        if (customSpec != null) {
                            // Known name typed by hand: offer the proper editor right away.
                            val v = customValue.ifEmpty { defaultValueFor(customSpec) }
                            EnvValueEditor(customSpec, v) { customValue = it }
                        } else {
                            OutlinedTextField(
                                value = customValue,
                                onValueChange = { customValue = stripWhitespace(it) },
                                label = { Text("Value") },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                                singleLine = true,
                                shape = WinZShapes.Small,
                                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false)
                            )
                        }
                    } else if (custom.entries.isNotEmpty()) {
                        SettingsCard {
                            custom.entries.forEachIndexed { i, e ->
                                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp)) {
                                    Text(e.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false))
                                    Text(" = ${e.value}", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                }
                                if (i != custom.entries.lastIndex) SettingsDivider()
                            }
                        }
                    }
                }
            }
            if (replacing > 0) {
                Text(
                    if (toAdd.size == 1) "Already added: its value will be replaced."
                    else "$replacing already added: their values will be replaced.",
                    modifier = Modifier.padding(horizontal = 14.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onDismiss,
                colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
                border = BorderStroke(1.dp, hairlineColor())
            ) { Text("Cancel") }
            Button(
                onClick = {
                    val finalEntries = toAdd.map { e ->
                        // A known variable added without touching its editor gets the editor's default.
                        if (e.value.isEmpty()) e.copy(value = defaultValueFor(specFor(e.name))) else e
                    }
                    onAdd(finalEntries)
                },
                enabled = toAdd.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White)
            ) {
                Text(
                    when {
                        toAdd.size > 1 -> "Add ${toAdd.size}"
                        replacing > 0 -> "Replace"
                        else -> "Add"
                    }
                )
            }
        }
    }
}

@Composable
private fun ModeSwitch(mode: AddMode, presetEnabled: Boolean, onMode: (AddMode) -> Unit) {
    val accent = controlAccentColor()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = WinZShapes.Medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, hairlineColor())
    ) {
        Row(Modifier.padding(3.dp)) {
            listOf(AddMode.PRESET to "Preset", AddMode.CUSTOM to "Custom").forEach { (m, label) ->
                val selected = m == mode
                val enabled = m != AddMode.PRESET || presetEnabled
                Surface(
                    onClick = { onMode(m) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    shape = WinZShapes.Small,
                    color = if (selected) accent else Color.Transparent
                ) {
                    Box(Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (m == AddMode.PRESET && !presetEnabled) "$label (all added)" else label,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = when {
                                selected -> Color.White
                                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                }
            }
        }
    }
}
