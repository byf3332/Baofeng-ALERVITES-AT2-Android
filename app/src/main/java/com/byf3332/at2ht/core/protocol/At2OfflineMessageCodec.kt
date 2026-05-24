package com.byf3332.at2ht.core.protocol

import com.byf3332.at2ht.AppStrings

class At2OfflineMessageCodec {
    fun decode(packet: At2Packet): OfflineFrame? {
        if (packet.family != 0x02 || packet.command != 0x04) return null
        val p = packet.payload
        if (p.size < 2 || p[0] != 0x01.toByte()) return null
        val type = p[1].toInt() and 0xFF
        return when (type) {
            0x01, 0x03, 0x05 -> decodeStartFrame(type, p)
            0x02, 0x04, 0x06 -> decodeChunkFrame(type, p)
            else -> null
        }
    }

    fun buildTextFrames(username: String, text: String, msgId: UInt): List<ByteArray> {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val sender = encodeSender(username)
        return if (textBytes.size <= SHORT_TEXT_INLINE_MAX_BYTES) {
            listOf(buildSingleTextFrame(msgId, sender, textBytes))
        } else {
            buildFragmentedTextFrames(msgId, sender, textBytes)
        }
    }

    fun buildVoiceFrames(username: String, encodedVoice: ByteArray, durationMs: Int, msgId: UInt): List<ByteArray> {
        require(encodedVoice.isNotEmpty()) { "voice data empty" }
        require(durationMs > 0) { "voice duration invalid" }
        val sender = encodeSender(username)
        val packetCount = ((encodedVoice.size + VOICE_CHUNK_BYTES - 1) / VOICE_CHUNK_BYTES).coerceAtLeast(1)
        val durationSeconds = ((durationMs + 999) / 1000).coerceAtLeast(1)
        val start = byteArrayOf(0x00, 0x02, 0x04, 0x01, 0x03) +
            msgId.toByteArray() +
            byteArrayOf(0x00) +
            sender +
            le16(encodedVoice.size) +
            le16(packetCount) +
            le16(durationSeconds)
        val chunks = encodedVoice.asList().chunked(VOICE_CHUNK_BYTES).mapIndexed { index, chunk ->
            byteArrayOf(0x00, 0x02, 0x04, 0x01, 0x04) +
                msgId.toByteArray() +
                be16(index) +
                byteArrayOf(0x00) +
                chunk.toByteArray()
        }
        return listOf(start) + chunks
    }

    fun buildImageFrames(
        username: String,
        jpegBytes: ByteArray,
        originalWidth: Int,
        originalHeight: Int,
        msgId: UInt,
    ): List<ByteArray> {
        require(jpegBytes.isNotEmpty()) { "image data empty" }
        require(originalWidth > 0 && originalHeight > 0) { "image size invalid" }
        val sender = encodeSender(username)
        val parts = jpegBytes.asList().chunked(IMAGE_CHUNK_BYTES).map { it.toByteArray() }
        val partCount = parts.size.coerceAtLeast(1)
        val start = byteArrayOf(0x00, 0x02, 0x04, 0x01, 0x05) +
            msgId.toByteArray() +
            byteArrayOf(0x00) +
            sender +
            le16(jpegBytes.size) +
            byteArrayOf(partCount.toByte()) +
            byteArrayOf(0x00) +
            le16(originalWidth) +
            le16(originalHeight)
        val chunks = parts.mapIndexed { index, chunk ->
            byteArrayOf(0x00, 0x02, 0x04, 0x01, 0x06) +
                msgId.toByteArray() +
                be16(index) +
                byteArrayOf(0x00) +
                chunk
        }
        return listOf(start) + chunks
    }

    private fun decodeStartFrame(type: Int, payload: ByteArray): OfflineStartFrame? {
        return when (type) {
            0x05 -> decodeImageStartFrame(payload)
            else -> decodeTextOrVoiceStartFrame(type, payload)
        }
    }

    private fun decodeChunkFrame(type: Int, payload: ByteArray): OfflineChunk? {
        if (payload.size < 9) return null
        val msgId = readMsgId(payload, 2)
        val seq = readBe16(payload, 6)
        val data = payload.copyOfRange(8, payload.size)
        return OfflineChunk(type = type, msgId = msgId, seq = seq, data = data)
    }

    private fun buildSingleTextFrame(msgId: UInt, sender: ByteArray, textBytes: ByteArray): ByteArray {
        require(textBytes.size <= SHORT_TEXT_INLINE_MAX_BYTES) { "short text too large" }
        return byteArrayOf(0x00, 0x02, 0x04, 0x01, 0x01) +
            msgId.toByteArray() +
            byteArrayOf(0x00) +
            sender +
            le16(textBytes.size) +
            byteArrayOf(0x01, 0x00) +
            textBytes
    }

