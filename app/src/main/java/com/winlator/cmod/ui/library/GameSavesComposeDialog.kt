package com.winlator.cmod.ui.library

import androidx.compose.ui.res.stringResource
import com.winlator.cmod.ui.theme.DialogScrimColor
import com.winlator.cmod.ui.theme.WinZMotion
import androidx.activity.compose.BackHandler

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.MainActivity
import com.winlator.cmod.R
import com.winlator.cmod.container.SaveProfiles
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.core.GameSaveManager
import com.winlator.cmod.ui.ThemedAlertHost
import com.winlator.cmod.ui.filemanager.FileManagerController
import com.winlator.cmod.ui.theme.ThemedDialogSurface
import com.winlator.cmod.ui.theme.WinZOverlayTheme
import com.winlator.cmod.ui.theme.WinZShapes
import com.winlator.cmod.ui.theme.accentSwitchColors
import com.winlator.cmod.ui.theme.controlAccentColor
import com.winlator.cmod.ui.theme.dividerColor
import com.winlator.cmod.ui.theme.hairlineColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// "Backup" window of a game (the Backup button on the game page): backups of its saves in
// Winlator/backups — the latest one, the automatic-backup switch, what a backup covers, Back up and
// Restore (pick a backup) — plus where the live saves are (its save profile in Winlator/saves).
// Hosted like WinlatorServicesDialog: a ComposeView added onto the activity's content root, so it
// shares the themed dialog shell and scrim of every other window in the app.
//
// The window cannot be dismissed while a backup or restore is running, so the file work is never
// cut off half way. Restoring asks first: it overwrites the game's current save files.
object GameSavesComposeDialog {
    private const val VIEW_TAG = "game_saves_dialog"

