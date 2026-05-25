package com.byf3332.at2ht.core.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

object At2Commands {
    private fun cmd(vararg bytes: Int): ByteArray = bytes.map { (it and 0xFF).toByte() }.toByteArray()
    // --------------------
    // 0x020E / 0x020F
    // --------------------
    fun selectChannel(channel: Int): ByteArray {
        require(channel in 1..30) { "channel out of range: $channel" }
        return byteArrayOf(
            0x00, 0x02, 0x02, 0x0E,
            0x01,
            channel.toByte(),
            0x00
        )
    }

    fun selectDualWatchChannel(side: Side, channel: Int): ByteArray {
        require(channel in 1..30) { "channel out of range: $channel" }
        return byteArrayOf(
            0x00, 0x02, 0x02, 0x0E,
            side.code,
            channel.toByte(),
            0x00
        )
    }

    fun selectDualWatchFocus(side: Side): ByteArray {
        return byteArrayOf(
            0x00, 0x02, 0x02, 0x0F,
            side.code
        )
    }

    // --------------------
    // Device settings page (instant apply)
    // --------------------
    fun setDualWatch(enabled: Boolean): ByteArray =
        byteArrayOf(0x00, 0x02, 0x02, 0x0D, if (enabled) 0x02 else 0x00)

    fun setPromptLanguage(language: PromptLanguage): ByteArray =
        byteArrayOf(0x00, 0x02, 0x01, 0x03, language.code)

    fun setPromptTone(enabled: Boolean): ByteArray =
        byteArrayOf(0x00, 0x02, 0x01, 0x04, if (enabled) 0x01 else 0x00)

    fun setVolume(level: Int): ByteArray {
        require(level in 1..8) { "volume out of range: $level" }
        return byteArrayOf(0x00, 0x02, 0x01, 0x01, level.toByte())
    }

    fun setSquelch(level: Int): ByteArray {
        require(level in 0..9) { "squelch out of range: $level" }
        return byteArrayOf(0x00, 0x02, 0x02, 0x04, level.toByte())
    }

    fun setTotSeconds(seconds: Int): ByteArray {
        require(seconds in 0..240) { "TOT out of range: $seconds" }
        val le = shortLe(seconds)
        return byteArrayOf(0x00, 0x02, 0x02, 0x05) + le
    }

    fun setVox(enabled: Boolean): ByteArray =
        byteArrayOf(0x00, 0x02, 0x02, 0x06, if (enabled) 0x01 else 0x00)

    fun setVoxSensitivity(level: Int): ByteArray {
        require(level in 1..5) { "vox sensitivity out of range: $level" }
        return byteArrayOf(0x00, 0x02, 0x02, 0x07, level.toByte())
    }

    fun setTxInhibit(enabled: Boolean): ByteArray =
        byteArrayOf(0x00, 0x02, 0x02, 0x09, if (enabled) 0x01 else 0x00)

    fun setTxIntervalSeconds(seconds: Int): ByteArray {
        require(seconds in 0..240) { "tx interval out of range: $seconds" }
        val le = shortLe(seconds)
        return byteArrayOf(0x00, 0x02, 0x02, 0x0A) + le
    }

    fun setNoiseReduction(enabled: Boolean): ByteArray =
        byteArrayOf(0x00, 0x02, 0x02, 0x11, if (enabled) 0x01 else 0x00)

    // --------------------
    // Smart link / PTT mapping
    // --------------------
    fun querySmartLink(): ByteArray =
        byteArrayOf(0x00, 0x01, 0x04, 0x09)

    fun queryDualWatch(): ByteArray =
        byteArrayOf(0x00, 0x01, 0x02, 0x0D)

    fun queryPromptLanguage(): ByteArray =
        byteArrayOf(0x00, 0x01, 0x01, 0x03)

    fun queryPromptTone(): ByteArray =
        byteArrayOf(0x00, 0x01, 0x01, 0x04)

    fun queryVolume(): ByteArray =
        byteArrayOf(0x00, 0x01, 0x01, 0x01)

    fun querySquelch(): ByteArray =
        byteArrayOf(0x00, 0x01, 0x02, 0x04)

    fun queryTotSeconds(): ByteArray =
        byteArrayOf(0x00, 0x01, 0x02, 0x05)

    fun queryVox(): ByteArray =
        byteArrayOf(0x00, 0x01, 0x02, 0x06)

