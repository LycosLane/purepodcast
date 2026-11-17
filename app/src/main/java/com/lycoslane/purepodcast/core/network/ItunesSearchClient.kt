package com.lycoslane.purepodcast.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class PodcastSearchResult(
    val title: String,
    val author: String,
    val feedUrl: String,
    val artworkUrl: String,
    val primaryGenre: String
)

object ItunesSearchClient {

    suspend fun search(query: String, limit: Int = 25): List<PodcastSearchResult> =
        withContext(Dispatchers.IO) {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return@withContext emptyList()

            val encoded = URLEncoder.encode(trimmed, "UTF-8")
            val url = URL("https://itunes.apple.com/search?media=podcast&limit=$limit&term=$encoded")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", "PurePodcast/0.1 (Android)")
            }

            try {
                if (conn.responseCode !in 200..299) return@withContext emptyList()
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(body)
                val results = root.optJSONArray("results") ?: return@withContext emptyList()
                buildList {
                    for (i in 0 until results.length()) {
                        val item = results.optJSONObject(i) ?: continue
                        val feedUrl = item.optString("feedUrl", "").trim()
                        if (feedUrl.isEmpty()) continue
                        add(
                            PodcastSearchResult(
                                title = item.optString("collectionName", "Unknown"),
                                author = item.optString("artistName", ""),
                                feedUrl = feedUrl,
                                artworkUrl = item.optString("artworkUrl600")
                                    .ifEmpty { item.optString("artworkUrl100", "") },
                                primaryGenre = item.optString("primaryGenreName", "")
                            )
                        )
                    }
                }
            } finally {
                conn.disconnect()
            }
        }
}
