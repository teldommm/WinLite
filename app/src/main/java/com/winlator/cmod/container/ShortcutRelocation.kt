package com.winlator.cmod.container

import android.content.Context
import com.winlator.cmod.core.DefaultVersion
import com.winlator.cmod.core.KeyValueSet
import com.winlator.cmod.directaudio.DirectAudioDriver
import com.winlator.cmod.steam.SteamGames
import com.winlator.cmod.ui.library.HomeShortcuts
import java.io.File

/**
 * Shared logic for moving shortcuts between containers: the shortcut editor's container switch
 * (ShortcutEditorV2.changeContainer) and the container deletion flow (ContainersSettingsScreen),
 * which offers to salvage a dying container's games instead of deleting them with it.
 */
object ShortcutRelocation {

    /**
     * Architecture guard for a shortcut that now lives in [target]: DXVK/VKD3D builds with
     * "arm64ec" in the name only run on arm64 (WoW) Wine, and DirectAudio ships arm64ec-only
     * builds — a shortcut's own extras naming either would break (broken rendering / no audio)
     * on any other container. Rewrites the moved shortcut's own config to the target
     * container's choice; XServerDisplayActivity applies the same fallback at launch, so this
     * mostly keeps the stored settings honest.
     */
    fun sanitizeForContainer(shortcut: Shortcut, target: Container) {
        var changed = false
        val wrapperConfigExtra = shortcut.getExtra("dxwrapperConfig")
        if (wrapperConfigExtra.isNotEmpty() && !target.getWineVersion().contains("arm64ec", true)) {
            val config = KeyValueSet(wrapperConfigExtra)
            val containerConfig = KeyValueSet(target.getDXWrapperConfig())
            for (key in arrayOf("version", "vkd3dVersion")) {
                if (config.get(key).contains("arm64ec", true)) {
                    var fallback = containerConfig.get(key)
                    if (fallback.contains("arm64ec", true) || fallback.isEmpty())
                        fallback = if (key == "version") DefaultVersion.DXVK else DefaultVersion.VKD3D
                    config.put(key, fallback)
                    changed = true
                }
            }
            if (changed) shortcut.putExtra("dxwrapperConfig", config.toString())
        }
        if (DirectAudioDriver.isSelected(shortcut.getExtra("audioDriver")) &&
                DirectAudioDriver.flavorOf(target.getWineVersion()) == null) {
            shortcut.putExtra("audioDriver", null)
            changed = true
        }
        if (changed) shortcut.saveData()
    }

    /** Same Steam re-setup as ShortcutEditorV2.changeContainer, for a shortcut that moved. */
    private fun rebindSteam(source: Shortcut, moved: Shortcut, context: Context, target: Container) {
        source.getExtra("steamAppId").toIntOrNull()?.let { steamId ->
            moved.putExtra("steamConfigured", "0")
            moved.saveData()
            context.getSharedPreferences("steam-settings", 0)
                .edit().putInt("steam-container-$steamId", target.id).apply()
            SteamGames.changed()
        }
    }

    /**
     * Moves every shortcut of [from] into [target], called right before [from] is deleted.
     * Returns moved/skipped counts; a shortcut is skipped when the target already has a
     * .desktop with the same name (nothing to merge — the user is told how many were lost).
     */
    fun moveAll(from: Container, target: Container, context: Context): Pair<Int, Int> {
        var moved = 0
        var skipped = 0
        val files = from.getDesktopDir().listFiles() ?: return 0 to 0
        for (file in files) {
            if (!file.name.endsWith(".desktop")) continue
            val dst = File(target.getDesktopDir(), file.name)
            if (dst.exists()) { skipped++; continue }
            val shortcut = Shortcut(from, file)
            if (!shortcut.cloneToContainer(target) || !dst.isFile()) { skipped++; continue }
            val movedShortcut = Shortcut(target, dst)
            sanitizeForContainer(movedShortcut, target)
            rebindSteam(shortcut, movedShortcut, context, target)
            HomeShortcuts.updatePinned(context, movedShortcut)
            moved++
        }
        return moved to skipped
    }
}
