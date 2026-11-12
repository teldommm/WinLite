package com.winlator.cmod.steam

import android.content.Context
import androidx.compose.runtime.*
import com.winlator.cmod.ui.library.LibraryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

// Last list / snapshot read, kept for the process: a screen that opens again starts from it (and refreshes it behind),
// so the library, the profile and the playtimes are there on the first frame instead of popping in and shifting the page.
private val libraryCache = java.util.concurrent.ConcurrentHashMap<String, List<LibraryItem>>()
@Volatile private var userSnapshotCache: SteamUserSnapshot? = null

/** null until the list has been read for the first time (nothing cached either): "no games" must not be shown for that. */
@Composable internal fun rememberSteamLibraryOrNull(context: Context, json: String, enabled: Boolean = true, filterType: String = "game"): List<LibraryItem>? {
    val changes = SteamGames.version
    val artwork = SteamLibrary.artworkVersion
    val items by produceState<List<LibraryItem>?>(if (enabled) libraryCache[filterType] else emptyList(), json, enabled, changes, artwork, filterType) {
        value = if (enabled) withContext(Dispatchers.IO) { SteamLibrary.items(context, json, filterType) }.also { libraryCache[filterType] = it } else emptyList()
    }
    return items
}

@Composable fun rememberSteamLibrary(context: Context, json: String, enabled: Boolean = true, filterType: String = "game"): List<LibraryItem> =
    rememberSteamLibraryOrNull(context, json, enabled, filterType) ?: emptyList()

internal data class SteamUserSnapshot(val profile: JSONObject = JSONObject(), val owned: Map<Int, JSONObject> = emptyMap(), val achievements: Map<Int, List<SteamUserData.Achievement>> = emptyMap())
@Composable internal fun rememberSteamUserSnapshot(context: Context): SteamUserSnapshot {
    val revision = SteamUserData.contentRevision.intValue
    val snapshot by produceState(userSnapshotCache ?: SteamUserSnapshot(), revision) {
        value = withContext(Dispatchers.IO) {
            val games = SteamUserData.owned(context)
            SteamUserSnapshot(SteamUserData.profile(context), (0 until games.length()).map { games.getJSONObject(it) }.associateBy { it.optInt("appId") }, SteamUserData.cachedAchievements(context))
        }
        userSnapshotCache = value
    }
    return snapshot
}

internal data class SteamGameSnapshot(val ready: Boolean = false, val info: JSONObject = JSONObject(), val play: JSONObject? = null, val achievements: List<SteamUserData.Achievement>? = null)
// Last snapshot of each game: reopening a game page / sheet starts from it, so the achievements count and the
// other values are there on the first frame instead of arriving (and moving things) after every open.
private val snapshotCache = java.util.concurrent.ConcurrentHashMap<Int, SteamGameSnapshot>()

@Composable internal fun rememberSteamGameSnapshot(context: Context, id: Int): SteamGameSnapshot {
    val revision = SteamUserData.contentRevision.intValue
    val snapshot by produceState(snapshotCache[id] ?: SteamGameSnapshot(), id, revision) {
        value = withContext(Dispatchers.IO) {
            SteamGameSnapshot(true, runCatching { SteamGames.info(context, id) }.getOrDefault(JSONObject()), SteamUserData.game(context, id),
                SteamUserData.achievementData(context, id)?.let { SteamUserData.parseAchievements(it, id) })
        }
        snapshotCache[id] = value
    }
    return snapshot
}
