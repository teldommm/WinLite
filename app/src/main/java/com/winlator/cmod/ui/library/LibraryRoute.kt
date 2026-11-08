package com.winlator.cmod.ui.library

import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.winlator.cmod.R
import com.winlator.cmod.ui.filemanager.FilePickerDialog
import com.winlator.cmod.ui.theme.findActivity
import com.winlator.cmod.ui.uiStr

// Library as a plain composable tab of MainShell (replaces ShortcutsFragment). The logic is in
// LibraryScreenController; this route only wires it to the composition:
//  - the image picker for a game's icon / cover (the app's own FilePickerDialog, not the system
//    picker);
//  - reload on every resume (was onViewCreated + onResume — back from a game, from the
//    Containers/Components activities, …);
//  - reload when MainShell re-shows the tab or asks for a refresh (shownSerial), e.g. after
//    File Manager added a game or GameDetail edited a shortcut.
@Composable
fun LibraryRoute(shownSerial: Int, active: Boolean, detailOpen: Boolean = false) {
    val activity = LocalContext.current.findActivity() as AppCompatActivity
    val controller = remember { LibraryScreenController(activity) }

    val imagePickerTitle = uiStr(R.string.ui_select_image)
    SideEffect {
        controller.setImagePickerLauncher {
            // The picked file arrives as a file:// Uri, which ArtworkRepository reads like any other.
            FilePickerDialog.showImage(activity, imagePickerTitle, null) { file ->
                controller.onImagePicked(Uri.fromFile(file))
            }
        }
    }

    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }

    LifecycleResumeEffect(controller) {
        controller.loadShortcutsList()
        onPauseOrDispose { }
    }
    // Leaving the tab closes an open search and clears its filter (the tab itself persists).
    LaunchedEffect(active) {
        if (!active) controller.libraryController.closeSearch()
    }

    LaunchedEffect(shownSerial) {
        if (shownSerial > 0) controller.loadShortcutsList()
    }

    // MainShell keeps every tab composed (faded out, or covered by a detail screen), so artwork
    // may only move while this tab is the visible one and nothing sits on top of it. Otherwise it
    // is paused - frozen on its frame, not reset (a reset snapped visibly under the fading-in page).
    val motionEnabled = rememberArtworkMotionEnabled()
    CompositionLocalProvider(
        LocalArtworkMotion provides motionEnabled,
        LocalArtworkMotionPaused provides (!active || detailOpen)
    ) {
        LibraryContent(controller.libraryController, controller.callbacks)
    }
}
