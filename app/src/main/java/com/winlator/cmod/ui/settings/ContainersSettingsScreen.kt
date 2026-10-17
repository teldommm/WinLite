package com.winlator.cmod.ui.settings

import com.winlator.cmod.ui.theme.tabularNumbers
import com.winlator.cmod.ui.theme.ThemedDialogScrollBody
import com.winlator.cmod.ui.theme.ThemedDialogTitle
import android.content.Intent
import android.widget.Toast
import com.winlator.cmod.ui.toast.WinToast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.appcompat.app.AppCompatActivity
import com.winlator.cmod.XServerDisplayActivity
import com.winlator.cmod.container.Container
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.core.FileUtils
import com.winlator.cmod.core.StringUtils
import com.winlator.cmod.ui.ThemedAlertHost
import com.winlator.cmod.ui.theme.controlAccentColor
import com.winlator.cmod.ui.theme.ThemedDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayDeque
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.winlator.cmod.ui.theme.findActivity
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import com.winlator.cmod.ui.theme.WinZShapes
import com.winlator.cmod.ui.theme.hairlineColor
import com.winlator.cmod.ui.theme.dividerColor

// Containers list as a MainShell detail entry (replaces ContainersSettingsFragment). The
// container editor opens as another detail entry on top (onOpenEditor); when it closes and this
// screen is on top again, the list reloads (isTop), as the fragment's onResume used to.
@Composable
fun ContainersSettingsRoute(isTop: Boolean, onBack: () -> Unit, onOpenEditor: (Int?) -> Unit) {
    val activity = LocalContext.current.findActivity() as AppCompatActivity
    var containers by remember { mutableStateOf<List<Container>>(emptyList()) }
    var propertiesContainer by remember { mutableStateOf<Container?>(null) }
    val refresh = { containers = ContainerManager(activity).containers.toList() }

    LaunchedEffect(isTop) { if (isTop) refresh() }
    LifecycleResumeEffect(Unit) {
        if (isTop) refresh()
        onPauseOrDispose { }
    }

    ContainersSettingsScreen(
        containers = containers,
        propertiesContainer = propertiesContainer,
        onBack = onBack,
        onAdd = {
            propertiesContainer = null
            onOpenEditor(null)
        },
        onEdit = { id ->
            propertiesContainer = null
            onOpenEditor(id)
        },
        onProperties = { id -> propertiesContainer = ContainerManager(activity).getContainerById(id) },
        onDismissProperties = { propertiesContainer = null },
        onRun = { id ->
            activity.startActivity(Intent(activity, XServerDisplayActivity::class.java).putExtra("container_id", id))
        },
        onDuplicate = { id -> duplicateContainer(activity, id, refresh) },
        onRemove = { id -> removeContainer(activity, id, refresh) }
    )
}

private fun duplicateContainer(activity: AppCompatActivity, id: Int, refresh: () -> Unit) {
    val manager = ContainerManager(activity)
    val container = manager.getContainerById(id) ?: return
    ThemedAlertHost.confirm(
        activity,
        "Duplicate container?",
        "A copy of ${container.name} and its container files will be created.",
        "Duplicate",
        onConfirm = {
            manager.duplicateContainerAsync(container) {
                refresh()
                WinToast.show(activity, "Container duplicated", Toast.LENGTH_SHORT)
            }
        }
    )
}

private fun removeContainer(activity: AppCompatActivity, id: Int, refresh: () -> Unit) {
    val manager = ContainerManager(activity)
    val container = manager.getContainerById(id) ?: return
    ThemedAlertHost.confirm(
        activity,
        "Remove container?",
        "${container.name} and its container files will be deleted.",
        "Remove",
        onConfirm = {
            manager.removeContainerAsync(container) {
                refresh()
                WinToast.show(activity, "Container removed", Toast.LENGTH_SHORT)
            }
        },
        destructive = true
    )
}

@Composable
private fun ContainersSettingsScreen(
    containers: List<Container>,
    propertiesContainer: Container?,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Int) -> Unit,
    onProperties: (Int) -> Unit,
    onDismissProperties: () -> Unit,
    onRun: (Int) -> Unit,
    onDuplicate: (Int) -> Unit,
    onRemove: (Int) -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Containers") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background, scrolledContainerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAdd,
                containerColor = controlAccentColor(),
                contentColor = androidx.compose.ui.graphics.Color.White
            ) { Icon(Icons.Outlined.Add, "New container") }
        }
    ) { padding ->
        if (containers.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        modifier = Modifier.size(74.dp),
                        shape = WinZShapes.ExtraLarge,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Dns, null, modifier = Modifier.size(36.dp)) }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("No containers", style = MaterialTheme.typography.titleLarge)
                    Text("Create a Windows environment for your games.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(containers, key = { it.id }) { container ->
                    SettingsContainerCard(container, onEdit, onProperties, onRun, onDuplicate, onRemove)
                }
            }
        }
    }

    propertiesContainer?.let {
        ContainerPropertiesDialog(it, onDismissProperties)
    }
}