    @JvmStatic
    fun show(context: Context, shortcut: Shortcut) {
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
                    var busy by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { visibleState.targetState = true }
                    LaunchedEffect(visibleState.currentState) {
                        if (!visibleState.currentState && !visibleState.targetState) {
                            (composeView.parent as? ViewGroup)?.removeView(composeView)
                        }
                    }
                    val dismiss: () -> Unit = { if (!busy) visibleState.targetState = false }
                    // System back closes this overlay instead of reaching the screen behind it
                    // (it lives in the activity's own view tree, not a Dialog window).
                    BackHandler(enabled = visibleState.targetState) { dismiss() }
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
                                    GameSavesPanel(
                                        activity = activity,
                                        shortcut = shortcut,
                                        busy = busy,
                                        setBusy = { busy = it },
                                        onClose = dismiss
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        root.addView(composeView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
}

/**
 * Restoring replaces the game's current save files with a backup, so it is confirmed first (same
 * confirmation window as removing a game). The current saves are kept as a before-restore backup.
 */
internal fun confirmGameSavesRestore(context: Context, gameName: String, backupName: String, onConfirm: () -> Unit) {
    val activity = context as? AppCompatActivity
    if (activity == null) {
        onConfirm()
        return
    }
    ThemedAlertHost.confirm(
        activity,
        activity.getString(R.string.ui_restore_saves_title),
        activity.getString(R.string.ui_restore_saves_message, backupName, gameName),
        activity.getString(R.string.ui_restore),
        Runnable { onConfirm() },
        true
    )
}

@Composable
private fun ColumnScope.GameSavesPanel(
    activity: Activity,
    shortcut: Shortcut,
    busy: Boolean,
    setBusy: (Boolean) -> Unit,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val ownProfile = remember(shortcut.file.path) { SaveProfiles.isOwn(shortcut) }
    var roots by remember(shortcut.file.path) { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var autoBackup by remember { mutableStateOf(GameSaveManager.isAutoBackupEnabled(shortcut)) }
    var latest by remember { mutableStateOf(GameSaveManager.getLatestBackup(shortcut)) }
    var message by remember { mutableStateOf<String?>(null) }
    val profilesEnabled = remember(shortcut.file.path) { SaveProfiles.isEnabled(shortcut) }
    val profileShared = remember(shortcut.file.path) { SaveProfiles.isShared(shortcut) }
    var profileDir by remember(shortcut.file.path) { mutableStateOf<File?>(null) }

    fun openProfileFolder() {
        val dir = profileDir ?: return
        FileManagerController.requestOpen(dir)
        onClose()
        (activity as? MainActivity)?.navigateToMainDestination(R.id.main_menu_file_manager)
    }

    fun rescan() {
        if (busy) return
        setBusy(true)
        message = activity.getString(R.string.ui_scanning_saves)
        scope.launch {
            roots = withContext(Dispatchers.IO) { GameSaveManager.rediscoverSaveRoots(shortcut) }
            message = if (roots.isEmpty()) {
                activity.getString(R.string.ui_no_save_folder_detected)
            } else {
                activity.resources.getQuantityString(R.plurals.ui_detected_save_locations, roots.size, roots.size)
            }
            setBusy(false)
        }
    }

    fun backupNow() {
        if (busy) return
        setBusy(true)
        message = activity.getString(R.string.ui_backing_up)
        scope.launch {
            val result = withContext(Dispatchers.IO) { GameSaveManager.backup(shortcut, false) }
            latest = GameSaveManager.getLatestBackup(shortcut)
            message = when {
                result.ok && result.wholeProfile ->
                    activity.getString(R.string.ui_backup_complete_profile, result.fileCount)
                result.ok -> activity.getString(R.string.ui_backup_complete, result.fileCount)
                else -> activity.getString(R.string.ui_backup_failed, result.error ?: activity.getString(R.string.ui_unknown_error))
            }
            setBusy(false)
        }
    }

    fun restoreFrom(archive: File) {
        confirmGameSavesRestore(activity, shortcut.name, backupTitle(activity, archive)) {
            setBusy(true)
            message = activity.getString(R.string.ui_restoring)
            scope.launch {
                val result = withContext(Dispatchers.IO) { GameSaveManager.restore(shortcut, archive) }
                latest = GameSaveManager.getLatestBackup(shortcut)
                message = if (result.ok) {
                    activity.getString(R.string.ui_restored, result.fileCount)
                } else {
                    activity.getString(R.string.ui_restore_failed, result.error ?: activity.getString(R.string.ui_unknown_error))
                }
                setBusy(false)
            }
        }
    }

    // One backup: restore it. Several: pick one first (the newest may be the broken one).
    fun restoreNow() {
        if (busy) return
        scope.launch {
            val backups = withContext(Dispatchers.IO) { GameSaveManager.listBackups(shortcut) }
            if (backups.isEmpty()) {
                message = activity.getString(R.string.ui_no_backup_yet_dot)
                return@launch
            }
            val host = activity as? AppCompatActivity
            if (backups.size == 1 || host == null) {
                restoreFrom(backups[0])
                return@launch
            }
            ThemedAlertHost.actions(
                host,
                activity.getString(R.string.ui_restore_which),
                backups.map { file -> ThemedAlertHost.ActionItem(backupTitle(activity, file), Runnable { restoreFrom(file) }) }
            )
        }
    }

    LaunchedEffect(shortcut.file.path) {
        if (profilesEnabled) profileDir = withContext(Dispatchers.IO) { SaveProfiles.findProfileDir(shortcut) }
        roots = withContext(Dispatchers.IO) { GameSaveManager.getSaveRoots(shortcut) }
        latest = GameSaveManager.getLatestBackup(shortcut)
        loading = false
    }

    Text(stringResource(R.string.ui_backup), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Text(
        shortcut.name,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
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
            if (!profilesEnabled) {
                SavesLabel(stringResource(R.string.ui_save_folder))
                Text(
                    stringResource(R.string.ui_save_folder_in_container),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider(color = dividerColor())
            }
            if (profilesEnabled) {
                val sharedSuffix = stringResource(R.string.ui_shared_suffix)
                SavesLabel(stringResource(R.string.ui_save_folder))
                Text(
                    profileDir?.let { SaveProfiles.displayPath(it) + if (profileShared) sharedSuffix else "" }
                        ?: stringResource(R.string.ui_created_on_first_launch),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SavesOutlinedButton(
                    stringResource(R.string.ui_open_save_folder),
                    Modifier.fillMaxWidth(),
                    enabled = !busy && profileDir != null,
                    icon = Icons.Outlined.FolderOpen
                ) { openProfileFolder() }
                HorizontalDivider(color = dividerColor())
            }
            SavesLabel(stringResource(R.string.ui_backup_folder))
            Text(
                "Winlator/backups/${GameSaveManager.getGameDir(shortcut).name}/",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SavesLabel(stringResource(R.string.ui_latest_backup))
            Text(
                latest?.let { backupLabel(activity, it) } ?: stringResource(R.string.ui_no_backup_yet),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(color = dividerColor())
            val autoChecked = autoBackup
            val autoEnabled = !busy
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(value = autoChecked, enabled = autoEnabled, role = Role.Switch) {
                        autoBackup = it
                        GameSaveManager.setAutoBackupEnabled(shortcut, it)
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(stringResource(R.string.ui_automatic_backup), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.ui_automatic_backup_summary, GameSaveManager.AUTO_KEEP),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = autoChecked,
                    onCheckedChange = null,
                    enabled = autoEnabled,
                    colors = accentSwitchColors()
                )
            }

            HorizontalDivider(color = dividerColor())
            SavesLabel(stringResource(R.string.ui_save_locations))
            when {
                ownProfile -> Text(
                    stringResource(R.string.ui_save_locations_own),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = controlAccentColor())
                    Text(
                        "  " + stringResource(R.string.ui_detecting),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                roots.isEmpty() -> Text(
                    stringResource(R.string.ui_no_specific_folder),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> roots.forEach {
                    Text("• $it", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (!ownProfile) {
                SavesOutlinedButton(stringResource(R.string.ui_rescan_save_locations), Modifier.fillMaxWidth(), enabled = !busy && !loading, icon = Icons.Outlined.Refresh) { rescan() }
            }

            message?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = controlAccentColor())
                        Text("  ")
                    }
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SavesOutlinedButton(stringResource(R.string.ui_close), Modifier.weight(1f), enabled = !busy) { onClose() }
        SavesOutlinedButton(stringResource(R.string.ui_restore), Modifier.weight(1f), enabled = !busy && latest != null) { restoreNow() }
        Button(
            onClick = { backupNow() },
            modifier = Modifier.weight(1f),
            enabled = !busy && !loading,
            colors = ButtonDefaults.buttonColors(containerColor = controlAccentColor(), contentColor = Color.White)
        ) { Text(stringResource(R.string.ui_back_up)) }
    }
}

@Composable
private fun SavesLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SavesOutlinedButton(
    text: String,
    modifier: Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
        border = BorderStroke(1.dp, hairlineColor())
    ) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text)
    }
}

private fun backupLabel(context: Context, file: File): String = backupTitle(context, file)

// "Automatic • 06 Oct 2026, 14:30" (kind from the file name, time from the file).
private fun backupTitle(context: Context, file: File): String {
    val date = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
    val kind = when (GameSaveManager.kindOf(file)) {
        GameSaveManager.KIND_AUTO -> context.getString(R.string.ui_backup_kind_automatic)
        GameSaveManager.KIND_BEFORE_RESTORE -> context.getString(R.string.ui_backup_kind_before_restore)
        else -> context.getString(R.string.ui_backup_kind_manual)
    }
    return "$kind • $date"
}
