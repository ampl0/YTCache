package com.ytcache.app.data

/**
 * A single YouTube video is spread over many SourceBuffers as the player
 * adapts quality. Playing the first video stream produces a few seconds of
 * video and then "ends" — so the dominant (largest) stream must be used.
 *
 * A capture armed mid-playback also records streams whose first fragment is a
 * media segment rather than an init segment; those can never be appended to
 * MSE, so playable streams always win over larger broken ones.
 */
object StreamPicker {

    fun indexOf(streamKey: String): Int {
        val match = Regex("^s(\\d+)-").find(streamKey) ?: return -1
        return match.groupValues[1].toIntOrNull() ?: -1
    }

    private fun rank(streams: List<StreamSummary>): List<StreamSummary> =
        streams.sortedWith(
            compareByDescending<StreamSummary> { it.playable }
                .thenByDescending { it.bytes },
        )

    fun pick(streams: List<StreamSummary>): Pair<StreamSummary?, StreamSummary?> {
        val videos = rank(streams.filter { it.kind == "video" && it.fragmentCount > 0 && it.bytes > 0 })
        val audios = rank(streams.filter { it.kind == "audio" && it.fragmentCount > 0 && it.bytes > 0 })

        val video = videos.firstOrNull()
        val audio = if (video == null) {
            audios.firstOrNull()
        } else {
            val idx = indexOf(video.streamKey)
            audios.firstOrNull { indexOf(it.streamKey) == idx && idx >= 0 } ?: audios.firstOrNull()
        }
        return video to audio
    }
}
