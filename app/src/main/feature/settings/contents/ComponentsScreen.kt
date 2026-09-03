package com.winlator.cmod.feature.settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.cmod.R
import com.winlator.cmod.runtime.content.ContentProfile
import com.winlator.cmod.shared.ui.dialog.PopupDialog
import com.winlator.cmod.shared.ui.focus.rememberSettingsContentNav
import com.winlator.cmod.shared.ui.layout.isPortraitLayout
import com.winlator.cmod.shared.ui.nav.DialogPaneNav
import com.winlator.cmod.shared.ui.nav.LocalPaneNav
import com.winlator.cmod.shared.ui.nav.PaneNavRegistry
import com.winlator.cmod.shared.ui.nav.paneNavItem

// Palette (unified with Drivers / Stores / Other / Debug)
private val BgDark = Color(0xFF11111C)
private val CardDark = Color(0xFF1C1C2A)
private val CardDarker = Color(0xFF15151E)
private val CardBorder = Color(0xFF2A2A3A)
private val IconBoxBg = Color(0xFF242434)
private val SurfaceDark = Color(0xFF21212A)
private val Accent = Color(0xFF1A9FFF)
private val SuccessGreen = Color(0xFF5BD68F)
private val DangerRed = Color(0xFFFF7A88)
private val WarningAmber = Color(0xFFFFB454)
private val TextPrimary = Color(0xFFD6DAE0)
private val TextSecondary = Color(0xFF7A8FA8)
private val NavHighlight = Color(0xFF4FC3F7)

// State

data class ComponentItem(
    val key: String,
    val type: ContentProfile.ContentType,
    val verName: String,
    val isInstalled: Boolean,
    val hasRemote: Boolean,
    val sizeBytes: Long? = null,
    val isOfficial: Boolean = false,
)

data class ComponentsDownloadProgress(
    val title: String,
    val message: String,
    val progress: Float = 0f,
    val indeterminate: Boolean = false,
)

data class ComponentsConflict(
    val path: String,
)

data class ComponentRepo(
    val name: String,
    val apiUrl: String,
)

data class ComponentRepoSection(
    val repo: ComponentRepo,
    val itemsByType: Map<ContentProfile.ContentType, List<ComponentItem>>,
) {
    val totalCount: Int get() = itemsByType.values.sumOf { it.size }
}

data class ComponentsState(
    val installed: List<ComponentItem> = emptyList(),
    val repoSections: List<ComponentRepoSection> = emptyList(),
    val downloadProgress: ComponentsDownloadProgress? = null,
    val conflict: ComponentsConflict? = null,
    val autoCreateContainer: Boolean = true,
    val isRefreshing: Boolean = false,
    val expandedRepoApiUrl: String? = null,
)

// Root

