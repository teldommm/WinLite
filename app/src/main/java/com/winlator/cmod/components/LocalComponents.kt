package com.winlator.cmod.components

import android.content.Context
import android.util.Log
import com.winlator.cmod.core.TarCompressorUtils
import org.json.JSONObject
import java.io.File

/** One installable Windows component from the bundle shipped inside the APK. */
data class LocalComponent(
    val id: String,
    val title: String,
    val description: String,
    val overrides: List<String>,
    val size: Long,
)

/**
 * The local component bundle: one tar.xz in assets, unpacked into the app's files directory on
 * first use. No network, no external repositories — the catalog (components.json) travels inside
 * the bundle.
 */
object LocalComponents {
    private const val TAG = "LocalComponents"
    private const val ASSET = "components/bundle.tar.xz"
    private const val DIR = "components-bundle"
    private const val MARKER = ".ready"

    @Volatile
    private var cached: List<LocalComponent>? = null

    private fun dir(context: Context) = File(context.filesDir, DIR)

    /** The unpacked bundle directory, extracting it from assets first when needed. False on failure. */
    @Synchronized
    fun ensureReady(context: Context): Boolean {
        val root = dir(context)
        if (File(root, MARKER).isFile) return true
        Log.i(TAG, "extracting the component bundle")
        root.mkdirs()
        val tmp = File(context.cacheDir, "components-bundle.tmp")
        OfflineToolsDelete(tmp)
        return try {
            context.assets.open(ASSET).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (!TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, tmp, root)) {
                Log.e(TAG, "bundle extraction failed")
                false
            } else {
                File(root, MARKER).writeText("1")
                cached = null
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "bundle extraction", e)
            false
        } finally {
            tmp.delete()
        }
    }

    private fun OfflineToolsDelete(f: File) {
        if (f.isDirectory) f.deleteRecursively() else f.delete()
    }

    /** All components from the bundle's components.json. Empty when the bundle is not ready. */
    fun list(context: Context): List<LocalComponent> {
        cached?.let { return it }
        if (!ensureReady(context)) return emptyList()
        val json = File(dir(context), "components.json")
        if (!json.isFile) return emptyList()
        return try {
            val arr = JSONObject(json.readText()).optJSONArray("components") ?: return emptyList()
            (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                LocalComponent(
                    id = c.optString("id"),
                    title = c.optString("title"),
                    description = c.optString("description"),
                    overrides = c.optJSONArray("overrides")?.let { o -> (0 until o.length()).map { k -> o.getString(k) } } ?: emptyList(),
                    size = c.optLong("size"),
                )
            }.also { cached = it }
        } catch (e: Exception) {
            Log.w(TAG, "components.json", e)
            emptyList()
        }
    }

    /** The unpacked directory of [id]; null when the id is not in the catalog. */
    fun componentDir(context: Context, id: String): File? {
        if (list(context).none { it.id == id }) return null
        val dir = File(dir(context), id)
        return if (dir.isDirectory) dir else null
    }
}
