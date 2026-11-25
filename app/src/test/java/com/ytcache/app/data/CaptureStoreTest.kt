package com.ytcache.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CaptureStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store() = CaptureStore(File(temp.root, "YTCache"))

    private fun info(
        id: String = "dQw4w9WgXcQ",
        title: String = "Never Gonna Give You Up",
        length: Int = 213,
        thumb: String = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
    ) = """{"v":1,"videoId":"$id","title":"$title","author":"Rick Astley","lengthSeconds":$length,
        "description":"The official video","thumbUrl":"$thumb"}"""

    private fun header(
        streamKey: String = "s0-video",
        seq: Int = 0,
        kind: String = "video",
        mime: String = "video/mp4; codecs=\"av01.0.05M.08\"",
        id: String = "dQw4w9WgXcQ",
    ) = ChunkHeader(id, streamKey, kind, mime, seq, seq == 0, 4)

    @Test
    fun `sanitizes hostile names`() {
        val store = store()
        assertEquals("abc.def", store.sanitize("abc/../def"))
        assertEquals("unknown", store.sanitize(".."))
        assertEquals("unknown", store.sanitize(""))
        assertEquals("etcpasswd", store.sanitize("../../etc/passwd"))
        assertEquals("dQw4w9WgXcQ", store.sanitize("dQw4w9WgXcQ"))
        assertEquals("s0-video", store.sanitize("s0-video"))
        assertEquals("hidden", store.sanitize(".hidden"))
    }

    @Test
    fun `refuses to resolve outside the storage root`() {
        val store = store()
        assertNull(store.resolve("../secret.bin"))
        assertNull(store.resolve("/etc/passwd"))
        assertNull(store.resolve("a/../../b"))
        assertNull(store.resolve(""))
    }

    @Test
    fun `resolves a legitimate fragment`() {
        val store = store()
        store.writeChunk(header(), byteArrayOf(1, 2, 3, 4))
        val file = store.resolve("dQw4w9WgXcQ/s0-video/0.bin")
        assertNotNull(file)
        assertEquals(4L, file!!.length())
    }

    @Test
    fun `writes fragments atomically and skips duplicates`() {
        val store = store()
        assertTrue(store.writeChunk(header(seq = 0), byteArrayOf(1, 2, 3, 4)))
        assertTrue(store.writeChunk(header(seq = 1), byteArrayOf(5, 6, 7, 8)))
        assertFalse("same sequence must not overwrite", store.writeChunk(header(seq = 1), byteArrayOf(9)))
        val dir = store.streamDir("dQw4w9WgXcQ", "s0-video")
        assertEquals(2, dir.listFiles()!!.count { it.name.endsWith(".bin") })
        assertEquals(0, dir.listFiles()!!.count { it.name.endsWith(".tmp") })
    }

    @Test
    fun `writes stream metadata on the first chunk`() {
        val store = store()
        store.writeChunk(header(), byteArrayOf(1))
        val meta = File(store.streamDir("dQw4w9WgXcQ", "s0-video"), "meta.json")
        assertTrue(meta.isFile)
        val json = org.json.JSONObject(meta.readText())
        assertEquals("video", json.getString("kind"))
        assertEquals("video/mp4; codecs=\"av01.0.05M.08\"", json.getString("mime"))
    }

    @Test
    fun `builds an entry with metadata and totals`() {
        val store = store()
        assertEquals("dQw4w9WgXcQ", store.saveVideoInfo(info()))
        store.writeChunk(header(streamKey = "s0-video", seq = 0), ByteArray(100))
        store.writeChunk(header(streamKey = "s0-video", seq = 1), ByteArray(200))
        store.writeChunk(header(streamKey = "s1-audio", seq = 0, kind = "audio", mime = "audio/mp4"), ByteArray(50))

        val entry = store.entry("dQw4w9WgXcQ")
        assertNotNull(entry)
        entry!!
        assertEquals("Never Gonna Give You Up", entry.title)
        assertEquals("Rick Astley", entry.author)
        assertEquals(213, entry.lengthSeconds)
        assertEquals(350L, entry.sizeBytes)
        assertEquals(2, entry.streamCount)
        assertFalse(entry.hasThumb)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", entry.sourceUrl)
    }

    @Test
    fun `hides metadata-only folders from the library`() {
        val store = store()
        store.saveVideoInfo(info())
        assertNull("a browsed but uncaptured video is not a library entry", store.entry("dQw4w9WgXcQ"))
        assertTrue(store.listVideos().isEmpty())
    }

    @Test
    fun `merges repeated metadata without losing the capture time`() {
        val store = store()
        store.saveVideoInfo(info(title = "First title"))
        store.writeChunk(header(), byteArrayOf(1))
        val firstTime = store.entry("dQw4w9WgXcQ")!!.capturedAt

        Thread.sleep(5)
        store.saveVideoInfo("""{"videoId":"dQw4w9WgXcQ","title":"Second title","author":""}""")
        val entry = store.entry("dQw4w9WgXcQ")!!
        assertEquals("Second title", entry.title)
        assertEquals("Rick Astley", entry.author)
        assertEquals(firstTime, entry.capturedAt)
    }

    @Test
    fun `sorts the library newest first`() {
        val store = store()
        store.writeChunk(header(id = "aaa"), byteArrayOf(1))
        store.writeChunk(header(id = "bbb"), byteArrayOf(1))
        store.saveVideoInfo("""{"videoId":"aaa","title":"Old"}""")
        store.saveVideoInfo("""{"videoId":"bbb","title":"New"}""")
        val a = File(store.videoDir("aaa"), "video.json")
        val b = File(store.videoDir("bbb"), "video.json")
        a.writeText(a.readText().replace("\"capturedAt\":", "\"ignored\":"))
        val aJson = org.json.JSONObject(a.readText()).put("capturedAt", 1000L)
        val bJson = org.json.JSONObject(b.readText()).put("capturedAt", 2000L)
        a.writeText(aJson.toString())
        b.writeText(bJson.toString())

        val list = store.listVideos()
        assertEquals(2, list.size)
        assertEquals("bbb", list[0].videoId)
        assertEquals("aaa", list[1].videoId)
        assertEquals(2L, store.totalBytes())
    }

    @Test
    fun `pages through fragments with a bounded batch`() {
        val store = store()
        for (seq in 0 until 20) {
            store.writeChunk(header(seq = seq), ByteArray(1) { seq.toByte() })
        }
        val first = store.pageJson("dQw4w9WgXcQ", "s0-video", 0, 8)
        assertEquals(8, first.getJSONArray("items").length())
        assertEquals(8, first.getInt("next"))
        assertEquals(20, first.getInt("total"))
        assertFalse(first.getBoolean("done"))

        val last = store.pageJson("dQw4w9WgXcQ", "s0-video", 16, 8)
        assertEquals(4, last.getJSONArray("items").length())
        assertTrue(last.getBoolean("done"))

        val clamped = store.pageJson("dQw4w9WgXcQ", "s0-video", 0, 999)
        assertTrue(clamped.getJSONArray("items").length() <= 16)
    }

    @Test
    fun `pages decode to the stored bytes`() {
        val store = store()
        store.writeChunk(header(seq = 0), byteArrayOf(10, 20, 30))
        val page = store.pageJson("dQw4w9WgXcQ", "s0-video", 0, 1)
        val decoded = java.util.Base64.getDecoder().decode(page.getJSONArray("items").getString(0))
        assertEquals(listOf<Byte>(10, 20, 30), decoded.toList())
    }

    @Test
    fun `summarises streams with kinds and sizes`() {
        val store = store()
        store.writeChunk(header(streamKey = "s2-video", seq = 0), ByteArray(10))
        store.writeChunk(header(streamKey = "s2-video", seq = 1), ByteArray(20))
        store.writeChunk(header(streamKey = "s3-audio", seq = 0, kind = "audio"), ByteArray(5))
        val summaries = store.streamSummaries("dQw4w9WgXcQ")
        assertEquals(2, summaries.size)
        assertEquals("s2-video", summaries[0].streamKey)
        assertEquals(30L, summaries[0].bytes)
        assertEquals(2, summaries[0].fragmentCount)
        assertEquals("audio", summaries[1].kind)
    }

    @Test
    fun `infers the track kind when the mime was unknown`() {
        val store = store()
        val init = byteArrayOf(0, 0, 0, 8) + "ftypisom".toByteArray() + "soun".toByteArray()
        store.writeChunk(header(streamKey = "s9-other", kind = "other", mime = ""), init)
        val summary = store.streamSummaries("dQw4w9WgXcQ").first()
        assertEquals("audio", summary.kind)
    }

    @Test
    fun `stores and reads resume positions`() {
        val store = store()
        store.writeChunk(header(), byteArrayOf(1))
        assertEquals(0L, store.readResume("dQw4w9WgXcQ"))
        store.writeResume("dQw4w9WgXcQ", 42000L)
        assertEquals(42000L, store.readResume("dQw4w9WgXcQ"))
    }

    @Test
    fun `deletes a capture and its media`() {
        val store = store()
        store.writeChunk(header(), byteArrayOf(1))
        assertTrue(store.delete("dQw4w9WgXcQ"))
        assertFalse(store.videoDir("dQw4w9WgXcQ").exists())
        assertNull(store.entry("dQw4w9WgXcQ"))
    }

    @Test
    fun `keeps captures of different videos apart`() {
        val store = store()
        store.writeChunk(header(id = "one"), byteArrayOf(1, 2))
        store.writeChunk(header(id = "two"), byteArrayOf(1, 2, 3))
        assertEquals(2L, store.entry("one")!!.sizeBytes)
        assertEquals(3L, store.entry("two")!!.sizeBytes)
    }
}