@Composable
fun ComponentsScreen(
    bridge: SettingsNavBridge? = null,
    state: ComponentsState,
    onToggleRepoExpanded: (ComponentRepo) -> Unit,
    onInstallFromFile: () -> Unit,
    onDownloadItem: (ComponentItem) -> Unit,
    onRemoveItem: (ComponentItem) -> Unit,
    onDismissConflict: () -> Unit,
    onToggleAutoCreateContainer: (Boolean) -> Unit,
    onAddRepo: () -> Unit,
    onEditRepo: (ComponentRepo) -> Unit,
    onDeleteRepo: (ComponentRepo) -> Unit,
) {
    var itemPendingRemoval by remember { mutableStateOf<ComponentItem?>(null) }
    var repoPendingRemoval by remember { mutableStateOf<ComponentRepo?>(null) }
    val layoutDirection = LocalLayoutDirection.current
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
    val navBarStartPadding = navBarPadding.calculateStartPadding(layoutDirection)
    val navBarEndPadding = navBarPadding.calculateEndPadding(layoutDirection)
    val navBarBottomPadding = navBarPadding.calculateBottomPadding()
    val contentNav = rememberSettingsContentNav(bridge)

    // L1/R1 cycle which repo card is expanded, mirroring what used to cycle component types.
    val sectionSignal = bridge?.contentSectionSignal ?: 0
    var lastSectionSignal by remember { mutableStateOf(sectionSignal) }
    LaunchedEffect(sectionSignal) {
        if (sectionSignal != lastSectionSignal) {
            lastSectionSignal = sectionSignal
            val dir = bridge?.contentSectionDir ?: 0
            if (dir != 0 && state.repoSections.isNotEmpty()) {
                val repos = state.repoSections.map { it.repo }
                val idx = repos.indexOfFirst { it.apiUrl == state.expandedRepoApiUrl }.coerceAtLeast(0)
                val next = repos[((idx + dir) % repos.size + repos.size) % repos.size]
                onToggleRepoExpanded(next)
            }
        }
    }

    itemPendingRemoval?.let { item ->
        val nav = remember { PaneNavRegistry() }
        Dialog(onDismissRequest = { itemPendingRemoval = null }) {
            DialogPaneNav(nav, onDismiss = { itemPendingRemoval = null })
            CompositionLocalProvider(LocalPaneNav provides nav) {
                PopupDialog(
                    title = stringResource(R.string.settings_content_remove_title),
                    message = stringResource(R.string.settings_content_confirm_remove),
                    confirmLabel = stringResource(R.string.common_ui_remove),
                    modifier = Modifier.widthIn(min = 280.dp, max = 360.dp),
                    icon = Icons.Outlined.Delete,
                    accentColor = DangerRed,
                    onCancel = { itemPendingRemoval = null },
                    onConfirm = {
                        onRemoveItem(item)
                        itemPendingRemoval = null
                    },
                )
            }
        }
    }

    repoPendingRemoval?.let { repo ->
        val nav = remember { PaneNavRegistry() }
        Dialog(onDismissRequest = { repoPendingRemoval = null }) {
            DialogPaneNav(nav, onDismiss = { repoPendingRemoval = null })
            CompositionLocalProvider(LocalPaneNav provides nav) {
                PopupDialog(
                    title = stringResource(R.string.settings_content_repo_remove_title),
                    message = stringResource(R.string.settings_content_repo_confirm_remove, repo.name),
                    confirmLabel = stringResource(R.string.common_ui_remove),
                    modifier = Modifier.widthIn(min = 280.dp, max = 360.dp),
                    icon = Icons.Outlined.Delete,
                    accentColor = DangerRed,
                    onCancel = { repoPendingRemoval = null },
                    onConfirm = {
                        onDeleteRepo(repo)
                        repoPendingRemoval = null
                    },
                )
            }
        }
    }

    state.downloadProgress?.let { progress ->
        DownloadProgressDialog(progress = progress)
    }

    state.conflict?.let { conflict ->
        val nav = remember { PaneNavRegistry() }
        Dialog(onDismissRequest = onDismissConflict) {
            DialogPaneNav(nav, onDismiss = onDismissConflict)
            CompositionLocalProvider(LocalPaneNav provides nav) {
                PopupDialog(
                    title = stringResource(R.string.settings_content_already_installed_title),
                    message = stringResource(R.string.settings_content_already_installed_message, conflict.path),
                    confirmLabel = stringResource(R.string.common_ui_ok),
                    modifier = Modifier.widthIn(min = 280.dp, max = 360.dp),
                    icon = Icons.Outlined.Warning,
                    accentColor = WarningAmber,
                    onConfirm = onDismissConflict,
                )
            }
        }
    }

    CompositionLocalProvider(LocalPaneNav provides contentNav) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(BgDark)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = 16.dp + navBarStartPadding,
                        end = 16.dp + navBarEndPadding,
                        top = 16.dp,
                        bottom = 4.dp + navBarBottomPadding,
                    ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HeroHeader(
                installedCount = state.installed.size,
                availableCount = state.repoSections.sumOf { it.totalCount },
                autoCreateContainer = state.autoCreateContainer,
                onInstallFromFile = onInstallFromFile,
                onToggleAutoCreateContainer = onToggleAutoCreateContainer,
                onAddRepo = onAddRepo,
            )

            if (state.installed.isEmpty() && state.repoSections.isEmpty() && !state.isRefreshing) {
                EmptyState()
            }

            if (state.installed.isNotEmpty()) {
                SectionLabel(
                    text = stringResource(R.string.common_ui_installed),
                    modifier = Modifier.padding(top = 8.dp),
                )
                state.installed.forEach { item ->
                    key("installed_${item.key}") {
                        ComponentItemCard(
                            item = item,
                            onDownload = { onDownloadItem(item) },
                            onRemove = { itemPendingRemoval = item },
                        )
                    }
                }
            }

            if (state.repoSections.isNotEmpty()) {
                SectionLabel(
                    text = stringResource(R.string.settings_content_sources_label),
                    modifier = Modifier.padding(top = 6.dp),
                )
                state.repoSections.forEach { section ->
                    key(section.repo.apiUrl) {
                        ComponentRepoCard(
                            section = section,
                            isExpanded = state.expandedRepoApiUrl == section.repo.apiUrl,
                            onTap = { onToggleRepoExpanded(section.repo) },
                            onDownloadItem = onDownloadItem,
                            onEdit = { onEditRepo(section.repo) },
                            onDelete = { repoPendingRemoval = section.repo },
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// Hero header

@Composable
private fun HeroHeader(
    installedCount: Int,
    availableCount: Int,
    autoCreateContainer: Boolean,
    onInstallFromFile: () -> Unit,
    onToggleAutoCreateContainer: (Boolean) -> Unit,
    onAddRepo: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(CardDark)
                .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            val counts: @Composable () -> Unit = {
                CountPill(label = stringResource(R.string.common_ui_installed), count = installedCount)
                Spacer(Modifier.width(6.dp))
                CountPill(label = stringResource(R.string.common_ui_available), count = availableCount)
            }
            val toggle: @Composable (Modifier) -> Unit = { toggleModifier ->
                ToggleChip(
                    label = stringResource(R.string.settings_content_auto_create_container),
                    enabled = autoCreateContainer,
                    compact = true,
                    modifier = toggleModifier,
                    onToggle = { onToggleAutoCreateContainer(!autoCreateContainer) },
                )
            }
            val sources: @Composable () -> Unit = {
                SmallPillButton(
                    label = stringResource(R.string.settings_content_repo_add),
                    icon = Icons.Outlined.Add,
                    tint = Accent,
                    compact = true,
                    onClick = onAddRepo,
                )
            }
            val install: @Composable () -> Unit = {
                SmallPillButton(
                    label = stringResource(R.string.settings_content_install),
                    icon = Icons.Outlined.Upload,
                    tint = Accent,
                    compact = true,
                    onClick = onInstallFromFile,
                )
            }

            if (isPortraitLayout()) {
                // Portrait has no room for counts + toggle + install on a single line:
                // the counts collapse to nothing and the install pill is left a few
                // characters wide, wrapping its label mid-word. Split the controls over
                // two rows so every chip keeps its natural width.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    counts()
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The install pill is unweighted, so it is measured at its full
                    // intrinsic width first; the toggle absorbs whatever is left and
                    // ellipsises its label rather than squeezing its neighbour.
                    toggle(Modifier.weight(1f))
                    Spacer(Modifier.width(16.dp))
                    install()
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    sources()
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        counts()
                    }
                    Spacer(Modifier.width(10.dp))
                    toggle(Modifier)
                    Spacer(Modifier.width(6.dp))
                    sources()
                    Spacer(Modifier.width(8.dp))
                    install()
                }
            }
        }
    }
}

@Composable
private fun ToggleChip(
    label: String,
    enabled: Boolean,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    val tint = if (enabled) SuccessGreen else TextSecondary
    val background = if (enabled) SuccessGreen.copy(alpha = 0.14f) else SurfaceDark
    val borderColor = if (enabled) SuccessGreen.copy(alpha = 0.45f) else CardBorder
    val horizontalPadding = if (compact) 8.dp else 10.dp
    val verticalPadding = if (compact) 4.dp else 5.dp
    val dotSize = if (compact) 5.dp else 6.dp
    val fontSize = if (compact) 10.sp else 11.sp
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(8.dp))
                .background(background)
                .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                .paneNavItem(
                    cornerRadius = 8.dp,
                    onActivate = onToggle,
                    highlightColor = NavHighlight,
                    tapToSelect = true,
                )
                .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        // Centres the dot + label when a caller stretches the chip (portrait);
        // a no-op when the chip sits at its natural width (landscape).
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(dotSize)
                    .clip(RoundedCornerShape(3.dp))
                    .background(tint),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = tint,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CountPill(
    label: String,
    count: Int,
) {
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Accent.copy(alpha = 0.12f))
                .border(1.dp, Accent.copy(alpha = 0.28f), RoundedCornerShape(6.dp))
                .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = count.toString(),
            color = Accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            color = TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun descriptionResFor(type: ContentProfile.ContentType): Int =
    when (type) {
        ContentProfile.ContentType.CONTENT_TYPE_WINE -> R.string.settings_content_desc_wine
        ContentProfile.ContentType.CONTENT_TYPE_PROTON -> R.string.settings_content_desc_proton
        ContentProfile.ContentType.CONTENT_TYPE_DXVK -> R.string.settings_content_desc_dxvk
        ContentProfile.ContentType.CONTENT_TYPE_VKD3D -> R.string.settings_content_desc_vkd3d
        ContentProfile.ContentType.CONTENT_TYPE_BOX64 -> R.string.settings_content_desc_box64
        ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64 -> R.string.settings_content_desc_wowbox64
        ContentProfile.ContentType.CONTENT_TYPE_FEXCORE -> R.string.settings_content_desc_fexcore
        ContentProfile.ContentType.CONTENT_TYPE_D7VK -> R.string.settings_content_desc_d7vk
    }

// Section label

@Composable
private fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        color = TextSecondary,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.4.sp,
        modifier = modifier.padding(bottom = 4.dp),
    )
}

