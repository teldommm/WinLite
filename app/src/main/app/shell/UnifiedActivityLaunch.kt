package com.winlator.cmod.app.shell

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.res.Configuration
import android.hardware.input.InputManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.winlator.cmod.BuildConfig
import com.winlator.cmod.R
import com.winlator.cmod.app.PluviaApp
import com.winlator.cmod.app.db.PluviaDatabase
import com.winlator.cmod.app.service.DownloadService
import com.winlator.cmod.app.service.download.DownloadCoordinator
import com.winlator.cmod.app.update.UpdateChecker
import com.winlator.cmod.feature.settings.InputControlsFragment
import com.winlator.cmod.feature.settings.SettingsFocusZone
import com.winlator.cmod.feature.settings.SettingsHost
import com.winlator.cmod.feature.settings.SettingsNavBridge
import com.winlator.cmod.feature.settings.SettingsNavItem
import com.winlator.cmod.feature.setup.SetupWizardActivity
import com.winlator.cmod.feature.shortcuts.LibraryShortcutUtils
import com.winlator.cmod.feature.shortcuts.LibraryShortcutArtwork
import com.winlator.cmod.feature.shortcuts.ShortcutBroadcastReceiver
import com.winlator.cmod.feature.shortcuts.ShortcutSettingsComposeDialog
import com.winlator.cmod.feature.shortcuts.ShortcutsFragment
import com.winlator.cmod.feature.stores.common.StoreArtworkCache
import com.winlator.cmod.feature.stores.steam.SteamLoginActivity
import com.winlator.cmod.feature.stores.steam.data.DepotInfo
import com.winlator.cmod.feature.stores.steam.data.DownloadInfo
import com.winlator.cmod.feature.stores.steam.data.SteamApp
import com.winlator.cmod.feature.stores.steam.enums.DownloadPhase
import com.winlator.cmod.feature.stores.steam.events.AndroidEvent
import com.winlator.cmod.feature.stores.steam.events.EventDispatcher
import com.winlator.cmod.feature.stores.steam.service.SteamService
import com.winlator.cmod.feature.stores.steam.utils.PrefManager
import com.winlator.cmod.feature.stores.steam.utils.getAvatarURL
import com.winlator.cmod.feature.sync.CloudSyncHelper
import com.winlator.cmod.feature.sync.google.CloudSyncManager
import com.winlator.cmod.feature.sync.google.GameSaveBackupManager
import com.winlator.cmod.feature.sync.ui.CloudSavesContent
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.container.Shortcut
import com.winlator.cmod.runtime.display.XServerDisplayActivity
import com.winlator.cmod.runtime.display.environment.ImageFs
import com.winlator.cmod.runtime.input.ControllerHelper
import com.winlator.cmod.runtime.wine.PeIconExtractor
import com.winlator.cmod.shared.android.ActivityResultHost
import com.winlator.cmod.shared.android.AppTerminationHelper
import com.winlator.cmod.shared.android.DirectoryPickerDialog
import com.winlator.cmod.shared.android.FixedFontScaleAppCompatActivity
import com.winlator.cmod.shared.android.RefreshRateUtils
import com.winlator.cmod.shared.io.StorageUtils
import com.winlator.cmod.shared.io.FileUtils
import com.winlator.cmod.shared.ui.CarouselView
import com.winlator.cmod.shared.ui.dialog.PopupDialog
import com.winlator.cmod.shared.ui.dialog.PopupTextAction
import androidx.compose.foundation.focusGroup
import com.winlator.cmod.shared.ui.focus.controllerFocusGlow
import com.winlator.cmod.shared.ui.focus.controllerMenuInput
import com.winlator.cmod.shared.ui.focus.controllerTextFieldEscape
import com.winlator.cmod.shared.ui.nav.DialogPaneNav
import com.winlator.cmod.shared.ui.nav.LocalPaneNav
import com.winlator.cmod.shared.ui.nav.PANE_DIR_ACTIVATE
import com.winlator.cmod.shared.ui.nav.PANE_DIR_DOWN
import com.winlator.cmod.shared.ui.nav.PANE_DIR_LEFT
import com.winlator.cmod.shared.ui.nav.PANE_DIR_RIGHT
import com.winlator.cmod.shared.ui.nav.PANE_DIR_SECONDARY
import com.winlator.cmod.shared.ui.nav.PANE_DIR_UP
import com.winlator.cmod.shared.ui.nav.PaneNavRegistry
import com.winlator.cmod.shared.ui.nav.paneNavItem
import com.winlator.cmod.shared.ui.FourByTwoGridView
import com.winlator.cmod.shared.ui.JoystickGridScroll
import com.winlator.cmod.shared.ui.JoystickListScroll
import com.winlator.cmod.shared.ui.ListView
import com.winlator.cmod.shared.ui.widget.chasingBorder
import com.winlator.cmod.shared.theme.WinNativeTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.Lazy
import com.winlator.cmod.feature.stores.steam.enums.EPersonaState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

