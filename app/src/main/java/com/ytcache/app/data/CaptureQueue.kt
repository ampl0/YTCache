package com.ytcache.app.data

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * `@JavascriptInterface` calls run synchronously from the renderer's point of
 * view: every millisecond spent decoding base64 and writing a fragment is a
 * millisecond YouTube's page cannot process a tap. Fragments are therefore
 * queued here and drained on one background thread (FIFO keeps each stream in
 * order), with a byte budget so a stalled disk cannot grow memory without bound.
 */
class CaptureQueue(
    private val session: CaptureSession,
    private val store: CaptureStore,
    private val listener: Listener,
) {

    interface Listener {
        fun onChunkSaved(videoId: String, entry: VideoEntry?, replacedIncomplete: Boolean)
        fun onDuplicate(title: String)
        fun onChunkDropped(videoId: String)
    }

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ytc-capture").apply {
            priority = Thread.NORM_PRIORITY - 1
            isDaemon = true
        }
    }

    private val pendingBytes = AtomicLong(0L)
    private val dropped = AtomicInteger(0)

    @Volatile
    private var closed = false

    fun offer(encoded: String) {
        if (closed || encoded.isEmpty()) return
        val cost = encoded.length.toLong() * 3L / 4L
        if (pendingBytes.addAndGet(cost) > MAX_PENDING_BYTES) {
            pendingBytes.addAndGet(-cost)
            dropped.incrementAndGet()
            listener.onChunkDropped("overflow")
            return
        }
        try {
            executor.execute {
                try {
                    val chunk = ChunkProtocol.parseBase64(encoded)
                    val id = store.sanitize(chunk.header.videoId)
                    val outcome = session.onChunk(chunk.header, chunk.payload)
                    if (outcome.duplicateTitle != null) {
                        listener.onDuplicate(outcome.duplicateTitle)
                    } else if (outcome.written) {
                        val entry = if (outcome.firstForVideo) store.entry(id) else null
                        listener.onChunkSaved(id, entry, outcome.replacedIncomplete)
                    }
                } catch (e: Exception) {
                    // A malformed frame is not fatal: the next one still lands.
                } finally {
                    pendingBytes.addAndGet(-cost)
                }
            }
        } catch (e: Exception) {
            pendingBytes.addAndGet(-cost)
        }
    }

    /** Runs [block] after every queued fragment has been flushed. */
    fun afterFlush(block: (String?) -> Unit) {
        if (closed) return
        try {
            executor.execute {
                val id = session.finishCurrent()
                block(id)
            }
        } catch (e: Exception) {
            block(null)
        }
    }

    fun pending(): Long = pendingBytes.get()

    fun droppedChunks(): Int = dropped.get()

    fun close() {
        closed = true
        executor.shutdown()
    }

    companion object {
        /** ~60 MB of encoded fragments in flight. */
        private const val MAX_PENDING_BYTES = 60L * 1024 * 1024
    }
}
