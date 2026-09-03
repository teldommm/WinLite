package com.winlator.cmod.runtime.content

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.winlator.cmod.app.PluviaApp
import com.winlator.cmod.shared.io.NativeContentIO
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Native-backed component download helper.
 *
 * Large file transfers use [NativeContentIO] / libcurl. OkHttp is retained only for small
 * metadata requests such as remote JSON manifests.
 */
object Downloader {
    private const val TAG = "Downloader"
    private const val NATIVE_CA_BUNDLE_NAME = "native_curl_cacert.pem"
    private const val SYSTEM_CA_DIR = "/system/etc/security/cacerts"

    private val metadataClient =
        OkHttpClient
            .Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

    @Volatile private var logEnabledCached = false
    @Volatile private var logEnabledResolved = false
    @Volatile private var nativeCaBundlePath: String? = null

    fun interface DownloadListener {
        fun onProgress(
            downloadedBytes: Long,
            totalBytes: Long,
        )
    }

    @JvmStatic
    fun downloadFile(
        address: String,
        file: File,
        listener: DownloadListener?,
    ): Boolean {
        try {
            val parent = file.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                if (logEnabled()) Log.w(TAG, "Unable to create download directory: ${parent.absolutePath}")
                return false
            }

            if (NativeContentIO.downloadFile(address, file, ensureNativeCaBundle(), listener)) {
                return true
            }
        } catch (e: Throwable) {
            if (logEnabled()) Log.w(TAG, "Download failed for $address", e)
        }

        if (file.exists() && !file.delete() && logEnabled()) {
            Log.w(TAG, "Unable to delete partial download: ${file.absolutePath}")
        }
        return false
    }

    @JvmStatic
    fun fetchContentLength(address: String?): Long {
        if (address.isNullOrEmpty()) return -1L
        return try {
            NativeContentIO.fetchContentLength(address, ensureNativeCaBundle())
        } catch (e: Throwable) {
            if (logEnabled()) Log.w(TAG, "Native HEAD failed for $address", e)
            -1L
        }
    }

    @JvmStatic
    fun downloadString(
        address: String,
        headers: Map<String, String> = emptyMap(),
    ): String? {
        val requestBuilder = Request.Builder().url(address)
        headers.forEach { (key, value) -> requestBuilder.addHeader(key, value) }
        val request = requestBuilder.build()
        return try {
            metadataClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code} for $address")
                }
                response.body?.string()
            }
        } catch (e: Exception) {
            if (logEnabled()) Log.w(TAG, "String download failed for $address", e)
            null
        }
    }

    /**
     * Fetches a GitHub REST "list" endpoint (releases, etc.) via the shared OkHttp client,
     * paginating through up to [maxPages] pages of [perPage] items each and merging the
     * results into one JSONArray — matches GitHub's own pagination contract (a page shorter
     * than [perPage] means there's nothing left).
     *
     * URLs that aren't api.github.com (a self-hosted/custom release feed) are trusted to
     * return everything in a single response, since we don't know their pagination contract.
     */
    @JvmStatic
    fun fetchGithubReleases(
        apiUrl: String,
        perPage: Int = 100,
        maxPages: Int = 2,
    ): JSONArray {
        val headers = mapOf("Accept" to "application/vnd.github+json", "User-Agent" to "WinLite")
        val merged = JSONArray()

        if (!apiUrl.contains("api.github.com")) {
            val body = downloadString(apiUrl, headers) ?: return merged
            runCatching { JSONArray(body) }.getOrNull()?.let { page ->
                for (i in 0 until page.length()) merged.put(page.get(i))
            }
            return merged
        }

        var page = 1
        while (page <= maxPages) {
            val separator = if (apiUrl.contains("?")) "&" else "?"
            val pageUrl = "$apiUrl${separator}per_page=$perPage&page=$page"
            val body = downloadString(pageUrl, headers) ?: break
            val pageArray = runCatching { JSONArray(body) }.getOrNull() ?: break
            for (i in 0 until pageArray.length()) merged.put(pageArray.get(i))
            if (pageArray.length() < perPage) break
            page++
        }
        return merged
    }

    private fun ensureNativeCaBundle(): String {
        nativeCaBundlePath?.let { return it }

        synchronized(this) {
            nativeCaBundlePath?.let { return it }
            val context = appContext()
            if (context == null) {
                nativeCaBundlePath = ""
                return ""
            }

            val out = File(context.filesDir, NATIVE_CA_BUNDLE_NAME)
            if (out.isFile && out.length() > 1024L) {
                nativeCaBundlePath = out.absolutePath
                return out.absolutePath
            }

            val certs = File(SYSTEM_CA_DIR).listFiles { file -> file.isFile && file.name.endsWith(".0") }
            if (certs.isNullOrEmpty()) {
                if (logEnabled()) Log.w(TAG, "No Android CA certificates found for native curl")
                nativeCaBundlePath = ""
                return ""
            }

            val tmp = File(out.parentFile, "${out.name}.tmp")
            try {
                BufferedWriter(OutputStreamWriter(FileOutputStream(tmp), StandardCharsets.UTF_8)).use { writer ->
                    certs.forEach { cert ->
                        try {
                            BufferedReader(InputStreamReader(FileInputStream(cert), StandardCharsets.UTF_8)).use { reader ->
                                reader.lineSequence().forEach { line ->
                                    writer.write(line)
                                    writer.newLine()
                                }
                                writer.newLine()
                            }
                        } catch (e: Exception) {
                            if (logEnabled()) Log.w(TAG, "Skipped CA certificate: ${cert.name}", e)
                        }
                    }
                }
            } catch (e: Exception) {
                if (logEnabled()) Log.w(TAG, "Failed to create native curl CA bundle", e)
                tmp.delete()
                nativeCaBundlePath = ""
                return ""
            }

            if (out.exists() && !out.delete() && logEnabled()) {
                Log.w(TAG, "Unable to replace native curl CA bundle")
            }
            nativeCaBundlePath = if (tmp.renameTo(out)) out.absolutePath else ""
            return nativeCaBundlePath.orEmpty()
        }
    }

    private fun refreshLogEnabled() {
        logEnabledResolved = false
    }

    private fun logEnabled(): Boolean {
        if (logEnabledResolved) return logEnabledCached
        return try {
            val context = appContext() ?: return false
            logEnabledCached =
                PreferenceManager
                    .getDefaultSharedPreferences(context)
                    .getBoolean("enable_download_logs", false)
            logEnabledResolved = true
            logEnabledCached
        } catch (_: Exception) {
            false
        }
    }

    private fun appContext(): Context? =
        try {
            PluviaApp.instance.applicationContext
        } catch (_: Exception) {
            null
        }
}
