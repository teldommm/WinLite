package com.winlator.cmod.steam

import com.winlator.cmod.ui.theme.TablerIcons
import com.winlator.cmod.ui.uiStr

import com.winlator.cmod.R

import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bumptech.glide.Glide
import com.winlator.cmod.ui.theme.WinZOverlayTheme
import com.winlator.cmod.ui.theme.WinZShapes
import com.winlator.cmod.ui.theme.controlAccentColor
import com.winlator.cmod.ui.theme.dividerColor

object SteamSheets {
    /** Hosts [content] in a WinLite-style bottom sheet (translucent surface, shared system-bar policy) over the activity. */
    private fun show(context: Context, content: @Composable (() -> Unit) -> Unit) {
        val activity = context as? AppCompatActivity ?: return
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val view = ComposeView(activity)
        val dismiss = { root.removeView(view); Unit }
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        root.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view.setContent { WinZOverlayTheme {
            SteamSheet(onDismiss = dismiss) { content(dismiss) }
        } }
    }
    @Composable fun MenuEntries(context: Context, dismiss: () -> Unit) {
        listOf(uiStr(R.string.steam_ui_manage_games) to TablerIcons.DeviceGamepad2, uiStr(R.string.steam_ui_manage_downloads) to TablerIcons.Download).forEachIndexed { index, (title, icon) ->
            DropdownMenuItem(text = { Text(title) }, leadingIcon = { Icon(icon, null) }, onClick = {
                dismiss(); context.startActivity(Intent(context, SteamActivity::class.java).putExtra("steam_tab", index))
            })
        }
    }
    fun game(context: Context, id: Int) {
        if (!SteamSettings.enabled(context)) return
        context.startForegroundService(Intent(context, SteamService::class.java))
        show(context) { dismiss ->
            val state = observeSteam()
            val apps = rememberSteamLibrary(context, state.library)
            val demos = rememberSteamLibrary(context, state.library, filterType = "demo")
            val item = remember(apps, demos, id) { (apps + demos).find { app -> SteamLibrary.appId(app.shortcutPath) == id } }
            var favorite by remember { mutableStateOf(context.getSharedPreferences("steam-settings", 0).getBoolean("favorite-$id", false)) }
            // Header: cover and name, then WinLite's hairline divider (same layout as the Library game menu).
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                AndroidView(factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.CENTER_CROP } },
                    update = { Glide.with(it).load("https://shared.steamstatic.com/store_item_assets/steam/apps/$id/library_600x900_2x.jpg").into(it) },
                    onRelease = { Glide.with(it).clear(it) },
                    modifier = Modifier.size(40.dp, 60.dp).clip(WinZShapes.Small))
                Text(item?.name ?: uiStr(R.string.steam_ui_game), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), color = dividerColor())
            val installed = java.io.File(context.filesDir, "steam-library/$id.json").isFile
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SteamAccentButton(
                    if (installed) uiStr(R.string.steam_ui_view_game) else uiStr(R.string.steam_ui_download),
                    Modifier.weight(1f),
                    enabled = installed || (state.signedIn && !state.downloading && !state.gameActive),
                    icon = if (installed) TablerIcons.DeviceGamepad2 else TablerIcons.Download
                ) {
                    if (installed) { dismiss(); SteamLibrary.open(context, "steam://$id") } else SteamSession.install(id)
                }
                SteamActionTile(
                    if (favorite) TablerIcons.HeartFilled else TablerIcons.Heart,
                    if (favorite) uiStr(R.string.steam_ui_unfavorite) else uiStr(R.string.steam_ui_favorite),
                    Modifier.weight(1f),
                    tint = if (favorite) controlAccentColor() else MaterialTheme.colorScheme.onSurface
                ) {
                    favorite = !favorite; context.getSharedPreferences("steam-settings", 0).edit().putBoolean("favorite-$id", favorite).apply(); SteamGames.changed()
                }
            }
            Spacer(Modifier.height(4.dp))
            SteamStoreDetails(id, installed, state)
            if (state.downloading && state.id == id) SteamDownloadPanel(state)
            else if (!state.signedIn) {
                Text(steamStatusText(state.status), Modifier.padding(horizontal = 16.dp, vertical = 8.dp).heightIn(max = 48.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!state.restoring && !state.gameActive) SteamSecondaryButton(
                    if (state.savedAccount) uiStr(R.string.steam_ui_retry_connection) else uiStr(R.string.steam_ui_connect),
                    Modifier.padding(horizontal = 16.dp), icon = if (state.savedAccount) TablerIcons.Refresh else TablerIcons.BrandSteam
                ) {
                    if (state.savedAccount) SteamSession.reconnect() else { dismiss(); context.startActivity(Intent(context, SteamActivity::class.java)) }
                }
            }
        }
    }
}
