package com.byf3332.at2ht.core.protocol

import kotlin.math.min

class At2ProtocolDecoder(
    private val frameCodec: At2FrameCodec = At2FrameCodec(),
    private val offlineCodec: At2OfflineMessageCodec = At2OfflineMessageCodec(),
) {
    fun decodeFrame(frame: ByteArray): At2Packet? {
        val payload = frameCodec.tryDecode(frame) ?: return null
        if (payload.size < 4 || payload[0] != 0.toByte()) return null
        val family = payload[1].toInt() and 0xFF
        val command = payload[2].toInt() and 0xFF
        val body = payload.copyOfRange(3, payload.size)
        return At2Packet(family, command, body)
    }

    fun decodeOfflineFrame(packet: At2Packet): OfflineFrame? = offlineCodec.decode(packet)

    fun decodeRealtimeVoiceChunk(packet: At2Packet): ByteArray? {
        if (packet.family != 0x02 || packet.command != 0x04) return null
        val p = packet.payload
        if (p.size < 3 || p[0] != 0x03.toByte()) return null
        var start = 1
        while (start < p.size && p[start] == 0.toByte() && ((p.size - (start + 1)) % 12 != 0 || start < 3)) {
            start += 1
        }
        val data = p.copyOfRange(start, p.size)
        return if (data.isNotEmpty() && data.size % 12 == 0) data else null
    }

    companion object {
        fun ByteArray.hexPreview(maxBytes: Int = 24): String {
            val end = min(size, maxBytes)
            return copyOfRange(0, end).joinToString("") { "%02x".format(it) }
        }
    }
}