package com.winlator.cmod.ui.filemanager

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.winlator.cmod.R
import com.winlator.cmod.ui.theme.DialogScrimColor
import com.winlator.cmod.ui.theme.ThemedDialogSurface
import com.winlator.cmod.ui.theme.WinZMotion
import com.winlator.cmod.ui.theme.WinZOverlayTheme
import com.winlator.cmod.ui.theme.WinZShapes
import com.winlator.cmod.ui.theme.accentSwitchColors
import com.winlator.cmod.ui.theme.controlAccentColor
import com.winlator.cmod.ui.theme.dividerColor
import com.winlator.cmod.ui.theme.findActivity
import com.winlator.cmod.ui.theme.hairlineColor
import com.winlator.cmod.ui.uiStr
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.winlator.cmod.ui.theme.TablerIcons

// Receives the file or folder the person picked. A fun interface so Kotlin callers pass a lambda
// and Java callers (OnboardingActivity, ControlsEditorActivity) pass a plain lambda too.
fun interface FilePickedListener {
    fun onPicked(file: File)
}

// The app's own file / folder / image picker, used everywhere instead of the system Storage Access
// Framework. It browses with plain java.io.File — the same storage access the File Manager tab
// uses (the app holds all-files access) — so there is no activity-result plumbing and no URI
// permission to persist: the caller just gets a File in a callback.
//
// It is hosted like GameSavesComposeDialog: a ComposeView added onto the activity's content root,
// sharing the themed dialog card and scrim of every other window in the app.
//
// Files are listed by extension (e.g. "icp"); "Show all files" lifts the filter for files that
// were renamed. Navigation stays inside the selected storage root, like the File Manager.
object FilePickerDialog {
    private const val VIEW_TAG = "file_picker_dialog"

    /** Extensions (lower case, no dot) the image picker lists. */
    @JvmField
    val IMAGE_EXTENSIONS: List<String> = listOf("png", "jpg", "jpeg", "webp", "bmp", "gif")

    // Where the last pick happened; the next picker without a better start folder opens there.
    // In memory only: it is a convenience, not a setting.
    private var lastDir: File? = null

    /**
     * The folder a stored path points at, or its nearest existing parent (a folder that was not
     * created yet), or null. Use it to open the picker where a path field currently points.
     */
    @JvmStatic
    fun nearestDir(path: String?): File? {
        if (path.isNullOrBlank()) return null
        var candidate: File? = File(path)
        while (candidate != null && !candidate.isDirectory) candidate = candidate.parentFile
        return candidate
    }

    /**
     * Pick a file.
     * @param extensions lower-case extensions without the dot; empty means every file is listed.
     * @param startDir where to open first; falls back to the last used folder, Downloads, then the
     *                 storage root.
     */
    @JvmStatic
    fun show(
        context: Context,
        title: String,
        extensions: List<String>,
        startDir: File?,
        listener: FilePickedListener
    ) = launch(context, title, PickMode.FILE, extensions, startDir, listener)

    /** Pick an image (png / jpg / webp / bmp / gif). */
    @JvmStatic
    fun showImage(
        context: Context,
        title: String,
        startDir: File?,
        listener: FilePickedListener
    ) = launch(context, title, PickMode.FILE, IMAGE_EXTENSIONS, startDir, listener)

    /** Pick a folder: browse into it, then "Select this folder". */
    @JvmStatic
    fun showFolder(
        context: Context,
        title: String,
        startDir: File?,
        listener: FilePickedListener
    ) = launch(context, title, PickMode.FOLDER, emptyList(), startDir, listener)