// Game launching (Steam/custom) + wine command builders, split out of UnifiedActivity.kt (behavior-identical).

// Game launch with drive-aware mapping
internal fun UnifiedActivity.launchSteamGame(
    context: android.content.Context,
    containerManager: ContainerManager,
    app: SteamApp,
    joinConnect: String? = null,
) {
    lifecycleScope.launch(Dispatchers.IO) {
        val gameInstallPath = SteamService.getAppDirPath(app.id)
        val gameDir = java.io.File(gameInstallPath)
        if (!gameDir.exists()) {
            withContext(Dispatchers.Main) {
                com.winlator.cmod.shared.ui.toast.WinToast.show(
                    context,
                    "Game not installed: ${app.name}",
                    android.widget.Toast.LENGTH_SHORT,
                )
            }
            return@launch
        }

        val shortcut =
            containerManager.loadShortcuts().find {
                it.getExtra("game_source") == "STEAM" && it.getExtra("app_id") == app.id.toString()
            }
        val detectedLaunchExecutable = SteamService.getInstalledExe(app.id)

        if (shortcut != null) {
            if (!SetupWizardActivity.isContainerUsable(context, shortcut.container)) {
                withContext(Dispatchers.Main) {
                    SetupWizardActivity.promptToInstallWineOrCreateContainer(
                        context,
                        shortcut.container.wineVersion,
                    )
                }
                return@launch
            }
            normalizeContainerDrives(shortcut.container)
            shortcut.putExtra("game_source", "STEAM")
            shortcut.putExtra("game_install_path", gameInstallPath)
            val existingLaunchExecutable = shortcut.getExtra("launch_exe_path")
            if (existingLaunchExecutable.isNullOrBlank() && detectedLaunchExecutable.isNotBlank()) {
                shortcut.putExtra("launch_exe_path", detectedLaunchExecutable)
            }
            val loaderExec = "wine \"C:\\\\Program Files (x86)\\\\Steam\\\\steamclient_loader_x64.exe\""
            val lines =
                com.winlator.cmod.shared.io.FileUtils
                    .readLines(shortcut.file)
            val rewritten = StringBuilder()
            var execUpdated = false
            for (line in lines) {
                if (line.startsWith("Exec=")) {
                    rewritten.append("Exec=").append(loaderExec).append("\n")
                    execUpdated = true
                } else {
                    rewritten.append(line).append("\n")
                }
            }
            if (!execUpdated) {
                rewritten.append("Exec=").append(loaderExec).append("\n")
            }
            com.winlator.cmod.shared.io.FileUtils
                .writeString(shortcut.file, rewritten.toString())
            shortcut.saveData()
            val intent = Intent(context, XServerDisplayActivity::class.java)
            intent.putExtra("container_id", shortcut.container.id)
            intent.putExtra("shortcut_path", shortcut.file.path)
            intent.putExtra("shortcut_name", shortcut.name)
            if (!joinConnect.isNullOrBlank()) intent.putExtra("steam_join_connect", joinConnect)
            withContext(Dispatchers.Main) {
                launchGame(context, intent)
            }
        } else {
            val container = SetupWizardActivity.getPreferredGameContainer(context, containerManager)

            if (container == null) {
                withContext(Dispatchers.Main) {
                    SetupWizardActivity.promptToInstallWineOrCreateContainer(context)
                }
                return@launch
            }

            normalizeContainerDrives(container)

            val execPath = "wine \"C:\\\\Program Files (x86)\\\\Steam\\\\steamclient_loader_x64.exe\""

            // Generate a shortcut dynamically
            val desktopDir = container.getDesktopDir()
            if (!desktopDir.exists()) desktopDir.mkdirs()
            val shortcutFile = java.io.File(desktopDir, "${app.name.replace("/", "_")}.desktop")
            val content = java.lang.StringBuilder()
            content.append("[Desktop Entry]\n")
            content.append("Type=Application\n")
            content.append("Name=${app.name}\n")
            content.append("Exec=$execPath\n")
            content.append("Icon=steam_icon_${app.id}\n")
            content.append("\n[Extra Data]\n")
            content.append("game_source=STEAM\n")
            content.append("app_id=${app.id}\n")
            content.append("container_id=${container.id}\n")
            content.append("game_install_path=${gameInstallPath}\n")
            content.append("launch_exe_path=${detectedLaunchExecutable}\n")
            content.append("use_container_defaults=1\n")

            com.winlator.cmod.shared.io.FileUtils
                .writeString(shortcutFile, content.toString())

            container.saveData()

            val intent = Intent(context, XServerDisplayActivity::class.java)
            intent.putExtra("container_id", container.id)
            intent.putExtra("shortcut_path", shortcutFile.path)
            intent.putExtra("shortcut_name", app.name)
            if (!joinConnect.isNullOrBlank()) intent.putExtra("steam_join_connect", joinConnect)
            withContext(Dispatchers.Main) {
                launchGame(context, intent)
            }
        }
    }
}


