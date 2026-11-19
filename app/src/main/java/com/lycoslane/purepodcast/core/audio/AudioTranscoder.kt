package com.lycoslane.purepodcast.core.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale

object AudioTranscoder {

    private const val TIMEOUT_US = 2_000L
    private val LOSSLESS_AUDIO_MIMES = setOf(
        "audio/flac",
        "audio/raw",
        "audio/alac",
        "audio/wav",
        "audio/x-wav"
    )

    /**
     * Returns true when an audio track of [mime] at [bitrateBps] is already at or below
     * [targetKbps] so compression will never upscale or waste quality re-encoding.
     */
    internal fun isBitrateAlreadyCompact(mime: String, bitrateBps: Long, targetKbps: Int): Boolean {
        if (targetKbps <= 0) return true
        val normalizedMime = mime.lowercase(Locale.ROOT)
        if (normalizedMime in LOSSLESS_AUDIO_MIMES) return false
        if (bitrateBps <= 0L) return false
        if (normalizedMime == "audio/opus") {
            // Opus achieves higher quality per bit than AAC-LC, so avoid bloating compact Opus streams.
            return bitrateBps <= targetKbps.toLong() * 1350L
        }
        return bitrateBps <= targetKbps.toLong() * 1050L
    }

    /** True if [file] is already at or below [targetKbps] (never upscale any audio format). */
    fun isAlreadyCompact(file: File, targetKbps: Int): Boolean {
        if (!file.exists() || file.length() <= 0L) return false
        if (targetKbps <= 0) return true
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                val bitrateBps = when {
                    fmt.containsKey(MediaFormat.KEY_BIT_RATE) ->
                        fmt.getInteger(MediaFormat.KEY_BIT_RATE).toLong()
                    fmt.containsKey(MediaFormat.KEY_DURATION) -> {
                        val durUs = fmt.getLong(MediaFormat.KEY_DURATION)
                        if (durUs > 0L) (file.length() * 8L * 1_000_000L) / durUs else 0L
                    }
                    else -> 0L
                }
                return isBitrateAlreadyCompact(mime, bitrateBps, targetKbps)
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

    /**
     * Downmixes little-endian 16-bit signed stereo PCM ([stereoPcm]) to mono PCM by averaging L and R.
     */
    internal fun downmixStereoPcm16ToMono(stereoPcm: ByteArray, length: Int = stereoPcm.size): ByteArray {
        val frames = length.coerceAtMost(stereoPcm.size) / 4
        if (frames <= 0) return ByteArray(0)
        val mono = ByteArray(frames * 2)
        var inIdx = 0
        var outIdx = 0
        for (i in 0 until frames) {
            val l = (stereoPcm[inIdx].toInt() and 0xFF) or (stereoPcm[inIdx + 1].toInt() shl 8)
            val r = (stereoPcm[inIdx + 2].toInt() and 0xFF) or (stereoPcm[inIdx + 3].toInt() shl 8)
            val m = (l + r) shr 1
            mono[outIdx] = (m and 0xFF).toByte()
            mono[outIdx + 1] = ((m shr 8) and 0xFF).toByte()
            inIdx += 4
            outIdx += 2
        }
        return mono
    }

    private fun downmixStereoPcm16ToMonoInto(src: ByteArray, srcByteLen: Int, dst: ByteBuffer): Int {
        val frames = srcByteLen.coerceAtMost(src.size) / 4
        var inIdx = 0
        for (i in 0 until frames) {
            val l = (src[inIdx].toInt() and 0xFF) or (src[inIdx + 1].toInt() shl 8)
            val r = (src[inIdx + 2].toInt() and 0xFF) or (src[inIdx + 3].toInt() shl 8)
            val m = (l + r) shr 1
            dst.put((m and 0xFF).toByte())
            dst.put(((m shr 8) and 0xFF).toByte())
            inIdx += 4
        }
        return frames * 2
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
     * Transcodes [sourceFile] to AAC-LC inside an M4A container at [targetBitrateKbps] (e.g. 64, 128, or 192).
     * Target bitrates up to 64 kbps automatically downmix stereo PCM to mono before AAC encoding.
     * Writes to [tempOutputFile]. Returns true if transcoding succeeded and produced a valid file.
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

            val pcmEncoding = if (inputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                inputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }
            val rawPcmBypass = inputMime == MediaFormat.MIMETYPE_AUDIO_RAW &&
                pcmEncoding == AudioFormat.ENCODING_PCM_16BIT

            val activeDecoder: MediaCodec? = if (rawPcmBypass) {
                null
            } else {
                MediaCodec.createDecoderByType(inputMime).apply {
                    configure(inputFormat, null, null, 0)
                    start()
                }
            }
            decoder = activeDecoder
            val rawReadBuffer = if (rawPcmBypass) ByteBuffer.allocateDirect(65_536) else null

            val forceMono = targetBitrateKbps in 1..64
            var currentEncRate = sampleRate
            var currentEncChannels = if (forceMono) 1 else channelCount
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
            var queuedPcmBytes = 0
            val maxQueuedChunks = 32
            val scratchStereo = ByteArray(1024 * 4)

            while (!encoderDone) {
                if (shouldCancel()) {
                    tempOutputFile.delete()
                    return false
                }

                var madeProgress = false

                if (rawPcmBypass && rawReadBuffer != null) {
                    while (!decoderDone && pcmQueue.size < maxQueuedChunks) {
                        rawReadBuffer.clear()
                        val size = extractor.readSampleData(rawReadBuffer, 0)
                        if (size < 0) {
                            extractorDone = true
                            decoderDone = true
                        } else {
                            madeProgress = true
                            if (size > 0) {
                                val chunk = ByteArray(size)
                                rawReadBuffer.position(0)
                                rawReadBuffer.get(chunk, 0, size)
                                pcmQueue.addLast(chunk)
                                queuedPcmBytes += size
                            }
                            extractor.advance()
                        }
                    }
                } else if (activeDecoder != null) {
                    // 1. Feed extractor -> decoder (backpressure: pause when encoder is behind)
                    while (!extractorDone && pcmQueue.size < maxQueuedChunks) {
                        val inIdx = activeDecoder.dequeueInputBuffer(0L)
                        if (inIdx < 0) break
                        madeProgress = true
                        val buf = activeDecoder.getInputBuffer(inIdx)
                        if (buf != null) {
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                activeDecoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                extractorDone = true
                            } else {
                                val pts = extractor.sampleTime.coerceAtLeast(0L)
                                activeDecoder.queueInputBuffer(inIdx, 0, size, pts, 0)
                                extractor.advance()
                            }
                        } else {
                            activeDecoder.queueInputBuffer(inIdx, 0, 0, 0L, 0)
                            break
                        }
                    }

                    // 2. Drain decoder -> pcmQueue
                    while (!decoderDone && pcmQueue.size < maxQueuedChunks) {
                        val outIdx = activeDecoder.dequeueOutputBuffer(decInfo, 0L)
                        if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            break
                        } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            madeProgress = true
                            val f = activeDecoder.outputFormat
                            if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) decRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) decChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (decChannels !in 1..2 || decRate <= 0) {
                                tempOutputFile.delete()
                                return false
                            }
                            val desiredEncChannels = if (forceMono) 1 else decChannels
                            if (decRate != currentEncRate || desiredEncChannels != currentEncChannels) {
                                if (!muxerStarted && encodedFrames == 0L) {
                                    runCatching { activeEncoder.stop() }
                                    runCatching { activeEncoder.release() }
                                    currentEncRate = decRate
                                    currentEncChannels = desiredEncChannels
                                    activeEncoder = createAacEncoder(currentEncRate, currentEncChannels, targetBitrateKbps)
                                    encoder = activeEncoder
                                } else {
                                    tempOutputFile.delete()
                                    return false
                                }
                            }
                        } else if (outIdx >= 0) {
                            madeProgress = true
                            val outBuf = activeDecoder.getOutputBuffer(outIdx)
                            if (outBuf != null && decInfo.size > 0) {
                                outBuf.position(decInfo.offset)
                                outBuf.limit(decInfo.offset + decInfo.size)
                                val chunk = ByteArray(decInfo.size)
                                outBuf.get(chunk)
                                pcmQueue.addLast(chunk)
                                queuedPcmBytes += decInfo.size
                            }
                            activeDecoder.releaseOutputBuffer(outIdx, false)
                            if ((decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                decoderDone = true
                            }
                        } else {
                            break
                        }
                    }
                }

                // 3. Feed pcmQueue -> encoder (1 AAC frame = 1024 samples per buffer so encoder output pool never saturates)
                val srcBytesPerFrame = 2 * decChannels
                val encBytesPerFrame = 2 * currentEncChannels
                val downmixToMono = decChannels == 2 && currentEncChannels == 1
                var fedBuffersThisTurn = 0
                while ((queuedPcmBytes >= srcBytesPerFrame || decoderDone) && !encoderEosSent && fedBuffersThisTurn < 2) {
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

                    val availFrames = queuedPcmBytes / srcBytesPerFrame
                    if (availFrames > 0) {
                        val maxEncFrames = minOf(encInBuf.remaining() / encBytesPerFrame, 1024, availFrames)
                        if (maxEncFrames <= 0) {
                            val ptsUs = encodedFrames * 1_000_000L / decRate
                            activeEncoder.queueInputBuffer(encInIdx, 0, 0, ptsUs, 0)
                            break
                        }
                        val srcTakeTotal = maxEncFrames * srcBytesPerFrame
                        val ptsUs = encodedFrames * 1_000_000L / decRate
                        var srcTaken = 0
                        while (pcmQueue.isNotEmpty() && srcTaken < srcTakeTotal) {
                            val chunk = pcmQueue.first()
                            val take = minOf(chunk.size, srcTakeTotal - srcTaken)
                            if (take <= 0) break
                            if (downmixToMono) {
                                System.arraycopy(chunk, 0, scratchStereo, srcTaken, take)
                            } else {
                                encInBuf.put(chunk, 0, take)
                            }
                            srcTaken += take
                            queuedPcmBytes -= take
                            pcmQueue.removeFirst()
                            if (take < chunk.size) {
                                pcmQueue.addFirst(chunk.copyOfRange(take, chunk.size))
                                break
                            }
                        }
                        val queuedBytes = if (downmixToMono) {
                            downmixStereoPcm16ToMonoInto(scratchStereo, srcTaken, encInBuf)
                        } else {
                            srcTaken
                        }
                        encodedFrames += (srcTaken / srcBytesPerFrame).toLong()
                        activeEncoder.queueInputBuffer(encInIdx, 0, queuedBytes, ptsUs, 0)
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
                        pcmQueue.clear()
                        queuedPcmBytes = 0
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
