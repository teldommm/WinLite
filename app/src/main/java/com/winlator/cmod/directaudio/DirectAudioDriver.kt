package com.winlator.cmod.directaudio

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.winlator.cmod.core.WineInfo
import com.winlator.cmod.xenvironment.ImageFs
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Host side of DirectAudio (Wine mmdevapi -> Android AAudio driver, by The412Banner,
 * LGPL-2.1-or-later: https://github.com/The412Banner/directaudio).
 *
 * The bundled builds live in assets/directaudio/ as complete 3-file sets:
 *   aarch64-windows/winedirectaudio.drv  arm64ec PE (64-bit guests)
 *   i386-windows/winedirectaudio.drv     32-bit PE (wow64 guests)
 *   aarch64-unix/winedirectaudio.so      bionic unixlib, the AAudio backend
 *
 * The driver plugs into mmdevapi's unixlib table, so which build fits is decided by the
 * selected Wine layer's own table size (36 entries = Wine/Proton 9-10, 37 = Wine 11),
 * read from that layer's winealsa.so/winepulse.so - never from a version string.
 */
object DirectAudioDriver {
    private const val TAG = "DirectAudio"

    const val IDENTIFIER = "directaudio"
    const val EXTRA_MIC = "directAudioMic"
    const val ENV_MIC = "WINLITE_AUDIO_DIRECT_MIC"
    const val REQUEST_RECORD_AUDIO = 4120

    private const val ASSET_DIR = "directaudio"
    private const val DRV_NAME = "winedirectaudio.drv"
    private const val SO_NAME = "winedirectaudio.so"
    private const val UNIX_CALL_TABLE = "__wine_unix_call_funcs"
    private const val SHT_DYNSYM = 11
    private const val PT_LOAD = 1

    // Every arm64ec Proton ships at least one of these; they carry the same mmdevapi
    // unixlib table the driver has to match.
    private val HOST_UNIX_PROBES = listOf("aarch64-unix/winealsa.so", "aarch64-unix/winepulse.so")

    // Archive entry -> destination dir under <wine>/lib/wine.
    private val LAYOUT = listOf(
        "aarch64-windows/$DRV_NAME" to "aarch64-windows",
        "i386-windows/$DRV_NAME" to "i386-windows",
        "aarch64-unix/$SO_NAME" to "aarch64-unix",
    )

    /** Outcome of [install]; [message] is user-facing when the driver cannot be used. */
    class Result(val ok: Boolean, val message: String?) {
        companion object {
            val OK = Result(true, null)
            fun fail(message: String) = Result(false, message)
        }
    }

    private class Bundle(val asset: String, val abi: Int, val loadAlign: Long)

    @Volatile private var bundles: List<Bundle>? = null

    fun isSelected(audioDriver: String?): Boolean = IDENTIFIER == audioDriver

    fun isMicEnabled(value: String?): Boolean = value == "1" || value.equals("true", true)

    // ---- install -------------------------------------------------------------------------

    /**
     * Makes the driver loadable for [wineInfo] and the current prefix. Must run after the
     * prefix is ready and BEFORE the registry names "directaudio": if it fails, the caller
     * falls back to another driver, because mmdevapi failing to load the named driver
     * makes titles that open audio on startup hang instead of just running silent.
     */
    fun install(context: Context, imageFs: ImageFs, wineInfo: WineInfo?): Result {
        if (wineInfo == null || !wineInfo.isArm64EC || wineInfo.path.isNullOrEmpty()) {
            return Result.fail("DirectAudio needs an arm64ec Wine/Proton")
        }
        val wineLibDir = File(wineInfo.path, "lib/wine")
        val hostAbi = hostUnixAbi(wineLibDir)
        val pageSize = pageSize()
        val candidates = loadBundles(context).filter { it.abi == hostAbi }
        val bundle = candidates.firstOrNull { it.loadAlign >= pageSize }
        if (bundle == null) {
            removeFromPrefix(imageFs)
            val message = when {
                hostAbi <= 0 -> "DirectAudio: cannot read the audio ABI of ${wineInfo.identifier()}"
                candidates.isEmpty() -> "DirectAudio has no build for ${wineInfo.identifier()}"
                else -> "DirectAudio for ${wineInfo.identifier()} does not support ${pageSize / 1024} KB memory pages"
            }
            Log.w(TAG, "$message (abi=$hostAbi, pageSize=$pageSize)")
            return Result.fail(message)
        }

        val stampId = "${bundle.asset}@${apkStamp(context)}"
        val stamp = File(wineLibDir, ".directaudio.stamp")
        val installed = LAYOUT.all { (entry, dir) -> File(wineLibDir, "$dir/${File(entry).name}").isFile }
        if (!installed || readStamp(stamp) != stampId) {
            try {
                if (!unzipBundle(context, bundle.asset, wineLibDir)) {
                    return Result.fail("DirectAudio: ${bundle.asset} is incomplete")
                }
                stamp.writeText(stampId)
                Log.i(TAG, "installed ${bundle.asset} (abi ${bundle.abi}) into $wineLibDir")
            } catch (e: Exception) {
                Log.e(TAG, "failed to install ${bundle.asset}", e)
                return Result.fail("DirectAudio: failed to install the driver")
            }
        }

        if (!mirrorIntoPrefix(imageFs, wineLibDir)) {
            return Result.fail("DirectAudio: failed to stage the driver into the prefix")
        }
        return Result.OK
    }

    // mmdevapi loads the backend with LoadLibraryW("winedirectaudio.drv"), which searches the
    // prefix (system32, and syswow64 for 32-bit guests) - not the Wine layer the bundle was
    // unpacked into. Stock backends are already there from prefix creation; this one is not.
    private fun mirrorIntoPrefix(imageFs: ImageFs, wineLibDir: File): Boolean {
        val windowsDir = File(imageFs.rootDir, ImageFs.WINEPREFIX + "/drive_c/windows")
        val win64 = copyIfChanged(File(wineLibDir, "aarch64-windows/$DRV_NAME"), File(windowsDir, "system32/$DRV_NAME"))
        val win32 = copyIfChanged(File(wineLibDir, "i386-windows/$DRV_NAME"), File(windowsDir, "syswow64/$DRV_NAME"))
        return win64 && win32
    }

    // A refused build must not stay in the prefix from an earlier, matching layer.
    private fun removeFromPrefix(imageFs: ImageFs) {
        val windowsDir = File(imageFs.rootDir, ImageFs.WINEPREFIX + "/drive_c/windows")
        for (dir in listOf("system32", "syswow64")) {
            val stale = File(windowsDir, "$dir/$DRV_NAME")
            if (stale.isFile && stale.delete()) Log.i(TAG, "removed unusable $DRV_NAME from $dir")
        }
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

    private fun unzipBundle(context: Context, asset: String, wineLibDir: File): Boolean {
        val wanted = LAYOUT.toMap()
        val written = mutableSetOf<String>()
        context.assets.open(asset).use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val dir = wanted[entry.name]
                    if (!entry.isDirectory && dir != null) {
                        // Destination comes from the known layout, never from the entry path.
                        val dest = File(wineLibDir, "$dir/${File(entry.name).name}")
                        dest.parentFile?.mkdirs()
                        val tmp = File(dest.path + ".tmp")
                        tmp.outputStream().use { zip.copyTo(it) }
                        if (!tmp.renameTo(dest)) {
                            tmp.delete()
                            return false
                        }
                        dest.setExecutable(true, false)
                        written += entry.name
                    }
                    zip.closeEntry()
                }
            }
        }
        val missing = wanted.keys - written
        if (missing.isNotEmpty()) Log.e(TAG, "$asset is missing ${missing.joinToString()}")
        return missing.isEmpty()
    }

    private fun apkStamp(context: Context): Long =
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        } catch (e: Exception) {
            0L
        }

    private fun readStamp(stamp: File): String? =
        try {
            if (stamp.isFile) stamp.readText().trim() else null
        } catch (e: Exception) {
            null
        }

    private fun pageSize(): Long =
        try {
            Os.sysconf(OsConstants._SC_PAGESIZE)
        } catch (e: Exception) {
            4096L
        }

    // ---- bundled builds ------------------------------------------------------------------

    private fun loadBundles(context: Context): List<Bundle> {
        bundles?.let { return it }
        val found = mutableListOf<Bundle>()
        val names = try {
            context.assets.list(ASSET_DIR)?.filter { it.endsWith(".zip") }.orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
        for (name in names.sorted()) {
            val asset = "$ASSET_DIR/$name"
            val so = readEntry(context, asset, "aarch64-unix/$SO_NAME") ?: continue
            val abi = unixCallEntries(so)
            val align = minLoadAlign(so)
            Log.i(TAG, "bundled $asset: abi=$abi loadAlign=0x${java.lang.Long.toHexString(align)}")
            if (abi > 0 && align > 0) found += Bundle(asset, abi, align)
        }
        bundles = found
        return found
    }

    private fun readEntry(context: Context, asset: String, entryName: String): ByteArray? =
        try {
            context.assets.open(asset).use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    var data: ByteArray? = null
                    while (data == null) {
                        val entry = zip.nextEntry ?: break
                        if (entry.name == entryName) data = zip.readBytes()
                        zip.closeEntry()
                    }
                    data
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not read $entryName in $asset", e)
            null
        }

    private fun hostUnixAbi(wineLibDir: File): Int {
        for (probe in HOST_UNIX_PROBES) {
            val file = File(wineLibDir, probe)
            if (!file.isFile) continue
            val entries = try {
                unixCallEntries(file.readBytes())
            } catch (e: Exception) {
                Log.w(TAG, "could not read $file", e)
                0
            }
            if (entries > 0) return entries
        }
        return 0
    }

    // ---- ELF ---------------------------------------------------------------------------

    private fun isElf64Le(elf: ByteArray): Boolean =
        elf.size >= 64 && elf[0] == 0x7F.toByte() && elf[1] == 'E'.code.toByte() &&
            elf[2] == 'L'.code.toByte() && elf[3] == 'F'.code.toByte() &&
            elf[4].toInt() == 2 && elf[5].toInt() == 1

    private fun u16(elf: ByteArray, o: Int) = (elf[o].toInt() and 0xFF) or ((elf[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(elf: ByteArray, o: Int) = u16(elf, o).toLong() or (u16(elf, o + 2).toLong() shl 16)
    private fun u64(elf: ByteArray, o: Int) = u32(elf, o) or (u32(elf, o + 4) shl 32)

    /** Entry count of the exported __wine_unix_call_funcs table, 0 if absent. */
    internal fun unixCallEntries(elf: ByteArray): Int {
        if (!isElf64Le(elf)) return 0
        val sectionOffset = u64(elf, 0x28).toInt()
        val sectionSize = u16(elf, 0x3A)
        val sectionCount = u16(elf, 0x3C)
        if (sectionOffset <= 0 || sectionCount == 0) return 0
        for (i in 0 until sectionCount) {
            val section = sectionOffset + i * sectionSize
            if (section + sectionSize > elf.size) return 0
            if (u32(elf, section + 4).toInt() != SHT_DYNSYM) continue
            val symbols = u64(elf, section + 24).toInt()
            val symbolsSize = u64(elf, section + 32).toInt()
            val entrySize = u64(elf, section + 56).toInt().let { if (it > 0) it else 24 }
            val linked = sectionOffset + u32(elf, section + 40).toInt() * sectionSize
            if (linked + sectionSize > elf.size) return 0
            val strings = u64(elf, linked + 24).toInt()
            var sym = symbols
            while (sym + entrySize <= symbols + symbolsSize && sym + entrySize <= elf.size) {
                val at = strings + u32(elf, sym).toInt()
                if (at in 0 until elf.size) {
                    var end = at
                    while (end < elf.size && elf[end] != 0.toByte()) end++
                    if (String(elf, at, end - at, Charsets.US_ASCII) == UNIX_CALL_TABLE) {
                        return (u64(elf, sym + 16) / 8L).toInt()
                    }
                }
                sym += entrySize
            }
        }
        return 0
    }

    /** Smallest PT_LOAD alignment: a 4 KB-aligned build cannot be mapped on a 16 KB-page kernel. */
    internal fun minLoadAlign(elf: ByteArray): Long {
        if (!isElf64Le(elf)) return 0
        val phOffset = u64(elf, 0x20).toInt()
        val phSize = u16(elf, 0x36)
        val phCount = u16(elf, 0x38)
        var min = 0L
        for (i in 0 until phCount) {
            val ph = phOffset + i * phSize
            if (ph + 56 > elf.size) break
            if (u32(elf, ph).toInt() != PT_LOAD) continue
            val align = u64(elf, ph + 48)
            if (min == 0L || align < min) min = align
        }
        return min
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
