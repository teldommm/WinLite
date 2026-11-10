package com.winlator.cmod.ui.shell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.winlator.cmod.ui.ShellChrome
import com.winlator.cmod.ui.theme.TablerIcons
import com.winlator.cmod.ui.theme.WinZShapes
import com.winlator.cmod.ui.theme.hairlineColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Landscape status block: clock · network · battery, pinned top-end next to LandscapeNavGroup.
//
// Landscape runs fully immersive (WinZSystemBars hides the system bars), so none of this is
// visible there. Re-enabling the real status bar would cost ~24dp of height on every screen, so
// MainShell draws this small card in the header band instead — same card style as the nav group,
// same height, so the two read as one piece of chrome.
//
// Geometry is fixed on purpose (ShellChrome.landscapeStatusWidth): the header of every screen
// reserves ShellChrome.LandscapeNavReserve, and that number must not depend on what the clock or
// the battery currently says. Text is sized in dp (font scale ignored) and uses tabular digits for
// the same reason.
//
// Registrations live only while the card is composed (landscape, no detail screen) AND the
// activity is resumed — see rememberStatusState.

private enum class NetKind { None, Wifi, Cellular, Other }

@Stable
private class StatusState {
    var time by mutableStateOf("")
    var percent by mutableIntStateOf(-1)
    var charging by mutableStateOf(false)
    var network by mutableStateOf(NetKind.None)
}

