package com.ytcache.app

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.ytcache.app.data.CaptureStore
import com.ytcache.app.data.MediaSniffer
import com.ytcache.app.data.MuxFormat
import com.ytcache.app.data.MuxPlanner
import com.ytcache.app.data.StreamAssembler
import com.ytcache.app.data.StreamPicker
import com.ytcache.app.data.StreamSummary
import com.ytcache.app.data.VideoEntry
import com.ytcache.app.util.Format
import java.io.File

/**
 * Export prefers a single file that contains both tracks: MP4 when the codecs
 * are MP4-legal (H.264/H.265/AV1 + AAC), WebM when they are WebM-legal
 * (VP8/VP9/AV1 + Opus/Vorbis). Muxing copies samples, so it is lossless and
 * quick. Only when no container can legally hold both tracks does it fall back
 * to exporting the video track alone, and the message says exactly why.
 */
object Exporter {

    data class Outcome(val ok: Boolean, val message: String)

    fun export(context: Context, store: CaptureStore, videoId: String, kind: String): Outcome {
        val entry = store.entry(videoId) ?: return Outcome(false, "Capture not found")
        val streams = store.streamSummaries(videoId)
        val (videoStream, audioStream) = StreamPicker.pick(streams)

        val staging = File(context.cacheDir, "export").apply { mkdirs() }
        staging.listFiles()?.forEach { it.delete() }

        if (kind == "audio") {
            val audio = audioStream ?: return Outcome(false, "This capture has no audio track")
            return exportAudio(context, store, entry, audio, staging)
        }

        val video = videoStream
        if (video == null) {
            return if (audioStream != null) {
                exportAudio(context, store, entry, audioStream, staging)
            } else {
                Outcome(false, "This capture has no playable track")
            }
        }

        if (audioStream != null) {
            val combined = exportCombined(context, store, entry, video, audioStream, staging)
            if (combined != null) return combined
        }

        return exportTrack(
            context = context,
            store = store,
            entry = entry,
            stream = video,
            staging = staging,
            audioOnly = false,
            note = if (audioStream == null) {
                "no audio track was captured"
            } else {
                "this device cannot mux these codecs into one container"
            },
        )
    }

    // ------------------------------------------------------------- combined

    private fun exportCombined(
        context: Context,
        store: CaptureStore,
        entry: VideoEntry,
        video: StreamSummary,
        audio: StreamSummary,
        staging: File,
    ): Outcome? {
        val videoContainer = StreamAssembler.containerOf(store, entry.videoId, video.streamKey)
        val audioContainer = StreamAssembler.containerOf(store, entry.videoId, audio.streamKey)
        val videoCodec = MediaSniffer.codec(
            "video", store.initBytes(entry.videoId, video.streamKey), video.mime,
        )
        val audioCodec = MediaSniffer.codec(
            "audio", store.initBytes(entry.videoId, audio.streamKey), audio.mime,
        )

        val plan = MuxPlanner.choose(videoContainer, videoCodec, audioContainer, audioCodec)
        if (plan == MuxFormat.NONE) {
            Log.d(TAG, "no legal container for $videoCodec/$videoContainer + $audioCodec/$audioContainer")
            return null
        }

        val extension = MuxPlanner.extension(plan)
        val name = Format.fileName(entry.title, entry.videoId) + "." + extension
        val output = File(staging, name)
        val videoTmp = File(staging, "video.tmp")
        val audioTmp = File(staging, "audio.tmp")
        return try {
            if (StreamAssembler.concat(store, entry.videoId, video.streamKey, videoTmp) <= 0L) {
                return null
            }
            if (StreamAssembler.concat(store, entry.videoId, audio.streamKey, audioTmp) <= 0L) {
                return null
            }
            MediaRemuxer.remux(videoTmp, audioTmp, output, plan)
            val mime = if (plan == MuxFormat.WEBM) "video/webm" else "video/mp4"
            val published = publish(context, output, name, mime, audio = false)
            if (!published.ok) {
                published
            } else {
                Outcome(
                    true,
                    "Exported ${MuxPlanner.describe(plan)} with sound (${videoCodec.uppercase()}+" +
                        "${audioCodec.uppercase()}) · ${published.message}",
                )
            }
        } catch (e: Exception) {
            Log.d(TAG, "combined export failed: ${e.message}")
            null
        } finally {
            videoTmp.delete()
            audioTmp.delete()
            output.delete()
        }
    }

