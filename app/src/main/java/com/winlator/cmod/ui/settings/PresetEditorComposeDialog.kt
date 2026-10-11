package com.winlator.cmod.ui.settings

import androidx.activity.compose.BackHandler

import com.winlator.cmod.ui.theme.isCompactDialogHeight
import com.winlator.cmod.ui.theme.ThemedDialogScrollBody
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import com.winlator.cmod.ui.theme.ThemedDialogTitle
import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.ui.theme.ThemedDialog
import com.winlator.cmod.ui.theme.ThemedDialogSurface
import com.winlator.cmod.ui.theme.WinZOverlayTheme
import com.winlator.cmod.ui.theme.accentSwitchColors
import com.winlator.cmod.ui.theme.controlAccentColor
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import com.winlator.cmod.ui.theme.WinZShapes
import com.winlator.cmod.ui.theme.hairlineColor
import com.winlator.cmod.ui.theme.dividerColor

data class PresetEditorVariable(
    val name: String,
    val value: String,
    val values: List<String>,
    val toggle: Boolean,
    val editable: Boolean,
    val help: String?
)

fun interface PresetEditorSaveListener {
    fun onSave(name: String, values: Map<String, String>)
}

// Same hosting technique as ThemedAlertHost/ThemedProgressHost/ThemedDownloadProgressHost: a
// ComposeView added straight onto the activity's content root, not a real android.app.Dialog —
// so it gets the exact same WinZOverlayTheme + single scrim + ThemedDialogSurface shell as every
// other themed dialog in the app, instead of looking like its own, slightly different kind of
// window. create() builds the view without attaching it (so callers can still wire
// setOnConfirmCallback before anything is shown); show() attaches it.
object PresetEditorComposeDialog {
    @JvmStatic
    fun create(
        context: Context,
        title: String,
        initialName: String,
        readOnly: Boolean,
        variables: List<PresetEditorVariable>,
        listener: PresetEditorSaveListener
    ): View {
        lateinit var composeView: ComposeView
        composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WinZOverlayTheme {
                    val visibleState = remember { MutableTransitionState(false) }
                    LaunchedEffect(Unit) { visibleState.targetState = true }
                    LaunchedEffect(visibleState.currentState) {
                        if (!visibleState.currentState && !visibleState.targetState) {
                            (composeView.parent as? ViewGroup)?.removeView(composeView)
                        }
                    }
                    val dismiss: () -> Unit = { visibleState.targetState = false }
                    // System back closes this overlay instead of reaching the screen behind it
                    // (it lives in the activity's own view tree, not a Dialog window).
                    BackHandler(enabled = visibleState.targetState) { dismiss() }
                    AnimatedVisibility(
                        visibleState = visibleState,
                        enter = fadeIn(tween(180)),
                        exit = fadeOut(tween(150))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.5f))
                                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { dismiss() },
                            contentAlignment = Alignment.Center
                        ) {
                            AnimatedVisibility(
                                visibleState = visibleState,
                                enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.9f, animationSpec = tween(200)),
                                exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.9f, animationSpec = tween(150))
                            ) {
                                ThemedDialogSurface(modifier = Modifier.heightIn(max = 560.dp)) {
                                    PresetEditorScreen(
                                        title = title,
                                        initialName = initialName,
                                        readOnly = readOnly,
                                        variables = variables,
                                        onCancel = dismiss,
                                        onSave = { name, values ->
                                            listener.onSave(name, values)
                                            dismiss()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        return composeView
    }

    @JvmStatic
    fun show(context: Context, view: View) {
        if (view.parent != null) return
        val root = (context as Activity).findViewById<ViewGroup>(android.R.id.content)
        root.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
}

@Composable
private fun ColumnScope.PresetEditorScreen(
    title: String,
    initialName: String,
    readOnly: Boolean,
    variables: List<PresetEditorVariable>,
    onCancel: () -> Unit,
    onSave: (String, Map<String, String>) -> Unit
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    val values = remember(variables) {
        mutableStateMapOf<String, String>().apply {
            variables.forEach { put(it.name, it.value) }
        }
    }
    var helpText by remember { mutableStateOf<String?>(null) }
    // Landscape phone: every dp of height goes to the variables list (the only part that
    // shrinks), so the subtitle is dropped and the gaps around the fixed parts are tightened.
    val compact = isCompactDialogHeight()

    Text(title, style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    if (!compact) {
        Text(
            if (readOnly) "Bundled preset · read only" else "Edit the preset name and environment variables",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
    // Same title divider as every other dialog (see ThemedDialogTitle), under the subtitle here.
    HorizontalDivider(Modifier.padding(vertical = if (compact) 8.dp else 12.dp), color = dividerColor())
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        enabled = !readOnly,
        singleLine = true,
        label = { Text("Preset") },
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        "Environment variables",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = if (compact) 8.dp else 18.dp, bottom = if (compact) 4.dp else 7.dp)
    )
    Surface(
        modifier = Modifier.fillMaxWidth().weight(1f),
        shape = WinZShapes.Small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
        border = BorderStroke(1.dp, hairlineColor())
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(variables, key = { it.name }) { variable ->
                PresetVariableRow(
                    variable = variable,
                    value = values[variable.name] ?: variable.value,
                    readOnly = readOnly,
                    onValueChange = { values[variable.name] = it },
                    onHelp = { variable.help?.let { helpText = it } }
                )
                if (variable != variables.last()) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        color = dividerColor()
                    )
                }
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = if (compact) 10.dp else 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
            border = BorderStroke(1.dp, hairlineColor())
        ) { Text("Cancel") }
        Button(
            onClick = {
                val cleanName = name.trim().replace(Regex("[,|]+"), "")
                if (cleanName.isNotEmpty()) onSave(cleanName, values.toMap())
            },
            enabled = !readOnly && name.isNotBlank(),
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = controlAccentColor(), contentColor = Color.White)
        ) { Text("Save") }
    }

    helpText?.let { message ->
        ThemedDialog(onDismissRequest = { helpText = null }) {
            ThemedDialogTitle("Help")
            ThemedDialogScrollBody {
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.align(Alignment.End)) {
                Button(
                    onClick = { helpText = null },
                    colors = ButtonDefaults.buttonColors(containerColor = controlAccentColor(), contentColor = Color.White)
                ) { Text("OK") }
            }
        }
    }
}

@Composable
private fun PresetVariableRow(
    variable: PresetEditorVariable,
    value: String,
    readOnly: Boolean,
    onValueChange: (String) -> Unit,
    onHelp: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    val toggleOn = value == "1"
    Column(
        Modifier
            .fillMaxWidth()
            // Toggle variables flip from anywhere on the row; the Help button keeps its own tap.
            .then(
                if (variable.toggle) Modifier.toggleable(
                    value = toggleOn,
                    enabled = !readOnly,
                    role = Role.Switch,
                    onValueChange = { onValueChange(if (it) "1" else "0") }
                ) else Modifier
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                variable.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (!variable.help.isNullOrBlank()) {
                IconButton(onClick = onHelp, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.AutoMirrored.Outlined.HelpOutline,
                        contentDescription = "Help",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (variable.toggle) {
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = toggleOn,
                    enabled = !readOnly,
                    onCheckedChange = null,
                    colors = accentSwitchColors()
                )
            }
        }
        if (!variable.toggle) {
            Spacer(Modifier.size(6.dp))
            if (variable.editable) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = !readOnly,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Box(Modifier.fillMaxWidth()) {
                    Surface(
                        onClick = { expanded = true },
                        enabled = !readOnly,
                        modifier = Modifier.fillMaxWidth(),
                        shape = WinZShapes.Small,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, hairlineColor())
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            Icon(Icons.Outlined.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    // Small anchored DropdownMenu, same as SettingChoice (audio driver, wine
                    // version, etc. in container settings) — not the big ModalBottomSheet this
                    // used to open.
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier.widthIn(min = 220.dp, max = 380.dp).heightIn(max = 420.dp),
                        shape = WinZShapes.Medium,
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        val accent = controlAccentColor()
                        variable.values.distinct().forEach { option ->
                            val isSelected = option == value
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        option,
                                        color = if (isSelected) accent else MaterialTheme.colorScheme.onSurface,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                trailingIcon = { if (isSelected) Icon(Icons.Outlined.Check, null, tint = accent) },
                                onClick = {
                                    expanded = false
                                    onValueChange(option)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
