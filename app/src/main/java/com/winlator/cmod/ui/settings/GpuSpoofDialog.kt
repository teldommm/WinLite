@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.winlator.cmod.ui.settings

import com.winlator.cmod.R
import com.winlator.cmod.core.GpuSpoof
import com.winlator.cmod.ui.uiStr
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.ui.theme.ThemedDialog
import com.winlator.cmod.ui.theme.ThemedDialogScrollBody
import com.winlator.cmod.ui.theme.ThemedDialogTitle
import com.winlator.cmod.ui.theme.WinZShapes
import com.winlator.cmod.ui.theme.controlAccentColor
import com.winlator.cmod.ui.theme.hairlineColor

/**
 * "GPU Spoof" row of the Video settings (container editor, shortcut editor, container overview).
 * Shows what is spoofed and opens [GpuSpoofDialog]; [onChanged] receives the whole updated
 * graphicsDriverConfig (the spoof keys rewritten in one go, so the caller saves once).
 */
@Composable
internal fun GpuSpoofSettingRow(
    graphicsConfig: String,
    onChanged: (String) -> Unit
) {
    val spoof = remember(graphicsConfig) { GpuSpoof.fromConfig(graphicsConfig) }
    var showDialog by remember { mutableStateOf(false) }
    val summary = when {
        spoof.isEmpty -> uiStr(R.string.ui_off)
        spoof.deviceName.isNotBlank() -> spoof.deviceName
        else -> uiStr(R.string.ui_gpu_spoof_overridden, spoof.overriddenCount)
    }
    Surface(
        onClick = { showDialog = true },
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp)) {
            Text(uiStr(R.string.ui_gpu_spoof), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                summary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    // ThemedDialog (Compose Dialog), not ThemedAlertHost: it attaches to whichever window hosts
    // the composition, so it also shows over the shortcut editor's own ComponentDialog window.
    if (showDialog) {
        GpuSpoofDialog(
            initial = spoof,
            onDismiss = { showDialog = false },
            onDone = {
                showDialog = false
                onChanged(it.writeTo(graphicsConfig))
            }
        )
    }
}

/**
 * One field per WRAPPER_* spoof variable; an empty field keeps the real value. IDs take decimal
 * or 0x hex, the driver version a raw number or a dotted version. Done stays disabled while a
 * field is malformed.
 */
@Composable
internal fun GpuSpoofDialog(
    initial: GpuSpoof,
    onDismiss: () -> Unit,
    onDone: (GpuSpoof) -> Unit
) {
    val accent = controlAccentColor()
    var vendorId by remember { mutableStateOf(initial.vendorId) }
    var deviceId by remember { mutableStateOf(initial.deviceId) }
    var deviceName by remember { mutableStateOf(initial.deviceName) }
    var driverId by remember { mutableStateOf(initial.driverId) }
    var driverName by remember { mutableStateOf(initial.driverName) }
    var driverInfo by remember { mutableStateOf(initial.driverInfo) }
    var driverVersion by remember { mutableStateOf(initial.driverVersion) }

    val current = GpuSpoof(vendorId, deviceId, deviceName, driverId, driverName, driverInfo, driverVersion)

    ThemedDialog(onDismissRequest = onDismiss) {
        ThemedDialogTitle(uiStr(R.string.ui_gpu_spoof))
        ThemedDialogScrollBody {
            Text(
                uiStr(R.string.ui_gpu_spoof_hint),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SpoofSectionHeader(uiStr(R.string.ui_gpu_spoof_device))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SpoofField(
                    label = uiStr(R.string.ui_gpu_spoof_vendor_id),
                    value = vendorId,
                    placeholder = "0x10de",
                    isError = !GpuSpoof.isValidId(vendorId),
                    keyboardType = KeyboardType.Ascii,
                    modifier = Modifier.weight(1f)
                ) { vendorId = GpuSpoof.sanitizeId(it) }
                SpoofField(
                    label = uiStr(R.string.ui_gpu_spoof_device_id),
                    value = deviceId,
                    placeholder = "0x2684",
                    isError = !GpuSpoof.isValidId(deviceId),
                    keyboardType = KeyboardType.Ascii,
                    modifier = Modifier.weight(1f)
                ) { deviceId = GpuSpoof.sanitizeId(it) }
            }
            SpoofField(
                label = uiStr(R.string.ui_gpu_spoof_device_name),
                value = deviceName,
                placeholder = "NVIDIA GeForce RTX 4090",
                modifier = Modifier.fillMaxWidth()
            ) { deviceName = GpuSpoof.sanitizeText(it) }

            SpoofSectionHeader(uiStr(R.string.ui_gpu_spoof_driver))
            Text(
                uiStr(R.string.ui_gpu_spoof_driver_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SpoofField(
                    label = uiStr(R.string.ui_gpu_spoof_driver_id),
                    value = driverId,
                    placeholder = "4",
                    isError = !GpuSpoof.isValidId(driverId),
                    keyboardType = KeyboardType.Ascii,
                    modifier = Modifier.weight(1f)
                ) { driverId = GpuSpoof.sanitizeId(it) }
                SpoofField(
                    label = uiStr(R.string.ui_gpu_spoof_driver_version),
                    value = driverVersion,
                    placeholder = "560.94",
                    isError = !GpuSpoof.isValidDriverVersion(driverVersion),
                    keyboardType = KeyboardType.Ascii,
                    modifier = Modifier.weight(1f)
                ) { driverVersion = GpuSpoof.sanitizeVersion(it) }
            }
            SpoofField(
                label = uiStr(R.string.ui_gpu_spoof_driver_name),
                value = driverName,
                placeholder = "NVIDIA",
                modifier = Modifier.fillMaxWidth()
            ) { driverName = GpuSpoof.sanitizeText(it) }
            SpoofField(
                label = uiStr(R.string.ui_gpu_spoof_driver_info),
                value = driverInfo,
                placeholder = "560.94",
                imeAction = ImeAction.Done,
                modifier = Modifier.fillMaxWidth()
            ) { driverInfo = GpuSpoof.sanitizeText(it) }
        }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = {
                    vendorId = ""; deviceId = ""; deviceName = ""
                    driverId = ""; driverName = ""; driverInfo = ""; driverVersion = ""
                },
                enabled = !current.isEmpty
            ) { Text(uiStr(R.string.ui_gpu_spoof_reset), color = if (current.isEmpty) MaterialTheme.colorScheme.onSurfaceVariant else accent) }
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = onDismiss,
                colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
                border = BorderStroke(1.dp, hairlineColor())
            ) { Text(uiStr(R.string.cancel)) }
            Spacer(Modifier.width(10.dp))
            Button(
                onClick = { onDone(current.normalized()) },
                enabled = current.isValid,
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent,
                    contentColor = Color.White,
                    disabledContainerColor = accent.copy(alpha = 0.35f),
                    disabledContentColor = Color.White.copy(alpha = 0.6f)
                )
            ) { Text(uiStr(R.string.ui_done)) }
        }
    }
}

@Composable
private fun SpoofSectionHeader(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SpoofField(
    label: String,
    value: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onValueChange: (String) -> Unit
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.padding(top = 4.dp),
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        placeholder = {
            Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), maxLines = 1)
        },
        isError = isError,
        singleLine = true,
        shape = WinZShapes.Small,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
    )
}
