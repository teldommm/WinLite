package com.winlator.cmod.steam

import com.winlator.cmod.ui.uiStr

import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bumptech.glide.Glide
import com.winlator.cmod.R
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.itemsIndexed
import com.winlator.cmod.ui.theme.RevealOnEnter
import com.winlator.cmod.ui.theme.RevealSection
import com.winlator.cmod.ui.theme.WinZMotion
import com.winlator.cmod.ui.theme.TablerIcons
import com.winlator.cmod.ui.theme.WinZShapes
import org.json.JSONObject
import java.io.File

@Composable fun SteamManagerScreen(state: SteamUiSnapshot, initialTab: Int, onBack: () -> Unit, signOut: () -> Unit, openGame: (Int) -> Unit) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(initialTab.coerceIn(0, 4)) }
    var query by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf(false) }
    var grid by rememberSaveable { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var sorting by remember { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf("Recent") }
    val apps = rememberSteamLibrary(context, state.library)
    val demos = rememberSteamLibrary(context, state.library, enabled = tab == 4, filterType = "demo")
    val installed = remember(apps) { apps.count { it.containerName == "Steam · Installed" } }
    val user = rememberSteamUserSnapshot(context)
    val profile = user.profile
    val owned = user.owned
    val achievementCache = user.achievements
    LaunchedEffect(state.signedIn, state.libraryLoading) {
        if (state.signedIn && !state.libraryLoading && !state.gameActive) SteamUserData.refreshProfile(context)
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)).imePadding()) {
        SteamTopBar("Steam", uiStr(R.string.steam_ui_subtitle), onBack = onBack) {
            IconButton(enabled = !state.gameActive && !state.downloading, onClick = {
                if (state.signedIn) { SteamSession.retryLibrary(); SteamUserData.refreshProfile(context, true) } else SteamSession.reconnect()
            }) { Icon(TablerIcons.Refresh, uiStr(R.string.steam_ui_refresh)) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(TablerIcons.DotsVertical, uiStr(R.string.steam_ui_options)) }
                SteamMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(uiStr(R.string.steam_friends)) }, leadingIcon = { Icon(TablerIcons.Users, null) }, onClick = { menu = false; context.startActivity(android.content.Intent(context, SteamFriendsActivity::class.java)) })
                    DropdownMenuItem(text = { Text(uiStr(R.string.settings)) }, leadingIcon = { Icon(TablerIcons.Settings, null) }, onClick = { menu = false; context.startActivity(android.content.Intent(context, StoreSettingsActivity::class.java)) })
                    DropdownMenuItem(text = { Text(uiStr(R.string.steam_ui_sign_out)) }, leadingIcon = { Icon(TablerIcons.Logout, null) }, onClick = { menu = false; signOut() })
                }
            }
        }
        SteamTabRow(listOf(
            SteamTabItem(0, uiStr(R.string.steam_ui_games), TablerIcons.DeviceGamepad2), SteamTabItem(4, uiStr(R.string.steam_ui_demos), TablerIcons.DeviceGamepad),
            SteamTabItem(1, uiStr(R.string.steam_ui_downloads), TablerIcons.Download), SteamTabItem(2, uiStr(R.string.steam_ui_installed), TablerIcons.CircleCheck),
            SteamTabItem(3, uiStr(R.string.steam_ui_achievements), TablerIcons.Trophy)), tab) { tab = it }
        Spacer(Modifier.height(8.dp))
        RevealSection(state.restoring || (!state.signedIn && state.savedAccount)) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(steamStatusText(state.status), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                if (state.restoring) com.winlator.cmod.ui.theme.WinZSpinner(18.dp, 2.dp)
                else if (!state.gameActive) TextButton(onClick = { SteamSession.reconnect() }) { Text(uiStr(R.string.steam_ui_retry)) }
            }
        }
        RevealSection(state.signedIn && state.libraryLoading) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(uiStr(R.string.steam_ui_loading_library, state.libraryProgress), style = MaterialTheme.typography.labelMedium)
                SteamProgress(state.libraryProgress / 100f, Modifier.fillMaxWidth())
            }
        }
        if (!(state.signedIn && state.libraryLoading) && state.libraryError.isNotEmpty()) Text(state.libraryError, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        val revealed = remember { mutableSetOf<String>() }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(tab, transitionSpec = { fadeIn(WinZMotion.enter()) togetherWith fadeOut(WinZMotion.exit()) }, label = "steamTab") { currentTab ->
        if (currentTab == 1) {
            SteamDownloadsScreen(state, openGame, Modifier.fillMaxSize())
        } else if (currentTab == 3) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                item { SteamAccountCard(profile, state.signedIn, apps.size, installed) }
                item { SteamSectionTitle(uiStr(R.string.steam_ui_achievements), Modifier.padding(top = 0.dp)) }
                item { Text(uiStr(R.string.steam_ui_achievements_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 3.dp)) }
                itemsIndexed(achievementCache.keys.toList()) { index, id ->
                    val list = achievementCache[id].orEmpty()
                    RevealOnEnter("a$id", index, revealed, Modifier.fillMaxWidth()) {
                        SteamCard(Modifier.fillMaxWidth(), onClick = { if (File(context.filesDir, "steam-library/$id.json").isFile) openGame(id) else SteamSheets.game(context, id) }) {
                            SteamRow(TablerIcons.Trophy, apps.find { SteamLibrary.appId(it.shortcutPath) == id }?.name ?: uiStr(R.string.steam_ui_game),
                                uiStr(R.string.steam_ui_ach_count, list.count { it.unlocked }, list.size))
                        }
                    }
                }
            }
        } else {
            val sourceApps = if (currentTab == 4) demos else apps
            val filtered = remember(sourceApps, query, currentTab) { sourceApps.filter { it.name.contains(query, true) && (currentTab != 2 || it.containerName == "Steam · Installed") } }
            val visible = remember(filtered, owned, sort) { sortSteamLibrary(filtered, owned, sort) }
            LazyVerticalGrid(if (grid) GridCells.Adaptive(160.dp) else GridCells.Fixed(1), Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item(key = "account", span = { GridItemSpan(maxLineSpan) }) { SteamAccountCard(profile, state.signedIn, apps.size, installed) }
                item(key = "controls", span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (currentTab == 2) uiStr(R.string.steam_ui_installed) else if (currentTab == 4) uiStr(R.string.steam_ui_demos) else uiStr(R.string.steam_ui_library), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                            IconButton(onClick = { search = !search }) { Icon(TablerIcons.Search, uiStr(R.string.steam_ui_search_games)) }
                            Box {
                                TextButton(onClick = { sorting = true }) { Icon(TablerIcons.ArrowsSort, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(steamSortLabel(sort)); Icon(TablerIcons.ChevronDown, null, Modifier.size(18.dp)) }
                                SteamMenu(sorting, { sorting = false }) { listOf("Recent", "Name", "Playtime").forEach { title -> SteamChoiceItem(steamSortLabel(title), title == sort) { sort = title; sorting = false } } }
                            }
                            IconButton(onClick = { grid = !grid }) { Icon(if (grid) TablerIcons.LayoutList else TablerIcons.LayoutGrid, uiStr(R.string.steam_ui_layout)) }
                        }
                        RevealSection(search) { SteamSearchField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) }
                    }
                }
                itemsIndexed(visible, key = { _, it -> it.id }) { index, app ->
                    val id = SteamLibrary.appId(app.shortcutPath)!!
                    val isInstalled = app.containerName == "Steam · Installed"
                    RevealOnEnter("g$currentTab-$id", index, revealed, Modifier.fillMaxWidth()) { SteamCard(Modifier.fillMaxWidth(), onClick = { if (isInstalled) openGame(id) else SteamSheets.game(context, id) }) {
                        SteamArt("https://shared.steamstatic.com/store_item_assets/steam/apps/$id/header.jpg", Modifier.fillMaxWidth().padding(6.dp).aspectRatio(460f / 215f).clip(WinZShapes.Small))
                        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(app.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                val minutes = owned[id]?.optLong("playtimeForever")
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (minutes != null) Icon(TablerIcons.Clock, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(if (minutes != null) "${"%.1f".format(minutes / 60.0)} h" else if (isInstalled) uiStr(R.string.steam_ui_installed_game) else uiStr(R.string.steam_ui_not_installed),
                                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            IconButton(onClick = { if (isInstalled) openGame(id) else SteamSheets.game(context, id) }) { Icon(if (isInstalled) TablerIcons.PlayerPlay else TablerIcons.Download, if (isInstalled) uiStr(R.string.steam_ui_game_details) else uiStr(R.string.steam_ui_download_game), tint = if (isInstalled) com.winlator.cmod.ui.theme.controlAccentColor() else MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    } }
                }
                if (visible.isEmpty() && !state.libraryLoading) item(span = { GridItemSpan(maxLineSpan) }) { Text(if (currentTab == 2) uiStr(R.string.steam_ui_no_installed) else if (currentTab == 4) uiStr(R.string.steam_ui_no_demos) else uiStr(R.string.steam_ui_no_games), Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
            }
        }
    }
}

@Composable internal fun SteamArt(url: String, modifier: Modifier) {
    AndroidView(factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.CENTER_CROP } }, update = { if (it.tag != url) { it.tag = url; Glide.with(it).load(url).error(R.drawable.icon_wine).into(it) } }, onRelease = { Glide.with(it).clear(it) }, modifier = modifier)
}

@Composable private fun SteamDownloadsScreen(state: SteamUiSnapshot, openGame: (Int) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    var confirmRemove by remember(state.id) { mutableStateOf(false) }
    val installed = state.id > 0 && File(context.filesDir, "steam-library/${state.id}.json").isFile
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        item { SteamSectionTitle(uiStr(R.string.steam_ui_downloads), Modifier.padding(top = 0.dp)) }
        if (state.id > 0) item {
            SteamCard(Modifier.fillMaxWidth(), shape = WinZShapes.Large) {
                SteamArt("https://shared.steamstatic.com/store_item_assets/steam/apps/${state.id}/header.jpg", Modifier.fillMaxWidth().aspectRatio(460f / 215f))
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(state.name, style = MaterialTheme.typography.titleLarge)
                    Text(if (state.downloading) uiStr(R.string.steam_ui_downloading) else if (installed) uiStr(R.string.steam_ui_installed_game) else uiStr(R.string.steam_ui_paused), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.downloading && state.total > 0)
                        SteamProgress(state.done.toFloat() / state.total, Modifier.fillMaxWidth())
                    if (state.downloading) Text(downloadTimingText(state), style = MaterialTheme.typography.bodySmall)
                    Text("${state.done / 1048576} / ${state.total / 1048576} MB", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.downloading) {
                            SteamOutlineButton(uiStr(R.string.steam_ui_pause), icon = TablerIcons.PlayerPause) { SteamSession.cancel() }
                        } else if (installed) {
                            SteamAccentButton(uiStr(R.string.steam_ui_view_game), icon = TablerIcons.DeviceGamepad2) { openGame(state.id) }
                        } else {
                            SteamAccentButton(uiStr(R.string.steam_ui_resume), enabled = state.signedIn && !state.gameActive, icon = TablerIcons.Download) { SteamSession.install(state.id) }
                            SteamOutlineButton(uiStr(R.string.steam_download_cancel_delete), enabled = !state.gameActive, icon = TablerIcons.Trash) { confirmRemove = true }
                        }
                    }
                    if (state.status.startsWith("Download failed"))
                        Text(steamStatusText(state.status), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        } else item {
            SteamCard(Modifier.fillMaxWidth()) { SteamRow(TablerIcons.CircleCheck, uiStr(R.string.steam_ui_no_downloads), uiStr(R.string.steam_ui_choose_download), trailing = null) }
        }
    }
    if (confirmRemove && state.id > 0 && !installed && !state.downloading) {
        com.winlator.cmod.ui.theme.ThemedDialog(onDismissRequest = { confirmRemove = false }) {
            com.winlator.cmod.ui.theme.ThemedDialogTitle(uiStr(R.string.steam_download_cancel_delete))
            Text(uiStr(R.string.steam_download_cancel_confirm), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            SteamDialogButtons {
                SteamSecondaryButton(uiStr(R.string.steam_ui_cancel)) { confirmRemove = false }
                SteamPrimaryButton(uiStr(R.string.steam_download_cancel_delete), destructive = true) {
                    confirmRemove = false
                    SteamSession.discardPaused(state.id)
                }
            }
        }
    }
}

@Composable private fun SteamAccountCard(profile: JSONObject, connected: Boolean, games: Int, installed: Int) {
    SteamCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val hash = profile.optString("avatarHash")
            if (hash.matches(Regex("[a-fA-F0-9]{40}")) && hash.any { it != '0' }) SteamArt("https://avatars.akamai.steamstatic.com/${hash}_medium.jpg", Modifier.size(40.dp).clip(WinZShapes.Small))
            else SteamIconTile(TablerIcons.BrandSteam)
            Column(Modifier.weight(1f)) {
                Text(profile.optString("playerName").ifBlank { uiStr(R.string.steam_ui_profile) }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(6.dp).background(if (connected) androidx.compose.ui.graphics.Color(0xFF43A047) else MaterialTheme.colorScheme.onSurfaceVariant, androidx.compose.foundation.shape.CircleShape))
                    Text(if (connected) uiStr(R.string.steam_ui_connected) else uiStr(R.string.steam_ui_offline), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            listOf(games to uiStr(R.string.steam_ui_games), installed to uiStr(R.string.steam_ui_installed)).forEach { (count, label) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(count.toString(), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
    }
}
