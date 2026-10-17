package com.winlator.cmod.ui.settings

import com.winlator.cmod.R
import com.winlator.cmod.ui.uiStr
import com.winlator.cmod.ui.theme.DialogScrimColor
import com.winlator.cmod.ui.theme.WinZMotion
import androidx.activity.compose.BackHandler

import com.winlator.cmod.ui.theme.ThemedDialogScrollBody
import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.contentdialog.DriverRepo
import com.winlator.cmod.core.RemoteSources
import com.winlator.cmod.ui.theme.ThemedDialogSurface
import com.winlator.cmod.ui.theme.WinZOverlayTheme
import com.winlator.cmod.ui.toast.WinToast
import com.winlator.cmod.ui.theme.WinZShapes
import com.winlator.cmod.ui.theme.controlAccentColor
import com.winlator.cmod.ui.theme.dividerColor
import com.winlator.cmod.ui.theme.hairlineColor

// "Winlator servers" (the button in the component manager): one window for the remote addresses of
// components, drivers and input controls (component catalog, driver repositories, input-controls
// profiles). The artwork ones live in Settings, COVER ART, next to the key they are used with.
// Same hosting technique as PresetEditorComposeDialog: a ComposeView added onto the activity's content root, so it shares
// the themed dialog shell and scrim of every other dialog in the app.
//
// Adding a driver repository is a second, small window (AddRepoOverlay) opened from the list, so the
// main window only ever shows the repositories that are there, not an input form.
//
// Edits are a draft until Save; the values are stored through RemoteSources (blank / default means
// "no override"), so the rest of the app reads them from there and needs no other plumbing.
object WinlatorServicesDialog {
    private const val VIEW_TAG = "winlator_services_dialog"

    @JvmStatic
    fun show(context: Context) = show(context, null)