@Composable
internal fun LandscapeStatusCluster(modifier: Modifier = Modifier) {
    val compact = ShellChrome.isCompactLandscape()
    val state = rememberStatusState()

    val onBg = MaterialTheme.colorScheme.onBackground
    val primary = MaterialTheme.colorScheme.primary
    val low = state.percent in 0..15 && !state.charging
    val batteryColor = when {
        low -> MaterialTheme.colorScheme.error
        state.charging -> primary
        else -> onBg.copy(alpha = .85f)
    }
    val textColor = onBg.copy(alpha = .85f)

    // Numbers only, so the description needs no string resources and reads the same in any locale.
    val description = if (state.percent >= 0) "${state.time}, ${state.percent}%" else state.time

    Surface(
        modifier = modifier
            .width(ShellChrome.landscapeStatusWidth(compact))
            .height(ShellChrome.LandscapeNavHeight)
            .semantics(mergeDescendants = true) { contentDescription = description },
        shape = WinZShapes.Large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, hairlineColor())
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            StatusText(state.time, Modifier.width(38.dp), 14, FontWeight.Medium, textColor)

            val netIcon: ImageVector = when (state.network) {
                NetKind.Wifi -> TablerIcons.Wifi
                NetKind.Cellular -> TablerIcons.Cellular
                NetKind.Other -> TablerIcons.Network
                NetKind.None -> TablerIcons.WifiOff
            }
            Icon(
                netIcon,
                contentDescription = null,
                tint = if (state.network == NetKind.None) onBg.copy(alpha = .5f) else textColor,
                modifier = Modifier.size(18.dp)
            )

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                BatteryGlyph(
                    percent = state.percent,
                    outline = onBg.copy(alpha = .55f),
                    fill = batteryColor
                )
                if (!compact) {
                    // The bolt lives inside the fixed-width percentage box, after the glyph, so it no
                    // longer needs its own always-reserved slot between the network icon and the
                    // battery (that empty slot was what made the gap look too wide). The box is a fixed
                    // width, so plugging in only moves the number inside it, never the other items.
                    Row(
                        Modifier.width(44.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start
                    ) {
                        if (state.charging) {
                            Icon(TablerIcons.Bolt, null, tint = primary, modifier = Modifier.size(11.dp))
                        }
                        StatusText(
                            if (state.percent >= 0) "${state.percent}%" else "",
                            Modifier,
                            12,
                            FontWeight.Medium,
                            if (low) MaterialTheme.colorScheme.error else textColor
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusText(text: String, modifier: Modifier, sizeDp: Int, weight: FontWeight, color: Color) {
    // dp-based size: this chrome has fixed geometry, so it ignores the system font scale.
    val size = with(LocalDensity.current) { sizeDp.dp.toSp() }
    Text(
        text = text,
        modifier = modifier,
        color = color,
        maxLines = 1,
        softWrap = false,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelLarge.copy(
            fontSize = size,
            fontWeight = weight,
            lineHeight = TextUnit.Unspecified,
            fontFeatureSettings = "tnum"
        )
    )
}

// 22 × 11dp battery: outlined body + terminal nub + level fill. Drawn rather than an icon so the
// fill actually follows the percentage (the Material battery icons are stepped).
@Composable
private fun BatteryGlyph(percent: Int, outline: Color, fill: Color) {
    Canvas(Modifier.size(width = 22.dp, height = 11.dp)) {
        val stroke = 1.2.dp.toPx()
        val nubW = 2.dp.toPx()
        val nubH = 5.dp.toPx()
        val bodyW = size.width - nubW

        drawRoundRect(
            color = outline,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(bodyW - stroke, size.height - stroke),
            cornerRadius = CornerRadius(2.5.dp.toPx()),
            style = Stroke(stroke)
        )
        drawRoundRect(
            color = outline,
            topLeft = Offset(bodyW, (size.height - nubH) / 2),
            size = Size(nubW, nubH),
            cornerRadius = CornerRadius(1.dp.toPx())
        )

        if (percent > 0) {
            val inset = stroke + 1.dp.toPx()
            val innerW = bodyW - inset * 2
            val fillW = (innerW * percent.coerceAtMost(100) / 100f).coerceAtLeast(1.5.dp.toPx())
            drawRoundRect(
                color = fill,
                topLeft = Offset(inset, inset),
                size = Size(fillW, size.height - inset * 2),
                cornerRadius = CornerRadius(1.2.dp.toPx())
            )
        }
    }
}

// Clock / battery / network, kept fresh while the activity is resumed. Receivers and the network
// callback are registered on resume and dropped on pause (or when the card leaves composition),
// and every value is refreshed synchronously on registration so there is no stale frame or
// "no network" flash when coming back.
@Composable
private fun rememberStatusState(): StatusState {
    val context = LocalContext.current.applicationContext
    val state = remember { StatusState() }

    LifecycleResumeEffect(context) {
        state.time = formatClock(context)
        state.network = currentNetworkKind(context)

        val clockReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                state.time = formatClock(c)
            }
        }
        val clockFilter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, clockReceiver, clockFilter, ContextCompat.RECEIVER_NOT_EXPORTED)

        val batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                applyBattery(state, intent)
            }
        }
        // ACTION_BATTERY_CHANGED is sticky: registering returns the current value right away.
        ContextCompat.registerReceiver(
            context, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )?.let { applyBattery(state, it) }

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val netCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                state.network = caps.toKind()
            }

            override fun onLost(network: Network) {
                state.network = NetKind.None
            }
        }
        val netRegistered = runCatching {
            cm?.registerDefaultNetworkCallback(netCallback, Handler(Looper.getMainLooper()))
        }.isSuccess

        onPauseOrDispose {
            runCatching { context.unregisterReceiver(clockReceiver) }
            runCatching { context.unregisterReceiver(batteryReceiver) }
            if (netRegistered) runCatching { cm?.unregisterNetworkCallback(netCallback) }
        }
    }
    return state
}

private fun formatClock(context: Context): String {
    // Same 12/24h choice as the system clock. No AM/PM: the card has a fixed width.
    val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
    // A new formatter each time so a timezone change is picked up.
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date())
}

private fun applyBattery(state: StatusState, intent: Intent) {
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level >= 0 && scale > 0) state.percent = (level * 100 / scale).coerceIn(0, 100)
    state.charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
}

private fun currentNetworkKind(context: Context): NetKind {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return NetKind.None
    return runCatching { cm.getNetworkCapabilities(cm.activeNetwork).toKind() }.getOrDefault(NetKind.None)
}

private fun NetworkCapabilities?.toKind(): NetKind = when {
    this == null -> NetKind.None
    hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetKind.Wifi
    hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetKind.Cellular
    hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetKind.Other
    else -> NetKind.None
}
