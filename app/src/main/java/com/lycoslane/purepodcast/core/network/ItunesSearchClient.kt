package com.lycoslane.purepodcast.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

data class PodcastSearchResult(
    val title: String,
    val author: String,
    val feedUrl: String,
    val artworkUrl: String,
    val primaryGenre: String
)

object ItunesSearchClient {

    private const val MAX_RESPONSE_BYTES = 2L * 1024 * 1024

    suspend fun search(query: String, limit: Int = 25): List<PodcastSearchResult> =
        withContext(Dispatchers.IO) {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return@withContext emptyList()

            val safeLimit = limit.coerceIn(1, 50)
            val encoded = URLEncoder.encode(trimmed, "UTF-8")
            val conn = NetworkSecurity.openValidatedConnection(
                rawUrl = "https://itunes.apple.com/search?media=podcast&limit=$safeLimit&term=$encoded",
                connectTimeoutMs = 10_000,
                readTimeoutMs = 10_000
            )

            try {
                if (conn.responseCode !in 200..299) return@withContext emptyList()
                val body = BoundedInputStream(conn.inputStream, MAX_RESPONSE_BYTES)
                    .bufferedReader()
                    .use { it.readText() }
                val root = JSONObject(body)
                val results = root.optJSONArray("results") ?: return@withContext emptyList()
                buildList {
                    for (i in 0 until results.length()) {
                        val item = results.optJSONObject(i) ?: continue
                        val feedUrl = item.optString("feedUrl", "").trim()
                        if (!NetworkSecurity.isValidHttpUrl(feedUrl)) continue
                        val artwork = item.optString("artworkUrl600")
                            .ifEmpty { item.optString("artworkUrl100", "") }
                            .trim()
                        add(
                            PodcastSearchResult(
                                title = item.optString("collectionName", "Unknown").take(512),
                                author = item.optString("artistName", "").take(256),
                                feedUrl = feedUrl,
                                artworkUrl = if (NetworkSecurity.isValidHttpUrl(artwork)) artwork else "",
                                primaryGenre = item.optString("primaryGenreName", "").take(128)
                            )
                        )
                    }
                }
            } finally {
                conn.disconnect()
            }
        }
}
