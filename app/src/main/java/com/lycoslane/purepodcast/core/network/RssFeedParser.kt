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
    val fileSizeBytes: Long,
    val chaptersJson: String = "",
    val chaptersUrl: String = ""
)

sealed class FeedFetchResult {
    object NotModified : FeedFetchResult()
    data class Parsed(
        val feed: ParsedPodcastFeed,
        val etag: String,
        val lastModified: String,
        /** Set when the feed URL was permanently redirected (301/308) to this URL. */
        val movedToUrl: String?
    ) : FeedFetchResult()
}

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
    private const val MAX_PARSED_ITEMS = 6_000
    private const val MAX_TITLE_CHARS = 512
    private const val MAX_DESC_CHARS = 65_536

    private val zonedFormatters = listOf(
        DateTimeFormatter.RFC_1123_DATE_TIME,
        DateTimeFormatter.ISO_OFFSET_DATE_TIME,
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss zzz", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm zzz", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm Z", Locale.ENGLISH)
    )
    private val localDateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)

    suspend fun fetchAndParse(feedUrl: String): ParsedPodcastFeed {
        val result = fetchConditional(feedUrl, "", "")
        return (result as FeedFetchResult.Parsed).feed
    }

    /** Fetches the feed using ETag / Last-Modified validators; returns NotModified on HTTP 304. */
    suspend fun fetchConditional(feedUrl: String, etag: String, lastModified: String): FeedFetchResult =
        withContext(Dispatchers.IO) {
            val headers = buildMap<String, String> {
                if (etag.isNotEmpty()) put("If-None-Match", etag)
                if (lastModified.isNotEmpty()) put("If-Modified-Since", lastModified)
            }
            val tracker = RedirectTracker()
            val conn = NetworkSecurity.openValidatedConnection(
                rawUrl = feedUrl,
                connectTimeoutMs = 15_000,
                readTimeoutMs = 20_000,
                headers = headers,
                redirectTracker = tracker
            )
            try {
                val code = conn.responseCode
                if (code == 304 && headers.isNotEmpty()) return@withContext FeedFetchResult.NotModified
                if (code !in 200..299) {
                    throw IOException("HTTP $code")
                }
                val finalUrl = conn.url?.toString()?.takeIf { NetworkSecurity.isValidHttpUrl(it) } ?: feedUrl
                val feed = BoundedInputStream(conn.inputStream, MAX_FEED_BYTES).use { stream ->
                    parseStream(finalUrl, stream)
                }
                FeedFetchResult.Parsed(
                    feed = feed,
                    etag = conn.getHeaderField("ETag").orEmpty().trim(),
                    lastModified = conn.getHeaderField("Last-Modified").orEmpty().trim(),
                    movedToUrl = tracker.permanentUrl?.takeIf { it != feedUrl }
                )
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
        var epChaptersUrl = ""
        val epChapters = mutableListOf<EpisodeChapter>()

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT && episodes.size < MAX_PARSED_ITEMS) {
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
                            epChaptersUrl = ""
                            epChapters.clear()
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
                            "podcast:chapters" -> {
                                val urlAttr = parser.getAttributeValue(null, "url").orEmpty().trim()
                                val typeAttr = parser.getAttributeValue(null, "type").orEmpty().lowercase(Locale.ROOT)
                                if (NetworkSecurity.isValidHttpUrl(urlAttr) &&
                                    (typeAttr.isEmpty() || typeAttr.contains("json"))
                                ) {
                                    epChaptersUrl = urlAttr
                                }
                            }
                            "psc:chapter", "chapter" -> {
                                val startRaw = parser.getAttributeValue(null, "start").orEmpty()
                                val chTitle = parser.getAttributeValue(null, "title").orEmpty().trim()
                                val startMs = ChaptersParser.parsePodloveTimestampMs(startRaw)
                                if (startMs >= 0L && chTitle.isNotEmpty()) {
                                    epChapters += EpisodeChapter(startMs = startMs, title = chTitle)
                                }
                            }
                            "enclosure" -> {
                                val urlAttr = parser.getAttributeValue(null, "url").orEmpty().trim()
                                val typeAttr = parser.getAttributeValue(null, "type").orEmpty()
                                val lenAttr = parser.getAttributeValue(null, "length").orEmpty()
                                val typeLc = typeAttr.lowercase(Locale.ROOT)
                                if (epAudioUrl.isEmpty() && NetworkSecurity.isValidHttpUrl(urlAttr) &&
                                    (typeLc.isEmpty() || typeLc.startsWith("audio") || typeLc.contains("octet-stream"))
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
                                fileSizeBytes = epAudioSize,
                                chaptersJson = ChaptersParser.encodeChaptersJson(epChapters),
                                chaptersUrl = epChaptersUrl
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
            // Oldest-first feeds: keep the newest episodes rather than the first ones in the file.
            episodes = if (episodes.size > MAX_EPISODES) {
                episodes.sortedByDescending { it.pubDateMs }.take(MAX_EPISODES)
            } else {
                episodes
            }
        )
    }

    private fun safeNextText(parser: XmlPullParser): String {
        return runCatching { parser.nextText().trim() }.getOrDefault("")
    }

    private fun parseDateMs(rawInput: String): Long {
        if (rawInput.isEmpty()) return 0L
        // Normalise common sloppiness: lower-case zone names, doubled spaces.
        val raw = rawInput.trim().replace(Regex("\\s+"), " ")
            .replace(Regex("\\b(gmt|utc|est|edt|cst|cdt|mst|mdt|pst|pdt)$"), { it.value.uppercase(Locale.ROOT) })
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
        raw.trim().toDoubleOrNull()?.let { seconds ->
            if (seconds.isFinite()) return (seconds * 1000.0).toLong().coerceAtLeast(0L)
        }
        val parts = raw.split(":").mapNotNull { it.trim().toDoubleOrNull() }
        if (parts.any { !it.isFinite() }) return 0L
        return when (parts.size) {
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000.0
            2 -> (parts[0] * 60 + parts[1]) * 1000.0
            else -> 0.0
        }.toLong().coerceAtLeast(0L)
    }
}
