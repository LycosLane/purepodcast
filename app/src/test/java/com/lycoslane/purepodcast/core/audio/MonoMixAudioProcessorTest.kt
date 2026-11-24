package com.lycoslane.purepodcast.core.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

@OptIn(UnstableApi::class)
class MonoMixAudioProcessorTest {

    @Test
    fun resolvesEffectiveMonoHierarchyAndNormalization() {
        assertEquals(AudioMonoMode.OFF, AudioMonoMode.normalizeConcrete(-9))
        assertEquals(AudioMonoMode.RIGHT, AudioMonoMode.normalizeConcrete(AudioMonoMode.RIGHT))
        assertEquals(AudioMonoMode.INHERIT, AudioMonoMode.normalizeOverride(99))

        assertEquals(
            AudioMonoMode.MIX,
            AudioMonoMode.resolveEffective(AudioMonoMode.MIX, AudioMonoMode.INHERIT, AudioMonoMode.INHERIT)
        )
        assertEquals(
            AudioMonoMode.LEFT,
            AudioMonoMode.resolveEffective(AudioMonoMode.MIX, AudioMonoMode.LEFT, AudioMonoMode.INHERIT)
        )
        assertEquals(
            AudioMonoMode.OFF,
            AudioMonoMode.resolveEffective(AudioMonoMode.MIX, AudioMonoMode.LEFT, AudioMonoMode.OFF)
        )
        assertEquals(
            AudioMonoMode.RIGHT,
            AudioMonoMode.resolveEffective(AudioMonoMode.OFF, AudioMonoMode.INHERIT, AudioMonoMode.RIGHT)
        )

        assertFalse(AudioOverride.resolve(globalEnabled = false, podcastOverride = AudioOverride.INHERIT))
        assertTrue(AudioOverride.resolve(globalEnabled = true, podcastOverride = AudioOverride.INHERIT))
        assertFalse(AudioOverride.resolve(globalEnabled = true, podcastOverride = AudioOverride.OFF))
        assertTrue(AudioOverride.resolve(globalEnabled = false, podcastOverride = AudioOverride.ON))
    }

    @Test
    fun processesOffMixLeftAndRightModesAccurately() {
        val processor = MonoMixAudioProcessor()

        val offSamples = shortArrayOf(1200, -400, 3000, 1000)
        processor.processStereoPcm16(offSamples, 2, 44_100, AudioMonoMode.OFF)
        assertEquals(1200.toShort(), offSamples[0])
        assertEquals((-400).toShort(), offSamples[1])

        val mixSamples = shortArrayOf(2000, 1000, Short.MAX_VALUE, Short.MAX_VALUE)
        processor.processStereoPcm16(mixSamples, 2, 44_100, AudioMonoMode.MIX)
        assertEquals(1500.toShort(), mixSamples[0])
        assertEquals(1500.toShort(), mixSamples[1])
        assertEquals(Short.MAX_VALUE, mixSamples[2])
        assertEquals(Short.MAX_VALUE, mixSamples[3])

        val leftSamples = shortArrayOf(5000, -100, -4000, 200)
        processor.processStereoPcm16(leftSamples, 2, 44_100, AudioMonoMode.LEFT)
        assertEquals(5000.toShort(), leftSamples[0])
        assertEquals(5000.toShort(), leftSamples[1])
        assertEquals((-4000).toShort(), leftSamples[2])
        assertEquals((-4000).toShort(), leftSamples[3])

        val rightSamples = shortArrayOf(5000, -100, -4000, 2500)
        processor.processStereoPcm16(rightSamples, 2, 44_100, AudioMonoMode.RIGHT)
        assertEquals((-100).toShort(), rightSamples[0])
        assertEquals((-100).toShort(), rightSamples[1])
        assertEquals(2500.toShort(), rightSamples[2])
        assertEquals(2500.toShort(), rightSamples[3])
    }

    @Test
    fun smartAutoModePreservesLouderChannelAndAvoidsPhaseCancellation() {
        // 1. Speech only on Left channel (Right silent) -> tail block converges near full Left amplitude
        val leftOnlyProc = MonoMixAudioProcessor()
        val frames = 4096
        val leftOnly = ShortArray(frames * 2)
        for (f in 0 until frames) {
            leftOnly[f * 2] = if (f % 2 == 0) 10_000 else -10_000
            leftOnly[f * 2 + 1] = 0
        }
        leftOnlyProc.processStereoPcm16(leftOnly, frames, 44_100, AudioMonoMode.AUTO)
        val tailAmp = abs(leftOnly[(frames - 2) * 2].toInt())
        assertTrue("Expected tail amplitude > 8500, was $tailAmp", tailAmp > 8500)
        assertEquals(leftOnly[(frames - 2) * 2], leftOnly[(frames - 2) * 2 + 1])

        // 2. Inverted phase (R = -L) -> must avoid cancelling out near 0
        val phaseProc = MonoMixAudioProcessor()
        val antiPhase = ShortArray(frames * 2)
        for (f in 0 until frames) {
            val v: Short = if (f % 2 == 0) 12_000 else -12_000
            antiPhase[f * 2] = v
            antiPhase[f * 2 + 1] = (-v.toInt()).toShort()
        }
        phaseProc.processStereoPcm16(antiPhase, frames, 44_100, AudioMonoMode.AUTO)
        val tailAntiPhaseAmp = abs(antiPhase[(frames - 2) * 2].toInt())
        assertTrue("Expected non-cancelled amplitude > 9000, was $tailAntiPhaseAmp", tailAntiPhaseAmp > 9000)

        // 3. Identical balanced stereo -> preserves original level
        val balancedProc = MonoMixAudioProcessor()
        val balanced = ShortArray(frames * 2) { 6_000 }
        balancedProc.processStereoPcm16(balanced, frames, 44_100, AudioMonoMode.AUTO)
        assertEquals(6_000.toShort(), balanced[(frames - 1) * 2])
    }

