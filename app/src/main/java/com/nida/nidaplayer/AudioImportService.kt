package com.nida.nidaplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

@OptIn(UnstableApi::class)
class AudioImportService : Service() {
    private lateinit var store: ImportStore
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var transformer: Transformer? = null
    private var batchId = ""
    private var pending = ArrayDeque<String>()
    private var current: ImportEntry? = null
    private var temp: File? = null
    private var generation = 0
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val CHANNEL = "audio_import"
        const val NOTIFICATION_ID = 2002
        const val CANCEL = "cancel_import"
        @Volatile var runningBatch: String? = null
            private set
        @Volatile var progress: Int = -1
            private set
    }

    override fun onCreate() {
        super.onCreate()
        store = ImportStore(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "音源の取り込み", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) { cancel("取り込みを中止しました。続きから再開できます"); return START_NOT_STICKY }
        if (runningBatch != null) return START_NOT_STICKY
        batchId = intent?.getStringExtra("batch_id").orEmpty()
        val batch = store.get(batchId)
        if (batch == null) { stopSelf(); return START_NOT_STICKY }
        runningBatch = batchId
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            else if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification("取り込みを準備中"), type)
            else startForeground(NOTIFICATION_ID, notification("取り込みを準備中"))
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "NidaPlayer:AudioImport").apply { acquire(6 * 60 * 60 * 1000L) }
            pending = ArrayDeque(batch.entries.sortedBy { it.position }.filter {
                it.source.isNotBlank() && it.state !in setOf("done", "skipped")
            }.map { it.id })
            next()
        } catch (_: Exception) { cancel("バックグラウンド処理を開始できませんでした。アプリを開いて再試行してください") }
        return START_NOT_STICKY
    }

    private val updateProgress = object : Runnable {
        override fun run() {
            val active = transformer ?: return
            val holder = ProgressHolder()
            progress = if (active.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else -1
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,
                notification("${current?.title.orEmpty()} · ${if (progress < 0) "処理中" else "$progress%"}"))
            handler.postDelayed(this, 700L)
        }
    }

    private fun next() {
        handler.removeCallbacks(updateProgress)
        transformer = null
        current = null
        temp = null
        progress = -1
        val entryId = pending.removeFirstOrNull()
        if (entryId == null) { finishBatch(); return }
        val batch = store.get(batchId) ?: run { finishBatch(); return }
        val entry = batch.entries.find { it.id == entryId } ?: run { next(); return }
        current = entry
        store.update(batchId) { it.entries.find { row -> row.id == entry.id }?.apply { state = "processing"; error = "" } }
        val directory = File(filesDir, "audio_imports").apply { mkdirs() }
        val output = File(directory, "${entry.id}.part.m4a")
        output.delete()
        temp = output
        val token = ++generation
        try {
            require(directory.usableSpace > 32L * 1024 * 1024) { "空き容量が不足しています（最低32MB）" }
            // Only user-selected document providers may supply audio. A YouTube URL
            // or arbitrary remote source can never reach the media pipeline.
            require(Uri.parse(entry.source).scheme == "content") { "音源ファイルを選択し直してください" }
            transformer = Transformer.Builder(this).setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (token != generation) return
                        handler.removeCallbacks(updateProgress)
                        transformer = null
                        if (exportResult.audioMimeType != MimeTypes.AUDIO_AAC) {
                            fail(entry, "この音源をAACに変換できませんでした"); return
                        }
                        io.execute {
                            val result = runCatching {
                                val cover = loadCover(entry)
                                M4aMetadata.write(output, entry.title, entry.artist, batch.name, cover)
                            }
                            handler.post {
                                if (token != generation) { output.delete(); return@post }
                                result.fold(onSuccess = {
                                    val published = File(directory, "${entry.id}.m4a")
                                    if (!output.renameTo(published)) {
                                        fail(entry, "変換済みファイルを保存できませんでした")
                                    } else {
                                        store.update(batchId) { saved -> saved.entries.find { it.id == entry.id }?.apply {
                                            state = "done"; this.output = Uri.fromFile(published).toString(); error = ""
                                        } }
                                        next()
                                    }
                                }, onFailure = { fail(entry, "曲情報の保存に失敗しました。空き容量を確認してください") })
                            }
                        }
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (token == generation) fail(entry,
                            "変換に失敗しました（${exportException.errorCodeName}）。ファイル形式・アクセス許可・空き容量を確認してください")
                    }
                }).build()
            transformer!!.start(EditedMediaItem.Builder(MediaItem.fromUri(entry.source)).setRemoveVideo(true).build(), output.path)
            handler.post(updateProgress)
        } catch (error: Exception) { fail(entry, error.message?.takeIf { !it.contains("://") } ?: "この音源を開けませんでした") }
    }

    private fun fail(entry: ImportEntry, message: String) {
        handler.removeCallbacks(updateProgress)
        runCatching { transformer?.cancel() }
        transformer = null
        temp?.delete()
        store.update(batchId) { it.entries.find { row -> row.id == entry.id }?.apply { state = "failed"; error = message } }
        handler.post { next() }
    }

    private fun loadCover(entry: ImportEntry): ByteArray? {
        // Prefer the source file's embedded cover; otherwise use the playlist thumbnail.
        val embedded = runCatching {
            val retriever = MediaMetadataRetriever()
            try { retriever.setDataSource(this, Uri.parse(entry.source)); retriever.embeddedPicture }
            finally { retriever.release() }
        }.getOrNull()
        val bytes = embedded ?: runCatching {
            val url = URL(entry.thumbnail)
            require(url.protocol == "https" && url.host in setOf("i.ytimg.com", "img.youtube.com"))
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 8_000; connection.readTimeout = 8_000; connection.instanceFollowRedirects = false
            try {
                require(connection.responseCode == 200)
                connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 2_000_000)
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            } finally { connection.disconnect() }
        }.getOrNull() ?: return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 700 || bounds.outHeight / sample > 700) sample *= 2
            val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            ByteArrayOutputStream().use { out ->
                image.compress(Bitmap.CompressFormat.JPEG, 88, out); image.recycle(); out.toByteArray()
            }
        }.getOrNull()
    }

    private fun notification(text: String, ongoing: Boolean = true): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, ImportActivity::class.java)
            .putExtra("batch_id", batchId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("NidaPlayer · M4A取り込み").setContentText(text)
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(ongoing)
        if (ongoing) builder.setProgress(100, progress.coerceAtLeast(0), progress < 0).addAction(
            android.R.drawable.ic_menu_close_clear_cancel, "中止", PendingIntent.getService(this, 1,
                Intent(this, AudioImportService::class.java).setAction(CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        return builder.build()
    }

    private fun finishBatch() {
        val batch = store.get(batchId)
        val done = batch?.entries?.count { it.state == "done" } ?: 0
        val failed = batch?.entries?.count { it.state == "failed" } ?: 0
        finishService("取り込み終了 · ${done}曲完了 / ${failed}曲失敗")
    }

    private fun cancel(message: String) {
        generation++
        runCatching { transformer?.cancel() }
        transformer = null
        current?.let { entry -> store.update(batchId) { batch -> batch.entries.find { it.id == entry.id }?.apply {
            if (state == "processing") { state = "waiting"; error = message }
        } } }
        finishService(message)
    }

    private fun finishService(message: String) {
        handler.removeCallbacksAndMessages(null)
        runningBatch = null
        progress = -1
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message, false))
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        cancel("Androidの処理時間上限に達しました。アプリを開いて続きから再開してください")
    }

    override fun onDestroy() {
        generation++
        handler.removeCallbacksAndMessages(null)
        runCatching { transformer?.cancel() }
        if (wakeLock?.isHeld == true) wakeLock?.release()
        runningBatch = null
        io.shutdownNow()
        super.onDestroy()
    }
}
