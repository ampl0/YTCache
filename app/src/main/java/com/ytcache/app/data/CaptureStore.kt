package com.ytcache.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Base64

data class VideoEntry(
    val videoId: String,
    val title: String,
    val author: String,
    val lengthSeconds: Int,
    val description: String,
    val thumbUrl: String,
    val sourceUrl: String,
    val capturedAt: Long,
    val sizeBytes: Long,
    val streamCount: Int,
    val hasThumb: Boolean,
    val thumbPath: String,
    val resumeMs: Long = 0L,
    /** At least one stream has a usable init segment, so the player can start. */
    val playable: Boolean = true,
    /** Fragments are missing, or the capture was stopped before it finished. */
    val partial: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("videoId", videoId)
        put("title", title)
        put("author", author)
        put("lengthSeconds", lengthSeconds)
        put("description", description)
        put("thumbUrl", thumbUrl)
        put("sourceUrl", sourceUrl)
        put("capturedAt", capturedAt)
        put("sizeBytes", sizeBytes)
        put("streamCount", streamCount)
        put("hasThumb", hasThumb)
        put("thumbPath", thumbPath)
        put("resumeMs", resumeMs)
        put("playable", playable)
        put("partial", partial)
    }
}

data class StreamSummary(
    val videoId: String,
    val streamKey: String,
    val kind: String,
    val mime: String,
    val fragmentCount: Int,
    val bytes: Long,
    val hasInit: Boolean = true,
    /** First fragment number present on disk (normally 0). */
    val firstSeq: Int = 0,
    /** Fragments are missing between firstSeq and the last fragment written. */
    val partial: Boolean = false,
) {
    val playable: Boolean get() = hasInit && fragmentCount > 0

    fun toJson(): JSONObject = JSONObject().apply {
        put("streamKey", streamKey)
        put("kind", kind)
        put("mime", mime)
        put("fragmentCount", fragmentCount)
        put("bytes", bytes)
        put("hasInit", hasInit)
        put("firstSeq", firstSeq)
        put("partial", partial)
        put("playable", playable)
    }
}

/**
 * Owns the on-device layout:
 *
 * YTCache/<videoId>/video.json
 * YTCache/<videoId>/thumbnail.jpg
 * YTCache/<videoId>/capture.json   (present only once a capture started)
 * YTCache/<videoId>/state.json     (resume position)
 * YTCache/<videoId>/<streamKey>/meta.json, 0.bin, 1.bin, ...
 */
class CaptureStore(private val root: File) {

    fun rootDir(): File {
        if (!root.exists()) root.mkdirs()
        return root
    }

