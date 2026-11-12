package com.winlator.cmod.steam

import com.winlator.cmod.ui.theme.TablerIcons
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.winlator.cmod.R
import com.winlator.cmod.container.Container
import com.winlator.cmod.ui.theme.WinZShapes
import com.winlator.cmod.ui.uiStr
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SteamNativeCloudPanel(id: Int, container: Container, close: () -> Unit, onBackup: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var direction by remember(id, container.id) { mutableStateOf<SteamNativeCloud.Direction?>(null) }
    var plan by remember(id, container.id) { mutableStateOf<SteamNativeCloud.Plan?>(null) }
    var previewLoading by remember(id, container.id) { mutableStateOf(true) }
    var transferring by remember { mutableStateOf(false) }
    var previewError by remember { mutableStateOf("") }
    var transferError by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var finished by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(id, container.id, refresh) {
        previewLoading = true
        plan = null
        previewError = ""
        val loaded = withContext(Dispatchers.IO) { runCatching { SteamNativeCloud.preview(context, id, container) } }
        loaded.onSuccess { plan = it }.onFailure { previewError = it.message ?: uiStr(R.string.steam_cloud_failed) }
        previewLoading = false
    }
    val remote = plan?.entries.orEmpty().filter { it.remoteSha != null }
    val local = plan?.entries.orEmpty().filter { it.localSha != null }
    val entries = if (direction == SteamNativeCloud.Direction.Download) remote else local
    val needsTransfer = entries.count { it.localSha != it.remoteSha }
    val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    fun saveDate(count: Int, timestamp: Long, emptyLabel: Int): String = when {
        count == 0 -> uiStr(emptyLabel)
        timestamp <= 0 -> uiStr(R.string.steam_cloud_date_unknown)
        else -> uiStr(R.string.steam_cloud_last_saved, format.format(Date(timestamp)))
    }
    val remoteDate = saveDate(remote.size, remote.maxOfOrNull { it.time * 1000L } ?: 0L, R.string.steam_cloud_no_remote)
    val localDate = saveDate(local.size, local.maxOfOrNull { it.local.lastModified() } ?: 0L, R.string.steam_cloud_no_local)

    SteamDialog(onDismissRequest = { if (!transferring) close() }) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    com.winlator.cmod.ui.theme.ThemedDialogTitle(uiStr(R.string.steam_cloud_saves))
                    Text(container.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (previewLoading) {
                        com.winlator.cmod.ui.theme.WinZProgressBar(null)
                        Text(uiStr(R.string.steam_native_cloud_checking), style = MaterialTheme.typography.bodySmall)
                    } else if (previewError.isNotBlank()) {
                        Text(previewError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        SteamSecondaryButton(uiStr(R.string.steam_cloud_retry), icon = TablerIcons.Refresh) { refresh++ }
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(TablerIcons.Download, null, Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(uiStr(R.string.steam_cloud_remote_label), style = MaterialTheme.typography.labelLarge)
                                Text(remoteDate, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider(color = com.winlator.cmod.ui.theme.dividerColor())
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(TablerIcons.Database, null, Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(uiStr(R.string.steam_cloud_local_label), style = MaterialTheme.typography.labelLarge)
                                Text(localDate, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (direction == null && result.isBlank()) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SteamSecondaryButton(uiStr(R.string.steam_cloud_download_action), Modifier.weight(1f), enabled = remote.isNotEmpty(), icon = TablerIcons.Download) {
                                    direction = SteamNativeCloud.Direction.Download; transferError = ""
                                }
                                SteamSecondaryButton(uiStr(R.string.steam_cloud_upload_action), Modifier.weight(1f), enabled = local.isNotEmpty(), icon = TablerIcons.CloudUpload) {
                                    direction = SteamNativeCloud.Direction.Upload; transferError = ""
                                }
                            }
                        } else if (transferring) {
                            if (total > 0) {
                                com.winlator.cmod.ui.theme.WinZProgressBar((finished.toFloat() / total).coerceIn(0f, 1f))
                                Text("$finished / $total", style = MaterialTheme.typography.bodySmall)
                            } else {
                                com.winlator.cmod.ui.theme.WinZProgressBar(null)
                                Text(uiStr(R.string.steam_native_cloud_checking), style = MaterialTheme.typography.bodySmall)
                            }
                        } else if (result.isNotBlank()) {
                            Text(uiStr(R.string.steam_cloud_transfer_done), style = MaterialTheme.typography.bodySmall)
                        } else if (direction != null) {
                            Text(uiStr(R.string.steam_cloud_files_found, entries.size), style = MaterialTheme.typography.bodySmall)
                            Text(if (needsTransfer == 0) uiStr(R.string.steam_cloud_already_updated)
                                 else uiStr(R.string.steam_cloud_pending_files, needsTransfer), style = MaterialTheme.typography.bodySmall)
                            if (transferError.isNotBlank()) Text(transferError, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    SteamDialogButtons {
                        if (direction == null) {
                            if (onBackup != null) SteamSecondaryButton(uiStr(R.string.steam_cloud_backup), onClick = onBackup)
                            SteamPrimaryButton(uiStr(R.string.ui_close), onClick = close)
                        } else {
                            SteamSecondaryButton(if (result.isNotBlank()) uiStr(R.string.ui_close) else uiStr(R.string.steam_ui_back), enabled = !transferring) { if (result.isNotBlank()) close() else direction = null }
                            if (!previewLoading && previewError.isBlank() && result.isBlank() && transferError.isBlank() && !transferring && needsTransfer > 0) {
                                SteamPrimaryButton(uiStr(if (direction == SteamNativeCloud.Direction.Download) R.string.steam_cloud_apply_download else R.string.steam_cloud_confirm_upload), onClick = {
                                    transferring = true
                                    finished = 0
                                    total = 0
                                    val selected = direction!!
                                    val selectedPlan = plan!!
                                    scope.launch {
                                        val synced = withContext(Dispatchers.IO) {
                                            runCatching { SteamNativeCloud.synchronize(context, selectedPlan, selected) { done, count ->
                                                android.os.Handler(android.os.Looper.getMainLooper()).post { finished = done; total = count }
                                            } }
                                        }
                                        transferring = false
                                        synced.onSuccess { result = it }.onFailure { transferError = it.message ?: uiStr(R.string.steam_cloud_failed) }
                                    }
                                })
                            }
                        }
                    }
                }
    }
}
