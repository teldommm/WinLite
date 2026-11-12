package com.winlator.cmod.steam

import android.content.Context
import com.winlator.cmod.core.TarCompressorUtils
import java.io.File

/** Optional Goldberg regular mode. Never modifies an original without keeping a backup. */
object SteamGoldberg {
    private fun root(context: Context) = File(context.filesDir, "steam-components/goldberg")
    // Goldberg "regular" steam_api DLLs (x86 + x64) ship inside the APK: assets/winlitesteam/bionic/goldberg.tzst.
    private const val ASSET = "winlitesteam/bionic/goldberg.tzst"

    @JvmOverloads @Synchronized fun ensureInstalled(context: Context, progress: ((String, Long, Long) -> Unit)? = null) {
        if (File(root(context), "regular/x64/steam_api64.dll").isFile &&
            File(root(context), "regular/x86/steam_api.dll").isFile) return
        val archive = File(context.cacheDir, "goldberg-bundled.tzst")
        try {
            progress?.invoke("extract", 0L, 0L)
            context.assets.open(ASSET).use { input -> archive.outputStream().use { input.copyTo(it) } }
            root(context).mkdirs()
            check(TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, archive, root(context), null)) {
                "Goldberg extraction failed."
            }
            check(File(root(context), "regular/x64/steam_api64.dll").isFile &&
                File(root(context), "regular/x86/steam_api.dll").isFile) {
                "Goldberg files are incomplete."
            }
        } catch (error: java.io.IOException) {
            throw IllegalStateException("Unable to unpack the bundled Goldberg component.", error)
        } finally {
            archive.delete()
        }
    }
    private val dllIndex = java.util.concurrent.ConcurrentHashMap<String, List<File>>()
    fun invalidate(game: File) { dllIndex.remove(game.canonicalPath) }
    private fun apiDlls(game: File): List<File> {
        val root = game.canonicalFile
        return dllIndex.getOrPut(root.path) {
            root.walkTopDown().onEnter { it.canonicalFile.toPath().startsWith(root.toPath()) }
                .filter { it.isFile && it.canonicalFile.toPath().startsWith(root.toPath()) }
                .map { if (it.name.endsWith(".winlite-original")) File(it.parentFile, it.name.removeSuffix(".winlite-original")) else it }
                .filter { it.name.lowercase() in setOf("steam_api.dll", "steam_api64.dll") }.distinctBy { it.path }.toList()
        }
    }
    fun settingsDirectories(game: File) = apiDlls(game).map { File(it.parentFile, "steam_settings") }.distinct()
    fun restore(game: File) {
        apiDlls(game).forEach { target ->
            File(target.parentFile, target.name + ".winlite-original").takeIf { it.isFile }?.copyTo(target, true)
        }
    }
    @JvmOverloads fun prepare(context: Context, game: File, id: Int, progress: ((String, Long, Long) -> Unit)? = null) {
        val dlls = apiDlls(game).filter { it.isFile }
        check(dlls.isNotEmpty()) { "No Steam API DLL found for regular Goldberg mode. Use SteamWinLite or Raw for this game." }
        ensureInstalled(context, progress)
        progress?.invoke("prepare", 0L, 0L)
        dlls.forEach { dll ->
            val backup = File(dll.parentFile, dll.name + ".winlite-original")
            if (!backup.exists()) dll.copyTo(backup)
            val arch = if (dll.name.equals("steam_api64.dll", true)) "x64" else "x86"
            File(root(context), "regular/$arch/${dll.name.lowercase()}").copyTo(dll, true)
            File(dll.parentFile, "steam_appid.txt").writeText(id.toString())
        }
    }
}
