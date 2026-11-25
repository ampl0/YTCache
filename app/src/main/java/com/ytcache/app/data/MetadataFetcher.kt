package com.ytcache.app.data

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The page hook reads metadata the moment YouTube exposes it, but Shorts and
 * some SPA transitions expose it late or not at all. oEmbed is a tiny public
 * endpoint with title/author/thumbnail, and the watch page carries the
 * description, so an incomplete record can always be repaired after the fact.
 */
object MetadataFetcher {

    data class Info(
        val videoId: String,
        val title: String,
        val author: String,
        val description: String,
        val lengthSeconds: Int,
        val thumbUrl: String,
    ) {
        fun toJson(): String = JSONObject().apply {
            put("v", 1)
            put("videoId", videoId)
            put("title", title)
            put("author", author)
            put("description", description)
            put("lengthSeconds", lengthSeconds)
            put("thumbUrl", thumbUrl)
        }.toString()
    }

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"

    fun fetch(videoId: String, wantDescription: Boolean): Info? {
        if (videoId.isBlank() || videoId == "unknown") return null
        val base = videoId.trim()
        var title = ""
        var author = ""
        var thumb = ""

        val oembed = get(
            "https://www.youtube.com/oembed?url=" +
                URLEncoder.encode("https://www.youtube.com/watch?v=$base", "UTF-8") +
                "&format=json",
        )
        if (oembed != null) {
            runCatching {
                val json = JSONObject(oembed)
                title = json.optString("title", "")
                author = json.optString("author_name", "")
                thumb = json.optString("thumbnail_url", "")
            }
        }

        var description = ""
        var length = 0
        if (wantDescription || title.isBlank()) {
            val page = get("https://www.youtube.com/watch?v=$base&hl=en&bpctr=9999999999&has_verified=1")
            if (page != null) {
                if (title.isBlank()) title = stringField(page, "title").orEmpty()
                if (title.isBlank()) title = metaContent(page, "og:title")
                if (author.isBlank()) {
                    author = stringField(page, "ownerChannelName").orEmpty()
                        .ifBlank { stringField(page, "author").orEmpty() }
                }
                description = stringField(page, "shortDescription").orEmpty()
                length = stringField(page, "lengthSeconds")?.toIntOrNull() ?: 0
            }
        }

        if (title.isBlank()) return null
        return Info(
            videoId = base,
            title = cleanTitle(title),
            author = author.trim(),
            description = description.trim(),
            lengthSeconds = if (length > 0) length else 0,
            thumbUrl = thumb.ifBlank { "https://i.ytimg.com/vi/$base/hqdefault.jpg" },
        )
    }

    private fun cleanTitle(value: String): String =
        value.replace(Regex("\\s*[-\\u2013\\u2014|]\\s*YouTube\\s*$"), "").trim()

    private fun get(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", UA)
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                var total = 0
                while (total < MAX_BYTES) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    total += read
                }
                out.toString("UTF-8")
            }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /** Reads a top-level JSON string field out of the page's inline JSON. */
    private fun stringField(html: String, field: String): String? {
        val regex = Regex("\"$field\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
        val match = regex.find(html) ?: return null
        val raw = match.groupValues[1]
        return runCatching {
            JSONObject("{\"v\":\"$raw\"}").getString("v")
        }.getOrElse { raw }
    }

    private fun metaContent(html: String, property: String): String {
        val regex = Regex(
            "<meta[^>]+(?:property|name)=[\"']" + Regex.escape(property) + "[\"'][^>]*content=[\"']([^\"']*)[\"']",
            RegexOption.IGNORE_CASE,
        )
        return regex.find(html)?.groupValues?.get(1).orEmpty()
    }

    private const val MAX_BYTES = 3 * 1024 * 1024
}
