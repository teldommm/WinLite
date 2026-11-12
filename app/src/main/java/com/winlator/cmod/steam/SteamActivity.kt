package com.winlator.cmod.steam

import com.winlator.cmod.ui.uiStr

import com.winlator.cmod.R

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.bumptech.glide.Glide
import com.winlator.cmod.XServerDisplayActivity
import com.winlator.cmod.container.ContainerManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import com.winlator.cmod.ui.theme.FadeInOnShow
import com.winlator.cmod.ui.theme.WinZMotion
import com.winlator.cmod.ui.theme.TablerIcons
import com.winlator.cmod.ui.theme.WinZShapes
import com.winlator.cmod.ui.theme.WinZTheme
import com.winlator.cmod.ui.theme.controlAccentColor
import org.json.JSONObject
import java.io.File

class SteamActivity : ImmersiveSteamActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        supportFragmentManager.addOnBackStackChangedListener { if (supportFragmentManager.backStackEntryCount == 0) detailOpening = false }
        com.winlator.cmod.ui.applyAppFullscreen(this)
        if (!SteamSettings.enabled(this)) { startActivity(Intent(this, StoreSettingsActivity::class.java)); finish(); return }
        startForegroundService(Intent(this, SteamService::class.java))
        if (savedInstanceState != null && supportFragmentManager.findFragmentById(android.R.id.content) is SteamGameDetailFragment) return
        val selectedId = intent.getIntExtra("steam_app_id", 0)
        if (selectedId > 0 && isInstalled(selectedId)) showDetail(selectedId)
        else setContent { WinZTheme { SteamScreen() } }
    }
    @Composable private fun SteamScreen() {
        val state = observeSteam()
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (state.signedIn || state.savedAccount) {
                SteamManagerScreen(state, intent.getIntExtra("steam_tab", 0), { finish() }, ::signOut, ::showDetail)
            } else {
                Column(Modifier.fillMaxSize().imePadding()) {
                    SteamTopBar("Steam", null, onBack = { finish() }, modifier = Modifier.windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)))
                    LoginForm(state)
                }
            }
        }
    }
    @Composable private fun LoginForm(state: SteamUiSnapshot) {
        var username by rememberSaveable { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var visible by remember { mutableStateOf(false) }
        var code by remember { mutableStateOf("") }
        var qr by rememberSaveable { mutableStateOf(false) }
        val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        DisposableEffect(Unit) {
            onDispose { if (!SteamSession.savedAccount && SteamSession.signingIn) SteamSession.cancelLogin() }
        }
        LaunchedEffect(qr, state.ready) {
            if (qr && state.ready && !SteamSession.signingIn) SteamSession.loginQr()
        }
        val introduction: @Composable () -> Unit = {
            Icon(TablerIcons.BrandSteam, null, Modifier.size(if (landscape) 48.dp else 64.dp), tint = MaterialTheme.colorScheme.onBackground)
            Text(uiStr(R.string.steam_ui_login_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
            Text(uiStr(R.string.steam_ui_login_intro), color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        val form: @Composable () -> Unit = {
                    SteamSegmented(
                        listOf(SteamTabItem(0, uiStr(R.string.steam_login_account), TablerIcons.User), SteamTabItem(1, uiStr(R.string.steam_login_qr), TablerIcons.QrCode)),
                        if (qr) 1 else 0, { index ->
                            val value = index == 1
                            if (qr != value) { SteamSession.cancelLogin(); password = ""; code = ""; qr = value }
                        })
                    AnimatedContent(if (qr) 1 else if (state.guard.isNotEmpty()) 2 else 0,
                        transitionSpec = { (fadeIn(WinZMotion.enter()) + scaleIn(WinZMotion.enter(), initialScale = 0.96f)) togetherWith fadeOut(WinZMotion.exit()) },
                        label = "steamLoginMode") { mode ->
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (mode == 1) {
                        SteamQrCode(SteamSession.qrUrl, if (landscape) 150.dp else 240.dp)
                        if (!landscape) Text(uiStr(R.string.steam_qr_scan), style = MaterialTheme.typography.titleLarge)
                        if (!landscape) Text(uiStr(R.string.steam_qr_help), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SteamOutlineButton(uiStr(R.string.steam_qr_refresh), Modifier.fillMaxWidth(), enabled = state.ready && !state.gameActive, icon = TablerIcons.Refresh) {
                            SteamSession.cancelLogin(); SteamSession.loginQr()
                        }
                    } else if (mode == 2) {
                        Text(steamStatusText(state.guard), style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(code, { code = it }, singleLine = true, label = { Text(uiStr(R.string.steam_ui_guard_code)) }, shape = WinZShapes.Medium, modifier = Modifier.fillMaxWidth())
                        Button(onClick = { SteamSession.guard.submit(code); code = "" }, enabled = code.isNotBlank(), shape = WinZShapes.Medium, colors = steamButtonColors(), modifier = Modifier.fillMaxWidth()) { Text(uiStr(R.string.steam_ui_confirm_code)) }
                    } else {
                        OutlinedTextField(username, { username = it }, placeholder = { Text(uiStr(R.string.steam_ui_account_name)) },
                            leadingIcon = { Icon(TablerIcons.User, null) }, singleLine = true, enabled = !state.busy,
                            shape = WinZShapes.Medium, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(password, { password = it }, placeholder = { Text(uiStr(R.string.steam_ui_password)) },
                            leadingIcon = { Icon(TablerIcons.Lock, null) },
                            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) TablerIcons.EyeOff else TablerIcons.Eye, uiStr(R.string.steam_ui_show_password)) } },
                            singleLine = true, enabled = !state.busy, shape = WinZShapes.Medium, modifier = Modifier.fillMaxWidth())
                        Button(onClick = { SteamSession.login(username, password); password = ""; visible = false },
                            enabled = state.ready && !state.busy && username.isNotBlank() && password.isNotEmpty(),
                            shape = WinZShapes.Medium, colors = steamButtonColors(), modifier = Modifier.fillMaxWidth().height(50.dp)) {
                            if (state.busy) com.winlator.cmod.ui.theme.WinZSpinner(22.dp, 2.dp, color = Color.White) else Text(uiStr(R.string.steam_ui_sign_in))
                        }
                    }
                    }
                    }
                    if (state.authError.isNotEmpty()) Text(steamStatusText(state.authError), color = MaterialTheme.colorScheme.error)
                    Text(steamStatusText(state.status), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!state.gameActive && !state.ready && !state.busy) TextButton(onClick = { SteamSession.reconnect() }) { Text(uiStr(R.string.steam_ui_reconnect)) }
                    if (!qr && (state.busy || state.guard.isNotEmpty())) TextButton(onClick = { SteamSession.cancelLogin() }) { Text(uiStr(R.string.steam_ui_cancel_login)) }
        }
        StoreTheme {
            FadeInOnShow {
            Box(Modifier.fillMaxSize().storeBackground(), contentAlignment = Alignment.TopCenter) {
                if (landscape) {
                    Row(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            Modifier.weight(.85f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (qr) {
                                Icon(TablerIcons.BrandSteam, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onBackground)
                                Text(uiStr(R.string.steam_qr_scan), style = MaterialTheme.typography.titleLarge)
                                Text(uiStr(R.string.steam_qr_help),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            } else introduction()
                        }
                        Column(
                            Modifier.weight(1.15f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) { form() }
                    }
                } else {
                    Column(
                        Modifier.widthIn(max = 440.dp).fillMaxWidth()
                            .verticalScroll(rememberScrollState()).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        if (!qr) introduction()
                        form()
                    }
                }
            }
            }
        }
    }
    private fun isInstalled(id: Int) = File(filesDir, "steam-library/$id.json").isFile
    private fun signOut() {
        if (SteamSession.downloading) { com.winlator.cmod.ui.toast.WinToast.show(this, uiStr(R.string.steam_ui_pause_logout)); return }
        SteamCredentials.clear(this)
        File(filesDir, "steam-library-cache.json").delete()
        SteamService.shutdown(this); finish()
    }
    private var detailOpening = false
    private fun showDetail(id: Int) {
        if (detailOpening || supportFragmentManager.fragments.any { it is SteamGameDetailFragment }) return
        detailOpening = true
        val content = findViewById<android.view.ViewGroup>(android.R.id.content)
        // Opened from the manager: the page goes on a back stack over it, so Back returns to the list (with its scroll,
        // tab and search kept) instead of leaving Steam. The manager's ComposeView must not swallow the window insets:
        // this app targets SDK 28, where they go to the children one after another until one consumes them, and the
        // page - the second child - got a zero status-bar inset (its cover slid under the clock / battery bar).
        val manager = content.getChildAt(0) as? androidx.compose.ui.platform.ComposeView
        manager?.consumeWindowInsets = false
        val transaction = supportFragmentManager.beginTransaction().replace(android.R.id.content, SteamGameDetailFragment.newInstance(id))
        if (manager != null) transaction.addToBackStack(null) else detailOpening = false
        transaction.commit()
    }
}
