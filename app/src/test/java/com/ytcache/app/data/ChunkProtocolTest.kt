package com.ytcache.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ChunkProtocolTest {

    private fun header(seq: Int = 0, videoId: String = "dQw4w9WgXcQ") = ChunkHeader(
        videoId = videoId,
        streamKey = "s0-video",
        kind = "video",
        mime = "video/mp4; codecs=\"av01.0.05M.08\"",
        seq = seq,
        isFirstAppend = seq == 0,
        byteLength = 0,
    )

    @Test
    fun `round trips a frame byte for byte`() {
        val payload = ByteArray(1337) { (it % 251).toByte() }
        val parsed = ChunkProtocol.parse(ChunkProtocol.encode(header(seq = 3), payload))
        assertEquals("dQw4w9WgXcQ", parsed.header.videoId)
        assertEquals("s0-video", parsed.header.streamKey)
        assertEquals("video", parsed.header.kind)
        assertEquals(3, parsed.header.seq)
        assertEquals(false, parsed.header.isFirstAppend)
        assertEquals(payload.size, parsed.header.byteLength)
        assertArrayEquals(payload, parsed.payload)
    }

    @Test
    fun `marks the init segment`() {
        val parsed = ChunkProtocol.parse(ChunkProtocol.encode(header(seq = 0), byteArrayOf(1, 2, 3)))
        assertEquals(true, parsed.header.isFirstAppend)
        assertEquals(0, parsed.header.seq)
    }

    @Test
    fun `parses base64 frames`() {
        val encoded = java.util.Base64.getEncoder()
            .encodeToString(ChunkProtocol.encode(header(seq = 7), byteArrayOf(9, 8, 7)))
        val parsed = ChunkProtocol.parseBase64(encoded)
        assertEquals(7, parsed.header.seq)
        assertArrayEquals(byteArrayOf(9, 8, 7), parsed.payload)
    }

    @Test
    fun `rejects a truncated frame`() {
        assertThrows(ChunkProtocol.MalformedChunkException::class.java) {
            ChunkProtocol.parse(byteArrayOf(1, 2))
        }
    }

    @Test
    fun `rejects a header that overruns the frame`() {
        val frame = ByteArray(8)
        frame[3] = 0x7F // absurd header length
        assertThrows(ChunkProtocol.MalformedChunkException::class.java) {
            ChunkProtocol.parse(frame)
        }
    }

    @Test
    fun `rejects a byteLength that disagrees with the payload`() {
        val headerBytes = """{"videoId":"abc","streamKey":"s0-video","byteLength":99}""".toByteArray()
        val frame = ByteArray(4 + headerBytes.size + 3)
        frame[0] = (headerBytes.size and 0xFF).toByte()
        frame[1] = ((headerBytes.size ushr 8) and 0xFF).toByte()
        System.arraycopy(headerBytes, 0, frame, 4, headerBytes.size)
        assertThrows(ChunkProtocol.MalformedChunkException::class.java) {
            ChunkProtocol.parse(frame)
        }
    }

    @Test
    fun `rejects a frame without a video id`() {
        val headerBytes = """{"streamKey":"s0-video","byteLength":1}""".toByteArray()
        val frame = ByteArray(4 + headerBytes.size + 1)
        frame[0] = (headerBytes.size and 0xFF).toByte()
        frame[1] = ((headerBytes.size ushr 8) and 0xFF).toByte()
        System.arraycopy(headerBytes, 0, frame, 4, headerBytes.size)
        assertThrows(ChunkProtocol.MalformedChunkException::class.java) {
            ChunkProtocol.parse(frame)
        }
    }

    @Test
    fun `rejects invalid base64`() {
        assertThrows(ChunkProtocol.MalformedChunkException::class.java) {
            ChunkProtocol.parseBase64("not base64 %%%")
        }
    }
}
