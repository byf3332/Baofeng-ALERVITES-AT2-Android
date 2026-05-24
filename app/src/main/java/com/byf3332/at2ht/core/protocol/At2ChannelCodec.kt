package com.byf3332.at2ht.core.protocol

import java.math.BigDecimal
import java.math.RoundingMode

object At2ChannelCodec {
    data class SubtoneEncoding(
        val value: Int,
        val type: Int,
        val polarity: Int,
    )

    private val ctcssValues = listOf(
        "67.0", "69.3", "71.9", "74.4", "77.0", "79.7", "82.5", "85.4", "88.5", "91.5",
        "94.8", "97.4", "100.0", "103.5", "107.2", "110.9", "114.8", "118.8", "123.0", "127.3",
        "131.8", "136.5", "141.3", "146.2", "150.0", "151.4", "156.7", "159.8", "162.2", "165.5",
        "167.9", "171.3", "173.8", "177.3", "179.9", "183.5", "186.2", "189.9", "192.8", "196.6",
        "199.5", "203.5", "206.5", "210.7", "218.1", "225.7", "229.1", "233.6", "241.8", "250.3",
        "254.1"
    )

    private val dcsValues = listOf(
        "D023", "D025", "D026", "D031", "D032", "D036", "D043", "D047", "D051", "D053",
        "D054", "D065", "D071", "D072", "D073", "D074", "D114", "D115", "D116", "D122",
        "D125", "D131", "D132", "D134", "D143", "D145", "D152", "D155", "D156", "D162",
        "D165", "D172", "D174", "D205", "D212", "D223", "D225", "D226", "D243", "D244",
        "D245", "D246", "D251", "D252", "D255", "D261", "D263", "D265", "D266", "D271",
        "D274", "D306", "D311", "D315", "D325", "D331", "D332", "D343", "D346", "D351",
        "D356", "D364", "D365", "D371", "D411", "D412", "D413", "D423", "D431", "D432",
        "D445", "D446", "D452", "D454", "D455", "D462", "D464", "D465", "D466", "D503",
        "D506", "D516", "D523", "D526", "D532", "D546", "D565", "D606", "D612", "D624",
        "D627", "D631", "D632", "D645", "D654", "D662", "D664", "D703", "D712", "D723",
        "D731", "D732", "D734", "D743", "D754"
    )

    fun parseFreqOrNull(raw: String): Double? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        return text.toDoubleOrNull()
    }

    fun toneOptions(): List<String> = buildList {
        add("OFF")
        addAll(ctcssValues.map { "${it}Hz" })
        addAll(dcsValues.map { "${it}N" })
        addAll(dcsValues.map { "${it}I" })
    }

    fun toneLabel(value: Int, type: Int, polarity: Int): String {
        if (value == 0x7F && type == 0x00) return "OFF"
        return when (type) {
            0x00 -> ctcssValues.getOrNull(value)?.let { "${it}Hz" } ?: "OFF"
            0x01 -> dcsValues.getOrNull(value)?.let { it + if (polarity == 0x01) "I" else "N" } ?: "OFF"
            else -> "OFF"
        }
    }

    fun parseToneLabel(value: String): SubtoneEncoding? {
        val text = value.trim().uppercase()
        if (text == "OFF") return SubtoneEncoding(0x7F, 0x00, 0x00)

        if (text.endsWith("N") || text.endsWith("I")) {
            val base = text.dropLast(1)
            val index = dcsValues.indexOf(base)
            if (index >= 0) {
                return SubtoneEncoding(
                    value = index,
                    type = 0x01,
                    polarity = if (text.endsWith("I")) 0x01 else 0x00
                )
            }
        }

        val normalizedCtcss = text.removeSuffix("HZ")
        val ctcssIndex = ctcssValues.indexOf(normalizedCtcss)
        if (ctcssIndex >= 0) {
            return SubtoneEncoding(ctcssIndex, 0x00, 0x00)
        }
        return null
    }

    fun encryptOptions(): List<String> = buildList {
        add("OFF")
        addAll((1..31).map { it.toString() })
    }

    fun parseEncryptLabel(value: String): Int? {
        val text = value.trim().uppercase()
        if (text == "OFF") return 0
        val numeric = text.toIntOrNull() ?: return null
        return numeric.takeIf { it in 1..31 }
    }

    fun encodeSingleChannelRecord(channel: Int, config: ChannelConfig): ByteArray {
        val record = At2Commands.emptyChannelRecord(channel)
        writeFreqToRecord(record, 3, config.rxMhz)
        writeFreqToRecord(record, 7, config.txMhz)
        record[11] = (config.rxTone and 0xFF).toByte()
        record[12] = (config.rxToneType and 0xFF).toByte()
        record[13] = (config.txTone and 0xFF).toByte()
        record[14] = (config.txToneType and 0xFF).toByte()
        record[15] = (config.rxTonePolarity and 0xFF).toByte()
        record[16] = (config.txTonePolarity and 0xFF).toByte()
        record[17] = if (config.busyLock) 0x00 else 0x01
        record[18] = if (config.bandwidthNarrow) 0x01 else 0x00
        record[19] = if (config.highPower) 0x01 else 0x00
        record[20] = if (config.scanAdd) 0x00 else 0x01
        record[21] = if (config.hopOn) 0x01 else 0x00
        record[22] = if (config.modeDigital) 0x01 else 0x00
        record[23] = (config.encryptKey and 0xFF).toByte()
        return record
    }

    fun encodeFrequencyExact(mhz: Double): ByteArray {
        val raw = BigDecimal.valueOf(mhz)
            .movePointRight(5)
            .setScale(0, RoundingMode.HALF_UP)
            .toLong()
        require(raw in 0..0xFFFF_FFFFL) { "frequency out of range: $mhz" }
        return byteArrayOf(
            (raw and 0xFF).toByte(),
            ((raw shr 8) and 0xFF).toByte(),
            ((raw shr 16) and 0xFF).toByte(),
            ((raw shr 24) and 0xFF).toByte(),
        )
    }

    private fun writeFreqToRecord(record: ByteArray, offset: Int, mhz: Double?) {
        val bytes = if (mhz == null) byteArrayOf(0, 0, 0, 0) else encodeFrequencyExact(mhz)
        bytes.copyInto(record, destinationOffset = offset)
    }
}