    private fun buildFragmentedTextFrames(msgId: UInt, sender: ByteArray, textBytes: ByteArray): List<ByteArray> {
        val parts = textBytes.asList().chunked(FRAGMENT_TEXT_CHUNK_BYTES).map { it.toByteArray() }
        val totalParts = parts.size.coerceAtMost(0xFF)
        require(totalParts == parts.size) { "too many text fragments" }
        val streamLength = parts.sumOf { it.size + 1 }
        val start = byteArrayOf(0x00, 0x02, 0x04, 0x01, 0x01) +
            msgId.toByteArray() +
            byteArrayOf(0x00) +
            sender +
            le16(streamLength) +
            byteArrayOf(totalParts.toByte(), 0x00)
        val chunks = parts.mapIndexed { index, chunk ->
            byteArrayOf(0x00, 0x02, 0x04, 0x01, 0x02) +
                msgId.toByteArray() +
                be16(index) +
                byteArrayOf(0x00) +
                chunk
        }
        return listOf(start) + chunks
    }

    private fun decodeTextOrVoiceStartFrame(type: Int, payload: ByteArray): OfflineStartFrame? {
        if (type == 0x03) return decodeVoiceStartFrame(payload)
        if (payload.size < 26) return null
        val msgId = readMsgId(payload, 2)
        val sender = decodeSender(payload.copyOfRange(7, 23))
        val declaredLength = readLe16(payload, 23)
        val totalParts = payload[25].toInt() and 0xFF
        val seq = if (payload.size > 26) payload[26].toInt() and 0xFF else 0
        val inlineData = if (payload.size > 26) payload.copyOfRange(26, payload.size) else ByteArray(0)
        return OfflineStartFrame(type, msgId, sender, declaredLength, totalParts, seq, inlineData)
    }

    private fun decodeVoiceStartFrame(payload: ByteArray): OfflineStartFrame? {
        if (payload.size < 29) return null
        val msgId = readMsgId(payload, 2)
        val sender = decodeSender(payload.copyOfRange(7, 23))
        val declaredLength = readLe16(payload, 23)
        val totalParts = readLe16(payload, 25)
        val durationSeconds = readLe16(payload, 27).coerceAtLeast(1)
        return OfflineStartFrame(
            type = 0x03,
            msgId = msgId,
            sender = sender,
            declaredLength = declaredLength,
            totalParts = totalParts,
            seq = 0,
            inlineData = ByteArray(0),
            durationMs = durationSeconds * 1000,
        )
    }

    private fun decodeImageStartFrame(payload: ByteArray): OfflineStartFrame? {
        if (payload.size < 31) return null
        val msgId = readMsgId(payload, 2)
        val sender = decodeSender(payload.copyOfRange(7, 23))
        val declaredLength = readLe16(payload, 23)
        val totalParts = payload[25].toInt() and 0xFF
        return OfflineStartFrame(
            type = 0x05,
            msgId = msgId,
            sender = sender,
            declaredLength = declaredLength,
            totalParts = totalParts,
            seq = 0,
            inlineData = payload.copyOfRange(26, payload.size),
        )
    }

    private fun readMsgId(bytes: ByteArray, start: Int): UInt {
        return ((bytes[start].toUInt() and 0xFFu) shl 24) or
            ((bytes[start + 1].toUInt() and 0xFFu) shl 16) or
            ((bytes[start + 2].toUInt() and 0xFFu) shl 8) or
            (bytes[start + 3].toUInt() and 0xFFu)
    }

    private fun readLe16(bytes: ByteArray, start: Int): Int {
        return (bytes[start].toInt() and 0xFF) or ((bytes[start + 1].toInt() and 0xFF) shl 8)
    }

    private fun readBe16(bytes: ByteArray, start: Int): Int {
        return ((bytes[start].toInt() and 0xFF) shl 8) or (bytes[start + 1].toInt() and 0xFF)
    }

    private fun be16(value: Int): ByteArray {
        require(value in 0..0xFFFF) { "value out of range: $value" }
        return byteArrayOf(((value shr 8) and 0xFF).toByte(), (value and 0xFF).toByte())
    }

    private fun le16(value: Int): ByteArray {
        require(value in 0..0xFFFF) { "value out of range: $value" }
        return byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())
    }

    private fun decodeSender(bytes: ByteArray): String =
        bytes.toString(Charsets.UTF_8).trimEnd(' ', '\u0000').ifBlank { AppStrings.chatSenderDefault() }

    private fun encodeSender(username: String): ByteArray {
        val normalized = username.trim().ifBlank { DEFAULT_USERNAME }
        val out = ByteArray(SENDER_FIELD_BYTES) { 0x20 }
        var offset = 0
        normalized.forEach { ch ->
            val bytes = ch.toString().toByteArray(Charsets.UTF_8)
            if (offset + bytes.size > SENDER_FIELD_BYTES) return@forEach
            bytes.copyInto(out, offset)
            offset += bytes.size
        }
        return out
    }

    private fun UInt.toByteArray(): ByteArray = byteArrayOf(
        ((this shr 24) and 0xFFu).toByte(),
        ((this shr 16) and 0xFFu).toByte(),
        ((this shr 8) and 0xFFu).toByte(),
        (this and 0xFFu).toByte(),
    )

    companion object {
        const val SENDER_FIELD_BYTES = 16
        const val SHORT_TEXT_INLINE_MAX_BYTES = 180
        const val FRAGMENT_TEXT_CHUNK_BYTES = 131
        const val VOICE_CHUNK_BYTES = 132
        const val IMAGE_CHUNK_BYTES = 132
        const val IMAGE_LONG_EDGE_PX = 300
        const val IMAGE_JPEG_QUALITY = 75
        const val DEFAULT_USERNAME = "AT2HT"
    }
}
