package com.winlator.cmod.ui.library

import com.winlator.cmod.R
import androidx.compose.ui.res.stringResource
import com.winlator.cmod.ui.theme.OnArtwork
import com.winlator.cmod.ui.theme.tabularNumbers
import androidx.compose.material3.LocalTextStyle
import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.cmod.MainActivity
import com.winlator.cmod.ui.KeepLandscapeChromeHidden
import com.winlator.cmod.ui.theme.controlAccentColor
import com.winlator.cmod.ui.theme.destructiveColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import com.winlator.cmod.ui.theme.WinZShapes
import com.winlator.cmod.ui.theme.hairlineColor
import kotlin.math.roundToInt

interface GameDetailCallbacks {
    fun onBack()
    fun onPlay()
    fun onConfigure()
    fun onArguments()
    fun onSaves()
    fun onFavorite(favorite: Boolean)
    // Both removals ask for confirmation themselves (ThemedAlertHost, like every other dialog).
    fun onRemove()
    // Home-screen icon: pin it (the launcher asks), or switch the pinned one off.
    fun onPinHome()
    fun onUnpinHome()
}

// The game detail screen. Opened by MainShell as a detail entry through GameDetailRoute
// (GameDetailRoute.kt); onBack pops it.
@Composable
internal fun GameDetailScreen(title: String, subtitle: String, bannerPath: String?, coverPath: String?, coverIsUser: Boolean, fallback: Bitmap?, initialFavorite: Boolean, stats: GameStats, homePinned: Boolean, callbacks: GameDetailCallbacks) {
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    val activity = LocalContext.current as? MainActivity

    // Fullscreen is kept by the shell's tabs; the native Toolbar this screen used in portrait
    // (title + up arrow) is gone — portrait now has the same on-artwork back button as landscape.
    if (landscape) KeepLandscapeChromeHidden(activity)

    // The Library pager/tiles have usually decoded this banner/cover already (LibraryImageCache),
    // so start from it instead of flashing the exe icon first.
    // Banner when there is one, else the (portrait) cover. There is no icon fallback here on
    // purpose: an exe icon cropped over the whole header looked broken (see ArtworkViews.kt).
    val artworkPath = bannerPath ?: coverPath
    val coverOnly = bannerPath == null && coverPath != null
    val artworkKey = remember(artworkPath) { LibraryImageCache.keyFor(artworkPath) }
    val artwork by produceState<Bitmap?>(LibraryImageCache.peek(artworkKey), artworkKey) {
        value = withContext(Dispatchers.IO) { LibraryImageCache.load(artworkPath) }
    }
    var favorite by remember(initialFavorite) { mutableStateOf(initialFavorite) }
    val toggle = {
        favorite = !favorite
        callbacks.onFavorite(favorite)
    }
    val items = listOf(
        DetailActionItem(Icons.Outlined.Settings, stringResource(R.string.ui_configure), stringResource(R.string.ui_configure), callbacks::onConfigure),
        DetailActionItem(Icons.Outlined.Dns, stringResource(R.string.ui_container), stringResource(R.string.ui_enter_container), callbacks::onArguments),
        DetailActionItem(Icons.Outlined.Backup, stringResource(R.string.ui_backup), stringResource(R.string.ui_backup), callbacks::onSaves),
        DetailActionItem(
            Icons.Outlined.Home,
            if (homePinned) stringResource(R.string.ui_unpin) else stringResource(R.string.ui_home),
            if (homePinned) stringResource(R.string.ui_remove_from_home) else stringResource(R.string.ui_add_to_home),
            // The route asks "Remove ...?" with the app's standard confirm dialog before unpinning.
            if (homePinned) callbacks::onUnpinHome else callbacks::onPinHome,
            accent = homePinned
        ),
        DetailActionItem(Icons.Outlined.DeleteOutline, stringResource(R.string.remove), stringResource(R.string.remove), callbacks::onRemove, destructive = true)
    )
    if (landscape) LandscapeDetail(title, subtitle, artwork, artworkKey, coverOnly, coverIsUser, favorite, stats, items, callbacks, toggle)
    else PortraitDetail(title, subtitle, artwork, artworkKey, coverOnly, coverIsUser, fallback, favorite, stats, items, callbacks, toggle)
}