// Component repo card

@Composable
private fun ComponentRepoCard(
    section: ComponentRepoSection,
    isExpanded: Boolean,
    onTap: () -> Unit,
    onDownloadItem: (ComponentItem) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val borderColor = if (isExpanded) Accent.copy(alpha = 0.45f) else CardBorder
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        label = "componentRepoChevron_${section.repo.apiUrl}",
    )
    var menuOpen by remember { mutableStateOf(false) }
    val types = remember(section) { section.itemsByType.keys.toList() }
    var selectedType by remember(section.repo.apiUrl, types) { mutableStateOf(types.firstOrNull()) }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CardDark)
                .border(1.dp, borderColor, RoundedCornerShape(12.dp)),
    ) {
        Column {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .paneNavItem(
                            cornerRadius = 12.dp,
                            onActivate = onTap,
                            highlightColor = NavHighlight,
                            tapToSelect = true,
                        ).padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(IconBoxBg),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Folder,
                        contentDescription = null,
                        tint = if (isExpanded) Accent else TextSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(13.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = section.repo.name,
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text =
                            if (section.totalCount > 0) {
                                stringResource(R.string.settings_content_repo_item_count, section.totalCount)
                            } else {
                                stringResource(R.string.settings_content_repo_empty)
                            },
                        color = TextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Box {
                    PlainIconButton(
                        icon = Icons.Outlined.MoreVert,
                        tint = TextSecondary,
                        onClick = { menuOpen = true },
                    )
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        containerColor = CardDark,
                    ) {
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Outlined.Edit,
                                        contentDescription = null,
                                        tint = Accent,
                                        modifier = Modifier.size(15.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.common_ui_edit), color = TextPrimary, fontSize = 13.sp)
                                }
                            },
                            onClick = {
                                menuOpen = false
                                onEdit()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Outlined.Delete,
                                        contentDescription = null,
                                        tint = DangerRed,
                                        modifier = Modifier.size(15.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.common_ui_remove), color = TextPrimary, fontSize = 13.sp)
                                }
                            },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }

                Spacer(Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = if (isExpanded) Accent else TextSecondary,
                    modifier = Modifier.size(18.dp).rotate(chevronRotation),
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter =
                    fadeIn(tween(200)) +
                        expandVertically(
                            animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                        ),
                exit =
                    fadeOut(tween(140)) +
                        shrinkVertically(
                            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                        ),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (types.isEmpty()) {
                        Text(
                            text = stringResource(R.string.settings_content_repo_empty),
                            color = TextSecondary,
                            fontSize = 12.sp,
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            types.forEachIndexed { index, type ->
                                RepoTypeChip(
                                    label = type.toString(),
                                    selected = type == selectedType,
                                    onClick = { selectedType = type },
                                )
                                if (index < types.lastIndex) Spacer(Modifier.width(8.dp))
                            }
                        }

                        val items = section.itemsByType[selectedType].orEmpty()
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items.forEach { item ->
                                key(item.key) {
                                    ComponentItemCard(
                                        item = item,
                                        onDownload = { onDownloadItem(item) },
                                        onRemove = {},
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RepoTypeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val background = if (selected) Accent.copy(alpha = 0.18f) else SurfaceDark
    val borderColor = if (selected) Accent.copy(alpha = 0.45f) else CardBorder
    val textColor = if (selected) Accent else TextSecondary
    Box(
        modifier =
            Modifier
                .height(30.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(background)
                .border(1.dp, borderColor, RoundedCornerShape(15.dp))
                .paneNavItem(
                    cornerRadius = 15.dp,
                    onActivate = onClick,
                    highlightColor = NavHighlight,
                    tapToSelect = true,
                ).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// Component item card

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComponentItemCard(
    item: ComponentItem,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CardDark)
                .border(1.dp, CardBorder, RoundedCornerShape(12.dp)),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.verName,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee(
                        iterations = Int.MAX_VALUE,
                        initialDelayMillis = 5000,
                        repeatDelayMillis = 5000,
                        velocity = 25.dp,
                        spacing = MarqueeSpacing(40.dp),
                    ),
                )
                val sizeLabel = formatSizeLabel(item)
                if (sizeLabel != null) {
                    Text(
                        text = sizeLabel,
                        color = TextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            // The trailing action (Download / Delete / placeholder) drives the
            // height; the badges fillMaxHeight() so they always match it exactly.
            Row(
                modifier = Modifier.height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (item.isOfficial) {
                    OfficialBadge(Modifier.fillMaxHeight())
                    Spacer(Modifier.width(8.dp))
                }
                if (isSteamCompatible(item)) {
                    SteamCompatBadge(Modifier.fillMaxHeight())
                    Spacer(Modifier.width(8.dp))
                }
                if (item.isInstalled) {
                    IconTapButton(
                        icon = Icons.Outlined.Delete,
                        tint = DangerRed,
                        onClick = onRemove,
                    )
                } else if (item.hasRemote) {
                    SmallPillButton(
                        label = stringResource(R.string.common_ui_download),
                        icon = Icons.Outlined.Download,
                        tint = Accent,
                        compact = true,
                        onClick = onDownload,
                    )
                } else {
                    // Locally extracted profile with no remote URL — non-interactive placeholder.
                    Icon(
                        imageVector = Icons.Outlined.CloudDownload,
                        contentDescription = null,
                        tint = TextSecondary.copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

// Generic small controls

@Composable
private fun PlainIconButton(
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .paneNavItem(
                    cornerRadius = 8.dp,
                    onActivate = onClick,
                    highlightColor = NavHighlight,
                    tapToSelect = true,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun IconTapButton(
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(tint.copy(alpha = 0.14f))
                .border(1.dp, tint.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
                .paneNavItem(
                    cornerRadius = 8.dp,
                    onActivate = onClick,
                    highlightColor = NavHighlight,
                    tapToSelect = true,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}

private fun isSteamCompatible(item: ComponentItem): Boolean =
    item.verName.contains("steam", ignoreCase = true) ||
        item.key.contains("steam", ignoreCase = true)

// Badge marking first-party "WinLite" builds. A perfect square (width follows
// the filled height) in WinLite blue, carrying only the WinLite logo for
// "WN" branding. Pass Modifier.fillMaxHeight() to match the row's action height.
@Composable
private fun OfficialBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(Accent.copy(alpha = 0.14f))
            .border(1.dp, Accent.copy(alpha = 0.30f), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_winlite_badge),
            contentDescription = "Official WinLite build",
            modifier = Modifier.fillMaxSize(0.8f),
        )
    }
}

@Composable
private fun SteamCompatBadge(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(SuccessGreen.copy(alpha = 0.14f))
            .border(1.dp, SuccessGreen.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Steam",
            color = SuccessGreen,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SmallPillButton(
    label: String,
    icon: ImageVector?,
    tint: Color,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val horizontalPadding = if (compact) 8.dp else 10.dp
    val verticalPadding = if (compact) 4.dp else 6.dp
    val iconSize = if (compact) 11.dp else 12.dp
    val fontSize = if (compact) 10.sp else 11.sp
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(tint.copy(alpha = 0.14f))
                .border(1.dp, tint.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
                .paneNavItem(
                    cornerRadius = 8.dp,
                    onActivate = onClick,
                    highlightColor = NavHighlight,
                    tapToSelect = true,
                )
                .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(iconSize),
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = label,
            color = tint,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            // Never let a squeezed row break the label across lines mid-word;
            // clip it instead so the pill keeps a single-line height.
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// Empty state

@Composable
private fun EmptyState() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(CardDark)
                .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
                .padding(horizontal = 20.dp, vertical = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Outlined.Inbox,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(34.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.common_ui_no_items_to_display),
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_content_empty_subtitle),
                color = TextSecondary,
                fontSize = 11.sp,
            )
        }
    }
}

// Confirm dialog

@Composable
private fun DownloadProgressDialog(progress: ComponentsDownloadProgress) {
    Dialog(
        onDismissRequest = { /* non-dismissable while a transfer is in flight */ },
        properties =
            DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
            ),
    ) {
        PopupDialog(
            title = progress.title,
            message = progress.message,
            modifier = Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth(0.88f),
            icon = Icons.Outlined.Download,
            accentColor = Accent,
            progress = if (progress.indeterminate) Float.NaN else progress.progress,
        )
    }
}

@Composable
fun ComponentRepoEditDialog(
    existing: ComponentRepo?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, apiUrl: String) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var url by remember { mutableStateOf(existing?.apiUrl.orEmpty()) }
    val registry = remember { PaneNavRegistry() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        DialogPaneNav(registry, onDismiss = onDismiss)
        CompositionLocalProvider(LocalPaneNav provides registry) {
            BoxWithConstraints(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier =
                        Modifier
                            .widthIn(max = 440.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(CardDark)
                            .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Column(modifier = Modifier.wrapContentHeight()) {
                        Text(
                            text =
                                if (existing == null) {
                                    stringResource(R.string.settings_content_repo_add)
                                } else {
                                    stringResource(R.string.settings_content_repo_edit)
                                },
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(8.dp))

                        SmallLabeledField(
                            label = stringResource(R.string.settings_content_repo_name),
                            value = name,
                            onValueChange = { name = it },
                            placeholder = "Display name",
                        )
                        Spacer(Modifier.height(6.dp))
                        SmallLabeledField(
                            label = stringResource(R.string.settings_content_repo_url),
                            value = url,
                            onValueChange = { url = it },
                            placeholder = "https://github.com/owner/repo/releases",
                            keyboardType = KeyboardType.Uri,
                        )

                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        ) {
                            SmallDialogButton(label = stringResource(R.string.common_ui_cancel), textColor = TextSecondary, onClick = onDismiss)
                            SmallDialogButton(
                                label =
                                    if (existing == null) {
                                        stringResource(R.string.settings_content_repo_add)
                                    } else {
                                        stringResource(R.string.common_ui_save)
                                    },
                                textColor = Accent,
                                onClick = {
                                    val trimmedName = name.trim()
                                    val trimmedUrl = url.trim()
                                    if (trimmedName.isNotEmpty() && trimmedUrl.isNotEmpty()) {
                                        onConfirm(trimmedName, trimmedUrl)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallLabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label.uppercase(),
            color = TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        )
        Spacer(Modifier.height(4.dp))
        val interactionSource = remember { MutableInteractionSource() }
        val isFocused by interactionSource.collectIsFocusedAsState()
        val borderColor = if (isFocused) Accent else CardBorder
        val fieldFocus = remember { FocusRequester() }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .requiredHeightIn(min = 40.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(CardDarker)
                    .border(if (isFocused) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(9.dp))
                    .padding(horizontal = 11.dp, vertical = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontSize = 13.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Accent),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
                interactionSource = interactionSource,
                modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus),
                decorationBox = { innerTextField ->
                    if (value.isEmpty()) {
                        Text(text = placeholder, color = TextSecondary.copy(alpha = 0.6f), fontSize = 13.sp)
                    }
                    innerTextField()
                },
            )
        }
    }
}

@Composable
private fun SmallDialogButton(
    label: String,
    textColor: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .paneNavItem(cornerRadius = 8.dp, onActivate = onClick, highlightColor = NavHighlight, tapToSelect = true)
                .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(text = label, color = textColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}


@Composable
private fun formatSizeLabel(item: ComponentItem): String? {
    if (item.isInstalled) {
        val bytes = item.sizeBytes ?: return "${stringResource(R.string.common_ui_size)}: --"
        if (bytes <= 0L) return null
        return "${stringResource(R.string.common_ui_size)}: ${formatBytes(bytes)}"
    }
    if (!item.hasRemote) return null
    val bytes = item.sizeBytes ?: return "${stringResource(R.string.common_ui_size)}: --"
    if (bytes <= 0L) return null
    return "${stringResource(R.string.common_ui_size)}: ${formatBytes(bytes)}"
}

private fun formatBytes(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    return if (unitIndex == 0) {
        "${value.toInt()} ${units[unitIndex]}"
    } else {
        String.format(java.util.Locale.US, "%.1f %s", value, units[unitIndex])
    }
}
