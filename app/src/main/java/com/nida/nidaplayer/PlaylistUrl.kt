package com.nida.nidaplayer

import java.net.URI
import java.net.URLDecoder

object PlaylistUrl {
    fun id(input: String): String {
        val url = URI(input.trim())
        require(url.scheme == "https" && url.host?.lowercase() in setOf(
            "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be")) {
            "YouTube再生リストのHTTPS URLを入力してください"
        }
        val id = url.rawQuery.orEmpty().split('&').mapNotNull { part ->
            val pair = part.split('=', limit = 2)
            if (pair.size == 2 && pair[0] == "list") URLDecoder.decode(pair[1], "UTF-8") else null
        }.firstOrNull()
        require(id != null && id.matches(Regex("[A-Za-z0-9_-]{10,100}"))) {
            "list= を含む再生リストURLを入力してください"
        }
        return id
    }
}
