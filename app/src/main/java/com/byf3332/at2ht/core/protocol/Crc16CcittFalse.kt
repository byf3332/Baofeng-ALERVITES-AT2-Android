package com.byf3332.at2ht.core.protocol

object Crc16CcittFalse {
    private const val POLY = 0x1021
    // Verified from btsnoop_hci-2..12 with reveng + full replay:
    // CRC-16/CCITT (non-reflected), polynomial 0x1021, init 0x1234.
    private const val INIT = 0x1234

    fun compute(data: ByteArray): Int {
        var crc = INIT
        for (byte in data) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) {
                    ((crc shl 1) xor POLY) and 0xFFFF
                } else {
                    (crc shl 1) and 0xFFFF
                }
            }
        }
        return crc and 0xFFFF
    }
}
