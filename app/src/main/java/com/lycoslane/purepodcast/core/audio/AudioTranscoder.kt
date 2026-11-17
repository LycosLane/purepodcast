package com.lycoslane.purepodcast.core.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

object AudioTranscoder {

    private const val TIMEOUT_US = 10_000L

    /**
     * Transcodes [sourceFile] to AAC-LC inside an M4A container at [targetBitrateKbps] (e.g. 96 or 128).
     * Writes to [tempOutputFile]. Returns true if transcoding succeeded and produced a valid smaller file.
     */
    fun transcodeToAacM4a(
        sourceFile: File,
        tempOutputFile: File,
        targetBitrateKbps: Int,
        shouldCancel: () -> Boolean = { false }
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
            val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)

            extractor.selectTrack(audioTrackIndex)

            decoder = MediaCodec.createDecoderByType(inputMime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val outputFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                sampleRate,
                channelCount
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, targetBitrateKbps * 1000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            if (tempOutputFile.exists()) tempOutputFile.delete()
            muxer = MediaMuxer(tempOutputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            var extractorDone = false
            var decoderDone = false
            var encoderDone = false
            var muxerTrackIndex = -1

            // Keep a simple FIFO of decoded PCM chunks to feed the encoder cleanly
            val pcmQueue = ArrayDeque<Pair<ByteArray, Long>>()

            while (!encoderDone) {
                if (shouldCancel()) {
                    return false
                }

                // 1. Feed extractor -> decoder
                if (!extractorDone) {
                    val inIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
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
                        }
                    }
                }

                // 2. Drain decoder -> pcmQueue
                if (!decoderDone) {
                    val outIdx = decoder.dequeueOutputBuffer(decInfo, TIMEOUT_US)
                    if (outIdx >= 0) {
                        val outBuf = decoder.getOutputBuffer(outIdx)
                        if (outBuf != null && decInfo.size > 0) {
                            outBuf.position(decInfo.offset)
                            outBuf.limit(decInfo.offset + decInfo.size)
                            val chunk = ByteArray(decInfo.size)
                            outBuf.get(chunk)
                            pcmQueue.addLast(chunk to decInfo.presentationTimeUs)
                        }
                        decoder.releaseOutputBuffer(outIdx, false)
                        if ((decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            decoderDone = true
                        }
                    }
                }

                // 3. Feed pcmQueue -> encoder
                while (pcmQueue.isNotEmpty() || decoderDone) {
                    val encInIdx = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (encInIdx < 0) break

                    val encInBuf: ByteBuffer = encoder.getInputBuffer(encInIdx) ?: break
                    encInBuf.clear()

                    if (pcmQueue.isNotEmpty()) {
                        val (chunk, pts) = pcmQueue.first()
                        if (chunk.size <= encInBuf.remaining()) {
                            pcmQueue.removeFirst()
                            encInBuf.put(chunk)
                            encoder.queueInputBuffer(encInIdx, 0, chunk.size, pts, 0)
                        } else {
                            // Split chunk to fit encoder input buffer
                            val take = encInBuf.remaining()
                            encInBuf.put(chunk, 0, take)
                            val remaining = chunk.copyOfRange(take, chunk.size)
                            pcmQueue.removeFirst()
                            pcmQueue.addFirst(remaining to pts)
                            encoder.queueInputBuffer(encInIdx, 0, take, pts, 0)
                        }
                    } else if (decoderDone) {
                        encoder.queueInputBuffer(encInIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        break
                    } else {
                        break
                    }
                }

                // 4. Drain encoder -> muxer
                while (true) {
                    val encOutIdx = encoder.dequeueOutputBuffer(encInfo, TIMEOUT_US)
                    if (encOutIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break
                    } else if (encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxerStarted) {
                            muxerTrackIndex = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    } else if (encOutIdx >= 0) {
                        val outBuf = encoder.getOutputBuffer(encOutIdx)
                        if (outBuf != null && (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && encInfo.size > 0 && muxerStarted) {
                            outBuf.position(encInfo.offset)
                            outBuf.limit(encInfo.offset + encInfo.size)
                            muxer.writeSampleData(muxerTrackIndex, outBuf, encInfo)
                        }
                        encoder.releaseOutputBuffer(encOutIdx, false)
                        if ((encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encoderDone = true
                            break
                        }
                    }
                }
            }

            if (muxerStarted) {
                muxer.stop()
                muxerStarted = false
            }
            return tempOutputFile.exists() && tempOutputFile.length() > 0L
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
