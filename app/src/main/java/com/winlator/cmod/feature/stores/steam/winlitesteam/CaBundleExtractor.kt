package com.winlator.cmod.feature.stores.steam.winlitesteam

import android.content.Context
import timber.log.Timber
import java.io.File


object CaBundleExtractor {

    private const val OUT_NAME = "winlitesteam_cacert.pem"
    private const val SYS_CA_DIR = "/system/etc/security/cacerts"

    /**
     * Ensures the bundle exists; returns its absolute path. Empty string
     * on failure (in which case TLS handshakes will be rejected).
     */
    fun ensureBundle(context: Context): String {
        val out = File(context.filesDir, OUT_NAME)
        if (out.exists() && out.length() > 1024) {
            // Sanity floor: a single empty file means a prior extraction failed.
            return out.absolutePath
        }

        val src = File(SYS_CA_DIR)
        if (!src.isDirectory) {
            Timber.w("WinLiteSteam CA bundle: %s missing, TLS will fail", SYS_CA_DIR)
            return ""
        }
        val pems = src.listFiles { f -> f.isFile && f.name.endsWith(".0") }
            ?: emptyArray()
        if (pems.isEmpty()) {
            Timber.w("WinLiteSteam CA bundle: no *.0 files under %s", SYS_CA_DIR)
            return ""
        }

        try {
            out.bufferedWriter().use { w ->
                for (f in pems) {
                    try {
                        f.bufferedReader().use { r ->
                            r.copyTo(w)
                        }
                        w.newLine()
                    } catch (e: Exception) {
                        Timber.w(e, "WinLiteSteam CA: skipped %s", f.name)
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "WinLiteSteam CA: bundle write failed")
            return ""
        }
        Timber.i("WinLiteSteam CA bundle ready: %s (%d certs, %d bytes)",
            out.absolutePath, pems.size, out.length())
        return out.absolutePath
    }
}
