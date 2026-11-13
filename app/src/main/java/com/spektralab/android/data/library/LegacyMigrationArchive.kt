package com.spektralab.android.data.library

import com.spektralab.android.core.model.LegacySourceBinding
import com.spektralab.android.core.model.SourceAvailability
import com.spektralab.android.core.model.SourceEvidence
import com.spektralab.android.data.local.writeAtomicJson
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Immutable copies precede every database write. A missing manifest is an incomplete capture. */
internal class LegacyMigrationArchive private constructor(val directory: File, private val manifest: JSONObject) {
    data class Entry(val key: String, val sha256: String, val relativePath: String, val bytes: Long)
    val id: String = manifest.getString("id")
    val entries: List<Entry> = manifest.getJSONArray("sidecars").let { array ->
        (0 until array.length()).map { index -> array.getJSONObject(index).let {
            Entry(it.getString("key"), it.getString("sha256"), it.getString("path"), it.getLong("bytes"))
        } }
    }
    val sessionKeys: List<String> = manifest.getJSONArray("session").let { array ->
        (0 until array.length()).map(array::getString)
    }
    val currentPosition: Int? = if (manifest.isNull("current_position")) null else manifest.getInt("current_position")
    val bindings: List<LegacySourceBinding> = manifest.getJSONArray("bindings").let { array ->
        (0 until array.length()).map { index -> array.getJSONObject(index).let { row ->
            val source = row.getJSONObject("source")
            LegacySourceBinding(row.getString("key"), SourceEvidence(
                source.getString("uri"), source.getString("source_key"), source.getString("authority"),
                source.optString("document_id").takeIf(String::isNotEmpty),
                source.optLong("media_id", -1).takeIf { it > 0 }, source.getString("name"), source.getString("mime"),
                source.getLong("size"), source.getInt("width"), source.getInt("height"), source.getInt("orientation"),
                source.optString("sha256").takeIf(String::isNotEmpty), SourceAvailability.valueOf(source.getString("availability")),
            ))
        } }
    }

    fun payload(entry: Entry): String {
        require(entry in entries && entry.bytes <= MAX_EDIT_BYTES)
        val file = checkedFile(entry)
        require(file.length() == entry.bytes && sha256(file) == entry.sha256) { "ARCHIVE_PAYLOAD_CHANGED" }
        return file.readText(Charsets.UTF_8)
    }

    fun verify() {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,64}")))
        require(entries.map { it.key }.distinct().size == entries.size)
        require(currentPosition == null || currentPosition in sessionKeys.indices)
        entries.forEach { entry ->
            require(validKey(entry.key) && entry.bytes >= 0 && entry.sha256.matches(Regex("[a-f0-9]{64}")))
            val file = checkedFile(entry)
            require(file.length() == entry.bytes && sha256(file) == entry.sha256) { "ARCHIVE_PAYLOAD_CHANGED" }
        }
        require(sessionKeys.all(::validKey) && bindings.all { validKey(it.legacyKey) })
        bindings.forEach { binding ->
            val source = binding.source
            require(source.sourceKey.matches(Regex("[a-f0-9]{64}")))
            require(source.uri.startsWith("file:") || source.uri.startsWith("content:"))
            require(source.width >= 0 && source.height >= 0 && source.orientation in 1..8 && source.size >= 0)
        }
    }

    private fun checkedFile(entry: Entry): File {
        require(entry.relativePath == "sidecars/${entry.key}.json")
        return File(directory, entry.relativePath).also {
            require(it.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath()) && it.isFile)
        }
    }

    companion object {
        const val MAX_EDIT_BYTES = 2L * 1024 * 1024
        fun validKey(key: String) = key.matches(Regex("(?:media-[1-9][0-9]*|uri-[0-9a-f]{16})"))

        /** Call under the legacy store lock when capturing real sidecars. Never copies originals. */
        fun capture(legacyDirectory: File, snapshots: File, bindings: List<LegacySourceBinding>,
                    session: List<String>, currentPosition: Int?): LegacyMigrationArchive {
            require(bindings.all { validKey(it.legacyKey) } && session.all(::validKey))
            require(currentPosition == null || currentPosition in session.indices)
            val source = legacyDirectory.canonicalFile.toPath()
            val destination = snapshots.canonicalFile.toPath()
            require(!destination.startsWith(source) && !source.startsWith(destination)) { "ARCHIVE_MUST_BE_SEPARATE" }
            require(!File(legacyDirectory, "pending-edit-batch.json").exists() &&
                !File(legacyDirectory, "pending-edit-batch.json.bak").exists()) { "LEGACY_BATCH_NOT_SETTLED" }
            val id = UUID.randomUUID().toString()
            val directory = File(snapshots, id)
            check(directory.mkdirs())
            val sidecars = JSONArray()
            val files = legacyDirectory.listFiles().orEmpty().filter { it.isFile &&
                (it.name.endsWith(".json") || it.name.endsWith(".json.bak")) &&
                validKey(it.name.removeSuffix(".bak").removeSuffix(".json")) }
                .groupBy { it.name.removeSuffix(".bak").removeSuffix(".json") }
            for ((key, candidates) in files.toSortedMap()) {
                // AtomicFile's committed .bak is read as evidence without recovering live files.
                val file = candidates.firstOrNull { it.name.endsWith(".bak") } ?: candidates.single()
                val relative = "sidecars/$key.json"
                val target = File(directory, relative)
                target.parentFile!!.mkdirs()
                val before = sha256(file)
                file.inputStream().use { input -> target.outputStream().use { output ->
                    input.copyTo(output, 64 * 1024)
                    output.fd.sync()
                } }
                check(sha256(target) == before && sha256(file) == before) { "LEGACY_CHANGED_DURING_CAPTURE" }
                sidecars.put(JSONObject().put("key", key).put("sha256", before).put("path", relative).put("bytes", target.length()))
            }
            val manifest = JSONObject().put("version", 1).put("id", id).put("sidecars", sidecars)
                .put("session", JSONArray(session)).put("current_position", currentPosition ?: JSONObject.NULL)
                .put("bindings", JSONArray().apply { bindings.forEach { binding ->
                    val s = binding.source
                    put(JSONObject().put("key", binding.legacyKey).put("source", JSONObject()
                        .put("uri", s.uri).put("source_key", s.sourceKey).put("authority", s.authority)
                        .put("document_id", s.documentId.orEmpty()).put("media_id", s.mediaId ?: -1)
                        .put("name", s.displayName).put("mime", s.mime).put("size", s.size)
                        .put("width", s.width).put("height", s.height).put("orientation", s.orientation)
                        .put("sha256", s.sha256.orEmpty()).put("availability", s.availability.name)))
                } })
            writeAtomicJson(File(directory, "manifest.json"), manifest.toString())
            return open(directory)
        }

        fun open(directory: File): LegacyMigrationArchive {
            val manifest = JSONObject(File(directory, "manifest.json").readText())
            require(manifest.get("version") is Number && manifest.getDouble("version") == 1.0)
            return LegacyMigrationArchive(directory, manifest).also { it.verify() }
        }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}