    /** [onSaved] runs after the person pressed Save, so the caller can reload what it shows. */
    @JvmStatic
    fun show(context: Context, onSaved: Runnable?) {
        val activity = context as? Activity ?: return
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        if (root.findViewWithTag<View>(VIEW_TAG) != null) return

        lateinit var composeView: ComposeView
        composeView = ComposeView(activity).apply {
            tag = VIEW_TAG
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
                    val repos = remember { mutableStateListOf<DriverRepo>().apply { addAll(RemoteSources.driverRepos(activity)) } }
                    var addRepoOpen by remember { mutableStateOf(false) }
                    // The inline "Add repository" card closes first, then the dialog itself.
                    BackHandler(enabled = visibleState.targetState) {
                        if (addRepoOpen) addRepoOpen = false else dismiss()
                    }
                    AnimatedVisibility(
                        visibleState = visibleState,
                        enter = WinZMotion.dialogScrimEnter(),
                        exit = WinZMotion.dialogScrimExit()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(DialogScrimColor)
                                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { dismiss() },
                            contentAlignment = Alignment.Center
                        ) {
                            AnimatedVisibility(
                                visibleState = visibleState,
                                enter = WinZMotion.dialogCardEnter(),
                                exit = WinZMotion.dialogCardExit()
                            ) {
                                // Swallow taps inside the card so they don't reach the dismiss scrim.
                                ThemedDialogSurface(
                                    modifier = Modifier
                                        .heightIn(max = 620.dp)
                                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { }
                                ) {
                                    ServicesScreen(
                                        context = activity,
                                        repos = repos,
                                        onAddRepo = { addRepoOpen = true },
                                        onCancel = dismiss,
                                        onSaved = {
                                            onSaved?.run()
                                            WinToast.show(activity, uiStr(R.string.ui_winlator_services_saved), Toast.LENGTH_SHORT)
                                            dismiss()
                                        }
                                    )
                                }
                            }
                            if (addRepoOpen) {
                                AddRepoOverlay(
                                    existing = repos,
                                    onDismiss = { addRepoOpen = false },
                                    onAdd = { name, input ->
                                        val api = RemoteSources.normalizeDriverRepoUrl(input)
                                        if (api.isNotEmpty() && repos.none { it.apiUrl == api }) {
                                            repos.add(DriverRepo(name.trim().ifEmpty { RemoteSources.suggestRepoName(api) }, api))
                                        }
                                        addRepoOpen = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
        root.addView(composeView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
}

@Composable
private fun ColumnScope.ServicesScreen(
    context: Context,
    repos: SnapshotStateList<DriverRepo>,
    onAddRepo: () -> Unit,
    onCancel: () -> Unit,
    onSaved: () -> Unit
) {
    var contentsUrl by remember { mutableStateOf(RemoteSources.contentsUrl(context)) }
    var inputControls by remember { mutableStateOf(RemoteSources.get(context, RemoteSources.KEY_INPUT_CONTROLS, RemoteSources.DEFAULT_INPUT_CONTROLS)) }

    Text(uiStr(R.string.ui_winlator_servers), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Text(
        uiStr(R.string.ui_choose_where_the_app_downloads_components),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp)
    )
    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = dividerColor())

    Surface(
        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
        shape = WinZShapes.Small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
        border = BorderStroke(1.dp, hairlineColor())
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            GroupLabel(uiStr(R.string.ui_components))
            ServiceField(uiStr(R.string.ui_contents_url), contentsUrl, RemoteSources.DEFAULT_CONTENTS_URL) { contentsUrl = it }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    uiStr(R.string.ui_driver_repositories),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = {
                    repos.clear()
                    repos.addAll(RemoteSources.defaultDriverRepos())
                }) {
                    Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(uiStr(R.string.ui_default))
                }
            }
            if (repos.isEmpty()) {
                Text(
                    uiStr(R.string.ui_no_repositories_the_driver_list_will_be_e),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            repos.toList().forEachIndexed { index, repo ->
                RepoRow(repo) { repos.removeAt(index) }
            }
            OutlinedButton(
                onClick = onAddRepo,
                modifier = Modifier.align(Alignment.End),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
                border = BorderStroke(1.dp, hairlineColor())
            ) {
                Icon(Icons.Outlined.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(uiStr(R.string.ui_add_repository))
            }

            HorizontalDivider(color = dividerColor())
            GroupLabel(uiStr(R.string.ui_input_controls_2))
            ServiceField(uiStr(R.string.ui_profiles_folder_url), inputControls, RemoteSources.DEFAULT_INPUT_CONTROLS) { inputControls = it }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
            border = BorderStroke(1.dp, hairlineColor())
        ) { Text(uiStr(R.string.cancel)) }
        Button(
            onClick = {
                RemoteSources.set(context, RemoteSources.KEY_CONTENTS_URL, contentsUrl, RemoteSources.DEFAULT_CONTENTS_URL)
                RemoteSources.set(context, RemoteSources.KEY_INPUT_CONTROLS, inputControls, RemoteSources.DEFAULT_INPUT_CONTROLS)
                RemoteSources.saveDriverRepos(context, repos.toList())
                RemoteSources.dropLegacyKeys(context)
                onSaved()
            },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = controlAccentColor(), contentColor = Color.White)
        ) { Text(uiStr(R.string.ui_save)) }
    }
}

// The "add a driver repository" window: name (optional) and address. Drawn over the servers window
// with its own scrim; tapping outside or Cancel closes it without adding anything.
@Composable
private fun AddRepoOverlay(
    existing: List<DriverRepo>,
    onDismiss: () -> Unit,
    onAdd: (name: String, input: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    val api = RemoteSources.normalizeDriverRepoUrl(input)
    val duplicate = api.isNotEmpty() && existing.any { it.apiUrl == api }
    val canAdd = api.isNotEmpty() && !duplicate

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DialogScrimColor)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        ThemedDialogSurface(
            modifier = Modifier
                .heightIn(max = 620.dp)
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { }
        ) {
            Text(uiStr(R.string.ui_add_repository), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                uiStr(R.string.ui_a_github_repository_that_publishes_adreno),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            // Scrolls on a short (landscape) screen or with the keyboard up, so Cancel/Add
            // under it are never pushed off the card.
            ThemedDialogScrollBody(
                modifier = Modifier.padding(top = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    isError = duplicate,
                    label = { Text(uiStr(R.string.ui_owner_repo_or_github_releases_api_url)) },
                    supportingText = if (duplicate) ({ Text(uiStr(R.string.ui_already_in_the_list)) }) else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(uiStr(R.string.ui_name_optional)) },
                    placeholder = if (api.isNotEmpty()) ({ Text(RemoteSources.suggestRepoName(api)) }) else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (canAdd) onAdd(name, input) }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
                    border = BorderStroke(1.dp, hairlineColor())
                ) { Text(uiStr(R.string.cancel)) }
                Button(
                    onClick = { onAdd(name, input) },
                    enabled = canAdd,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = controlAccentColor(), contentColor = Color.White)
                ) { Text(uiStr(R.string.ui_add_2)) }
            }
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

// One address. The trailing icon puts the default back (or clears the field when the default is
// empty); the default is shown underneath while the field differs from it, so it is never lost.
@Composable
private fun ServiceField(
    label: String,
    value: String,
    default: String,
    error: String? = null,
    onChange: (String) -> Unit
) {
    val differs = value.trim() != default
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        isError = error != null,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        supportingText = when {
            error != null -> ({ Text(error) })
            differs && default.isNotEmpty() -> ({
                Text(uiStr(R.string.ui_default_2, default), maxLines = 1, overflow = TextOverflow.Ellipsis)
            })
            else -> null
        },
        trailingIcon = if (differs) ({
            IconButton(onClick = { onChange(default) }) {
                Icon(
                    if (default.isEmpty()) Icons.Outlined.Close else Icons.Outlined.Refresh,
                    contentDescription = if (default.isEmpty()) uiStr(R.string.ui_clear) else uiStr(R.string.ui_restore_default),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }) else null
    )
}

@Composable
private fun RepoRow(repo: DriverRepo, onRemove: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = WinZShapes.Small,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, hairlineColor())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    repo.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    repo.apiUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = uiStr(R.string.ui_remove_repository),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
