package com.nida.nidaplayer

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.Executors

/** Local-only conversion workspace; network access is limited to optional playlist metadata. */
class ImportActivity : ComponentActivity() {
    private lateinit var root: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var store: ImportStore
    private var selectedBatch = ""
    private var selectedEntry = ""
    private var shownRows = 50
    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var lastSnapshot = ""
    private var fetching = false
    private var fetchStatus: TextView? = null
    private var fetchMessage = ""
    private val white = 0xFFF7F7FA.toInt()
    private val secondary = 0xFFB4B4C0.toInt()
    private val pink = 0xFFFF375F.toInt()

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { startBatch() }
    private val sourcePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            store.update(selectedBatch) { batch -> batch.entries.find { it.id == selectedEntry }?.apply {
                source = uri.toString(); state = "waiting"; error = ""
            } }
        }.onFailure { toast("音源への継続アクセスを保存できませんでした。端末内のファイルを選択してください") }
        render()
    }
    private val localPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        io.execute {
            val entries = uris.mapIndexedNotNull { index, uri ->
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    var name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    }.orEmpty().ifBlank { "音源 ${index + 1}" }.substringBeforeLast('.')
                    var artist = "アーティスト不明"
                    val retriever = android.media.MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(this, uri)
                        retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
                            ?.takeIf { it.isNotBlank() }?.let { name = it }
                        retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
                            ?.takeIf { it.isNotBlank() }?.let { artist = it }
                    } catch (_: Exception) { /* Some sources only expose their filename. */ }
                    finally { retriever.release() }
                    ImportEntry(position = index, title = name, artist = artist, source = uri.toString())
                }.getOrNull()
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (entries.isEmpty()) { toast("選択した音源を開けませんでした"); return@runOnUiThread }
                val batch = ImportBatch(name = "音源の取り込み", entries = entries.toMutableList())
                store.add(batch); selectedBatch = batch.id
                if (entries.size != uris.size) toast("${entries.size}件を追加しました。一部のファイルは開けませんでした")
                render()
            }
        }
    }

    private val exportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/mp4")) { uri ->
        if (uri == null) return@registerForActivityResult
        val output = store.get(selectedBatch)?.entries?.find { it.id == selectedEntry }?.output ?: return@registerForActivityResult
        io.execute {
            val result = runCatching {
                contentResolver.openOutputStream(uri, "wt")?.use { target ->
                    contentResolver.openInputStream(Uri.parse(output))?.use { it.copyTo(target) }
                        ?: error("変換済みファイルを開けませんでした")
                } ?: error("保存先を開けませんでした")
            }
            runOnUiThread { toast(if (result.isSuccess) "M4Aファイルを保存しました" else "保存できませんでした。空き容量を確認してください") }
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            val snapshot = store.all().joinToString { it.json().toString() } + AudioImportService.runningBatch + AudioImportService.progress
            if (snapshot != lastSnapshot) { lastSnapshot = snapshot; render() }
            handler.postDelayed(this, 900L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ImportStore(this)
        selectedBatch = savedInstanceState?.getString("batch") ?: intent.getStringExtra("batch_id") ?: ""
        selectedEntry = savedInstanceState?.getString("entry").orEmpty()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF101012.toInt())
        }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom)); insets
        }
        render()
    }

    override fun onStart() { super.onStart(); handler.post(poll) }
    override fun onStop() { handler.removeCallbacks(poll); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("batch", selectedBatch); outState.putString("entry", selectedEntry)
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        val scrollPosition = (root.getChildAt(1) as? ScrollView)?.scrollY ?: 0
        root.removeAllViews()
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        top.addView(button("‹", false) { finish() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        top.addView(text("音楽を取り込む", 22f, white, true), LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(top)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(32)) }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val batches = store.all()
        val batch = batches.find { it.id == selectedBatch }
        if (batch == null) renderStart(batches) else renderBatch(batch)
        scroll.post { scroll.scrollTo(0, scrollPosition) }
    }

    private fun renderStart(batches: List<ImportBatch>) {
        body.addView(text("お気に入りを、\nオフラインでも。", 30f, white, true)); gap(12)
        body.addView(text("YouTubeの曲一覧と、手元の音源ファイルを組み合わせてプレイリストを作成します。変換はこのスマホで行います。", 14f)); gap(24)
        body.addView(button("YouTube再生リストのURLを貼り付け", true) { showPlaylistDialog() }); gap(10)
        body.addView(button("音源ファイルを選んでM4Aに変換", false) { localPicker.launch(arrayOf("audio/*")) }); gap(12)
        fetchStatus = text(fetchMessage, 13f).also { body.addView(it) }
        gap(24)
        body.addView(text("取り込み履歴・プレイリスト", 20f, white, true)); gap(12)
        if (batches.isEmpty()) body.addView(text("取り込みを始めるとここに保存されます。", 14f))
        batches.reversed().forEach { batch ->
            body.addView(button("${batch.name}\n${batch.entries.count { it.state == "done" }} / ${batch.entries.size} 曲", false) {
                selectedBatch = batch.id; shownRows = 50; render()
            }); gap(10)
        }
    }

    private fun showPlaylistDialog() {
        if (fetching) { toast("曲一覧を取得しています"); return }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(10), dp(22), 0) }
        val url = input("https://www.youtube.com/playlist?list=…")
        val key = input("YouTube Data API v3 のAPIキー").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            setSaveEnabled(false)
        }
        layout.addView(url); layout.addView(key)
        layout.addView(text("公開・限定公開の曲一覧を取得します。APIキーはこの取得にだけ使用します。", 12f))
        layout.addView(button("APIキーの設定方法", false) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://developers.google.com/youtube/v3/getting-started")))
        })
        val dialog = AlertDialog.Builder(this).setTitle("再生リストを取得").setView(layout)
            .setNegativeButton("閉じる", null).setPositiveButton("取得", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val address = url.text.toString().trim()
                val apiKey = key.text.toString().trim()
                if (runCatching { PlaylistUrl.id(address) }.isFailure || apiKey.isBlank()) {
                    toast("再生リストURLとAPIキーを入力してください"); return@setOnClickListener
                }
                key.setText(""); dialog.dismiss()
                fetching = true; fetchMessage = "曲一覧を取得しています…"; render()
                io.execute {
                    val result = runCatching { YouTubePlaylistClient(this).fetch(address, apiKey) { count ->
                        runOnUiThread { fetchMessage = "${count}曲を取得しました…"; fetchStatus?.text = fetchMessage }
                    } }
                    runOnUiThread {
                        fetching = false
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        result.fold(onSuccess = { batch ->
                            store.add(batch); selectedBatch = batch.id; fetchMessage = ""
                        }, onFailure = {
                            fetchMessage = it.message?.takeIf { message -> !message.contains("://") }
                                ?: "曲一覧を取得できませんでした。通信状態を確認してください"
                            toast(fetchMessage)
                        })
                        render()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun renderBatch(batch: ImportBatch) {
        val running = AudioImportService.runningBatch == batch.id
        body.addView(button("‹ 取り込み履歴", false) { selectedBatch = ""; render() }); gap(18)
        body.addView(text(batch.name, 26f, white, true)); gap(8)
        val done = batch.entries.count { it.state == "done" }
        val ready = batch.entries.count { it.source.isNotEmpty() && it.state !in setOf("done", "skipped") }
        body.addView(text("${done} / ${batch.entries.size} 曲完了 · 音源割り当て済み ${ready}曲", 14f)); gap(8)
        body.addView(text(if (running) "バックグラウンドで処理中 · ${if (AudioImportService.progress < 0) "準備中" else "${AudioImportService.progress}%"}"
            else "各曲をタップして音源を割り当てます。曲名・アーティストは編集できます。", 13f)); gap(16)
        if (running) body.addView(button("取り込みを中止", false) {
            startService(Intent(this, AudioImportService::class.java).setAction(AudioImportService.CANCEL))
        }) else {
            body.addView(button("${ready}曲を順番に取り込む / 再試行", true) {
                if (ready == 0) { toast("取り込む曲に音源ファイルを割り当ててください"); return@button }
                AlertDialog.Builder(this).setTitle("音源をM4Aに変換")
                    .setMessage("自作・購入済みなど、保存・変換が許可されている音源を使用します。${ready}曲を順番に処理します。")
                    .setNegativeButton("戻る", null).setPositiveButton("取り込む") { _, _ ->
                        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else startBatch()
                    }.show()
            }); gap(8)
            body.addView(button("プレイリスト名を編集", false) {
                val name = input("プレイリスト名").apply { setText(batch.name) }
                AlertDialog.Builder(this).setTitle("プレイリスト名").setView(name)
                    .setNegativeButton("戻る", null).setPositiveButton("保存") { _, _ ->
                        store.update(batch.id) { it.name = name.text.toString().trim().ifBlank { it.name } }; render()
                    }.show()
            })
        }
        gap(20)
        batch.entries.sortedBy { it.position }.take(shownRows).forEach { entry ->
            val status = when (entry.state) {
                "done" -> "完了 · タップしてM4Aを書き出す"
                "processing" -> if (running) "変換中" else "中断 · 再開できます"
                "failed" -> "失敗 · ${entry.error}"
                "skipped" -> "スキップ"
                else -> if (entry.source.isBlank()) "音源ファイルを選択" else "音源選択済み"
            }
            body.addView(button("${entry.position + 1}. ${entry.title}\n${entry.artist}\n$status", false) {
                if (running) return@button
                selectedEntry = entry.id
                if (entry.state == "done") exportPicker.launch("${entry.title.replace(Regex("[/\\\\:*?\"<>|]"), "_")}.m4a")
                else editEntry(batch, entry)
            }); gap(8)
        }
        if (batch.entries.size > shownRows) body.addView(button("さらに50曲を表示", false) { shownRows += 50; render() })
        if (done > 0) { gap(12); body.addView(text("完了した曲はメイン画面の「プレイリスト」から曲順どおりに再生できます。", 13f)) }
    }

    private fun editEntry(batch: ImportBatch, entry: ImportEntry) {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(10), dp(20), 0) }
        val title = input("曲名").apply { setText(entry.title) }
        val artist = input("アーティスト").apply { setText(entry.artist) }
        layout.addView(title); layout.addView(artist)
        AlertDialog.Builder(this).setTitle("${entry.position + 1}曲目").setView(layout)
            .setNeutralButton(if (entry.state == "skipped") "スキップ解除" else "スキップ") { _, _ ->
                store.update(batch.id) { it.entries.find { row -> row.id == entry.id }?.apply {
                    state = if (state == "skipped") "waiting" else "skipped"
                } }; render()
            }.setNegativeButton("情報を保存") { _, _ ->
                saveInfo(batch.id, entry.id, title, artist); render()
            }.setPositiveButton("音源を選択") { _, _ ->
                saveInfo(batch.id, entry.id, title, artist)
                sourcePicker.launch(arrayOf("audio/*"))
            }.show()
    }

    private fun saveInfo(batch: String, entry: String, title: EditText, artist: EditText) {
        store.update(batch) { it.entries.find { row -> row.id == entry }?.apply {
            this.title = title.text.toString().trim().ifBlank { this.title }
            this.artist = artist.text.toString().trim()
        } }
    }

    private fun startBatch() {
        if (AudioImportService.runningBatch != null) { toast("別の取り込みが進行中です"); return }
        runCatching { ContextCompat.startForegroundService(this, Intent(this, AudioImportService::class.java)
            .putExtra("batch_id", selectedBatch)) }.onFailure { toast("取り込みを開始できませんでした。アプリを開いて再試行してください") }
    }

    private fun text(value: String, size: Float, color: Int = secondary, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun button(value: String, primary: Boolean, action: () -> Unit) = text(value, 14f, white, primary).apply {
        setPadding(dp(16), dp(14), dp(16), dp(14)); minHeight = dp(50)
        background = GradientDrawable().apply { setColor(if (primary) pink else 0xFF232329.toInt()); cornerRadius = dp(18).toFloat() }
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }
    private fun input(hint: String) = EditText(this).apply {
        this.hint = hint; setTextColor(white); setHintTextColor(secondary); textSize = 14f
        inputType = InputType.TYPE_CLASS_TEXT; maxLines = 3
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    private fun gap(height: Int) { body.addView(android.view.View(this), LinearLayout.LayoutParams(1, dp(height))) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); io.shutdownNow(); super.onDestroy() }
}
