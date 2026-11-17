package com.lycoslane.purepodcast.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

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

    private const val MAX_FEED_BYTES = 10L * 1024 * 1024
    private const val MAX_EPISODES = 2_000
    private const val MAX_TITLE_CHARS = 512
    private const val MAX_DESC_CHARS = 65_536

    private val zonedFormatters = listOf(
        DateTimeFormatter.RFC_1123_DATE_TIME,
        DateTimeFormatter.ISO_OFFSET_DATE_TIME,
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.ENGLISH)
    )
    private val localDateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)

    suspend fun fetchAndParse(feedUrl: String): ParsedPodcastFeed = withContext(Dispatchers.IO) {
        val conn = NetworkSecurity.openValidatedConnection(
            rawUrl = feedUrl,
            connectTimeoutMs = 15_000,
            readTimeoutMs = 20_000
        )
        try {
            if (conn.responseCode !in 200..299) {
                throw IOException("HTTP ${conn.responseCode}")
            }
            val finalUrl = conn.url?.toString()?.takeIf { NetworkSecurity.isValidHttpUrl(it) } ?: feedUrl
            BoundedInputStream(conn.inputStream, MAX_FEED_BYTES).use { stream ->
                parseStream(finalUrl, stream)
            }
        } finally {
            conn.disconnect()
        }
    }

    fun parseStream(feedUrl: String, input: InputStream): ParsedPodcastFeed {
        val parser = NetworkSecurity.newSafeXmlPullParser().apply {
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
        while (eventType != XmlPullParser.END_DOCUMENT && episodes.size < MAX_EPISODES) {
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
                            val href = parser.getAttributeValue(null, "href").orEmpty().trim()
                            if (channelArtwork.isEmpty() && NetworkSecurity.isValidHttpUrl(href)) {
                                channelArtwork = href
                            }
                        }
                        inItem -> when (tag) {
                            "title" -> epTitle = safeNextText(parser).take(MAX_TITLE_CHARS)
                            "guid" -> epGuid = safeNextText(parser).take(1024)
                            "description", "itunes:summary" -> {
                                val txt = safeNextText(parser).take(MAX_DESC_CHARS)
                                if (epDescription.isEmpty()) epDescription = txt
                            }
                            "content:encoded" -> epContentEncoded = safeNextText(parser).take(MAX_DESC_CHARS)
                            "pubdate" -> epPubDateMs = parseDateMs(safeNextText(parser))
                            "itunes:duration" -> epDurationMs = parseDurationMs(safeNextText(parser))
                            "itunes:image" -> {
                                val href = parser.getAttributeValue(null, "href").orEmpty().trim()
                                if (NetworkSecurity.isValidHttpUrl(href)) epArtwork = href
                            }
                            "enclosure" -> {
                                val urlAttr = parser.getAttributeValue(null, "url").orEmpty().trim()
                                val typeAttr = parser.getAttributeValue(null, "type").orEmpty()
                                val lenAttr = parser.getAttributeValue(null, "length").orEmpty()
                                if (NetworkSecurity.isValidHttpUrl(urlAttr) &&
                                    (typeAttr.isEmpty() || typeAttr.startsWith("audio") || typeAttr.contains("octet-stream"))
                                ) {
                                    epAudioUrl = urlAttr
                                    epAudioSize = (lenAttr.toLongOrNull() ?: 0L).coerceAtLeast(0L)
                                }
                            }
                        }
                        !inItem -> when (tag) {
                            "title" -> if (!inImage && channelTitle.isEmpty()) channelTitle = safeNextText(parser).take(MAX_TITLE_CHARS)
                            "itunes:author", "managingeditor" -> if (channelAuthor.isEmpty()) channelAuthor = safeNextText(parser).take(256)
                            "description", "itunes:summary" -> if (channelDescription.isEmpty()) channelDescription = safeNextText(parser).take(MAX_DESC_CHARS)
                            "url" -> if (inImage && channelArtwork.isEmpty()) {
                                val u = safeNextText(parser)
                                if (NetworkSecurity.isValidHttpUrl(u)) channelArtwork = u
                            }
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
                                title = epTitle.ifEmpty { "Episode" },
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

    private fun safeNextText(parser: XmlPullParser): String {
        return runCatching { parser.nextText().trim() }.getOrDefault("")
    }

    private fun parseDateMs(raw: String): Long {
        if (raw.isEmpty()) return 0L
        for (formatter in zonedFormatters) {
            val ms = runCatching { ZonedDateTime.parse(raw, formatter).toInstant().toEpochMilli() }.getOrNull()
            if (ms != null) return ms
        }
        val instantMs = runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
        if (instantMs != null) return instantMs
        return runCatching {
            LocalDateTime.parse(raw, localDateTimeFormatter).toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrDefault(0L)
    }

    private fun parseDurationMs(raw: String): Long {
        if (raw.isEmpty()) return 0L
        raw.toLongOrNull()?.let { seconds -> return (seconds * 1000L).coerceAtLeast(0L) }
        val parts = raw.split(":").mapNotNull { it.trim().toLongOrNull() }
        return when (parts.size) {
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000L
            2 -> (parts[0] * 60 + parts[1]) * 1000L
            else -> 0L
        }.coerceAtLeast(0L)
    }
}
