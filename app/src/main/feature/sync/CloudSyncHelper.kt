package com.winlator.cmod.feature.sync
import android.content.Context
import com.winlator.cmod.feature.steamcloudsync.SteamCloudSyncHelper
import com.winlator.cmod.feature.sync.google.GameSaveBackupManager
import com.winlator.cmod.runtime.container.Shortcut
import kotlinx.coroutines.runBlocking
import timber.log.Timber

object CloudSyncHelper {
    /**
     * Per-shortcut "Disable Cloud Sync" override. When true, every automatic
     * cloud interaction (launch download, conflict prompt, exit provider sync,
     * exit Drive auto-backup) is skipped. Manual user-initiated actions
     * (Back up, Restore, Sync from Cloud) are NOT blocked by this flag.
     */
    @JvmStatic
    fun isOfflineMode(shortcut: Shortcut?): Boolean =
        shortcut != null && shortcut.getExtra("offline_mode", "0") == "1"

    private fun markCloudSaveSyncedById(
        context: Context,
        source: GameSaveBackupManager.GameSource,
        gameId: String,
    ) {
        if (gameId.isEmpty()) return
        val prefs = context.getSharedPreferences("cloud_sync_state", Context.MODE_PRIVATE)
        prefs.edit().putLong("synced_${source.name}_$gameId", System.currentTimeMillis()).apply()
    }

    @JvmStatic
    @JvmOverloads
    fun downloadCloudSaves(
        context: Context,
        source: GameSaveBackupManager.GameSource,
        gameId: String,
        shortcut: Shortcut? = null,
    ): Boolean {
        val result =
            runBlocking {
                when (source) {
                    GameSaveBackupManager.GameSource.STEAM -> {
                        SteamCloudSyncHelper.forceDownloadById(context, gameId.toIntOrNull() ?: return@runBlocking false)
                    }
                    GameSaveBackupManager.GameSource.CUSTOM -> false
                }
            }
        if (result) {
            markCloudSaveSyncedById(context, source, gameId)
        }
        Timber.i("Cloud save download for %s/%s: %s", source, gameId, result)
        return result
    }
}
