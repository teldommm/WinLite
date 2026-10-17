package com.winlator.cmod.ui.library

import com.winlator.cmod.R
import androidx.compose.runtime.mutableStateOf
import android.content.Intent
import android.os.Environment
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.winlator.cmod.MainActivity
import com.winlator.cmod.XServerDisplayActivity
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.contents.ContentsManager
import com.winlator.cmod.core.FileUtils
import com.winlator.cmod.ui.ThemedAlertHost
import com.winlator.cmod.ui.toast.WinToast
import com.winlator.cmod.ui.shortcut.ShortcutSettingsComposeDialog
import com.winlator.cmod.ui.theme.findActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.winlator.cmod.steamgrid.ArtworkRepository

// Game detail as a MainShell detail entry (replaces GameDetailFragment). Same actions; the
// runtime subtitle (which syncs ContentsManager — disk work) is now resolved off the main thread
// instead of blocking the screen's creation.
//
// The Shortcut comes from the Library (preloaded: already in memory) whenever the page is opened
// from a tile. Only otherwise (reopened on a new path after a container switch) is it looked up
// on disk - off the main thread now: it used to scan every container's shortcuts right here in
// composition, freezing the first frame of the page's entrance for as long as that took.
@Composable
fun GameDetailRoute(shortcutPath: String, preloaded: Shortcut? = null, onClose: () -> Unit, onLibraryChanged: () -> Unit) {
    val activity = LocalContext.current.findActivity() as AppCompatActivity
    var missing by remember(shortcutPath) { mutableStateOf(false) }
    val shortcut by produceState(preloaded?.takeIf { it.file?.path == shortcutPath }, shortcutPath) {
        if (value != null) return@produceState
        val found = withContext(Dispatchers.IO) {
            ContainerManager(activity).loadShortcuts().firstOrNull { it?.file?.path == shortcutPath }
        }
        if (found == null) missing = true else value = found
    }
    if (missing) {
        // Shortcut vanished (deleted elsewhere) — nothing to show.
        LaunchedEffect(Unit) { onClose() }
        return
    }
    // Still loading (only without a preloaded Shortcut): the page's plain background meanwhile.
    val loaded = shortcut ?: return
    GameDetailLoaded(shortcutPath, loaded, activity, onClose, onLibraryChanged)
}

