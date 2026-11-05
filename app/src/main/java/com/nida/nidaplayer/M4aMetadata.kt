package com.nida.nidaplayer

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/** iTunes-compatible title, artist, album and cover tags in an AAC/MP4 container. */
object M4aMetadata {
    private fun box(type: String, content: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(content.size + 8)
            out.write(type.toByteArray(Charsets.ISO_8859_1))
            out.write(content)
        }
        return bytes.toByteArray()
    }

    private fun tag(type: String, value: ByteArray, kind: Int = 1): ByteArray =
        box(type, box("data", ByteBuffer.allocate(8 + value.size)
            .putInt(kind).putInt(0).put(value).array()))

    fun write(file: File, title: String, artist: String, album: String, jpeg: ByteArray?) {
        require(jpeg == null || jpeg.size <= 2_000_000)
        val tags = ByteArrayOutputStream().apply {
            write(tag("©nam", title.toByteArray(Charsets.UTF_8)))
            write(tag("©ART", artist.toByteArray(Charsets.UTF_8)))
            write(tag("©alb", album.toByteArray(Charsets.UTF_8)))
            if (jpeg != null) write(tag("covr", jpeg, 13))
        }.toByteArray()
        val handler = ByteBuffer.allocate(25).putInt(0).putInt(0)
            .put("mdir".toByteArray()).put(ByteArray(13)).array()
        val metadata = box("udta", box("meta", ByteArray(4) + box("hdlr", handler) + box("ilst", tags)))
        RandomAccessFile(file, "rw").use { data ->
            val length = data.length()
            var position = 0L
            var moovPosition = -1L
            var moovSize = 0L
            var moovHeader = 8
            while (position + 8 <= length) {
                data.seek(position)
                var size = data.readInt().toLong() and 0xFFFFFFFFL
                val type = ByteArray(4).also { data.readFully(it) }.toString(Charsets.ISO_8859_1)
                var header = 8
                if (size == 1L) { size = data.readLong(); header = 16 }
                else if (size == 0L) size = length - position
                require(size >= header && size <= length - position) { "M4Aコンテナが破損しています" }
                if (type == "moov") { moovPosition = position; moovSize = size; moovHeader = header; break }
                position += size
            }
            require(moovPosition >= 0 && moovSize <= 64_000_000L) { "M4Aヘッダーを確認できませんでした" }
            data.seek(moovPosition + moovHeader)
            val payload = ByteArray((moovSize - moovHeader).toInt()).also { data.readFully(it) }
            val children = ByteArrayOutputStream()
            var offset = 0
            while (offset + 8 <= payload.size) {
                val size = ByteBuffer.wrap(payload, offset, 4).int
                require(size >= 8 && size <= payload.size - offset) { "M4Aヘッダーが破損しています" }
                val type = String(payload, offset + 4, 4, Charsets.ISO_8859_1)
                if (type != "udta") children.write(payload, offset, size)
                offset += size
            }
            require(offset == payload.size)
            children.write(metadata)
            // Append a replacement moov and retire the old one as free space. Existing
            // mdat/stco/co64 offsets remain valid even when the original moov preceded mdat.
            data.seek(length)
            data.write(box("moov", children.toByteArray()))
            data.fd.sync()
            data.seek(moovPosition + 4)
            data.write("free".toByteArray(Charsets.ISO_8859_1))
            data.fd.sync()
        }
    }
}