internal fun UnifiedActivity.normalizeContainerDrives(container: com.winlator.cmod.runtime.container.Container) {
    container.drives =
        com.winlator.cmod.runtime.wine.WineUtils.normalizePersistentDrives(
            this,
            container.drives ?: com.winlator.cmod.runtime.container.Container.DEFAULT_DRIVES,
            false,
        )
}

internal fun UnifiedActivity.resolveShortcutLaunchContainer(
    containerManager: ContainerManager,
    shortcut: Shortcut,
): com.winlator.cmod.runtime.container.Container? {
    val overrideContainerId = shortcut.getExtra("container_id").toIntOrNull()?.takeIf { it > 0 }
    return overrideContainerId
        ?.let { containerManager.getContainerById(it) }
        ?: shortcut.container
}

internal fun UnifiedActivity.ensureShortcutFileInContainer(
    shortcut: Shortcut,
    targetContainer: com.winlator.cmod.runtime.container.Container,
): java.io.File {
    val targetDesktopDir = targetContainer.getDesktopDir()
    val alreadyInTarget =
        runCatching {
            shortcut.file.parentFile?.canonicalFile == targetDesktopDir.canonicalFile
        }.getOrDefault(false)

    if (alreadyInTarget) return shortcut.file

    if (!targetDesktopDir.exists()) targetDesktopDir.mkdirs()
    shortcut.putExtra("container_id", targetContainer.id.toString())
    shortcut.saveData()

    val targetFile = java.io.File(targetDesktopDir, shortcut.file.name)
    runCatching {
        com.winlator.cmod.shared.io.FileUtils.copy(shortcut.file, targetFile)
        val lnkFileName = shortcut.file.name.substringBeforeLast(".desktop") + ".lnk"
        val oldLnkFile = java.io.File(shortcut.file.parentFile, lnkFileName)
        if (oldLnkFile.exists()) {
            com.winlator.cmod.shared.io.FileUtils.copy(oldLnkFile, java.io.File(targetDesktopDir, lnkFileName))
            oldLnkFile.delete()
        }
        shortcut.file.delete()
    }.onFailure {
        Log.w("UnifiedActivity", "Failed to move shortcut ${shortcut.file.name} to container ${targetContainer.id}; launching original file", it)
        return shortcut.file
    }

    return targetFile
}

internal fun UnifiedActivity.buildStoreWineExecCommand(
    container: com.winlator.cmod.runtime.container.Container?,
    source: String,
    gameInstallPath: String,
    exeFile: java.io.File,
): String {
    val windowsPath =
        container?.let {
            com.winlator.cmod.runtime.wine.WineUtils.getDriveCGameWindowsPath(
                it,
                source,
                gameInstallPath,
                exeFile.absolutePath,
            )
        } ?: run {
            val relativePath =
                try {
                    exeFile.relativeTo(java.io.File(gameInstallPath)).path.replace("/", "\\")
                } catch (_: Exception) {
                    exeFile.name
                }
            val linkName =
                com.winlator.cmod.runtime.wine.WineUtils.getDriveCGameLinkName(gameInstallPath)
            "C:\\WinNative\\Games\\$source\\$linkName\\$relativePath"
    }
    return "wine \"$windowsPath\""
}

internal fun UnifiedActivity.buildStoreWineExecCommandForSelectedExe(
    container: com.winlator.cmod.runtime.container.Container?,
    source: String,
    gameInstallPath: String,
    selectedExePath: String?,
): String? {
    if (selectedExePath.isNullOrBlank()) return null

    val selectedExe = java.io.File(selectedExePath)
    if (!selectedExe.isFile) return null

    val normalizedBaseDir =
        java.io
            .File(gameInstallPath)
            .absolutePath
            .removeSuffix("/")
    val normalizedExePath = selectedExe.absolutePath
    return if (normalizedExePath == normalizedBaseDir || normalizedExePath.startsWith("$normalizedBaseDir/")) {
        buildStoreWineExecCommand(container, source, gameInstallPath, selectedExe)
    } else {
        val hostPath = normalizedExePath.replace("/", "\\\\").let { if (it.startsWith("\\")) it else "\\$it" }
        "wine \"Z:${hostPath}\""
    }
}

