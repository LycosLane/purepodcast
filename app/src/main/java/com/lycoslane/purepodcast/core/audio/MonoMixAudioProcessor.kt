package com.lycoslane.purepodcast.core.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Always-active stereo-to-mono AudioProcessor so switching mono modes (L+R, Smart, L, R)
 * or toggling mono off takes effect immediately mid-playback without AudioTrack reconfiguration.
 */
@UnstableApi
class MonoMixAudioProcessor : AudioProcessor {

    @Volatile
    var activeMode: Int = AudioMonoMode.OFF
        private set

    private var inputFormat: AudioProcessor.AudioFormat = AudioProcessor.AudioFormat.NOT_SET
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var reusableBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var sampleScratch: ShortArray = ShortArray(0)
    private var inputEnded: Boolean = false

    private var envL: Double = 0.0
    private var envR: Double = 0.0
    private var envCross: Double = 0.0
    private var currentWeightL: Float = 0.5f

    fun setMode(mode: Int) {
        activeMode = AudioMonoMode.normalizeConcrete(mode)
    }

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        inputFormat = inputAudioFormat
        if (inputAudioFormat == AudioProcessor.AudioFormat.NOT_SET) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        resetEnvelopes()
        return inputAudioFormat
    }

    override fun isActive(): Boolean = inputFormat != AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return

        val mode = activeMode
        val shouldProcess = mode != AudioMonoMode.OFF &&
            inputFormat.encoding == C.ENCODING_PCM_16BIT &&
            inputFormat.channelCount == 2

        if (!shouldProcess) {
            ensureBufferCapacity(remaining)
            reusableBuffer.put(inputBuffer)
            reusableBuffer.flip()
            outputBuffer = reusableBuffer
            return
        }

        val frameCount = remaining / 4
        val byteLen = frameCount * 4
        if (frameCount <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }

        ensureBufferCapacity(byteLen)
        val neededShorts = frameCount * 2
        if (sampleScratch.size < neededShorts) {
            sampleScratch = ShortArray(neededShorts)
        }

        val shortView = inputBuffer.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
        shortView.get(sampleScratch, 0, neededShorts)
        inputBuffer.position(inputBuffer.position() + byteLen)

        processStereoPcm16(
            samples = sampleScratch,
            frameCount = frameCount,
            sampleRate = inputFormat.sampleRate,
            mode = mode
        )

        val outShortView = reusableBuffer.asShortBuffer()
        outShortView.put(sampleScratch, 0, neededShorts)
        reusableBuffer.limit(byteLen)
        reusableBuffer.position(0)
        outputBuffer = reusableBuffer
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val out = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return out
    }

    override fun isEnded(): Boolean = inputEnded && !outputBuffer.hasRemaining()

    override fun flush() {
        inputEnded = false
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        resetEnvelopes()
    }

    override fun reset() {
        flush()
        inputFormat = AudioProcessor.AudioFormat.NOT_SET
        reusableBuffer = AudioProcessor.EMPTY_BUFFER
        sampleScratch = ShortArray(0)
    }

    private fun resetEnvelopes() {
        envL = 0.0
        envR = 0.0
        envCross = 0.0
        currentWeightL = 0.5f
    }

    private fun ensureBufferCapacity(capacityBytes: Int) {
        if (reusableBuffer.capacity() < capacityBytes) {
            reusableBuffer = ByteBuffer.allocateDirect(capacityBytes).order(ByteOrder.nativeOrder())
        } else {
            reusableBuffer.clear()
        }
    }

    internal fun processStereoPcm16(
        samples: ShortArray,
        frameCount: Int,
        sampleRate: Int,
        mode: Int
    ) {
        if (frameCount <= 0 || mode == AudioMonoMode.OFF) return
        val totalShorts = frameCount * 2
        when (mode) {
            AudioMonoMode.MIX -> {
                var i = 0
                while (i < totalShorts) {
                    val m = ((samples[i].toInt() + samples[i + 1].toInt()) shr 1).toShort()
                    samples[i] = m
                    samples[i + 1] = m
                    i += 2
                }
            }
            AudioMonoMode.LEFT -> {
                var i = 0
                while (i < totalShorts) {
                    val l = samples[i]
                    samples[i + 1] = l
                    i += 2
                }
            }
            AudioMonoMode.RIGHT -> {
                var i = 0
                while (i < totalShorts) {
                    val r = samples[i + 1]
                    samples[i] = r
                    i += 2
                }
            }
            AudioMonoMode.AUTO -> {
                processAutoSmart(samples, frameCount, sampleRate)
            }
        }
    }

    private fun processAutoSmart(samples: ShortArray, frameCount: Int, sampleRate: Int) {
        val safeRate = sampleRate.coerceAtLeast(8_000).toDouble()
        var frameOffset = 0
        while (frameOffset < frameCount) {
            val count = minOf(BLOCK_FRAMES, frameCount - frameOffset)
            var sumL2 = 0.0
            var sumR2 = 0.0
            var sumCross = 0.0
            var idx = frameOffset * 2
            val endIdx = idx + count * 2
            while (idx < endIdx) {
                val l = samples[idx].toDouble()
                val r = samples[idx + 1].toDouble()
                sumL2 += l * l
                sumR2 += r * r
                sumCross += l * r
                idx += 2
            }

            val blockEL = sumL2 / count
            val blockER = sumR2 / count
            val blockCross = sumCross / count

            val envAlpha = (1.0 - exp(-count / (0.030 * safeRate))).coerceIn(0.02, 0.5)
            envL += envAlpha * (blockEL - envL)
            envR += envAlpha * (blockER - envR)
            envCross += envAlpha * (blockCross - envCross)

            val geom = sqrt(envL * envR)
            val targetL = when {
                envL + envR < 16.0 -> 0.5f
                geom > 1.0 && envCross < -0.4 * geom -> if (envL >= envR) 1f else 0f
                else -> (envL / (envL + envR)).toFloat().coerceIn(0f, 1f)
            }

            val weightAlpha = (1.0 - exp(-count / (0.025 * safeRate))).toFloat().coerceIn(0.02f, 0.5f)
            val startWeightL = currentWeightL
            val endWeightL = startWeightL + weightAlpha * (targetL - startWeightL)
            val step = (endWeightL - startWeightL) / count

            idx = frameOffset * 2
            for (s in 0 until count) {
                val wL = startWeightL + step * s
                val wR = 1f - wL
                val l = samples[idx].toFloat()
                val r = samples[idx + 1].toFloat()
                val m = (l * wL + r * wR)
                    .roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
                samples[idx] = m
                samples[idx + 1] = m
                idx += 2
            }

            currentWeightL = endWeightL
            frameOffset += count
        }
    }

    private companion object {
        const val BLOCK_FRAMES = 128
    }
}
