package com.ytcache.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.widget.Toast
import com.ytcache.app.data.CaptureQueue
import com.ytcache.app.data.CaptureSession
import com.ytcache.app.data.CaptureStore
import com.ytcache.app.data.MetadataFetcher
import com.ytcache.app.data.VideoEntry
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The only bridge exposed to pages. Bulk media never crosses it in the player
 * (the player fetches files through the asset loader); capture fragments do,
 * base64-encoded, because that transport is the one Chrome never drops.
 *
 * Fragments are handed to a background queue immediately: doing the base64
 * decode and disk write inside the bridge call freezes YouTube's renderer for
 * the duration of every write, which is what made its own buttons feel dead.
 */
class CacheBridge(
    private val context: Context,
    private val store: CaptureStore,
    private val session: CaptureSession,
    private val host: BridgeHost,
) : CaptureQueue.Listener {

    interface BridgeHost {
        fun onCaptureSaved(entry: VideoEntry)
        fun onCaptureDuplicate(title: String)
        fun onMetadataUpdated(entry: VideoEntry)
        fun copyText(text: String)
        fun export(videoId: String, kind: String)
        fun closePlayer()
        fun openLibrary()
        fun deleteVideo(videoId: String)
        fun log(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val queue = CaptureQueue(session, store, this)
    private val io = Executors.newFixedThreadPool(2)
    private val metadataBusy = ConcurrentHashMap.newKeySet<String>()
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------- capture

    @JavascriptInterface
    fun postChunk(encoded: String) {
        queue.offer(encoded)
    }

    @JavascriptInterface
    fun saveVideoInfo(json: String): String {
        val id = session.onInfo(json) ?: return ""
        if (id.isEmpty()) return ""
        val thumbUrl = runCatching {
            org.json.JSONObject(json).optString("thumbUrl", "").trim()
        }.getOrDefault("")
        ensureThumbnail(id, thumbUrl)
        ensureMetadata(id)
        return id
    }

    fun finishCapture(after: (String?) -> Unit) {
        queue.afterFlush { id ->
            if (id != null) ensureMetadata(id)
            main.post { after(id) }
        }
    }

    fun pendingBytes(): Long = queue.pending()

    /** Releases background threads; the JS `close()` below is unrelated. */
    fun release() {
        queue.close()
        io.shutdown()
    }

    // -------------------------------------------------------------- player

    @JavascriptInterface
    fun getVideo(videoId: String): String =
        store.entry(videoId)?.toJson()?.toString() ?: "{}"

    @JavascriptInterface
    fun getStreams(videoId: String): String {
        val array = JSONArray()
        store.streamSummaries(videoId).forEach { array.put(it.toJson()) }
        return array.toString()
    }

    @JavascriptInterface
    fun listVideos(): String {
        val array = JSONArray()
        store.listVideos().forEach { array.put(it.toJson()) }
        return array.toString()
    }

    @JavascriptInterface
    fun readStreamPage(videoId: String, streamKey: String, offset: Int, count: Int): String =
        store.pageJson(videoId, streamKey, offset, count).toString()

    @JavascriptInterface
    fun saveResume(videoId: String, positionMs: Long) {
        store.writeResume(videoId, positionMs)
    }

    @JavascriptInterface
    fun getResume(videoId: String): Long = store.readResume(videoId)

    /** Boolean app settings exposed to the offline player. */
    @JavascriptInterface
    fun pref(name: String): String = when (name) {
        "sound" -> if (prefs.getBoolean(KEY_SOUND, true)) "1" else "0"
        else -> ""
    }

    @JavascriptInterface
    fun export(videoId: String, kind: String) {
        main.post { host.export(videoId, kind) }
    }

    @JavascriptInterface
    fun copyLink(url: String) {
        val link = if (url.startsWith("http")) url else "https://www.youtube.com/watch?v=$url"
        main.post { host.copyText(link) }
    }

    @JavascriptInterface
    fun deleteVideo(videoId: String) {
        main.post { host.deleteVideo(videoId) }
    }

    @JavascriptInterface
    fun close() {
        main.post { host.closePlayer() }
    }

    @JavascriptInterface
    fun openLibrary() {
        main.post { host.openLibrary() }
    }

    @JavascriptInterface
    fun toast(message: String) {
        main.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }

    @JavascriptInterface
    fun log(message: String) {
        host.log(message)
    }

    // ------------------------------------------------------- queue callbacks

    override fun onChunkSaved(videoId: String, entry: VideoEntry?, replacedIncomplete: Boolean) {
        // Only the first fragment carries an entry; metadata repair is not run
        // per fragment, which would rescan the capture folder hundreds of times.
        if (entry != null) {
            main.post { host.onCaptureSaved(entry) }
            ensureMetadata(videoId)
        }
    }

    override fun onDuplicate(title: String) {
        main.post { host.onCaptureDuplicate(title) }
    }

    override fun onChunkDropped(videoId: String) {
        host.log("capture backpressure: dropped a fragment ($videoId)")
    }

    // ------------------------------------------------------------- metadata

    /**
     * Titles and descriptions are the one thing the page hook cannot always
     * read (Shorts and SPA transitions expose them late). Repair them from
     * oEmbed plus the watch page, once per capture, off the UI thread.
     */
    fun ensureMetadata(videoId: String) {
        val entry = store.entry(videoId) ?: return
        val needsTitle = entry.title.isBlank() || entry.title == "Untitled video"
        val needsDescription = entry.description.isBlank()
        if (!needsTitle && !needsDescription) return
        if (!metadataBusy.add(videoId)) return
        io.execute {
            try {
                val info = MetadataFetcher.fetch(videoId, wantDescription = needsDescription) ?: return@execute
                val storedId = session.onInfo(info.toJson()) ?: return@execute
                ensureThumbnail(storedId, info.thumbUrl)
                val fresh = store.entry(storedId) ?: return@execute
                if (!session.titleOf(storedId).isBlank()) session.noteTitle(storedId, fresh.title)
                main.post { host.onMetadataUpdated(fresh) }
            } catch (e: Exception) {
                Log.d(TAG, "metadata backfill failed: ${e.message}")
            } finally {
                metadataBusy.remove(videoId)
            }
        }
    }

    // ------------------------------------------------------------ internal

    private fun ensureThumbnail(videoId: String, url: String) {
        val target = File(store.videoDir(videoId), "thumbnail.jpg")
        if (target.exists() && target.length() > 0) return
        val source = url.ifBlank { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" }
        io.execute { downloadThumbnail(target, source) }
    }

    private fun downloadThumbnail(target: File, url: String) {
        if (target.exists() && target.length() > 0) return
        var connection: HttpURLConnection? = null
        try {
            val http = URL(url).openConnection() as HttpURLConnection
            connection = http
            http.connectTimeout = 8000
            http.readTimeout = 8000
            http.instanceFollowRedirects = true
            http.setRequestProperty("User-Agent", USER_AGENT)
            if (http.responseCode in 200..299) {
                val tmp = File(target.parentFile, "thumbnail.jpg.tmp")
                http.inputStream.use { input ->
                    FileOutputStream(tmp).use { out -> input.copyTo(out, 64 * 1024) }
                }
                if (tmp.length() in 1..(6 * 1024 * 1024)) {
                    if (target.exists()) target.delete()
                    tmp.renameTo(target)
                } else {
                    tmp.delete()
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "thumbnail fetch failed: ${e.message}")
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    companion object {
        private const val TAG = "YTCacheBridge"
        private const val PREFS = "ytcache"
        const val KEY_SOUND = "play_sound"
        const val KEY_PILL = "show_pill"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
