package com.nida.nidaplayer

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * NidaPlayer's phone-first music UI.
 * Design direction: dark music-discovery interface with bold artwork, quick actions,
 * collection browsing, a persistent mini-player and a dedicated now-playing screen.
 */
class MainActivity : ComponentActivity() {
    private enum class Page { HOME, EXPLORE, LIBRARY, COLLECTION }
    private enum class LibraryTab { SONGS, ALBUMS, ARTISTS }

    private val backgroundColor = 0xFF101012.toInt()
    private val surfaceColor = 0xFF1D1D21.toInt()
    private val elevatedColor = 0xFF29292F.toInt()
    private val accentColor = 0xFFFF375F.toInt()
    private val primaryText = 0xFFF7F7F8.toInt()
    private val secondaryText = 0xFFB0B0B8.toInt()
    private val mutedText = 0xFF777780.toInt()

    private lateinit var root: LinearLayout
    private var page = Page.HOME
    private var libraryTab = LibraryTab.SONGS
    private var fullPlayerVisible = false
    private var searchQuery = ""
    private var infoMessage = "端末内の音楽を読み込みます"
    private var collectionTitle = ""
    private var collectionTracks: List<AudioTrack> = emptyList()

    private val tracks = mutableListOf<AudioTrack>()
    private var activeQueue: List<AudioTrack> = emptyList()
    private var activeTrack: AudioTrack? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val recentUris = mutableListOf<String>()
    private val artworkCache = mutableMapOf<String, Bitmap>()
    private var activeArtwork: Bitmap? = null
    private val artworkViews = mutableListOf<ImageView>()

    private var miniPlayButton: TextView? = null
    private var fullPlayButton: TextView? = null
    private var fullSeekBar: SeekBar? = null
    private var fullPositionLabel: TextView? = null
    private var fullDurationLabel: TextView? = null
    private var searchInput: EditText? = null
    private var ignoreSeekChange = false

