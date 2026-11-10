package com.winlator.cmod.ui.library

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.winlator.cmod.MainActivity
import com.winlator.cmod.ui.applyAppFullscreen
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import com.winlator.cmod.ui.theme.FadeInOnShow

@Immutable
data class LibraryItem(
    val id: String,
    val shortcutPath: String,
    val name: String,
    val containerName: String,
    val coverPath: String?,
    // coverPath is a picture the user chose themselves (NAME.user.png): shown as it is, never
    // reduced to a blurred backdrop.
    val coverIsUser: Boolean,
    // Offline placeholder built from the game's icon (see ArtworkRepository.generatedCoverFile);
    // used only where coverPath is null, so the downloader still treats the cover as missing.
    val generatedCoverPath: String?,
    val bannerPath: String?,
    val iconPath: String?,
    val fallbackIcon: Bitmap?,
    val favorite: Boolean,
    val lastRunAt: Long
)

@Stable
interface LibraryCallbacks {
    fun onOpen(shortcutPath: String)
    fun onRun(shortcutPath: String)
    fun onGridViewChanged(gridView: Boolean)
    fun onAction(shortcutPath: String, action: String)
    fun onArtworkNeeded(shortcutPath: String, kind: String)
    fun onSearchQueryChanged(query: String)
    fun onOpenFileManager()
}

class LibraryComposeController internal constructor(
    private val context: Context,
    internal val items: MutableState<List<LibraryItem>>,
    internal val grid: MutableState<Boolean>,
    internal val query: MutableState<String>,
    internal val selectedShortcutPath: MutableState<String?>
) {
    // False until the first item list arrives. Items are loaded off the main thread now, so
    // without this the "empty library" state would flash for a frame on every cold start.
    internal val loaded = mutableStateOf(false)

    // Set once the Library has been shown (see LibraryContent): later entries don't replay the fade-in.
    internal var revealedOnce = false

    // Search bar open/closed. Lives here (not in the header's rememberSaveable) so the shell can
    // close it when the user leaves the Library tab — tabs persist now, so a local flag would
    // stay open (with the list still filtered) until the user came back and closed it by hand.
    internal val searchActive = mutableStateOf(false)

    fun closeSearch() {
        searchActive.value = false
        query.value = ""
    }

    private val statePreferences = context.getSharedPreferences("library_compose_state", Context.MODE_PRIVATE)

    // Items arrive fully resolved (runtime label, pre-decoded icon) from LibraryScreenController's
    // loader thread. There used to be a second pass here that published the raw container name
    // first and swapped in the "Proton …" label a moment later — the text jump on tiles
    // every time the Library reloaded (e.g. switching back from Input Controls).
    fun setItems(value: List<LibraryItem>) {
        items.value = value.toList()
        loaded.value = true
    }

    fun setGridView(value: Boolean) { grid.value = value }
    fun setSearchQuery(value: String?) { query.value = value?.trim().orEmpty() }

    fun setSelectedShortcutPath(value: String?) {
        selectedShortcutPath.value = value
        statePreferences.edit().apply {
            if (value.isNullOrEmpty()) remove("selected_shortcut_path")
            else putString("selected_shortcut_path", value)
        }.apply()
    }
}

object LibraryComposeHost {
    const val ACTION_SETTINGS = "settings"
    const val ACTION_ICON = "icon"
    const val ACTION_CLONE = "clone"
    const val ACTION_HOME = "home"
    const val ACTION_EXPORT = "export"
    const val ACTION_REMOVE = "remove"
    const val ACTION_FAVORITE = "favorite"

    // Library UI state. The screen itself is LibraryContent(), called from LibraryRoute inside
    // MainShell — there is no ComposeView for the Library anymore.
    @JvmStatic
    fun createController(context: Context, initialGridView: Boolean): LibraryComposeController {
        applyAppFullscreen(context as? MainActivity)
        return LibraryComposeController(
            context.applicationContext,
            mutableStateOf(emptyList()),
            mutableStateOf(initialGridView),
            mutableStateOf(""),
            mutableStateOf(
                context.getSharedPreferences("library_compose_state", Context.MODE_PRIVATE)
                    .getString("selected_shortcut_path", null)
            )
        )
    }
}

// Search open/closed state for the Library headers (portrait and landscape).
internal val LocalLibrarySearchActive = staticCompositionLocalOf<MutableState<Boolean>> {
    mutableStateOf(false)
}

@Composable
internal fun LibraryContent(controller: LibraryComposeController, callbacks: LibraryCallbacks) {
    if (!controller.loaded.value) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }
    // Fades in once when the first item list replaces the blank loading frame.
    // The controller outlives the composition (tab switches), so only the first reveal animates.
    val animateReveal = remember { !controller.revealedOnce }
    LaunchedEffect(Unit) { controller.revealedOnce = true }
    FadeInOnShow(enabled = animateReveal) {
        CompositionLocalProvider(LocalLibrarySearchActive provides controller.searchActive) {
            LibraryRootWithoutEmptyDescription(
                controller.items.value,
                controller.grid.value,
                controller.query.value,
                controller.selectedShortcutPath,
                callbacks
            )
        }
    }
}

internal enum class LibraryFilter { All, Favorites, Recent }
