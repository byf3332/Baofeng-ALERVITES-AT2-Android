package com.byf3332.at2ht.core.ble

import java.util.UUID

object BleConstants {
    private fun shortUuid16(hex: String): UUID =
        UUID.fromString("0000$hex-0000-1000-8000-00805F9B34FB")

    // Fixed from btsnoop mapping across hci-2..hci-12:
    // handle 0x0013 -> AE10 (write)
    // handle 0x0010 -> AE05 (indicate)
    val SERVICE_UUID: UUID = shortUuid16("AE60")
    val TX_CHAR_UUID: UUID = shortUuid16("AE10")
    val RX_CHAR_UUID: UUID = shortUuid16("AE05")
}
