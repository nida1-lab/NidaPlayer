package com.nida.nidaplayer

import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.media.MediaFormat
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sin

@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class AudioPipelineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    private fun wavBytes(): ByteArray {
        val sampleCount = 44100
        val wav = ByteBuffer.allocate(44 + sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + sampleCount * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(44100).putInt(88200).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(sampleCount * 2)
        repeat(sampleCount) { wav.putShort((sin(it * 2 * Math.PI * 440 / 44100) * 7000).toInt().toShort()) }
        return wav.array()
    }

    @Test fun realWavToAacMetadataLyricsAndOrderedJournal() {
        val source = File(context.cacheDir, "test-input.wav")
        val output = File(context.cacheDir, "test-output.m4a").apply { delete() }
        source.writeBytes(wavBytes())
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        var transformer: Transformer? = null
        instrumentation.runOnMainSync {
            transformer = Transformer.Builder(context).setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) { done.countDown() }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        failure = exportException; done.countDown()
                    }
                }).build()
            transformer!!.start(EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source))).setRemoveVideo(true).build(), output.path)
        }
        try {
            assertTrue("Conversion timed out", done.await(60, TimeUnit.SECONDS))
            failure?.let { throw AssertionError("AAC export failed", it) }
            val cover = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(-65536) }
            val jpeg = ByteArrayOutputStream().apply { cover.compress(Bitmap.CompressFormat.JPEG, 90, this) }.toByteArray()
            M4aMetadata.write(output, "テスト曲", "テスト歌手", "曲順テスト", jpeg)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(output.path)
                assertEquals(1, extractor.trackCount)
                assertEquals("audio/mp4a-latm", extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME))
            } finally { extractor.release() }
            val metadata = MediaMetadataRetriever()
            try {
                metadata.setDataSource(output.path)
                assertEquals("テスト曲", metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE))
                assertEquals("テスト歌手", metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST))
                assertEquals("曲順テスト", metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM))
                assertNotNull(metadata.embeddedPicture)
            } finally { metadata.release() }
            val uri = Uri.fromFile(output).toString()
            LyricsStore(context).save(uri, "[00:00.10]一\n[00:00.50]二")
            assertEquals(1, LyricsStore(context).load(uri).indexAt(600))
            val batch = ImportBatch(name = "test", entries = mutableListOf(
                ImportEntry(position = 2, title = "後", artist = "", state = "done", output = uri),
                ImportEntry(position = 0, title = "先", artist = "", state = "done", output = uri),
                ImportEntry(position = 1, title = "失敗", artist = "", state = "failed")))
            ImportStore(context).add(batch)
            assertEquals(listOf("先", "後"), ImportStore(context).get(batch.id)!!.tracks().map { it.title })
        } finally {
            instrumentation.runOnMainSync { transformer?.cancel() }
            source.delete(); output.delete()
        }
    }

    @Test fun foregroundQueueContinuesAfterFailureAndFloatingWindowCanClose() {
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.READ_MEDIA_AUDIO").close()
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS").close()
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "NidaPlayer-pipeline-test.wav")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/NidaPlayerTests")
        })!!
        resolver.openOutputStream(uri)!!.use { it.write(wavBytes()) }
        val batch = ImportBatch(name = "service test", entries = mutableListOf(
            ImportEntry(position = 0, title = "missing", artist = "", source = "content://invalid.provider/no-file"),
            ImportEntry(position = 1, title = "valid", artist = "test", source = uri.toString())))
        ImportStore(context).add(batch)
        val activity = instrumentation.startActivitySync(Intent(context, ImportActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("batch_id", batch.id))
        try {
            instrumentation.runOnMainSync {
                ContextCompat.startForegroundService(context, Intent(context, AudioImportService::class.java)
                    .putExtra("batch_id", batch.id))
            }
            val deadline = System.currentTimeMillis() + 60_000
            var saved: ImportBatch
            do {
                Thread.sleep(200)
                saved = ImportStore(context).get(batch.id)!!
            } while (saved.entries.any { it.state !in setOf("done", "failed") } && System.currentTimeMillis() < deadline)
            assertEquals(listOf("failed", "done"), saved.entries.map { it.state })
            assertEquals(listOf("valid"), saved.tracks().map { it.title })
            instrumentation.uiAutomation.executeShellCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
                .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
            assertTrue(Settings.canDrawOverlays(context))
            instrumentation.runOnMainSync {
                val player = androidx.media3.exoplayer.ExoPlayer.Builder(context).build()
                player.setMediaItem(MediaItem.Builder().setMediaId(saved.entries[1].output)
                    .setUri(saved.entries[1].output).build())
                val overlay = LyricsOverlay(context, player) {}
                try { assertTrue(overlay.toggle()); assertTrue(overlay.visible); overlay.hide(); assertFalse(overlay.visible) }
                finally { overlay.hide(); player.release() }
            }
            saved.entries.filter { it.output.isNotEmpty() }.forEach { File(Uri.parse(it.output).path!!).delete() }
        } finally {
            instrumentation.runOnMainSync { activity.finish(); context.stopService(Intent(context, AudioImportService::class.java)) }
            resolver.delete(uri, null, null)
        }
    }
}
