package dev.meshaid.app.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File

/**
 * Re-encodes a captured video to HEVC at a starved bitrate and capped duration so a whole
 * clip fits the control lane's 1 MiB blob budget (MeshNode.MAX_AUTO_FETCH_BYTES). Resolution
 * is left alone — scaling it down would need a GL pass to resample the decoder's output
 * before re-encoding, which is a lot of fragile code for a marginal size win once bitrate is
 * already this starved. Blocky video over the mesh beats no video when the alternative is no
 * connectivity at all — same tradeoff bitchat-style disaster tools make everywhere else.
 *
 * Standard decode-into-encoder's-input-surface transcode pipeline: the decoder writes
 * directly onto the encoder's input Surface, so there's no manual YUV buffer handling here.
 */
object VideoTranscoder {
    private const val MAX_DURATION_US = 8_000_000L // 8s
    private const val TARGET_BITRATE = 350_000 // 350 kbps — ~350 KB for the full 8s clip
    private const val FRAME_RATE = 20
    private const val I_FRAME_INTERVAL = 2
    private const val TIMEOUT_US = 10_000L

    // The per-call dequeue timeout above bounds one poll, not the whole job — nothing
    // previously stopped `while (!outputDone)` from spinning forever if the encoder never
    // signals end-of-stream (a real, device-specific MediaCodec risk, not hypothetical).
    // That's indistinguishable from the app doing nothing: no crash, no error, no video —
    // just silence. Generous relative to the 8s cap, but bounded.
    private const val MAX_TRANSCODE_WALL_MS = 45_000L

    /** Throws if the source has no video track or the platform lacks an HEVC encoder. */
    fun transcode(context: Context, uri: Uri, cacheDir: File): ByteArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: error("no video track in $uri")
        val format = extractor.getTrackFormat(trackIndex)
        extractor.selectTrack(trackIndex)

        val width = format.getInteger(MediaFormat.KEY_WIDTH)
        val height = format.getInteger(MediaFormat.KEY_HEIGHT)
        val sourceMime = format.getString(MediaFormat.KEY_MIME)!!

        val outputFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, TARGET_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
        encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()

        val decoder = MediaCodec.createDecoderByType(sourceMime)
        decoder.configure(format, inputSurface, null, 0)
        decoder.start()

        val outFile = File.createTempFile("ping-video-", ".mp4", cacheDir)
        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerTrack = -1
        var muxerStarted = false

        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        val deadlineMs = System.currentTimeMillis() + MAX_TRANSCODE_WALL_MS

        try {
            while (!outputDone) {
                check(System.currentTimeMillis() < deadlineMs) {
                    "transcode timed out after ${MAX_TRANSCODE_WALL_MS}ms — encoder never finished"
                }
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0 || extractor.sampleTime > MAX_DURATION_US) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                var draining = true
                while (draining) {
                    val outIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    if (outIndex >= 0) {
                        val eos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        decoder.releaseOutputBuffer(outIndex, bufferInfo.size > 0)
                        if (eos) {
                            encoder.signalEndOfInputStream()
                            draining = false
                        }
                    } else {
                        draining = false
                    }

                    // Drain the encoder alongside the decoder — its buffer queue can otherwise
                    // fill and stall the shared surface.
                    var encoderDraining = true
                    while (encoderDraining) {
                        val encOutIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                        when {
                            encOutIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                muxerTrack = muxer.addTrack(encoder.outputFormat)
                                muxer.start()
                                muxerStarted = true
                            }
                            encOutIndex >= 0 -> {
                                val encodedData = encoder.getOutputBuffer(encOutIndex)!!
                                if (bufferInfo.size > 0 && muxerStarted) {
                                    encodedData.position(bufferInfo.offset)
                                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                    muxer.writeSampleData(muxerTrack, encodedData, bufferInfo)
                                }
                                val encEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                                encoder.releaseOutputBuffer(encOutIndex, false)
                                if (encEos) {
                                    outputDone = true
                                    encoderDraining = false
                                }
                            }
                            else -> encoderDraining = false
                        }
                    }
                }
            }
        } finally {
            runCatching { decoder.stop() }
            decoder.release()
            runCatching { encoder.stop() }
            encoder.release()
            inputSurface.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
            extractor.release()
        }

        val bytes = outFile.readBytes()
        outFile.delete()
        return bytes
    }
}
