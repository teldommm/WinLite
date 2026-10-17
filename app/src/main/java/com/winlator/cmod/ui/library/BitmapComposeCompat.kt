package com.winlator.cmod.ui.library

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.offset
import com.winlator.cmod.ui.theme.OnArtwork
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import com.winlator.cmod.ui.LandscapeScreenHeader
import com.winlator.cmod.ui.ShellChrome
import com.winlator.cmod.MainActivity
import com.winlator.cmod.R
import com.winlator.cmod.ui.PortraitMainHeader
import com.winlator.cmod.ui.theme.controlAccentColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.ui.graphics.asImageBitmap as composeAsImageBitmap
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.outlined.Close
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import com.winlator.cmod.ui.theme.WinZShapes
import com.winlator.cmod.ui.theme.hairlineColor
import com.winlator.cmod.ui.theme.dividerColor

internal fun Bitmap.asImageBitmap(): ImageBitmap = this.composeAsImageBitmap()

@Composable
internal fun LibraryRoot(
    items: List<LibraryItem>,
    grid: Boolean,
    query: String,
    selectedShortcutPath: MutableState<String?>,
    cb: LibraryCallbacks
) {
    var filterName by rememberSaveable { mutableStateOf(LibraryFilter.All.name) }
    val filter = LibraryFilter.valueOf(filterName)
    val visible = remember(items, filter, query) {
        val source = when (filter) {
            LibraryFilter.All -> items
            LibraryFilter.Favorites -> items.filter { it.favorite }
            LibraryFilter.Recent -> items.sortedByDescending { it.lastRunAt }
        }
        if (query.isBlank()) source else source.filter { it.name.contains(query, true) }
    }
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val activity = LocalContext.current as? MainActivity

    if (landscape && visible.isNotEmpty() && !grid) {
        var menu by remember { mutableStateOf<LibraryItem?>(null) }
        LandscapePagerCore(
            items = visible,
            selectedShortcutPath = selectedShortcutPath,
            callbacks = cb,
            header = {
                LibraryLandscapeTop(
                    activity = activity,
                    grid = grid,
                    onArtwork = true,
                    query = query,
                    filter = filter,
                    onFilter = { filterName = it.name },
                    cb = cb
                )
            },
            footerActions = { item ->
                IconButton(onClick = { menu = item }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.ui_more_options), tint = Color.White) }
            }
        )
        menu?.let { LibraryItemMenuCompat(it, cb) { menu = null } }
        return
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 14.dp)) {
        if (landscape) {
            // Same top section, at the same screen position, as the artwork pager — the grid/list
            // toggle switches between the two layouts, and the title and filter chips used to
            // jump (different side padding, chips 12dp lower and differently spaced).
            Box(Modifier.padding(horizontal = LANDSCAPE_TOP_EXTRA_PADDING)) {
                LibraryLandscapeTop(
                    activity = activity,
                    grid = grid,
                    onArtwork = false,
                    query = query,
                    filter = filter,
                    onFilter = { filterName = it.name },
                    cb = cb
                )
            }
            Spacer(Modifier.height(12.dp))
        } else {
            LibraryPortraitHeader(
                activity = activity,
                grid = grid,
                query = query,
                onGridViewChanged = cb::onGridViewChanged,
                onSearchQueryChanged = cb::onSearchQueryChanged,
                onOpenFileManager = cb::onOpenFileManager
            )
            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(LIBRARY_CHIP_SPACING)) {
                LibraryFilter.values().forEach { option ->
                    LibraryFilterChip(libraryFilterLabel(option), option == filter) { filterName = option.name }
                }
            }
        }
        if (visible.isEmpty()) {
            if (query.isNotBlank() || (filter != LibraryFilter.All && items.isNotEmpty())) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (query.isNotBlank()) stringResource(R.string.ui_no_games_match_search) else stringResource(R.string.ui_no_games_in_section),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Surface(
                        onClick = { activity?.navigateToMainDestination(R.id.main_menu_file_manager) },
                        modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
                        shape = WinZShapes.ExtraLarge,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, hairlineColor())
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                modifier = Modifier.size(72.dp),
                                shape = WinZShapes.ExtraLarge,
                                color = controlAccentColor(),
                                contentColor = Color.White
                            ) {
                                Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Add, null, modifier = Modifier.size(34.dp)) }
                            }
                            Spacer(Modifier.height(16.dp))
                            Text(stringResource(R.string.ui_add_games), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(if (grid) 172.dp else 360.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 112.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(visible, key = { it.id }) { item ->
                    if (grid) CoverArtworkCard(item, cb) else CompactArtworkCard(item, cb)
                }
            }
        }
    }
}

