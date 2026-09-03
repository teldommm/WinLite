package com.winlator.cmod.app.shell
import com.winlator.cmod.app.shell.UnifiedActivity.GameSettingsActionItem
import com.winlator.cmod.app.shell.UnifiedActivity.GameSettingsScreen
import com.winlator.cmod.app.shell.UnifiedActivity.HeroBootChoice
import com.winlator.cmod.app.shell.UnifiedActivity.HomeShortcutUiState

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
import com.winlator.cmod.shared.theme.WinLiteTheme
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

// Game settings/detail dialogs, split out of UnifiedActivity.kt (behavior-identical).

@Composable
internal fun UnifiedActivity.GameSettingsDialogFrame(
    title: String,
    onDismissRequest: () -> Unit,
    wide: Boolean = false,
    contentKey: Any? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val registry = remember { PaneNavRegistry() }
    LaunchedEffect(contentKey) { registry.reset() }
    Dialog(
        onDismissRequest = onDismissRequest,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
      CompositionLocalProvider(LocalPaneNav provides registry) {
        DialogPaneNav(registry, onDismiss = onDismissRequest)
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.navigationBars),
            contentAlignment = Alignment.Center,
        ) {
            val widthModifier =
                if (wide) {
                    Modifier.widthIn(min = 320.dp, max = (maxWidth - 32.dp).coerceAtMost(560.dp))
                } else {
                    Modifier.widthIn(min = 200.dp, max = 280.dp)
                }
            val maxContentHeight = (maxHeight - 48.dp).coerceAtLeast(320.dp)
            Surface(
                modifier = widthModifier.heightIn(max = maxContentHeight),
                shape = RoundedCornerShape(14.dp),
                color = CardDark,
                border = BorderStroke(1.dp, CardBorder),
                tonalElevation = 8.dp,
            ) {
                Column(
                    modifier =
                        Modifier
                            .padding(vertical = 6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = title,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(
                            onClick = onDismissRequest,
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .size(34.dp)
                                .paneNavItem(cornerRadius = 17.dp, onActivate = onDismissRequest, pinTop = true),
                        ) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.common_ui_close),
                                tint = TextSecondary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    HorizontalDivider(color = CardBorder, thickness = 0.5.dp)
                    Column(
                        modifier =
                            Modifier
                                .weight(1f, fill = false)
                                .verticalScroll(rememberScrollState()),
                    ) {
                        content()
                    }
                }
            }
        }
      }
    }
}

