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

// Steam store tab, capsules and manager dialogs, split out of UnifiedActivity.kt (behavior-identical).

@Composable
internal fun UnifiedActivity.CompactActionButton(
    icon: ImageVector,
    label: String,
    tint: Color = TextPrimary,
    bgColor: Color = SurfaceDark,
    modifier: Modifier = Modifier,
    height: Dp = 36.dp,
    fontSize: TextUnit = 13.sp,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.93f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 800f),
        label = "btnScale",
    )
    val glowAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.18f else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "btnGlow",
    )
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.clip(RoundedCornerShape(10.dp))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ),
        color = bgColor,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, tint.copy(alpha = glowAlpha)),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = tint)
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                color = tint,
                fontSize = fontSize,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// Single game capsule for carousel / grid / list
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun UnifiedActivity.GameCapsule(
    app: SteamApp,
    iconRefreshKey: Int = 0,
    artworkCacheRefreshKey: Int = 0,
    isFocusedOverride: Boolean = false,
    isControllerActive: Boolean = false,
    customArtworkPath: String? = null,
    customIconPath: String? = null,
    customListPath: String? = null,
    customCarouselPath: String? = null,
    customHeroPath: String? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onMoreClick: (() -> Unit)? = null,
    useLibraryCapsule: Boolean = false,
    listMode: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val isCustom = app.id < 0
    val defaultClick: () -> Unit = {
        val containerManager =
            com.winlator.cmod.runtime.container
                .ContainerManager(context)
        if (isCustom) {
            launchCustomGame(context, containerManager, app.name)
        } else {
            launchSteamGame(context, containerManager, app)
        }
    }
    // Each view has its own shape, so prefer the slot scraped for it.
    val artworkToBeUsed =
        when {
            listMode -> customListPath ?: customArtworkPath
            useLibraryCapsule -> customCarouselPath ?: customArtworkPath
            else -> customArtworkPath
        }
    val clickInteraction = remember { MutableInteractionSource() }
    val isPressed by clickInteraction.collectIsPressedAsState()
    val isFocused = isControllerActive && isFocusedOverride
    val glowAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.7f else 0f,
        animationSpec = if (isPressed) tween(100) else tween(400),
        label = "capsuleGlow",
    )
    val clickModifier =
        Modifier
            .then(
                if (glowAlpha > 0f) {
                    Modifier.drawWithContent {
                        drawContent()
                        drawRoundRect(
                            color = AccentGlow,
                            alpha = glowAlpha * 0.25f,
                            cornerRadius = CornerRadius(12.dp.toPx()),
                        )
                    }
                } else {
                    Modifier
                },
            ).combinedClickable(
                interactionSource = clickInteraction,
                indication = null,
                onClick = onClick ?: defaultClick,
                onLongClick = onLongClick,
            )

    @Composable
    fun ArtContent(artModifier: Modifier) {
        val customArtworkFile =
            artworkToBeUsed
                ?.let { java.io.File(it) }

        if (customArtworkFile != null) {
            val customArtworkCacheKey =
                "library_custom_icon:${customArtworkFile.absolutePath}:${customArtworkFile.lastModified()}"
            AsyncImage(
                model =
                    ImageRequest
                        .Builder(context)
                        .data(customArtworkFile)
                        .memoryCacheKey(customArtworkCacheKey)
                        .diskCacheKey(customArtworkCacheKey)
                        .crossfade(300)
                        .build(),
                contentDescription = app.name,
                modifier = artModifier,
                contentScale = ContentScale.Crop,
            )
        } else if (isCustom) {
            val iconFile = customIconPath?.let { path -> java.io.File(path) }
            if (iconFile != null) {
                AsyncImage(
                    model =
                        ImageRequest
                            .Builder(context)
                            .data(iconFile)
                            .crossfade(300)
                            .build(),
                    contentDescription = app.name,
                    modifier = artModifier,
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = artModifier.background(SurfaceDark),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.SportsEsports,
                        contentDescription = app.name,
                        tint = Accent.copy(alpha = 0.6f),
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
        } else {
            val imageModel =
                remember(app.id, useLibraryCapsule, listMode, artworkCacheRefreshKey) {
                    StoreArtworkCache.imageModel(
                        context,
                        StoreArtworkCache.primaryRef(app, useLibraryCapsule, listMode),
                    )
                }
            AsyncImage(
                model =
                    ImageRequest
                        .Builder(context)
                        .data(imageModel)
                        .crossfade(300)
                        .build(),
                contentDescription = app.name,
                modifier = artModifier,
                contentScale = ContentScale.Crop,
            )
        }
    }

    if (listMode) {
        // Horizontal row card with hero background
        val heroRef = if (!isCustom) StoreArtworkCache.heroRef(app) else null
        val heroModel =
            remember(app.id, heroRef, artworkCacheRefreshKey) {
                StoreArtworkCache.imageModel(context, heroRef)
            }

        Box(
            modifier =
                modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .then(
                        if (isControllerActive && !isFocused) {
                            Modifier.border(1.dp, CardBorder, RoundedCornerShape(14.dp))
                        } else {
                            Modifier
                        },
                    ).chasingBorder(
                        isFocused = isFocused,
                        paused = chasingBordersPaused.value || !libraryTabActive.value,
                        cornerRadius = 14.dp,
                    ).background(CardDark, RoundedCornerShape(14.dp))
                    .focusable()
                    .then(clickModifier),
        ) {
            // Hero background layer (falls back to CardDark if image fails)
            if (heroRef != null) {
                AsyncImage(
                    model =
                        ImageRequest
                            .Builder(context)
                            .data(heroModel)
                            .crossfade(300)
                            .build(),
                    contentDescription = null,
                    modifier =
                        Modifier
                            .matchParentSize()
                            .graphicsLayer { alpha = 0.25f },
                    contentScale = ContentScale.Crop,
                )
            } else {
                customHeroPath?.let {
                    val heroFile = java.io.File(customHeroPath)
                    if (heroFile.isFile) {
                        AsyncImage(
                            model =
                                ImageRequest
                                    .Builder(context)
                                    .data(heroFile)
                                    .crossfade(300)
                                    .build(),
                            contentDescription = null,
                            modifier =
                                Modifier
                                    .matchParentSize()
                                    .graphicsLayer { alpha = 0.25f },
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            }

            // Foreground content
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier =
                        Modifier
                            .height(52.dp)
                            .aspectRatio(462f / 174f)
                            .clip(RoundedCornerShape(8.dp)),
                ) {
                    ArtContent(Modifier.fillMaxSize())
                }

                Spacer(Modifier.width(14.dp))

                Text(
                    text = app.name,
                    modifier =
                        Modifier
                            .weight(1f)
                            .then(if (isFocused) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier),
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                if (onMoreClick != null) {
                    Spacer(Modifier.width(6.dp))
                    GameCapsuleMoreButton(onClick = onMoreClick)
                }
            }
        }
    } else {
        // Vertical card: art on top, title below
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier =
                modifier
                    .fillMaxWidth()
                    .then(
                        if (isFocused) {
                            Modifier
                        } else {
                            Modifier.border(1.dp, CardDark, RoundedCornerShape(12.dp))
                        },
                    ).chasingBorder(
                        isFocused = isFocused,
                        paused = chasingBordersPaused.value || !libraryTabActive.value,
                        cornerRadius = 12.dp,
                    ).background(CardDark, RoundedCornerShape(12.dp))
                    .focusable()
                    .then(clickModifier),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
            ) {
                ArtContent(Modifier.fillMaxSize())
                if (onMoreClick != null) {
                    GameCapsuleMoreButton(
                        onClick = onMoreClick,
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                    )
                }
            }

            Text(
                text = app.name,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                        .then(if (isFocused) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier),
                style = MaterialTheme.typography.bodySmall,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Small "⋮" overlay so touch users can discover the settings menu without needing long-press. */
@Composable
internal fun UnifiedActivity.GameCapsuleMoreButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier =
            modifier
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MoreButtonBg)
                .border(1.dp, MoreButtonOutline, RoundedCornerShape(8.dp))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.MoreVert,
            contentDescription = stringResource(R.string.common_ui_options),
            tint = MoreButtonTint,
            modifier = Modifier.size(18.dp),
        )
    }
}

// Matches the "⋮" affordance used for containers (ContainersScreen.kt's SmallVectorIconButton),
// kept as its own copy here since GameCapsule overlays artwork rather than a flat card.
private val MoreButtonBg = Color(0xFF161622).copy(alpha = 0.75f)
private val MoreButtonOutline = Color(0xFF2A2A3A)
private val MoreButtonTint = Color(0xFF7A8FA8)

@Composable
internal fun UnifiedActivity.StoreInstalledBadge(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    attachedCorner: Boolean = false,
) {
    val shape =
        if (attachedCorner) {
            RoundedCornerShape(topStart = 8.dp)
        } else {
            RoundedCornerShape(4.dp)
        }
    Box(
        modifier =
            modifier
                .background(StatusOnline, shape)
                .border(1.dp, Color.White.copy(alpha = 0.34f), shape)
                .padding(
                    start = if (compact) 6.dp else 9.dp,
                    end = if (compact) 6.dp else 9.dp,
                    top = if (compact) 2.dp else 4.dp,
                    bottom = if (compact) 1.dp else 2.dp,
                ),
    ) {
        Text(
            stringResource(R.string.library_games_installed_badge),
            color = Color(0xFF06140A),
            fontSize = if (compact) 9.sp else 11.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.6.sp,
            maxLines = 1,
        )
    }
}





// Steam Store Tab
@Composable
internal fun UnifiedActivity.SteamStoreTab(
    isLoggedIn: Boolean,
    steamApps: List<SteamApp>,
    searchQuery: String = "",
    layoutMode: LibraryLayoutMode = LibraryLayoutMode.GRID_4,
) {
    if (!isLoggedIn && !SteamService.hasStoredCredentials(this)) {
        LoginRequiredScreen("Steam") {
            startActivity(Intent(this@SteamStoreTab, SteamLoginActivity::class.java))
        }
        return
    }

    var selectedAppForDialog by remember { mutableStateOf<SteamApp?>(null) }
    val gridState = rememberLazyGridState()
    val activity = LocalContext.current as? UnifiedActivity

    val displayedApps =
        remember(steamApps, searchQuery) {
            if (searchQuery.isBlank()) {
                steamApps
            } else {
                steamApps.filter { it.name.contains(searchQuery, ignoreCase = true) }
            }
        }
    val installStateById = rememberSteamInstallStateMap(displayedApps)

    // Sync store focus infrastructure
    LaunchedEffect(displayedApps.size) {
        activity?.storeItemCount = displayedApps.size
        val lastIndex = (displayedApps.size - 1).coerceAtLeast(0)
        if (activity != null && displayedApps.isNotEmpty() && activity.storeFocusIndex.value > lastIndex) {
            activity.storeFocusIndex.value = lastIndex
        }
    }
    // Register A-button click callback and grid state for visible-area snapping
    DisposableEffect(displayedApps) {
        val clickCallback: (Int) -> Unit = { idx ->
            displayedApps.getOrNull(idx)?.let { selectedAppForDialog = it }
        }
        activity?.storeItemClickCallback = clickCallback
        activity?.storeGridState = gridState
        onDispose {
            if (activity?.storeItemClickCallback === clickCallback) {
                activity?.storeItemClickCallback = null
                activity?.storeGridState = null
            }
        }
    }

    if (layoutMode == LibraryLayoutMode.LIST) {
        val listViewState = rememberLazyListState()
        JoystickListScroll(listViewState, activity?.rightStickScrollState, minSpeed = 2.5f, maxSpeed = 16f, quadratic = true)
        ListView(
            items = displayedApps,
            modifier = Modifier.tabScreenPadding(),
            listState = listViewState,
            contentPadding = TabListContentPadding,
            keyOf = { it.id },
        ) { app, _, _ ->
            SteamStoreCapsule(
                app,
                isInstalled = installStateById[app.id] == true,
                listMode = true,
                isControllerActive = ControllerHelper.isControllerConnected(),
                onClick = {
                    selectedAppForDialog =
                        app
                },
            )
        }
    } else {
        val focusIndex by (activity?.storeFocusIndex ?: kotlinx.coroutines.flow.MutableStateFlow(0)).collectAsState()
        val focusRequesters =
            remember(displayedApps.size) {
                List(displayedApps.size) { FocusRequester() }
            }
        LaunchedEffect(focusIndex, focusRequesters.size) {
            if (searchQuery.isEmpty() && focusRequesters.isNotEmpty() && focusIndex in focusRequesters.indices) {
                gridState.animateScrollToItem(focusIndex)
                try {
                    focusRequesters[focusIndex].requestFocus()
                } catch (_: Exception) {
                }
            }
        }
        // Right joystick: 2x faster at full push with quadratic speed curve
        JoystickGridScroll(gridState, activity?.rightStickScrollState, minSpeed = 2.5f, maxSpeed = 16f, quadratic = true)
        // Left joystick: 75% slower scrolling (vertical only, for browsing store)
        JoystickGridScroll(gridState, activity?.leftStickScrollState, deadZone = 0.15f, minSpeed = 0.3125f, maxSpeed = 2f)
        FourByTwoGridView(
            items = displayedApps,
            modifier = Modifier.tabScreenPadding(top = TabGridTopPadding),
            gridState = gridState,
            keyOf = { it.id },
        ) { app, index, rowHeight ->
            Box(
                Modifier.height(rowHeight).then(
                    if (index in focusRequesters.indices) {
                        Modifier.focusRequester(focusRequesters[index])
                    } else {
                        Modifier
                    },
                ),
            ) {
                SteamStoreCapsule(
                    app,
                    isInstalled = installStateById[app.id] == true,
                    isFocusedOverride = index == focusIndex,
                    isControllerActive =
                        ControllerHelper
                            .isControllerConnected(),
                    onClick = {
                        selectedAppForDialog =
                            app
                    },
                )
            }
        }
    }

    if (selectedAppForDialog != null) {
        GameManagerDialog(
            app = selectedAppForDialog!!,
            onDismissRequest = { selectedAppForDialog = null },
        )
    }
}

@Composable
internal fun UnifiedActivity.SteamStoreCapsule(
    app: SteamApp,
    isInstalled: Boolean,
    listMode: Boolean = false,
    isFocusedOverride: Boolean = false,
    isControllerActive: Boolean = false,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    var isFocused by remember { mutableStateOf(false) }
    val clickInteraction = remember { MutableInteractionSource() }
    val isPressed by clickInteraction.collectIsPressedAsState()
    val glowAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.7f else 0f,
        animationSpec = if (isPressed) tween(100) else tween(400),
        label = "steamCapsuleGlow",
    )
    val effectiveFocus = isControllerActive && (isFocusedOverride || isFocused)
    val borderColor = if (isControllerActive) CardBorder else Color.Transparent

    if (listMode) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, borderColor, RoundedCornerShape(14.dp))
                    .chasingBorder(isFocused = effectiveFocus, paused = chasingBordersPaused.value, cornerRadius = 14.dp)
                    .background(CardDark, RoundedCornerShape(14.dp))
                    .onFocusChanged { isFocused = it.isFocused }
                    .focusable()
                    .then(
                        if (glowAlpha > 0f) {
                            Modifier.drawWithContent {
                                drawContent()
                                drawRoundRect(color = AccentGlow, alpha = glowAlpha * 0.25f, cornerRadius = CornerRadius(14.dp.toPx()))
                            }
                        } else {
                            Modifier
                        },
                    ).clickable(interactionSource = clickInteraction, indication = null, onClick = onClick),
        ) {
            // Hero background
            AsyncImage(
                model =
                    ImageRequest
                        .Builder(context)
                        .data(app.getHeroUrl())
                        .crossfade(300)
                        .build(),
                contentDescription = null,
                modifier =
                    Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = 0.25f },
                contentScale = ContentScale.Crop,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier
                        .height(52.dp)
                        .aspectRatio(462f / 174f)
                        .clip(RoundedCornerShape(8.dp)),
                ) {
                    AsyncImage(
                        model =
                            ImageRequest
                                .Builder(context)
                                .data(app.getSmallCapsuleUrl())
                                .crossfade(300)
                                .build(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                    if (isInstalled) {
                        StoreInstalledBadge(
                            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                            compact = true,
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Text(
                    text = app.name,
                    modifier =
                        Modifier
                            .weight(1f)
                            .then(if (effectiveFocus) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier),
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    } else {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier =
                Modifier
                    .fillMaxSize()
                    .border(1.dp, borderColor, RoundedCornerShape(16.dp))
                    .chasingBorder(isFocused = effectiveFocus, paused = chasingBordersPaused.value, cornerRadius = 16.dp)
                    .background(CardDark, RoundedCornerShape(16.dp))
                    .onFocusChanged { isFocused = it.isFocused }
                    .focusable()
                    .then(
                        if (glowAlpha > 0f) {
                            Modifier.drawWithContent {
                                drawContent()
                                drawRoundRect(color = AccentGlow, alpha = glowAlpha * 0.25f, cornerRadius = CornerRadius(16.dp.toPx()))
                            }
                        } else {
                            Modifier
                        },
                    ).clickable(interactionSource = clickInteraction, indication = null, onClick = onClick),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
            ) {
                val imageUrl = app.getCapsuleUrl()

                AsyncImage(
                    model =
                        ImageRequest
                            .Builder(context)
                            .data(imageUrl)
                            .crossfade(300)
                            .build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )

                if (isInstalled) {
                    StoreInstalledBadge(
                        modifier = Modifier.align(Alignment.BottomEnd),
                        attachedCorner = true,
                    )
                }
            }

            Text(
                text = app.name,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                        .then(if (effectiveFocus) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier),
                style = MaterialTheme.typography.bodySmall,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}