// Landscape top section shared by the artwork pager and the grid/list layout: header band +
// filter chips, identical in both so switching layouts leaves them exactly where they were.
// Both containers put it 26dp from the screen edges (pager: its own 26dp padding; grid: 14dp
// column padding + LANDSCAPE_TOP_EXTRA_PADDING).
private val LANDSCAPE_TOP_EXTRA_PADDING = 12.dp
private val LANDSCAPE_TOP_EDGE = 26.dp
private val LIBRARY_CHIP_SPACING = 8.dp

@Composable
private fun LibraryLandscapeTop(
    activity: MainActivity?,
    grid: Boolean,
    onArtwork: Boolean,
    query: String,
    filter: LibraryFilter,
    onFilter: (LibraryFilter) -> Unit,
    cb: LibraryCallbacks
) {
    Column {
        LibraryLandscapeHeader(
            activity = activity,
            grid = grid,
            onArtwork = onArtwork,
            containerEndPadding = LANDSCAPE_TOP_EDGE,
            query = query,
            onGridViewChanged = cb::onGridViewChanged,
            onSearchQueryChanged = cb::onSearchQueryChanged
        )
        Spacer(Modifier.height(7.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(LIBRARY_CHIP_SPACING)) {
            LibraryFilter.values().forEach { option ->
                LibraryFilterChip(libraryFilterLabel(option), option == filter) { onFilter(option) }
            }
        }
    }
}

@Composable
internal fun LibraryLandscapeHeader(
    activity: MainActivity?,
    grid: Boolean,
    onArtwork: Boolean,
    query: String,
    onGridViewChanged: (Boolean) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    // Horizontal padding of the container this header sits in (list/empty state: 14dp, pager:
    // 26dp), so the space kept free for MainShell's nav group is measured from the screen edge.
    containerEndPadding: Dp = 14.dp
) {
    var searchActive by LocalLibrarySearchActive.current

    if (searchActive) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(ShellChrome.LandscapeHeaderHeight)
                .padding(end = (ShellChrome.LandscapeNavReserve - containerEndPadding).coerceAtLeast(0.dp)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LibraryTopIcon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.ui_close_search), false) {
                searchActive = false
                onSearchQueryChanged("")
            }
            LibrarySearchField(query, onSearchQueryChanged, Modifier.weight(1f).padding(start = 4.dp))
        }
    } else {
        // Home / Input Controls / Settings are MainShell's floating nav group now.
        LandscapeScreenHeader(
            title = stringResource(R.string.ui_library),
            onArtwork = onArtwork,
            startPadding = 0.dp,
            containerEndPadding = containerEndPadding
        ) {
            LibraryTopIcon(Icons.Outlined.Search, stringResource(R.string.ui_search), false) { searchActive = true }
            LibraryTopIcon(if (grid) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView, if (grid) stringResource(R.string.ui_list_view) else stringResource(R.string.ui_grid_view), false) {
                onGridViewChanged(!grid)
            }
            LibraryTopIcon(Icons.Outlined.Add, stringResource(R.string.ui_add_games), false) { activity?.navigateToMainDestination(R.id.main_menu_file_manager) }
            LibraryOrientationMenu(activity)
        }
    }
}