// Landscape game page: the picture stays open, the controls sit in the corners.
//   top-left  : back plate, then the title and the container under it
//   top-right : favorite plate (same look as the back plate)
//   bottom-right : one compact panel - Play and a row of icon buttons with captions
//   bottom-left  : the user's own portrait cover as a poster (clear of the panel)
// Two soft scrims (top for the title, bottom for the panel) replace the old heavy left-to-right
// darkening, so most of the picture keeps its own colours.
@Composable
private fun LandscapeDetail(title: String, subtitle: String, artwork: Bitmap?, artworkKey: String?, coverOnly: Boolean, coverIsUser: Boolean, favorite: Boolean, stats: GameStats, items: List<DetailActionItem>, callbacks: GameDetailCallbacks, toggleFavorite: () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        DetailArtwork(artwork, artworkKey, coverOnly, coverIsUser, landscape = true)
        Box(
            Modifier.align(Alignment.TopStart).fillMaxWidth().height(132.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(.66f), Color.Transparent)))
        )
        Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(210.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(.60f))))
        )

        // Everything interactive stays clear of a display cutout on the left / right edge.
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))) {
            // ~42% of the width: 320dp on a small landscape phone, up to 400dp on a wide one.
            val panelWidth = (maxWidth * 0.42f).coerceIn(320.dp, 400.dp)
            // Five icon buttons share the panel: a narrow cell falls back to the smaller caption.
            val iconCell = (panelWidth - 28.dp - 8.dp * (items.size - 1)) / items.size
            val compactCaptions = iconCell < 64.dp
            val posterShown = artwork != null && coverIsUser && !usesAsBackground(artwork, coverOnly, coverIsUser)
            if (artwork != null && posterShown) {
                DetailPoster(artwork, Modifier.align(Alignment.BottomStart))
            }

            // The stat chips sit bottom-left, beside the poster when there is one. Where the picture
            // and the panel leave no room (small phone), they move into the panel above Play.
            val posterWidth = if (artwork != null && posterShown) {
                (maxHeight - 156.dp).coerceAtLeast(0.dp) * (artwork.width.toFloat() / artwork.height.toFloat()).coerceIn(0.5f, 1.29f)
            } else 0.dp
            val chipsStart = 34.dp + if (posterShown) posterWidth + 14.dp else 0.dp
            val chipsEndReserve = panelWidth + 34.dp + 16.dp
            val chipsInPanel = stats.hasAny && maxWidth - chipsStart - chipsEndReserve < 190.dp
            if (stats.hasAny && !chipsInPanel) {
                StatChips(stats, modifier = Modifier.align(Alignment.BottomStart).padding(start = chipsStart, end = chipsEndReserve, bottom = 24.dp))
            }

            ArtPlateButton(
                onClick = callbacks::onBack,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 18.dp, top = 16.dp)
            ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.ui_back), modifier = Modifier.size(24.dp)) }

            // 74dp = 18 margin + 44 plate + 12 gap, on both sides, so a long title never runs under a plate.
            Column(Modifier.align(Alignment.TopStart).padding(start = 74.dp, end = 74.dp, top = 15.dp)) {
                Text(
                    title,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineLarge.copy(
                        shadow = Shadow(OnArtwork.TextShadow, Offset(0f, 2f), 6f)
                    ),
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    subtitle,
                    color = OnArtwork.TextSecondary,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        shadow = Shadow(OnArtwork.TextShadow, Offset(0f, 1f), 4f)
                    )
                )
            }

            FavoriteButton(favorite, toggleFavorite, Modifier.align(Alignment.TopEnd).padding(end = 18.dp, top = 16.dp))

            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 34.dp, bottom = 24.dp).width(panelWidth),
                shape = WinZShapes.Large,
                color = OnArtwork.PanelFill,
                border = BorderStroke(1.dp, OnArtwork.PlateBorder)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (chipsInPanel) StatChips(stats, modifier = Modifier.fillMaxWidth())
                    Button(
                        onClick = callbacks::onPlay,
                        modifier = Modifier.fillMaxWidth().height(58.dp),
                        shape = WinZShapes.Medium,
                        colors = ButtonDefaults.buttonColors(containerColor = controlAccentColor(), contentColor = Color.White)
                    ) {
                        Icon(Icons.Outlined.PlayArrow, null, modifier = Modifier.size(30.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.ui_play), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items.forEach { DetailIconAction(it, Modifier.weight(1f), compactCaptions) }
                    }
                }
            }
        }
    }
}

