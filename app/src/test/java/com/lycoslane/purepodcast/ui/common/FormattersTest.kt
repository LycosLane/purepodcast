package com.lycoslane.purepodcast.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormattersTest {

    @Test
    fun formatsDurations() {
        assertEquals("0:00", formatDuration(0L))
        assertEquals("0:00", formatDuration(-500L))
        assertEquals("0:09", formatDuration(9_000L))
        assertEquals("12:34", formatDuration((12 * 60 + 34) * 1000L))
        assertEquals("1:05:09", formatDuration((3600 + 5 * 60 + 9) * 1000L))
    }

    @Test
    fun formatsPubDates() {
        assertEquals("", formatPubDate(0L))
        assertEquals("", formatPubDate(-1L))
        assertTrue(formatPubDate(1_700_000_000_000L).isNotBlank())
    }
}
