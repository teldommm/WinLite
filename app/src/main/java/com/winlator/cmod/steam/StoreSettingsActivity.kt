package com.winlator.cmod.steam

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.ui.theme.RevealSection
import com.winlator.cmod.ui.theme.TablerIcons
import com.winlator.cmod.ui.theme.ThemedDialog
import com.winlator.cmod.ui.theme.ThemedDialogTitle
import com.winlator.cmod.ui.uiStr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StoreSettingsActivity : ImmersiveSteamActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); supportActionBar?.hide()
        setContent { StoreTheme {
            var error by remember { mutableStateOf("") }
            var tier by remember { mutableIntStateOf(SteamDownloadSettings.tier(this)) }
            var speedMenu by remember { mutableStateOf(false) }
            var showFolder by remember { mutableStateOf(false) }
            var help by remember { mutableStateOf("") }
            val scope = rememberCoroutineScope()
            var folder by remember { mutableStateOf(SteamStorage.root(this).path) }
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri != null) scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching {
                        val path = com.winlator.cmod.core.FileUtils.getFilePathFromUri(this@StoreSettingsActivity, uri)
                        check(!path.isNullOrBlank()) { uiStr(R.string.steam_folder_unavailable) }
                        SteamStorage.setRoot(this@StoreSettingsActivity, java.io.File(path))
                    } }
                    error = result.exceptionOrNull()?.message.orEmpty(); folder = SteamStorage.root(this@StoreSettingsActivity).path
                }
            }
            fun speedLabel(index: Int) = uiStr(when (index) { 0 -> R.string.steam_ui_slow; 3 -> R.string.steam_ui_blazing; else -> R.string.steam_ui_fast })
            val enabled = SteamSettings.enabled(this@StoreSettingsActivity)
            Column(Modifier.fillMaxSize().storeBackground().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))) {
                SteamTopBar("Steam", uiStr(R.string.steam_ui_account_downloads), onBack = { finish() })
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    StoreSection {
                        SteamToggleRow(TablerIcons.BrandSteam, uiStr(R.string.steam_ui_integration), uiStr(R.string.steam_integration_short), enabled) { value ->
                            error = runCatching { SteamSettings.setEnabled(this@StoreSettingsActivity, value) }.exceptionOrNull()?.message.orEmpty()
                            if (!value) help = uiStr(R.string.steam_ui_disabled_help)
                        }
                    }
                    if (error.isNotEmpty()) Text(steamStatusText(error), Modifier.padding(horizontal = 3.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    RevealSection(enabled) {
                      Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        SteamSectionTitle("Steam")
                        StoreSection {
                            SteamRow(TablerIcons.DeviceGamepad2, uiStr(R.string.steam_store_library), uiStr(R.string.steam_store_library_help),
                                onClick = { startActivity(Intent(this@StoreSettingsActivity, SteamActivity::class.java)) })
                            SteamDivider()
                            SteamRow(TablerIcons.Download, uiStr(R.string.steam_ui_downloads), uiStr(R.string.steam_store_downloads_help),
                                onClick = { startActivity(Intent(this@StoreSettingsActivity, SteamActivity::class.java).putExtra("steam_tab", 1)) })
                            SteamDivider()
                            SteamRow(TablerIcons.FolderOpen, uiStr(R.string.steam_store_location), folder, onClick = { showFolder = true })
                        }
                        SteamSectionTitle(uiStr(R.string.steam_store_features))
                        StoreSection {
                            listOf("achievement-notifications" to R.string.steam_achievement_notifications, "achievement-sync" to R.string.steam_achievement_sync, "social" to R.string.steam_friends).forEachIndexed { index, (key, label) ->
                                var checked by remember(key) { mutableStateOf(getSharedPreferences("steam-settings", 0).getBoolean(key, key == "achievement-notifications")) }
                                if (index > 0) SteamDivider()
                                SteamToggleRow(when (key) { "social" -> TablerIcons.Users; "achievement-sync" -> TablerIcons.Refresh; else -> TablerIcons.Trophy }, uiStr(label), null, checked, enabled = !SteamSession.gameActive) { value ->
                                    checked = value; getSharedPreferences("steam-settings", 0).edit().putBoolean(key, value).apply()
                                    if (key == "social") SteamSession.work { SteamSession.session?.setAutoPersonaOnline(value); SteamSession.session?.setPersonaState(if (value) 1 else 0); if (value) SteamSocial.appEvent(this@StoreSettingsActivity) }
                                    if (key == "achievement-sync" && value) SteamAchievements.syncPending(this@StoreSettingsActivity)
                                }
                            }
                        }
                        StoreSection {
                            Box {
                                SteamRow(TablerIcons.Gauge, uiStr(R.string.steam_ui_download_speed), speedLabel(tier), onClick = { speedMenu = true },
                                    trailing = { Icon(TablerIcons.ChevronDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) })
                                SteamMenu(speedMenu, { speedMenu = false }) {
                                    SteamDownloadSettings.tiers.forEach { index -> SteamChoiceItem(speedLabel(index), index == tier) { tier = index; SteamDownloadSettings.setTier(this@StoreSettingsActivity, index); speedMenu = false } }
                                }
                            }
                        }
                      }
                    }
                }
            }
            if (help.isNotBlank()) ThemedDialog(onDismissRequest = { help = "" }) {
                Text(help, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                SteamDialogButtons { SteamPrimaryButton(uiStr(R.string.ui_close)) { help = "" } }
            }
            if (showFolder) ThemedDialog(onDismissRequest = { showFolder = false }) {
                ThemedDialogTitle(uiStr(R.string.steam_store_location))
                Text(folder, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                Text(uiStr(R.string.steam_folder_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                SteamDialogButtons {
                    SteamSecondaryButton(uiStr(R.string.steam_default_folder), enabled = !SteamSession.downloading && !SteamSession.gameActive) {
                        error = runCatching { SteamStorage.setRoot(this@StoreSettingsActivity, null); folder = SteamStorage.root(this@StoreSettingsActivity).path }.exceptionOrNull()?.message.orEmpty()
                        showFolder = false
                    }
                    SteamPrimaryButton(uiStr(R.string.steam_choose_folder), enabled = !SteamSession.downloading && !SteamSession.gameActive) { showFolder = false; picker.launch(null) }
                }
            }
        } }
    }
}
