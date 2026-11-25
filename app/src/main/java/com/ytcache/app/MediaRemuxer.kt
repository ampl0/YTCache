package com.ytcache.app

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.ytcache.app.data.MuxFormat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Puts the captured video and audio tracks into one container without touching
 * a single frame: MediaExtractor reads the samples out of the concatenated
 * (fragmented) captures and MediaMuxer writes a fresh index. No re-encode means
 * export is fast and lossless, and the codec must already be legal for the
 * target container (see [com.ytcache.app.data.MuxPlanner]).
 */
object MediaRemuxer {

    class RemuxException(message: String) : Exception(message)

    data class Report(val tracks: Int, val durationUs: Long)

    fun remux(video: File, audio: File?, out: File, format: MuxFormat): Report {
        if (format == MuxFormat.NONE) throw RemuxException("This codec pair cannot be muxed")
        val muxerFormat = if (format == MuxFormat.WEBM) {
            MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
        } else {
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        }

        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        var sink: RandomAccessFile? = null
        try {
            videoExtractor = MediaExtractor().apply { setDataSource(video.absolutePath) }
            val videoTrack = firstTrack(videoExtractor, "video/")
            if (videoTrack < 0) throw RemuxException("No video track in this capture")

            audioExtractor = audio?.let { file ->
                MediaExtractor().apply { setDataSource(file.absolutePath) }
            }
            val audioTrack = audioExtractor?.let { firstTrack(it, "audio/") } ?: -1
            if (audio != null && audioTrack < 0) throw RemuxException("No audio track in this capture")

            val videoFormat = videoExtractor.getTrackFormat(videoTrack)
            val audioFormat = audioExtractor?.getTrackFormat(audioTrack)

            // Both tracks keep their relative timing; the earliest sample becomes 0.
            val baseUs = minOf(
                firstSampleTime(videoExtractor, videoTrack),
                if (audioExtractor != null) firstSampleTime(audioExtractor, audioTrack) else Long.MAX_VALUE,
            ).let { if (it == Long.MAX_VALUE || it < 0L) 0L else it }

            // The WEBM muxer requires a read-write descriptor, not a plain
            // output stream, so both containers go through the same handle.
            sink = RandomAccessFile(out, "rw").apply { setLength(0) }
            muxer = MediaMuxer(sink.fd, muxerFormat)
            val videoOut = muxer.addTrack(videoFormat)
            val audioOut = if (audioFormat != null) muxer.addTrack(audioFormat) else -1
            muxer.start()

            copyTrack(videoExtractor, videoTrack, muxer, videoOut, baseUs)
            if (audioExtractor != null && audioOut >= 0) {
                copyTrack(audioExtractor, audioTrack, muxer, audioOut, baseUs)
            }

            muxer.stop()
            muxer.release()
            muxer = null
            sink.close()
            sink = null

            return verify(out, if (audioFormat != null) 2 else 1)
        } catch (e: RemuxException) {
            out.delete()
            throw e
        } catch (e: Exception) {
            out.delete()
            throw RemuxException(e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { videoExtractor?.release() }
            runCatching { audioExtractor?.release() }
            runCatching { muxer?.release() }
            runCatching { sink?.close() }
        }
    }

    fun firstTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith(mimePrefix)) return i
        }
        return -1
    }

    private fun firstSampleTime(extractor: MediaExtractor, track: Int): Long {
        extractor.selectTrack(track)
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        val buffer = ByteBuffer.allocateDirect(4 * 1024 * 1024)
        val size = runCatching { extractor.readSampleData(buffer, 0) }.getOrDefault(-1)
        val time = if (size >= 0) extractor.sampleTime else 0L
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        return time
    }

    private fun copyTrack(
        extractor: MediaExtractor,
        track: Int,
        muxer: MediaMuxer,
        outTrack: Int,
        baseUs: Long,
    ) {
        extractor.selectTrack(track)
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        val format = extractor.getTrackFormat(track)
        var capacity = runCatching {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        }.getOrDefault(0).coerceAtLeast(1024 * 1024)
        var buffer = ByteBuffer.allocateDirect(capacity)
        val info = MediaCodec.BufferInfo()

        while (true) {
            val size = try {
                extractor.readSampleData(buffer, 0)
            } catch (e: IllegalArgumentException) {
                // The sample is larger than our buffer: grow and retry once.
                capacity *= 2
                buffer = ByteBuffer.allocateDirect(capacity)
                extractor.readSampleData(buffer, 0)
            }
            if (size < 0) break

            val flags = extractor.sampleFlags
            if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                val time = extractor.sampleTime - baseUs
                if (time >= 0L && size > 0) {
                    info.set(0, size, time, flags)
                    muxer.writeSampleData(outTrack, buffer, info)
                }
            }
            if (!extractor.advance()) break
        }
    }

    private fun verify(out: File, expectedTracks: Int): Report {
        if (!out.isFile || out.length() <= 0L) throw RemuxException("The muxer wrote an empty file")
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(out.absolutePath)
            val tracks = extractor.trackCount
            if (tracks < expectedTracks) {
                throw RemuxException("Only $tracks of $expectedTracks tracks survived muxing")
            }
            val duration = (0 until tracks)
                .mapNotNull { index ->
                    runCatching {
                        extractor.getTrackFormat(index).getLong(MediaFormat.KEY_DURATION)
                    }.getOrNull()
                }
                .maxOrNull() ?: 0L
            return Report(tracks, duration)
        } finally {
            runCatching { extractor.release() }
        }
    }
}
