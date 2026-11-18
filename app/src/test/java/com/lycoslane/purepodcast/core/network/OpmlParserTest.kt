package com.lycoslane.purepodcast.core.network

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpmlParserTest {

    @Test
    fun parsesValidFeedsFiltersUnsafeAndDeduplicates() {
        val opml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <opml version="2.0">
              <body>
                <outline text="Show A" xmlUrl="https://example.com/a.xml" />
                <outline title="Show B" xmlurl="https://example.com/b.xml" />
                <outline text="Show A Duplicate" xmlUrl="https://example.com/a.xml" />
                <outline text="Local Unsafe" xmlUrl="http://localhost/feed.xml" />
                <outline text="File Unsafe" xmlUrl="file:///etc/passwd" />
                <outline text="Private IP" xmlUrl="https://192.168.1.50/rss" />
              </body>
            </opml>
        """.trimIndent()

        val entries = OpmlParser.parse(ByteArrayInputStream(opml.toByteArray(Charsets.UTF_8)))
        assertEquals(2, entries.size)
        assertEquals("Show A", entries[0].title)
        assertEquals("https://example.com/a.xml", entries[0].feedUrl)
        assertEquals("Show B", entries[1].title)
        assertEquals("https://example.com/b.xml", entries[1].feedUrl)
    }
}
