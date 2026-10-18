package com.winlator.cmod.ui

import com.winlator.cmod.ui.theme.DialogScrimColor
import com.winlator.cmod.ui.theme.WinZMotion
import androidx.activity.compose.BackHandler

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.winlator.cmod.MainActivity
import com.winlator.cmod.R
import com.winlator.cmod.ui.theme.WinZOverlayTheme
import com.winlator.cmod.ui.theme.controlAccentColor
import com.winlator.cmod.ui.theme.WinZShapes
import com.winlator.cmod.ui.theme.hairlineColor
import com.winlator.cmod.ui.theme.dividerColor

// Native bridge so any Java call site (nav-drawer "About" item, Settings row) shows
// the exact same Compose content — a single implementation, no risk of desync.
//
// Added directly into the Activity's own content view hierarchy (android.R.id.content)
// rather than a separate android.app.Dialog window: that hierarchy is already attached to
// the same window Compose already runs in elsewhere in this app (via fragments), so the
// ComposeView finds its lifecycle/viewmodel/saved-state owners for free.
object AboutDialogHost {
    @JvmStatic
    fun show(activity: MainActivity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        lateinit var composeView: ComposeView
        composeView = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WinZOverlayTheme {
                    val visibleState = remember { MutableTransitionState(false) }
                    LaunchedEffect(Unit) { visibleState.targetState = true }
                    LaunchedEffect(visibleState.currentState) {
                        if (!visibleState.currentState && !visibleState.targetState) {
                            (composeView.parent as? ViewGroup)?.removeView(composeView)
                        }
                    }
                    val dismiss: () -> Unit = { visibleState.targetState = false }
                    // System back closes this overlay instead of reaching the screen behind it
                    // (it lives in the activity's own view tree, not a Dialog window).
                    BackHandler(enabled = visibleState.targetState) { dismiss() }
                    AnimatedVisibility(
                        visibleState = visibleState,
                        enter = WinZMotion.dialogScrimEnter(),
                        exit = WinZMotion.dialogScrimExit()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(DialogScrimColor)
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                    onClick = dismiss
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            AnimatedVisibility(
                                visibleState = visibleState,
                                enter = WinZMotion.dialogCardEnter(),
                                exit = WinZMotion.dialogCardExit()
                            ) {
                                Box(modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}) {
                                    AboutDialogContent(onDismiss = dismiss)
                                }
                            }
                        }
                    }
                }
            }
        }
        root.addView(
            composeView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }
}

@Composable
private fun AboutDialogContent(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (e: PackageManager.NameNotFoundException) {
            ""
        }
    }
    Surface(
        modifier = Modifier.widthIn(max = 340.dp),
        shape = WinZShapes.Large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, hairlineColor())
    ) {
        Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Winlator CMOD", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(linkText("winlator.org" to "https://www.winlator.org"))
                    Text(
                        "${stringResource(R.string.version)} $versionName",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                val appIcon = remember {
                    val drawable = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
                    val size = 108
                    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    drawable?.setBounds(0, 0, size, size)
                    drawable?.draw(canvas)
                    bitmap.asImageBitmap()
                }
                Image(appIcon, null, Modifier.size(56.dp))
            }

            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = dividerColor())

            Text(stringResource(R.string.credits_and_third_party_apps), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(linkText("WinLite by teldommm (", "Fork" to "https://github.com/teldommm/WinLite", ")"))
            Text(
                linkText(
                    "Winlator Ludashi by StevenMX, pipetto-crypto (",
                    "Fork" to "https://github.com/StevenMXZ/Winlator-Ludashi",
                    ", ",
                    "Fork" to "https://github.com/Pipetto-crypto/winlator",
                    ")"
                )
            )
            Text(linkText("Termux Package(", "github.com/termux/termux-package" to "https://github.com/termux/termux-packages", ")"))
            Text(linkText("Wine (", "winehq.org" to "https://www.winehq.org", ")"))
            Text(linkText("Box64 (", "github.com/ptitSeb/box64" to "https://github.com/ptitSeb/box64", ")"))
            Text(linkText("Mesa (Turnip/Zink/Wrapper) (", "github.com/xMeM/mesa" to "https://github.com/xMeM/mesa/tree/wrapper", ")"))
            Text(linkText("DXVK (", "github.com/doitsujin/dxvk" to "https://github.com/doitsujin/dxvk", ")"))
            Text(linkText("VKD3D (", "gitlab.winehq.org/wine/vkd3d" to "https://gitlab.winehq.org/wine/vkd3d", ")"))
            Text(linkText("D8VK (", "github.com/AlpyneDreams/d8vk" to "https://github.com/AlpyneDreams/d8vk", ")"))
            Text(linkText("CNC DDraw (", "github.com/FunkyFr3sh/cnc-ddraw" to "https://github.com/FunkyFr3sh/cnc-ddraw", ")"))
            Text(linkText("dxwrapper (", "github.com/elishacloud/dxwrapper" to "https://github.com/elishacloud/dxwrapper", ")"))
            Text(linkText("FEX-Emu (", "github.com/FEX-Emu/FEX" to "https://github.com/FEX-Emu/FEX", ")"))
            Text(linkText("libadrenotools (", "github.com/bylaws/libadrenotools" to "https://github.com/bylaws/libadrenotools", ")"))

            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.glibc_exp_edition), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(linkText("longjunyu2's ", "(GLIBC Fork)" to "https://github.com/longjunyu2/winlator/tree/use-glibc-instead-of-proot"))

            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
                border = BorderStroke(1.dp, hairlineColor())
            ) { Text(stringResource(R.string.ui_close)) }
        }
    }
}

@Composable
private fun linkText(vararg parts: Any): AnnotatedString {
    val accent = controlAccentColor()
    return buildAnnotatedString {
        for (part in parts) {
            when (part) {
                is Pair<*, *> -> {
                    val label = part.first as String
                    val url = part.second as String
                    withLink(LinkAnnotation.Url(url, TextLinkStyles(style = SpanStyle(color = accent, textDecoration = TextDecoration.Underline)))) {
                        append(label)
                    }
                }
                else -> append(part.toString())
            }
        }
    }
}