    private val handler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgressUi()
            if (fullPlayerVisible) handler.postDelayed(this, 500L)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            loadLibrary()
        } else {
            infoMessage = "音楽ファイルへのアクセスが必要です。設定から許可してください。"
            renderShell()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor
        WindowCompat.setDecorFitsSystemWindows(window, false)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor)
        }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    fullPlayerVisible -> {
                        fullPlayerVisible = false
                        renderShell()
                    }
                    page == Page.COLLECTION -> {
                        page = Page.LIBRARY
                        renderShell()
                    }
                    page != Page.HOME -> {
                        page = Page.HOME
                        renderShell()
                    }
                    else -> finish()
                }
            }
        })

        loadRecentHistory()
        renderShell()
        connectPlayback()
        requestLibraryPermission()
    }

    private fun connectPlayback() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture?.addListener({
            try {
                controller = controllerFuture?.get()
                controller?.addListener(object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        val player = controller ?: return
                        val item = activeQueue.getOrNull(player.currentMediaItemIndex)
                        if (item != null) {
                            activeTrack = item
                            activeArtwork = artworkCache[item.uri]
                            renderShell()
                            loadEmbeddedCover(item)
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        updatePlayButtons()
                        if (fullPlayerVisible) {
                            handler.removeCallbacks(progressRunnable)
                            handler.post(progressRunnable)
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        updatePlayButtons()
                        updateProgressUi()
                    }
                })
                val player = controller
                if (player != null && player.currentMediaItemIndex in activeQueue.indices) {
                    activeTrack = activeQueue[player.currentMediaItemIndex]
                    renderShell()
                    activeTrack?.let { loadEmbeddedCover(it) }
                }
            } catch (_: Exception) {
                infoMessage = "プレイヤーを初期化できませんでした"
                renderShell()
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
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val loaded = mutableListOf<AudioTrack>()
        try {
            contentResolver.query(
                collection,
                projection,
                selection,
                null,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { cursor: Cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val title = cursor.getString(titleColumn)?.takeIf { it.isNotBlank() } ?: "タイトル不明"
                    val rawArtist = cursor.getString(artistColumn)
                    val artist = rawArtist?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "アーティスト不明"
                    val album = cursor.getString(albumColumn)?.takeIf { it.isNotBlank() } ?: "アルバム不明"
                    loaded += AudioTrack(
                        id = id,
                        title = title,
                        artist = artist,
                        album = album,
                        uri = ContentUris.withAppendedId(collection, id).toString()
                    )
                }
            }
            tracks.clear()
            tracks.addAll(loaded)
            if (activeQueue.isEmpty()) activeQueue = tracks.toList()
            infoMessage = if (tracks.isEmpty()) {
                "曲が見つかりません。端末に音楽ファイルを保存してください。"
            } else {
                "端末内の音楽 · ${tracks.size}曲"
            }
            if (activeTrack == null && activeQueue.isEmpty()) activeQueue = tracks.toList()
            renderShell()
        } catch (_: SecurityException) {
            infoMessage = "音楽ライブラリを開けませんでした。アクセス権限を確認してください。"
            renderShell()
        } catch (_: Exception) {
            infoMessage = "音楽ライブラリを読み込めませんでした"
            renderShell()
        }
    }

    private fun renderShell() {
        root.removeAllViews()
        artworkViews.clear()
        miniPlayButton = null
        fullPlayButton = null
        fullSeekBar = null
        fullPositionLabel = null
        fullDurationLabel = null
        searchInput = null

        if (fullPlayerVisible) {
            renderFullPlayer(root)
            return
        }

        renderTopBar(root)
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(24))
        }
        scroll.addView(content, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        when (page) {
            Page.HOME -> renderHome(content)
            Page.EXPLORE -> renderExplore(content)
            Page.LIBRARY -> renderLibrary(content)
            Page.COLLECTION -> renderCollection(content)
        }

        if (activeTrack != null || (controller?.mediaItemCount ?: 0) > 0) {
            renderMiniPlayer(root)
        }
        renderBottomNavigation(root)
    }

    private fun renderTopBar(parent: LinearLayout) {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(8), dp(14), dp(8))
        }
        val logo = TextView(this).apply {
            text = "N"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(primaryText)
            background = rounded(accentColor, 50f)
        }
        bar.addView(logo, LinearLayout.LayoutParams(dp(38), dp(38)))
        val title = TextView(this).apply {
            text = "NidaPlayer"
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(primaryText)
            setPadding(dp(10), 0, 0, 0)
        }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(iconButton("⌕", "音楽を検索") {
            page = Page.EXPLORE
            renderShell()
            searchInput?.requestFocus()
        })
        bar.addView(iconButton("⟳", "ライブラリを再読み込み") {
            requestLibraryPermission()
        })
        parent.addView(bar)
    }

    private fun renderHome(parent: LinearLayout) {
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(20))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF58202E.toInt(), 0xFF2C171F.toInt(), 0xFF201D23.toInt())
            ).apply { cornerRadius = dp(24).toFloat() }
        }
        hero.addView(makeText("YOUR MUSIC. YOUR SPACE.", 11f, 0xFFFF9CAE.toInt(), true))
        addGap(hero, 10)
        hero.addView(makeText("好きな曲を、\n好きな順番で。", 29f, primaryText, true))
        addGap(hero, 8)
        hero.addView(makeText(
            "端末の音楽を、ひとつのプレイヤーに。",
            14f, 0xFFE2CDD2.toInt(), false
        ))
        addGap(hero, 18)
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(actionButton("▶  シャッフル再生", true) {
            playAll(tracks.toList(), shuffle = true)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(actionButton("曲を探す", false) {
            page = Page.LIBRARY
            libraryTab = LibraryTab.SONGS
            renderShell()
        }, LinearLayout.LayoutParams(dp(106), dp(48)).apply {
            leftMargin = dp(8)
        })
        hero.addView(actions)
        parent.addView(hero, fullWidthWrap())
        addGap(parent, 26)

        val recent = recentTracks()
        sectionHeader(parent, if (recent.isEmpty()) "端末の音楽" else "もう一度聴く",
            "すべて表示") {
            page = Page.LIBRARY
            libraryTab = LibraryTab.SONGS
            renderShell()
        }
        if (tracks.isEmpty()) {
            emptyState(parent, "まだ曲がありません", "端末に保存した音楽ファイルがここに表示されます。", "再読み込み") {
                requestLibraryPermission()
            }
        } else {
            renderHorizontalTrackCards(parent, (recent.ifEmpty { tracks.toList() }).take(12))
        }
        addGap(parent, 25)

        sectionHeader(parent, "アルバム", "すべて表示") {
            page = Page.LIBRARY
            libraryTab = LibraryTab.ALBUMS
            renderShell()
        }
        val albums = tracks.groupBy { it.album.ifBlank { "アルバム不明" } }
            .entries.sortedBy { it.key.lowercase(Locale.getDefault()) }
        if (albums.isEmpty()) {
            parent.addView(makeText("曲を読み込むとアルバムが並びます。", 14f, secondaryText))
        } else {
            renderHorizontalCollectionCards(parent, albums.take(12).map { it.key to it.value })
        }
        addGap(parent, 25)

        sectionHeader(parent, "最近追加された曲", "すべて表示") {
            page = Page.LIBRARY
            libraryTab = LibraryTab.SONGS
            renderShell()
        }
        renderTrackRows(parent, tracks.take(8), tracks)
        if (infoMessage.isNotBlank()) {
            addGap(parent, 14)
            parent.addView(makeText(infoMessage, 12f, mutedText))
        }
    }

    private fun renderExplore(parent: LinearLayout) {
        parent.addView(makeText("探索", 30f, primaryText, true))
        addGap(parent, 6)
        parent.addView(makeText("端末にある音楽を検索", 14f, secondaryText))
        addGap(parent, 16)

        val input = EditText(this).apply {
            setSingleLine(true)
            hint = "曲名、アーティスト、アルバム"
            setHintTextColor(mutedText)
            setTextColor(primaryText)
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(16), 0, dp(16), 0)
            background = rounded(surfaceColor, 16f, 0xFF414149.toInt(), dp(1))
            setCompoundDrawablesWithIntrinsicBounds(null, null, null, null)
            setText(searchQuery)
        }
        searchInput = input
        parent.addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(54)
        ))
        addGap(parent, 22)
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        parent.addView(results)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString().orEmpty()
                renderSearchResults(results)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        renderSearchResults(results)
    }

    private fun renderSearchResults(parent: LinearLayout) {
        parent.removeAllViews()
        val query = searchQuery.trim()
        if (query.isEmpty()) {
            sectionHeader(parent, "すべての曲", "${tracks.size}曲")
            if (tracks.isEmpty()) {
                emptyState(parent, "検索できる曲がありません", "音楽ファイルを端末に保存してください。", "再読み込み") {
                    requestLibraryPermission()
                }
            } else {
                renderTrackRows(parent, tracks.take(30), tracks)
            }
            return
        }
        val filtered = tracks.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.artist.contains(query, ignoreCase = true) ||
                it.album.contains(query, ignoreCase = true)
        }
        sectionHeader(parent, "検索結果", "${filtered.size}件")
        if (filtered.isEmpty()) {
            emptyState(parent, "見つかりませんでした", "別のキーワードでもう一度検索してください。")
        } else {
            renderTrackRows(parent, filtered, tracks)
        }
    }

    private fun renderLibrary(parent: LinearLayout) {
        parent.addView(makeText("ライブラリ", 30f, primaryText, true))
        addGap(parent, 6)
        parent.addView(makeText("この端末にある音楽", 14f, secondaryText))
        addGap(parent, 18)

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabs.addView(tabButton("曲", libraryTab == LibraryTab.SONGS) {
            libraryTab = LibraryTab.SONGS
            renderShell()
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        tabs.addView(tabButton("アルバム", libraryTab == LibraryTab.ALBUMS) {
            libraryTab = LibraryTab.ALBUMS
            renderShell()
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(6) })
        tabs.addView(tabButton("アーティスト", libraryTab == LibraryTab.ARTISTS) {
            libraryTab = LibraryTab.ARTISTS
            renderShell()
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(6) })
        parent.addView(tabs)
        addGap(parent, 20)

        when (libraryTab) {
            LibraryTab.SONGS -> {
                val header = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                header.addView(makeText("${tracks.size}曲", 13f, secondaryText), LinearLayout.LayoutParams(0, dp(42), 1f))
                header.addView(actionButton("▶ シャッフル", false) { playAll(tracks.toList(), true) },
                    LinearLayout.LayoutParams(dp(128), dp(40)))
                parent.addView(header)
                addGap(parent, 8)
                if (tracks.isEmpty()) emptyState(parent, "曲がありません", "右上の再読み込みから音楽ライブラリを確認できます。", "再読み込み") {
                    requestLibraryPermission()
                } else renderTrackRows(parent, tracks, tracks)
            }
            LibraryTab.ALBUMS -> {
                val groups = tracks.groupBy { it.album.ifBlank { "アルバム不明" } }
                    .entries.sortedBy { it.key.lowercase(Locale.getDefault()) }
                if (groups.isEmpty()) emptyState(parent, "アルバムがありません", "音楽ファイルを追加するとここに表示されます。")
                else groups.forEach { (name, items) -> renderCollectionRow(parent, name, items, "アルバム") }
            }
            LibraryTab.ARTISTS -> {
                val groups = tracks.groupBy { it.artist.ifBlank { "アーティスト不明" } }
                    .entries.sortedBy { it.key.lowercase(Locale.getDefault()) }
                if (groups.isEmpty()) emptyState(parent, "アーティストがありません", "音楽ファイルを追加するとここに表示されます。")
                else groups.forEach { (name, items) -> renderCollectionRow(parent, name, items, "アーティスト") }
            }
        }
    }

    private fun renderCollection(parent: LinearLayout) {
        val items = collectionTracks
        if (items.isEmpty()) {
            emptyState(parent, "曲がありません", "ライブラリに戻って別の項目を選択してください。", "ライブラリに戻る") {
                page = Page.LIBRARY
                renderShell()
            }
            return
        }

        val cover = artworkView("collection:${collectionTitle}", dp(230), dp(230)).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        cover.contentDescription = collectionTitle
        parent.addView(cover, LinearLayout.LayoutParams(dp(230), dp(230)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(20)
        })
        parent.addView(makeText(collectionTitle, 27f, primaryText, true).apply {
            gravity = Gravity.CENTER
        })
        addGap(parent, 6)
        parent.addView(makeText("${items.size}曲 · ${if (items.first().album == collectionTitle) "アルバム" else "コレクション"}", 13f, secondaryText).apply {
            gravity = Gravity.CENTER
        })
        addGap(parent, 18)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(actionButton("▶  再生", true) { playAll(items) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        buttons.addView(actionButton("⤨  シャッフル", false) { playAll(items, true) },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(8) })
        parent.addView(buttons)
        addGap(parent, 20)
        renderTrackRows(parent, items, items)
    }

    private fun renderMiniPlayer(parent: LinearLayout) {
        val track = activeTrack ?: controller?.currentMediaItem?.let {
            AudioTrack(
                id = 0L,
                title = it.mediaMetadata.title?.toString() ?: "再生中の曲",
                artist = it.mediaMetadata.artist?.toString() ?: "NidaPlayer",
                album = it.mediaMetadata.albumTitle?.toString() ?: "",
                uri = it.localConfiguration?.uri?.toString().orEmpty()
            )
        } ?: return
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(8), dp(8))
            background = rounded(0xFF272327.toInt(), 16f, 0xFF40353A.toInt(), dp(1))
            setOnClickListener {
                fullPlayerVisible = true
                renderShell()
            }
        }
        container.addView(artworkView(track.uri, dp(48), dp(48)), LinearLayout.LayoutParams(dp(48), dp(48)))
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(4), 0)
        }
        labels.addView(makeText(track.title, 14f, primaryText, true).apply { maxLines = 1 })
        labels.addView(makeText(track.artist, 12f, secondaryText).apply { maxLines = 1 })
        container.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        miniPlayButton = iconButton(if (controller?.isPlaying == true) "Ⅱ" else "▶", "再生 / 一時停止") {
            togglePlayback()
        }
        container.addView(miniPlayButton)
        container.addView(iconButton("⏭", "次の曲") { playRelative(1) })
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(6))
        }
        outer.addView(container)
        parent.addView(outer)
    }

    private fun renderBottomNavigation(parent: LinearLayout) {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(4))
            setBackgroundColor(backgroundColor)
        }
        nav.addView(navItem("⌂", "ホーム", page == Page.HOME) {
            page = Page.HOME
            renderShell()
        }, LinearLayout.LayoutParams(0, dp(56), 1f))
        nav.addView(navItem("⌕", "探索", page == Page.EXPLORE) {
            page = Page.EXPLORE
            renderShell()
        }, LinearLayout.LayoutParams(0, dp(56), 1f))
        nav.addView(navItem("▤", "ライブラリ", page == Page.LIBRARY || page == Page.COLLECTION) {
            page = Page.LIBRARY
            renderShell()
        }, LinearLayout.LayoutParams(0, dp(56), 1f))
        parent.addView(nav)
    }

    private fun renderFullPlayer(parent: LinearLayout) {
        val track = activeTrack ?: controller?.currentMediaItem?.let {
            AudioTrack(0L, it.mediaMetadata.title?.toString() ?: "曲を選択してください",
                it.mediaMetadata.artist?.toString() ?: "NidaPlayer",
                it.mediaMetadata.albumTitle?.toString() ?: "",
                it.localConfiguration?.uri?.toString().orEmpty())
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        top.addView(iconButton("⌄", "プレイヤーを閉じる") {
            fullPlayerVisible = false
            renderShell()
        })
        val topText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        topText.addView(makeText("再生中", 15f, primaryText, true))
        topText.addView(makeText("NIDAPLAYER", 10f, secondaryText, true))
        top.addView(topText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(iconButton("⋮", "ライブラリ") {
            fullPlayerVisible = false
            page = Page.LIBRARY
            renderShell()
        })
        parent.addView(top)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(10), dp(24), dp(28))
        }
        scroll.addView(body)
        parent.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        if (track == null) {
            emptyState(body, "曲を選択してください", "ライブラリから音楽を選ぶと、ここで再生できます。", "ライブラリを開く") {
                fullPlayerVisible = false
                page = Page.LIBRARY
                renderShell()
            }
            return
        }

        val cover = artworkView(track.uri, dp(300), dp(300)).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            elevation = dp(8).toFloat()
        }
        body.addView(cover, LinearLayout.LayoutParams(dp(300), dp(300)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(28)
        })
        body.addView(makeText(track.title, 24f, primaryText, true).apply {
            gravity = Gravity.CENTER
            maxLines = 2
        })
        addGap(body, 8)
        body.addView(makeText(track.artist, 16f, secondaryText).apply { gravity = Gravity.CENTER })
        addGap(body, 22)

        val progressLabels = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fullPositionLabel = makeText("0:00", 12f, secondaryText)
        fullDurationLabel = makeText("0:00", 12f, secondaryText)
        progressLabels.addView(fullPositionLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        progressLabels.addView(fullDurationLabel)
        body.addView(progressLabels)
        val seek = SeekBar(this).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(accentColor)
            thumbTintList = ColorStateList.valueOf(primaryText)
            progressBackgroundTintList = ColorStateList.valueOf(0xFF4A4449.toInt())
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val duration = controller?.duration ?: 0L
                        if (duration > 0L && duration != C.TIME_UNSET) {
                            fullPositionLabel?.text = formatTime(duration * progress / 1000L)
                        }
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    ignoreSeekChange = true
                }
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    val duration = controller?.duration ?: 0L
                    if (duration > 0L && duration != C.TIME_UNSET) {
                        controller?.seekTo(duration * seekBar.progress / 1000L)
                    }
                    ignoreSeekChange = false
                    updateProgressUi()
                }
            })
        }
        fullSeekBar = seek
        body.addView(seek, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(30)
        ))
        addGap(body, 12)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        controls.addView(iconButton("⤨", "ライブラリの先頭からシャッフル") {
            playAll(activeQueue.ifEmpty { tracks.toList() }, shuffle = true)
        })
        controls.addView(iconButton("⏮", "前の曲") { playRelative(-1) })
        fullPlayButton = TextView(this).apply {
            text = if (controller?.isPlaying == true) "Ⅱ" else "▶"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(0xFF191519.toInt())
            background = rounded(primaryText, 50f)
            setOnClickListener { togglePlayback() }
        }
        controls.addView(fullPlayButton, LinearLayout.LayoutParams(dp(72), dp(72)).apply {
            leftMargin = dp(16)
            rightMargin = dp(16)
        })
        controls.addView(iconButton("⏭", "次の曲") { playRelative(1) })
        controls.addView(iconButton("↻", "再生位置を先頭へ") { controller?.seekTo(0L) })
        body.addView(controls)
        addGap(body, 20)

        val safety = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(surfaceColor, 14f)
        }
        safety.addView(makeText("●", 11f, accentColor, true))
        safety.addView(makeText(
            "  イヤホン未検知時は安全のため消音します",
            12f, secondaryText
        ))
        body.addView(safety, fullWidthWrap())
        addGap(body, 22)

        sectionHeader(body, "次に再生", "${activeQueue.size}曲")
        renderTrackRows(body, activeQueue.ifEmpty { tracks.toList() }, activeQueue.ifEmpty { tracks.toList() })
        handler.removeCallbacks(progressRunnable)
        handler.post(progressRunnable)
        updateProgressUi()
    }

    private fun renderHorizontalTrackCards(parent: LinearLayout, items: List<AudioTrack>) {
        val horizontal = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEach { track ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, dp(12), 0)
                setOnClickListener { playTrack(track, tracks) }
            }
            card.addView(artworkView(track.uri, dp(144), dp(144)), LinearLayout.LayoutParams(dp(144), dp(144)))
            addGap(card, 9)
            card.addView(makeText(track.title, 14f, primaryText, true).apply { maxLines = 2 })
            addGap(card, 3)
            card.addView(makeText(track.artist, 12f, secondaryText).apply { maxLines = 1 })
            row.addView(card, LinearLayout.LayoutParams(dp(156), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        horizontal.addView(row)
        parent.addView(horizontal, fullWidthWrap())
    }

    private fun renderHorizontalCollectionCards(parent: LinearLayout, groups: List<Pair<String, List<AudioTrack>>>) {
        val horizontal = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        groups.forEach { (name, items) ->
            val key = "collection:" + name
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, dp(12), 0)
                setOnClickListener { openCollection(name, items) }
            }
            card.addView(artworkView(key, dp(130), dp(130)), LinearLayout.LayoutParams(dp(130), dp(130)))
            addGap(card, 8)
            card.addView(makeText(name, 14f, primaryText, true).apply { maxLines = 2 })
            addGap(card, 3)
            card.addView(makeText("${items.size}曲", 12f, secondaryText))
            row.addView(card, LinearLayout.LayoutParams(dp(142), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        horizontal.addView(row)
        parent.addView(horizontal, fullWidthWrap())
    }

    private fun renderCollectionRow(parent: LinearLayout, name: String, items: List<AudioTrack>, kind: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener { openCollection(name, items) }
        }
        val key = "collection:" + name
        row.addView(artworkView(key, dp(58), dp(58)), LinearLayout.LayoutParams(dp(58), dp(58)))
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        labels.addView(makeText(name, 15f, primaryText, true).apply { maxLines = 1 })
        labels.addView(makeText("${kind} · ${items.size}曲", 12f, secondaryText))
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(iconButton("▶", "再生") { playAll(items) })
        parent.addView(row)
        parent.addView(divider())
    }

    private fun renderTrackRows(parent: LinearLayout, items: List<AudioTrack>, queue: List<AudioTrack>) {
        if (items.isEmpty()) {
            emptyState(parent, "曲がありません", "端末に保存された音楽が表示されます。")
            return
        }
        items.forEach { track ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(7), 0, dp(7))
                setOnClickListener { playTrack(track, queue) }
            }
            row.addView(artworkView(track.uri, dp(54), dp(54)), LinearLayout.LayoutParams(dp(54), dp(54)))
            val labels = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
            }
            val isCurrent = activeTrack?.uri == track.uri
            labels.addView(makeText(track.title, 15f, if (isCurrent) accentColor else primaryText, true).apply {
                maxLines = 1
            })
            labels.addView(makeText(track.artist, 12f, secondaryText).apply { maxLines = 1 })
            row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(iconButton(if (isCurrent && controller?.isPlaying == true) "Ⅱ" else "▶", "再生") {
                playTrack(track, queue)
            })
            parent.addView(row)
            parent.addView(divider())
        }
    }

    private fun sectionHeader(parent: LinearLayout, title: String, action: String? = null, onAction: (() -> Unit)? = null) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(makeText(title, 21f, primaryText, true),
            LinearLayout.LayoutParams(0, dp(40), 1f))
        if (action != null) {
            val actionView = makeText(action, 12f, secondaryText, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(8), dp(2), dp(8))
                if (onAction != null) setOnClickListener { onAction() }
            }
            row.addView(actionView)
        }
        parent.addView(row)
        addGap(parent, 10)
    }

    private fun emptyState(
        parent: LinearLayout,
        title: String,
        description: String,
        buttonText: String? = null,
        onButton: (() -> Unit)? = null
    ) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(28), dp(18), dp(28))
            background = rounded(surfaceColor, 18f)
        }
        box.addView(makeText("♫", 34f, accentColor, true).apply { gravity = Gravity.CENTER })
        addGap(box, 10)
        box.addView(makeText(title, 18f, primaryText, true).apply { gravity = Gravity.CENTER })
        addGap(box, 6)
        box.addView(makeText(description, 13f, secondaryText).apply { gravity = Gravity.CENTER })
        if (buttonText != null && onButton != null) {
            addGap(box, 15)
            box.addView(actionButton(buttonText, true, onButton), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)
            ))
        }
        parent.addView(box, fullWidthWrap())
    }

    private fun openCollection(title: String, items: List<AudioTrack>) {
        collectionTitle = title
        collectionTracks = items
        page = Page.COLLECTION
        renderShell()
    }

    private fun playAll(items: List<AudioTrack>, shuffle: Boolean = false) {
        if (items.isEmpty()) {
            Toast.makeText(this, "再生できる曲がありません", Toast.LENGTH_SHORT).show()
            return
        }
        val queue = if (shuffle) items.shuffled() else items
        playTrack(queue.first(), queue)
    }

    private fun playTrack(track: AudioTrack, queue: List<AudioTrack> = tracks) {
        val player = controller ?: run {
            Toast.makeText(this, "プレイヤーを準備中です", Toast.LENGTH_SHORT).show()
            return
        }
        val safeQueue = queue.ifEmpty { tracks.toList() }
        val index = safeQueue.indexOfFirst { it.uri == track.uri }
        if (index < 0) {
            Toast.makeText(this, "この曲を再生できませんでした", Toast.LENGTH_SHORT).show()
            return
        }
        activeQueue = safeQueue.toList()
        activeTrack = track
        activeArtwork = artworkCache[track.uri]
        rememberRecent(track)
        val items = activeQueue.map { item ->
            MediaItem.Builder()
                .setMediaId(item.uri)
                .setUri(Uri.parse(item.uri))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(item.title)
                        .setArtist(item.artist)
                        .setAlbumTitle(item.album)
                        .build()
                )
                .build()
        }
        player.setMediaItems(items, index, 0L)
        player.prepare()
        player.play()
        renderShell()
        loadEmbeddedCover(track)
    }

    private fun togglePlayback() {
        val player = controller ?: return
        if (player.isPlaying) {
            player.pause()
        } else if (player.mediaItemCount > 0) {
            player.play()
        }
        updatePlayButtons()
    }

    private fun playRelative(delta: Int) {
        val player = controller ?: return
        val queue = activeQueue.ifEmpty { tracks.toList() }
        if (queue.isEmpty()) return
        if (player.mediaItemCount == 0) {
            playTrack(queue.first(), queue)
            return
        }
        val target = (player.currentMediaItemIndex + delta + queue.size) % queue.size
        player.seekTo(target, 0L)
        player.play()
    }

    private fun loadEmbeddedCover(track: AudioTrack) {
        artworkCache[track.uri]?.let {
            activeArtwork = it
            updateArtworkViews()
        }
        Thread {
            var bytes: ByteArray? = null
            try {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(this, Uri.parse(track.uri))
                    bytes = retriever.embeddedPicture
                } finally {
                    retriever.release()
                }
            } catch (_: Exception) {
                bytes = null
            }

            var bitmap: Bitmap? = null
            if (bytes != null) {
                bitmap = try {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes!!.size)
                } catch (_: Exception) {
                    null
                }
            }
            runOnUiThread {
                val player = controller
                if (isFinishing || activeTrack?.uri != track.uri) return@runOnUiThread
                val image = bitmap ?: placeholderCover(track.title)
                artworkCache[track.uri] = image
                activeArtwork = image
                updateArtworkViews()

                // Send embedded art to MediaSession too, so compatible system media controls
                // can use it for the notification and lock-screen artwork.
                val currentIndex = player?.currentMediaItemIndex ?: -1
                val currentItem = player?.currentMediaItem
                if (player != null && currentIndex >= 0 && currentItem != null && currentItem.mediaId == track.uri) {
                    val artworkBytes = bytes ?: encodeArtwork(image)
                    val metadata = MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist)
                        .setAlbumTitle(track.album)
                        .setArtworkData(artworkBytes, 3)
                        .build()
                    player.replaceMediaItem(
                        currentIndex,
                        currentItem.buildUpon().setMediaMetadata(metadata).build()
                    )
                }
            }
        }.start()
    }

    private fun updateArtworkViews() {
        artworkViews.forEach { view ->
            val key = view.tag as? String ?: return@forEach
            val bitmap = artworkCache[key]
            if (bitmap != null) view.setImageBitmap(bitmap)
            else if (activeTrack?.uri == key && activeArtwork != null) view.setImageBitmap(activeArtwork)
        }
    }

    private fun artworkView(key: String, width: Int, height: Int): ImageView {
        val view = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            tag = key
            background = rounded(surfaceColor, 12f)
            setImageBitmap(artworkCache[key] ?: placeholderCover(
                tracks.firstOrNull { it.uri == key }?.title ?: key.removePrefix("collection:")
            ))
            contentDescription = "アルバムアート"
        }
        artworkViews += view
        return view
    }

    private fun placeholderCover(seed: String): Bitmap {
        val key = "placeholder:" + seed
        artworkCache[key]?.let { return it }
        val palette = intArrayOf(
            0xFF5A2338.toInt(), 0xFF2B4960.toInt(), 0xFF49523A.toInt(),
            0xFF604A30.toInt(), 0xFF3E315E.toInt(), 0xFF24534F.toInt()
        )
        val index = (seed.hashCode().absoluteValueSafe()) % palette.size
        val first = palette[index]
        val second = palette[(index + 2) % palette.size]
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 512f, 512f, first, second, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, 512f, 512f, paint)
        paint.shader = null
        paint.color = 0x22FFFFFF
        canvas.drawCircle(390f, 105f, 170f, paint)
        paint.color = 0x18000000
        canvas.drawCircle(110f, 430f, 230f, paint)
        paint.color = 0xFFFFFFFF.toInt()
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        paint.textSize = 210f
        val glyph = seed.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "N"
        canvas.drawText(glyph, 256f, 335f, paint)
        artworkCache[key] = bitmap
        return bitmap
    }

    private fun encodeArtwork(bitmap: Bitmap): ByteArray {
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)
        return output.toByteArray()
    }

    private fun loadRecentHistory() {
        val saved = getSharedPreferences("nida_player", MODE_PRIVATE)
            .getString("recent_uris", "").orEmpty()
        recentUris.clear()
        recentUris.addAll(saved.split("\n").filter { it.isNotBlank() }.take(30))
    }

    private fun rememberRecent(track: AudioTrack) {
        recentUris.remove(track.uri)
        recentUris.add(0, track.uri)
        while (recentUris.size > 30) recentUris.removeAt(recentUris.lastIndex)
        getSharedPreferences("nida_player", MODE_PRIVATE).edit()
            .putString("recent_uris", recentUris.joinToString("\n"))
            .apply()
    }

    private fun recentTracks(): List<AudioTrack> {
        val byUri = tracks.associateBy { it.uri }
        return recentUris.mapNotNull { byUri[it] }
    }

    private fun updatePlayButtons() {
        val label = if (controller?.isPlaying == true) "Ⅱ" else "▶"
        miniPlayButton?.text = label
        fullPlayButton?.text = label
    }

    private fun updateProgressUi() {
        val player = controller ?: return
        val duration = player.duration.takeIf { it > 0L && it != C.TIME_UNSET } ?: 0L
        val position = player.currentPosition.coerceAtLeast(0L)
        fullPositionLabel?.text = formatTime(position)
        fullDurationLabel?.text = formatTime(duration)
        val seek = fullSeekBar ?: return
        if (!ignoreSeekChange) {
            seek.max = 1000
            seek.progress = if (duration > 0L) ((position.toDouble() / duration.toDouble()) * 1000.0).toInt().coerceIn(0, 1000) else 0
        }
    }

    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = (milliseconds / 1000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    private fun navItem(symbol: String, title: String, selected: Boolean, action: () -> Unit): View {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = if (selected) rounded(0xFF352128.toInt(), 16f) else rounded(backgroundColor, 16f)
            setOnClickListener { action() }
        }
        item.addView(makeText(symbol, 23f, if (selected) accentColor else secondaryText, true).apply {
            gravity = Gravity.CENTER
        })
        item.addView(makeText(title, 11f, if (selected) primaryText else secondaryText, selected).apply {
            gravity = Gravity.CENTER
        })
        return item
    }

    private fun iconButton(label: String, description: String, action: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            contentDescription = description
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(primaryText)
            background = rounded(backgroundColor, 50f)
            setOnClickListener { action() }
            minWidth = dp(42)
            minHeight = dp(42)
        }
    }

    private fun actionButton(label: String, primary: Boolean, action: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(if (primary) 0xFFFFFFFF.toInt() else primaryText)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = if (primary) rounded(accentColor, 24f) else rounded(elevatedColor, 24f)
            setOnClickListener { action() }
        }
    }

    private fun tabButton(label: String, selected: Boolean, action: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setTextColor(if (selected) primaryText else secondaryText)
            background = rounded(if (selected) 0xFF403038.toInt() else surfaceColor, 22f,
                if (selected) accentColor else surfaceColor, if (selected) dp(1) else 0)
            setOnClickListener { action() }
        }
    }

    private fun makeText(value: String, size: Float, color: Int, bold: Boolean = false): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = true
        }
    }

    private fun rounded(color: Int, radiusDp: Float, strokeColor: Int? = null, strokeWidth: Int = 0): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp.toInt()).toFloat()
            if (strokeColor != null && strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }
    }

    private fun divider(): View {
        return View(this).apply { setBackgroundColor(0xFF2B2B31.toInt()) }
            .also { it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)) }
    }

    private fun addGap(parent: LinearLayout, height: Int) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))
    }

    private fun fullWidthWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun Int.absoluteValueSafe(): Int {
        val value = if (this == Int.MIN_VALUE) 0 else kotlin.math.abs(this)
        return value
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onDestroy()
    }
}
