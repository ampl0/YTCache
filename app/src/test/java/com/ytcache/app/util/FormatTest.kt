package com.ytcache.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatTest {

    @Test
    fun `formats durations`() {
        assertEquals("", Format.duration(0))
        assertEquals("0:05", Format.duration(5))
        assertEquals("1:05", Format.duration(65))
        assertEquals("1:01:01", Format.duration(3661))
    }

    @Test
    fun `formats sizes`() {
        assertEquals("0 MB", Format.size(0))
        assertEquals("512 KB", Format.size(512 * 1024))
        assertEquals("1.5 MB", Format.size((1.5 * 1024 * 1024).toLong()))
        assertTrue(Format.size(3L * 1024 * 1024 * 1024).endsWith("GB"))
    }

    @Test
    fun `formats relative dates`() {
        val now = 1_700_000_000_000L
        assertEquals("Just now", Format.relative(now, now))
        assertEquals("5 min ago", Format.relative(now - 5 * 60_000, now))
        assertEquals("2 h ago", Format.relative(now - 2 * 3_600_000, now))
        assertEquals("Yesterday", Format.relative(now - 30 * 3_600_000, now))
        assertEquals("3 d ago", Format.relative(now - 3 * 86_400_000, now))
        assertTrue(Format.relative(now - 40L * 86_400_000, now).isNotBlank())
    }

    @Test
    fun `produces filesystem safe names`() {
        assertEquals("Never Gonna Give You Up", Format.fileName("Never Gonna Give You Up"))
        assertEquals("a b c", Format.fileName("a/b:c"))
        assertEquals("video", Format.fileName("   ", "video"))
        assertEquals("video", Format.fileName("", "video"))
        assertFalse(Format.fileName("x".repeat(200)).length > 70)
    }
}
