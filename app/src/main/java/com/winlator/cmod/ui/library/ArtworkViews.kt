package com.winlator.cmod.ui.library

import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Immutable
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.winlator.cmod.ui.theme.WinZShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Shared pieces for showing game artwork where a cover or banner may be missing.
//
// Why: the tiles used to fall back to `cover ?: banner ?: icon` with ContentScale.Crop, so a
// 256px exe icon was blown up to fill a whole portrait tile, and a portrait cover was stretched
// across a landscape screen (a ~4x zoom of one horizontal strip). Now a missing cover shows the
// icon at its own size on a plain tile, and a cover-only backdrop is blurred instead of enlarged.

// The game icon at a fixed size, centred on a plain tile. For "there is no cover" states.
@Composable
internal fun IconTile(iconPath: String?, fallback: Bitmap?, modifier: Modifier = Modifier, iconSize: Dp = 72.dp) {
    val key = remember(iconPath) { LibraryImageCache.keyFor(iconPath) }
    val bitmap by produceState(LibraryImageCache.peek(key) ?: fallback, key) {
        value = withContext(Dispatchers.IO) { LibraryImageCache.load(iconPath) } ?: fallback
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val icon = bitmap
        if (icon != null) {
            Image(
                icon.asImageBitmap(),
                null,
                Modifier.size(iconSize).clip(WinZShapes.Small),
                contentScale = ContentScale.Fit
            )
        }
    }
}

// A picture the user chose that is wide enough to be a background on its own.
private const val WIDE_ASPECT = 1.3f
private val wideCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

// Header-only read (no decode); cached per file version. Call off the main thread.
internal fun isWideImage(path: String?): Boolean {
    val key = LibraryImageCache.keyFor(path) ?: return false
    wideCache[key]?.let { return it }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    val wide = bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth >= bounds.outHeight * WIDE_ASPECT
    wideCache[key] = wide
    return wide
}

// What a backdrop shows: a sharp picture (banner / the user's own wide background) or the soft
// colour wash of a cover.
@Immutable
private data class BackdropArt(val bitmap: Bitmap, val sharp: Boolean)

// Cache-only lookup for the first frame (main thread: file stats only, no decode).
private fun peekBackdrop(bannerPath: String?, coverPath: String?, coverIsUser: Boolean): BackdropArt? {
    val coverKey = LibraryImageCache.keyFor(coverPath)
    val wide = coverIsUser && coverKey != null && wideCache[coverKey] == true
    val sharpPath = bannerPath ?: if (wide) coverPath else null
    if (sharpPath != null) {
        return LibraryImageCache.peek(LibraryImageCache.keyFor(sharpPath))?.let { BackdropArt(it, sharp = true) }
    }
    if (coverPath != null) return SoftArt.peek(coverKey)?.let { BackdropArt(it, sharp = false) }
    return null
}

// Full lookup with decoding. Off the main thread.
private fun loadBackdrop(bannerPath: String?, coverPath: String?, coverIsUser: Boolean): BackdropArt? {
    val wide = coverIsUser && isWideImage(coverPath)
    val sharpPath = bannerPath ?: if (wide) coverPath else null
    if (sharpPath != null) return LibraryImageCache.load(sharpPath)?.let { BackdropArt(it, sharp = true) }
    if (coverPath != null) return SoftArt.load(coverPath)?.let { BackdropArt(it, sharp = false) }
    return null
}

// Decodes (and caches) what ArtBackdrop would show for these pictures, so moving to that game
// finds its backdrop ready. Off the main thread.
internal fun warmBackdrop(bannerPath: String?, coverPath: String?, coverIsUser: Boolean) {
    loadBackdrop(bannerPath, coverPath, coverIsUser)
}

// Full-bleed background, by where the picture came from:
//  - a banner (downloaded, or the user's own background): sharp, slowly breathing;
//  - the user's own cover: shown as picked. A wide one is the background itself (sharp); a portrait
//    one is not blown up into a strip, the backdrop falls back to the soft wash (the sharp cover is
//    shown by the card / detail page in front of it);
//  - a downloaded portrait cover or the offline placeholder: the soft colour wash made from it
//    (see SoftArt), drifting a little more.
// Draws nothing when the game has no artwork at all.
//
// When the pictures change (the pager moved to another game, a banner finished downloading) the
// current backdrop stays up until the new one is decoded, then it is replaced. It used to be
// dropped at once, so every swipe to a game whose backdrop wasn't cached yet showed a frame of
// bare background before the new picture.
@Composable
internal fun ArtBackdrop(bannerPath: String?, coverPath: String?, coverIsUser: Boolean, modifier: Modifier = Modifier) {
    var art by remember { mutableStateOf(peekBackdrop(bannerPath, coverPath, coverIsUser)) }
    LaunchedEffect(bannerPath, coverPath, coverIsUser) {
        peekBackdrop(bannerPath, coverPath, coverIsUser)?.let { art = it; return@LaunchedEffect }
        art = withContext(Dispatchers.IO) { loadBackdrop(bannerPath, coverPath, coverIsUser) }
    }
    val shown = art ?: return
    // keyed by the picture: a new picture is a new layer (and starts its own Ken Burns loop).
    key(shown) {
        Box(modifier.clipToBounds()) {
            val image = remember(shown.bitmap) { shown.bitmap.asImageBitmap() }
            Image(
                image,
                null,
                if (shown.sharp) Modifier.fillMaxSize().kenBurns()
                else Modifier.fillMaxSize().kenBurns(amplitude = 0.12f, periodMs = 26_000, pan = 0.9f),
                contentScale = ContentScale.Crop
            )
        }
    }
}
