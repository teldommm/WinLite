package com.winlator.cmod.steam

import com.winlator.cmod.R
import com.winlator.cmod.ui.uiStr
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp

import android.content.Intent
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.winlator.cmod.XServerDisplayActivity
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.ui.library.*
import com.winlator.cmod.ui.shortcut.ShortcutSettingsComposeDialog
import java.io.File

/**
 * Actions of a Steam game's page (Play, configure, saves, ...). The page itself is part of SteamActivity's single
 * Compose root, like WinLite's detail pages in MainShell (it used to be a fragment hosted next to the manager,
 * which is what pushed its cover under the status bar); this class holds what used to be that fragment's code.
 */
internal class SteamDetailController(private val activity: SteamActivity, private val appId: Int, private val close: () -> Unit) {
    private var configuring = false
    private var preparing = false
    private val context: android.content.Context get() = activity
    private val alive get() = !activity.isFinishing && !activity.isDestroyed
    private fun startActivity(intent: Intent) = activity.startActivity(intent)
    private fun notice(text: String) = com.winlator.cmod.ui.toast.WinToast.show(context, text)

    // The name comes from the list the manager already holds; the game's PICS file is read (on the main thread, during the
    // page's entrance) only when the page was opened without it.
    val title: String = cachedSteamGameName(appId)
        ?: SteamGames.info(activity, appId).optJSONObject("common")?.optString("name").orEmpty().ifBlank { "Steam $appId" }
    val artworkPath: String = File(activity.cacheDir, "steam-art/$appId-banner.jpg").path

