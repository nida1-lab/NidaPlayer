package com.nida.nidaplayer

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class LyricsStore(context: Context) {
    private val app = context.applicationContext
    private val directory = File(app.filesDir, "lyrics").apply { mkdirs() }
    private fun key(uri: String) = MessageDigest.getInstance("SHA-256")
        .digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }

    fun load(uri: String): LrcDocument = runCatching {
        LrcParser.parse(File(directory, "${key(uri)}.lrc").readText())
    }.getOrDefault(LrcDocument(emptyList()))

    fun save(uri: String, source: String): LrcDocument {
        require(source.length <= 1_000_000) { "歌詞ファイルが大きすぎます（最大1MB）" }
        val parsed = LrcParser.parse(source)
        require(parsed.lines.isNotEmpty()) { "時刻付きのLRC歌詞を選択してください" }
        val file = AtomicFile(File(directory, "${key(uri)}.lrc"))
        val stream = file.startWrite()
        try {
            stream.write(source.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
        app.getSharedPreferences("lyrics", Context.MODE_PRIVATE).edit()
            .putLong("revision", System.currentTimeMillis()).apply()
        return parsed
    }

    fun importFile(trackUri: String, lrcUri: Uri): LrcDocument {
        val bytes = app.contentResolver.openInputStream(lrcUri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 1_000_000) { "歌詞ファイルが大きすぎます（最大1MB）" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("歌詞を開けませんでした")
        require(bytes.size <= 1_000_000) { "歌詞ファイルが大きすぎます（最大1MB）" }
        return save(trackUri, bytes.toString(Charsets.UTF_8))
    }

    fun delay(uri: String): Long = app.getSharedPreferences("lyrics", Context.MODE_PRIVATE)
        .getLong("delay_${key(uri)}", 0L)

    fun setDelay(uri: String, delay: Long) {
        app.getSharedPreferences("lyrics", Context.MODE_PRIVATE).edit()
            .putLong("delay_${key(uri)}", delay.coerceIn(-60_000L, 60_000L)).apply()
    }
}
