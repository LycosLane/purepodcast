package com.lycoslane.purepodcast.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RssFeedParserTest {

    @Test
    fun parsesChannelAndEpisodesWithNestedTagsAndChapters() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"
                 xmlns:psc="http://podlove.org/simple-chapters"
                 xmlns:podcast="https://podcastindex.org/namespace/1.0">
              <channel>
                <title>Test Show</title>
                <itunes:author>Test Host</itunes:author>
                <description>Show <p>summary</p> here</description>
                <itunes:image href="https://example.com/cover.jpg" />
                <item>
                  <guid>ep-1</guid>
                  <title>Episode One</title>
                  <description>Intro <b>bold</b> text</description>
                  <pubDate>Mon, 01 Jan 2024 10:00:00 GMT</pubDate>
                  <itunes:duration>01:02:03</itunes:duration>
                  <enclosure url="https://example.com/ep1.mp3" type="audio/mpeg" length="123456" />
                  <psc:chapter start="00:00" title="Start" />
                  <psc:chapter start="01:30" title="Topic" />
                </item>
                <item>
                  <title>Episode Two</title>
                  <itunes:duration>90</itunes:duration>
                  <enclosure url="https://example.com/ep2.m4a" type="audio/mp4" length="0" />
                  <podcast:chapters url="https://example.com/ep2-chapters.json" type="application/json+chapters" />
                </item>
                <item>
                  <title>Ignored Non-Audio</title>
                  <enclosure url="https://example.com/ep3.pdf" type="application/pdf" length="999" />
                </item>
                <item>
                  <title>Ignored Localhost</title>
                  <enclosure url="http://127.0.0.1/secret.mp3" type="audio/mpeg" length="999" />
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val feed = RssFeedParser.parseStream(
            "https://example.com/feed.xml",
            ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))
        )

        assertEquals("Test Show", feed.title)
        assertEquals("Test Host", feed.author)
        assertEquals("https://example.com/cover.jpg", feed.artworkUrl)
        assertEquals(2, feed.episodes.size)

        val ep1 = feed.episodes[0]
        assertEquals("ep-1", ep1.guid)
        assertEquals("Episode One", ep1.title)
        assertTrue(ep1.descriptionHtml.contains("Intro"))
        assertEquals(3_723_000L, ep1.durationMs)
        assertEquals(123_456L, ep1.fileSizeBytes)
        assertEquals("https://example.com/ep1.mp3", ep1.audioUrl)
        assertEquals(2, ChaptersParser.decodeChaptersJson(ep1.chaptersJson).size)

        val ep2 = feed.episodes[1]
        assertEquals("https://example.com/ep2.m4a", ep2.guid)
        assertEquals(90_000L, ep2.durationMs)
        assertEquals("https://example.com/ep2-chapters.json", ep2.chaptersUrl)
    }
}