// Launch custom game by shortcut name
internal fun UnifiedActivity.launchCustomGame(
    context: android.content.Context,
    containerManager: ContainerManager,
    gameName: String,
) {
    lifecycleScope.launch(Dispatchers.IO) {
        val allShortcuts = containerManager.loadShortcuts()

        // Try matching by app_id (for non-official Steam), custom_name, or filename
        var shortcut =
            allShortcuts.find { it.getExtra("app_id") == gameName }
                ?: allShortcuts.find { it.getExtra("custom_name") == gameName }
                ?: allShortcuts.find { it.name == gameName }
                ?: allShortcuts.find { it.name == gameName.replace("/", "_").replace("\\", "_") }

        // If still not found, try matching by looking at the safe filename directly
        if (shortcut == null) {
            val safeName = gameName.replace("/", "_").replace("\\", "_")
            for (container in containerManager.containers) {
                val desktopFile = java.io.File(container.getDesktopDir(), "$safeName.desktop")
                if (desktopFile.exists()) {
                    shortcut =
                        com.winlator.cmod.runtime.container
                            .Shortcut(container, desktopFile)
                    break
                }
            }
        }

        if (shortcut == null) {
            withContext(Dispatchers.Main) {
                com.winlator.cmod.shared.ui.toast.WinToast.show(
                    context,
                    "Custom game shortcut not found: $gameName",
                    android.widget.Toast.LENGTH_SHORT,
                )
            }
            return@launch
        }

        // Backfill custom_name if missing (legacy shortcuts)
        if (shortcut.getExtra("custom_name").isEmpty()) {
            shortcut.putExtra("custom_name", gameName)
            shortcut.saveData()
        }

        // Refresh storage-root mappings; custom game paths launch through the drive_c game symlink.
        val gameFolder = shortcut.getExtra("custom_game_folder", "")
        if (gameFolder.isNotEmpty()) {
            normalizeContainerDrives(shortcut.container)
            shortcut.container.saveData()
        }
        val intent = Intent(context, XServerDisplayActivity::class.java)
        intent.putExtra("container_id", shortcut.container.id)
        intent.putExtra("shortcut_path", shortcut.file.path)
        intent.putExtra("shortcut_name", gameName)
        withContext(Dispatchers.Main) {
            launchGame(context, intent)
        }
    }
}

internal fun UnifiedActivity.launchGame(
    context: android.content.Context,
    intent: Intent,
) {
    DownloadService.clearCompletedDownloads()
    context.startActivity(intent)
    // Suppress the default activity transition so the preloader stays seamless
    if (context is android.app.Activity) {
        com.winlator.cmod.shared.android.AppUtils
            .applyOpenActivityTransition(context, 0, 0)
    }
}

internal fun UnifiedActivity.findGameExe(dir: java.io.File): java.io.File? {
    // BFS: check each directory level fully before going deeper
    val exclusions =
        listOf(
            "unins",
            "redist",
            "setup",
            "dotnet",
            "vcredist",
            "dxsetup",
            "helper",
            "crash",
            "ue4prereq",
            "dxwebsetup",
            "launcher",
        )

    var currentDirs = listOf(dir)
    var depth = 0
    var fallbackExe: java.io.File? = null

    while (currentDirs.isNotEmpty() && depth <= 4) {
        val nextDirs = mutableListOf<java.io.File>()
        val candidates = mutableListOf<java.io.File>()

        for (d in currentDirs) {
            val children = d.listFiles() ?: continue
            for (f in children) {
                if (f.isDirectory) {
                    nextDirs.add(f)
                } else if (f.extension.equals("exe", ignoreCase = true)) {
                    val name = f.name.lowercase()
                    if (exclusions.none { name.contains(it) }) {
                        candidates.add(f)
                    }
                }
            }
        }

        // Prefer 64-bit executable candidates at the current depth
        val exe64 =
            candidates.find {
                it.name.lowercase().contains("64") ||
                    it.parentFile
                        ?.name
                        ?.lowercase()
                        ?.contains("64") == true
            }
        if (exe64 != null) return exe64

        // Collect the first valid candidate as a fallback
        if (fallbackExe == null && candidates.isNotEmpty()) {
            fallbackExe = candidates.first()
        }

        currentDirs = nextDirs
        depth++
    }
    return fallbackExe
}
