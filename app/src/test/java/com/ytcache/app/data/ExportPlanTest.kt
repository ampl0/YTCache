package com.ytcache.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MuxPlannerTest {

    @Test
    fun `muxes h264 and aac into mp4`() {
        assertEquals(MuxFormat.MP4, MuxPlanner.choose("mp4", "avc", "mp4", "aac"))
        assertEquals(MuxFormat.MP4, MuxPlanner.choose("mp4", "hevc", "mp4", "aac"))
    }

    @Test
    fun `muxes vp9 and opus into webm`() {
        assertEquals(MuxFormat.WEBM, MuxPlanner.choose("webm", "vp9", "webm", "opus"))
        assertEquals(MuxFormat.WEBM, MuxPlanner.choose("webm", "vp8", "webm", "vorbis"))
    }

    @Test
    fun `refuses combinations no android muxer supports`() {
        assertEquals(MuxFormat.NONE, MuxPlanner.choose("webm", "vp9", "mp4", "aac"))
        assertEquals(MuxFormat.NONE, MuxPlanner.choose("mp4", "avc", "webm", "opus"))
        assertEquals(MuxFormat.NONE, MuxPlanner.choose("mp4", "av1", "webm", "opus"))
        assertEquals(MuxFormat.NONE, MuxPlanner.choose("unknown", "avc", "unknown", "aac"))
    }

    @Test
    fun `maps containers to file extensions`() {
        assertEquals("mp4", MuxPlanner.extension(MuxFormat.MP4))
        assertEquals("webm", MuxPlanner.extension(MuxFormat.WEBM))
    }
}

class InitSegmentTest {

    private fun mp4(fourcc: String): ByteArray =
        byteArrayOf(0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6F, 0x6D) +
            fourcc.toByteArray() + ByteArray(16)

    @Test
    fun `accepts a real init segment`() {
        assertTrue(MediaSniffer.isInitSegment(mp4("av01")))
        assertTrue(MediaSniffer.isInitSegment(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) + ByteArray(8)))
    }

    @Test
    fun `rejects a bare media fragment`() {
        val moof = byteArrayOf(0, 0, 0, 0x20, 0x6D, 0x6F, 0x6F, 0x66) + ByteArray(24)
        assertFalse(MediaSniffer.isInitSegment(moof))
        assertFalse(MediaSniffer.isInitSegment(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun `reads a single codec family`() {
        assertEquals("avc", MediaSniffer.codec("video", mp4("avc1")))
        assertEquals("av1", MediaSniffer.codec("video", mp4("av01")))
        assertEquals("aac", MediaSniffer.codec("audio", mp4("mp4a")))
        assertEquals("opus", MediaSniffer.codec("audio", byteArrayOf(1, 2, 3), "audio/webm; codecs=\"opus\""))
    }
}

class PartialCaptureTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store() = CaptureStore(File(temp.root, "YTCache"))

    private fun initMp4() = byteArrayOf(0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6F, 0x6D)

    private fun header(key: String, seq: Int) = ChunkHeader("vid", key, "video", "video/mp4", seq, seq == 0, 1)

    @Test
    fun `counts contiguous fragments and flags a gap`() {
        val store = store()
        store.writeChunk(header("s0-video", 0), initMp4())
        store.writeChunk(header("s0-video", 1), byteArrayOf(1))
        store.writeChunk(header("s0-video", 3), byteArrayOf(3))

        val summary = store.streamSummaries("vid").first()
        assertEquals(2, summary.fragmentCount)
        assertTrue(summary.hasInit)
        assertTrue(summary.partial)

        val entry = store.entry("vid")!!
        assertTrue(entry.partial)
        assertTrue(entry.playable)
    }

    @Test
    fun `does not claim playback for a mid-stream capture`() {
        val store = store()
        store.writeChunk(header("s4-video", 7), byteArrayOf(1))
        store.writeChunk(header("s4-video", 8), byteArrayOf(2))

        val summary = store.streamSummaries("vid").first()
        assertFalse(summary.hasInit)
        assertEquals(7, summary.firstSeq)
        assertFalse(summary.playable)

        val entry = store.entry("vid")!!
        assertFalse(entry.playable)
        assertTrue(entry.partial)
    }

    @Test
    fun `stopping a capture marks it incomplete`() {
        val store = store()
        store.writeChunk(header("s0-video", 0), initMp4())
        assertFalse(store.entry("vid")!!.partial)
        store.markStopped("vid")
        assertTrue(store.entry("vid")!!.partial)
        store.clearStopped("vid")
        assertFalse(store.entry("vid")!!.partial)
    }

    @Test
    fun `recapture keeps metadata and drops the old media`() {
        val store = store()
        store.saveVideoInfo("""{"videoId":"vid","title":"Keep me","author":"A"}""")
        store.writeChunk(header("s0-video", 0), initMp4())
        store.markStopped("vid")

        assertTrue(store.resetForRecapture("vid"))
        assertEquals(0, store.streamSummaries("vid").size)
        assertEquals("vid", store.entry("vid")?.videoId ?: "vid")
        val meta = org.json.JSONObject(File(store.videoDir("vid"), "video.json").readText())
        assertEquals("Keep me", meta.getString("title"))
    }

    @Test
    fun `exposes resume progress on the entry`() {
        val store = store()
        store.writeChunk(header("s0-video", 0), initMp4())
        store.writeResume("vid", 90000L)
        assertEquals(90000L, store.entry("vid")!!.resumeMs)
    }
}
