package com.nida.nidaplayer

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ImportEntry(
    val id: String = UUID.randomUUID().toString(),
    val position: Int,
    var title: String,
    var artist: String,
    var thumbnail: String = "",
    var source: String = "",
    var state: String = "waiting",
    var output: String = "",
    var error: String = ""
) {
    fun json() = JSONObject().put("id", id).put("position", position).put("title", title)
        .put("artist", artist).put("thumbnail", thumbnail).put("source", source)
        .put("state", state).put("output", output).put("error", error)

    companion object {
        fun from(json: JSONObject) = ImportEntry(json.getString("id"), json.getInt("position"),
            json.getString("title"), json.optString("artist"), json.optString("thumbnail"),
            json.optString("source"), json.optString("state", "waiting"),
            json.optString("output"), json.optString("error"))
    }
}

data class ImportBatch(val id: String = UUID.randomUUID().toString(), var name: String,
    val entries: MutableList<ImportEntry>, val playlistId: String = "") {
    fun json() = JSONObject().put("id", id).put("name", name).put("playlistId", playlistId)
        .put("entries", JSONArray().apply { entries.forEach { put(it.json()) } })

    fun tracks() = entries.sortedBy { it.position }.filter {
        it.state == "done" && it.output.isNotBlank() && File(Uri.parse(it.output).path.orEmpty()).exists()
    }.map { AudioTrack(it.id.hashCode().toLong(), it.title, it.artist, name, it.output) }
}

/** One atomic journal for queue checkpoints and ordered playlists. Never stores an API key. */
class ImportStore(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.filesDir, "imports.json"))

    fun all(): List<ImportBatch> = synchronized(lock) { read() }
    fun get(id: String): ImportBatch? = all().find { it.id == id }

    fun add(batch: ImportBatch) = synchronized(lock) {
        val batches = read().toMutableList()
        batches.add(batch)
        write(batches)
    }

    fun update(id: String, change: (ImportBatch) -> Unit) = synchronized(lock) {
        val batches = read()
        batches.find { it.id == id }?.let(change)
        write(batches)
    }

    private fun read(): List<ImportBatch> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
        val json = JSONArray(file.openRead().bufferedReader().use { it.readText() })
        return (0 until json.length()).map { index ->
            val item = json.getJSONObject(index)
            val entries = item.getJSONArray("entries")
            ImportBatch(item.getString("id"), item.getString("name"),
                (0 until entries.length()).map { ImportEntry.from(entries.getJSONObject(it)) }.toMutableList(),
                item.optString("playlistId"))
        }
    }

    private fun write(batches: List<ImportBatch>) {
        val stream = file.startWrite()
        try {
            stream.write(JSONArray().apply { batches.forEach { put(it.json()) } }.toString().toByteArray())
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    companion object { private val lock = Any() }
}
