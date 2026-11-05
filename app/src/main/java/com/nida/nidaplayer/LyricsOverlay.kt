package com.nida.nidaplayer

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.Player

/** The playback service owns this window, so lyrics survive closing the activity. */
class LyricsOverlay(private val context: Context, private val player: Player, private val changed: () -> Unit) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val store = LyricsStore(context)
    private var view: LinearLayout? = null
    private var panel: LyricsPanel? = null
    private var track = ""
    private var revision = -1L
    private var document = LrcDocument(emptyList())
    val visible: Boolean get() = view != null

    private val tick = object : Runnable {
        override fun run() {
            if (!Settings.canDrawOverlays(context)) { hide(); return }
            val uri = player.currentMediaItem?.mediaId.orEmpty()
            val rev = context.getSharedPreferences("lyrics", Context.MODE_PRIVATE).getLong("revision", 0L)
            if (uri != track || rev != revision) {
                track = uri
                revision = rev
                document = store.load(uri)
            }
            panel?.display(document, player.currentPosition, store.delay(uri))
            if (visible) handler.postDelayed(this, 180L)
        }
    }

    fun toggle(): Boolean {
        if (visible) { hide(); return true }
        if (!Settings.canDrawOverlays(context) || player.currentMediaItem == null) return false
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val prefs = context.getSharedPreferences("lyrics", Context.MODE_PRIVATE)
        val screen = context.resources.displayMetrics
        val params = WindowManager.LayoutParams(
            (screen.widthPixels - dp(32)).coerceAtMost(dp(420)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("window_x", dp(16)).coerceIn(0, screen.widthPixels - width)
            y = prefs.getInt("window_y", dp(100)).coerceIn(dp(24), screen.heightPixels - dp(240))
            alpha = prefs.getInt("opacity", 88).coerceIn(30, 100) / 100f
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xD91B1B22.toInt()); cornerRadius = dp(22).toFloat()
            }
            elevation = dp(10).toFloat()
        }
        val bar = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(6), dp(6), 0)
        }
        val handle = TextView(context).apply {
            text = "⠿  歌詞 · ドラッグで移動"; textSize = 12f; setTextColor(0xFFB9B9C4.toInt())
            contentDescription = "歌詞ウィンドウを移動"
        }
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y; downX = event.rawX; downY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (startX + event.rawX - downX).toInt()
                        .coerceIn(0, (screen.widthPixels - params.width).coerceAtLeast(0))
                    params.y = (startY + event.rawY - downY).toInt()
                        .coerceIn(0, (screen.heightPixels - root.height).coerceAtLeast(0))
                    runCatching { windows.updateViewLayout(root, params) }
                }
                MotionEvent.ACTION_UP -> prefs.edit().putInt("window_x", params.x)
                    .putInt("window_y", params.y).apply()
            }
            true
        }
        bar.addView(handle, LinearLayout.LayoutParams(0, dp(42), 1f))
        bar.addView(TextView(context).apply {
            text = "×"; textSize = 25f; gravity = Gravity.CENTER; setTextColor(-1)
            contentDescription = "フローティング歌詞を閉じる"
            setOnClickListener { hide() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(bar)
        panel = LyricsPanel(context, true)
        root.addView(panel)
        return try {
            windows.addView(root, params)
            view = root
            handler.post(tick)
            changed()
            true
        } catch (_: Exception) { panel = null; false }
    }

    fun hide() {
        handler.removeCallbacks(tick)
        view?.let { runCatching { windows.removeView(it) } }
        view = null
        panel = null
        changed()
    }
}