    @Test
    fun resolvesSpeedSilenceAndSmartRewindOverrides() {
        assertTrue(SpeedOverride.isInherit(SpeedOverride.INHERIT))
        assertTrue(SpeedOverride.isInherit(Float.NaN))
        assertFalse(SpeedOverride.isInherit(1.0f))
        assertEquals(SpeedOverride.INHERIT, SpeedOverride.normalize(-1f), 0.001f)
        assertEquals(0.50f, SpeedOverride.normalize(0.2f), 0.001f)
        assertEquals(3.00f, SpeedOverride.normalize(5.0f), 0.001f)
        assertEquals(1.25f, SpeedOverride.resolve(1.25f, SpeedOverride.INHERIT), 0.001f)
        assertEquals(1.60f, SpeedOverride.resolve(1.25f, 1.60f), 0.001f)

        assertEquals(SilenceOverride.INHERIT, SilenceOverride.normalize(99))
        assertEquals(SilenceOverride.OFF, SilenceOverride.fromEnabledAndIntensity(false, 2))
        assertEquals(SilenceOverride.SOFT, SilenceOverride.fromEnabledAndIntensity(true, 0))
        assertEquals(SilenceOverride.MEDIUM, SilenceOverride.fromEnabledAndIntensity(true, 1))
        assertEquals(SilenceOverride.STRONG, SilenceOverride.fromEnabledAndIntensity(true, 2))
        assertEquals(true to 2, SilenceOverride.resolve(true, 2, SilenceOverride.INHERIT))
        assertEquals(false to 1, SilenceOverride.resolve(true, 2, SilenceOverride.OFF))
        assertEquals(true to 1, SilenceOverride.resolve(false, 0, SilenceOverride.MEDIUM))

        assertEquals(SmartRewindOverride.INHERIT, SmartRewindOverride.normalize(-5))
        assertEquals(120, SmartRewindOverride.normalize(500))
        assertEquals(15, SmartRewindOverride.resolve(15, SmartRewindOverride.INHERIT))
        assertEquals(0, SmartRewindOverride.resolve(15, SmartRewindOverride.OFF))
        assertEquals(25, SmartRewindOverride.resolve(15, 25))
    }

    @Test
    fun delegatingSilenceProcessorSurvivesCrossFormatTrackTransitions() {
        val processor = DelegatingSilenceAudioProcessor()
        processor.setMode(enabled = true, intensity = 1)

        // Non-PCM-16BIT returns NOT_SET cleanly instead of throwing
        val floatFmt = AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_FLOAT)
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, processor.configure(floatFmt))

        // Track 1: 44.1 kHz stereo
        val fmt1 = AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)
        assertEquals(fmt1, processor.configure(fmt1))
        processor.flush()

        val pcm1 = ByteBuffer.allocateDirect(4096 * 4).order(ByteOrder.nativeOrder())
        repeat(4096) { i ->
            val v: Short = if (i % 2 == 0) 8000 else -8000
            pcm1.putShort(v).putShort(v)
        }
        pcm1.flip()
        processor.queueInput(pcm1)
        processor.queueEndOfStream()
        var outBytes1 = 0
        while (true) {
            val out = processor.getOutput()
            if (!out.hasRemaining()) break
            outBytes1 += out.remaining()
        }
        assertTrue("Expected non-empty output on Track 1", outBytes1 > 0)
        assertTrue(processor.isEnded)

        // Track 2 transition: 48 kHz mono
        processor.flush()
        val fmt2 = AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT)
        assertEquals(fmt2, processor.configure(fmt2))
        processor.flush()

        val pcm2 = ByteBuffer.allocateDirect(4096 * 2).order(ByteOrder.nativeOrder())
        repeat(4096) { i ->
            val v: Short = if (i % 2 == 0) 9000 else -9000
            pcm2.putShort(v)
        }
        pcm2.flip()
        processor.queueInput(pcm2)
        processor.queueEndOfStream()
        var outBytes2 = 0
        while (true) {
            val out = processor.getOutput()
            if (!out.hasRemaining()) break
            outBytes2 += out.remaining()
        }
        assertTrue("Expected non-empty output on Track 2 after format switch", outBytes2 > 0)
    }
}
