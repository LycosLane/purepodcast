package com.lycoslane.purepodcast.core.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTranscoderTest {

    @Test
    fun neverUpscalesOrReencodesCompactFormats() {
        assertTrue(AudioTranscoder.isBitrateAlreadyCompact("audio/mpeg", 128_000L, 192))
        assertTrue(AudioTranscoder.isBitrateAlreadyCompact("audio/mpeg", 128_000L, 128))
        assertFalse(AudioTranscoder.isBitrateAlreadyCompact("audio/mpeg", 192_000L, 128))
        assertFalse(AudioTranscoder.isBitrateAlreadyCompact("audio/mpeg", 128_000L, 64))
        assertTrue(AudioTranscoder.isBitrateAlreadyCompact("audio/opus", 64_000L, 64))
        assertFalse(AudioTranscoder.isBitrateAlreadyCompact("audio/flac", 900_000L, 192))
        assertFalse(AudioTranscoder.isBitrateAlreadyCompact("audio/raw", 1_411_200L, 192))
    }
}