@Composable
private fun SettingsContainerCard(
    container: Container,
    onEdit: (Int) -> Unit,
    onProperties: (Int) -> Unit,
    onRun: (Int) -> Unit,
    onDuplicate: (Int) -> Unit,
    onRemove: (Int) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = WinZShapes.Medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, hairlineColor())
    ) {
        Column {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(Modifier.size(50.dp), shape = WinZShapes.Medium, color = MaterialTheme.colorScheme.surfaceVariant) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Dns, null, modifier = Modifier.size(26.dp)) }
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(container.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(container.wineVersion, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Surface(
                    onClick = { onRun(container.id) },
                    modifier = Modifier.size(46.dp),
                    shape = WinZShapes.Medium,
                    color = controlAccentColor(),
                    contentColor = androidx.compose.ui.graphics.Color.White
                ) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.PlayArrow, null) }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ActionButton(Icons.Outlined.Edit, "Edit", Modifier.weight(1f)) { onEdit(container.id) }
                ActionButton(Icons.Outlined.Info, "Properties", Modifier.weight(1f)) { onProperties(container.id) }
                ContainerMoreButton(
                    modifier = Modifier.weight(1f),
                    onDuplicate = { onDuplicate(container.id) },
                    onRemove = { onRemove(container.id) }
                )
            }
        }
    }
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, action: () -> Unit) {
    Surface(
        onClick = action,
        modifier = modifier,
        color = Color.Transparent,
        shape = WinZShapes.Small
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ContainerMoreButton(
    modifier: Modifier = Modifier,
    onDuplicate: () -> Unit,
    onRemove: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        ActionButton(Icons.Outlined.MoreVert, "More", Modifier.fillMaxWidth()) { expanded = true }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = WinZShapes.Medium,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            DropdownMenuItem(
                text = { Text("Duplicate") },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                onClick = {
                    expanded = false
                    onDuplicate()
                }
            )
            DropdownMenuItem(
                text = { Text("Remove", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                onClick = {
                    expanded = false
                    onRemove()
                }
            )
        }
    }
}

@Composable
private fun ContainerPropertiesDialog(container: Container, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var refreshKey by remember(container.id) { mutableIntStateOf(0) }
    var loading by remember(container.id) { mutableStateOf(true) }
    var clearing by remember(container.id) { mutableStateOf(false) }
    var driveSize by remember(container.id) { mutableStateOf(0L) }
    var cacheSize by remember(container.id) { mutableStateOf(0L) }
    var totalSize by remember(container.id) { mutableStateOf(0L) }

    val rootDir = container.rootDir
    val driveCDir = remember(container.id) { File(rootDir, ".wine/drive_c") }
    val cacheDir = remember(container.id) { File(rootDir, ".cache") }

    LaunchedEffect(container.id, refreshKey) {
        loading = true
        val sizes = withContext(Dispatchers.IO) {
            Triple(
                directorySize(driveCDir),
                directorySize(cacheDir),
                directorySize(rootDir)
            )
        }
        driveSize = sizes.first
        cacheSize = sizes.second
        totalSize = sizes.third
        loading = false
    }

    val storageSize = FileUtils.getInternalStorageSize().coerceAtLeast(1L)
    val usedPercent = ((totalSize.toDouble() / storageSize.toDouble()) * 100.0)
        .toInt()
        .coerceIn(0, 100)

    ThemedDialog(onDismissRequest = onDismiss) {
        ThemedDialogTitle("Container properties", leading = { Icon(Icons.Outlined.Storage, null) })
        ThemedDialogScrollBody(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                container.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            HorizontalDivider(color = dividerColor())
            if (loading) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 18.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Calculating storage…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        StorageValue("Drive C", driveSize)
                        StorageValue("Cache", cacheSize)
                        StorageValue("Total", totalSize, emphasize = true)
                    }
                    Surface(
                        modifier = Modifier.size(90.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, hairlineColor())
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("$usedPercent%", style = MaterialTheme.typography.headlineSmall.tabularNumbers(), fontWeight = FontWeight.SemiBold)
                                Text("storage", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onDismiss,
                colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
                border = BorderStroke(1.dp, hairlineColor())
            ) { Text("Close") }
            Button(
                enabled = !clearing,
                onClick = {
                    if (!clearing) {
                        clearing = true
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                FileUtils.clear(cacheDir)
                                container.putExtra("desktopTheme", null)
                                container.saveData()
                            }
                            clearing = false
                            refreshKey++
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = controlAccentColor(), contentColor = androidx.compose.ui.graphics.Color.White)
            ) {
                if (clearing) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Clearing…")
                } else {
                    Text("Clear cache")
                }
            }
        }
    }
}

@Composable
private fun StorageValue(label: String, bytes: Long, emphasize: Boolean = false) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            StringUtils.formatBytes(bytes),
            style = if (emphasize) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.SemiBold
        )
    }
}

private fun directorySize(root: File): Long {
    if (!root.exists()) return 0L
    if (root.isFile) return root.length().coerceAtLeast(0L)

    var total = 0L
    val stack = ArrayDeque<File>()
    stack.add(root)
    while (stack.isNotEmpty()) {
        val current = stack.removeLast()
        if (FileUtils.isSymlink(current)) continue
        val files = current.listFiles() ?: continue
        for (file in files) {
            if (file.isDirectory) {
                if (!FileUtils.isSymlink(file)) stack.add(file)
            } else {
                total += file.length().coerceAtLeast(0L)
            }
        }
    }
    return total
}
