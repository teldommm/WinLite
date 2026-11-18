package com.winlator.cmod.components

import android.util.Log
import com.winlator.cmod.components.offline.OfflineTools
import com.winlator.cmod.components.offline.WineRegistryFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * Undo journals for the local Windows components: every install records which files it replaced
 * (kept in backup/) and which registry values it changed, so removing a component puts the prefix
 * back. A component that overwrote files of a later-installed one hands its backups over to that
 * component's journal.
 *
 * Trimmed port of the offline-installer journal: only the journaled (file-placing) install path —
 * no installer-session snapshots.
 */
object ComponentUndo {
    private const val TAG = "ComponentUndo"
    private const val DIR = ".winlite-component-undo"
    private const val JOURNAL = "journal.json"

    private fun safe(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    private fun dirOf(root: File, name: String) = File(root, "$DIR/${safe(name)}")
    private fun ident(hive: String, key: String, name: String) = "$hive\t${key.lowercase(Locale.ROOT)}\t${name.lowercase(Locale.ROOT)}"

    /** The journals [name] went in under, newest first. */
    fun journalsFor(root: File, name: String): List<String> {
        return listOf(name).mapNotNull { n -> read(dirOf(root, n))?.let { n to it.optLong("seq") } }
            .sortedByDescending { it.second }.map { it.first }
    }

    /** Only this manager's restore journals, independent of the catalog or preferences. */
    fun installedNames(root: File): List<String> = all(root).sortedByDescending { it.optLong("seq") }
        .map { it.getString("name") }

    /** Undo the latest changes first. */
    @Synchronized
    fun revertAll(root: File, onRemoved: (String) -> Unit = {}, onProgress: (Float) -> Unit = {}): List<String> {
        val names = installedNames(root)
        val notes = ArrayList<String>()
        names.forEachIndexed { index, name ->
            notes += revert(root, name) { part -> onProgress((index + part) / names.size) }
            onRemoved(name)
            onProgress((index + 1f) / names.size)
        }
        return notes
    }

    /** Starts [name]'s journal in the container at [root]. [Journal.close] saves it. */
    fun open(root: File, name: String): Journal = Journal(root, name)

    class Journal internal constructor(root: File, val name: String) {
        private val wine = File(root, ".wine")
        private val dir = dirOf(root, name)
        internal val json: JSONObject = read(dir)
            ?: JSONObject().put("version", 1).put("name", name).put("seq",
                maxOf(System.currentTimeMillis(), (all(root).maxOfOrNull { it.optLong("seq") } ?: 0L) + 1L))
        // The registry as it was when the journal opened; compared with the files at close.
        private val before: RegState? = RegState.read(wine)
        // The files written while open, whose size and time are noted at close.
        private val touched = HashSet<String>()
        private var closed = false

        internal fun obj(key: String): JSONObject = json.optJSONObject(key) ?: JSONObject().also { json.put(key, it) }
        internal fun arr(key: String): JSONArray = json.optJSONArray(key) ?: JSONArray().also { json.put(key, it) }

        private fun relOf(f: File): String? {
            val base = wine.absolutePath + File.separator
            val path = f.absolutePath
            return if (path.startsWith(base)) path.substring(base.length).replace(File.separatorChar, '/') else null
        }

        /**
         * Call just before [target] is written over or deleted. The first time this journal sees it,
         * the file there now (or the link) is kept, and the folders the write is about to make are
         * noted. Returns true when it was noted now.
         */
        fun beforeWrite(target: File): Boolean {
            val rel = relOf(target) ?: return false
            touched += rel
            val files = obj("files")
            if (files.has(rel)) return false
            val dirs = arr("dirs")
            var parent = target.absoluteFile.parentFile
            while (parent != null && !parent.exists()) {
                val d = relOf(parent) ?: break
                if (!contains(dirs, d)) dirs.put(d)
                parent = parent.parentFile
            }
            val entry = JSONObject()
            val path = target.toPath()
            if (Files.isSymbolicLink(path)) entry.put("link", Files.readSymbolicLink(path).toString())
            else if (target.isFile) entry.put("backup", keep(target))
            files.put(rel, entry)
            return true
        }

        /** Places one bundle file ([rel] under [prefix], the windows folder), journaled. */
        fun placeSingle(file: File, prefix: File, rel: String) {
            val target = try {
                com.winlator.cmod.components.offline.PrefixPlacer.resolve(prefix, rel)
            } catch (e: Exception) {
                File(prefix, rel)
            }
            val fresh = beforeWrite(target)
            val placed = try {
                com.winlator.cmod.components.offline.PrefixPlacer.placeOne(file, prefix, rel)
            } catch (e: Exception) {
                Log.w(TAG, "place $rel", e)
                false
            }
            if (!placed && fresh) forget(target)
        }

        /** Undoes [beforeWrite] for a write that did not happen (the same file was already there). */
        internal fun forget(target: File) {
            val rel = relOf(target) ?: return
            touched -= rel
            val entry = obj("files").remove(rel) as? JSONObject ?: return
            entry.optString("backup").takeIf { it.isNotEmpty() }?.let { File(dir, "backup/$it").delete() }
        }

        /** The original's copy in backup/: a hard link when it can be (the write replaces the file, never writes into it). */
        private fun keep(target: File): String {
            val id = nextBackup(json)
            val copy = File(dir, "backup/$id")
            copy.parentFile?.mkdirs()
            try {
                Files.createLink(copy.toPath(), target.toPath())
            } catch (e: Exception) {
                target.copyTo(copy, overwrite = true)
            }
            return id
        }

        /** Notes what the files are now and what the registry changed, and saves. Safe to call twice. */
        fun close() {
            if (closed) return
            closed = true
            try {
                val files = obj("files")
                for (rel in touched) files.optJSONObject(rel)?.let { stamp(it, File(wine, rel)) }
                before?.let { b -> RegState.read(wine)?.let { after -> record(b, after) } }
                save()
            } catch (e: Exception) {
                Log.e(TAG, "close $name", e)
            }
        }

        /** Notes how the registry went from [before] to [after]: every value this install changed. */
        internal fun record(before: RegState, after: RegState) {
            val registry = obj("registry")
            for ((id, v) in after.values) {
                val was = before.values[id]?.get(3)
                if (was == v[3]) continue
                note(registry, id, v[0], v[1], v[2], was, v[3])
            }
            for ((id, v) in before.values) {
                if (after.values.containsKey(id)) continue
                note(registry, id, v[0], v[1], v[2], v[3], null)
            }
            val keys = arr("keys")
            for ((id, k) in after.keys) {
                if (before.keys.containsKey(id)) continue
                val known = (0 until keys.length()).any { i ->
                    keys.getJSONObject(i).let { "${it.optString("hive")}\t${it.optString("key").lowercase(Locale.ROOT)}" == id }
                }
                if (!known) keys.put(JSONObject().put("hive", k.first).put("key", k.second))
            }
        }

        // The first value before it is the one to put back; the latest is what it set.
        private fun note(registry: JSONObject, id: String, hive: String, key: String, name: String, prev: String?, set: String?) {
            val known = registry.optJSONObject(id)
            if (known != null) {
                known.put("set", set ?: JSONObject.NULL)
                return
            }
            registry.put(id, JSONObject().put("hive", hive).put("key", key).put("name", name)
                .put("prev", prev ?: JSONObject.NULL).put("set", set ?: JSONObject.NULL))
        }

        internal fun save() {
            dir.mkdirs()
            write(File(dir, JOURNAL), json.toString(1))
        }
    }

    // ---- taking it out ----------------------------------------------------------------------------

    /**
     * Puts back what [name]'s journal holds and deletes the journal. [onProgress] gets 0..1. Returns
     * notes on what was left as it is (changed since, or with no original to put back).
     */
    @Synchronized
    fun revert(root: File, name: String, onProgress: (Float) -> Unit = {}): List<String> {
        val dir = dirOf(root, name)
        val json = read(dir) ?: return emptyList()
        val wine = File(root, ".wine")
        val seq = json.optLong("seq")
        // Components that went in after this one: they may have changed the same things since.
        val later = all(root).filter { it.optString("name") != name && it.optLong("seq") > seq }.sortedBy { it.optLong("seq") }
        val handed = LinkedHashSet<JSONObject>()
        val files = json.optJSONObject("files") ?: JSONObject()
        val registry = json.optJSONObject("registry") ?: JSONObject()
        val total = (files.length() + registry.length()).coerceAtLeast(1)
        var done = 0
        var left = 0

        for (rel in files.keys().asSequence().toList()) {
            val entry = files.getJSONObject(rel)
            val heir = later.firstOrNull { it.optJSONObject("files")?.has(rel) == true }
            if (heir != null) {
                handFile(root, dir, entry, heir, rel)
                handed += heir
            } else if (!restoreFile(dir, wine, rel, entry)) left++
            if (++done % 25 == 0) onProgress(done.toFloat() / total)
        }

        val regs = HashMap<String, WineRegistryFile>()
        fun reg(hive: String): WineRegistryFile? {
            regs[hive]?.let { return it }
            val file = File(wine, if (hive == "HKLM") "system.reg" else "user.reg")
            if (!file.isFile) return null
            return WineRegistryFile(file).also { regs[hive] = it }
        }
        for (id in registry.keys().asSequence().toList()) {
            val entry = registry.getJSONObject(id)
            val heir = later.firstOrNull { it.optJSONObject("registry")?.has(id) == true }
            if (heir != null) {
                heir.getJSONObject("registry").getJSONObject(id).put("prev", entry.opt("prev") ?: JSONObject.NULL)
                handed += heir
            } else {
                val r = reg(entry.optString("hive"))
                val key = entry.optString("key")
                val valueName = entry.optString("name")
                val set = if (entry.isNull("set")) null else entry.optString("set")
                val prev = if (entry.isNull("prev")) null else entry.optString("prev")
                if (r == null || r.get(key, valueName) != set) left++ else r.put(key, valueName, prev)
            }
            if (++done % 25 == 0) onProgress(done.toFloat() / total)
        }
        val keys = json.optJSONArray("keys") ?: JSONArray()
        val made = (0 until keys.length()).map { keys.getJSONObject(it) }.sortedByDescending { it.optString("key").length }
        for (k in made) {
            val r = reg(k.optString("hive")) ?: continue
            val key = k.optString("key")
            if (r.removeKeyIfEmpty(key)) continue
            // Still holds another component's values: it goes when that one does.
            val lower = key.lowercase(Locale.ROOT)
            val heir = later.firstOrNull { h ->
                val values = h.optJSONObject("registry") ?: return@firstOrNull false
                values.keys().asSequence().any { id -> values.getJSONObject(id).optString("key").lowercase(Locale.ROOT).let { it == lower || it.startsWith("$lower\\") } }
            }
            if (heir != null) {
                (heir.optJSONArray("keys") ?: JSONArray().also { heir.put("keys", it) }).put(JSONObject(k.toString()))
                handed += heir
            }
        }
        regs.values.forEach { runCatching { it.save() } }

        val dirs = json.optJSONArray("dirs") ?: JSONArray()
        val folders = (0 until dirs.length()).map { dirs.getString(it) }.sortedByDescending { it.length }
        for (rel in folders) {
            val folder = File(wine, rel)
            if (folder.isDirectory && !Files.isSymbolicLink(folder.toPath()) && folder.list()?.isEmpty() == true) {
                folder.delete()
            } else if (folder.isDirectory) {
                // Still holds a later component's files: the folder goes when that one does.
                val heir = later.firstOrNull { h -> h.optJSONObject("files")?.keys()?.asSequence()?.any { it.startsWith("$rel/") } == true }
                if (heir != null) {
                    val theirs = heir.optJSONArray("dirs") ?: JSONArray().also { heir.put("dirs", it) }
                    if (!contains(theirs, rel)) theirs.put(rel)
                    handed += heir
                }
            }
        }
        for (h in handed) write(File(dirOf(root, h.optString("name")), JOURNAL), h.toString(1))
        OfflineTools.deleteTree(dir)
        onProgress(1f)

        val notes = ArrayList<String>()
        if (left > 0) notes += "$left file(s) or registry value(s) changed since it went in were left as they are"
        Log.i(TAG, "reverted $name: ${files.length()} file(s), ${registry.length()} value(s), $left left")
        return notes
    }

    /** A later component also wrote [rel]: its journal gets this one's original (or none, when this one added it). */
    private fun handFile(root: File, dir: File, entry: JSONObject, heir: JSONObject, rel: String) {
        val heirDir = dirOf(root, heir.optString("name"))
        val theirs = heir.getJSONObject("files").getJSONObject(rel)
        theirs.optString("backup").takeIf { it.isNotEmpty() }?.let { File(heirDir, "backup/$it").delete() }
        theirs.remove("backup")
        theirs.remove("link")
        entry.optString("backup").takeIf { it.isNotEmpty() }?.let { id ->
            val from = File(dir, "backup/$id")
            if (from.isFile) {
                val next = nextBackup(heir)
                val to = File(heirDir, "backup/$next")
                to.parentFile?.mkdirs()
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
                theirs.put("backup", next)
            }
        }
        if (entry.has("link")) theirs.put("link", entry.getString("link"))
    }

    /** Puts [rel] back as it was; false when it changed since and is left as it is. */
    private fun restoreFile(dir: File, wine: File, rel: String, entry: JSONObject): Boolean {
        val f = File(wine, rel)
        val path = f.toPath()
        val link = Files.isSymbolicLink(path)
        val size = if (!link && f.isFile) f.length() else -1L
        if (entry.has("size")) {
            val want = entry.getLong("size")
            if (size != want || size >= 0 && entry.has("sha256") && fileHash(f) != entry.getString("sha256")) return false
            if (!entry.has("sha256") && size >= 0 && entry.has("mtime") && f.lastModified() / 1000 != entry.getLong("mtime")) return false
        }
        val backup = entry.optString("backup").takeIf { it.isNotEmpty() }?.let { File(dir, "backup/$it") }
        when {
            backup != null && backup.isFile -> {
                f.parentFile?.mkdirs()
                Files.move(backup.toPath(), path, StandardCopyOption.REPLACE_EXISTING)
            }
            entry.has("link") -> {
                Files.deleteIfExists(path)
                f.parentFile?.mkdirs()
                Files.createSymbolicLink(path, Paths.get(entry.getString("link")))
            }
            // The original's copy went missing: what is there now stays.
            backup != null -> return false
            !link -> Files.deleteIfExists(path)
        }
        return true
    }

    // ---- helpers ----------------------------------------------------------------------------------

    /** The prefix's registry values and keys, by ident: values [hive, key, name, line(s)], keys hive to key. */
    internal class RegState(val values: Map<String, Array<String>>, val keys: Map<String, Pair<String, String>>) {
        companion object {
            fun read(wine: File): RegState? = read(File(wine, "system.reg"), File(wine, "user.reg"))

            fun read(system: File, user: File): RegState? = try {
                val values = HashMap<String, Array<String>>()
                val keys = HashMap<String, Pair<String, String>>()
                for ((hive, file) in listOf("HKLM" to system, "HKCU" to user)) {
                    if (!file.isFile) continue
                    val reg = WineRegistryFile(file)
                    for (k in reg.keys()) keys["$hive\t${k.lowercase(Locale.ROOT)}"] = hive to k
                    for (v in reg.values()) values[ident(hive, v[0], v[1])] = arrayOf(hive, v[0], v[1], v[2])
                }
                RegState(values, keys)
            } catch (e: Exception) {
                Log.w(TAG, "registry", e)
                null
            }
        }
    }

    private fun fileHash(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun stamp(entry: JSONObject, f: File) {
        if (!Files.isSymbolicLink(f.toPath()) && f.isFile) {
            entry.put("size", f.length()).put("mtime", f.lastModified() / 1000).put("sha256", fileHash(f))
        } else {
            entry.put("size", -1L)
            entry.remove("mtime")
        }
    }

    private fun nextBackup(json: JSONObject): String {
        val n = json.optInt("next", 0)
        json.put("next", n + 1)
        return n.toString()
    }

    private fun contains(a: JSONArray, s: String): Boolean = (0 until a.length()).any { a.optString(it) == s }

    private fun read(dir: File): JSONObject? = try {
        val f = File(dir, JOURNAL)
        if (f.isFile) JSONObject(f.readText()) else null
    } catch (e: Exception) {
        Log.w(TAG, "read $dir", e)
        null
    }

    private fun all(root: File): List<JSONObject> =
        File(root, DIR).listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { read(it) }

    private fun write(file: File, text: String) {
        val temp = File(file.parentFile, "." + file.name + ".tmp")
        temp.writeText(text)
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
