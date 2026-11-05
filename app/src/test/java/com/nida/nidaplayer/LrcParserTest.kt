package com.nida.nidaplayer

import org.junit.Assert.*
import org.junit.Test

class LrcParserTest {
    @Test fun repeatedTimestampsFractionsBomAndMetadata() {
        val doc = LrcParser.parse("\uFEFF[ti:曲名]\n[00:03.123]三\n[00:01.2][00:02.45]一・二\n[00:99]壊れた時刻")
        assertEquals(listOf(1200L, 2450L, 3123L), doc.lines.map { it.timeMs })
        assertEquals(listOf("一・二", "一・二", "三"), doc.lines.map { it.text })
        assertEquals(-1, doc.indexAt(0))
        assertEquals(1, doc.indexAt(2450))
        assertEquals(2, doc.indexAt(100_000))
        assertEquals(0, doc.indexAt(1800)) // Seeking backwards must reset the active line.
    }

    @Test fun positiveOffsetAdvancesAndPositiveUserDelayDelays() {
        val doc = LrcParser.parse("[offset:+200]\n[00:01.000]歌詞")
        assertEquals(0, doc.indexAt(800))
        assertEquals(-1, doc.indexAt(800, 100))
        assertEquals(0, doc.indexAt(900, 100))
    }

    @Test fun blankLinesAndDuplicateTimestampsRemainDeterministic() {
        val doc = LrcParser.parse("[00:01]一\n[00:01]翻訳\n[00:02]\n[00:03.1]次")
        assertEquals(1, doc.indexAt(1000))
        assertEquals("", doc.lines[doc.indexAt(2000)].text)
        assertTrue(LrcParser.parse("ただのテキスト").lines.isEmpty())
    }
}
