package com.winlator.cmod.ui.library

import com.winlator.cmod.ui.theme.WinZProgressBar
import com.winlator.cmod.ui.theme.TablerIcons
import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.steam.SteamChoiceItem
import com.winlator.cmod.steam.SteamMenu
import com.winlator.cmod.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface SteamDetailCallbacks {
    fun onPlay()
    fun onConfigure()
    fun onArguments()
    fun onSaves()
    fun onFavorite(favorite: Boolean)
    fun onRemove()
    fun onHome()
    fun onSteamMode(mode: String)
    fun onSteamExecutable()
    // The page has finished closing (after its exit animation): leave to the screen it was opened from.
    fun onClose()
}

// Steam game page used by SteamActivity. It is WinLite's own game page (GameDetailComposeHost) in every detail:
// the same scrims, plates, dark-glass stat chips on the picture, accent Play button and action tiles/rows,
// built from the same pieces. Installed Steam games also exist as ordinary shortcuts, so the Library lists them too.
object SteamDetailHost {
    @JvmStatic
    fun create(context: Context, title: String, artwork: String?, id: Int, callbacks: SteamDetailCallbacks): ComposeView =
        ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            // The overlay theme has no opaque root surface: the Steam manager stays visible under the page while it
            // slides in / out, exactly as WinLite's tabs do under a game page (SteamPageFrame draws the page's own background).
            setContent { WinZOverlayTheme { SteamPageFrame(callbacks::onClose) { CompositionLocalProvider(LocalArtworkMotion provides rememberArtworkMotionEnabled()) {
                val revision = com.winlator.cmod.steam.SteamGames.version
                // The shortcut is read off the main thread; until then the page does not know which of WinLite's
                // own pictures (banner / cover of the game) it shows, so it does not draw a placeholder first.
                val found by produceState<Pair<Boolean, Shortcut?>>(false to null, id, revision) {
                    value = true to withContext(Dispatchers.IO) { com.winlator.cmod.steam.SteamGames.shortcut(context, id) }
                }
                SteamDetailScreen(title, "Steam", artwork, null, context.getSharedPreferences("steam-settings", 0).getBoolean("favorite-$id", false), found.second, callbacks, id, found.first)
            } } } }
        }
}

// The frame of the page: WinLite's detail transition (ShellDetails.kt - fade + a slide of 1/10 of the width, 220 / 160 ms;
// only a fade in landscape) and its back handling. Back (system button, gesture or the page's back plate) plays the exit
// first and only then calls onClosed, which returns to the Steam manager the page was opened from.
@Composable
private fun SteamPageFrame(onClosed: () -> Unit, content: @Composable () -> Unit) {
    val transition = remember { MutableTransitionState(false).apply { targetState = true } }
    androidx.activity.compose.BackHandler(enabled = transition.targetState) { transition.targetState = false }
    LaunchedEffect(transition.currentState, transition.isIdle) {
        if (!transition.targetState && transition.isIdle && !transition.currentState) onClosed()
    }
    // System bars as WinlatorTheme sets them for a full-screen host (this frame does not use that theme).
    val activity = LocalContext.current as? android.app.Activity
    ApplySystemBarsPolicy(activity?.window)
    val whiteTheme = WinlatorThemeManager.currentTheme() == WinlatorThemeType.WHITE
    DisposableEffect(activity, whiteTheme) {
        activity?.window?.let { window ->
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = whiteTheme
                isAppearanceLightNavigationBars = whiteTheme
            }
        }
        onDispose { }
    }
    val landscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val enter = if (landscape) fadeIn(tween(180, easing = FastOutSlowInEasing))
        else fadeIn(tween(220, easing = LinearOutSlowInEasing)) + slideInHorizontally(tween(220, easing = LinearOutSlowInEasing)) { it / 10 }
    val exit = if (landscape) fadeOut(tween(180, easing = FastOutSlowInEasing))
        else fadeOut(tween(160, easing = FastOutLinearInEasing)) + slideOutHorizontally(tween(160, easing = FastOutLinearInEasing)) { it / 10 }
    AnimatedVisibility(visibleState = transition, enter = enter, exit = exit) {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).pointerInput(Unit) { }
        ) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                // Portrait: the status bar stays visible, the page starts below it (0 while the bar is hidden in landscape).
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars), propagateMinConstraints = true) { content() }
            }
        }
    }
}

