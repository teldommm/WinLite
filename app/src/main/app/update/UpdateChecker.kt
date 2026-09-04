package com.winlator.cmod.app.update
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.preference.PreferenceManager
import com.winlator.cmod.app.PluviaApp
import com.winlator.cmod.runtime.display.XServerDisplayActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object UpdateChecker {
    private const val DEFAULT_RELEASES_API_URL = "https://api.github.com/repos/teldommm/WinLite/releases/latest"

    private const val PREF_CHECK_FOR_UPDATES = "check_for_updates"
    private const val PREF_INSTALL_TIMESTAMP = "app_install_timestamp"
    private const val PREF_LAST_UPDATE_CHECK = "last_update_check_time"
    private const val PREF_REPO_URL = "update_repo_url"

    private const val CHECK_INTERVAL_MS = 60 * 60 * 1000L
    private const val MANUAL_CHECK_COOLDOWN_MS = 30 * 1000L
    private const val POST_GAME_CHECK_DELAY_MS = 10 * 1000L

    private val lastManualCheckTime = AtomicLong(0L)

    private val isChecking = AtomicBoolean(false)

    private var backgroundHandler: Handler? = null
    private var backgroundRunnable: Runnable? = null

    private var postGameHandler: Handler? = null
    private var postGameRunnable: Runnable? = null

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    fun isEnabled(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean(PREF_CHECK_FOR_UPDATES, false)
    }

    // Record the app install/update timestamp from PackageManager.
    fun refreshInstallTimestamp(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val installTime = pInfo.lastUpdateTime.coerceAtLeast(pInfo.firstInstallTime)
            prefs.edit().putLong(PREF_INSTALL_TIMESTAMP, installTime).apply()
        } catch (e: PackageManager.NameNotFoundException) {
            if (!prefs.contains(PREF_INSTALL_TIMESTAMP)) {
                prefs.edit().putLong(PREF_INSTALL_TIMESTAMP, System.currentTimeMillis()).apply()
            }
        }
    }

    fun isDueForCheck(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val lastCheck = prefs.getLong(PREF_LAST_UPDATE_CHECK, 0L)
        return System.currentTimeMillis() - lastCheck >= CHECK_INTERVAL_MS
    }

    // Start the hourly background loop when auto-update is enabled.
    fun startBackgroundLoop(context: Context) {
        stopBackgroundLoop()
        if (!isEnabled(context)) return
        if (!isAutoCheckAllowed()) return

        val appContext = context.applicationContext
        backgroundHandler = Handler(Looper.getMainLooper())
        backgroundRunnable =
            object : Runnable {
                override fun run() {
                    if (isEnabled(appContext) && isAutoCheckAllowed()) {
                        checkForUpdate(appContext, force = false)
                        backgroundHandler?.postDelayed(this, CHECK_INTERVAL_MS)
                    }
                }
            }
        backgroundHandler?.postDelayed(backgroundRunnable!!, 5_000L)
    }

    fun stopBackgroundLoop() {
        backgroundRunnable?.let { backgroundHandler?.removeCallbacks(it) }
        backgroundHandler = null
        backgroundRunnable = null
    }

    // Perform an automatic update check.
    fun checkForUpdate(
        context: Context,
        force: Boolean = false,
    ) {
        if (!isEnabled(context)) return
        if (!isAutoCheckAllowed()) return
        if (!force && !isDueForCheck(context)) return
        launchCheck(context)
    }

    // Manual check; returns false while in cooldown.
    fun checkForUpdateManual(context: Context): Boolean {
        val now = System.currentTimeMillis()
        val last = lastManualCheckTime.get()
        if (now - last < MANUAL_CHECK_COOLDOWN_MS) return false
        lastManualCheckTime.set(now)
        launchCheck(context)
        return true
    }

    fun manualCheckCooldownSeconds(): Int {
        val elapsed = System.currentTimeMillis() - lastManualCheckTime.get()
        val remaining = MANUAL_CHECK_COOLDOWN_MS - elapsed
        return if (remaining > 0) ((remaining + 999) / 1000).toInt() else 0
    }

    // Schedule a deferred update check after a game exits.
    fun schedulePostGameCheck(context: Context) {
        cancelPostGameCheck()
        if (!isEnabled(context)) return
        if (!isAutoCheckAllowed()) return
        val appContext = context.applicationContext
        postGameHandler = Handler(Looper.getMainLooper())
        postGameRunnable =
            Runnable {
                if (isAutoCheckAllowed()) {
                    checkForUpdate(appContext, force = true)
                }
            }
        postGameHandler?.postDelayed(postGameRunnable!!, POST_GAME_CHECK_DELAY_MS)
    }

    fun cancelPostGameCheck() {
        postGameRunnable?.let { postGameHandler?.removeCallbacks(it) }
        postGameHandler = null
        postGameRunnable = null
    }

    fun resetCheckTimer(context: Context) {
        PreferenceManager
            .getDefaultSharedPreferences(context)
            .edit()
            .putLong(PREF_LAST_UPDATE_CHECK, 0L)
            .apply()
    }

    // The GitHub repo backing update checks. Empty pref = built-in default.
    fun getEffectiveApiUrl(context: Context): String {
        val stored = PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_REPO_URL, null)
        return stored?.takeIf { it.isNotBlank() } ?: DEFAULT_RELEASES_API_URL
    }

    fun isCustomRepo(context: Context): Boolean {
        val stored = PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_REPO_URL, null)
        return !stored.isNullOrBlank()
    }

    fun getDefaultRepoUrl(): String = DEFAULT_RELEASES_API_URL

    // Accepts "owner/repo", a github.com link, or an already-correct api.github.com releases
    // URL. Anything else is stored as typed — a bad value just fails at fetch time, same as
    // any other manually-entered GitHub source in this app.
    private fun normalizeRepoUrl(raw: String): String {
        var input = raw.trim()
        if (input.isEmpty()) return input

        if (input.contains("api.github.com/repos/")) {
            return when {
                input.endsWith("/releases/latest") -> input
                input.endsWith("/releases") -> "$input/latest"
                else -> "${input.trimEnd('/')}/releases/latest"
            }
        }

        val stripped =
            input
                .replaceFirst(Regex("^https?://github\\.com/", RegexOption.IGNORE_CASE), "")
                .removeSuffix(".git")
                .trim('/')
        val parts = stripped.split("/")
        if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) return input

        val owner = parts[0].trim()
        val repo = parts[1].trim()

        return "https://api.github.com/repos/$owner/$repo/releases/latest"
    }

    fun setRepoUrl(
        context: Context,
        raw: String,
    ) {
        val normalized = normalizeRepoUrl(raw)
        PreferenceManager
            .getDefaultSharedPreferences(context)
            .edit()
            .putString(PREF_REPO_URL, normalized)
            .apply()
        resetCheckTimer(context)
    }

    fun resetRepoUrlToDefault(context: Context) {
        PreferenceManager
            .getDefaultSharedPreferences(context)
            .edit()
            .remove(PREF_REPO_URL)
            .apply()
        resetCheckTimer(context)
    }

    private fun isAutoCheckAllowed(): Boolean {
        val activity = PluviaApp.currentForegroundActivity ?: return false
        return activity !is XServerDisplayActivity
    }

    private fun launchCheck(context: Context) {
        if (!isChecking.compareAndSet(false, true)) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                refreshInstallTimestamp(context)
                val result = fetchUpdateInfo(context)
                if (result != null) {
                    withContext(Dispatchers.Main) {
                        showUpdateDialog(context, result)
                    }
                }
                PreferenceManager
                    .getDefaultSharedPreferences(context)
                    .edit()
                    .putLong(PREF_LAST_UPDATE_CHECK, System.currentTimeMillis())
                    .apply()
            } catch (e: Exception) {
                Timber.e(e, "Update check failed")
            } finally {
                isChecking.set(false)
            }
        }
    }

    data class UpdateInfo(
        val serverModified: Date,
        val serverModifiedFormatted: String,
        val serverVersionName: String?,
        val downloadUrl: String,
        val releaseNotes: String?,
    )

    // Fetch the latest GitHub release and compare its publish date against install time.
    private fun fetchUpdateInfo(context: Context): UpdateInfo? {
        val request =
            Request
                .Builder()
                .url(getEffectiveApiUrl(context))
                .header("Accept", "application/vnd.github+json")
                .header("Cache-Control", "no-cache")
                .build()

        val body =
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("Update check request failed: ${response.code}")
                    return null
                }
                response.body?.string() ?: return null
            }

        val json =
            try {
                org.json.JSONObject(body)
            } catch (e: Exception) {
                Timber.w(e, "Could not parse release JSON")
                return null
            }

        val publishedAtStr = json.optString("published_at").takeIf { it.isNotBlank() } ?: return null
        val serverDate =
            parseGitHubDate(publishedAtStr) ?: run {
                Timber.w("Could not parse 'published_at' date: $publishedAtStr")
                return null
            }

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val installTimestamp = prefs.getLong(PREF_INSTALL_TIMESTAMP, System.currentTimeMillis())

        if (serverDate.time <= installTimestamp) {
            return null
        }

        val versionName = json.optString("tag_name").takeIf { it.isNotBlank() }
        val releaseNotes = json.optString("body").takeIf { it.isNotBlank() }

        var downloadUrl: String? = null
        val assets = json.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                    downloadUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                    break
                }
            }
        }
        // Fall back to the release page itself if no .apk asset is attached.
        val finalDownloadUrl = downloadUrl ?: json.optString("html_url").takeIf { it.isNotBlank() } ?: return null

        val dateFormat = SimpleDateFormat("MMM dd, yyyy 'at' hh:mm a", Locale.US)
        dateFormat.timeZone = TimeZone.getDefault()

        return UpdateInfo(
            serverModified = serverDate,
            serverModifiedFormatted = dateFormat.format(serverDate),
            serverVersionName = versionName,
            downloadUrl = finalDownloadUrl,
            releaseNotes = releaseNotes,
        )
    }

    // GitHub's REST API always returns timestamps in strict ISO-8601 UTC ("2026-09-01T12:34:56Z").
    private fun parseGitHubDate(dateStr: String): Date? =
        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            sdf.parse(dateStr)
        } catch (e: Exception) {
            null
        }

    private fun showUpdateDialog(
        context: Context,
        info: UpdateInfo,
    ) {
        if (context is android.app.Activity && context.isFinishing) return

        val padding = (16 * context.resources.displayMetrics.density).toInt()
        val smallPad = (8 * context.resources.displayMetrics.density).toInt()

        val container =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding, padding, padding)
            }

        val releasedLabel =
            TextView(context).apply {
                text = "Released: ${info.serverModifiedFormatted}"
                setTextColor(0xFFB0B0B0.toInt())
                textSize = 14f
            }
        container.addView(releasedLabel)

        if (!info.releaseNotes.isNullOrBlank()) {
            val divider =
                android.view.View(context).apply {
                    layoutParams =
                        LinearLayout
                            .LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                (1 * context.resources.displayMetrics.density).toInt(),
                            ).apply {
                                topMargin = padding
                                bottomMargin = smallPad
                            }
                    setBackgroundColor(0xFF444444.toInt())
                }
            container.addView(divider)

            val notesHeader =
                TextView(context).apply {
                    text = "Release Notes"
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 15f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(0, smallPad, 0, smallPad)
                }
            container.addView(notesHeader)

            val notesBody =
                TextView(context).apply {
                    text = info.releaseNotes
                    setTextColor(0xFFCCCCCC.toInt())
                    textSize = 13f
                    movementMethod = ScrollingMovementMethod.getInstance()
                    maxLines = 12
                    isVerticalScrollBarEnabled = true
                }

            val scrollView =
                ScrollView(context).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                    addView(notesBody)
                }
            container.addView(scrollView)
        }

        val dialog =
            AlertDialog
                .Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(
                    if (info.serverVersionName.isNullOrBlank()) {
                        "Update Available"
                    } else {
                        "Update Available (${info.serverVersionName})"
                    },
                ).setView(container)
                .setPositiveButton("Download") { _, _ ->
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(info.downloadUrl))
                    context.startActivity(intent)
                }.setNegativeButton("Later", null)
                .create()

        dialog.show()
    }
}
