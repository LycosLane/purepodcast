package com.lycoslane.purepodcast.core.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

    @Test
    fun downmixesStereoPcm16ToMonoCleanly() {
        val stereoBuf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        // Frame 0: (2000, 1000) -> 1500
        stereoBuf.putShort(2000).putShort(1000)
        // Frame 1: (-4000, 2000) -> -1000
        stereoBuf.putShort(-4000).putShort(2000)
        // Frame 2: (Short.MAX_VALUE, Short.MAX_VALUE) -> Short.MAX_VALUE
        stereoBuf.putShort(Short.MAX_VALUE).putShort(Short.MAX_VALUE)
        // Frame 3: (Short.MIN_VALUE, Short.MIN_VALUE) -> Short.MIN_VALUE
        stereoBuf.putShort(Short.MIN_VALUE).putShort(Short.MIN_VALUE)

        val monoBytes = AudioTranscoder.downmixStereoPcm16ToMono(stereoBuf.array())
        val expected = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1500)
            .putShort(-1000)
            .putShort(Short.MAX_VALUE)
            .putShort(Short.MIN_VALUE)
            .array()

        assertArrayEquals(expected, monoBytes)
    }
}
