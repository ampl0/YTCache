package com.ytcache.app.data

/**
 * Per-activity capture state: how much has been written for the video that is
 * currently being cached, and whether it is already in the library (in which
 * case incoming fragments are dropped instead of corrupting a finished capture).
 */
class CaptureSession(private val store: CaptureStore) {

    data class ChunkOutcome(
        val written: Boolean,
        val firstForVideo: Boolean,
        val duplicateTitle: String?,
        /** The previous, incomplete capture was replaced by this one. */
        val replacedIncomplete: Boolean = false,
    )

    data class Stats(
        val videoId: String?,
        val title: String,
        val streams: Int,
        val bytes: Long,
        val writes: Int,
        val lastWriteAt: Long,
    ) {
        val active: Boolean get() = videoId != null
    }

    private val lock = Any()
    private val claimed = HashMap<String, MutableSet<String>>()
    private val blocked = HashSet<String>()
    private val bytesByVideo = HashMap<String, Long>()
    private val titles = HashMap<String, String>()
    private var writes = 0
    private var currentVideoId: String? = null
    private var lastWriteAt = 0L
    private var finished = false
    private var dirty = false

    fun onInfo(json: String): String? {
        val id = store.saveVideoInfo(json) ?: return null
        val title = runCatching { org.json.JSONObject(json).optString("title") }.getOrDefault("")
        synchronized(lock) {
            if (title.isNotBlank()) titles[id] = title
        }
        return id
    }

    fun onChunk(header: ChunkHeader, payload: ByteArray): ChunkOutcome {
        val id = store.sanitize(header.videoId)
        if (id == "unknown") return ChunkOutcome(false, false, null)

        var firstForVideo = false
        var replaced = false
        synchronized(lock) {
            // Once a finished capture is refused, every following fragment is
            // dropped silently instead of raising a snackbar per fragment.
            if (id in blocked) return ChunkOutcome(false, false, null)
            if (id !in claimed) {
                if (store.hasMedia(id)) {
                    val existing = store.entry(id)
                    if (existing != null && !existing.partial) {
                        blocked.add(id)
                        return ChunkOutcome(false, false, existing.title.ifBlank { id })
                    }
                    // An incomplete capture is replaced so the user can simply
                    // arm again and replay the video from the beginning.
                    store.resetForRecapture(id)
                    replaced = true
                }
                claimed[id] = LinkedHashSet()
                firstForVideo = true
            }
            finished = false
            dirty = true
            clearStoppedLocked(id)
        }

        val written = store.writeChunk(header, payload)
        if (!written) return ChunkOutcome(false, firstForVideo, null, replaced)

        synchronized(lock) {
            claimed[id]?.add(header.streamKey)
            bytesByVideo[id] = (bytesByVideo[id] ?: 0L) + payload.size
            writes++
            currentVideoId = id
            lastWriteAt = System.currentTimeMillis()
            finished = false
        }
        return ChunkOutcome(true, firstForVideo, null, replaced)
    }

    private fun clearStoppedLocked(id: String) {
        val dir = store.videoDir(id)
        val marker = java.io.File(dir, "capture.json")
        if (marker.isFile) marker.delete()
    }

    /** The user disarmed capture (or left the page): remember it as incomplete. */
    fun finishCurrent(): String? {
        val id = synchronized(lock) {
            if (!dirty) return null
            dirty = false
            finished = true
            currentVideoId
        } ?: return null
        store.markStopped(id)
        return id
    }

    fun activeVideoId(): String? = synchronized(lock) { currentVideoId }

    fun titleOf(id: String): String = synchronized(lock) { titles[id].orEmpty() }

    fun noteTitle(id: String, title: String) {
        if (title.isBlank()) return
        synchronized(lock) { titles[id] = title }
    }

    fun stats(): Stats = synchronized(lock) {
        val id = currentVideoId
        Stats(
            videoId = if (finished) null else id,
            title = id?.let { titles[it] } ?: "",
            streams = id?.let { claimed[it]?.size } ?: 0,
            bytes = id?.let { bytesByVideo[it] } ?: 0L,
            writes = writes,
            lastWriteAt = lastWriteAt,
        )
    }

    fun forgetSound() {
        synchronized(lock) { blocked.clear() }
    }
}
