package com.lycoslane.purepodcast.core.network

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class ParsedEpisode(
    val guid: String,
    val title: String,
    val descriptionHtml: String,
    val audioUrl: String,
    val artworkUrl: String,
    val pubDateMs: Long,
    val durationMs: Long,
    val fileSizeBytes: Long
)

data class ParsedPodcastFeed(
    val feedUrl: String,
    val title: String,
    val author: String,
    val description: String,
    val artworkUrl: String,
    val episodes: List<ParsedEpisode>
)

object RssFeedParser {

    private val dateFormats = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEE, d MMM yyyy HH:mm:ss Z",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd HH:mm:ss"
    )

    suspend fun fetchAndParse(feedUrl: String): ParsedPodcastFeed = withContext(Dispatchers.IO) {
        val conn = (URL(feedUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "PurePodcast/0.1 (Android)")
        }
        try {
            if (conn.responseCode !in 200..299) {
                error("HTTP ${conn.responseCode} beim Laden des Feeds")
            }
            conn.inputStream.use { stream ->
                parseStream(feedUrl, stream)
            }
        } finally {
            conn.disconnect()
        }
    }

    fun parseStream(feedUrl: String, input: InputStream): ParsedPodcastFeed {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(input, null)
        }

        var channelTitle = ""
        var channelAuthor = ""
        var channelDescription = ""
        var channelArtwork = ""
        val episodes = mutableListOf<ParsedEpisode>()

        var inItem = false
        var inImage = false

        var epGuid = ""
        var epTitle = ""
        var epDescription = ""
        var epContentEncoded = ""
        var epAudioUrl = ""
        var epAudioSize = 0L
        var epArtwork = ""
        var epPubDateMs = 0L
        var epDurationMs = 0L

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.lowercase(Locale.ROOT)
                    when {
                        tag == "item" -> {
                            inItem = true
                            epGuid = ""
                            epTitle = ""
                            epDescription = ""
                            epContentEncoded = ""
                            epAudioUrl = ""
                            epAudioSize = 0L
                            epArtwork = ""
                            epPubDateMs = 0L
                            epDurationMs = 0L
                        }
                        !inItem && tag == "image" -> inImage = true
                        !inItem && tag == "itunes:image" -> {
                            val href = parser.getAttributeValue(null, "href").orEmpty()
                            if (href.isNotEmpty() && channelArtwork.isEmpty()) channelArtwork = href
                        }
                        inItem -> when (tag) {
                            "title" -> epTitle = parser.nextText().trim()
                            "guid" -> epGuid = parser.nextText().trim()
                            "description", "itunes:summary" -> {
                                val txt = parser.nextText().trim()
                                if (epDescription.isEmpty()) epDescription = txt
                            }
                            "content:encoded" -> epContentEncoded = parser.nextText().trim()
                            "pubdate" -> epPubDateMs = parseDateMs(parser.nextText().trim())
                            "itunes:duration" -> epDurationMs = parseDurationMs(parser.nextText().trim())
                            "itunes:image" -> {
                                val href = parser.getAttributeValue(null, "href").orEmpty()
                                if (href.isNotEmpty()) epArtwork = href
                            }
                            "enclosure" -> {
                                val urlAttr = parser.getAttributeValue(null, "url").orEmpty()
                                val typeAttr = parser.getAttributeValue(null, "type").orEmpty()
                                val lenAttr = parser.getAttributeValue(null, "length").orEmpty()
                                if (urlAttr.isNotEmpty() && (typeAttr.isEmpty() || typeAttr.startsWith("audio") || typeAttr.contains("octet-stream"))) {
                                    epAudioUrl = urlAttr
                                    epAudioSize = lenAttr.toLongOrNull() ?: 0L
                                }
                            }
                        }
                        !inItem -> when (tag) {
                            "title" -> if (!inImage && channelTitle.isEmpty()) channelTitle = parser.nextText().trim()
                            "itunes:author", "managingeditor" -> if (channelAuthor.isEmpty()) channelAuthor = parser.nextText().trim()
                            "description", "itunes:summary" -> if (channelDescription.isEmpty()) channelDescription = parser.nextText().trim()
                            "url" -> if (inImage && channelArtwork.isEmpty()) channelArtwork = parser.nextText().trim()
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.name.lowercase(Locale.ROOT)
                    if (tag == "item" && inItem) {
                        inItem = false
                        if (epAudioUrl.isNotEmpty()) {
                            episodes += ParsedEpisode(
                                guid = epGuid.ifEmpty { epAudioUrl },
                                title = epTitle.ifEmpty { "Unbenannte Episode" },
                                descriptionHtml = epContentEncoded.ifEmpty { epDescription },
                                audioUrl = epAudioUrl,
                                artworkUrl = epArtwork.ifEmpty { channelArtwork },
                                pubDateMs = epPubDateMs,
                                durationMs = epDurationMs,
                                fileSizeBytes = epAudioSize
                            )
                        }
                    } else if (tag == "image") {
                        inImage = false
                    }
                }
            }
            eventType = parser.next()
        }

        return ParsedPodcastFeed(
            feedUrl = feedUrl,
            title = channelTitle.ifEmpty { feedUrl },
            author = channelAuthor,
            description = channelDescription,
            artworkUrl = channelArtwork,
            episodes = episodes
        )
    }

    private fun parseDateMs(raw: String): Long {
        if (raw.isEmpty()) return 0L
        for (pattern in dateFormats) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.ENGLISH).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val parsed = sdf.parse(raw)
                if (parsed != null) return parsed.time
            } catch (_: Exception) {
            }
        }
        return 0L
    }

    private fun parseDurationMs(raw: String): Long {
        if (raw.isEmpty()) return 0L
        raw.toLongOrNull()?.let { seconds -> return seconds * 1000L }
        val parts = raw.split(":").mapNotNull { it.trim().toLongOrNull() }
        return when (parts.size) {
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000L
            2 -> (parts[0] * 60 + parts[1]) * 1000L
            else -> 0L
        }
    }
}
