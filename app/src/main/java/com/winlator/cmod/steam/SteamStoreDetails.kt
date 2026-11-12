package com.winlator.cmod.steam

import com.winlator.cmod.ui.theme.TablerIcons
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import com.winlator.cmod.ui.theme.WinZShapes
import com.winlator.cmod.ui.theme.controlAccentColor
import com.winlator.cmod.R
import com.winlator.cmod.ui.uiStr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal object SteamStoreMetadata {
    fun fetch(id: Int): JSONObject {
        val connection = URL("https://store.steampowered.com/api/appdetails?appids=$id&cc=us&l=english").openConnection() as HttpURLConnection
        connection.connectTimeout = 10000
        connection.readTimeout = 12000
        connection.setRequestProperty("Accept", "application/json")
        return try {
            if (connection.responseCode != 200) return JSONObject()
            val text = connection.inputStream.bufferedReader().use { it.readText().take(1500000) }
            JSONObject(text).optJSONObject(id.toString())?.optJSONObject("data") ?: JSONObject()
        } finally { connection.disconnect() }
    }
    fun cloudListing(id: Int): JSONObject {
        val future = CompletableFuture<JSONObject>()
        SteamSession.work {
            try {
                check(SteamSession.signedIn && !SteamSession.gameActive)
                future.complete(JSONObject(SteamSession.session?.getCloudFileList(id) ?: error("Steam Cloud unavailable")))
            } catch (error: Throwable) { future.completeExceptionally(error) }
        }
        return future.get(35, TimeUnit.SECONDS)
    }
}

// What the sheet read last time for a game (PICS info + store page). Opening the sheet again shows it at once,
// so Version / Language and the text do not appear (and shift everything) on every open; it is refreshed behind.
private val detailsCache = java.util.concurrent.ConcurrentHashMap<String, Pair<JSONObject, JSONObject>>()

