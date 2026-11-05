package com.nida.nidaplayer

import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class M4aMetadataTest {
    private fun box(type: String, data: ByteArray) = ByteBuffer.allocate(8 + data.size)
        .putInt(8 + data.size).put(type.toByteArray()).put(data).array()

    @Test fun keepsMediaOffsetsAndWritesUtf8TagsForBothAtomOrders() {
        for (moovFirst in listOf(true, false)) {
            val media = box("mdat", ByteArray(50) { it.toByte() })
            val movie = box("moov", box("mvhd", ByteArray(20)))
            val original = if (moovFirst) movie + media else media + movie
            val file = File.createTempFile("metadata", ".m4a")
            try {
                file.writeBytes(original)
                M4aMetadata.write(file, "日本語の曲名", "歌手", "プレイリスト", null)
                val result = file.readBytes()
                val mediaOffset = if (moovFirst) movie.size else 0
                assertArrayEquals(media, result.copyOfRange(mediaOffset, mediaOffset + media.size))
                val oldMoov = if (moovFirst) 0 else media.size
                assertEquals("free", String(result, oldMoov + 4, 4))
                assertEquals("moov", String(result, original.size + 4, 4))
                assertTrue(result.toString(Charsets.UTF_8).contains("日本語の曲名"))
                assertTrue(result.toString(Charsets.ISO_8859_1).contains("©ART"))
            } finally { file.delete() }
        }
    }

    @Test fun rejectsTruncatedOrInvalidContainer() {
        val file = File.createTempFile("invalid", ".m4a")
        try {
            file.writeBytes(ByteBuffer.allocate(8).putInt(10000).put("moov".toByteArray()).array())
            assertTrue(runCatching { M4aMetadata.write(file, "", "", "", null) }.isFailure)
        } finally { file.delete() }
    }
}