// Portrait counterpart to LibraryLandscapeHeader: replaces the old native Toolbar options-menu
// (grid/list toggle, collapsible SearchView, "Open File Manager", and the orientation "More"
// submenu) now that ShortcutsFragment no longer implements onCreateOptionsMenu. Home/Input
// Controls/Settings icons aren't needed here (unlike the landscape header) because portrait
// keeps the app's BottomNavigation visible for those destinations.
@Composable
internal fun LibraryPortraitHeader(
    activity: MainActivity?,
    grid: Boolean,
    query: String,
    onGridViewChanged: (Boolean) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onOpenFileManager: () -> Unit
) {
    var searchActive by LocalLibrarySearchActive.current

    // Both states share one fixed-height band. The search row used to be a 56dp OutlinedTextField
    // plus 14dp vertical padding — taller than the title row — so opening search pushed the
    // filter chips and the games below it down.
    if (searchActive) {
        Row(
            Modifier.fillMaxWidth().height(ShellChrome.PortraitHeaderHeight),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LibraryTopIcon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.ui_close_search), false) {
                searchActive = false
                onSearchQueryChanged("")
            }
            LibrarySearchField(query, onSearchQueryChanged, Modifier.weight(1f).padding(start = 4.dp))
        }
    } else {
        Box(Modifier.fillMaxWidth().height(ShellChrome.PortraitHeaderHeight), contentAlignment = Alignment.CenterStart) {
            PortraitMainHeader(stringResource(R.string.ui_library))
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LibraryTopIcon(Icons.Outlined.Search, stringResource(R.string.ui_search), false) { searchActive = true }
                LibraryTopIcon(if (grid) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView, if (grid) stringResource(R.string.ui_list_view) else stringResource(R.string.ui_grid_view), false) {
                    onGridViewChanged(!grid)
                }
                LibraryTopIcon(Icons.Outlined.Add, stringResource(R.string.ui_add_games), false) { onOpenFileManager() }
                LibraryOrientationMenu(activity)
            }
        }
    }
}

@Composable
private fun LibraryOrientationMenu(activity: MainActivity?) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        LibraryTopIcon(Icons.Outlined.MoreVert, stringResource(R.string.ui_more_options), false) { expanded = true }
        LibraryOrientationDropdown(activity, expanded) { expanded = false }
    }
}

@Composable
private fun LibraryOrientationDropdown(activity: MainActivity?, expanded: Boolean, onDismiss: () -> Unit) {
    var orientationRevision by remember { mutableStateOf(0) }
    val orientationState = remember(activity, orientationRevision) {
        Triple(
            activity?.isOrientationLocked ?: false,
            activity?.isVerticalModeEnabled ?: false,
            activity?.isHorizontalModeEnabled ?: false
        )
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = WinZShapes.Medium,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        OrientationToggleMenuItem(stringResource(R.string.ui_lock_orientation), orientationState.first) {
            activity?.toggleOrientationLock()
            orientationRevision++
        }
        OrientationToggleMenuItem(stringResource(R.string.ui_vertical_mode), orientationState.second) {
            activity?.toggleVerticalMode()
            orientationRevision++
        }
        OrientationToggleMenuItem(stringResource(R.string.ui_horizontal_mode), orientationState.third) {
            activity?.toggleHorizontalMode()
            orientationRevision++
        }
    }
}

@Composable
private fun OrientationToggleMenuItem(label: String, checked: Boolean, onClick: () -> Unit) {
    val accent = controlAccentColor()
    DropdownMenuItem(
        text = {
            Text(
                label,
                color = if (checked) accent else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal
            )
        },
        trailingIcon = { if (checked) Icon(Icons.Outlined.Check, null, tint = accent) },
        onClick = onClick
    )
}

