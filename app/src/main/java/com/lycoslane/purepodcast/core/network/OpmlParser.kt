package com.lycoslane.purepodcast.core.network

import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.Locale

data class OpmlFeedEntry(
    val title: String,
    val feedUrl: String
)

object OpmlParser {
    private const val MAX_OPML_BYTES = 2L * 1024 * 1024
    private const val MAX_ENTRIES = 500

    fun parse(input: InputStream): List<OpmlFeedEntry> {
        val bounded = BoundedInputStream(input, MAX_OPML_BYTES)
        val parser = NetworkSecurity.newSafeXmlPullParser().apply {
            setInput(bounded, null)
        }
        val feeds = mutableListOf<OpmlFeedEntry>()
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT && feeds.size < MAX_ENTRIES) {
            if (eventType == XmlPullParser.START_TAG &&
                parser.name.lowercase(Locale.ROOT) == "outline"
            ) {
                val xmlUrl = (parser.getAttributeValue(null, "xmlUrl")
                    ?: parser.getAttributeValue(null, "xmlurl")
                    ?: "").trim()
                if (NetworkSecurity.isValidHttpUrl(xmlUrl)) {
                    val title = parser.getAttributeValue(null, "text")
                        ?: parser.getAttributeValue(null, "title")
                        ?: xmlUrl
                    feeds += OpmlFeedEntry(title = title.trim().take(512), feedUrl = xmlUrl)
                }
            }
            eventType = parser.next()
        }
        return feeds.distinctBy { it.feedUrl }
    }
}
