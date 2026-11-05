package com.nida.nidaplayer

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/** Shared three-line view, used in the player and in the floating window. */
class LyricsPanel(context: Context, private val floating: Boolean = false) : LinearLayout(context) {
    private val previous = label(14f, 0xFFB9B9C4.toInt())
    private val current = label(if (floating) 20f else 23f, 0xFFF7F7FA.toInt())
    private val next = label(14f, 0xFFB9B9C4.toInt())
    private var lastKey = ""

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        val pad = (12 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
        current.typeface = Typeface.DEFAULT_BOLD
        addView(previous)
        addView(current)
        addView(next)
        contentDescription = "同期歌詞"
    }

    fun display(document: LrcDocument, position: Long, delay: Long = 0L) {
        val index = document.indexAt(position, delay)
        val before = document.lines.getOrNull(index - 1)?.text.orEmpty()
        val text = if (document.lines.isEmpty()) "LRC歌詞を追加してください"
            else if (index < 0) "まもなく歌詞が始まります"
            else document.lines[index].text.ifEmpty { "♪" }
        val after = document.lines.getOrNull(index + 1)?.text.orEmpty()
        val key = "$index\n$before\n$text\n$after"
        if (key == lastKey) return
        lastKey = key
        previous.text = before
        current.text = text
        next.text = after
        current.animate().cancel()
        current.alpha = 0.25f
        current.translationY = 8f * resources.displayMetrics.density
        current.animate().alpha(1f).translationY(0f).setDuration(280L)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    private fun label(size: Float, color: Int) = TextView(context).apply {
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        maxLines = if (floating) 3 else 4
        setPadding(0, 6, 0, 6)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }
}
