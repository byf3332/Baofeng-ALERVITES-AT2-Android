package com.byf3332.at2ht.core.protocol

class At2RxState {
    private val pending = ArrayList<Byte>(4096)
    private var collecting0202 = false
    private val collect0202 = ArrayList<Byte>(2048)
    private var started0202Payload = false
    private val seenChannels = linkedSetOf<Int>()
    val seenChannelCount: Int
        get() = seenChannels.size
    var lastCurrentChannel: Int = -1
        private set
    var lastDualWatchChannelA: Int = -1
        private set
    var lastDualWatchChannelB: Int = -1
        private set

    fun reset() {
        pending.clear()
        collecting0202 = false
        collect0202.clear()
        started0202Payload = false
        seenChannels.clear()
        lastCurrentChannel = -1
        lastDualWatchChannelA = -1
        lastDualWatchChannelB = -1
    }

    fun feed(chunk: ByteArray): List<Pair<Int, ChannelConfig>> {
        val updates = mutableListOf<Pair<Int, ChannelConfig>>()
        updates += scanChannelRecords(chunk).onEach { seenChannels.add(it.first) }
        chunk.forEach { pending.add(it) }
        while (true) {
            val frame = extractOneAtFrame() ?: break
            val payload = decodePayloadVariant(frame) ?: continue
            if (payload.size >= 3 && payload[0] == 0x81.toByte() && payload[1] == 0x02.toByte()) {
                val cmd = payload[2].toInt() and 0xFF
                val body = payload.copyOfRange(3, payload.size)
                if (cmd == 0x0E && body.size >= 4 && body[0] == 0x01.toByte()) {
                    val ch = body[1].toInt() and 0xFF
                    if (ch in 1..30) lastCurrentChannel = ch
                    if (body.size >= 6 && body[3] == 0x02.toByte()) {
                        val chA = body[1].toInt() and 0xFF
                        val chB = body[4].toInt() and 0xFF
                        if (chA in 1..30) lastDualWatchChannelA = chA
                        if (chB in 1..30) lastDualWatchChannelB = chB
                    }
                }
                if (cmd == 0x02) {
                    updates += scanChannelRecords(body).onEach { seenChannels.add(it.first) }
                }
            }
        }

        if (chunk.size >= 6 && chunk[0] == 0xAA.toByte() && chunk[1] == 0x55.toByte() && chunk[3] == 0x02.toByte() && chunk[4] == 0x81.toByte() && chunk[5] == 0x02.toByte()) {
            collecting0202 = true
            started0202Payload = true
            collect0202.clear()
        }
        if (collecting0202) {
            chunk.forEach { collect0202.add(it) }
            if (started0202Payload) {
                val all = collect0202.toByteArray()
                updates += scanChannelRecords(all).onEach { seenChannels.add(it.first) }
            }
            val n = collect0202.size
            if (n >= 2 && collect0202[n - 2] == 0x77.toByte() && collect0202[n - 1] == 0xEE.toByte()) {
                collecting0202 = false
                started0202Payload = false
            }
        }

        return updates.distinctBy { it.first }
    }

    private fun decodePayloadVariant(frame: ByteArray): ByteArray? {
        if (frame.size < 8) return null
        val len = frame[2].toInt() and 0xFF
        val t1 = 2 + 1 + (len + 1) + 2 + 2
        if (frame.size == t1) {
            val body = frame.copyOfRange(3, 3 + len + 1)
            if (body.isNotEmpty() && body[0] == 0x00.toByte()) return body.copyOfRange(1, body.size)
        }
        val t2 = 2 + 1 + len + 2 + 2
        if (frame.size == t2) {
            return frame.copyOfRange(3, 3 + len)
        }
        return null
    }

    private fun extractOneAtFrame(): ByteArray? {
        val n = pending.size
        if (n < 8) return null
        var start = -1
        for (i in 0 until n - 1) {
            if (pending[i] == 0xAA.toByte() && pending[i + 1] == 0x55.toByte()) {
                start = i
                break
            }
        }
        if (start < 0) {
            pending.clear()
            return null
        }
        if (start > 0) repeat(start) { pending.removeAt(0) }
        if (pending.size < 8) return null
        val len = pending[2].toInt() and 0xFF
        val t2 = 2 + 1 + len + 2 + 2
        val t1 = 2 + 1 + (len + 1) + 2 + 2
        fun isTail(total: Int): Boolean = total <= pending.size && pending[total - 2] == 0x77.toByte() && pending[total - 1] == 0xEE.toByte()
        val total = when {
            isTail(t2) -> t2
            isTail(t1) -> t1
            pending.size > (t1 + 32) -> {
                pending.removeAt(0)
                return null
            }
            else -> return null
        }
        val out = ByteArray(total) { i -> pending[i] }
        repeat(total) { pending.removeAt(0) }
        return out
    }

    private fun scanChannelRecords(data: ByteArray): List<Pair<Int, ChannelConfig>> {
        if (data.size < 24) return emptyList()
        val out = linkedMapOf<Int, ChannelConfig>()
        for (i in 0..(data.size - 24)) {
            val marker = data[i].toInt() and 0xFF
            if (marker != 0x01) continue
            val ch = data[i + 1].toInt() and 0xFF
            if (ch !in 1..30) continue
            val reserved2 = data[i + 2].toInt() and 0xFF
            if (reserved2 != 0x00) continue
            val rawA = le32(data, i + 3)
            val rawB = le32(data, i + 7)
            val freqLooksValid =
                (rawA in 30_000_000..520_000_000 && rawB in 30_000_000..520_000_000) ||
                    (rawA == 0 && rawB == 0)
            if (!freqLooksValid) continue
            val rx = if (rawA == 0) null else rawA / 100000.0
            val tx = if (rawB == 0) null else rawB / 100000.0
            out[ch] = ChannelConfig(
                rxMhz = rx,
                txMhz = tx,
                rxTone = data[i + 11].toInt() and 0xFF,
                rxToneType = data[i + 12].toInt() and 0xFF,
                txTone = data[i + 13].toInt() and 0xFF,
                txToneType = data[i + 14].toInt() and 0xFF,
                rxTonePolarity = data[i + 15].toInt() and 0xFF,
                txTonePolarity = data[i + 16].toInt() and 0xFF,
                busyLock = (data[i + 17].toInt() and 0xFF) == 0,
                bandwidthNarrow = (data[i + 18].toInt() and 0xFF) == 1,
                highPower = (data[i + 19].toInt() and 0xFF) == 1,
                scanAdd = (data[i + 20].toInt() and 0xFF) == 0,
                hopOn = (data[i + 21].toInt() and 0xFF) == 1,
                modeDigital = (data[i + 22].toInt() and 0xFF) == 1,
                encryptKey = data[i + 23].toInt() and 0xFF,
            )
        }
        return out.entries.map { it.key to it.value }
    }

    private fun le32(data: ByteArray, start: Int): Int =
        (data[start].toInt() and 0xFF) or
            ((data[start + 1].toInt() and 0xFF) shl 8) or
            ((data[start + 2].toInt() and 0xFF) shl 16) or
            ((data[start + 3].toInt() and 0xFF) shl 24)
}