    // ------------------------------------------------------------ one track

    private fun exportAudio(
        context: Context,
        store: CaptureStore,
        entry: VideoEntry,
        stream: StreamSummary,
        staging: File,
    ): Outcome {
        val container = StreamAssembler.containerOf(store, entry.videoId, stream.streamKey)
        val webm = container == "webm"
        val extension = if (webm) "webm" else "m4a"
        val name = Format.fileName(entry.title, entry.videoId) + " (audio)." + extension
        val mime = if (webm) "audio/webm" else "audio/mp4"
        return writeTrack(context, store, entry, stream, staging, name, mime, audio = true)
    }

    private fun exportTrack(
        context: Context,
        store: CaptureStore,
        entry: VideoEntry,
        stream: StreamSummary,
        staging: File,
        audioOnly: Boolean,
        note: String,
    ): Outcome {
        val container = StreamAssembler.containerOf(store, entry.videoId, stream.streamKey)
        val webm = container == "webm"
        val extension = if (webm) "webm" else "mp4"
        val suffix = if (audioOnly) " (audio)" else ""
        val name = Format.fileName(entry.title, entry.videoId) + suffix + "." + extension
        val mime = when {
            audioOnly && webm -> "audio/webm"
            audioOnly -> "audio/mp4"
            webm -> "video/webm"
            else -> "video/mp4"
        }
        val outcome = writeTrack(context, store, entry, stream, staging, name, mime, audioOnly)
        if (!outcome.ok) return outcome
        return Outcome(true, "${outcome.message} (silent video: $note)")
    }

    private fun writeTrack(
        context: Context,
        store: CaptureStore,
        entry: VideoEntry,
        stream: StreamSummary,
        staging: File,
        name: String,
        mime: String,
        audio: Boolean,
    ): Outcome {
        val staged = File(staging, name)
        val bytes = try {
            StreamAssembler.concat(store, entry.videoId, stream.streamKey, staged)
        } catch (e: Exception) {
            return Outcome(false, "Export failed: ${e.message}")
        }
        if (bytes <= 0L) {
            staged.delete()
            return Outcome(false, "Nothing to export")
        }
        val outcome = publish(context, staged, name, mime, audio)
        staged.delete()
        return outcome
    }

    // --------------------------------------------------------------- publish

    private fun publish(
        context: Context,
        file: File,
        name: String,
        mime: String,
        audio: Boolean,
    ): Outcome {
        val bucket = if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = if (audio) {
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$bucket/YTCache")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(collection, values)
                    ?: return Outcome(false, "Could not create a media entry")
                resolver.openOutputStream(uri)?.use { sink ->
                    file.inputStream().use { it.copyTo(sink, 128 * 1024) }
                } ?: return Outcome(false, "Could not open the output stream")
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
                Outcome(true, "saved to $bucket/YTCache")
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(bucket), "YTCache")
                if (!dir.exists()) dir.mkdirs()
                val target = File(dir, name)
                file.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
                Outcome(true, "saved to ${target.absolutePath}")
            }
        } catch (e: Exception) {
            Log.d(TAG, "public export failed: ${e.message}")
            fallback(context, file, name)
        }
    }

    private fun fallback(context: Context, file: File, name: String): Outcome = try {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "Export")
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, name)
        file.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        Outcome(true, "saved to app storage: ${target.absolutePath}")
    } catch (e: Exception) {
        Outcome(false, "Export failed: ${e.message}")
    }

    private const val TAG = "YTCacheExport"
}