@Composable
private fun GameDetailLoaded(
    shortcutPath: String,
    shortcut: Shortcut,
    activity: AppCompatActivity,
    onClose: () -> Unit,
    onLibraryChanged: () -> Unit
) {
    val baseName = remember(shortcut) { FileUtils.getBasename(shortcut.file.path) }

    // Bumped whenever the screen comes back to the front (after a game, after the launcher's
    // "add to home screen" prompt): the stats and the home-screen state are read again.
    var refreshTick by remember { mutableIntStateOf(0) }
    var pinTick by remember { mutableIntStateOf(0) }
    // The first resume is the screen opening: the effects below already load for it.
    val firstResume = remember { booleanArrayOf(true) }
    LifecycleResumeEffect(shortcutPath) {
        if (firstResume[0]) firstResume[0] = false else refreshTick++
        onPauseOrDispose { }
    }
    // Playtime / plays / last launch and the stored folder size first (cheap); the folder is only
    // measured again when the stored size is missing or out of date.
    val stats by produceState(GameStats(), shortcut, refreshTick) {
        val basic = withContext(Dispatchers.IO) { GameStatsLoader.load(activity, shortcut) }
        val cached = withContext(Dispatchers.IO) { GameStatsLoader.cachedSize(activity, shortcut) }
        value = basic.copy(sizeText = cached?.text ?: value.sizeText)
        if (cached == null || !cached.fresh) {
            value = value.copy(sizeText = withContext(Dispatchers.IO) { GameStatsLoader.sizeText(activity, shortcut) })
        }
    }
    val homePinned by produceState(false, shortcut, refreshTick, pinTick) {
        value = withContext(Dispatchers.IO) { HomeShortcuts.isPinned(activity, shortcut) }
    }
    // Both are passed on: the screen picks the banner, or blurs/frames the cover when that is all
    // there is (a portrait cover stretched over the header looked wrong).
    // The user's own background (banners/NAME.user.png) wins over the downloaded banner.
    val bannerPath = remember(shortcut) {
        val banners = File(Environment.getExternalStorageDirectory(), "Winlator/banners")
        File(banners, "$baseName.user.png").takeIf { it.isFile && it.length() > 0 }?.path
            ?: File(banners, "$baseName.png").takeIf { it.exists() }?.path
    }
    // The user's own cover (covers/NAME.user.png), else the downloaded one, else the offline
    // placeholder built from the icon (covers/NAME.gen.png).
    val userCoverPath = remember(shortcut) {
        File(File(Environment.getExternalStorageDirectory(), "Winlator/covers"), "$baseName.user.png")
            .takeIf { it.isFile && it.length() > 0 }?.path
    }
    val coverPath = remember(shortcut) {
        val covers = File(Environment.getExternalStorageDirectory(), "Winlator/covers")
        userCoverPath
            ?: File(covers, "$baseName.png").takeIf { it.exists() }?.path
            ?: File(covers, "$baseName.gen.png").takeIf { it.isFile && it.length() > 0 }?.path
    }
    // Same label as the Library tile ("Proton 9.0 arm64ec"), which the Library has
    // normally already resolved — so the subtitle is final from the first frame. Refreshed in the
    // background in case the container's runtime changed since.
    val subtitle by produceState(
        LibraryEnvironmentLabels.cached(shortcut.file.path) ?: shortcut.container.name,
        shortcut
    ) {
        value = withContext(Dispatchers.IO) { LibraryEnvironmentLabels.resolveOne(activity, shortcut) }
    }

    val callbacks = remember(shortcut) {
        object : GameDetailCallbacks {
            override fun onBack() = onClose()

            override fun onPlay() {
                val intent = Intent(activity, XServerDisplayActivity::class.java)
                intent.putExtra("container_id", shortcut.container.id)
                intent.putExtra("shortcut_path", shortcut.file.path)
                intent.putExtra("shortcut_name", shortcut.name)
                activity.startActivity(intent)
            }

            override fun onConfigure() {
                // Edits made here (rename, copy to container) must show up in the Library tab.
                // After a container switch the shortcut lives at a new path: reopen the page on it.
                ShortcutSettingsComposeDialog.show(activity, shortcut, Runnable { onLibraryChanged() }) { newPath ->
                    onClose()
                    (activity as? MainActivity)?.openGameDetail(newPath)
                }
            }

            override fun onArguments() {
                activity.startActivity(
                    Intent(activity, XServerDisplayActivity::class.java).putExtra("container_id", shortcut.container.id)
                )
            }

            override fun onSaves() {
                GameSavesComposeDialog.show(activity, shortcut)
            }

            override fun onFavorite(favorite: Boolean) {
                shortcut.putExtra("favorite", if (favorite) "1" else "0")
                shortcut.saveData()
                onLibraryChanged()
            }

            // Same clean-up as the Library tile's Remove: the shortcut file and its .lnk/.bat, the
            // artwork nobody else uses, the home-screen icon. Asked with the standard confirm dialog.
            override fun onRemove() {
                ThemedAlertHost.confirm(
                    activity,
                    activity.getString(R.string.ui_remove_shortcut_title),
                    activity.getString(R.string.ui_remove_shortcut_message),
                    activity.getString(R.string.remove),
                    {
                        if (shortcut.file.delete()) {
                            val base = shortcut.file.path.substringBeforeLast('.', "")
                            if (base.isNotEmpty()) {
                                File("$base.lnk").delete()
                                File("$base.bat").delete()
                            }
                            ArtworkRepository.deleteArtworkIfUnused(activity, shortcut.file)
                            HomeShortcuts.unpin(activity, shortcut)
                            onLibraryChanged()
                            onClose()
                            WinToast.show(activity, activity.getString(R.string.ui_shortcut_removed), Toast.LENGTH_SHORT)
                            SaveProfileCleanup.offerDelete(activity, shortcut)
                        } else {
                            WinToast.show(activity, activity.getString(R.string.ui_shortcut_remove_failed), Toast.LENGTH_LONG)
                        }
                    },
                    true
                )
            }

            override fun onPinHome() {
                when (HomeShortcuts.pin(activity, shortcut)) {
                    HomeShortcuts.PinResult.Unsupported ->
                        WinToast.show(activity, activity.getString(R.string.ui_pin_unsupported), Toast.LENGTH_LONG)
                    HomeShortcuts.PinResult.Restored -> {
                        WinToast.show(activity, activity.getString(R.string.ui_pin_restored), Toast.LENGTH_SHORT)
                        pinTick++
                    }
                    // The launcher shows its own prompt; the state is read again when we come back.
                    HomeShortcuts.PinResult.Requested -> pinTick++
                }
            }

            override fun onUnpinHome() {
                ThemedAlertHost.confirm(
                    activity,
                    activity.getString(R.string.ui_unpin_title),
                    activity.getString(R.string.ui_unpin_message),
                    activity.getString(R.string.remove),
                    {
                        if (HomeShortcuts.unpin(activity, shortcut)) {
                            WinToast.show(activity, activity.getString(R.string.ui_unpin_done), Toast.LENGTH_SHORT)
                        } else {
                            WinToast.show(activity, activity.getString(R.string.ui_unpin_not_pinned), Toast.LENGTH_SHORT)
                        }
                        pinTick++
                    },
                    true
                )
            }
        }
    }

    val motionEnabled = rememberArtworkMotionEnabled()
    CompositionLocalProvider(LocalArtworkMotion provides motionEnabled) {
        GameDetailScreen(
            shortcut.name,
            subtitle,
            bannerPath,
            coverPath,
            userCoverPath != null,
            shortcut.icon,
            "1" == shortcut.getExtra("favorite", "0"),
            stats,
            homePinned,
            callbacks
        )
    }
}