@Composable
internal fun UnifiedActivity.GameSettingsActionGrid(
    actions: List<GameSettingsActionItem>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        actions.forEachIndexed { index, action ->
            if (index > 0) {
                HorizontalDivider(
                    color = CardBorder.copy(alpha = 0.5f),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            GameSettingsActionCard(action = action, isEntry = index == 0)
        }
    }
}

@Composable
internal fun UnifiedActivity.GameSettingsActionCard(
    action: GameSettingsActionItem,
    modifier: Modifier = Modifier,
    isEntry: Boolean = false,
) {
    val isDanger = action.accentColor == DangerRed
    val iconColor = if (isDanger) DangerRed else TextSecondary
    val textColor = if (isDanger) DangerRed else TextPrimary

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "actionCardScale",
    )
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.paneNavItem(cornerRadius = 0.dp, onActivate = action.onClick, isEntry = isEntry)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = action.onClick,
                ).padding(horizontal = 16.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = action.icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = action.title,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
internal fun UnifiedActivity.GameSettingsInfoCard(
    message: String,
    accentColor: Color = Accent,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Warning,
            contentDescription = null,
            tint = accentColor.copy(alpha = 0.7f),
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** A single label/value stat line used on the Stats tab (playtime, plays, last played, size). */
@Composable
internal fun UnifiedActivity.GameSettingsStatRow(
    icon: ImageVector,
    label: String,
    value: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Shared uninstall/remove confirmation UI, used by GameSettingsDialog.
 */
@Composable
internal fun UnifiedActivity.UninstallConfirmation(
    message: String,
    confirmLabel: String = stringResource(R.string.common_ui_uninstall),
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    var isUninstalling by remember { mutableStateOf(false) }

    GameSettingsInfoCard(message = message, accentColor = DangerRed)

    if (isUninstalling) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = DangerRed)
        }
    } else {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = {
                    isUninstalling = true
                    onConfirm()
                },
                modifier = Modifier.paneNavItem(
                    cornerRadius = 8.dp,
                    onActivate = { isUninstalling = true; onConfirm() },
                    isEntry = true,
                ),
                border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed),
            ) {
                Text(
                    confirmLabel,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = onCancel,
                modifier = Modifier.paneNavItem(cornerRadius = 8.dp, onActivate = onCancel),
            ) {
                Text(stringResource(R.string.common_ui_cancel), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun UnifiedActivity.ShortcutRemovalConfirmation(
    message: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    var isRemoving by remember { mutableStateOf(false) }

    GameSettingsInfoCard(message = message, accentColor = DangerRed)

    if (isRemoving) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = DangerRed)
        }
    } else {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = {
                    isRemoving = true
                    onConfirm()
                },
                modifier = Modifier.paneNavItem(
                    cornerRadius = 8.dp,
                    onActivate = { isRemoving = true; onConfirm() },
                    isEntry = true,
                ),
                border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed),
            ) {
                Text(
                    stringResource(R.string.common_ui_remove),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = onCancel,
                modifier = Modifier.paneNavItem(cornerRadius = 8.dp, onActivate = onCancel),
            ) {
                Text(stringResource(R.string.common_ui_cancel), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun UnifiedActivity.HeroLaunchConfirmFooter(
    onCancel: () -> Unit,
    onContinue: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PaneFooterAction(
            label = stringResource(R.string.common_ui_cancel),
            textColor = DangerRed,
            onClick = onCancel,
        )
        PaneFooterAction(
            label = stringResource(R.string.common_ui_continue),
            textColor = StatusOnline,
            onClick = onContinue,
            isEntry = true,
        )
    }
}

@Composable
internal fun UnifiedActivity.PaneFooterAction(
    label: String,
    textColor: Color,
    onClick: () -> Unit,
    isEntry: Boolean = false,
) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .paneNavItem(
                    cornerRadius = 8.dp,
                    onActivate = onClick,
                    tapToSelect = true,
                    isEntry = isEntry,
                ).padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
internal fun UnifiedActivity.HeroBootDialog(
    onConfirm: (HeroBootChoice) -> Unit,
    onDismissRequest: () -> Unit,
) {
    var choice by remember { mutableStateOf(HeroBootChoice.Desktop) }
    val graphicsTest = stringResource(R.string.hero_graphics_tests_title)
    val inputTest = stringResource(R.string.hero_input_tests_title)
    val bits32 = stringResource(R.string.hero_graphics_test_32)
    val bits64 = stringResource(R.string.hero_graphics_test_64)
    val test32 = "$graphicsTest $bits32"
    val test64 = "$graphicsTest $bits64"
    val input32 = "$inputTest $bits32"
    val input64 = "$inputTest $bits64"
    val title =
        when (choice) {
            HeroBootChoice.Desktop -> stringResource(R.string.hero_boot_to_desktop_title)
            HeroBootChoice.Cube32 -> test32
            HeroBootChoice.Cube64 -> test64
            HeroBootChoice.Input32 -> input32
            HeroBootChoice.Input64 -> input64
        }
    val registry = remember { PaneNavRegistry() }
    Dialog(onDismissRequest = onDismissRequest) {
      CompositionLocalProvider(LocalPaneNav provides registry) {
        DialogPaneNav(registry, onDismiss = onDismissRequest, onStart = { onConfirm(choice) })
        PopupDialog(
            title = title,
            icon = Icons.Outlined.DesktopWindows,
            accentColor = Accent,
            modifier = Modifier.widthIn(min = 220.dp, max = 290.dp),
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    HeroBootOptionRow(
                        label = stringResource(R.string.hero_boot_to_desktop_title),
                        selected = choice == HeroBootChoice.Desktop,
                        onClick = { choice = HeroBootChoice.Desktop },
                    )
                    HeroBootOptionRow(
                        label = test32,
                        selected = choice == HeroBootChoice.Cube32,
                        onClick = { choice = HeroBootChoice.Cube32 },
                    )
                    HeroBootOptionRow(
                        label = test64,
                        selected = choice == HeroBootChoice.Cube64,
                        onClick = { choice = HeroBootChoice.Cube64 },
                    )
                    HeroBootOptionRow(
                        label = input32,
                        selected = choice == HeroBootChoice.Input32,
                        onClick = { choice = HeroBootChoice.Input32 },
                    )
                    HeroBootOptionRow(
                        label = input64,
                        selected = choice == HeroBootChoice.Input64,
                        onClick = { choice = HeroBootChoice.Input64 },
                    )
                }
            },
            footer = {
                HeroLaunchConfirmFooter(onCancel = onDismissRequest, onContinue = { onConfirm(choice) })
            },
        )
      }
    }
}