    fun queryVoxSensitivity(): ByteArray =
        byteArrayOf(0x00, 0x01, 0x02, 0x07)

    fun queryTxInhibit(): ByteArray =
        byteArrayOf(0x00, 0x01, 0x02, 0x09)

    fun queryTxIntervalSeconds(): ByteArray =
        byteArrayOf(0x00, 0x01, 0x02, 0x0A)

    fun queryNoiseReduction(): ByteArray =
        byteArrayOf(0x00, 0x01, 0x02, 0x11)

    fun queryMainPttLongPress(): ByteArray =
        byteArrayOf(0x00, 0x01, 0x04, 0x0A, 0x01)

    fun setSmartLink(enabled: Boolean): ByteArray =
        byteArrayOf(0x00, 0x02, 0x04, 0x09, if (enabled) 0x01 else 0x00)

    fun setMainPttLongPress(target: MainPttTarget): ByteArray =
        byteArrayOf(0x00, 0x02, 0x04, 0x0A, 0x01, target.code)

    // --------------------
    // Chat/PTT mode in offline comm page
    // --------------------
    fun setOfflineCommMode(pttMode: Boolean): ByteArray =
        byteArrayOf(0x00, 0x02, 0x04, 0x02, if (pttMode) 0x01 else 0x00)

    fun queryCurrentChannelConfig(): ByteArray = cmd(0x00, 0x01, 0x02, 0x02)
    fun queryCurrentChannelInfo(): ByteArray = cmd(0x00, 0x01, 0x02, 0x0E)
    fun triggerCodeplugWriteEntry(): ByteArray = cmd(0x00, 0x02, 0x02, 0x02)

    fun setDeviceName(nameUtf8: ByteArray): ByteArray =
        byteArrayOf(0x00, 0x02, 0x03, 0x01) + nameUtf8

    fun setDeviceName(name: String): ByteArray = setDeviceName(name.toByteArray(Charsets.UTF_8))

    // --------------------
    // Codeplug / channel records
    // --------------------
    fun setInstantParam(cmd: Int, value: ByteArray): ByteArray {
        require(cmd in 0x00..0xFF) { "invalid cmd: $cmd" }
        return byteArrayOf(0x00, 0x02, 0x02, cmd.toByte()) + value
    }

    fun setTxFrequency(mhz: Double): ByteArray {
        return setInstantParam(0x04, byteArrayOf(encodeFreqByte(mhz)))
    }

    fun buildCodeplugWrite(records: List<ByteArray>): List<ByteArray> {
        require(records.size == 30) { "need 30 channel records" }
        records.forEachIndexed { index, rec ->
            require(rec.size == 24) { "record ${index + 1} must be 24 bytes" }
        }
        val raw = records.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
        val chunks = raw.asList().chunked(168).map { it.toByteArray() }
        return chunks.map { chunk ->
            byteArrayOf(0x00, 0x02, 0x02, 0x02) + chunk
        }
    }

    fun emptyChannelRecord(channel: Int): ByteArray {
        require(channel in 1..30)
        return ByteArray(24).also {
            it[0] = 0x01
            it[1] = channel.toByte()
            it[11] = 0x7F.toByte()
            it[13] = 0x7F.toByte()
            it[12] = 0x00
            it[14] = 0x00
            it[15] = 0x00
            it[16] = 0x00
        }
    }

    fun encodeFrequencyUInt32(mhz: Double): ByteArray {
        val raw = (mhz * 100000.0).toLong()
        require(raw in 0..0xFFFF_FFFFL) { "frequency out of range: $mhz" }
        return ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(raw.toInt())
            .array()
    }

    private fun encodeFreqByte(mhz: Double): Byte {
        val raw = (mhz * 100000.0).toLong()
        return (raw and 0xFF).toByte()
    }

    private fun shortLe(value: Int): ByteArray {
        require(value in 0..0xFFFF)
        return byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())
    }

    enum class Side(val code: Byte) {
        A(0x01),
        B(0x02),
    }

    enum class PromptLanguage(val code: Byte) {
        Chinese(0x00),
        English(0x01),
    }

    enum class MainPttTarget(val code: Byte) {
        Other1(0x01),
        Zello(0x06),
        Other3(0x08),
        Other4(0x09),
        Other2(0x0A),
        Olaradio(0x0B),
        ;

        companion object {
            fun fromCodeOrNull(code: Byte): MainPttTarget? = entries.firstOrNull { it.code == code }
        }
    }
}
