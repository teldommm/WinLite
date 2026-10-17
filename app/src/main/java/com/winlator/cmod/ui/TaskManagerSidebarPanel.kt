package com.winlator.cmod.ui

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FlipToFront
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.ui.theme.controlAccentColor
import com.winlator.cmod.ui.theme.destructiveColor
import com.winlator.cmod.ui.theme.sidebarCardFillColor
import com.winlator.cmod.ui.theme.hairlineColor

// One process row. rawName is the exact name WinHandler/Windows reports (used for the
// bring-to-front/kill/affinity calls); displayName additionally carries the " *32" suffix
// for WOW64 processes — cosmetic only, never sent back to WinHandler.
data class ProcessRowData(
    val pid: Int,
    val rawName: String,
    val displayName: String,
    val pidLabel: String,
    val memoryLabel: String,
    val affinityMask: Int,
    val iconBitmap: Bitmap?
)

interface TaskManagerCallbacks {
    fun onNewTask()
    fun onBringToFront(pid: Int, name: String)
    fun onEndProcess(pid: Int, name: String)
    fun onProcessorAffinity(pid: Int, name: String, affinityMask: Int)
}

// Plain observable holder, not a data class — TaskManagerSidebar.java mutates these
// properties directly (they compile to normal getX()/setX() from Java) on every timer
// tick / onGetProcessInfo callback, and Compose recomposes whatever actually changed.
class TaskManagerPanelState {
    var cpuLabel: String by mutableStateOf("--%")
    var memoryLabel: String by mutableStateOf("-- / --")
    var processes: List<ProcessRowData> by mutableStateOf(emptyList())
}

object TaskManagerPanelHost {
    @JvmStatic
    fun attach(sidebar: IngameSidebarController, panelId: Int, callbacks: TaskManagerCallbacks): TaskManagerPanelState {
        val state = TaskManagerPanelState()
        sidebar.setPanel(panelId) { TaskManagerPanel(state, callbacks) }
        return state
    }
}

@Composable
private fun TaskManagerPanel(state: TaskManagerPanelState, callbacks: TaskManagerCallbacks) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        SidebarPanelTitle(uiStr(R.string.ui_task_manager))

        Row(Modifier.fillMaxWidth().height(66.dp)) {
            MetricCard(modifier = Modifier.weight(0.7f), title = "CPU", value = state.cpuLabel)
            Spacer(Modifier.width(10.dp))
            MetricCard(modifier = Modifier.weight(1.3f), title = uiStr(R.string.memory), value = state.memoryLabel)
        }

        Spacer(Modifier.height(14.dp))
        SidebarSectionTitle(uiStr(R.string.ui_processes, state.processes.size))
        Spacer(Modifier.height(8.dp))

        if (state.processes.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().height(160.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.no_items_to_display),
                    style = SidebarText.caption(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            state.processes.forEach { row ->
                key(row.pid) {
                    Column {
                        ProcessRow(row = row, callbacks = callbacks)
                        SidebarGap()
                    }
                }
            }
        }

        SidebarActionRow(label = uiStr(R.string.ui_new_task), accent = true, onClick = callbacks::onNewTask)
    }
}

@Composable
private fun MetricCard(modifier: Modifier, title: String, value: String) {
    val shape = sidebarCardShape()
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(shape)
            .background(sidebarCardFillColor())
            .border(BorderStroke(1.dp, hairlineColor()), shape)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Text(
            text = title,
            style = SidebarText.small().copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium,
            color = controlAccentColor()
        )
    }
}

@Composable
private fun ProcessRow(row: ProcessRowData, callbacks: TaskManagerCallbacks) {
    var menuExpanded by remember { mutableStateOf(false) }
    val shape = sidebarCardShape()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 62.dp)
            .clip(shape)
            .background(sidebarCardFillColor())
            .border(BorderStroke(1.dp, hairlineColor()), shape)
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val icon = row.iconBitmap
        if (icon != null) {
            Image(
                bitmap = icon.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
        } else {
            Icon(
                painter = painterResource(id = R.drawable.taskmgr_process),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = Color.Unspecified
            )
        }

        Spacer(Modifier.width(10.dp))

        // Memory moved from its own 72dp column onto the PID line, so the name gets the full
        // width; long names wrap to a second line instead of ending in "…".
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                text = row.displayName,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (row.memoryLabel.isNotEmpty()) "${row.pidLabel} · ${row.memoryLabel}" else row.pidLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = SidebarText.small(),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Box {
            IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(32.dp)) {
                Icon(
                    painter = painterResource(id = R.drawable.ui_ic_more),
                    contentDescription = uiStr(R.string.ui_more_options),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SidebarMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                SidebarActionItem(
                    label = stringResource(R.string.processor_affinity),
                    leadingIcon = { Icon(Icons.Outlined.Memory, null) },
                    onClick = {
                        menuExpanded = false
                        callbacks.onProcessorAffinity(row.pid, row.rawName, row.affinityMask)
                    }
                )
                SidebarActionItem(
                    label = stringResource(R.string.bring_to_front),
                    leadingIcon = { Icon(Icons.Outlined.FlipToFront, null) },
                    onClick = {
                        menuExpanded = false
                        callbacks.onBringToFront(row.pid, row.rawName)
                    }
                )
                SidebarActionItem(
                    label = stringResource(R.string.end_process),
                    leadingIcon = { Icon(Icons.Outlined.Close, null) },
                    onClick = {
                        menuExpanded = false
                        callbacks.onEndProcess(row.pid, row.rawName)
                    }
                )
            }
        }
    }
}