@Composable
// description: read by TalkBack (these buttons have no visible label).
private fun LibraryTopIcon(icon: ImageVector, description: String, selected: Boolean, click: () -> Unit) {
    val background = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else Color.Transparent
    val content = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onBackground.copy(alpha = .68f)
    Surface(
        onClick = click,
        modifier = Modifier.padding(horizontal = 3.dp),
        shape = WinZShapes.Medium,
        color = background,
        contentColor = content
    ) { Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Icon(icon, description, modifier = Modifier.size(23.dp)) } }
}

// Fill of small controls that sit over the content: the selected All / Favorites / Recent chip and
// the back arrow of the game page. It used to be the opaque surfaceVariant, a dark slab (near-black on
// AMOLED) that hid whatever is behind it, e.g. the artwork. Now a translucent tint of the text colour
// - light on the dark themes, dark on White - so the backdrop shows through. Raise the alphas for a
// stronger highlight; both controls follow.
@Composable
internal fun translucentControlFill(): Color {
    val light = MaterialTheme.colorScheme.background.luminance() > .65f
    return MaterialTheme.colorScheme.onSurface.copy(alpha = if (light) 0.06f else 0.10f)
}

// Shown text of a filter; the enum name stays the saved state (rememberSaveable).
@Composable
internal fun libraryFilterLabel(filter: LibraryFilter): String = when (filter) {
    LibraryFilter.All -> stringResource(R.string.ui_filter_all)
    LibraryFilter.Favorites -> stringResource(R.string.ui_filter_favorites)
    LibraryFilter.Recent -> stringResource(R.string.ui_filter_recent)
}

