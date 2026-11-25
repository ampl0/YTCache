package com.ytcache.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MediaSnifferTest {

    private fun mp4(fourcc: String): ByteArray {
        val head = byteArrayOf(0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6F, 0x6D)
        return head + fourcc.toByteArray() + ByteArray(16)
    }

    private fun webm(codecId: String): ByteArray =
        byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) + ByteArray(8) + codecId.toByteArray()

    @Test
    fun `detects containers`() {
        assertEquals("mp4", MediaSniffer.container(mp4("av01")))
        assertEquals("webm", MediaSniffer.container(webm("V_VP9")))
        assertEquals("unknown", MediaSniffer.container(byteArrayOf(1, 2, 3, 4, 5)))
    }

    @Test
    fun `detects ISO sample entries`() {
        assertEquals("av01.0.05M.08", MediaSniffer.inspect(mp4("av01")).codecs.first())
        assertTrue(MediaSniffer.inspect(mp4("vp09")).codecs.contains("vp09.00.10.08"))
        assertTrue(MediaSniffer.inspect(mp4("mp4a")).codecs.contains("mp4a.40.2"))
    }

    @Test
    fun `detects WebM codec ids`() {
        assertTrue(MediaSniffer.inspect(webm("V_VP9")).codecs.contains("vp09.00.10.08"))
        assertTrue(MediaSniffer.inspect(webm("V_AV1")).codecs.contains("av01.0.05M.08"))
        assertTrue(MediaSniffer.inspect(webm("A_OPUS")).codecs.contains("opus"))
    }

    @Test
    fun `uses the recorded mime when it matches the bytes`() {
        val candidates = MediaSniffer.mimeCandidates(
            "video", "video/mp4; codecs=\"av01.0.05M.08\"", mp4("av01"),
        )
        assertEquals("video/mp4; codecs=\"av01.0.05M.08\"", candidates.first())
    }

    @Test
    fun `overrides a recorded mime that contradicts the bytes`() {
        val candidates = MediaSniffer.mimeCandidates("video", "video/webm; codecs=\"vp9\"", mp4("av01"))
        assertTrue(candidates.first().startsWith("video/mp4"))
        assertTrue(candidates.contains("video/mp4"))
        assertTrue(candidates.contains("video/webm"))
    }

    @Test
    fun `always offers a plain fallback`() {
        val candidates = MediaSniffer.mimeCandidates("audio", "", byteArrayOf())
        assertTrue(candidates.contains("audio/mp4"))
        assertTrue(candidates.contains("audio/webm"))
        assertEquals(candidates.size, candidates.distinct().size)
    }

    @Test
    fun `audio candidates never become video mimes`() {
        val candidates = MediaSniffer.mimeCandidates("audio", "audio/webm; codecs=\"opus\"", webm("A_OPUS"))
        assertTrue(candidates.all { it.startsWith("audio/") })
    }

    @Test
    fun `infers track kind from the handler box`() {
        assertEquals("video", MediaSniffer.trackKind(mp4("av01") + "vide".toByteArray()))
        assertEquals("audio", MediaSniffer.trackKind(mp4("mp4a") + "soun".toByteArray()))
        assertNull(MediaSniffer.trackKind(byteArrayOf(1, 2, 3)))
    }
}

class StreamPickerTest {

    private fun stream(key: String, kind: String, bytes: Long, count: Int = 10) =
        StreamSummary("vid", key, kind, "", count, bytes)

    @Test
    fun `picks the dominant video stream`() {
        val picked = StreamPicker.pick(
            listOf(
                stream("s3-video", "video", 900),
                stream("s5-video", "video", 91_000),
                stream("s7-video", "video", 1_200),
            ),
        )
        assertEquals("s5-video", picked.first!!.streamKey)
    }

    @Test
    fun `pairs audio with the dominant video index`() {
        val picked = StreamPicker.pick(
            listOf(
                stream("s1-audio", "audio", 400),
                stream("s5-video", "video", 91_000),
                stream("s5-audio", "audio", 9_000),
            ),
        )
        assertEquals("s5-audio", picked.second!!.streamKey)
    }

    @Test
    fun `falls back to the largest audio`() {
        val picked = StreamPicker.pick(
            listOf(
                stream("s1-audio", "audio", 400),
                stream("s2-audio", "audio", 8_000),
                stream("s6-video", "video", 91_000),
            ),
        )
        assertEquals("s2-audio", picked.second!!.streamKey)
    }

    @Test
    fun `skips empty streams`() {
        val picked = StreamPicker.pick(
            listOf(
                stream("s0-video", "video", 0, count = 0),
                stream("s1-audio", "audio", 100),
            ),
        )
        assertNull(picked.first)
        assertEquals("s1-audio", picked.second!!.streamKey)
    }

    @Test
    fun `handles empty input`() {
        val picked = StreamPicker.pick(emptyList())
        assertNull(picked.first)
        assertNull(picked.second)
    }

    @Test
    fun `parses stream indexes`() {
        assertEquals(12, StreamPicker.indexOf("s12-video"))
        assertEquals(-1, StreamPicker.indexOf("audio"))
        assertFalse(StreamPicker.indexOf("s2-video") < 0)
    }
}

class StreamAssemblerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store() = CaptureStore(File(temp.root, "YTCache"))

    private fun header(seq: Int, key: String = "s0-video") =
        ChunkHeader("vid", key, "video", "video/mp4", seq, seq == 0, 1)

    @Test
    fun `concatenates fragments in sequence order`() {
        val store = store()
        store.writeChunk(header(0), byteArrayOf(1, 2))
        store.writeChunk(header(1), byteArrayOf(3, 4))
        store.writeChunk(header(2), byteArrayOf(5, 6))
        val out = File(temp.root, "out.bin")
        val bytes = StreamAssembler.concat(store, "vid", "s0-video", out)
        assertEquals(6L, bytes)
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5, 6), out.readBytes().toList())
    }

    @Test
    fun `returns zero when there is nothing to concatenate`() {
        val store = store()
        val out = File(temp.root, "empty.bin")
        assertEquals(0L, StreamAssembler.concat(store, "missing", "s0-video", out))
    }

    @Test
    fun `detects the container from the init segment`() {
        val store = store()
        store.writeChunk(header(0), byteArrayOf(0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6F, 0x6D))
        assertEquals("mp4", StreamAssembler.containerOf(store, "vid", "s0-video"))

        store.writeChunk(header(0, key = "s1-audio"), byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()))
        assertEquals("webm", StreamAssembler.containerOf(store, "vid", "s1-audio"))
    }
}
