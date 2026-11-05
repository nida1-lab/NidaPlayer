package com.nida.nidaplayer

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/** Data API metadata only. This client has no playback/stream-extraction endpoint. */
class YouTubePlaylistClient(private val context: Context) {
    private fun request(resource: String, parameters: Map<String, String>, key: String): JSONObject {
        val query = (parameters + ("key" to key)).entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val connection = URL("https://www.googleapis.com/youtube/v3/$resource?$query")
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("X-Android-Package", context.packageName)
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName,
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
        @Suppress("DEPRECATION")
        val signature = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners?.firstOrNull()
            else info.signatures?.firstOrNull()
        signature?.let {
            connection.setRequestProperty("X-Android-Cert", MessageDigest.getInstance("SHA-1")
                .digest(it.toByteArray()).joinToString("") { byte -> "%02X".format(byte) })
        }
        try {
            if (connection.responseCode != 200) {
                val message = when (connection.responseCode) {
                    400 -> "URLまたはAPIキーを確認してください"
                    403 -> "APIキーの制限・YouTube Data APIの有効化・利用上限を確認してください"
                    404 -> "再生リストが見つかりません。公開設定を確認してください"
                    else -> "YouTubeから曲一覧を取得できませんでした。時間をおいて再試行してください"
                }
                error(message)
            }
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }

    fun fetch(url: String, apiKey: String, progress: (Int) -> Unit): ImportBatch {
        require(apiKey.isNotBlank()) { "YouTube Data API v3のAPIキーを入力してください" }
        val id = PlaylistUrl.id(url)
        val metadata = request("playlists", mapOf("part" to "snippet", "id" to id), apiKey)
            .getJSONArray("items")
        require(metadata.length() > 0) { "公開または限定公開の再生リストを指定してください" }
        val name = metadata.getJSONObject(0).getJSONObject("snippet").getString("title")
        val entries = mutableListOf<ImportEntry>()
        var token = ""
        val seen = mutableSetOf<String>()
        do {
            check(!Thread.currentThread().isInterrupted) { "取得を中止しました" }
            val response = request("playlistItems", mapOf("part" to "snippet", "playlistId" to id,
                "maxResults" to "50", "pageToken" to token), apiKey)
            val items = response.getJSONArray("items")
            for (i in 0 until items.length()) {
                val snippet = items.getJSONObject(i).getJSONObject("snippet")
                val title = snippet.optString("title", "取得できない動画")
                val thumbs = snippet.optJSONObject("thumbnails")
                val thumb = (thumbs?.optJSONObject("high") ?: thumbs?.optJSONObject("default"))
                    ?.optString("url").orEmpty()
                entries += ImportEntry(position = snippet.getInt("position"), title = title,
                    artist = snippet.optString("videoOwnerChannelTitle"), thumbnail = thumb)
            }
            progress(entries.size)
            token = response.optString("nextPageToken")
            check(token.isEmpty() || seen.add(token)) { "ページ情報が重複しました。再試行してください" }
            check(entries.size <= 10_000) { "1万曲を超える再生リストには対応していません" }
        } while (token.isNotEmpty())
        return ImportBatch(name = name, entries = entries.sortedBy { it.position }.toMutableList(), playlistId = id)
    }
}
