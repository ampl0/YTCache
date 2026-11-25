package com.ytcache.app.data

/** How the two captured tracks can be combined into one playable file. */
enum class MuxFormat { MP4, WEBM, NONE }

/**
 * Android's MediaMuxer can hold AVC/HEVC/AV1 + AAC in MP4 and VP8/VP9/AV1 +
 * Opus/Vorbis in WebM. Anything else needs a transcoder, which this app does
 * not ship, so the exporter falls back to a single track and says so.
 */
object MuxPlanner {

    private val MP4_VIDEO = setOf("avc", "hevc", "av1")
    private val MP4_AUDIO = setOf("aac")
    private val WEBM_VIDEO = setOf("vp8", "vp9", "av1")
    private val WEBM_AUDIO = setOf("opus", "vorbis")

    fun choose(
        videoContainer: String,
        videoCodec: String,
        audioContainer: String,
        audioCodec: String,
    ): MuxFormat {
        if (videoContainer == "mp4" && audioContainer == "mp4" &&
            videoCodec in MP4_VIDEO && audioCodec in MP4_AUDIO
        ) return MuxFormat.MP4

        if (videoContainer == "webm" && audioContainer == "webm" &&
            videoCodec in WEBM_VIDEO && audioCodec in WEBM_AUDIO
        ) return MuxFormat.WEBM

        return MuxFormat.NONE
    }

    /** Best container for a capture that only has a video track. */
    fun videoOnly(videoContainer: String): MuxFormat =
        if (videoContainer == "webm") MuxFormat.WEBM else MuxFormat.MP4

    fun extension(format: MuxFormat): String = when (format) {
        MuxFormat.WEBM -> "webm"
        MuxFormat.MP4 -> "mp4"
        MuxFormat.NONE -> "mp4"
    }

    fun describe(format: MuxFormat): String = when (format) {
        MuxFormat.MP4 -> "MP4"
        MuxFormat.WEBM -> "WebM"
        MuxFormat.NONE -> "track"
    }
}