@Composable
internal fun UnifiedActivity.HeroBootOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val glassBlue = Accent
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(glassBlue.copy(alpha = if (selected) 0.26f else 0.05f))
            .border(1.dp, glassBlue.copy(alpha = if (selected) 0.65f else 0.12f), RoundedCornerShape(8.dp))
            .paneNavItem(cornerRadius = 8.dp, onActivate = onClick, tapToSelect = true)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else glassBlue.copy(alpha = 0.5f),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun UnifiedActivity.HeroRemoveShortcutDialog(
    gameName: String,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val registry = remember { PaneNavRegistry() }
    var isRemoving by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismissRequest) {
      CompositionLocalProvider(LocalPaneNav provides registry) {
        DialogPaneNav(registry, onDismiss = onDismissRequest)
        PopupDialog(
            title = stringResource(R.string.common_ui_shortcut),
            message = stringResource(R.string.shortcuts_list_remove_game_shortcut_message, gameName),
            icon = Icons.Outlined.Home,
            accentColor = DangerRed,
            confirmButtonColor = DangerRed,
            progressLabel = stringResource(R.string.common_ui_working),
            modifier = Modifier.widthIn(min = 280.dp, max = 360.dp),
            footer = {
                if (isRemoving) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(color = DangerRed, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        Text(
                            stringResource(R.string.common_ui_working),
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PaneFooterAction(
                            label = stringResource(R.string.common_ui_cancel),
                            textColor = TextSecondary,
                            onClick = onDismissRequest,
                        )
                        PaneFooterAction(
                            label = stringResource(R.string.common_ui_remove),
                            textColor = DangerRed,
                            onClick = {
                                isRemoving = true
                                onConfirm()
                            },
                            isEntry = true,
                        )
                    }
                }
            },
        )
      }
    }
}

private fun formatLibraryPlaytime(playtimeMillis: Long): String {
    val totalMinutes = (playtimeMillis / 60000L).coerceAtLeast(1L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0L && minutes > 0L -> "${hours}h ${minutes}m"
        hours > 0L -> "${hours}h"
        else -> "${minutes}m"
    }
}

private fun formatLibraryLastPlayed(lastPlayedMillis: Long): String =
    java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(lastPlayedMillis))

