package com.winlator.cmod.ui.filemanager

import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.winlator.cmod.ui.theme.findActivity
import com.winlator.cmod.ui.theme.FadeInOnShow

// File Manager as a plain composable tab of MainShell (replaces FileManagerFragment). It is now
// a tab in both orientations — Library's "Add" navigates to it instead of pushing a second
// File Manager instance onto the detail back stack in portrait.
@Composable
fun FileManagerRoute(shownSerial: Int) {
    val activity = LocalContext.current.findActivity() as AppCompatActivity
    var model by remember { mutableStateOf<FileManagerModel?>(null) }
    val controller = remember { FileManagerController(activity) { model = it } }
    val callbacks = remember(controller) { controller.createCallbacks() }

    DisposableEffect(controller) {
        controller.setActive(true)
        controller.refresh()
        onDispose { controller.setActive(false) }
    }
    LaunchedEffect(shownSerial) {
        if (shownSerial > 0) controller.refresh()
    }

    val current = model
    if (current != null) {
        FadeInOnShow { FileManagerScreen(current, callbacks) }
    } else {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
    }
}
