package com.nida.nidaplayer

data class LyricLine(val timeMs: Long, val text: String)

data class LrcDocument(val lines: List<LyricLine>, val offsetMs: Long = 0L) {
    // An LRC positive offset advances the lyrics; a user delay moves them later.
    fun indexAt(positionMs: Long, delayMs: Long = 0L): Int {
        val time = positionMs + offsetMs - delayMs
        var low = 0
        var high = lines.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMs <= time) low = mid + 1 else high = mid - 1
        }
        return high
    }
}

object LrcParser {
    private val timestamp = Regex("\\[(\\d{1,4}):([0-5]\\d)(?:[.:](\\d{1,3}))?]")
    private val offset = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE)

    fun parse(source: String): LrcDocument {
        val result = mutableListOf<LyricLine>()
        var offsetMs = 0L
        source.removePrefix("\uFEFF").lineSequence().forEach { line ->
            offset.find(line)?.groupValues?.get(1)?.toLongOrNull()?.let {
                offsetMs = it.coerceIn(-600_000L, 600_000L)
            }
            val times = timestamp.findAll(line).toList()
            if (times.isNotEmpty()) {
                val lyric = line.substring(times.last().range.last + 1).trim()
                times.forEach { match ->
                    val fraction = match.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                    val ms = match.groupValues[1].toLong() * 60_000L +
                        match.groupValues[2].toLong() * 1000L + fraction
                    result += LyricLine(ms, lyric)
                }
            }
        }
        return LrcDocument(result.sortedBy { it.timeMs }, offsetMs)
    }
}
