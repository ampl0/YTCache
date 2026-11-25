package com.ytcache.app.data

/**
 * The mime recorded by the page can disagree with the bytes on disk (a stream
 * that says webm/vp09 while the init segment is a fragmented MP4). Playing with a
 * wrong mime is the classic "no decodable streams" failure, so the container is
 * sniffed from the init bytes and a short candidate list is produced for MSE.
 */
object MediaSniffer {

    data class Info(val container: String, val codecs: List<String>)

    private val FOURCC = listOf(
        // ISO-BMFF sample entry fourccs
        "av01" to "av01.0.05M.08",
        "vp09" to "vp09.00.10.08",
        "vp08" to "vp08.00.10.08",
        "avc1" to "avc1.42E01E",
        "avc3" to "avc1.42E01E",
        "hvc1" to "hvc1.1.6.L93.B0",
        "hev1" to "hev1.1.6.L93.B0",
        "mp4a" to "mp4a.40.2",
        "Opus" to "opus",
        "opus" to "opus",
        "vorbis" to "vorbis",
        // Matroska/WebM CodecIDs
        "V_AV1" to "av01.0.05M.08",
        "V_VP9" to "vp09.00.10.08",
        "V_VP8" to "vp08.00.10.08",
        "A_OPUS" to "opus",
        "A_VORBIS" to "vorbis",
    )

    private val VIDEO_CODECS = listOf(
        "V_AV1" to "av1",
        "av01" to "av1",
        "V_VP9" to "vp9",
        "vp09" to "vp9",
        "V_VP8" to "vp8",
        "vp08" to "vp8",
        "avc1" to "avc",
        "avc3" to "avc",
        "hvc1" to "hevc",
        "hev1" to "hevc",
    )

    private val AUDIO_CODECS = listOf(
        "A_OPUS" to "opus",
        "Opus" to "opus",
        "opus" to "opus",
        "A_VORBIS" to "vorbis",
        "vorbis" to "vorbis",
        "mp4a" to "aac",
    )

    fun container(bytes: ByteArray): String {
        if (bytes.size >= 4 &&
            bytes[0] == 0x1A.toByte() && bytes[1] == 0x45.toByte() &&
            bytes[2] == 0xDF.toByte() && bytes[3] == 0xA3.toByte()
        ) return "webm"
        if (bytes.size >= 8 &&
            bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte()
        ) return "mp4"
        return "unknown"
    }

    /**
     * True when the bytes look like a media *initialisation* segment (moov/EBML
     * header) rather than a media fragment. A capture armed mid-playback starts
     * with a `moof`/cluster instead, which MSE can never decode on its own.
     */
    fun isInitSegment(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        if (container(bytes) == "webm") return true
        var offset = 0
        var guard = 0
        while (offset + 8 <= bytes.size && guard++ < 4096) {
            val size = readUInt32(bytes, offset)
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            if (type == "moov" || type == "ftyp" || type == "styp") return true
            if (type == "moof" || type == "mdat" || type == "sidx") return false
            if (size < 8) return containsAscii(bytes, minOf(bytes.size, 16384), "moov")
            offset += size
        }
        return containsAscii(bytes, minOf(bytes.size, 16384), "moov")
    }

    /** Single codec family ("avc", "av1", "vp9", "aac", "opus", …) or "". */
    fun codec(kind: String, bytes: ByteArray, mime: String = ""): String {
        val tags = if (kind == "audio") AUDIO_CODECS else VIDEO_CODECS
        val limit = minOf(bytes.size, 16384)
        for ((tag, family) in tags) {
            if (containsAscii(bytes, limit, tag)) return family
        }
        val lower = mime.lowercase()
        return when {
            kind == "audio" && (lower.contains("opus")) -> "opus"
            kind == "audio" && (lower.contains("vorbis")) -> "vorbis"
            kind == "audio" && (lower.contains("mp4a") || lower.contains("aac")) -> "aac"
            lower.contains("av01") -> "av1"
            lower.contains("vp09") || lower.contains("vp9") -> "vp9"
            lower.contains("vp08") || lower.contains("vp8") -> "vp8"
            lower.contains("avc1") || lower.contains("avc3") -> "avc"
            lower.contains("hvc1") || lower.contains("hev1") -> "hevc"
            else -> ""
        }
    }

    fun inspect(bytes: ByteArray): Info {
        val container = container(bytes)
        val limit = minOf(bytes.size, 8192)
        val codecs = LinkedHashSet<String>()
        for ((tag, codec) in FOURCC) {
            if (containsAscii(bytes, limit, tag) && !codecs.contains(codec)) codecs.add(codec)
        }
        // An audio codec string accidentally sniffed from video bytes (or the
        // reverse) would be useless; callers filter by kind when building mimes.
        return Info(container, codecs.toList())
    }

    /**
     * Ordered list of `type/subtype; codecs="..."` values to try with
     * `MediaSource.addSourceBuffer`. First success wins on the JS side.
     */
    fun mimeCandidates(kind: String, recordedMime: String, initBytes: ByteArray): List<String> {
        val type = if (kind == "audio") "audio" else "video"
        val info = inspect(initBytes)
        val base = "$type/" + when (info.container) {
            "webm" -> "webm"
            else -> "mp4"
        }
        val out = LinkedHashSet<String>()

        val recorded = recordedMime.trim()
        if (recorded.startsWith("$type/", ignoreCase = true)) {
            val recordedIsWebm = recorded.contains("webm", ignoreCase = true)
            val agrees = info.container == "unknown" || recordedIsWebm == (info.container == "webm")
            if (agrees) out.add(recorded)
        }

        val codecs = when (type) {
            "audio" -> info.codecs.filter { it == "opus" || it == "vorbis" || it.startsWith("mp4a") }
            else -> info.codecs.filter { it.startsWith("av01") || it.startsWith("vp0") || it.startsWith("avc1") || it.startsWith("hvc1") || it.startsWith("hev1") }
        }
        if (codecs.isEmpty()) {
            info.codecs.forEach { out.add("$base; codecs=\"$it\"") }
        } else {
            codecs.forEach { out.add("$base; codecs=\"$it\"") }
        }
        out.add(base)
        out.add("$type/mp4")
        out.add("$type/webm")
        if (recorded.startsWith("$type/", ignoreCase = true)) out.add(recorded)
        return out.toList()
    }

    /** Reads the track handler from an init segment when the mime was missing. */
    fun trackKind(bytes: ByteArray): String? {
        val limit = minOf(bytes.size, 8192)
        if (containsAscii(bytes, limit, "vide")) return "video"
        if (containsAscii(bytes, limit, "soun")) return "audio"
        return null
    }

    fun containsAscii(bytes: ByteArray, limit: Int, needle: String): Boolean {
        val n = needle.toByteArray(Charsets.US_ASCII)
        if (n.isEmpty() || bytes.size < n.size) return false
        val last = minOf(limit, bytes.size) - n.size
        outer@ for (i in 0..last) {
            for (j in n.indices) {
                if (bytes[i + j] != n[j]) continue@outer
            }
            return true
        }
        return false
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return -1
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }
}
