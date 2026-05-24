package com.byf3332.at2ht.core.protocol

class OfflineMessageAssembler {
    private data class Partial(
        var type: Int = 0,
        var sender: String? = null,
        var declaredLength: Int? = null,
        var expectedParts: Int? = null,
        var durationMs: Int? = null,
        val chunks: MutableMap<Int, ByteArray> = linkedMapOf(),
    )

    private val partials = linkedMapOf<UInt, Partial>()

    fun consume(frame: OfflineFrame): OfflineEvent? = when (frame) {
        is OfflineStartFrame -> consumeStart(frame)
        is OfflineChunk -> consumeChunk(frame)
    }

    fun clear() = partials.clear()

    private fun consumeStart(frame: OfflineStartFrame): OfflineEvent? {
        val partial = partials.getOrPut(frame.msgId) { Partial() }
        partial.type = frame.type
        partial.sender = frame.sender.takeIf { it.isNotBlank() }
        partial.declaredLength = frame.declaredLength.takeIf { it > 0 }
        partial.expectedParts = frame.totalParts.takeIf { it > 0 }
        partial.durationMs = frame.durationMs

        return when (frame.type) {
            0x01 -> {
                if (frame.totalParts > 1) {
                    null
                } else {
                    val payload = trimToDeclared(stripLeadingControlByte(frame.inlineData), partial.declaredLength)
                    val text = parseUtf8(payload)
                    partials.remove(frame.msgId)
                    OfflineEvent.Text(frame.msgId, partial.sender, text)
                }
            }
            0x03 -> null
            0x05 -> null
            else -> OfflineEvent.UnknownStartFrame(frame.msgId, frame.type, partial.sender, frame.inlineData)
        }
    }

    private fun consumeChunk(chunk: OfflineChunk): OfflineEvent? {
        if (chunk.type != 0x02 && chunk.type != 0x04 && chunk.type != 0x06) return null
        val partial = partials.getOrPut(chunk.msgId) { Partial(type = chunk.type) }
        if (partial.type == 0) partial.type = chunk.type
        partial.chunks[chunk.seq] = chunk.data

        val orderedKeys = partial.chunks.keys.sorted()
        val contiguousKeys = if (orderedKeys.isEmpty()) false else orderedKeys == (0..orderedKeys.last()).toList()
        val expectedParts = partial.expectedParts
        val fullTextChunkSize = At2OfflineMessageCodec.FRAGMENT_TEXT_CHUNK_BYTES + 1
        val fullVoiceChunkSize = partial.chunks.values.maxOfOrNull { it.size } ?: 0
        val lastChunkSize = partial.chunks[orderedKeys.lastOrNull() ?: 0]?.size ?: 0
        val complete = when {
            expectedParts != null -> partial.chunks.size >= expectedParts && orderedKeys == (0 until expectedParts).toList()
            chunk.type == 0x02 -> contiguousKeys && lastChunkSize in 1 until fullTextChunkSize
            chunk.type == 0x04 -> contiguousKeys && fullVoiceChunkSize > 0 && lastChunkSize in 1 until fullVoiceChunkSize
            else -> false
        }
        if (!complete) return null

        val merged = mergeChunks(orderedKeys.mapNotNull { partial.chunks[it] })
        partials.remove(chunk.msgId)
        return when (chunk.type) {
            0x02 -> OfflineEvent.Text(chunk.msgId, partial.sender, parseUtf8(trimToDeclared(merged, partial.declaredLength)))
            0x04 -> OfflineEvent.VoiceMessage(
                chunk.msgId,
                partial.sender,
                trimToDeclared(merged, partial.declaredLength),
                partial.durationMs,
            )
            0x06 -> extractJpeg(trimToDeclared(merged, partial.declaredLength))?.let { OfflineEvent.Image(chunk.msgId, partial.sender, it) }
            else -> null
        }
    }

    private fun mergeChunks(values: List<ByteArray>): ByteArray {
        val stripped = values.map { part ->
            if (part.isNotEmpty() && part[0] == 0.toByte()) part.copyOfRange(1, part.size) else part
        }
        val total = stripped.sumOf { it.size }
        val out = ByteArray(total)
        var off = 0
        stripped.forEach { p ->
            p.copyInto(out, off)
            off += p.size
        }
        return out
    }

    private fun stripLeadingControlByte(bytes: ByteArray): ByteArray {
        return if (bytes.isNotEmpty() && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    }
    private fun trimToDeclared(bytes: ByteArray, declaredLength: Int?): ByteArray {
        if (declaredLength == null || declaredLength <= 0 || bytes.isEmpty()) return bytes
        return bytes.copyOfRange(0, minOf(bytes.size, declaredLength))
    }

    private fun parseUtf8(data: ByteArray): String =
        data.filter { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8).trim()

    private fun extractJpeg(bytes: ByteArray): ByteArray? {
        val soi = bytes.indexOfPair(0xFF.toByte(), 0xD8.toByte())
        val eoi = bytes.lastIndexOfPair(0xFF.toByte(), 0xD9.toByte())
        if (soi < 0 || eoi < soi) return null
        return bytes.copyOfRange(soi, eoi + 2)
    }

    private fun ByteArray.indexOfPair(a: Byte, b: Byte): Int {
        for (i in 0 until size - 1) if (this[i] == a && this[i + 1] == b) return i
        return -1
    }

    private fun ByteArray.lastIndexOfPair(a: Byte, b: Byte): Int {
        for (i in size - 2 downTo 0) if (this[i] == a && this[i + 1] == b) return i
        return -1
    }
}
