package com.lycoslane.purepodcast.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

data class EpisodeChapter(
    val startMs: Long,
    val title: String
)

object ChaptersParser {

    private const val MAX_CHAPTERS_BYTES = 2L * 1024 * 1024
    private const val MAX_CHAPTERS = 200

    fun encodeChaptersJson(chapters: List<EpisodeChapter>): String {
        if (chapters.isEmpty()) return ""
        val arr = JSONArray()
        chapters.sortedBy { it.startMs }.take(MAX_CHAPTERS).forEach { ch ->
            arr.put(
                JSONObject().apply {
                    put("t", ch.startMs.coerceAtLeast(0L))
                    put("title", ch.title.take(256))
                }
            )
        }
        return arr.toString()
    }

    fun decodeChaptersJson(json: String): List<EpisodeChapter> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            val list = ArrayList<EpisodeChapter>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val startMs = obj.optLong("t", -1L)
                val title = obj.optString("title", "").trim()
                if (startMs >= 0L && title.isNotEmpty()) {
                    list += EpisodeChapter(startMs = startMs, title = title)
                }
            }
            list.sortedBy { it.startMs }
        }.getOrDefault(emptyList())
    }

    fun parsePodloveTimestampMs(raw: String): Long {
        val cleaned = raw.trim().substringBefore('.').substringBefore(',')
        if (cleaned.isEmpty()) return -1L
        val parts = cleaned.split(":").mapNotNull { it.toLongOrNull() }
        return when (parts.size) {
            1 -> parts[0] * 1000L
            2 -> (parts[0] * 60L + parts[1]) * 1000L
            3 -> (parts[0] * 3600L + parts[1] * 60L + parts[2]) * 1000L
            else -> -1L
        }
    }

    suspend fun fetchPodcasting2ChaptersJson(chaptersUrl: String): String = withContext(Dispatchers.IO) {
        if (!NetworkSecurity.isValidHttpUrl(chaptersUrl)) return@withContext ""
        runCatching {
            val conn = NetworkSecurity.openValidatedConnection(
                rawUrl = chaptersUrl,
                connectTimeoutMs = 8_000,
                readTimeoutMs = 10_000
            )
            try {
                if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
                val text = BoundedInputStream(conn.inputStream, MAX_CHAPTERS_BYTES).use { stream ->
                    stream.readBytes().toString(Charsets.UTF_8)
                }
                val root = JSONObject(text)
                val arr = root.optJSONArray("chapters") ?: return@runCatching ""
                val parsed = ArrayList<EpisodeChapter>(arr.length().coerceAtMost(MAX_CHAPTERS))
                for (i in 0 until arr.length().coerceAtMost(MAX_CHAPTERS)) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val startSec = obj.optDouble("startTime", -1.0)
                    val title = obj.optString("title", "").trim()
                    if (startSec >= 0.0 && title.isNotEmpty()) {
                        parsed += EpisodeChapter(
                            startMs = (startSec * 1000.0).toLong().coerceAtLeast(0L),
                            title = title
                        )
                    }
                }
                encodeChaptersJson(parsed)
            } finally {
                conn.disconnect()
            }
        }.getOrDefault("")
    }
}
