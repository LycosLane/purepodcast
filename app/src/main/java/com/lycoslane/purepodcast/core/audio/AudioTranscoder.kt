package com.lycoslane.purepodcast.core.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

object AudioTranscoder {

    private const val TIMEOUT_US = 2_000L

    /** True if [file] is already AAC (mp4a) with a declared or effective bitrate at or below [targetKbps]. */
    fun isAlreadyCompact(file: File, targetKbps: Int): Boolean {
        if (!file.exists() || file.length() <= 0L) return false
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                if (mime != "audio/mp4a-latm") return false
                val bitrate = when {
                    fmt.containsKey(MediaFormat.KEY_BIT_RATE) -> fmt.getInteger(MediaFormat.KEY_BIT_RATE)
                    fmt.containsKey(MediaFormat.KEY_DURATION) -> {
                        val durUs = fmt.getLong(MediaFormat.KEY_DURATION)
                        if (durUs > 0L) ((file.length() * 8L * 1_000_000L) / durUs).toInt() else Int.MAX_VALUE
                    }
                    else -> Int.MAX_VALUE
                }
                return bitrate <= (targetKbps * 1080)
            }
            false
        } catch (_: Exception) {
            false
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** Reads the audio track duration in milliseconds from a local media file, or 0L if unavailable. */
    fun readDurationMs(file: File): Long {
        if (!file.exists() || file.length() <= 0L) return 0L
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                    val durUs = fmt.getLong(MediaFormat.KEY_DURATION)
                    if (durUs > 0L) return durUs / 1_000L
                }
            }
            0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun createAacEncoder(sampleRate: Int, channelCount: Int, targetBitrateKbps: Int): MediaCodec {
        val outputFormat = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            channelCount
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, targetBitrateKbps * 1000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    }

    /**
     * Transcodes [sourceFile] to AAC-LC inside an M4A container at [targetBitrateKbps] (e.g. 96 or 128).
     * Writes to [tempOutputFile]. Returns true if transcoding succeeded and produced a valid smaller file.
     */
    fun transcodeToAacM4a(
        sourceFile: File,
        tempOutputFile: File,
        targetBitrateKbps: Int,
        shouldCancel: () -> Boolean = { false },
        onProgress: (Int) -> Unit = {}
    ): Boolean {
        if (!sourceFile.exists() || sourceFile.length() <= 0L) return false

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        try {
            extractor.setDataSource(sourceFile.absolutePath)
            var audioTrackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    inputFormat = fmt
                    break
                }
            }
            if (audioTrackIndex < 0 || inputFormat == null) return false

            val inputMime = inputFormat.getString(MediaFormat.KEY_MIME).orEmpty()
            if (!inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE) ||
                !inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
            ) return false
            val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (channelCount !in 1..2 || sampleRate <= 0) return false
            val totalDurationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inputFormat.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(0L)
            } else {
                0L
            }
            var lastReportedPct = 0
            onProgress(0)

            extractor.selectTrack(audioTrackIndex)

            decoder = MediaCodec.createDecoderByType(inputMime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            var currentEncRate = sampleRate
            var currentEncChannels = channelCount
            var activeEncoder = createAacEncoder(currentEncRate, currentEncChannels, targetBitrateKbps)
            encoder = activeEncoder

            if (tempOutputFile.exists()) tempOutputFile.delete()
            muxer = MediaMuxer(tempOutputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            var extractorDone = false
            var decoderDone = false
            var encoderDone = false
            var encoderEosSent = false
            var muxerTrackIndex = -1
            var decChannels = channelCount
            var decRate = sampleRate
            var encodedFrames = 0L

            // Keep a simple FIFO of decoded PCM chunks to feed the encoder cleanly
            val pcmQueue = ArrayDeque<ByteArray>()
            val maxQueuedChunks = 32

            while (!encoderDone) {
                if (shouldCancel()) {
                    tempOutputFile.delete()
                    return false
                }

                var madeProgress = false

                // 1. Feed extractor -> decoder (backpressure: pause when encoder is behind)
                while (!extractorDone && pcmQueue.size < maxQueuedChunks) {
                    val inIdx = decoder.dequeueInputBuffer(0L)
                    if (inIdx < 0) break
                    madeProgress = true
                    val buf = decoder.getInputBuffer(inIdx)
                    if (buf != null) {
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            extractorDone = true
                        } else {
                            val pts = extractor.sampleTime.coerceAtLeast(0L)
                            decoder.queueInputBuffer(inIdx, 0, size, pts, 0)
                            extractor.advance()
                        }
                    } else {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0L, 0)
                        break
                    }
                }

                // 2. Drain decoder -> pcmQueue
                while (!decoderDone && pcmQueue.size < maxQueuedChunks) {
                    val outIdx = decoder.dequeueOutputBuffer(decInfo, 0L)
                    if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break
                    } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        madeProgress = true
                        val f = decoder.outputFormat
                        if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) decRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) decChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (decChannels !in 1..2 || decRate <= 0) {
                            tempOutputFile.delete()
                            return false
                        }
                        if (decRate != currentEncRate || decChannels != currentEncChannels) {
                            if (!muxerStarted && encodedFrames == 0L) {
                                runCatching { activeEncoder.stop() }
                                runCatching { activeEncoder.release() }
                                currentEncRate = decRate
                                currentEncChannels = decChannels
                                activeEncoder = createAacEncoder(currentEncRate, currentEncChannels, targetBitrateKbps)
                                encoder = activeEncoder
                            } else {
                                tempOutputFile.delete()
                                return false
                            }
                        }
                    } else if (outIdx >= 0) {
                        madeProgress = true
                        val outBuf = decoder.getOutputBuffer(outIdx)
                        if (outBuf != null && decInfo.size > 0) {
                            outBuf.position(decInfo.offset)
                            outBuf.limit(decInfo.offset + decInfo.size)
                            val chunk = ByteArray(decInfo.size)
                            outBuf.get(chunk)
                            pcmQueue.addLast(chunk)
                        }
                        decoder.releaseOutputBuffer(outIdx, false)
                        if ((decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            decoderDone = true
                        }
                    } else {
                        break
                    }
                }

                // 3. Feed pcmQueue -> encoder (1 AAC frame = 1024 samples per buffer so encoder output pool never saturates)
                var fedBuffersThisTurn = 0
                while ((pcmQueue.isNotEmpty() || decoderDone) && !encoderEosSent && fedBuffersThisTurn < 2) {
                    val encInIdx = activeEncoder.dequeueInputBuffer(0L)
                    if (encInIdx < 0) break
                    madeProgress = true

                    val encInBuf: ByteBuffer? = activeEncoder.getInputBuffer(encInIdx)
                    if (encInBuf == null) {
                        val ptsUs = encodedFrames * 1_000_000L / decRate
                        activeEncoder.queueInputBuffer(encInIdx, 0, 0, ptsUs, 0)
                        break
                    }
                    encInBuf.clear()

                    if (pcmQueue.isNotEmpty()) {
                        val bytesPerFrame = 2 * decChannels
                        val rawCapacity = (encInBuf.remaining() / bytesPerFrame) * bytesPerFrame
                        // Feed at most 1024 samples (1 AAC-LC frame = 4096B stereo / 2048B mono) per input buffer
                        val capacity = minOf(rawCapacity, 1024 * bytesPerFrame)
                        if (capacity <= 0) {
                            val ptsUs = encodedFrames * 1_000_000L / decRate
                            activeEncoder.queueInputBuffer(encInIdx, 0, 0, ptsUs, 0)
                            break
                        }
                        val ptsUs = encodedFrames * 1_000_000L / decRate
                        var totalPut = 0
                        while (pcmQueue.isNotEmpty() && totalPut < capacity) {
                            val chunk = pcmQueue.first()
                            val remainingCap = capacity - totalPut
                            val take = minOf(chunk.size, remainingCap)
                            if (take <= 0) break
                            encInBuf.put(chunk, 0, take)
                            totalPut += take
                            pcmQueue.removeFirst()
                            if (take < chunk.size) {
                                pcmQueue.addFirst(chunk.copyOfRange(take, chunk.size))
                                break
                            }
                        }
                        encodedFrames += (totalPut / bytesPerFrame).toLong()
                        activeEncoder.queueInputBuffer(encInIdx, 0, totalPut, ptsUs, 0)
                        fedBuffersThisTurn++
                        if (totalDurationUs > 0L) {
                            val currentUs = encodedFrames * 1_000_000L / decRate
                            val pct = ((currentUs * 100L) / totalDurationUs).toInt().coerceIn(0, 99)
                            if (pct >= lastReportedPct + 2) {
                                lastReportedPct = pct
                                onProgress(pct)
                            }
                        }
                    } else {
                        val ptsUs = encodedFrames * 1_000_000L / decRate
                        activeEncoder.queueInputBuffer(encInIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        encoderEosSent = true
                        break
                    }
                }

                // 4. Drain encoder -> muxer
                while (true) {
                    val timeoutUs = if (!madeProgress) TIMEOUT_US else 0L
                    val encOutIdx = activeEncoder.dequeueOutputBuffer(encInfo, timeoutUs)
                    if (encOutIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break
                    } else if (encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        madeProgress = true
                        if (!muxerStarted) {
                            muxerTrackIndex = muxer.addTrack(activeEncoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    } else if (encOutIdx >= 0) {
                        madeProgress = true
                        val outBuf = activeEncoder.getOutputBuffer(encOutIdx)
                        if (outBuf != null && (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && encInfo.size > 0 && muxerStarted) {
                            outBuf.position(encInfo.offset)
                            outBuf.limit(encInfo.offset + encInfo.size)
                            muxer.writeSampleData(muxerTrackIndex, outBuf, encInfo)
                        }
                        activeEncoder.releaseOutputBuffer(encOutIdx, false)
                        if ((encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encoderDone = true
                            break
                        }
                    } else {
                        break
                    }
                }
            }

            if (muxerStarted) {
                muxer.stop()
                muxerStarted = false
            }
            val ok = tempOutputFile.exists() && tempOutputFile.length() > 0L
            if (ok) onProgress(100)
            return ok
        } catch (_: Throwable) {
            tempOutputFile.delete()
            return false
        } finally {
            runCatching { if (muxerStarted) muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { extractor.release() }
        }
    }
}
