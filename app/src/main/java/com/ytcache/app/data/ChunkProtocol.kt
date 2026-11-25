package com.ytcache.app.data

import org.json.JSONObject
import java.util.Base64

/**
 * Wire format produced by assets/hooks/v1.js:
 *
 *   [uint32 little-endian header length][UTF-8 JSON header][raw media bytes]
 */
data class ChunkHeader(
    val videoId: String,
    val streamKey: String,
    val kind: String,
    val mime: String,
    val seq: Int,
    val isFirstAppend: Boolean,
    val byteLength: Int,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("v", 1)
        put("videoId", videoId)
        put("streamKey", streamKey)
        put("kind", kind)
        put("mime", mime)
        put("seq", seq)
        put("isFirstAppend", isFirstAppend)
        put("byteLength", byteLength)
    }
}

data class ParsedChunk(val header: ChunkHeader, val payload: ByteArray)

object ChunkProtocol {
    const val MAX_HEADER_BYTES = 64 * 1024
    /** Comfortably below the 4 MiB practical limit for a single bridge string. */
    const val MAX_FRAME_BYTES = 24 * 1024 * 1024

    class MalformedChunkException(message: String) : Exception(message)

    fun parse(frame: ByteArray): ParsedChunk {
        if (frame.size < 5) throw MalformedChunkException("frame too small: ${frame.size}")
        if (frame.size > MAX_FRAME_BYTES) throw MalformedChunkException("frame too large: ${frame.size}")
        val headerLength = readUInt32LE(frame, 0)
        if (headerLength <= 0) throw MalformedChunkException("empty header")
        if (headerLength > MAX_HEADER_BYTES) throw MalformedChunkException("header too large: $headerLength")
        val payloadStart = 4 + headerLength
        if (payloadStart > frame.size) throw MalformedChunkException("header overruns frame")

        val headerJson = String(frame, 4, headerLength, Charsets.UTF_8)
        val obj = try {
            JSONObject(headerJson)
        } catch (e: Exception) {
            throw MalformedChunkException("bad header json: ${e.message}")
        }

        val videoId = obj.optString("videoId", "")
        if (videoId.isBlank()) throw MalformedChunkException("missing videoId")
        val streamKey = obj.optString("streamKey", "")
        if (streamKey.isBlank()) throw MalformedChunkException("missing streamKey")

        val declaredLength = obj.optInt("byteLength", -1)
        val actualLength = frame.size - payloadStart
        if (declaredLength >= 0 && declaredLength != actualLength && actualLength > 0) {
            throw MalformedChunkException("byteLength mismatch: declared=$declaredLength actual=$actualLength")
        }
        if (actualLength <= 0) throw MalformedChunkException("empty payload")

        val header = ChunkHeader(
            videoId = videoId,
            streamKey = streamKey,
            kind = obj.optString("kind", "unknown"),
            mime = obj.optString("mime", ""),
            seq = obj.optInt("seq", 0).coerceAtLeast(0),
            isFirstAppend = obj.optBoolean("isFirstAppend", false),
            byteLength = actualLength,
        )
        return ParsedChunk(header, frame.copyOfRange(payloadStart, frame.size))
    }

    fun parseBase64(encoded: String): ParsedChunk {
        if (encoded.isBlank()) throw MalformedChunkException("empty payload")
        val bytes = try {
            Base64.getDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            throw MalformedChunkException("bad base64: ${e.message}")
        }
        return parse(bytes)
    }

    fun encode(header: ChunkHeader, payload: ByteArray): ByteArray {
        val headerBytes = header.copy(byteLength = payload.size)
            .toJson().toString().toByteArray(Charsets.UTF_8)
        val out = ByteArray(4 + headerBytes.size + payload.size)
        out[0] = (headerBytes.size and 0xFF).toByte()
        out[1] = ((headerBytes.size ushr 8) and 0xFF).toByte()
        out[2] = ((headerBytes.size ushr 16) and 0xFF).toByte()
        out[3] = ((headerBytes.size ushr 24) and 0xFF).toByte()
        System.arraycopy(headerBytes, 0, out, 4, headerBytes.size)
        System.arraycopy(payload, 0, out, 4 + headerBytes.size, payload.size)
        return out
    }

    private fun readUInt32LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
