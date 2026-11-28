package com.winlator.cmod.steam

import android.app.*
import android.content.*
import android.os.*
import com.winlator.cmod.R
import com.winlator.cmod.ui.uiStr
import com.winlator.cmod.feature.stores.steam.winlitesteam.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Owns the native session so navigation and screen rotation do not interrupt a download. */
class SteamService : Service() {
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        if (!SteamSettings.enabled(this)) { stopSelf(); return }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("steam", "Steam", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, SteamActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(NOTIFICATION_ID, Notification.Builder(this, "steam").setSmallIcon(R.drawable.ic_steam)
            .setContentTitle("Steam").setContentText("Steam session active").setContentIntent(open).build())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        SteamSession.open(applicationContext)
        return START_NOT_STICKY
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        SteamSession.close()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }
    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        SteamSession.close()
        super.onDestroy()
    }
    companion object {
        const val NOTIFICATION_ID = 310
        @JvmStatic fun shutdown(context: Context) {
            // Remove the visible notification before app exit can kill the process.
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            context.stopService(Intent(context, SteamService::class.java))
            SteamSession.close()
        }
    }
}

object SteamSession {
    /** How long a finished library sync keeps answering new sessions before PICS is asked again. */
    private const val LIBRARY_CACHE_TTL_MS = 6L * 60 * 60 * 1000
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile var session: WinLiteSteamSession? = null
        private set
    @Volatile var status = "Connecting…"
        private set
    @Volatile var qrUrl = ""
        private set
    @Volatile var library = "{}"
        private set
    @Volatile var downloading = false
        private set
    val guard = SteamGuardPrompt()
    @Volatile var connected = false
        private set
    @Volatile var signedIn = false
        private set
    @Volatile var signingIn = false
        private set
    @Volatile var downloadId = 0
        private set
    @Volatile var downloadName = ""
        private set
    @Volatile private var activeDownload: Any? = null
    @Volatile internal var downloadFinalizing = false
        private set
    @Volatile internal var downloadPending = false
        private set
    private var requireDlcOnResume = false
    @Volatile var installRequestId = 0
        private set
    @Volatile var installError = ""
        private set
    @Volatile var installErrorAppId = 0
        private set
    @Volatile internal var entitlementLibrary = "{}"
        private set
    @Volatile private var downloadMeter: SteamDownloadMeter? = null
    internal fun downloadReading() = downloadMeter?.read(SystemClock.elapsedRealtime())
    @Volatile var downloadDone = 0L
        private set
    @Volatile var downloadTotal = 0L
        private set
    @Volatile private var authAttempt = 0
    private var ca = ""
    @Volatile private var activeCmUrl = ""
    private var app: Context? = null
    @Volatile var gameActive = false
        private set
    private var generation = 0
    @Volatile private var closing = true
    private val recovery = SteamSessionRecovery()
    @Volatile private var restoreDeadline = 0L
    @Volatile private var restoreStage = "Connecting to Steam"
    private val restoreWatch = object : Runnable {
        override fun run() {
            if (closing || gameActive || !restoring || restoreDeadline == 0L) return
            val remaining = (restoreDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            if (remaining == 0L) {
                recoveryBlocked = true; restoring = false
                status = "Steam restoration timed out. Retry connection."
                val timedOut = session
                work { if (session === timedOut) { session = null; timedOut?.setStateObserver(null); timedOut?.disconnect(); timedOut?.close(); connected = false; signedIn = false } }
                return
            }
            status = "$restoreStage • ${remaining / 1000}s remaining"
            main.postDelayed(this, 1000)
        }
    }
    private fun startRestoreDeadline() {
        restoreDeadline = SystemClock.elapsedRealtime() + 45_000
        main.removeCallbacks(restoreWatch); main.post(restoreWatch)
    }
    private fun ensurePolling() { main.removeCallbacks(poll); main.post(poll) }
    private fun restoreFailure(error: Exception) {
        restoring = false; recoveryBlocked = true
        status = "Steam connection failed: ${error.message ?: "unknown error"}. Retry connection."
    }
    @Volatile var savedAccount = false
        private set
    @Volatile var restoring = false
        private set
    @Volatile var authError = ""
        private set
    @Volatile private var recoveryBlocked = false
    private val librarySync = SteamLibrarySync().also { sync ->
        // A signed-in session whose licence set matches the last finished sync keeps the cached library instead of
        // re-requesting every package and app through PICS (the whole library again on each login and after every game).
        sync.cacheUsable = { fingerprint -> app?.let { libraryCacheUsable(it, fingerprint) } == true }
    }
    @Volatile private var forceFullLibrary = false
    @Volatile private var libraryMarkerWritten = false
    private fun libraryCacheUsable(context: Context, fingerprint: String): Boolean {
        if (forceFullLibrary) { forceFullLibrary = false; return false }
        return runCatching {
            val account = SteamCredentials.load(context)?.optLong("id") ?: return false
            val marker = JSONObject(File(context.filesDir, "steam-library-sync.json").readText())
            marker.optLong("account") == account && marker.optString("fingerprint") == fingerprint &&
                System.currentTimeMillis() - marker.optLong("at") in 0 until LIBRARY_CACHE_TTL_MS &&
                SteamCatalog.packageMetadataReady(JSONObject(library)) &&
                SteamLibrary.items(context, library).isNotEmpty()
        }.getOrDefault(false)
    }
    private fun writeLibraryMarker(context: Context, fingerprint: String) {
        val account = SteamCredentials.load(context)?.optLong("id") ?: return
        runCatching {
            File(context.filesDir, "steam-library-sync.json").writeText(
                JSONObject().put("account", account).put("fingerprint", fingerprint).put("at", System.currentTimeMillis()).toString())
        }.onFailure { android.util.Log.w("SteamService", "Cannot store library sync marker", it) }
    }
    private val polling = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var licensesReceived = false
    @Volatile var libraryProgress = 0
        private set
    @Volatile var libraryLoading = true
        private set
    @Volatile var libraryError = ""
        private set
    private val poll = object : Runnable {
        override fun run() {
            if (closing || gameActive || app?.let { !SteamSettings.enabled(it) } == true) return
            if (polling.compareAndSet(false, true)) work {
                try {
                    if (gameActive) return@work
                    var s = session
                    if (s != null && savedAccount && !recoveryBlocked) {
                        val now = SystemClock.elapsedRealtime()
                        recovery.observe(if (signedIn) 3 else if (connected) 2 else 0, now)
                        if (!signedIn && recovery.shouldRetry(now)) {
                            try { replaceSession(app ?: return@work) } catch (error: Exception) { restoreFailure(error) }
                            s = session
                        } else if (!signedIn && recovery.exhausted(now)) {
                            restoring = false
                            status = "Steam session could not be restored. Reconnect to try again."
                        }
                    }
                    if (s != null && signedIn) {
                        SteamAchievements.syncPending(app ?: return@work)
                        val active = s
                        librarySync.step(object : SteamLibrarySync.Gateway {
                            override fun snapshot() = active.getLibrarySnapshotJson()
                            override fun packages(ids: List<Int>, tokens: List<Long>): Boolean {
                                if (!downloading) status = librarySync.message
                                return active.getPicsPackageInfo(ids, tokens)?.let { org.json.JSONArray(it).length() > 0 } ?: false
                            }
                            override fun apps(ids: List<Int>, tokens: List<Long>): Boolean {
                                if (!downloading) status = librarySync.message
                                return active.getPicsAppProductInfo(ids, tokens)?.let { org.json.JSONArray(it).length() > 0 } ?: false
                            }
                            override fun tokens(ids: List<Int>): Map<Int, Long> {
                                val json = active.getPicsAccessTokens(ids, emptyList()) ?: error("Steam access-token request timed out. Try again.")
                                val tokens = JSONObject(json).optJSONObject("appTokens") ?: return emptyMap()
                                return ids.mapNotNull { id -> tokens.optString(id.toString()).toULongOrNull()?.toLong()?.let { id to it } }.toMap()
                            }
                        }, licensesReceived, SystemClock.elapsedRealtime())
                        libraryProgress = librarySync.progress; libraryLoading = librarySync.loading; libraryError = librarySync.error
                        if (!downloading) status = librarySync.message
                        val snapshot = active.getLibrarySnapshotJson()
                        entitlementLibrary = validatedEntitlements(snapshot)
                        // A cached pass never touches the stored library: the live PICS store is empty on purpose.
                        if (!librarySync.fromCache) {
                            if (snapshot != library && (SteamLibrary.items(app ?: return@work, snapshot).isNotEmpty() || (!libraryLoading && libraryError.isEmpty()))) {
                                library = snapshot
                                app?.let { File(it.filesDir, "steam-library-cache.json").writeText(snapshot) }
                            }
                            if (!libraryLoading && libraryError.isEmpty() && !libraryMarkerWritten && librarySync.fingerprint.isNotEmpty()) {
                                libraryMarkerWritten = true
                                app?.let { writeLibraryMarker(it, librarySync.fingerprint) }
                            }
                        }
                    }
                } finally { polling.set(false) }
            }
            main.postDelayed(this, 3000)
        }
    }
    fun message(text: String) { status = text }
    fun work(action: () -> Unit) { worker.execute { try { action() } catch (e: Exception) { status = e.message ?: "Steam operation failed" } catch (e: LinkageError) { status = "Steam native library unavailable" } } }
    fun open(context: Context) {
        if (!SteamSettings.enabled(context) || gameActive) return
        app = context.applicationContext
        if (session != null && !closing) { ensurePolling(); return }
        closing = false
        val current = ++generation
        work {
            if (session != null || current != generation || closing || gameActive) return@work
            recovery.reset(); recoveryBlocked = false; restoreDeadline = 0L
            ensurePolling()
            try { replaceSession(context) } catch (error: Exception) { restoreFailure(error) }
        }
    }
    // Always destroy the previous transport before another auth attempt. Keep the library cache.
    private fun replaceSession(context: Context, restoreSaved: Boolean = true) {
        if (!restoreSaved) recoveryBlocked = false
        val saved = if (restoreSaved) SteamCredentials.load(context) else null
        savedAccount = saved != null; restoring = savedAccount
        restoreStage = "Closing previous Steam connection"
        if (restoring && (restoreDeadline == 0L || recoveryBlocked)) startRestoreDeadline()
        val previous = session
        session = null
        ++authAttempt; guard.cancel(); signingIn = false; qrUrl = ""; connected = false; signedIn = false
        previous?.let { it.cancelDownload(); it.cancelLogin(); it.disconnect(); it.close() }
        if (downloading) status = "Pausing download after session loss…"
        entitlementLibrary = "{}"
        librarySync.reset(); licensesReceived = false; libraryLoading = true; libraryProgress = 0; libraryError = ""; libraryMarkerWritten = false
        restoreStage = "Finding a Steam server"
        if (saved != null && library == "{}") library = runCatching { File(context.filesDir, "steam-library-cache.json").readText() }.getOrDefault("{}")
        ca = CaBundleExtractor.ensureBundle(context)
        check(ca.isNotEmpty()) { "No trusted CA bundle available" }
        val s = WinLiteSteamSession()
        session = s
        if (saved != null) recovery.began(SystemClock.elapsedRealtime())
        s.setAutoPersonaOnline(SteamSocial.enabled(context))
        s.setCaBundlePath(ca)
        val connectionGate = SteamConnectionGate()
        s.setStateObserver(object : WinLiteSteamStateObserver {
            override fun onStateChanged(state: Int) {
                connectionGate.observe(state)
                if (session !== s || closing || recoveryBlocked || gameActive) return
                connected = state >= 2; signedIn = state == 3
                if (signedIn) {
                    SteamSocial.appEvent(context)
                    signingIn = false; restoring = false; authError = ""; restoreDeadline = 0L
                    context.getSharedPreferences("steam-settings", 0).edit().putString("last-cm-url", activeCmUrl).apply()
                }
                else if (savedAccount && !recoveryBlocked) restoring = true
                restoreStage = if (state >= 2) "Authenticating saved Steam account" else "Connecting to Steam server"
                status = if (!signedIn && savedAccount) restoreStage else
                    arrayOf("Disconnected", "Connecting…", "Connected — sign in to Steam", "Signed in — loading library…").getOrElse(state) { "Steam" }
            }
            override fun onClientMessage(emsg: Int, eresult: Int, body: ByteArray) {
                if (session !== s || closing) return
                if (emsg in listOf(766, 767, 146, 147, 152)) SteamSocial.appEvent(context)
                if (emsg == 766) work { if (session === s && !gameActive) SteamUserData.capturePersona(context, s.getSelfPersona()) }
                if (emsg == 780) {
                    entitlementLibrary = "{}"
                    val repeated = licensesReceived
                    licensesReceived = true
                    if (repeated) work {
                        if (session === s && librarySync.fingerprint.isNotEmpty() &&
                            SteamLibrarySync.licenseFingerprint(JSONObject(s.getLibrarySnapshotJson())) != librarySync.fingerprint) {
                            refreshLibrary()
                        }
                    }
                }
                if (emsg == 751 || emsg == 757 || emsg == 5500) {
                    val code = SteamLogonResult.code(body, eresult)
                    if (code != null && code != 1) {
                        status = "Steam ended/refused the session (code $code)."
                        if (SteamLogonResult.requiresNewLogin(code)) {
                            recoveryBlocked = true; restoring = false; savedAccount = false; signedIn = false
                            authError = "Steam rejected the saved login (code $code). Sign in again."
                            work { if (session === s) { SteamCredentials.clear(context); replaceSession(context, false) } }
                        } else if (code == 84) {
                            recoveryBlocked = true; restoring = false
                            status = "Steam is limiting sign-in attempts. Wait before reconnecting."
                        }
                    }
                }
            }
        })
        val cachedUrl = context.getSharedPreferences("steam-settings", 0).getString("last-cm-url", "").orEmpty()
        val url = if (recovery.attemptNumber == 1 && cachedUrl.startsWith("wss://")) cachedUrl else WinLiteSteamSession.pickCmUrl(ca)
        check(!closing && !gameActive && !recoveryBlocked) { "Steam restoration canceled" }
        activeCmUrl = url
        restoreStage = "Connecting to Steam server"
        check(url.startsWith("wss://")) { "Could not locate a Steam server" }
        check(s.connect(url)) { "Steam connection failed" }
        if (saved != null) {
            check(connectionGate.awaitConnected(10_000) && !closing && !gameActive && !recoveryBlocked) { "Steam transport did not become ready. Retry connection." }
            restoreStage = "Authenticating saved Steam account"
            if (s.state() != 3) check(s.logonWithRefreshToken(saved.getString("token"), saved.getString("account"), saved.getLong("id"))) { "Saved Steam login could not start. Reconnect to retry." }
        }
    }
    fun login(username: String, password: String) {
        if (gameActive || app?.let { !SteamSettings.enabled(it) } == true) return
        if (signingIn || username.isBlank() || password.isEmpty()) return
        SteamSocial.reset()
        signingIn = true; authError = ""; recoveryBlocked = false
        work {
            try {
                val context = app ?: error("Steam is starting")
                val s = session ?: error("Steam is starting")
                check(!downloading && s.state() == 2) { "Wait for the Steam connection before signing in" }
                guard.cancel(); s.cancelLogin()
                val attempt = ++authAttempt
                status = "Signing in…"
                s.startLoginWithCredentials(username.trim(), password, true, object : WinLiteAuthenticator {
                    override fun acceptDeviceConfirmation(): java.util.concurrent.CompletableFuture<Boolean> {
                        if (session !== s || attempt != authAttempt) return java.util.concurrent.CompletableFuture.completedFuture(false)
                        status = "Approve this sign-in in your Steam mobile app"
                        return java.util.concurrent.CompletableFuture.completedFuture(true)
                    }
                    override fun getDeviceCode(previousCodeWasIncorrect: Boolean) = if (session !== s || attempt != authAttempt) java.util.concurrent.CompletableFuture.completedFuture("") else guard.request(
                        if (previousCodeWasIncorrect) "Incorrect code. Enter a new Steam Guard code" else "Enter your Steam Guard code")
                    override fun getEmailCode(email: String?, previousCodeWasIncorrect: Boolean) = if (session !== s || attempt != authAttempt) java.util.concurrent.CompletableFuture.completedFuture("") else guard.request(
                        if (previousCodeWasIncorrect) "Incorrect code. Enter the new email code" else "Enter the Steam Guard code sent to ${email ?: "your email"}")
                }, authCallback(context, s, attempt)
                )
            } catch (e: Exception) { signingIn = false; throw e }
        }
    }
    private fun authCallback(context: Context, s: WinLiteSteamSession, attempt: Int): WinLiteAuthCallback = WinLiteAuthCallback { result ->
                    if (session === s && attempt == authAttempt) {
                        if (result.success && result.refreshToken.isNotBlank()) work {
                            if (session !== s || attempt != authAttempt) return@work
                            try {
                                check(result.steamId > 0 && result.accountName.isNotBlank()) { "Steam returned incomplete account details" }
                                SteamCredentials.save(context, result.accountName, result.refreshToken, result.steamId)
                                guard.cancel(); savedAccount = true; restoring = true
                                restoreStage = "Authenticating Steam account"; startRestoreDeadline()
                                recovery.reset(); recovery.began(SystemClock.elapsedRealtime())
                                check(s.logonWithRefreshToken(result.refreshToken, result.accountName, result.steamId)) { "Steam logon failed" }
                            } finally { signingIn = false }
                        } else if (!result.hadRemoteInteraction) {
                            signingIn = false; guard.cancel()
                            authError = result.errorMessage.ifBlank { "Steam sign-in failed" }
                            status = authError
                            // Auth jobs that timed out must not be retried on the same CM transport.
                            work { if (session === s) replaceSession(context, false) }
                        }
                    }
        }
    fun loginQr() {
        if (gameActive || signingIn || app?.let { !SteamSettings.enabled(it) } != false) return
        signingIn = true; authError = ""; qrUrl = ""; recoveryBlocked = false
        SteamSocial.reset()
        work {
            try {
                val context = app ?: error("Steam is starting")
                val s = session ?: error("Steam is starting")
                check(!downloading && s.state() == 2) { "Wait for the Steam connection before signing in" }
                guard.cancel(); s.cancelLogin()
                val attempt = ++authAttempt
                status = "Scan the QR code with the Steam mobile app"
                s.startLoginWithQr(WinLiteQrCallback { url ->
                    if (session === s && attempt == authAttempt) qrUrl = url
                }, authCallback(context, s, attempt))
            } catch (error: Exception) { signingIn = false; qrUrl = ""; throw error }
        }
    }
    fun retryLibrary() = work {
        check(!gameActive && app?.let { SteamSettings.enabled(it) } == true) { "Steam is disabled or paused for a running game" }
        refreshLibrary()
    }
    private fun refreshLibrary() {
        entitlementLibrary = "{}"
        forceFullLibrary = true; libraryMarkerWritten = false
        librarySync.reset(); libraryLoading = true; libraryProgress = 0; libraryError = ""
        status = "Reloading Steam library…"
    }
    fun reconnect() = work {
        check(!gameActive) { "Exit the running game before reconnecting the app's Steam session" }
        val context = app ?: return@work
        check(SteamSettings.enabled(context)) { "Steam is disabled" }
        recovery.reset(); recoveryBlocked = false; restoreDeadline = 0L
        ensurePolling()
        try { replaceSession(context) } catch (error: Exception) { restoreFailure(error) }
    }
    fun cancelLogin() {
        ++authAttempt; guard.cancel(); signingIn = false; qrUrl = ""
        work { session?.cancelLogin(); status = "Sign-in cancelled" }
    }
    fun info(id: Int): JSONObject {
        val raw = session?.getPicsAppInfo(id) ?: error("No Steam metadata for $id")
        return JSONObject(raw).getJSONObject("appinfo")
    }
    private fun validatedEntitlements(snapshot: String): String {
        if (!licensesReceived) return "{}"
        val live = JSONObject(snapshot)
        if (SteamCatalog.packageMetadataReady(live)) return snapshot
        if (librarySync.fromCache && SteamLibrarySync.licenseFingerprint(live) == librarySync.fingerprint) {
            return library
        }
        return "{}"
    }

    fun resumeDownload(id: Int) {
        if (downloadPending && downloadId == id) install(id, requireDlcOnResume)
    }

    @Synchronized fun install(id: Int, requireDlc: Boolean = false) {
        if (installRequestId != 0 || downloading || gameActive) return
        installRequestId = id
        installError = ""
        installErrorAppId = id
        work {
            try {
                installNow(id, requireDlc)
            } catch (error: Exception) {
                installError = error.message ?: "Steam download could not start"
                status = installError
            } catch (_: LinkageError) {
                installError = "Steam native library unavailable"
                status = installError
            } finally {
                installRequestId = 0
            }
        }
    }

    private fun installNow(id: Int, requireDlc: Boolean) {
        check(app?.let { SteamSettings.enabled(it) } == true && !gameActive && !closing) { "Steam is disabled or paused for a game" }
        val context = app ?: error("Steam is starting")
        val s = session ?: error("Steam is starting")
        check(s.state() == 3) { "Sign in first" }
        val recordFile = File(context.filesDir, "steam-library/$id.json")
        val previous = if (recordFile.isFile) SteamGames.info(context, id) else JSONObject()
        val info = info(id)
        val isDemo = info.optJSONObject("common")?.optString("type").equals("demo", true)
        if (isDemo && !recordFile.isFile) {
            val license = s.requestFreeLicense(id)?.let(::JSONObject)
            if (license?.optBoolean("granted") == true) refreshLibrary()
            // A demo can already be licensed. Depot access remains authoritative.
        }
        val inventory = JSONObject(validatedEntitlements(s.getLibrarySnapshotJson()))
        if (requireDlc) {
            check(SteamCatalog.packageMetadataReady(inventory)) { uiStr(R.string.steam_dlc_wait_licenses) }
        }
        val name = SteamCatalog.installName(info)
        val branch = SteamInstallOptions.branch(context, id)
        val language = SteamInstallOptions.language(context, id)
        val selectedDlc = SteamCatalog.selectedDlc(info, inventory, SteamInstallOptions.excludedDlc(context, id))
        val depots = SteamCatalog.depots(info, branch, language, selectedDlc, ::info)
        val freeToDownload = info.optJSONObject("common")?.let {
            it.optString("freetodownload", it.optString("FreeToDownload")) in listOf("1", "true")
        } == true
        if (requireDlc) check(depots.any { it.dlcAppId > 0 }) { uiStr(R.string.steam_dlc_no_content) }
        check(session === s && signedIn && !gameActive && !closing) { "Steam session changed while preparing the download" }
        val dir = SteamStorage.download(context, id, name)
        SteamGoldberg.restore(dir)
        SteamGoldberg.invalidate(dir)
        val token = Any()
        activeDownload = token
        downloadMeter = null
        downloadFinalizing = false
        requireDlcOnResume = requireDlc
        downloadPending = true
        downloading = true
        downloadId = id
        downloadName = name
        downloadDone = 0
        downloadTotal = 0
        status = "Downloading $name…"
        val tier = SteamDownloadSettings.tier(context)
        try {
            s.downloadApp(id, depots.map { it.id }.toIntArray(), depots.map { it.manifest }.toLongArray(),
                depots.map { if (freeToDownload || it.manifestAppId == 0) id else it.manifestAppId }.toIntArray(),
                depots.map { it.manifestBranch }.toTypedArray(), dir.absolutePath, false, ca,
                SteamDownloadSettings.network(tier), object : WinLiteDownloadListener {
                    @androidx.annotation.Keep
                    override fun onDepotsResolved(depotIds: IntArray) {
                        if (activeDownload !== token || session !== s) return
                        downloadMeter = SteamDownloadMeter(depotIds.toList(), depots.associate { it.id to it.size })
                    }

                    override fun onProgress(depotId: Int, depotDone: Long, depotTotal: Long, depotsDone: Int, depotsTotal: Int, verifying: Boolean) {
                        if (activeDownload !== token || session !== s || !signedIn) return
                        downloadMeter?.update(depotId, depotDone, depotTotal, depotsDone, verifying, SystemClock.elapsedRealtime())
                        val reading = downloadReading()
                        downloadDone = reading?.done ?: depotDone
                        downloadTotal = reading?.total ?: depotTotal
                        downloadFinalizing = !verifying && depotsDone + 1 >= depotsTotal &&
                            depotTotal > 0 && depotDone >= depotTotal
                        status = if (downloadFinalizing) {
                            "$name: finalizing files…"
                        } else {
                            val percent = if (depotTotal > 0) depotDone * 100 / depotTotal else 0
                            "$name: depot ${depotsDone + 1}/$depotsTotal — $percent%" + if (verifying) " (verifying)" else ""
                        }
                    }

                    override fun onComplete(success: Boolean, error: String, bytesWritten: Long, depotsCompleted: Int, depotsSkipped: Int) {
                        work {
                            if (activeDownload !== token) return@work
                            try {
                                if (session !== s || gameActive || closing) {
                                    status = "Download paused"
                                } else if (success) {
                                    downloadFinalizing = true
                                    status = "$name: finishing installation…"
                                    val actual = installedSteamManifests(dir)
                                    val denied = File(dir, ".winlite_depot/denied.depots").takeIf { it.isFile }
                                        ?.readLines()?.mapNotNull { it.toIntOrNull() }.orEmpty().toSet()
                                    val installed = depots.filter { it.id !in denied && actual[it.id] == it.manifest }
                                    check(installed.any { it.dlcAppId == 0 }) { "No base game depots were installed" }
                                    val record = SteamStorage.record(info, dir, branch, language)
                                    record.put("winliteInstalledDepots", SteamCatalog.installedManifestList(previous, depots, installed, actual))
                                    SteamInstallRecord.write(context.filesDir, id, record)
                                    dir.walkTopDown().filter { it.isFile && it.name.endsWith(".winlite-original") }.forEach { it.delete() }
                                    if (depots.any { it.dlcAppId != 0 && it !in installed }) {
                                        installError = uiStr(R.string.steam_dlc_incomplete)
                                    }
                                    downloadPending = false
                                    status = "$name installed"
                                    main.post { SteamGames.changed() }
                                } else {
                                    installError = if (isDemo && error.contains("entitled", ignoreCase = true)) {
                                        uiStr(R.string.steam_demo_no_access)
                                    } else error
                                    status = "Download failed: $installError"
                                }
                            } catch (failure: Exception) {
                                installError = failure.message ?: "Steam could not finalize the installation"
                                status = installError
                            } finally {
                                activeDownload = null
                                downloadFinalizing = false
                                downloading = false
                            }
                        }
                    }
                }, SteamDownloadSettings.processing(tier))
        } catch (failure: Throwable) {
            activeDownload = null
            downloading = false
            throw failure
        }
    }

    fun cancel() = work { session?.cancelDownload() }
    fun discardPaused(id: Int) = work {
        check(!downloading && installRequestId == 0 && !gameActive) { "Pause the download and close the game first" }
        check(downloadId == id && downloadName.isNotBlank()) { "No paused download to remove" }
        val context = app ?: error("Steam is not connected")
        SteamStorage.removePending(context, id, downloadName)
        downloadId = 0
        downloadPending = false
        downloadName = ""
        downloadDone = 0
        downloadTotal = 0
        downloadMeter = null
        status = "Paused download removed"
        main.post { SteamGames.changed() }
    }
    /** All ordinary runtimes, including the container desktop, suspend app Steam work. */
    @JvmStatic fun pauseForCustomGame(context: Context): Boolean {
        if (app == null || closing || !SteamSettings.enabled(context) || gameActive) return false
        check(!SteamNativeCloud.busy) { "Wait for Steam Cloud sync to finish before entering the container" }
        if (downloading) {
            downloading = false
            work { session?.cancelDownload() }
        }
        SteamSocial.status.value = com.winlator.cmod.ui.uiStr(com.winlator.cmod.R.string.steam_friends_empty)
        try { beginGame(context) } catch (error: Exception) {
            // Steam is optional for a custom executable: never fail its launch on a CM timeout.
            gameActive = true
            recoveryBlocked = true; restoring = false
            main.removeCallbacks(poll); main.removeCallbacks(restoreWatch)
            work {
                if (!gameActive) return@work
                val old = session; session = null
                old?.let { it.setStateObserver(null); it.cancelLogin(); it.logOffAndDisconnect(); it.close() }
                connected = false; signedIn = false
            }
            android.util.Log.w("SteamSession", "Custom launch continues; optional Steam pause queued", error)
        }
        return true
    }
    @JvmStatic fun beginGame(context: Context) {
        check(SteamSettings.enabled(context)) { "Steam is disabled in Steam / Stores settings" }
        check(!SteamNativeCloud.busy) { "Wait for Steam Cloud sync to finish" }
        check(!downloading && installRequestId == 0 && !gameActive) { "Pause the download and close any running Steam game first" }
        gameActive = true
        val ready = java.util.concurrent.CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        work {
            try {
                if (!gameActive) return@work
                recoveryBlocked = true; restoring = false; signingIn = false
                main.removeCallbacks(poll); main.removeCallbacks(restoreWatch)
                val old = session; session = null
                old?.let { it.setStateObserver(null); it.cancelLogin(); it.logOffAndDisconnect(); it.close() }
                connected = false; signedIn = false
                status = "Steam app session paused while the game runs"
            } catch (e: Exception) { failure.set(e) }
            finally { ready.countDown() }
        }
        if (!ready.await(15, java.util.concurrent.TimeUnit.SECONDS)) {
            gameActive = false
            error("Could not pause the Steam session in time. Retry after the current request finishes.")
        }
        failure.get()?.let { endGame(context); error("Could not hand off the Steam session: ${it.message}") }
    }
    @JvmStatic fun endGame(context: Context) {
        if (!gameActive) return
        gameActive = false
        if (SteamSettings.enabled(context) && SteamCredentials.load(context) != null) {
            app = context.applicationContext; closing = false
            work {
                if (gameActive || closing || !SteamSettings.enabled(context)) return@work
                recovery.reset(); recoveryBlocked = false; restoreDeadline = 0L
                ensurePolling()
                try { replaceSession(context.applicationContext) } catch (error: Exception) { restoreFailure(error) }
            }
        }
    }
    fun close() {
        closing = true; savedAccount = false; restoring = false; recoveryBlocked = true
        ++generation; ++authAttempt; guard.cancel(); signingIn = false; qrUrl = ""; signedIn = false; connected = false
        main.removeCallbacks(poll); main.removeCallbacks(restoreWatch)
        restoreDeadline = 0L
        work {
            session?.let { it.cancelDownload(); it.cancelLogin(); it.logOffAndDisconnect(); it.close() }
            session = null
            librarySync.reset(); licensesReceived = false; libraryLoading = true; libraryProgress = 0; libraryError = ""
            library = "{}"; entitlementLibrary = "{}"; qrUrl = ""
            activeDownload = null; downloading = false; downloadFinalizing = false; status = "Disconnected"
        }
    }
}