@Composable
internal fun UnifiedActivity.GameSettingsDialog(
    app: SteamApp,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    var currentTab by remember { mutableStateOf(GameSettingsScreen.Menu) }
    val scope = rememberCoroutineScope()
    val isCustom = app.id < 0
    var shortcutRefreshKey by remember(app.id, isCustom) { mutableStateOf(0) }
    var pinnedShortcutOverride by remember(app.id, isCustom) { mutableStateOf<Boolean?>(null) }
    val currentRefreshSignal = this@GameSettingsDialog.libraryRefreshSignal
    val homeShortcutState by produceState(
        HomeShortcutUiState(),
        app.id,
        isCustom,
        currentRefreshSignal,
        shortcutRefreshKey,
    ) {
        value =
            withContext(Dispatchers.IO) {
                val shortcut = findLibraryShortcutForGame(ContainerManager(context), app, isCustom)
                HomeShortcutUiState(
                    shortcut = shortcut,
                    isPinned = shortcut?.let { LibraryShortcutUtils.hasPinnedHomeShortcut(context, it) } == true,
                )
            }
    }
    val artworkRefreshListener =
        remember(app.id, isCustom) {
            object : EventDispatcher.JavaEventListener {
                override fun onEvent(event: Any) {
                    if (event is AndroidEvent.LibraryArtworkChanged) {
                        shortcutRefreshKey++
                    }
                }
            }
        }
    DisposableEffect(artworkRefreshListener) {
        PluviaApp.events.onJava(AndroidEvent.LibraryArtworkChanged::class, artworkRefreshListener)
        onDispose {
            PluviaApp.events.offJava(AndroidEvent.LibraryArtworkChanged::class, artworkRefreshListener)
        }
    }
    val hasPinnedShortcut = pinnedShortcutOverride ?: homeShortcutState.isPinned

    // Stats + achievements (folded into the "Stats" tab below).
    var showAchievements by remember(app.id) { mutableStateOf(false) }
    var showBootDialog by remember(app.id) { mutableStateOf(false) }
    var bootShortcut by remember(app.id) { mutableStateOf<com.winlator.cmod.runtime.container.Shortcut?>(null) }
    val playtimePrefs =
        remember {
            context.getSharedPreferences("playtime_stats", android.content.Context.MODE_PRIVATE)
        }
    val statsSearchKey =
        remember(app) {
            if (isCustom) {
                app.name
            } else {
                app.name.replace(LIBRARY_NAME_SANITIZE_REGEX, "")
            }
        }
    val lastPlayed = playtimePrefs.getLong("${statsSearchKey}_last_played", 0L)
    val totalPlaytime = playtimePrefs.getLong("${statsSearchKey}_playtime", 0L)
    val playCount = playtimePrefs.getInt("${statsSearchKey}_play_count", 0)
    val installPath =
        remember(app) {
            if (isCustom) {
                app.gameDir
            } else {
                try {
                    SteamService.getAppDirPath(app.id)
                } catch (_: Exception) {
                    ""
                }
            }
        }
    val installSizeText by produceState<String?>(initialValue = null, key1 = installPath) {
        value =
            if (installPath.isNotBlank()) {
                withContext(Dispatchers.IO) {
                    try {
                        val bytes = StorageUtils.getFolderSize(installPath)
                        if (bytes > 0) StorageUtils.formatBinarySize(bytes) else null
                    } catch (_: Exception) {
                        null
                    }
                }
            } else {
                null
            }
    }

    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) {
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val os = context.contentResolver.openOutputStream(uri) ?: return@launch
                        val zos = java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(os))

                        val containerManager =
                            com.winlator.cmod.runtime.container
                                .ContainerManager(context)
                        val shortcut = findLibraryShortcutForGame(containerManager, app, isCustom)

                        val dirsToZip = mutableListOf<java.io.File>()

                        val goldbergSaves = java.io.File(SteamService.getAppDirPath(app.id), "steam_settings/saves")
                        if (goldbergSaves.exists() && goldbergSaves.isDirectory) {
                            dirsToZip.add(goldbergSaves)
                        }

                        if (shortcut != null) {
                            val prefixDir = java.io.File(shortcut.container.getRootDir(), ".wine/drive_c/users/xuser")
                            val docs = java.io.File(prefixDir, "Documents")
                            val savedGames = java.io.File(prefixDir, "Saved Games")
                            val appData = java.io.File(prefixDir, "AppData")
                            if (docs.exists()) dirsToZip.add(docs)
                            if (savedGames.exists()) dirsToZip.add(savedGames)
                            if (appData.exists()) dirsToZip.add(appData)
                        }

                        fun zipDir(
                            dir: java.io.File,
                            baseName: String,
                        ) {
                            val children = dir.listFiles() ?: return
                            for (child in children) {
                                val name = if (baseName.isEmpty()) child.name else "$baseName/${child.name}"
                                if (child.isDirectory) {
                                    zos.putNextEntry(java.util.zip.ZipEntry("$name/"))
                                    zos.closeEntry()
                                    zipDir(child, name)
                                } else {
                                    zos.putNextEntry(java.util.zip.ZipEntry(name))
                                    val fis = java.io.FileInputStream(child)
                                    val buf = ByteArray(1024 * 8)
                                    var len: Int
                                    while (fis.read(buf).also { len = it } > 0) {
                                        zos.write(buf, 0, len)
                                    }
                                    fis.close()
                                    zos.closeEntry()
                                }
                            }
                        }

                        for (dir in dirsToZip) {
                            val baseName = dir.name
                            zos.putNextEntry(java.util.zip.ZipEntry("$baseName/"))
                            zos.closeEntry()
                            zipDir(dir, baseName)
                        }

                        zos.close()
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            com.winlator.cmod.shared.ui.toast.WinToast.show(
                                context,
                                R.string.saves_import_export_exported,
                                android.widget.Toast.LENGTH_SHORT,
                            )
                            onDismissRequest()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            com.winlator.cmod.shared.ui.toast.WinToast.show(
                                context,
                                getString(R.string.saves_import_export_exported_failed, e.message),
                                android.widget.Toast.LENGTH_SHORT,
                            )
                        }
                    }
                }
            }
        }

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val `is` = context.contentResolver.openInputStream(uri) ?: return@launch
                        val zis = java.util.zip.ZipInputStream(java.io.BufferedInputStream(`is`))

                        val containerManager =
                            com.winlator.cmod.runtime.container
                                .ContainerManager(context)
                        val shortcut = findLibraryShortcutForGame(containerManager, app, isCustom)

                        val goldbergSavesParent =
                            java.io.File(
                                SteamService.getAppDirPath(app.id),
                                "steam_settings",
                            )
                        val prefixDir = shortcut?.let { java.io.File(it.container.getRootDir(), ".wine/drive_c/users/xuser") }

                        var ze: java.util.zip.ZipEntry?
                        while (zis.nextEntry.also { ze = it } != null) {
                            val entry = ze!!
                            val name = entry.name
                            var destFile: java.io.File? = null
                            if (name.startsWith("saves/")) {
                                destFile = java.io.File(goldbergSavesParent, name)
                            } else if (prefixDir != null) {
                                if (name.startsWith("Documents/") || name.startsWith("Saved Games/") || name.startsWith("AppData/")) {
                                    destFile = java.io.File(prefixDir, name)
                                }
                            }

                            if (destFile != null) {
                                if (entry.isDirectory) {
                                    destFile.mkdirs()
                                } else {
                                    destFile.parentFile?.mkdirs()
                                    val fos = java.io.FileOutputStream(destFile)
                                    val buf = ByteArray(1024 * 8)
                                    var len: Int
                                    while (zis.read(buf).also { len = it } > 0) {
                                        fos.write(buf, 0, len)
                                    }
                                    fos.close()
                                }
                            }
                            zis.closeEntry()
                        }
                        zis.close()
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            com.winlator.cmod.shared.ui.toast.WinToast.show(
                                context,
                                R.string.saves_import_export_imported,
                                android.widget.Toast.LENGTH_SHORT,
                            )
                            onDismissRequest()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            com.winlator.cmod.shared.ui.toast.WinToast.show(
                                context,
                                getString(R.string.saves_import_export_imported_failed, e.message),
                                android.widget.Toast.LENGTH_SHORT,
                            )
                        }
                    }
                }
            }
        }

    GameSettingsDialogFrame(
        title = app.name,
        onDismissRequest = onDismissRequest,
        wide = currentTab == GameSettingsScreen.CloudSaves,
        contentKey = currentTab,
    ) {
        when (currentTab) {
            GameSettingsScreen.Menu -> {
                val actions =
                    listOf(
                        GameSettingsActionItem(
                            title = stringResource(R.string.common_ui_settings),
                            icon = Icons.Outlined.Settings,
                            onClick = {
                                val containerManager = ContainerManager(context)
                                val shortcut =
                                    findLibraryShortcutForGame(containerManager, app, isCustom)
                                        ?: if (isCustom) {
                                            null
                                        } else {
                                            ShortcutSettingsComposeDialog.createLibraryShortcut(
                                                context = context,
                                                containerManager = containerManager,
                                                source = "STEAM",
                                                appId = app.id,
                                                appName = app.name,
                                            )
                                        }
                                if (shortcut != null) {
                                    ShortcutSettingsComposeDialog(this@GameSettingsDialog, shortcut).show()
                                }
                                onDismissRequest()
                            },
                        ),
                        GameSettingsActionItem(
                            title = stringResource(R.string.hero_boot_to_desktop_title),
                            icon = Icons.Outlined.DesktopWindows,
                            onClick = {
                                val shortcut =
                                    findLibraryShortcutForGame(ContainerManager(context), app, isCustom)
                                if (shortcut != null) {
                                    bootShortcut = shortcut
                                    showBootDialog = true
                                } else {
                                    com.winlator.cmod.shared.ui.toast.WinToast.show(context, R.string.shortcuts_list_not_available)
                                }
                            },
                        ),
                        GameSettingsActionItem(
                            title =
                                stringResource(
                                    if (hasPinnedShortcut) {
                                        R.string.common_ui_remove
                                    } else {
                                        R.string.common_ui_shortcut
                                    },
                                ),
                            icon = Icons.Outlined.Home,
                            accentColor = if (hasPinnedShortcut) DangerRed else Accent,
                            onClick = {
                                if (hasPinnedShortcut) {
                                    currentTab = GameSettingsScreen.Shortcut
                                } else {
                                    scope.launch {
                                        val created =
                                            withContext(Dispatchers.IO) {
                                                addLibraryShortcutToHomeScreen(
                                                    context,
                                                    app,
                                                    isCustom,
                                                )
                                            }
                                        if (!created) {
                                            com.winlator.cmod.shared.ui.toast.WinToast.show(
                                                context,
                                                context.getString(
                                                    R.string.library_games_failed_to_create_shortcut,
                                                    app.name,
                                                ),
                                            )
                                        }
                                    }
                                }
                            },
                        ),
                        GameSettingsActionItem(
                            title = stringResource(R.string.cloud_saves_title),
                            icon = Icons.Outlined.CloudSync,
                            onClick = { currentTab = GameSettingsScreen.CloudSaves },
                        ),

                        GameSettingsActionItem(
                            title = stringResource(R.string.library_games_stats_title),
                            icon = Icons.Outlined.QueryStats,
                            onClick = { currentTab = GameSettingsScreen.Stats },
                        ),

                        GameSettingsActionItem(
                            title =
                                if (isCustom) {
                                    stringResource(
                                        R.string.common_ui_remove,
                                    )
                                } else {
                                    stringResource(R.string.common_ui_uninstall)
                                },
                            icon = Icons.Outlined.Delete,
                            accentColor = DangerRed,
                            onClick = { currentTab = GameSettingsScreen.Uninstall },
                        ),
                    )

                GameSettingsActionGrid(actions = actions)
            }

            GameSettingsScreen.Shortcut -> {
                ShortcutRemovalConfirmation(
                    message = stringResource(R.string.shortcuts_list_remove_game_shortcut_message, app.name),
                    onConfirm = {
                        scope.launch {
                            val removed =
                                withContext(Dispatchers.IO) {
                                    homeShortcutState.shortcut?.let {
                                        LibraryShortcutUtils.disablePinnedHomeShortcut(context, it)
                                    } == true
                                }
                            pinnedShortcutOverride = if (removed) false else hasPinnedShortcut
                            shortcutRefreshKey++
                            currentTab = GameSettingsScreen.Menu
                            com.winlator.cmod.shared.ui.toast.WinToast.show(
                                context,
                                if (removed) {
                                    context.getString(R.string.shortcuts_list_removed)
                                } else {
                                    context.getString(R.string.common_ui_unknown_error)
                                },
                            )
                        }
                    },
                    onCancel = { currentTab = GameSettingsScreen.Menu },
                )
            }

            GameSettingsScreen.CloudSaves -> {
                var isWorking by remember { mutableStateOf(false) }
                val shortcut =
                    remember(app.id, isCustom) {
                        findLibraryShortcutForGame(ContainerManager(context), app, isCustom)
                    }
                var cloudSyncEnabled by remember(shortcut?.file?.absolutePath) {
                    mutableStateOf(isShortcutCloudSyncEnabled(shortcut))
                }
                var offlineModeEnabled by remember(shortcut?.file?.absolutePath) {
                    mutableStateOf(isShortcutOfflineMode(shortcut))
                }

                val gameSource =
                    when {
                        isCustom -> GameSaveBackupManager.GameSource.CUSTOM
                        else -> GameSaveBackupManager.GameSource.STEAM
                    }
                val gameIdStr =
                    when {
                        isCustom -> shortcut?.let { GameSaveBackupManager.customGameId(it) } ?: app.name
                        else -> app.id.toString()
                    }
                val providerLabel =
                    when (gameSource) {
                        GameSaveBackupManager.GameSource.CUSTOM ->
                            stringResource(R.string.preloader_platform_custom)
                        else ->
                            stringResource(R.string.preloader_platform_steam)
                    }

                CloudSavesContent(
                    activity = this@GameSettingsDialog,
                    isWorking = isWorking,
                    cloudSyncEnabled = cloudSyncEnabled,
                    offlineModeEnabled = offlineModeEnabled,
                    gameSource = gameSource,
                    gameId = gameIdStr,
                    gameName = app.name,
                    shortcut = shortcut,
                    onCloudSyncToggle = { enabled ->
                        cloudSyncEnabled = enabled
                        setShortcutCloudSyncEnabled(shortcut, enabled)
                        com.winlator.cmod.shared.ui.toast.WinToast.show(
                            context,
                            if (enabled) {
                                context.getString(R.string.cloud_sync_enabled_summary)
                            } else {
                                context.getString(R.string.cloud_sync_disabled_summary)
                            },
                            android.widget.Toast.LENGTH_SHORT,
                        )
                    },
                    onOfflineModeToggle = { enabled ->
                        offlineModeEnabled = enabled
                        setShortcutOfflineMode(shortcut, enabled)
                    },
                    onSyncFromCloud = {
                        if (!isWorking) {
                            isWorking = true
                            scope.launch(Dispatchers.IO) {
                                val ok =
                                    CloudSyncHelper.downloadCloudSaves(
                                        context,
                                        gameSource,
                                        gameIdStr,
                                        shortcut,
                                    )
                                withContext(Dispatchers.Main) {
                                    isWorking = false
                                    com.winlator.cmod.shared.ui.toast.WinToast.show(
                                        context,
                                        if (ok) {
                                            context.getString(
                                                R.string.cloud_saves_sync_from_provider_success,
                                                providerLabel,
                                            )
                                        } else {
                                            context.getString(
                                                R.string.cloud_saves_sync_from_provider_failed,
                                                providerLabel,
                                            )
                                        },
                                        android.widget.Toast.LENGTH_SHORT,
                                    )
                                }
                            }
                        }
                    },
                    onBack = { currentTab = GameSettingsScreen.Menu },
                )
            }

            GameSettingsScreen.Stats -> {
                val hasAnyStat =
                    totalPlaytime > 0L || playCount > 0 || lastPlayed > 0L || installSizeText != null
                if (hasAnyStat) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        if (totalPlaytime > 0L) {
                            GameSettingsStatRow(
                                icon = Icons.Outlined.Schedule,
                                label = stringResource(R.string.library_games_playtime),
                                value = formatLibraryPlaytime(totalPlaytime),
                            )
                        }
                        if (playCount > 0) {
                            GameSettingsStatRow(
                                icon = Icons.Outlined.SportsEsports,
                                label = stringResource(R.string.library_games_plays),
                                value = playCount.toString(),
                            )
                        }
                        if (lastPlayed > 0L) {
                            GameSettingsStatRow(
                                icon = Icons.Outlined.History,
                                label = stringResource(R.string.library_games_last_played),
                                value = formatLibraryLastPlayed(lastPlayed),
                            )
                        }
                        installSizeText?.let { sizeText ->
                            GameSettingsStatRow(
                                icon = Icons.Outlined.Storage,
                                label = stringResource(R.string.common_ui_size),
                                value = sizeText,
                            )
                        }
                    }
                } else {
                    GameSettingsInfoCard(message = stringResource(R.string.library_games_no_stats_yet))
                }

                if (!isCustom) {
                    HorizontalDivider(
                        color = CardBorder.copy(alpha = 0.5f),
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = if (hasAnyStat) 4.dp else 0.dp),
                    )
                    GameSettingsActionGrid(
                        actions =
                            listOf(
                                GameSettingsActionItem(
                                    title = stringResource(R.string.steam_achievements_title),
                                    icon = Icons.Outlined.EmojiEvents,
                                    onClick = { showAchievements = true },
                                ),
                            ),
                    )
                }
            }

            GameSettingsScreen.Uninstall -> {
                UninstallConfirmation(
                    message =
                        if (isCustom) {
                            getString(R.string.library_games_remove_confirm, app.name)
                        } else {
                            getString(R.string.library_games_uninstall_confirm, app.name)
                        },
                    confirmLabel =
                        if (isCustom) {
                            stringResource(
                                R.string.common_ui_remove,
                            )
                        } else {
                            stringResource(R.string.common_ui_uninstall)
                        },
                    onConfirm = {
                        if (isCustom) {
                            scope.launch(Dispatchers.IO) {
                                val cm = ContainerManager(context)
                                val sc = findLibraryShortcutForGame(cm, app, isCustom)
                                sc?.let { LibraryShortcutUtils.deleteShortcutArtifacts(context, it) }
                                PluviaApp.events.emit(AndroidEvent.LibraryInstallStatusChanged(app.id))
                                withContext(Dispatchers.Main) {
                                    com.winlator.cmod.shared.ui.toast.WinToast.show(
                                        context,
                                        getString(R.string.library_games_game_removed, app.name),
                                        android.widget.Toast.LENGTH_SHORT,
                                    )
                                    onDismissRequest()
                                }
                            }
                        } else {
                            SteamService.uninstallApp(app.id) { success ->
                                if (success) {
                                    com.winlator.cmod.shared.ui.toast.WinToast.show(
                                        context,
                                        getString(R.string.library_games_game_uninstalled, app.name),
                                        android.widget.Toast.LENGTH_SHORT,
                                    )
                                } else {
                                    com.winlator.cmod.shared.ui.toast.WinToast.show(
                                        context,
                                        getString(R.string.library_games_failed_to_uninstall),
                                        android.widget.Toast.LENGTH_SHORT,
                                    )
                                }
                                onDismissRequest()
                            }
                        }
                    },
                    onCancel = { currentTab = GameSettingsScreen.Menu },
                )
            }
        }
    }

    if (showBootDialog) {
        HeroBootDialog(
            onConfirm = { choice ->
                showBootDialog = false
                bootShortcut?.let { sc ->
                    val intent =
                        Intent(context, XServerDisplayActivity::class.java)
                            .putExtra("container_id", sc.container.id)
                    when (choice) {
                        HeroBootChoice.Desktop -> {}
                        HeroBootChoice.Cube32 ->
                            intent
                                .putExtra("shortcut_path", sc.file.absolutePath)
                                .putExtra("boot_exe", "C:\\ProgramData\\Microsoft\\Windows\\Graphics-Test-32bit.exe")
                        HeroBootChoice.Cube64 ->
                            intent
                                .putExtra("shortcut_path", sc.file.absolutePath)
                                .putExtra("boot_exe", "C:\\ProgramData\\Microsoft\\Windows\\Graphics-Test-64bit.exe")
                        HeroBootChoice.Input32 ->
                            intent
                                .putExtra("shortcut_path", sc.file.absolutePath)
                                .putExtra("boot_exe", "C:\\ProgramData\\Microsoft\\Windows\\InputControl32.exe")
                        HeroBootChoice.Input64 ->
                            intent
                                .putExtra("shortcut_path", sc.file.absolutePath)
                                .putExtra("boot_exe", "C:\\ProgramData\\Microsoft\\Windows\\InputControl64.exe")
                    }
                    context.startActivity(intent)
                    onDismissRequest()
                }
            },
            onDismissRequest = { showBootDialog = false },
        )
    }

    if (showAchievements) {
        Dialog(
            onDismissRequest = { showAchievements = false },
            properties =
                DialogProperties(
                    usePlatformDefaultWidth = false,
                    dismissOnClickOutside = false,
                    decorFitsSystemWindows = false,
                ),
        ) {
            com.winlator.cmod.feature.stores.steam.achievements.SteamAchievementsScreen(
                appId = app.id,
                appName = app.name,
                onClose = { showAchievements = false },
            )
        }
    }
}

