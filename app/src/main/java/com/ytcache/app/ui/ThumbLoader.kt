package com.ytcache.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.util.concurrent.Executors

/**
 * Thumbnails are local JPEGs captured with the media, so decoding is cheap.
 * A tiny LRU cache + 2 worker threads is all this needs (no image library).
 */
class ThumbLoader {

    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun load(view: ImageView, path: String) {
        view.tag = path
        if (path.isBlank()) {
            view.setImageDrawable(null)
            return
        }
        cache.get(path)?.let {
            view.setImageBitmap(it)
            return
        }
        view.setImageDrawable(null)
        val targetWidth = if (view.width > 0) view.width else 360
        executor.execute {
            val bitmap = decode(path, targetWidth)
            if (bitmap == null) return@execute
            cache.put(path, bitmap)
            main.post {
                if (view.tag == path) view.setImageBitmap(bitmap)
            }
        }
    }

    private fun decode(path: String, targetWidth: Int): Bitmap? {
        val file = File(path)
        if (!file.isFile || file.length() == 0L) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return runCatching { BitmapFactory.decodeFile(path, options) }.getOrNull()
    }
}