private val LocalSteamInfoId = compositionLocalOf<Int?> { null }
private val LocalSteamDetail = compositionLocalOf { false }
private val LocalSteamReady = compositionLocalOf { true }
private val LocalSteamExecutable = compositionLocalOf { "" }
private val LocalSteamMode = compositionLocalOf { "RealSteam" }

@Composable
private fun SteamDetailScreen(title: String, subtitle: String, artworkPath: String?, fallback: Bitmap?, initialFavorite: Boolean, shortcut: Shortcut?, callbacks: SteamDetailCallbacks, steamId: Int? = null, shortcutReady: Boolean = true) {
    val context = LocalContext.current.applicationContext
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    // The picture is the one WinLite shows for this game (the Library tile / game page): the user's own background,
    // the downloaded banner, the user's own cover; then Steam's banner (cache / CDN); then the downloaded cover and
    // the offline placeholder. The shortcut's name decides the file (Winlator/banners|covers/NAME[.user].png).
    val baseName = remember(shortcut) { shortcut?.let { com.winlator.cmod.core.FileUtils.getBasename(it.file.path) } }
    val steamKey = LibraryImageCache.keyFor(artworkPath)
    val art by produceState<SteamArt?>(
        if (shortcutReady && baseName == null) LibraryImageCache.peek(steamKey)?.let { SteamArt(it, steamKey, false, false) } else null,
        baseName, shortcutReady, artworkPath, fallback
    ) {
        if (!shortcutReady) return@produceState
        value = withContext(Dispatchers.IO) {
            fun usable(f: File) = f.isFile && f.length() > 0
            fun load(f: File?, coverOnly: Boolean, user: Boolean): SteamArt? {
                if (f == null || !usable(f)) return null
                val bitmap = LibraryImageCache.load(f.path) ?: return null
                return SteamArt(bitmap, LibraryImageCache.keyFor(f.path), coverOnly, user)
            }
            val root = android.os.Environment.getExternalStorageDirectory()
            val banners = File(root, "Winlator/banners")
            val covers = File(root, "Winlator/covers")
            val own = baseName?.let { name ->
                load(File(banners, "$name.user.png"), false, false)
                    ?: load(File(banners, "$name.png"), false, false)
                    ?: load(File(covers, "$name.user.png"), true, true)
            }
            own ?: (LibraryImageCache.load(artworkPath) ?: fallback ?: steamId?.let { id ->
                runCatching {
                    val request = com.bumptech.glide.Glide.with(context).downloadOnly()
                        .load("https://shared.steamstatic.com/store_item_assets/steam/apps/$id/header.jpg").submit()
                    try { android.graphics.BitmapFactory.decodeFile(request.get(20, java.util.concurrent.TimeUnit.SECONDS).path) }
                    finally { com.bumptech.glide.Glide.with(context).clear(request) }
                }.getOrNull()
            })?.let { SteamArt(it, steamKey, false, false) }
                ?: baseName?.let { name -> load(File(covers, "$name.png"), true, false) ?: load(File(covers, "$name.gen.png"), true, false) }
        }
    }
    var resumeSerial by remember { mutableIntStateOf(0) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumeSerial++
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val steamDataRevision = if (steamId != null) com.winlator.cmod.steam.SteamUserData.contentRevision.intValue else 0
    // Stats are remembered per game for the process: reopening the page shows them from the first frame, and what
    // is read again only replaces the values (the chips themselves are always there - see steamStatEntries).
    val stats by produceState(steamId?.let { steamStatsCache[it] } ?: GameStats(), shortcut?.file?.path, steamId, steamDataRevision, resumeSerial, shortcutReady) {
        if (!shortcutReady) return@produceState
        val loaded = withContext(Dispatchers.IO) {
            shortcut?.let { GameStatsLoader.load(context, it) } ?: GameStats()
        }
        val steamPlay = withContext(Dispatchers.IO) {
            steamId?.let { com.winlator.cmod.steam.SteamUserData.game(context, it) }
        }
        // loaded = true even for a game that was never run: the chips are always drawn (zeros / dashes), as on WinLite's page.
        val basic = loaded.copy(
            playtimeMillis = maxOf(loaded.playtimeMillis,
                steamPlay?.optLong("playtimeForever")?.coerceAtLeast(0L)?.times(60000L) ?: 0L),
            lastPlayedMillis = maxOf(loaded.lastPlayedMillis,
                steamPlay?.optLong("rtimeLastPlayed")?.coerceAtLeast(0L)?.times(1000L) ?: 0L),
            loaded = true
        )
        // Keep the size already shown while the rest is read again (no dash flicker on every resume).
        value = basic.copy(sizeText = value.sizeText)
        value = withContext(Dispatchers.IO) {
            val cached = shortcut?.let { GameStatsLoader.cachedSize(context, it) }
            val existingSize = shortcut?.let {
                if (cached?.fresh == true) cached.text else GameStatsLoader.sizeText(context, it)
            }
            basic.copy(sizeText = existingSize ?: steamId?.let { installedSteamSizeText(context, it) })
        }
        steamId?.let { steamStatsCache[it] = value }
    }
    var favorite by remember(initialFavorite) { mutableStateOf(initialFavorite) }
    val toggle = { favorite = !favorite; callbacks.onFavorite(favorite) }
    val configured = true
    val executable = shortcut?.getExtra("steamExecutable").orEmpty()
    val items = steamDetailItems(callbacks, steamId != null)
    androidx.compose.runtime.CompositionLocalProvider(LocalSteamExecutable provides executable, LocalSteamInfoId provides steamId, LocalSteamDetail provides (steamId != null), LocalSteamReady provides configured, LocalSteamMode provides (steamId?.let { com.winlator.cmod.steam.SteamSettings.mode(context, it) } ?: "RealSteam")) {
    if (landscape) LandscapeDetail(title, subtitle, art, favorite, stats, items, callbacks, toggle)
    else PortraitDetail(title, subtitle, art, favorite, stats, items, callbacks, toggle)
    }
}

// The same five actions as WinLite's game page (caption under the icon in landscape / row text in portrait).
@Composable
private fun steamDetailItems(callbacks: SteamDetailCallbacks, steam: Boolean): List<DetailActionItem> {
    val configure = stringResource(R.string.ui_configure)
    val container = stringResource(R.string.ui_container)
    val enterContainer = stringResource(R.string.ui_enter_container)
    val home = stringResource(R.string.ui_home)
    val addToHome = stringResource(R.string.ui_add_to_home)
    val saves = savesLabel()
    val remove = if (steam) stringResource(R.string.steam_ui_uninstall) else stringResource(R.string.remove)
    return listOf(
        DetailActionItem(TablerIcons.Settings, configure, configure, callbacks::onConfigure),
        DetailActionItem(TablerIcons.Server, container, enterContainer, callbacks::onArguments),
        DetailActionItem(TablerIcons.CloudUpload, saves, saves, callbacks::onSaves),
        DetailActionItem(TablerIcons.Home, home, addToHome, callbacks::onHome),
        DetailActionItem(TablerIcons.Trash, remove, remove, callbacks::onRemove, destructive = true)
    )
}

private val steamStatsCache = java.util.concurrent.ConcurrentHashMap<Int, GameStats>()

// The stat chips of the page. They are drawn from the first frame (WinLite's chips are always there too), so nothing
// pops in or shoves the title / buttons: until the numbers are read the values are dashes.
@Composable
private fun steamStatEntries(stats: GameStats): List<StatEntry> {
    val entries = rememberStatEntries(stats, always = true)
    return if (stats.loaded) entries else remember(entries) { entries.map { StatEntry(it.icon, it.label, "\u2014") } }
}

// A resolved picture of the page: where it came from decides how DetailArtwork draws it (WinLite's own rules).
private class SteamArt(val bitmap: Bitmap, val key: String?, val coverOnly: Boolean, val coverIsUser: Boolean)

// Landscape: the picture stays open, the controls sit in the corners (layout of WinLite's landscape game page).
//   top-left     : back plate, then the title under it      top-right : favorite plate
//   bottom-right : one compact panel - Play (+ exe / mode pickers) and a row of icon buttons with captions
//   bottom-left  : the stat chips, then Game info / Achievements; where the picture and the panel leave no
//                  room (small phone) they move into the panel.
@Composable
private fun LandscapeDetail(title: String, subtitle: String, artwork: SteamArt?, favorite: Boolean, stats: GameStats, items: List<DetailActionItem>, callbacks: SteamDetailCallbacks, toggle: () -> Unit) {
    val activity = LocalContext.current as? androidx.activity.ComponentActivity
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val artAlpha by animateFloatAsState(if (artwork != null) 1f else 0f, tween(420), label = "steamDetailArt")
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = artAlpha }) { if (artwork != null) DetailArtwork(artwork.bitmap, artwork.key, artwork.coverOnly, artwork.coverIsUser, landscape = true) }
        Box(Modifier.align(Alignment.TopStart).fillMaxWidth().height(132.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(.66f), Color.Transparent))))
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(210.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(.60f)))))

        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))) {
            val panelWidth = (maxWidth * 0.42f).coerceIn(320.dp, 400.dp)
            val iconCell = (panelWidth - 28.dp - 8.dp * (items.size - 1)) / items.size
            val compactCaptions = iconCell < 64.dp
            val infoId = LocalSteamInfoId.current
            // The user's own portrait cover is a poster at the bottom-left (as on WinLite's page); the chips sit beside it.
            val posterRatio: Float? = if (artwork != null && artwork.coverIsUser && !usesAsBackground(artwork.bitmap, artwork.coverOnly, artwork.coverIsUser)) {
                (artwork.bitmap.width.toFloat() / artwork.bitmap.height.toFloat()).coerceIn(0.5f, 1.29f)
            } else null
            if (posterRatio != null && artwork != null) DetailPoster(artwork.bitmap, posterRatio, Modifier.align(Alignment.BottomStart))
            val posterWidth = if (posterRatio != null) (maxHeight - (POSTER_TOP + 24.dp)).coerceAtLeast(0.dp) * posterRatio else 0.dp
            // The poster is known only once the picture is decoded: the column beside it slides over instead of jumping.
            val leftStartTarget = 34.dp + if (posterRatio != null) posterWidth + 14.dp else 0.dp
            val leftStart by androidx.compose.animation.core.animateDpAsState(leftStartTarget, tween(320), label = "steamLeftStart")
            val leftWidth = maxWidth - leftStart - (panelWidth + 34.dp + 16.dp)
            val inPanel = maxWidth - leftStartTarget - (panelWidth + 34.dp + 16.dp) < 190.dp
            val panelMax = (maxHeight - 132.dp - 24.dp).coerceAtLeast(120.dp)

            if (!inPanel) {
                Column(
                    Modifier.align(Alignment.BottomStart).padding(start = leftStart, bottom = 24.dp).width(leftWidth)
                        .heightIn(max = panelMax).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatChipRow(steamStatEntries(stats), Modifier.fillMaxWidth())
                    infoId?.let { com.winlator.cmod.steam.SteamGameInfoPanel(it, landscape = true, onArtwork = true) }
                }
            }

            ArtPlateButton(onClick = { activity?.onBackPressedDispatcher?.onBackPressed() }, modifier = Modifier.align(Alignment.TopStart).padding(start = 18.dp, top = 16.dp)) {
                Icon(TablerIcons.ArrowLeft, stringResource(R.string.ui_back), modifier = Modifier.size(24.dp))
            }
            Column(Modifier.align(Alignment.TopStart).padding(start = 74.dp, end = 74.dp, top = 15.dp)) {
                Text(title, color = Color.White,
                    style = MaterialTheme.typography.headlineLarge.copy(shadow = Shadow(OnArtwork.TextShadow, Offset(0f, 2f), 6f)),
                    fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = OnArtwork.TextSecondary,
                    style = MaterialTheme.typography.bodyLarge.copy(shadow = Shadow(OnArtwork.TextShadow, Offset(0f, 1f), 4f)))
            }
            FavoriteButton(favorite, toggle, Modifier.align(Alignment.TopEnd).padding(end = 18.dp, top = 16.dp))

            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 34.dp, bottom = 24.dp).width(panelWidth).heightIn(max = panelMax),
                shape = WinZShapes.Large, color = OnArtwork.PanelFill, border = BorderStroke(1.dp, OnArtwork.PlateBorder)
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (inPanel) StatChipRow(steamStatEntries(stats), Modifier.fillMaxWidth())
                    PlayButton(callbacks::onPlay, { mode -> callbacks.onSteamMode(mode) }, { callbacks.onSteamExecutable() }, onArtwork = true, height = 58.dp)
                    if (inPanel) infoId?.let { com.winlator.cmod.steam.SteamGameInfoPanel(it, landscape = true, onArtwork = true) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items.forEach { DetailIconAction(it, Modifier.weight(1f), compactCaptions) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PortraitDetail(title: String, subtitle: String, artwork: SteamArt?, favorite: Boolean, stats: GameStats, items: List<DetailActionItem>, callbacks: SteamDetailCallbacks, toggle: () -> Unit) {
    val activity = LocalContext.current as? androidx.activity.ComponentActivity
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // The stat chips sit under the title, over the picture, exactly as on WinLite's page.
        Box(Modifier.fillMaxWidth().aspectRatio(1.12f).background(MaterialTheme.colorScheme.surface)) {
            if (artwork != null) DetailArtwork(artwork.bitmap, artwork.key, artwork.coverOnly, artwork.coverIsUser, landscape = false, bottomReserve = 150.dp)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(.38f), Color.Transparent, Color.Black.copy(.88f)))))
            ArtPlateButton(onClick = { activity?.onBackPressedDispatcher?.onBackPressed() }, modifier = Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 14.dp)) {
                Icon(TablerIcons.ArrowLeft, stringResource(R.string.ui_back), modifier = Modifier.size(24.dp))
            }
            FavoriteButton(favorite, toggle, Modifier.align(Alignment.TopEnd).padding(end = 14.dp, top = 14.dp))
            Column(Modifier.align(Alignment.BottomStart).padding(22.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = OnArtwork.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                StatChipRow(steamStatEntries(stats), Modifier.fillMaxWidth())
            }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PlayButton(callbacks::onPlay, { mode -> callbacks.onSteamMode(mode) }, { callbacks.onSteamExecutable() }, onArtwork = false, height = 54.dp)
            LocalSteamInfoId.current?.let { com.winlator.cmod.steam.SteamGameInfoPanel(it) }
            items.forEach { DetailAction(it, Modifier.fillMaxWidth()) }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun savesLabel(): String = if (LocalSteamDetail.current && LocalSteamMode.current == "RealSteam")
    stringResource(R.string.steam_cloud_saves) else stringResource(R.string.ui_saves)

// Play = WinLite's Play button (accent fill, white content, 14dp radius). Steam adds two square buttons next to it:
// choose the executable and choose how to launch (Steam client / offline), on the same plates as the rest of the page.
@Composable
private fun PlayButton(click: () -> Unit, chooseMode: (String) -> Unit, chooseExecutable: () -> Unit, onArtwork: Boolean, height: Dp) {
    val steam = LocalSteamDetail.current
    val mode = LocalSteamMode.current
    val modeProgress = LocalSteamInfoId.current?.let { com.winlator.cmod.steam.SteamLaunch.modeStatus(it) }
    val modeBusy = modeProgress?.busy == true
    val accent = controlAccentColor()
    var expanded by remember { mutableStateOf(false) }
    // The mode-switch progress / error line appears under the button: the column grows smoothly instead of pushing what is below.
    Column(Modifier.fillMaxWidth().animateContentSize(tween(240)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                click, enabled = LocalSteamReady.current && !modeBusy,
                modifier = Modifier.weight(1f).height(height), shape = WinZShapes.Medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent, contentColor = Color.White,
                    disabledContainerColor = accent.copy(alpha = .38f), disabledContentColor = Color.White.copy(alpha = .62f)
                )
            ) {
                Icon(TablerIcons.PlayerPlay, null, Modifier.size(if (height >= 58.dp) 30.dp else 24.dp)); Spacer(Modifier.width(8.dp))
                Text(if (!LocalSteamReady.current) stringResource(R.string.steam_ui_configure_first)
                    else if (steam) stringResource(if (mode == "RealSteam") R.string.steam_ui_client else R.string.steam_ui_offline)
                    else stringResource(R.string.ui_play), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (steam) SteamSquareButton(TablerIcons.FolderOpen, stringResource(R.string.steam_ui_choose_exe), !modeBusy, onArtwork, chooseExecutable)
            if (steam) Box {
                SteamSquareButton(TablerIcons.ChevronDown, stringResource(R.string.steam_ui_launch), !com.winlator.cmod.steam.SteamSession.gameActive && !modeBusy, onArtwork) { expanded = true }
                SteamMenu(expanded, { expanded = false }) {
                    listOf("RealSteam" to R.string.steam_ui_client, "Goldberg" to R.string.steam_ui_offline).forEach { (value, label) ->
                        SteamChoiceItem(stringResource(label), mode == value) { expanded = false; chooseMode(value) }
                    }
                }
            }
        }
        if (modeProgress != null && modeBusy) {
            WinZProgressBar(if (modeProgress.total > 0) (modeProgress.done.toFloat() / modeProgress.total).coerceIn(0f, 1f) else null)
            Text(when (modeProgress.stage) {
                "download" -> stringResource(R.string.steam_mode_downloading)
                "verify" -> stringResource(R.string.steam_mode_verifying)
                "extract" -> stringResource(R.string.steam_mode_extracting)
                "client" -> stringResource(R.string.steam_mode_client)
                else -> stringResource(R.string.steam_mode_preparing)
            }, style = MaterialTheme.typography.labelSmall, color = if (onArtwork) OnArtwork.TextSecondary else MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (modeProgress?.error?.isNotBlank() == true) {
            Text(modeProgress.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// Square icon button beside Play: dark glass on the artwork panel, the theme surface in the portrait list.
@Composable
private fun SteamSquareButton(icon: ImageVector, description: String, enabled: Boolean, onArtwork: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp), shape = WinZShapes.Medium,
        color = if (onArtwork) OnArtwork.PlateFill else MaterialTheme.colorScheme.surface,
        contentColor = if (onArtwork) Color.White else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, if (onArtwork) OnArtwork.PlateBorder else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, description, Modifier.size(22.dp), tint = LocalContentColor.current.copy(alpha = if (enabled) 1f else .38f))
        }
    }
}

private fun installedSteamSizeText(context: Context, id: Int): String? = runCatching {
    val record = File(context.filesDir, "steam-library/$id.json")
    if (!record.isFile) return@runCatching null
    val dir = com.winlator.cmod.steam.SteamStorage.directory(context, JSONObject(record.readText()))
    if (!dir.isDirectory || Files.isSymbolicLink(dir.toPath())) return@runCatching null
    val deadline = android.os.SystemClock.uptimeMillis() + 3000L
    var size = 0L
    var examined = 0
    for (file in dir.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }) {
        if (++examined > 150000 || android.os.SystemClock.uptimeMillis() > deadline) break
        if (file.isFile && !Files.isSymbolicLink(file.toPath())) size += file.length()
    }
    if (size > 0) formatBinarySize(size) else null
}.getOrNull()
