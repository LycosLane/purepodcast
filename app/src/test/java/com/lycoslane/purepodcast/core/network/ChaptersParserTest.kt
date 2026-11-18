package com.lycoslane.purepodcast.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

class ChaptersParserTest {

    @Test
    fun parsesPodloveTimestamps() {
        assertEquals(0L, ChaptersParser.parsePodloveTimestampMs("00:00"))
        assertEquals(65_000L, ChaptersParser.parsePodloveTimestampMs("01:05"))
        assertEquals(3_661_000L, ChaptersParser.parsePodloveTimestampMs("01:01:01.500"))
        assertEquals(42_000L, ChaptersParser.parsePodloveTimestampMs("42"))
        assertEquals(-1L, ChaptersParser.parsePodloveTimestampMs(""))
        assertEquals(-1L, ChaptersParser.parsePodloveTimestampMs("bad"))
    }
}