    /** Keeps [A-Za-z0-9._-], collapses dots, trims dots, never returns empty. */
    fun sanitize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "unknown"
        val sb = StringBuilder(trimmed.length)
        for (ch in trimmed) {
            val keep = (ch in 'a'..'z') || (ch in 'A'..'Z') || (ch in '0'..'9') || ch == '.' || ch == '_' || ch == '-'
            if (keep) sb.append(ch)
        }
        var out = sb.toString()
        while (out.contains("..")) out = out.replace("..", ".")
        out = out.trim('.')
        if (out.isEmpty()) return "unknown"
        if (out.length > 120) out = out.substring(0, 120)
        return out
    }

    fun videoDir(videoId: String): File = File(rootDir(), sanitize(videoId))

    fun streamDir(videoId: String, streamKey: String): File =
        File(videoDir(videoId), sanitize(streamKey))

    // ---------------------------------------------------------------- write

    /** Merges captured page metadata into video.json. Returns the videoId. */
    fun saveVideoInfo(json: String): String? {
        return try {
            val incoming = JSONObject(json)
            val id = sanitize(incoming.optString("videoId", ""))
            if (id == "unknown") return null
            val dir = videoDir(id)
            if (!dir.exists() && !dir.mkdirs()) return null

            val target = File(dir, "video.json")
            val merged = if (target.exists()) {
                runCatching { JSONObject(target.readText()) }.getOrElse { JSONObject() }
            } else {
                JSONObject()
            }

            fun text(key: String, fallback: String): String {
                val fresh = incoming.optString(key, "").trim()
                if (fresh.isNotEmpty()) return fresh
                return merged.optString(key, "").ifBlank { fallback }
            }

            merged.put("v", 1)
            merged.put("videoId", id)
            merged.put("title", text("title", "Untitled video"))
            merged.put("author", text("author", ""))
            merged.put("description", text("description", ""))
            merged.put("thumbUrl", text("thumbUrl", ""))
            val length = incoming.optInt("lengthSeconds", 0)
            merged.put("lengthSeconds", if (length > 0) length else merged.optInt("lengthSeconds", 0))
            if (!merged.has("capturedAt") || merged.optLong("capturedAt", 0L) <= 0L) {
                merged.put("capturedAt", System.currentTimeMillis())
            }
            if (merged.optString("sourceUrl").isBlank()) {
                merged.put("sourceUrl", "https://www.youtube.com/watch?v=$id")
            }

            writeAtomic(target, merged.toString().toByteArray(Charsets.UTF_8))
            id
        } catch (e: Exception) {
            null
        }
    }

    fun writeChunk(header: ChunkHeader, payload: ByteArray): Boolean {
        if (payload.isEmpty()) return false
        val id = sanitize(header.videoId)
        if (id == "unknown") return false
        val vDir = videoDir(id)
        if (!vDir.exists() && !vDir.mkdirs()) return false
        val sDir = File(vDir, sanitize(header.streamKey))
        if (!sDir.exists() && !sDir.mkdirs()) return false

        val metaFile = File(sDir, "meta.json")
        if (!metaFile.exists()) {
            val meta = JSONObject().apply {
                put("v", 1)
                put("videoId", id)
                put("streamKey", sanitize(header.streamKey))
                put("kind", header.kind)
                put("mime", header.mime)
            }
            runCatching { writeAtomic(metaFile, meta.toString().toByteArray()) }
        }

        val target = File(sDir, "${header.seq}.bin")
        if (target.exists()) return false
        return try {
            // Media fragments are re-readable from the page if a write is lost,
            // and fsync per fragment stalls the WebView's JS bridge thread.
            writeAtomic(target, payload, durable = false)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun writeAtomic(target: File, bytes: ByteArray, durable: Boolean = true) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            if (durable) runCatching { out.fd.sync() }
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IllegalStateException("rename failed for ${target.name}")
        }
    }

    // ----------------------------------------------------------------- read

    fun fragments(videoId: String, streamKey: String): List<File> =
        StreamAssembler.fragments(streamDir(videoId, streamKey))

    fun hasMedia(videoId: String): Boolean = streamSummaries(videoId).any { it.bytes > 0L }

    /** First bytes of a stream's init fragment, for codec/container sniffing. */
    fun initBytes(videoId: String, streamKey: String, max: Int = 64 * 1024): ByteArray {
        val first = fragments(videoId, streamKey).firstOrNull() ?: return ByteArray(0)
        return headBytes(first, max)
    }

    private fun kindOf(streamKey: String): String = when {
        streamKey.endsWith("-audio") -> "audio"
        streamKey.endsWith("-video") -> "video"
        else -> "other"
    }

    fun streamSummaries(videoId: String): List<StreamSummary> {
        val id = sanitize(videoId)
        val subs = videoDir(id).listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return subs.mapNotNull { dir -> summaryOf(id, dir) }
            .sortedWith(compareBy({ StreamPicker.indexOf(it.streamKey) }, { it.streamKey }))
    }

    private fun summaryOf(id: String, dir: File): StreamSummary? {
        val frags = StreamAssembler.fragments(dir)
        if (frags.isEmpty()) return null
        val seqs = frags.mapNotNull { it.nameWithoutExtension.toIntOrNull() }
        if (seqs.isEmpty()) return null
        val firstSeq = seqs.min()
        val maxSeq = seqs.max()
        val present = seqs.toHashSet()
        var contiguous = 0
        var next = firstSeq
        while (present.contains(next)) {
            contiguous++
            next++
        }

        val firstFile = frags.first { it.nameWithoutExtension.toIntOrNull() == firstSeq }
        val head = headBytes(firstFile, 8192)
        val hasInit = firstSeq == 0 && MediaSniffer.isInitSegment(head)
        val meta = runCatching { JSONObject(File(dir, "meta.json").readText()) }.getOrNull()
        var kind = meta?.optString("kind").orEmpty().ifBlank { kindOf(dir.name) }
        if (kind != "video" && kind != "audio") {
            kind = MediaSniffer.trackKind(head) ?: kind
        }
        val bytes = frags.sumOf { it.length() }
        return StreamSummary(
            videoId = id,
            streamKey = dir.name,
            kind = kind,
            mime = meta?.optString("mime").orEmpty(),
            fragmentCount = contiguous,
            bytes = bytes,
            hasInit = hasInit,
            firstSeq = firstSeq,
            partial = contiguous < (maxSeq - firstSeq + 1),
        )
    }

    fun entry(videoId: String): VideoEntry? {
        val id = sanitize(videoId)
        val dir = videoDir(id)
        if (!dir.isDirectory) return null
        val streams = streamSummaries(id)
        val mediaBytes = streams.sumOf { it.bytes }
        // Metadata-only folders (a video was opened but never captured) are not
        // library entries: there is nothing to play.
        if (mediaBytes == 0L) return null
        val meta = runCatching { JSONObject(File(dir, "video.json").readText()) }.getOrNull()
        val thumb = File(dir, "thumbnail.jpg")
        val capturedAt = meta?.optLong("capturedAt", 0L) ?: 0L
        val stopped = File(dir, "capture.json").let { marker ->
            marker.isFile && runCatching { JSONObject(marker.readText()).optBoolean("stopped", false) }
                .getOrDefault(false)
        }
        val resumeMs = runCatching {
            JSONObject(File(dir, "state.json").readText()).optLong("positionMs", 0L)
        }.getOrDefault(0L)
        return VideoEntry(
            videoId = id,
            title = meta?.optString("title").orEmpty().ifBlank { "Untitled video" },
            author = meta?.optString("author").orEmpty(),
            lengthSeconds = meta?.optInt("lengthSeconds", 0) ?: 0,
            description = meta?.optString("description").orEmpty(),
            thumbUrl = meta?.optString("thumbUrl").orEmpty(),
            sourceUrl = meta?.optString("sourceUrl").orEmpty()
                .ifBlank { "https://www.youtube.com/watch?v=$id" },
            capturedAt = if (capturedAt > 0L) capturedAt else dir.lastModified(),
            sizeBytes = mediaBytes,
            streamCount = streams.size,
            hasThumb = thumb.length() > 0,
            thumbPath = if (thumb.length() > 0) thumb.absolutePath else "",
            resumeMs = resumeMs,
            playable = streams.any { it.playable },
            partial = stopped || streams.any { it.partial } || streams.any { it.firstSeq > 0 },
        )
    }

    fun listVideos(): List<VideoEntry> {
        val dirs = rootDir().listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { entry(it.name) }.sortedByDescending { it.capturedAt }
    }

    fun totalBytes(): Long = listVideos().sumOf { it.sizeBytes }

    /**
     * Keeps metadata/thumbnail and throws away the media so the same video can
     * be cached again from the start (used for incomplete captures).
     */
    fun resetForRecapture(videoId: String): Boolean {
        val dir = videoDir(videoId)
        if (!dir.isDirectory) return false
        var ok = true
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                if (!child.deleteRecursively()) ok = false
            } else if (child.name == "capture.json" || child.name == "state.json") {
                child.delete()
            }
        }
        return ok
    }

    /** Marks a capture as stopped before the page finished feeding it. */
    fun markStopped(videoId: String) {
        val dir = videoDir(videoId)
        if (!dir.isDirectory) return
        val marker = JSONObject().apply {
            put("stopped", true)
            put("stoppedAt", System.currentTimeMillis())
        }
        runCatching { writeAtomic(File(dir, "capture.json"), marker.toString().toByteArray()) }
    }

    fun clearStopped(videoId: String) {
        val marker = File(videoDir(videoId), "capture.json")
        if (marker.isFile) marker.delete()
    }

    fun pageJson(videoId: String, streamKey: String, offset: Int, count: Int): JSONObject {
        val safeCount = count.coerceIn(1, 16)
        val all = fragments(videoId, streamKey)
        val items = JSONArray()
        var index = offset.coerceAtLeast(0)
        var emitted = 0
        while (index < all.size && emitted < safeCount) {
            val bytes = runCatching { all[index].readBytes() }.getOrNull()
            if (bytes != null) {
                items.put(Base64.getEncoder().encodeToString(bytes))
            }
            index++
            emitted++
        }
        return JSONObject().apply {
            put("videoId", sanitize(videoId))
            put("streamKey", sanitize(streamKey))
            put("offset", offset)
            put("next", index)
            put("total", all.size)
            put("done", index >= all.size)
            put("items", items)
        }
    }

    /** Resolves a request path under the storage root, refusing traversal. */
    fun resolve(relative: String): File? {
        val cleaned = relative.trimStart('/').replace('\\', '/')
        if (cleaned.isEmpty() || cleaned.contains("..")) return null
        val candidate = File(rootDir(), cleaned)
        val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        val rootCanonical = runCatching { rootDir().canonicalPath }.getOrNull() ?: return null
        if (canonical.path != rootCanonical && !canonical.path.startsWith(rootCanonical + File.separator)) {
            return null
        }
        if (!canonical.isFile) return null
        return canonical
    }

    private fun headBytes(file: File, max: Int): ByteArray {
        val size = minOf(file.length(), max.toLong()).toInt()
        if (size <= 0) return ByteArray(0)
        return try {
            file.inputStream().use { input ->
                val buffer = ByteArray(size)
                var read = 0
                while (read < size) {
                    val count = input.read(buffer, read, size - read)
                    if (count <= 0) break
                    read += count
                }
                if (read == size) buffer else buffer.copyOf(read)
            }
        } catch (e: Exception) {
            ByteArray(0)
        }
    }

    fun readResume(videoId: String): Long {
        val file = File(videoDir(videoId), "state.json")
        if (!file.isFile) return 0L
        return runCatching { JSONObject(file.readText()).optLong("positionMs", 0L) }.getOrDefault(0L)
    }

    fun writeResume(videoId: String, positionMs: Long) {
        val dir = videoDir(videoId)
        if (!dir.isDirectory) return
        val state = runCatching { JSONObject(File(dir, "state.json").readText()) }.getOrElse { JSONObject() }
        state.put("positionMs", positionMs.coerceAtLeast(0L))
        state.put("updatedAt", System.currentTimeMillis())
        runCatching { writeAtomic(File(dir, "state.json"), state.toString().toByteArray()) }
    }

    fun delete(videoId: String): Boolean {
        val dir = videoDir(videoId)
        if (!dir.isDirectory) return false
        return dir.deleteRecursively()
    }
}
