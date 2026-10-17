package com.winlator.cmod.ui.inputcontrols

import com.winlator.cmod.ui.theme.WinZMotion
import com.winlator.cmod.ui.theme.ThemedDialogTitle
import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.ui.theme.WinZTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.winlator.cmod.ui.theme.ThemedDialog
import com.winlator.cmod.ui.theme.WinZShapes
import com.winlator.cmod.ui.theme.controlAccentColor
import com.winlator.cmod.ui.theme.hairlineColor

data class ExternalControllerBindingItem(
    val keyCode: Int,
    val title: String,
    val type: Int,
    val binding: String
)

interface ExternalControllerBindingsCallbacks {
    fun onBack()
    fun onRemove(keyCode: Int)
    fun onBindingSelected(keyCode: Int, type: Int, position: Int)
}

class ExternalControllerBindingsState {
    var items by mutableStateOf<List<ExternalControllerBindingItem>>(emptyList())
        private set
    var activeKeyCode by mutableStateOf<Int?>(null)
        private set
    var activation by mutableStateOf(0L)
        private set

    fun update(items: List<ExternalControllerBindingItem>) {
        this.items = items.toList()
    }

    fun activate(keyCode: Int) {
        activeKeyCode = keyCode
        activation++
    }
}

object ExternalControllerBindingsComposeHost {
    @JvmStatic
    fun create(
        context: Context,
        title: String,
        state: ExternalControllerBindingsState,
        bindingLabels: List<List<String>>,
        callbacks: ExternalControllerBindingsCallbacks
    ): ComposeView = ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            WinZTheme {
                ExternalControllerBindingsScreen(title, state, bindingLabels, callbacks)
            }
        }
    }
}

@Composable
private fun ExternalControllerBindingsScreen(
    title: String,
    state: ExternalControllerBindingsState,
    bindingLabels: List<List<String>>,
    callbacks: ExternalControllerBindingsCallbacks
) {
    val listState = rememberLazyListState()
    val types = stringArrayResource(R.array.binding_type_entries)
    var editingKey by rememberSaveable { mutableStateOf<Int?>(null) }
    val editingItem = state.items.firstOrNull { it.keyCode == editingKey }
    val accent = controlAccentColor()

    LaunchedEffect(state.activation) {
        val index = state.items.indexOfFirst { it.keyCode == state.activeKeyCode }
        if (index >= 0) listState.scrollToItem(index)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = callbacks::onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            stringResource(androidx.appcompat.R.string.abc_action_bar_up_description)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        if (state.items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), Alignment.Center) {
                Text(
                    stringResource(R.string.press_any_button_on_your_controller),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.items, key = { it.keyCode }) { item ->
                    val flash = remember { Animatable(0f) }
                    var choosingType by remember { mutableStateOf(false) }
                    LaunchedEffect(state.activation) {
                        if (state.activeKeyCode == item.keyCode) {
                            flash.snapTo(0.4f)
                            flash.animateTo(0f, WinZMotion.standard(WinZMotion.DurationM))
                        } else {
                            flash.snapTo(0f)
                        }
                    }
                    // Same card as every other list in the app; the "button pressed" flash is the
                    // shared accent instead of the (near-white) primary.
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = WinZShapes.Medium,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, hairlineColor())
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .background(accent.copy(alpha = flash.value))
                                .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(item.title, style = MaterialTheme.typography.titleMedium)
                                Box {
                                    BindingField(types[item.type]) { choosingType = true }
                                    DropdownMenu(
                                        expanded = choosingType,
                                        onDismissRequest = { choosingType = false },
                                        shape = WinZShapes.Medium,
                                        containerColor = MaterialTheme.colorScheme.surface
                                    ) {
                                        types.forEachIndexed { index, label ->
                                            val isSelected = index == item.type
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        label,
                                                        color = if (isSelected) accent else MaterialTheme.colorScheme.onSurface,
                                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                                    )
                                                },
                                                trailingIcon = { if (isSelected) Icon(Icons.Outlined.Check, null, tint = accent) },
                                                onClick = {
                                                    choosingType = false
                                                    if (index != item.type) {
                                                        callbacks.onBindingSelected(item.keyCode, index, 0)
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                                BindingField(item.binding) { editingKey = item.keyCode }
                            }
                            IconButton(onClick = { callbacks.onRemove(item.keyCode) }) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    stringResource(R.string.remove) + ": " + item.title,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Was a stock Material3 AlertDialog (opaque, different surface, OS-localized Cancel);
    // now the app's own ThemedDialog with the shared selected-item + checkmark list style.
    if (editingItem != null) {
        val labels = bindingLabels[editingItem.type]
        ThemedDialog(onDismissRequest = { editingKey = null }) {
            ThemedDialogTitle(stringResource(R.string.binding) + ": " + editingItem.title)
            val selectionState = rememberLazyListState(
                initialFirstVisibleItemIndex = labels.indexOf(editingItem.binding).coerceAtLeast(0)
            )
            LazyColumn(state = selectionState, modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 380.dp)) {
                items(labels.size) { index ->
                    val label = labels[index]
                    val isSelected = label == editingItem.binding
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clip(WinZShapes.Small)
                            .clickable {
                                callbacks.onBindingSelected(editingItem.keyCode, editingItem.type, index)
                                editingKey = null
                            }
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            label,
                            modifier = Modifier.weight(1f),
                            color = if (isSelected) accent else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )
                        if (isSelected) Icon(Icons.Outlined.Check, null, tint = accent)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.align(Alignment.End)) {
                OutlinedButton(
                    onClick = { editingKey = null },
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    border = BorderStroke(1.dp, hairlineColor())
                ) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}

// Field-style picker button (value + arrow), matching the container/shortcut settings pickers
// instead of the stock primary-tinted OutlinedButton.
@Composable
private fun BindingField(value: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(44.dp),
        shape = WinZShapes.Medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, hairlineColor())
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(value, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Outlined.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
