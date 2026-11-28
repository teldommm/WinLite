package com.winlator.cmod.steam

import java.io.File
import org.json.JSONObject

internal object SteamInstallRecord {
    fun directory(filesDir: File): File = File(filesDir, "steam-library").also {
        check(it.isDirectory || it.mkdirs()) { "Cannot create Steam installation records" }
    }

    fun write(filesDir: File, id: Int, record: JSONObject) {
        require(id > 0)
        val root = directory(filesDir)
        val temp = File.createTempFile(".$id-", ".tmp", root)
        try {
            temp.writeText(record.toString())
            check(temp.renameTo(File(root, "$id.json"))) { "Cannot save Steam installation record" }
        } finally {
            temp.delete()
        }
    }
}

/** The downloader records unsigned manifest IDs. Read their original decimal representation. */
internal fun installedSteamManifests(directory: File): Map<Int, Long> {
    val config = File(directory, ".winlite_depot/depot.config")
    if (!config.isFile) return emptyMap()
    return android.util.JsonReader(config.reader()).use { reader ->
        buildMap {
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() != "installedManifestIDs") {
                    reader.skipValue()
                    continue
                }
                reader.beginObject()
                while (reader.hasNext()) {
                    val id = reader.nextName().toIntOrNull()
                    val gid = reader.nextString().toULongOrNull()
                    if (id != null && gid != null && gid != 0uL && gid != Long.MAX_VALUE.toULong()) {
                        put(id, gid.toLong())
                    }
                }
                reader.endObject()
            }
            reader.endObject()
        }
    }
}
