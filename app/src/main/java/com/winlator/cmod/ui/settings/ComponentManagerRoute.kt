package com.winlator.cmod.ui.settings

import android.content.Context
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.winlator.cmod.ComponentCatalogController
import com.winlator.cmod.R
import com.winlator.cmod.core.WineInfo
import com.winlator.cmod.core.WineRuntimeGuard
import com.winlator.cmod.ui.filemanager.FilePickerDialog
import com.winlator.cmod.ui.onboarding.ComponentManagerContent
import com.winlator.cmod.ui.onboarding.OnboardingComposeHost
import com.winlator.cmod.ui.theme.findActivity
import com.winlator.cmod.ui.uiStr

// Components (catalog/install/remove) as a MainShell detail entry. The logic is
// ComponentCatalogController, shared with OnboardingActivity's first-run flow; this route is its
// Host. The UI is ComponentManagerContent — rendered directly in the shell's composition (it
// used to be a separate ComposeView embedded through AndroidView, with its own theme root).
@Composable
fun ComponentManagerRoute(onClose: () -> Unit) {
    val activity = LocalContext.current.findActivity() as AppCompatActivity
    val currentOnClose by rememberUpdatedState(onClose)
    val alive = remember { booleanArrayOf(true) }

    val catalog = remember {
        ComponentCatalogController(object : ComponentCatalogController.Host {
            override fun context(): Context = activity
            override fun hostActivity(): AppCompatActivity = activity
            override fun isAlive(): Boolean = alive[0]
            override fun runOnUi(action: Runnable) = activity.runOnUiThread(action)
            override fun close() = currentOnClose()
        })
    }

    // Same order the fragment used: initialize → UI state → callbacks → attach; start() once
    // the screen is composed (DisposableEffect below).
    val ui = remember {
        catalog.initialize(null, null, Int.MIN_VALUE)
        OnboardingComposeHost.createController(
            catalog.isCoreReady,
            catalog.coreProgress,
            WineRuntimeGuard.isBundledMainInstalled(activity),
            WineRuntimeGuard.isInUse(activity, WineInfo.MAIN_WINE_VERSION.identifier())
        ).also { catalog.attachComposeController(it) }
    }
    val callbacks = remember {
        catalog.createCallbacks(object : ComponentCatalogController.ExtraCallbacks {
            override fun onBrowseLocal() = browseLocalPackage(activity, catalog)
        })
    }

    DisposableEffect(catalog) {
        catalog.start()
        onDispose {
            alive[0] = false
            catalog.shutdown()
        }
    }

    ComponentManagerContent(ui, callbacks)
}

// Component (.wcp) and driver (.zip) packages. ComponentCatalogController decides what a picked
// file is from its first bytes, not its name, so "Show all files" in the picker covers a package
// that was saved under some other extension.
private val LOCAL_PACKAGE_EXTENSIONS = listOf("wcp", "zip", "xz", "zst", "tzst", "tar")

private fun browseLocalPackage(activity: AppCompatActivity, catalog: ComponentCatalogController) {
    FilePickerDialog.show(activity, uiStr(R.string.ui_select_component_package), LOCAL_PACKAGE_EXTENSIONS, null) { file ->
        catalog.handleLocalPackagePicked(Uri.fromFile(file))
    }
}