// Round plate for a control that sits on the artwork (back, favorite). Same translucent look as the
// selected All / Favorites / Recent chip, but always light-on-dark: the artwork under it is darkened
// by a scrim in every theme, so the White theme's dark glyph would vanish.
@Composable
private fun ArtPlateButton(onClick: () -> Unit, modifier: Modifier, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(44.dp),
        shape = WinZShapes.Medium,
        color = OnArtwork.PlateFill,
        contentColor = Color.White,
        border = BorderStroke(1.dp, OnArtwork.PlateBorder)
    ) { Box(contentAlignment = Alignment.Center) { content() } }
}

// Favorite star on the same plate as the back arrow; filled and in the accent colour when set.
@Composable
private fun FavoriteButton(favorite: Boolean, onToggle: () -> Unit, modifier: Modifier) {
    ArtPlateButton(onClick = onToggle, modifier = modifier) {
        Icon(
            if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
            if (favorite) stringResource(R.string.ui_remove_from_favorites) else stringResource(R.string.ui_add_to_favorites),
            modifier = Modifier.size(24.dp),
            tint = if (favorite) controlAccentColor() else LocalContentColor.current
        )
    }
}

// Square button for the landscape panel: icon over a short caption, light on the dark panel.
@Composable
private fun DetailIconAction(item: DetailActionItem, modifier: Modifier, compactCaption: Boolean) {
    val content = when {
        item.destructive -> destructiveColor()
        item.accent -> controlAccentColor()
        else -> Color.White
    }
    Surface(
        onClick = item.onClick,
        modifier = modifier.height(70.dp),
        shape = WinZShapes.Medium,
        color = OnArtwork.PlateFill,
        contentColor = content,
        border = BorderStroke(1.dp, OnArtwork.PlateBorder)
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(item.icon, contentDescription = item.label, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(4.dp))
            // A narrow cell (small landscape phone) falls back to a smaller caption so "Configure" fits.
            val base = if (compactCaption) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium
            Text(
                item.label,
                style = if (compactCaption) base.copy(fontSize = 10.sp) else base,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

// The user's own portrait cover as a poster, bottom-left, kept sharp above the scrims and clear of
// the title (top padding) and of the action panel (it is on the right).
@Composable
private fun DetailPoster(artwork: Bitmap, modifier: Modifier) {
    val ratio = (artwork.width.toFloat() / artwork.height.toFloat()).coerceIn(0.5f, 1.29f)
    Image(
        artwork.asImageBitmap(),
        null,
        modifier
            .padding(start = 34.dp, top = 132.dp, bottom = 24.dp)
            .fillMaxHeight()
            .aspectRatio(ratio)
            .kenBurns(amplitude = 0.04f, periodMs = 18_000, pan = 0f, phase = 0.5f)
            .clip(WinZShapes.Small),
        contentScale = ContentScale.Crop
    )
}

// A wide picture of the user's own is the background itself; anything else that is only a cover
// gets the soft wash with the cover shown in front.
private fun usesAsBackground(artwork: Bitmap, coverOnly: Boolean, coverIsUser: Boolean): Boolean =
    !coverOnly || (coverIsUser && artwork.width >= artwork.height * 1.3f)

// What the header shows, by where the picture came from (all of it moves only when the screen
// allowed motion: LocalArtworkMotion, the "Animated artwork" setting):
//  - banner or the user's own background: sharp, full-bleed, slowly breathing;
//  - the user's own cover, wide enough (>= 1.3): the same, it is the background itself;
//  - any other cover (downloaded portrait cover, the offline placeholder): a soft colour wash of it
//    drifting behind, plus - in the portrait header only - the cover itself in front, at the top.
//    (In landscape the user's own cover is a poster drawn above the scrims: see DetailPoster.)
//  - the user's own cover keeps its own proportions instead of being cropped to 2:3.
@Composable
private fun DetailArtwork(artwork: Bitmap?, artworkKey: String?, coverOnly: Boolean, coverIsUser: Boolean, landscape: Boolean, bottomReserve: Dp = 112.dp) {
    if (artwork == null) return
    val image = artwork.asImageBitmap()
    val ratio = artwork.width.toFloat() / artwork.height.toFloat()
    val frameRatio = if (coverIsUser) ratio.coerceIn(0.5f, 1.29f) else 2f / 3f
    Box(Modifier.fillMaxSize().clipToBounds()) {
        if (usesAsBackground(artwork, coverOnly, coverIsUser)) {
            Image(image, null, Modifier.fillMaxSize().kenBurns(), contentScale = ContentScale.Crop)
        } else {
            // The soft wash used to be made right here in composition (a shrink + blur of the
            // full-size picture on the main thread, during the page's entrance). It is shared with
            // the Library's backdrops through SoftArt's cache (same key), so it is usually ready;
            // otherwise it is made off the main thread and drawn once done.
            val soft by produceState(SoftArt.peek(artworkKey), artwork, artworkKey) {
                if (value == null) value = withContext(Dispatchers.Default) { SoftArt.of(artworkKey, artwork) }
            }
            soft?.let { wash ->
                val washImage = remember(wash) { wash.asImageBitmap() }
                Image(
                    washImage,
                    null,
                    Modifier.fillMaxSize().kenBurns(amplitude = 0.12f, periodMs = 26_000, pan = 0.9f),
                    contentScale = ContentScale.Crop
                )
            }
            if (!landscape) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Image(
                        image,
                        null,
                        Modifier
                            .padding(top = 16.dp, bottom = bottomReserve)
                            .fillMaxHeight()
                            .aspectRatio(frameRatio)
                            .kenBurns(amplitude = 0.04f, periodMs = 18_000, pan = 0f, phase = 0.5f)
                            .clip(WinZShapes.Small),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
    }
}

@Composable
private fun PortraitDetail(title: String, subtitle: String, artwork: Bitmap?, artworkKey: String?, coverOnly: Boolean, coverIsUser: Boolean, fallback: Bitmap?, favorite: Boolean, stats: GameStats, items: List<DetailActionItem>, callbacks: GameDetailCallbacks, toggleFavorite: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Taller than the old 1.28 header: the stat chips sit under the title, over the picture.
        Box(Modifier.fillMaxWidth().aspectRatio(1.12f).background(MaterialTheme.colorScheme.surface)) {
            if (artwork != null) DetailArtwork(artwork, artworkKey, coverOnly, coverIsUser, landscape = false, bottomReserve = 150.dp)
            else if (fallback != null) IconTile(null, fallback, Modifier.fillMaxSize())
            // A little more scrim at the top than before: the two plates sit right on the picture.
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(.38f), Color.Transparent, Color.Black.copy(.88f)))))
            ArtPlateButton(
                onClick = callbacks::onBack,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 14.dp)
            ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.ui_back), modifier = Modifier.size(24.dp)) }
            FavoriteButton(favorite, toggleFavorite, Modifier.align(Alignment.TopEnd).padding(end = 14.dp, top = 14.dp))
            Column(Modifier.align(Alignment.BottomStart).padding(22.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = OnArtwork.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                if (stats.hasAny) {
                    Spacer(Modifier.height(6.dp))
                    StatChips(stats, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = callbacks::onPlay,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = WinZShapes.Medium,
                colors = ButtonDefaults.buttonColors(containerColor = controlAccentColor(), contentColor = Color.White)
            ) { Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.size(8.dp)); Text(stringResource(R.string.ui_play), fontWeight = FontWeight.Bold) }
            items.forEach { DetailAction(it, Modifier.fillMaxWidth()) }
            Spacer(Modifier.height(12.dp))
        }
    }
}

