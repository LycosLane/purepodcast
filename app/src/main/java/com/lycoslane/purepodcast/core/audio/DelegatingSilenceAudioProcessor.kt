package com.lycoslane.purepodcast.core.audio

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Keeps silence-skipping active in Media3's AudioProcessingPipeline at all times so
 * enabling, disabling, or changing intensity takes effect immediately mid-playback
 * without requiring a sink reconfigure.
 */
@UnstableApi
class DelegatingSilenceAudioProcessor : AudioProcessor {

    private val tiers = arrayOf(
        // 0: Soft
        SilenceSkippingAudioProcessor(1_600_000L, 0.35f, 1_600_000L, 10, 1024),
        // 1: Medium
        SilenceSkippingAudioProcessor(850_000L, 0.20f, 850_000L, 5, 1024),
        // 2: Strong
        SilenceSkippingAudioProcessor(400_000L, 0.10f, 400_000L, 2, 1280)
    )

    @Volatile
    var activeTier: Int = -1
        private set

    private var currentTier: Int = -1
    private var inputFormat: AudioProcessor.AudioFormat = AudioProcessor.AudioFormat.NOT_SET
    private var passthroughBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var reusableBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded: Boolean = false

    fun setMode(enabled: Boolean, intensity: Int) {
        activeTier = if (enabled) intensity.coerceIn(0, 2) else -1
    }

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        inputFormat = inputAudioFormat
        if (inputAudioFormat == AudioProcessor.AudioFormat.NOT_SET) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        for (tier in tiers) {
            tier.setEnabled(true)
            tier.configure(inputAudioFormat)
        }
        currentTier = activeTier
        return inputAudioFormat
    }

    override fun isActive(): Boolean = inputFormat != AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val targetTier = activeTier
        if (targetTier != currentTier) {
            if (currentTier in 0..2) {
                tiers[currentTier].flush()
            }
            if (targetTier in 0..2) {
                tiers[targetTier].flush()
            }
            currentTier = targetTier
        }

        if (currentTier in 0..2) {
            tiers[currentTier].queueInput(inputBuffer)
        } else {
            val remaining = inputBuffer.remaining()
            if (remaining > 0) {
                if (reusableBuffer.capacity() < remaining) {
                    reusableBuffer = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder())
                } else {
                    reusableBuffer.clear()
                }
                reusableBuffer.put(inputBuffer)
                reusableBuffer.flip()
                passthroughBuffer = reusableBuffer
            }
        }
    }

    override fun queueEndOfStream() {
        inputEnded = true
        if (currentTier in 0..2) {
            tiers[currentTier].queueEndOfStream()
        }
    }

    override fun getOutput(): ByteBuffer {
        return if (currentTier in 0..2) {
            tiers[currentTier].getOutput()
        } else {
            val out = passthroughBuffer
            passthroughBuffer = AudioProcessor.EMPTY_BUFFER
            out
        }
    }

    override fun isEnded(): Boolean {
        return inputEnded && if (currentTier in 0..2) {
            tiers[currentTier].isEnded
        } else {
            !passthroughBuffer.hasRemaining()
        }
    }

    override fun flush() {
        inputEnded = false
        passthroughBuffer = AudioProcessor.EMPTY_BUFFER
        currentTier = activeTier
        for (tier in tiers) {
            tier.flush()
        }
    }

    override fun reset() {
        flush()
        inputFormat = AudioProcessor.AudioFormat.NOT_SET
        reusableBuffer = AudioProcessor.EMPTY_BUFFER
        for (tier in tiers) {
            tier.reset()
        }
    }
}
