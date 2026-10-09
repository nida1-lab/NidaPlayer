package com.nida.nidaplayer

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

class MainActivity : android.app.Activity() {
    private lateinit var root: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var cover: ImageView
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var statusView: TextView
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val tracks = mutableListOf<AudioTrack>()
    private var currentIndex = -1

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) loadLibrary() else statusView.text = "音楽ファイルへのアクセスが許可されていません"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        connectPlayback()
        requestLibraryPermission()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(12))
            setBackgroundColor(0xFFFFFFFF.toInt())
        }

        val header = TextView(this).apply {
            text = "NidaPlayer"
            textSize = 27f
            setTextColor(0xFF111111.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        root.addView(header)

        statusView = TextView(this).apply {
            text = "音楽ライブラリを読み込み中…"
            textSize = 13f
            setTextColor(0xFF555555.toInt())
            setPadding(0, dp(6), 0, dp(10))
        }
        root.addView(statusView)

        cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFFF0F0F0.toInt())
            setImageBitmap(defaultCover())
        }
        root.addView(cover, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(220)
        ))

        titleView = TextView(this).apply {
            text = "曲を選択してください"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(0xFF111111.toInt())
            setPadding(0, dp(14), 0, dp(4))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        root.addView(titleView)

        artistView = TextView(this).apply {
            text = "NidaPlayer"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xFF666666.toInt())
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(artistView)

        val controls = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
        }
        controls.addView(controlButton("⏮") { playRelative(-1) })
        controls.addView(controlButton("▶ / Ⅱ") { togglePlayback() })
        controls.addView(controlButton("⏭") { playRelative(1) })
        root.addView(controls)

        val section = TextView(this).apply {
            text = "曲一覧"
            textSize = 18f
            setTextColor(0xFF111111.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(18), 0, dp(8))
        }
        root.addView(section)

        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)
    }

    private fun controlButton(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(0xFF111111.toInt())
            setOnClickListener { action() }
        }
    }

    private fun connectPlayback() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture?.addListener({
            try {
                controller = controllerFuture?.get()
                controller?.addListener(object : androidx.media3.common.Player.Listener {
                    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                        updateNowPlaying()
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        statusView.text = if (isPlaying) "再生中" else "停止中 / 一時停止中"
                    }
                })
            } catch (e: Exception) {
                statusView.text = "プレイヤーの初期化に失敗しました"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun requestLibraryPermission() {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            loadLibrary()
        } else {
            permissionLauncher.launch(permission)
        }
    }

    private fun loadLibrary() {
        tracks.clear()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        try {
            contentResolver.query(
                collection, projection, selection, null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
            )?.use { cursor: Cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    tracks += AudioTrack(
                        id = id,
                        title = cursor.getString(titleColumn) ?: "不明な曲",
                        artist = cursor.getString(artistColumn)?.takeIf { it != "<unknown>" } ?: "不明なアーティスト",
                        album = cursor.getString(albumColumn) ?: "",
                        uri = ContentUris.withAppendedId(collection, id).toString()
                    )
                }
            }
            renderTracks()
            statusView.text = if (tracks.isEmpty()) {
                "曲が見つかりません。端末に音楽ファイルを保存してください。"
            } else {
                "${tracks.size} 曲を読み込みました。イヤホン未検知時は安全のため消音します。"
            }
        } catch (e: SecurityException) {
            statusView.text = "音楽ライブラリを開けませんでした。権限を確認してください。"
        }
    }

    private fun renderTracks() {
        list.removeAllViews()
        if (tracks.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "音楽ファイルがありません"
                textSize = 15f
                setTextColor(0xFF666666.toInt())
                setPadding(0, dp(12), 0, dp(12))
            })
            return
        }
        tracks.forEachIndexed { index, track ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(if (index % 2 == 0) 0xFFF7F7F7.toInt() else 0xFFFFFFFF.toInt())
                isClickable = true
                isFocusable = true
                setOnClickListener { playTrack(index) }
            }
            row.addView(TextView(this).apply {
                text = track.title
                textSize = 16f
                setTextColor(0xFF111111.toInt())
                maxLines = 1
            })
            row.addView(TextView(this).apply {
                text = track.artist
                textSize = 13f
                setTextColor(0xFF666666.toInt())
                maxLines = 1
            })
            list.addView(row)
        }
    }

    private fun playTrack(index: Int) {
        val player = controller ?: run {
            Toast.makeText(this, "プレイヤーを準備中です", Toast.LENGTH_SHORT).show()
            return
        }
        if (index !in tracks.indices) return
        currentIndex = index
        val track = tracks[index]
        val items = tracks.map { item ->
            MediaItem.Builder()
                .setUri(Uri.parse(item.uri))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(item.title)
                        .setArtist(item.artist)
                        .setAlbumTitle(item.album)
                        .setArtworkUri(findArtworkUri(item))
                        .build()
                )
                .build()
        }
        player.setMediaItems(items, index, 0L)
        player.prepare()
        player.play()
        updateTrackDisplay(track)
        loadEmbeddedCover(track)
    }

    private fun findArtworkUri(track: AudioTrack): Uri? {
        // MediaStore album art URIs are not consistently available on modern Android;
        // the embedded cover is loaded directly for the in-app player.
        return null
    }

    private fun updateNowPlaying() {
        val player = controller ?: return
        val index = player.currentMediaItemIndex
        if (index in tracks.indices) {
            currentIndex = index
            updateTrackDisplay(tracks[index])
            loadEmbeddedCover(tracks[index])
        }
    }

    private fun updateTrackDisplay(track: AudioTrack) {
        titleView.text = track.title
        artistView.text = track.artist
    }

    private fun loadEmbeddedCover(track: AudioTrack) {
        Thread {
            var bitmap: Bitmap? = null
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this, Uri.parse(track.uri))
                val bytes = retriever.embeddedPicture
                if (bytes != null) bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (_: Exception) {
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
            val result = bitmap ?: defaultCover()
            runOnUiThread {
                if (!isFinishing) cover.setImageBitmap(result)
            }
        }.start()
    }

    private fun defaultCover(): Bitmap {
        val size = 512
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(0xFF111827.toInt())
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF34D399.toInt()
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = 150f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        canvas.drawText("N", size / 2f, size / 2f + 50f, paint)
        return bitmap
    }

    private fun togglePlayback() {
        val player = controller ?: return
        if (player.isPlaying) player.pause() else if (player.mediaItemCount > 0) player.play()
    }

    private fun playRelative(delta: Int) {
        val player = controller ?: return
        if (tracks.isEmpty()) return
        if (player.mediaItemCount == 0) {
            playTrack(0)
            return
        }
        val target = (player.currentMediaItemIndex + delta + tracks.size) % tracks.size
        player.seekTo(target, 0L)
        player.play()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onDestroy()
    }
}
