package com.winlator.cmod.directaudio

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.winlator.cmod.core.StringUtils
import com.winlator.cmod.core.WineInfo
import com.winlator.cmod.xenvironment.ImageFs
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Host side of DirectAudio (Wine mmdevapi -> Android AAudio driver, by The412Banner,
 * LGPL-2.1-or-later: https://github.com/The412Banner/directaudio).
 *
 * Offered only for Proton 11 arm64ec: the bundled build is the Wine 11 one (its mmdevapi
 * unixlib table does not match Proton 9/10), and it is 16 KB-aligned, so it loads on both
 * 4 KB and 16 KB page kernels. assets/directaudio/directaudio-wine11-arm64ec.zip holds:
 *   aarch64-windows/winedirectaudio.drv  arm64ec PE (64-bit guests)
 *   i386-windows/winedirectaudio.drv     32-bit PE (wow64 guests)
 *   aarch64-unix/winedirectaudio.so      bionic unixlib, the AAudio backend
 */
object DirectAudioDriver {
    private const val TAG = "DirectAudio"

    const val IDENTIFIER = "directaudio"
    const val EXTRA_MIC = "directAudioMic"
    const val ENV_MIC = "WINLITE_AUDIO_DIRECT_MIC"
    const val REQUEST_RECORD_AUDIO = 4120

    private const val BUNDLE = "directaudio/directaudio-wine11-arm64ec.zip"
    private const val DRV_NAME = "winedirectaudio.drv"
    private const val SO_NAME = "winedirectaudio.so"

    // Archive entry -> destination dir under <wine>/lib/wine.
    private val LAYOUT = listOf(
        "aarch64-windows/$DRV_NAME" to "aarch64-windows",
        "i386-windows/$DRV_NAME" to "i386-windows",
        "aarch64-unix/$SO_NAME" to "aarch64-unix",
    )

    // Wine identifiers are "proton-<version>-<arch>"; installed contents may append "-<code>".
    private val PROTON_11_ARM64EC = Regex("^proton-11([.-].*)?-arm64ec(-.*)?$", RegexOption.IGNORE_CASE)

    fun isSelected(audioDriver: String?): Boolean = IDENTIFIER == audioDriver

    /** DirectAudio is offered only when the container runs Proton 11 arm64ec. */
    fun isSupportedWine(wineVersion: String?): Boolean =
        wineVersion != null && PROTON_11_ARM64EC.matches(wineVersion.trim())

    /** The audio driver list for [wineVersion]: without DirectAudio unless it is supported. */
    fun audioEntriesFor(entries: List<String>, wineVersion: String?): List<String> =
        if (isSupportedWine(wineVersion)) entries
        else entries.filterNot { isSelected(StringUtils.parseIdentifier(it)) }

    fun isMicEnabled(value: String?): Boolean = value == "1" || value.equals("true", true)

    /**
     * Unpacks the driver into the Wine layer (once per install: every app update reinstalls
     * the layers) and stages both PE halves into the prefix. Must run after the prefix is
     * ready and before the registry names "directaudio".
     */
    fun install(context: Context, imageFs: ImageFs, wineInfo: WineInfo?): Boolean {
        if (wineInfo == null || wineInfo.path.isNullOrEmpty() || !isSupportedWine(wineInfo.identifier())) {
            Log.e(TAG, "DirectAudio selected for unsupported Wine ${wineInfo?.identifier()}")
            return false
        }
        val wineLibDir = File(wineInfo.path, "lib/wine")
        val installed = LAYOUT.all { (entry, dir) -> File(wineLibDir, "$dir/${File(entry).name}").isFile }
        if (!installed) {
            try {
                if (!unzipBundle(context, wineLibDir)) return false
                Log.i(TAG, "installed $BUNDLE into $wineLibDir")
            } catch (e: Exception) {
                Log.e(TAG, "failed to install $BUNDLE", e)
                return false
            }
        }
        return mirrorIntoPrefix(imageFs, wineLibDir)
    }

    // mmdevapi loads the backend with LoadLibraryW("winedirectaudio.drv"), which searches the
    // prefix (system32, and syswow64 for 32-bit guests) - not the Wine layer it was unpacked
    // into. Stock backends are there from prefix creation; this one is not.
    private fun mirrorIntoPrefix(imageFs: ImageFs, wineLibDir: File): Boolean {
        val windowsDir = File(imageFs.rootDir, ImageFs.WINEPREFIX + "/drive_c/windows")
        val win64 = copyIfChanged(File(wineLibDir, "aarch64-windows/$DRV_NAME"), File(windowsDir, "system32/$DRV_NAME"))
        val win32 = copyIfChanged(File(wineLibDir, "i386-windows/$DRV_NAME"), File(windowsDir, "syswow64/$DRV_NAME"))
        return win64 && win32
    }

    private fun copyIfChanged(src: File, dst: File): Boolean {
        if (!src.isFile) {
            Log.e(TAG, "no driver PE at $src")
            return false
        }
        return try {
            val data = src.readBytes()
            if (dst.isFile && dst.length() == data.size.toLong() && dst.readBytes().contentEquals(data)) return true
            dst.parentFile?.mkdirs()
            dst.writeBytes(data)
            Log.i(TAG, "staged ${src.name} into ${dst.parentFile?.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "failed to stage ${src.name} into the prefix", e)
            false
        }
    }

    private fun unzipBundle(context: Context, wineLibDir: File): Boolean {
        val wanted = LAYOUT.toMap()
        val written = mutableSetOf<String>()
        context.assets.open(BUNDLE).use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val dir = wanted[entry.name]
                    if (!entry.isDirectory && dir != null) {
                        // Destination comes from the known layout, never from the entry path.
                        val dest = File(wineLibDir, "$dir/${File(entry.name).name}")
                        dest.parentFile?.mkdirs()
                        dest.outputStream().use { zip.copyTo(it) }
                        dest.setExecutable(true, false)
                        written += entry.name
                    }
                    zip.closeEntry()
                }
            }
        }
        val missing = wanted.keys - written
        if (missing.isNotEmpty()) Log.e(TAG, "$BUNDLE is missing ${missing.joinToString()}")
        return missing.isEmpty()
    }

    // ---- microphone --------------------------------------------------------------------

    fun hasMicPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Asks for RECORD_AUDIO right when the user turns the microphone on. */
    fun requestMicPermission(context: Context) {
        if (hasMicPermission(context)) return
        val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>()
            .firstOrNull()
        if (activity == null) {
            Log.w(TAG, "no activity in context chain; cannot request RECORD_AUDIO")
            return
        }
        ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
    }

    /**
     * The capture endpoint is only exposed when the user asked for it AND the permission is
     * granted: an endpoint a game can enumerate but not open black-screens titles that probe
     * the microphone while loading (God of War, DiRT 3).
     */
    fun shouldExposeMic(context: Context, micRequested: Boolean): Boolean {
        if (!micRequested) return false
        if (!hasMicPermission(context)) {
            Log.w(TAG, "microphone requested but RECORD_AUDIO not granted; capture stays off")
            return false
        }
        return true
    }
}
