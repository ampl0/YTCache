package com.ytcache.app

import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import com.ytcache.app.data.CaptureStore
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.FileInputStream

/**
 * Maps https://appassets.androidplatform.net/local/... onto the capture folder
 * so the player can `fetch()` fragments straight into MSE. This keeps bulk
 * media off the JavaScript bridge entirely (no size limits, no base64).
 */
class LocalPathHandler(private val store: CaptureStore) : WebViewAssetLoader.PathHandler {

    override fun handle(path: String): WebResourceResponse? {
        val relative = path.removePrefix("/").removePrefix("local/")
        if (relative.isBlank() || relative.contains("..")) return null

        if (relative.endsWith("/streams.json")) {
            val videoId = relative.removeSuffix("/streams.json").trim('/')
            if (videoId.isBlank()) return null
            val array = JSONArray()
            store.streamSummaries(videoId).forEach { array.put(it.toJson()) }
            return json(array.toString())
        }

        if (relative.endsWith("/video.json")) {
            val videoId = relative.removeSuffix("/video.json").trim('/')
            val entry = store.entry(videoId) ?: return null
            return json(entry.toJson().toString())
        }

        val file = store.resolve(relative) ?: return null
        val mime = when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }
        return try {
            WebResourceResponse(mime, null, 200, "OK", headers(), FileInputStream(file))
        } catch (e: Exception) {
            null
        }
    }

    private fun json(body: String): WebResourceResponse =
        WebResourceResponse(
            "application/json",
            "utf-8",
            200,
            "OK",
            headers(),
            ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)),
        )

    private fun headers(): Map<String, String> = mapOf(
        "Cache-Control" to "no-store",
        "Access-Control-Allow-Origin" to "*",
    )
}
