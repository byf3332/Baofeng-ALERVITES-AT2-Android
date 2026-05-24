package com.byf3332.at2ht.core.protocol

class At2FrameCodec(
    private val crc16: (ByteArray) -> Int = Crc16CcittFalse::compute,
) {
    companion object {
        private const val HEAD_0: Byte = 0xAA.toByte()
        private const val HEAD_1: Byte = 0x55.toByte()
        private const val TAIL_0: Byte = 0x77
        private const val TAIL_1: Byte = 0xEE.toByte()
    }

    fun encode(payload: ByteArray): ByteArray {
        require(payload.isNotEmpty()) { "AT2 payload must not be empty" }
        require(payload[0] == 0x00.toByte()) { "AT2 payload must start with 0x00" }
        require(payload.size - 1 <= 0xFF) { "AT2 payload too long: ${payload.size}" }

        val crcInput = payload.copyOfRange(1, payload.size)
        val crc = crc16(crcInput)
        val bodyLen = payload.size - 1
        val out = ByteArray(2 + 1 + payload.size + 2 + 2)
        var idx = 0
        out[idx++] = HEAD_0
        out[idx++] = HEAD_1
        // Protocol length excludes the leading 0x00 in payload.
        out[idx++] = bodyLen.toByte()
        payload.copyInto(out, idx)
        idx += payload.size
        // Protocol stores CRC in little-endian order.
        out[idx++] = (crc and 0xFF).toByte()
        out[idx++] = ((crc shr 8) and 0xFF).toByte()
        out[idx++] = TAIL_0
        out[idx] = TAIL_1
        return out
    }

    fun tryDecode(frame: ByteArray): ByteArray? {
        if (frame.size < 7) return null
        for (start in 0..frame.size - 7) {
            if (frame[start] != HEAD_0 || frame[start + 1] != HEAD_1) continue
            val len = frame[start + 2].toInt() and 0xFF

            run {
                val expected = 2 + 1 + (len + 1) + 2 + 2
                val end = start + expected
                if (end <= frame.size && frame[end - 2] == TAIL_0 && frame[end - 1] == TAIL_1) {
                    val payloadStart = start + 3
                    val payloadEnd = payloadStart + len + 1
                    val payload = frame.copyOfRange(payloadStart, payloadEnd)
                    if (payload.isNotEmpty() && payload[0] == 0x00.toByte()) {
                        val gotCrc = (frame[payloadEnd].toInt() and 0xFF) or
                            ((frame[payloadEnd + 1].toInt() and 0xFF) shl 8)
                        val calcCrc = crc16(payload.copyOfRange(1, payload.size))
                        if (gotCrc == calcCrc) return payload
                    }
                }
            }

            run {
                val expected = 2 + 1 + len + 2 + 2
                val end = start + expected
                if (end <= frame.size && frame[end - 2] == TAIL_0 && frame[end - 1] == TAIL_1) {
                    val payloadStart = start + 3
                    val payloadEnd = payloadStart + len
                    val payloadNo00 = frame.copyOfRange(payloadStart, payloadEnd)
                    val gotCrc = (frame[payloadEnd].toInt() and 0xFF) or
                        ((frame[payloadEnd + 1].toInt() and 0xFF) shl 8)
                    val calcCrc = crc16(payloadNo00)
                    if (gotCrc == calcCrc) {
                        return byteArrayOf(0x00) + payloadNo00
                    }
                }
            }
        }
        return null
    }
}
