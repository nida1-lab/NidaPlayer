package com.nida.nidaplayer

import org.junit.Assert.*
import org.junit.Test

class PlaylistUrlTest {
    @Test fun extractsListFromPlaylistWatchAndMusicLinks() {
        listOf("https://www.youtube.com/playlist?list=PL_example123&si=abc",
            "https://music.youtube.com/playlist?list=PL_example123",
            "https://youtu.be/video?list=PL_example123").forEach {
            assertEquals("PL_example123", PlaylistUrl.id(it))
        }
    }

    @Test fun rejectsNonYoutubeHostsAndMissingList() {
        listOf("https://youtube.com.evil.example/?list=PL_example123", "http://youtube.com/?list=PL_example123",
            "https://www.youtube.com/watch?v=video", "file:///video.mp3").forEach {
            assertTrue(runCatching { PlaylistUrl.id(it) }.isFailure)
        }
    }
}
