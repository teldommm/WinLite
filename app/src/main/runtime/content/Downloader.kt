package com.winlator.cmod.runtime.content

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.winlator.cmod.app.PluviaApp
import com.winlator.cmod.shared.io.NativeContentIO
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
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
    fun downloadString(address: String): String? {
        val request = Request.Builder().url(address).build()
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
