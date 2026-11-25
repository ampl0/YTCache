package com.ytcache.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object Format {

    fun duration(totalSeconds: Int): String {
        if (totalSeconds <= 0) return ""
        val seconds = totalSeconds.toLong()
        val h = TimeUnit.SECONDS.toHours(seconds)
        val m = TimeUnit.SECONDS.toMinutes(seconds) % 60
        val s = seconds % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }

    fun size(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    fun relative(timestamp: Long, now: Long = System.currentTimeMillis()): String {
        if (timestamp <= 0L) return ""
        val diff = now - timestamp
        if (diff < 0) return "Just now"
        val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
        return when {
            minutes < 1 -> "Just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 60 * 24 -> "${minutes / 60} h ago"
            minutes < 60 * 24 * 2 -> "Yesterday"
            minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)} d ago"
            minutes < 60 * 24 * 365 -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(timestamp))
            else -> SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(timestamp))
        }
    }

    /** A filesystem-safe, human file name derived from a video title. */
    fun fileName(title: String, fallback: String = "video"): String {
        val cleaned = StringBuilder()
        for (ch in title) {
            val legal = ch.isLetterOrDigit() || ch == ' ' || ch == '-' || ch == '_' || ch == '(' || ch == ')' || ch == ',' || ch == '.' || ch == '[' || ch == ']'
            cleaned.append(if (legal) ch else ' ')
        }
        val collapsed = cleaned.toString().replace(Regex("\\s+"), " ").trim().trim('.').trim()
        val safe = if (collapsed.isBlank()) fallback else collapsed
        return if (safe.length > 70) safe.substring(0, 70).trim() else safe
    }
}
