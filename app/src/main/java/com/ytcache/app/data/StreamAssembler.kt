package com.ytcache.app.data

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Captured fragments are a fragmented MP4/WebM byte stream split across files.
 * Concatenating them in sequence order yields a playable file again (init
 * segment first, then every moof+mdat / EBML cluster).
 */
object StreamAssembler {

    fun fragments(dir: File): List<File> {
        val files = dir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && it.name.endsWith(".bin") && !it.name.endsWith(".tmp") }
            .sortedBy { it.nameWithoutExtension.toIntOrNull() ?: Int.MAX_VALUE }
    }

    fun concat(store: CaptureStore, videoId: String, streamKey: String, out: File): Long {
        val fragments = fragments(store.streamDir(videoId, streamKey))
        if (fragments.isEmpty()) return 0L
        out.parentFile?.mkdirs()
        var total = 0L
        BufferedOutputStream(FileOutputStream(out), 128 * 1024).use { sink ->
            for (fragment in fragments) {
                fragment.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        sink.write(buffer, 0, read)
                        total += read
                    }
                }
            }
        }
        return total
    }

    /** "mp4" or "webm", decided from the first bytes of the init segment. */
    fun containerOf(store: CaptureStore, videoId: String, streamKey: String): String {
        val first = fragments(store.streamDir(videoId, streamKey)).firstOrNull() ?: return "mp4"
        val head = ByteArray(12)
        val read = first.inputStream().use { it.read(head) }
        if (read <= 0) return "mp4"
        return MediaSniffer.container(head.copyOf(read))
    }
}
