package com.lycoslane.purepodcast.core.network

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.Locale

data class OpmlFeedEntry(
    val title: String,
    val feedUrl: String
)

object OpmlParser {
    fun parse(input: InputStream): List<OpmlFeedEntry> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(input, null)
        }
        val feeds = mutableListOf<OpmlFeedEntry>()
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG &&
                parser.name.lowercase(Locale.ROOT) == "outline"
            ) {
                val xmlUrl = parser.getAttributeValue(null, "xmlUrl")
                    ?: parser.getAttributeValue(null, "xmlurl")
                    ?: ""
                if (xmlUrl.isNotBlank()) {
                    val title = parser.getAttributeValue(null, "text")
                        ?: parser.getAttributeValue(null, "title")
                        ?: xmlUrl
                    feeds += OpmlFeedEntry(title = title.trim(), feedUrl = xmlUrl.trim())
                }
            }
            eventType = parser.next()
        }
        return feeds.distinctBy { it.feedUrl }
    }
}