// Full-width row of the portrait list: theme surface and outline; only a destructive row is red.
@Composable
private fun DetailAction(item: DetailActionItem, modifier: Modifier) {
    val tint = if (item.destructive) destructiveColor() else MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = item.onClick,
        modifier = modifier.height(54.dp),
        shape = WinZShapes.Medium,
        // Destructive keeps the regular fill and outline; only its icon and label are red.
        color = MaterialTheme.colorScheme.surface,
        contentColor = tint,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(item.icon, null)
            Spacer(Modifier.size(10.dp))
            Text(item.listLabel, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

// Playtime, plays, last launch and size, as dark glass chips (they always sit on the artwork) with
// the same blue icons as WinNative's launch screen (OnArtwork.InfoIcon).

private class StatEntry(val icon: ImageVector, val label: String, val value: String)

// The chips are measured, not guessed, so no chip is ever left alone on a second line under another:
//  1. one row at full size when it fits;
//  2. else the whole row is shrunk evenly (icons, text and gaps) just enough to fit, down to 80%;
//  3. else (a very narrow spot) two equal columns at full size, so the last chip sits in a tidy grid.
@Composable
private fun StatChips(stats: GameStats, modifier: Modifier = Modifier) {
    // Labels resolved here: the remember block below is not a composable context.
    val playtimeLabel = stringResource(R.string.ui_stat_playtime)
    val playsLabel = stringResource(R.string.ui_stat_plays)
    val lastPlayedLabel = stringResource(R.string.ui_stat_last_played)
    val sizeLabel = stringResource(R.string.ui_stat_size)
    val entries = remember(stats, playtimeLabel) {
        buildList {
            if (stats.playtimeMillis > 0L) add(StatEntry(Icons.Outlined.Schedule, playtimeLabel, formatPlaytime(stats.playtimeMillis)))
            if (stats.playCount > 0) add(StatEntry(Icons.Outlined.SportsEsports, playsLabel, stats.playCount.toString()))
            if (stats.lastPlayedMillis > 0L) add(StatEntry(Icons.Outlined.History, lastPlayedLabel, formatLastPlayed(stats.lastPlayedMillis)))
            stats.sizeText?.let { add(StatEntry(Icons.Outlined.Storage, sizeLabel, it)) }
        }
    }
    if (entries.isEmpty()) return
    SubcomposeLayout(modifier) { constraints ->
        val gap = 8.dp.roundToPx()
        val available = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val natural = Constraints()
        // The parent asks for the full width (fillMaxWidth). A layout that reports less is centred in
        // it, which put a lone chip in the middle: report at least the asked width and place from 0.
        fun layoutWidth(content: Int): Int = content.coerceIn(constraints.minWidth, available)

        // Composing the chips under a smaller density shrinks every dp and sp in them together, and
        // the measured sizes are the real ones (nothing is scaled after the fact).
        fun measureRow(slot: String, scale: Float): List<Placeable> {
            val scaled = Density(density * scale, fontScale)
            return subcompose(slot) {
                CompositionLocalProvider(LocalDensity provides scaled) {
                    entries.forEach { StatChip(it, fill = false) }
                }
            }.map { it.measure(natural) }
        }
        fun rowWidth(items: List<Placeable>, gapPx: Int): Int = items.sumOf { it.width } + gapPx * (items.size - 1)

        var scale = 1f
        var gapPx = gap
        var items = measureRow("row0", scale)
        var attempt = 0
        while (rowWidth(items, gapPx) > available && attempt < 3) {
            attempt++
            // A hair under the exact ratio: rounding of the pieces must not push it over again.
            scale *= available.toFloat() / rowWidth(items, gapPx) * 0.99f
            if (scale < 0.8f) break
            gapPx = (gap * scale).roundToInt()
            items = measureRow("row$attempt", scale)
        }
        if (scale >= 0.8f && rowWidth(items, gapPx) <= available) {
            val height = items.maxOf { it.height }
            return@SubcomposeLayout layout(layoutWidth(rowWidth(items, gapPx)), height) {
                var x = 0
                items.forEach {
                    it.placeRelative(x, 0)
                    x += it.width + gapPx
                }
            }
        }

        val column = (available - gap) / 2
        val cells = subcompose("grid") { entries.forEach { StatChip(it, fill = true) } }
            .map { it.measure(Constraints(minWidth = column, maxWidth = column)) }
        val rows = cells.chunked(2)
        val rowHeights = rows.map { row -> row.maxOf { it.height } }
        val height = rowHeights.sum() + gap * (rows.size - 1)
        val width = if (cells.size == 1) column else column * 2 + gap
        layout(layoutWidth(width), height) {
            var y = 0
            rows.forEachIndexed { index, row ->
                row.forEachIndexed { col, cell -> cell.placeRelative(col * (column + gap), y) }
                y += rowHeights[index] + gap
            }
        }
    }
}

@Composable
private fun StatChip(entry: StatEntry, fill: Boolean) {
    Surface(
        modifier = if (fill) Modifier.fillMaxWidth() else Modifier,
        shape = WinZShapes.Small,
        color = OnArtwork.PanelFill,
        border = BorderStroke(1.dp, OnArtwork.PlateBorder)
    ) {
        Row(
            (if (fill) Modifier.fillMaxWidth() else Modifier).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Icon(entry.icon, null, Modifier.size(16.dp), tint = OnArtwork.InfoIcon)
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(entry.label.uppercase(), color = OnArtwork.TextTertiary, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 12.sp), fontWeight = FontWeight.Bold, maxLines = 1)
                Text(entry.value, color = OnArtwork.Text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall.tabularNumbers())
            }
        }
    }
}

private class DetailActionItem(
    val icon: ImageVector,
    // Caption under the icon in the landscape panel / the row text in the portrait list.
    val label: String,
    val listLabel: String,
    val onClick: () -> Unit,
    val destructive: Boolean = false,
    val accent: Boolean = false
)