    private fun launch(
        context: Context,
        title: String,
        mode: PickMode,
        extensions: List<String>,
        startDir: File?,
        listener: FilePickedListener
    ) {
        // A Compose LocalContext is often a ContextWrapper around the activity, not the activity.
        val activity: Activity = context.findActivity() ?: return
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        if (root.findViewWithTag<View>(VIEW_TAG) != null) return

        val roots = storageRoots(activity)
        val initial = resolveStartDir(startDir, roots)

        lateinit var composeView: ComposeView
        composeView = ComposeView(activity).apply {
            tag = VIEW_TAG
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WinZOverlayTheme {
                    val visibleState = remember { MutableTransitionState(false) }
                    val state = remember { FilePickerState(roots, initial) }
                    LaunchedEffect(Unit) { visibleState.targetState = true }
                    LaunchedEffect(visibleState.currentState) {
                        if (!visibleState.currentState && !visibleState.targetState) {
                            (composeView.parent as? ViewGroup)?.removeView(composeView)
                        }
                    }
                    val dismiss: () -> Unit = { visibleState.targetState = false }
                    // Back goes up a folder first and only closes the picker at the storage root.
                    BackHandler(enabled = visibleState.targetState) {
                        if (state.canGoUp()) state.up() else dismiss()
                    }
                    AnimatedVisibility(
                        visibleState = visibleState,
                        enter = WinZMotion.dialogScrimEnter(),
                        exit = WinZMotion.dialogScrimExit()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(DialogScrimColor)
                                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { dismiss() },
                            contentAlignment = Alignment.Center
                        ) {
                            AnimatedVisibility(
                                visibleState = visibleState,
                                enter = WinZMotion.dialogCardEnter(),
                                exit = WinZMotion.dialogCardExit()
                            ) {
                                // Swallow taps inside the card so they don't reach the dismiss scrim.
                                ThemedDialogSurface(
                                    modifier = Modifier
                                        .heightIn(max = 620.dp)
                                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { }
                                ) {
                                    FilePickerPanel(
                                        title = title,
                                        mode = mode,
                                        extensions = extensions,
                                        state = state,
                                        onPick = { picked ->
                                            // The card stays tappable during its exit animation; a second
                                            // tap must not deliver the same pick twice.
                                            if (visibleState.targetState) {
                                                lastDir = if (picked.isDirectory) picked else picked.parentFile
                                                dismiss()
                                                listener.onPicked(picked)
                                            }
                                        },
                                        onClose = dismiss
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        root.addView(composeView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun resolveStartDir(requested: File?, roots: List<PickerRoot>): File {
        val candidates = listOf(
            requested,
            lastDir,
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStorageDirectory()
        )
        for (candidate in candidates) {
            if (candidate != null && candidate.isDirectory && candidate.canRead()) return candidate
        }
        return roots.first().dir
    }
}

private enum class PickMode { FILE, FOLDER }

private class PickerRoot(val label: String, val dir: File)

// The app reads storage with plain files, which needs all-files access on Android 11+. The app asks
// for it at startup, but during the first-run component setup it may not be granted yet — the
// picker then says so and offers to open the system screen instead of showing an empty list.
private fun hasStorageAccess(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

private fun openStorageAccessSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    try {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + context.packageName))
        )
    } catch (e: Exception) {
        try {
            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        } catch (ignored: Exception) {
        }
    }
}

// Internal storage first, then any removable volume (SD card / USB), found the same ways the File
// Manager finds them: StorageManager volumes on R+, and the /storage/<volume> entries as a fallback.
private fun storageRoots(context: Context): List<PickerRoot> {
    val internal = Environment.getExternalStorageDirectory()
    val result = ArrayList<PickerRoot>()
    result.add(PickerRoot(context.getString(R.string.ui_internal_storage), internal))

    val seen = HashSet<String>()
    seen.add(canonical(internal))

    fun addExternal(dir: File?) {
        if (dir == null || !dir.isDirectory || !dir.canRead()) return
        if (!seen.add(canonical(dir))) return
        result.add(PickerRoot(context.getString(R.string.ui_external_storage_named, dir.name), dir))
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        storageManager?.storageVolumes?.forEach { volume ->
            if (!volume.isPrimary) addExternal(volume.directory)
        }
    }

    File("/storage").listFiles()?.sortedBy { it.name.lowercase(Locale.ROOT) }?.forEach { entry ->
        if (entry.name != "emulated" && entry.name != "self") addExternal(entry)
    }
    return result
}

private fun canonical(file: File): String =
    try {
        file.canonicalPath
    } catch (e: Exception) {
        file.absolutePath
    }

private fun isInside(file: File, root: File): Boolean {
    val path = canonical(file)
    val rootPath = canonical(root)
    return path == rootPath || path.startsWith(rootPath + File.separator)
}

private class FilePickerState(val roots: List<PickerRoot>, start: File) {
    var root by mutableStateOf(rootFor(start))
        private set
    var dir by mutableStateOf(start)
        private set
    var showAll by mutableStateOf(false)

    private fun rootFor(file: File): PickerRoot =
        // The longest matching root wins, so a volume nested under another one is still picked.
        roots.filter { isInside(file, it.dir) }.maxByOrNull { canonical(it.dir).length } ?: roots.first()

    fun canGoUp(): Boolean = canonical(dir) != canonical(root.dir)

    fun up() {
        if (!canGoUp()) return
        val parent = dir.parentFile ?: return
        if (parent.canRead() && isInside(parent, root.dir)) dir = parent
    }

    fun open(directory: File) {
        if (directory.isDirectory && directory.canRead()) dir = directory
    }

    fun openRoot(target: PickerRoot) {
        root = target
        dir = target.dir
    }
}

private class PickerEntry(val file: File, val isDirectory: Boolean)

private fun listEntries(
    dir: File,
    extensions: List<String>,
    showAll: Boolean,
    foldersOnly: Boolean
): List<PickerEntry> {
    val files = dir.listFiles() ?: return emptyList()
    val result = ArrayList<PickerEntry>(files.size)
    for (file in files) {
        if (file.name.startsWith(".")) continue
        if (file.isDirectory) {
            result.add(PickerEntry(file, true))
        } else if (!foldersOnly &&
            (showAll || extensions.isEmpty() || extensions.contains(file.extension.lowercase(Locale.ROOT)))
        ) {
            result.add(PickerEntry(file, false))
        }
    }
    result.sortWith(
        compareByDescending<PickerEntry> { it.isDirectory }
            .thenBy { it.file.name.lowercase(Locale.ROOT) }
    )
    return result
}

@Composable
private fun ColumnScope.FilePickerPanel(
    title: String,
    mode: PickMode,
    extensions: List<String>,
    state: FilePickerState,
    onPick: (File) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val accent = controlAccentColor()
    val folderMode = mode == PickMode.FOLDER

    // Bumped whenever the app comes back to the foreground (e.g. from the all-files-access screen)
    // so the access check and the listing are redone.
    var refreshTick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refreshTick++
        onPauseOrDispose { }
    }
    val hasAccess = remember(refreshTick) { hasStorageAccess() }
    val entries = remember(state.dir, state.showAll, extensions, folderMode, refreshTick) {
        listEntries(state.dir, extensions, state.showAll, folderMode)
    }

    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    Spacer(Modifier.size(10.dp))

    // More than one storage (an SD card or USB drive is present): pick which one to browse.
    if (state.roots.size > 1) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            state.roots.forEach { root ->
                val selected = root === state.root
                Surface(
                    onClick = { state.openRoot(root) },
                    shape = WinZShapes.Medium,
                    color = if (selected) accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, if (selected) accent else hairlineColor())
                ) {
                    Text(
                        root.label,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                }
            }
        }
        Spacer(Modifier.size(8.dp))
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = state::up, enabled = state.canGoUp()) {
            Icon(TablerIcons.ArrowLeft, uiStr(R.string.ui_up_directory))
        }
        Text(
            state.dir.absolutePath,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            // Keeps both the storage root and the current folder visible on long paths.
            overflow = TextOverflow.MiddleEllipsis
        )
    }
    HorizontalDivider(color = dividerColor())

    Box(Modifier.weight(1f, fill = false).heightIn(min = 160.dp)) {
        if (!hasAccess) {
            Column(
                Modifier.fillMaxWidth().heightIn(min = 160.dp).padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    uiStr(R.string.ui_storage_access_needed),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(12.dp))
                Button(
                    onClick = { openStorageAccessSettings(context) },
                    shape = WinZShapes.Medium,
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White)
                ) {
                    Text(uiStr(R.string.ui_grant_access))
                }
            }
        } else if (entries.isEmpty()) {
            Box(Modifier.fillMaxWidth().heightIn(min = 160.dp), contentAlignment = Alignment.Center) {
                Text(
                    uiStr(
                        when {
                            folderMode -> R.string.ui_no_subfolders
                            state.showAll -> R.string.ui_this_folder_is_empty
                            else -> R.string.ui_no_matching_files
                        }
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(entries, key = { it.file.absolutePath }) { entry ->
                    PickerRow(entry) {
                        if (entry.isDirectory) state.open(entry.file) else onPick(entry.file)
                    }
                    HorizontalDivider(modifier = Modifier.padding(start = 48.dp), color = dividerColor())
                }
            }
        }
    }

    HorizontalDivider(color = dividerColor())
    if (folderMode) {
        // The folder being shown is the one that gets picked.
        Spacer(Modifier.size(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = onClose,
                modifier = Modifier.weight(1f),
                shape = WinZShapes.Medium
            ) {
                Text(uiStr(R.string.cancel))
            }
            Spacer(Modifier.width(10.dp))
            Button(
                onClick = { onPick(state.dir) },
                modifier = Modifier.weight(1f),
                enabled = hasAccess && state.dir.canRead(),
                shape = WinZShapes.Medium,
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White)
            ) {
                Text(uiStr(R.string.ui_select_this_folder), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    } else {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                uiStr(R.string.ui_show_all_files),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Switch(
                checked = state.showAll,
                onCheckedChange = { state.showAll = it },
                colors = accentSwitchColors()
            )
        }
        Spacer(Modifier.size(10.dp))
        OutlinedButton(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth(),
            shape = WinZShapes.Medium
        ) {
            Text(uiStr(R.string.cancel))
        }
    }
}

@Composable
private fun PickerRow(entry: PickerEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (entry.isDirectory) TablerIcons.Folder else TablerIcons.FileDescription,
            null,
            modifier = Modifier.size(28.dp),
            tint = if (entry.isDirectory) controlAccentColor() else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.file.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis
            )
            val details = remember(entry.file.absolutePath) { entryDetails(entry) }
            if (details.isNotEmpty()) {
                Text(
                    details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun entryDetails(entry: PickerEntry): String {
    val modified = entry.file.lastModified()
    val date = if (modified <= 0) "" else
        SimpleDateFormat("MMM d, yyyy  h:mm a", Locale.getDefault()).format(Date(modified))
    if (entry.isDirectory) return date
    val size = formatPickerSize(entry.file.length())
    return if (date.isEmpty()) size else "$size  •  $date"
}

private fun formatPickerSize(size: Long): String {
    if (size < 1024) return "$size B"
    val z = (63 - java.lang.Long.numberOfLeadingZeros(size)) / 10
    return String.format(Locale.getDefault(), "%.1f %sB", size / (1L shl (z * 10)).toDouble(), " KMGTPE"[z])
}