    val callbacks: SteamDetailCallbacks = object : SteamDetailCallbacks {
            override fun onPlay() {
                val shortcut = SteamGames.shortcut(context, appId)
                if (shortcut == null || !SteamGames.configured(context, appId)) { setupPlay(true); return }
                if (!SteamSettings.enabled(context) || (SteamSettings.mode(context, appId) == "RealSteam" && SteamCredentials.load(context) == null)) { notice(uiStr(R.string.steam_ui_enable_signin)); return }
                if (SteamNativeCloud.busy) { notice(uiStr(R.string.steam_native_cloud_busy)); return }
                if (preparing || SteamSession.gameActive || SteamLaunch.modeStatus(appId)?.busy == true) return
                preparing = true
                activity.lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching {
                        SteamLaunch.prepareConfigured(context, shortcut, SteamSettings.mode(context, appId))
                    } }
                    preparing = false
                    if (alive) result.onSuccess { prepared ->
                        startActivity(Intent(context, XServerDisplayActivity::class.java).putExtra("container_id", prepared.container.id)
                            .putExtra("shortcut_path", prepared.file.path).putExtra("shortcut_name", title))
                    }.onFailure { notice(it.message.orEmpty()) }
                }.invokeOnCompletion { preparing = false }
            }
            override fun onSteamExecutable() { setupPlay(false) }
            override fun onClose() = close()
            override fun onSteamMode(mode: String) {
                if (preparing || SteamSession.gameActive || SteamLaunch.modeStatus(appId)?.busy == true) return
                val shortcut = SteamGames.shortcut(context, appId)
                if (shortcut?.getExtra("steamConfigured") != "1") {
                    SteamSettings.setMode(context, appId, mode)
                    SteamGames.changed()
                    return
                }
                if (shortcut.getExtra("steamLaunchMode") == mode && SteamSettings.mode(context, appId) == mode) return
                SteamLaunch.switchMode(context.applicationContext, shortcut, mode) { error ->
                    if (alive) notice(error)
                }
            }
            override fun onConfigure() {
                if (SteamSession.gameActive) { notice(uiStr(R.string.steam_ui_exit_configure)); return }
                if (configuring || SteamLaunch.modeStatus(appId)?.busy == true) return
                configuring = true
                activity.lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching {
                        SteamGames.shortcut(context, appId) ?: run {
                            val containers = ContainerManager(context).containers
                            check(containers.isNotEmpty()) { uiStr(R.string.steam_ui_create_container) }
                            val preferred = context.getSharedPreferences("steam-settings", 0).getInt("steam-container-$appId", -1)
                            val container = containers.firstOrNull { it.id == preferred } ?: containers.first()
                            SteamGames.draft(context, appId, container)
                        }
                    } }
                    if (alive) result.onSuccess { ShortcutSettingsComposeDialog.show(activity, it, Runnable { SteamGames.changed() }) }
                        .onFailure { notice(it.message.orEmpty()) }
                }.invokeOnCompletion { configuring = false }
            }
            override fun onArguments() {
                val shortcut = SteamGames.shortcut(context, appId) ?: return notice(uiStr(R.string.steam_ui_choose_container))
                startActivity(Intent(context, XServerDisplayActivity::class.java).putExtra("container_id", shortcut.container.id))
            }
            override fun onSaves() {
                activity.lifecycleScope.launch {
                    val found = withContext(Dispatchers.IO) { runCatching {
                        SteamGames.shortcut(context, appId) ?: SteamGames.draft(context, appId, ContainerManager(context).containers.firstOrNull() ?: error(uiStr(R.string.steam_ui_create_container)))
                    } }
                    found.onSuccess { shortcut ->
                        if (SteamSettings.mode(context, appId) == "RealSteam") SteamCloud.showStatus(activity, appId, shortcut)
                        else GameSavesComposeDialog.show(activity, shortcut)
                    }.onFailure { notice(it.message.orEmpty()) }
                }
            }
            override fun onFavorite(favorite: Boolean) {
                context.getSharedPreferences("steam-settings", 0).edit().putBoolean("favorite-$appId", favorite).apply(); SteamGames.changed()
            }
            override fun onHome() {
                val shortcut = SteamGames.shortcut(context, appId) ?: return notice(uiStr(R.string.steam_ui_configure_game_first))
                if (SteamGames.configured(context, appId)) HomeShortcuts.pin(activity, shortcut) else notice(uiStr(R.string.steam_ui_configure_game_first))
            }
            override fun onRemove() {
                if (SteamLaunch.modeStatus(appId)?.busy == true) return
                com.winlator.cmod.ui.ConfirmActionDialog.show(activity,
                    uiStr(R.string.steam_ui_uninstall), uiStr(R.string.steam_ui_uninstall_confirm, title), uiStr(R.string.steam_ui_uninstall),
                    Runnable {
                        val result = java.util.concurrent.CompletableFuture<Unit>()
                        SteamSession.work { runCatching { SteamGames.uninstall(context, appId) }.onSuccess { result.complete(Unit) }.onFailure { result.completeExceptionally(it) } }
                        try { result.get() } catch (error: java.util.concurrent.ExecutionException) { throw (error.cause ?: error) }
                    }, Runnable {
                        SteamGames.changed()
                        // Through the page's own back handling: it plays the exit and returns to the list.
                        activity.onBackPressedDispatcher.onBackPressed()
                    })
            }
        }

    private fun setupPlay(play: Boolean) {
        if (SteamSession.gameActive || preparing || SteamLaunch.modeStatus(appId)?.busy == true) return
        val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
        if (root.findViewWithTag<android.view.View>("steam-play-setup") != null) return
        val view = androidx.compose.ui.platform.ComposeView(context).apply { tag = "steam-play-setup" }
        val dismiss = { view.disposeComposition(); root.removeView(view) }
        view.setContent {
            com.winlator.cmod.ui.theme.WinZOverlayTheme {
                androidx.activity.compose.BackHandler { dismiss() }
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.fillMaxSize()
                        .background(com.winlator.cmod.ui.theme.DialogScrimColor)
                        .clickable { dismiss() }.padding(24.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    com.winlator.cmod.ui.theme.ThemedDialogSurface(
                        modifier = androidx.compose.ui.Modifier.clickable { }
                    ) {
                        SteamPlaySetup(appId, play, done = { shortcut ->
                            dismiss()
                            if (play) startActivity(Intent(context, XServerDisplayActivity::class.java)
                                .putExtra("container_id", shortcut.container.id).putExtra("shortcut_path", shortcut.file.path)
                                .putExtra("shortcut_name", SteamGames.displayName(shortcut)))
                        }, dismiss = dismiss)
                    }
                }
            }
        }
        root.addView(view, android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
    }
}