@Composable
internal fun SteamStoreDetails(id: Int, installed: Boolean, state: SteamUiSnapshot) {
    val context = LocalContext.current
    var tab by remember(id) { mutableIntStateOf(0) }
    var branch by remember(id) { mutableStateOf(SteamInstallOptions.branch(context, id)) }
    var language by remember(id) { mutableStateOf(SteamInstallOptions.language(context, id)) }
    var branchMenu by remember { mutableStateOf(false) }
    var languageMenu by remember { mutableStateOf(false) }
    val cacheKey = "$id-$installed"
    val loaded by produceState<Pair<JSONObject, JSONObject>?>(detailsCache[cacheKey], id, state.signedIn, installed) {
        val fresh = withContext(Dispatchers.IO) {
            Pair(
                runCatching { if (installed) SteamGames.info(context, id) else if (state.signedIn) SteamSession.info(id) else JSONObject() }.getOrDefault(JSONObject()),
                runCatching { SteamStoreMetadata.fetch(id) }.getOrDefault(JSONObject())
            )
        }
        // A failed refresh (offline, signed out) must not blank what was already shown.
        if (fresh.second.length() > 0 || fresh.first.length() > 0 || value == null) value = fresh
        if (fresh.second.length() > 0) detailsCache[cacheKey] = fresh
    }
    val dataReady = loaded != null
    val pics = loaded?.first ?: JSONObject()
    val store = loaded?.second ?: JSONObject()
    val branches = remember(pics) { SteamCatalog.branches(pics) }
    val languages = remember(pics) { SteamCatalog.languages(pics) }
    val downloadSize = remember(pics, branch, language) {
        runCatching { SteamCatalog.depots(pics, branch, language).sumOf { it.size } }.getOrDefault(0L)
    }
    val installedSize by produceState<Long?>(null, id, installed) {
        value = if (!installed) null else withContext(Dispatchers.IO) {
            runCatching {
                val dir = SteamGames.directory(context, id)
                dir.walkTopDown().onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                    .filter { it.isFile }.sumOf { it.length() }
            }.getOrNull()
        }
    }
    val recordedBranch = if (installed) pics.optString("winliteInstallBranch", "public") else "public"
    val recordedLanguage = if (installed) pics.optString("winliteInstallLanguage", "english") else "english"
    fun readableSize(bytes: Long): String = if (bytes >= 1073741824L) "%.1f GiB".format(bytes / 1073741824.0)
        else "%.1f MiB".format(bytes / 1048576.0)
    val cached = rememberSteamGameSnapshot(context, id)
    val ach = cached.achievements
    var showCloud by remember(id) { mutableStateOf(false) }
    val shortcut = remember(installed, id, SteamGames.version) {
        if (installed) SteamGames.shortcut(context, id) else null
    }
    val tabs = listOf(
        uiStr(R.string.steam_store_details),
        uiStr(R.string.steam_ui_achievements),
        uiStr(R.string.steam_store_dlc),
        uiStr(R.string.steam_store_cloud),
        uiStr(R.string.steam_store_media)
    )
    LaunchedEffect(tab, id, state.signedIn) {
        if (tab == 1 && state.signedIn && !state.gameActive) SteamUserData.refreshAchievements(context, id)
    }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        SteamTabRow(tabs.mapIndexed { index, title -> SteamTabItem(index, title) }, tab) { tab = it }
        val scroll = rememberScrollState()
        LaunchedEffect(tab) { if (scroll.value > 0) scroll.animateScrollTo(0, tween(200)) }
        // The tab body is cross-faded; the box around it follows the new height smoothly instead of jumping.
        Box(Modifier.fillMaxWidth().animateContentSize(tween(240, easing = FastOutSlowInEasing)).heightIn(min = 96.dp, max = 270.dp).verticalScroll(scroll)) {
          AnimatedContent(
            targetState = tab,
            transitionSpec = { (fadeIn(tween(200, delayMillis = 80)) togetherWith fadeOut(tween(110))).using(SizeTransform(clip = false) { _, _ -> snap() }) },
            label = "steamStoreTab"
          ) { page ->
           Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = if (page == 0) Arrangement.Top else Arrangement.spacedBy(10.dp)
           ) {
            when (page) {
                0 -> {
                    // Every block eases in when its data arrives (SteamReveal); each carries its own bottom gap so the
                    // spacing appears with it. With cached data they are already there on the first frame.
                    val gap = Modifier.padding(bottom = 10.dp)
                    val description = store.optString("short_description")
                        .replace(Regex("<[^>]+>"), "").trim()
                    SteamReveal(dataReady && description.isNotBlank()) { Text(description, gap, style = MaterialTheme.typography.bodyMedium) }
                    val bytes = installedSize?.takeIf { installed } ?: downloadSize
                    Text(uiStr(R.string.steam_store_game_size,
                        if (bytes > 0) readableSize(bytes) else uiStr(R.string.steam_store_size_unknown)),
                        gap, style = MaterialTheme.typography.bodySmall)
                    SteamReveal(pics.optJSONObject("depots") != null) {
                        Column(gap) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.weight(1f)) {
                                    SteamDropdownButton(uiStr(R.string.steam_store_version) + ": " + branch, Modifier.fillMaxWidth(),
                                        enabled = !state.downloading && !state.gameActive) { branchMenu = true }
                                    SteamMenu(branchMenu, { branchMenu = false }) {
                                        branches.forEach { option ->
                                            SteamChoiceItem(option, option == branch) {
                                                SteamInstallOptions.setBranch(context, id, option)
                                                branch = option
                                                branchMenu = false
                                            }
                                        }
                                    }
                                }
                                Box(Modifier.weight(1f)) {
                                    SteamDropdownButton(uiStr(R.string.steam_store_language) + ": " + language, Modifier.fillMaxWidth(),
                                        enabled = !state.downloading && !state.gameActive) { languageMenu = true }
                                    SteamMenu(languageMenu, { languageMenu = false }) {
                                        languages.forEach { option ->
                                            SteamChoiceItem(option, option == language) {
                                                SteamInstallOptions.setLanguage(context, id, option)
                                                language = option
                                                languageMenu = false
                                            }
                                        }
                                    }
                                }
                            }
                            SteamReveal(installed && (recordedBranch != branch || recordedLanguage != language)) {
                                SteamPrimaryButton(uiStr(R.string.steam_store_apply_options), Modifier.padding(top = 10.dp),
                                    enabled = state.signedIn && !state.downloading && !state.gameActive,
                                    icon = TablerIcons.Download) { SteamSession.install(id) }
                            }
                        }
                    }
                    val common = pics.optJSONObject("common")
                    val developers = store.optJSONArray("developers")
                    val publishers = store.optJSONArray("publishers")
                    for ((label, values) in listOf(uiStr(R.string.steam_ui_developer) to developers, uiStr(R.string.steam_ui_publisher) to publishers)) {
                        val names = (0 until (values?.length() ?: 0)).map { values!!.optString(it) }.filter { it.isNotBlank() }
                        SteamReveal(names.isNotEmpty()) { Text("$label: ${names.joinToString(", ")}", gap, style = MaterialTheme.typography.bodySmall) }
                    }
                    val release = store.optJSONObject("release_date")?.optString("date")
                        .orEmpty().ifBlank { common?.optString("steam_release_date").orEmpty() }
                    SteamReveal(release.isNotBlank()) { Text(uiStr(R.string.steam_ui_release, release), gap, style = MaterialTheme.typography.bodySmall) }
                    SteamReveal(dataReady && description.isBlank() && release.isBlank() && developers == null) { Text(uiStr(R.string.steam_store_no_data), gap) }
                }
                1 -> {
                    if (ach == null) {
                        Text(uiStr(if (SteamUserData.busy("achievements-$id")) R.string.steam_ui_loading_achievements else R.string.steam_ui_connect_achievements))
                        if (state.signedIn && !state.gameActive)
                            SteamSecondaryButton(uiStr(R.string.steam_ui_refresh_info), icon = TablerIcons.Refresh) { SteamUserData.refreshAchievements(context, id, true) }
                    } else {
                        Text(uiStr(R.string.steam_ui_ach_count, ach.count { it.unlocked }, ach.size))
                        ach.forEach { achievement ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(if (achievement.unlocked) TablerIcons.CircleCheck else TablerIcons.Lock,
                                    null, Modifier.size(18.dp), tint = if (achievement.unlocked) controlAccentColor() else MaterialTheme.colorScheme.onSurfaceVariant)
                                Column {
                                    Text(achievement.title, style = MaterialTheme.typography.labelLarge)
                                    if (achievement.description.isNotBlank())
                                        Text(achievement.description, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                2 -> {
                    val dlcJson = store.optJSONArray("dlc") ?: JSONArray()
                    val ids = (0 until dlcJson.length()).mapNotNull { dlcJson.optInt(it).takeIf { item -> item > 0 } }
                        .ifEmpty {
                            pics.optJSONObject("extended")?.optString("listofdlc").orEmpty()
                                .split(',').mapNotNull { it.trim().toIntOrNull() }
                        }
                    val dlcNames by produceState<Map<Int, String>>(emptyMap(), id, ids.joinToString(","), state.signedIn) {
                        value = if (!state.signedIn || ids.isEmpty()) emptyMap() else withContext(Dispatchers.IO) {
                            ids.take(16).mapNotNull { dlcId ->
                                val name = runCatching { SteamSession.info(dlcId).optJSONObject("common")?.optString("name") }
                                    .getOrNull().orEmpty()
                                if (name.isNotBlank()) dlcId to name else null
                            }.toMap()
                        }
                    }
                    if (ids.isEmpty()) Text(uiStr(R.string.steam_store_no_dlc))
                    else {
                        Text(uiStr(R.string.steam_store_dlc_count, ids.size), style = MaterialTheme.typography.bodySmall)
                        ids.take(60).forEachIndexed { index, dlcId ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(TablerIcons.Apps, null, Modifier.size(18.dp))
                                Text(dlcNames[dlcId].orEmpty().ifBlank { "DLC ${index + 1}" }, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                3 -> {
                    val cloud by produceState<Pair<JSONObject?, String>>(null to "", id, state.signedIn, state.gameActive, page) {
                        value = if (state.signedIn && !state.gameActive) withContext(Dispatchers.IO) {
                            runCatching { SteamStoreMetadata.cloudListing(id) }
                                .fold({ it to "" }, { null to (it.message ?: "Steam Cloud unavailable") })
                        } else null to ""
                    }
                    if (!state.signedIn) Text(uiStr(R.string.steam_ui_enable_signin))
                    else if (cloud.second.isNotBlank()) {
                        Text(cloud.second, color = MaterialTheme.colorScheme.error)
                        Text(uiStr(R.string.steam_cloud_client_fallback), style = MaterialTheme.typography.bodySmall)
                    } else if (cloud.first == null) Text(uiStr(R.string.steam_native_cloud_checking))
                    else {
                        val files = cloud.first?.optJSONArray("files") ?: JSONArray()
                        val remote = (0 until files.length()).map { files.getJSONObject(it) }.filter { it.optInt("persistState") == 0 }
                        Text(uiStr(R.string.steam_cloud_files_found, remote.size))
                        remote.maxOfOrNull { it.optLong("timestamp") }?.takeIf { it > 0 }?.let {
                            Text(uiStr(R.string.steam_cloud_save_date, DateFormat.getDateTimeInstance().format(Date(it * 1000))))
                        }
                    }
                    if (shortcut != null) {
                        SteamPrimaryButton(uiStr(R.string.steam_cloud_saves), enabled = state.signedIn && !state.gameActive && !SteamNativeCloud.busy,
                            icon = TablerIcons.CloudUpload) { showCloud = true }
                    } else Text(uiStr(R.string.steam_store_cloud_setup), style = MaterialTheme.typography.bodySmall)
                }
                4 -> {
                    val images = store.optJSONArray("screenshots") ?: JSONArray()
                    val movies = store.optJSONArray("movies") ?: JSONArray()
                    if (images.length() == 0 && movies.length() == 0) Text(uiStr(R.string.steam_store_no_media))
                    else {
                        val pictures = (0 until images.length()).mapNotNull { images.optJSONObject(it)?.optString("path_thumbnail")?.takeIf { url -> url.startsWith("https://") } }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            items(pictures) { url ->
                                SteamArt(url, Modifier.width(220.dp).aspectRatio(16f / 9f).clip(WinZShapes.Small).clickable {
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                })
                            }
                        }
                        if (movies.length() > 0) {
                            Text(uiStr(R.string.steam_store_videos))
                            for (i in 0 until movies.length()) {
                                val movie = movies.optJSONObject(i) ?: continue
                                val url = movie.optJSONObject("mp4")?.optString("max") ?: ""
                                SteamSecondaryButton(movie.optString("name").ifBlank { uiStr(R.string.steam_store_videos) }, Modifier.fillMaxWidth(),
                                    enabled = url.startsWith("https://"), icon = TablerIcons.PlayerPlay) {
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                }
                            }
                        }
                    }
                }
            }
           }
          }
        }
    }
    if (showCloud && shortcut != null) {
        SteamNativeCloudPanel(id, shortcut.container, { showCloud = false })
    }
}