@Composable
private fun LibraryFilterChip(label: String, selected: Boolean, click: () -> Unit) {
    Surface(
        onClick = click,
        shape = WinZShapes.Small,
        color = if (selected) translucentControlFill() else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, hairlineColor())
    ) { Text(label, Modifier.padding(horizontal = 15.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CompactArtworkCard(item: LibraryItem, cb: LibraryCallbacks) {
    RequestArtworkCompat(item, cb)
    Surface(
        modifier = Modifier.fillMaxWidth().height(92.dp).combinedClickable(
            onClick = { cb.onOpen(item.shortcutPath) },
            onLongClick = { cb.onAction(item.shortcutPath, LibraryComposeHost.ACTION_SETTINGS) }
        ),
        shape = WinZShapes.Medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, hairlineColor())
    ) {
        Box {
            // Backdrop only from real artwork (or the placeholder cover); an icon stretched over
            // the whole row is just noise.
            val backdrop = item.bannerPath ?: item.coverPath ?: item.generatedCoverPath
            if (backdrop != null) {
                ArtworkCompat(backdrop, null, Modifier.fillMaxSize().alpha(.52f))
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface.copy(.38f),
                            MaterialTheme.colorScheme.surface.copy(.82f),
                            MaterialTheme.colorScheme.surface.copy(.96f)
                        )
                    )
                )
            )
            Row(Modifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                ArtworkCompat(
                    item.coverPath ?: item.bannerPath ?: item.iconPath,
                    item.fallbackIcon,
                    Modifier.size(68.dp).clip(WinZShapes.Small)
                )
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(item.containerName, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                PlayCompat(item, cb, false)
                MenuButtonCompat(item, cb, false)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CoverArtworkCard(item: LibraryItem, cb: LibraryCallbacks) {
    RequestArtworkCompat(item, cb)
    Surface(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = { cb.onOpen(item.shortcutPath) },
            onLongClick = { cb.onAction(item.shortcutPath, LibraryComposeHost.ACTION_SETTINGS) }
        ),
        shape = WinZShapes.Large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, hairlineColor())
    ) {
        Box(Modifier.aspectRatio(.72f)) {
            // No downloaded cover: the placeholder built from the icon (works offline); with no
            // usable icon either, the plain icon tile. Never a stretched icon/banner.
            val cover = item.coverPath ?: item.generatedCoverPath
            if (cover != null) ArtworkCompat(cover, null, Modifier.fillMaxSize())
            else IconTile(item.iconPath, item.fallbackIcon, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color.Black.copy(.92f)))))
            Column(Modifier.align(Alignment.BottomStart).padding(start = 14.dp, end = 50.dp, bottom = 13.dp)) {
                Text(item.name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(item.containerName, color = OnArtwork.TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            Box(Modifier.align(Alignment.TopEnd)) { MenuButtonCompat(item, cb, true) }
            Box(Modifier.align(Alignment.BottomEnd).padding(9.dp)) { PlayCompat(item, cb, true) }
        }
    }
}

@Composable
private fun RequestArtworkCompat(item: LibraryItem, cb: LibraryCallbacks) {
    LaunchedEffect(item.id, item.coverPath, item.bannerPath) {
        if (item.coverPath == null) cb.onArtworkNeeded(item.shortcutPath, "cover")
        if (item.bannerPath == null) cb.onArtworkNeeded(item.shortcutPath, "banner")
    }
}

@Composable
private fun ArtworkCompat(path: String?, fallback: Bitmap?, modifier: Modifier) {
    // Start from the cached decode when there is one (see LibraryImageCache), so a tile that
    // comes back into view doesn't flash the fallback icon first.
    val key = remember(path) { LibraryImageCache.keyFor(path) }
    val bitmap by produceState(LibraryImageCache.peek(key) ?: fallback, key) {
        value = withContext(Dispatchers.IO) { LibraryImageCache.load(path) } ?: fallback
    }
    if (bitmap != null) Image(bitmap!!.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
}

@Composable
private fun PlayCompat(item: LibraryItem, cb: LibraryCallbacks, overlay: Boolean) {
    Surface(
        onClick = { cb.onRun(item.shortcutPath) },
        modifier = Modifier.size(42.dp),
        shape = CircleShape,
        color = controlAccentColor(),
        contentColor = Color.White
    ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.PlayArrow, stringResource(R.string.ui_play)) } }
}

@Composable
private fun MenuButtonCompat(item: LibraryItem, cb: LibraryCallbacks, light: Boolean) {
    var open by remember { mutableStateOf(false) }
    // `light` = true means this sits over cover artwork (CoverArtworkCard's top-right corner),
    // which isn't guaranteed to have the bottom-gradient scrim under it. White stays white on
    // Black/Amoled (dark artwork scrim), but flips to a dark tint on White so it doesn't
    // disappear against the light surfaceVariant fallback or a bright cover.
    val whiteTheme = MaterialTheme.colorScheme.background.luminance() > .65f
    val tint = if (!light) MaterialTheme.colorScheme.onSurfaceVariant
        else if (whiteTheme) MaterialTheme.colorScheme.onSurface
        else Color.White
    IconButton(onClick = { open = true }) {
        Icon(Icons.Outlined.MoreVert, stringResource(R.string.ui_more_options), tint = tint)
    }
    if (open) LibraryItemMenuCompat(item, cb) { open = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryItemMenuCompat(item: LibraryItem, cb: LibraryCallbacks, close: () -> Unit) {
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = close,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        dragHandle = {
            Surface(
                modifier = Modifier.padding(top = 10.dp, bottom = 8.dp).size(width = 36.dp, height = 4.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .42f)
            ) {}
        }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = if (landscape) 920.dp else 760.dp)
                // Scrolls if the actions don't fit a short landscape screen.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (landscape) 22.dp else 16.dp)
                .padding(bottom = if (landscape) 10.dp else 24.dp)
                .align(Alignment.CenterHorizontally)
        ) {
            Text(
                item.name,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            androidx.compose.material3.HorizontalDivider(
                color = dividerColor()
            )
            val favoriteLabel = if (item.favorite) stringResource(R.string.ui_unfavorite) else stringResource(R.string.ui_favorite)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LibraryActionTileCompat(
                    if (item.favorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                    favoriteLabel,
                    Modifier.weight(1f),
                    horizontal = landscape
                ) {
                    close()
                    cb.onAction(item.shortcutPath, LibraryComposeHost.ACTION_FAVORITE)
                }
                LibraryActionTileCompat(Icons.Outlined.Settings, stringResource(R.string.ui_configure), Modifier.weight(1f), horizontal = landscape) {
                    close()
                    cb.onAction(item.shortcutPath, LibraryComposeHost.ACTION_SETTINGS)
                }
                LibraryActionTileCompat(Icons.Outlined.Photo, stringResource(R.string.ui_artwork), Modifier.weight(1f), horizontal = landscape) {
                    close()
                    cb.onAction(item.shortcutPath, LibraryComposeHost.ACTION_ICON)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LibraryActionTileCompat(Icons.Outlined.Home, stringResource(R.string.ui_home_screen), Modifier.weight(1f), horizontal = landscape) {
                    close()
                    cb.onAction(item.shortcutPath, LibraryComposeHost.ACTION_HOME)
                }
                LibraryActionTileCompat(Icons.Outlined.ContentCopy, stringResource(R.string.ui_clone), Modifier.weight(1f), horizontal = landscape) {
                    close()
                    cb.onAction(item.shortcutPath, LibraryComposeHost.ACTION_CLONE)
                }
                LibraryActionTileCompat(Icons.Outlined.FileUpload, stringResource(R.string.ui_export), Modifier.weight(1f), horizontal = landscape) {
                    close()
                    cb.onAction(item.shortcutPath, LibraryComposeHost.ACTION_EXPORT)
                }
            }
            LibraryActionTileCompat(
                Icons.Outlined.DeleteOutline,
                stringResource(R.string.ui_remove_from_library),
                Modifier.fillMaxWidth().padding(top = 8.dp),
                destructive = true,
                horizontal = landscape
            ) {
                close()
                cb.onAction(item.shortcutPath, LibraryComposeHost.ACTION_REMOVE)
            }
        }
    }
}

@Composable
private fun LibraryActionTileCompat(
    icon: ImageVector,
    label: String,
    modifier: Modifier,
    destructive: Boolean = false,
    horizontal: Boolean = false,
    click: () -> Unit
) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = click,
        modifier = modifier,
        shape = WinZShapes.Medium,
        // Same fill and outline for every tile; "Remove from library" differs only by its red
        // icon and label.
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .42f),
        border = BorderStroke(1.dp, hairlineColor())
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (horizontal) 13.dp else 12.dp,
                vertical = if (horizontal) 9.dp else 10.dp
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, modifier = Modifier.size(if (horizontal) 21.dp else 23.dp), tint = tint)
            Spacer(Modifier.width(9.dp))
            Text(
                label,
                style = if (horizontal) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// Compact search field shared by both Library headers: 44dp tall so it fits the header band
// (a Material OutlinedTextField is at least 56dp). Focuses itself when shown, so the keyboard
// comes up right away.
@Composable
private fun LibrarySearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val shape = WinZShapes.Medium
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(controlAccentColor()),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = modifier.focusRequester(focusRequester),
        decorationBox = { innerTextField ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(BorderStroke(1.dp, hairlineColor()), shape)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Box(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    if (query.isEmpty()) {
                        Text(
                            stringResource(R.string.ui_search_games),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    innerTextField()
                }
                if (query.isNotEmpty()) {
                    // The touch area is the field's full height and 36dp wide (it used to be just
                    // the 20dp glyph). Shifted 8dp into the end padding so the cross stays exactly
                    // where it was drawn before.
                    Box(
                        modifier = Modifier
                            .offset(x = 8.dp)
                            .size(width = 36.dp, height = 44.dp)
                            .clip(WinZShapes.Small)
                            .clickable(onClickLabel = stringResource(R.string.ui_clear_search)) { onQueryChange("") },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.ui_clear),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    )
}
