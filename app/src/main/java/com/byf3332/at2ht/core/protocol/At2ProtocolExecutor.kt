package com.byf3332.at2ht.core.protocol

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import com.byf3332.at2ht.AppLog
import java.io.IOException

class At2ProtocolExecutor(
    private val sendPayload: suspend (ByteArray, String) -> Unit,
    private val pttAckCounter: () -> Int,
    private val offlineAckCounter: () -> Int,
    private val channelSwitchAckCounter: () -> Int,
    private val smartLinkAckCounter: () -> Int,
    private val smartLinkPttAckCounter: () -> Int,
    private val deviceNameAckCounter: () -> Int,
    private val codeplugWriteAckCounter: () -> Int,
    private val nextOfflineMessageId: (String) -> UInt,
    private val offlineCodec: At2OfflineMessageCodec = At2OfflineMessageCodec(),
) {
    companion object {
        private const val OFFLINE_TEXT_FIRST_CHUNK_DELAY_MS = 360L
        private const val OFFLINE_TEXT_CHUNK_PERIOD_MS = 400L
        private const val OFFLINE_VOICE_FIRST_CHUNK_DELAY_MS = 350L
        private const val OFFLINE_VOICE_CHUNK_PERIOD_MS = 360L
        private const val OFFLINE_IMAGE_FIRST_CHUNK_DELAY_MS = 360L
        private const val OFFLINE_IMAGE_CHUNK_PERIOD_MS = 400L
        private const val OFFLINE_IMAGE_ACK_TIMEOUT_MS = 1500L
        private const val OFFLINE_IMAGE_ACK_RETRY_COUNT = 3
        private const val OFFLINE_IMAGE_ACK_RETRY_BACKOFF_MS = 220L
    }

    suspend fun applyDualWatch(enabled: Boolean) = sendCommand(At2Commands.setDualWatch(enabled), "SET DUAL WATCH")
    suspend fun applyPromptLanguage(language: At2Commands.PromptLanguage) = sendCommand(At2Commands.setPromptLanguage(language), "SET PROMPT LANG")
    suspend fun applyPromptTone(enabled: Boolean) = sendCommand(At2Commands.setPromptTone(enabled), "SET PROMPT TONE")
    suspend fun applyVolume(level: Int) = sendCommand(At2Commands.setVolume(level), "SET VOLUME")
    suspend fun applySquelch(level: Int) = sendCommand(At2Commands.setSquelch(level), "SET SQUELCH")
    suspend fun applyTotSeconds(seconds: Int) = sendCommand(At2Commands.setTotSeconds(seconds), "SET TOT")
    suspend fun applyVox(enabled: Boolean) = sendCommand(At2Commands.setVox(enabled), "SET VOX")
    suspend fun applyVoxSensitivity(level: Int) = sendCommand(At2Commands.setVoxSensitivity(level), "SET VOX SENS")
    suspend fun applyTxInhibit(enabled: Boolean) = sendCommand(At2Commands.setTxInhibit(enabled), "SET TX INHIBIT")
    suspend fun applyTxIntervalSeconds(seconds: Int) = sendCommand(At2Commands.setTxIntervalSeconds(seconds), "SET TX INTERVAL")
    suspend fun applyNoiseReduction(enabled: Boolean) = sendCommand(At2Commands.setNoiseReduction(enabled), "SET NR")
    suspend fun applySmartLink(enabled: Boolean): Boolean =
        sendCommandAwaitAck(At2Commands.setSmartLink(enabled), "SET SMART LINK", smartLinkAckCounter)

    suspend fun applySmartLinkMainPttTarget(target: At2Commands.MainPttTarget): Boolean =
        sendCommandAwaitAck(At2Commands.setMainPttLongPress(target), "SET SMART PTT ${target.name}", smartLinkPttAckCounter)

    suspend fun querySmartLink() =
        sendCommand(At2Commands.querySmartLink(), "QUERY SMART LINK")

    suspend fun queryDualWatchState() =
        sendCommand(At2Commands.queryDualWatch(), "QUERY DUAL WATCH")

    suspend fun querySmartLinkMainPttTarget() =
        sendCommand(At2Commands.queryMainPttLongPress(), "QUERY SMART PTT")

    suspend fun setDeviceName(name: String): Boolean =
        sendCommandAwaitAck(At2Commands.setDeviceName(name), "SET DEVICE NAME", deviceNameAckCounter)

    suspend fun queryCurrentChannelInfo() =
        sendCommand(At2Commands.queryCurrentChannelInfo(), "QUERY CURRENT CHANNEL")

    suspend fun writeEmptyChannel(channel: Int) {
        val record = At2Commands.emptyChannelRecord(channel)
        sendPayload(byteArrayOf(0x02, 0x02, 0x02) + record, "CLEAR CH$channel")
    }

    suspend fun writeSingleChannelConfig(channel: Int, config: ChannelConfig) {
        val record = At2ChannelCodec.encodeSingleChannelRecord(channel, config)
        sendPayload(byteArrayOf(0x02, 0x02, 0x02) + record, "WRITE CH$channel")
    }

    suspend fun writeAllChannelConfigs(configs: List<ChannelConfig>): Boolean {
        require(configs.size == 30) { "need 30 channel configs" }
        val frames = At2Commands.buildCodeplugWrite(
            configs.mapIndexed { index, config ->
                At2ChannelCodec.encodeSingleChannelRecord(index + 1, config)
            }
        )
        frames.forEachIndexed { index, frame ->
            val beforeAck = codeplugWriteAckCounter()
            sendPayload(frame.copyOfRange(1, frame.size), "WRITE ALL CHUNK ${index + 1}/${frames.size}")
            val ok = withTimeoutOrNull(1500) {
                while (codeplugWriteAckCounter() == beforeAck) delay(10)
                true
            } ?: false
            if (!ok) {
                return false
            }
        }
        return true
    }

    suspend fun sendOfflineText(msgId: UInt, username: String, text: String, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        val frames = offlineCodec.buildTextFrames(username, text, msgId)
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val totalUnits = if (textBytes.size <= At2OfflineMessageCodec.SHORT_TEXT_INLINE_MAX_BYTES) {
            textBytes.size.coerceAtLeast(1)
        } else {
            textBytes.asList().chunked(At2OfflineMessageCodec.FRAGMENT_TEXT_CHUNK_BYTES).sumOf { it.size + 1 }
        }
        var sentUnits = 0
        if (frames.isEmpty()) return

        sendOfflineBusinessFrameWithAck(frames.first(), "OFFLINE TEXT #0")
        sentUnits = when {
            frames.size == 1 -> totalUnits
            else -> 0
        }
        onProgress(sentUnits, totalUnits)

        var nextChunkAtNs = System.nanoTime() + OFFLINE_TEXT_FIRST_CHUNK_DELAY_MS * 1_000_000L
        frames.drop(1).forEachIndexed { offset, payload ->
            delayUntil(nextChunkAtNs)
            sendOfflineBusinessFrameWithAck(payload, "OFFLINE TEXT #${offset + 1}")
            val index = offset + 1
            sentUnits = when {
                frames.size == 1 -> totalUnits
                else -> (sentUnits + (payload.size - 11)).coerceAtMost(totalUnits)
            }
            onProgress(sentUnits, totalUnits)
            nextChunkAtNs += OFFLINE_TEXT_CHUNK_PERIOD_MS * 1_000_000L
        }
    }

    suspend fun sendOfflineVoice(
        msgId: UInt,
        username: String,
        encodedVoice: ByteArray,
        durationMs: Int,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ) {
        val frames = offlineCodec.buildVoiceFrames(username, encodedVoice, durationMs, msgId)
        val totalPackets = frames.size.coerceAtLeast(1)
        var sentPackets = 0
        if (frames.isEmpty()) return

        sendOfflineBusinessFrameWithAck(frames.first(), "OFFLINE VOICE #0")
        sentPackets += 1
        onProgress(sentPackets, totalPackets)

        var nextChunkAtNs = System.nanoTime() + OFFLINE_VOICE_FIRST_CHUNK_DELAY_MS * 1_000_000L
        frames.drop(1).forEachIndexed { offset, payload ->
            delayUntil(nextChunkAtNs)
            sendOfflineBusinessFrameWithAck(payload, "OFFLINE VOICE #${offset + 1}")
            sentPackets += 1
            onProgress(sentPackets, totalPackets)
            nextChunkAtNs += OFFLINE_VOICE_CHUNK_PERIOD_MS * 1_000_000L
        }
    }

    suspend fun sendOfflineImage(
        msgId: UInt,
        username: String,
        jpegBytes: ByteArray,
        originalWidth: Int,
        originalHeight: Int,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ) {
        val frames = offlineCodec.buildImageFrames(username, jpegBytes, originalWidth, originalHeight, msgId)
        val totalPackets = frames.size.coerceAtLeast(1)
        var sentPackets = 0
        if (frames.isEmpty()) return

        sendOfflineBusinessFrameWithAck(frames.first(), "OFFLINE IMAGE #0", includeHex = true)
        sentPackets += 1
        onProgress(sentPackets, totalPackets)

        var nextChunkAtNs = System.nanoTime() + OFFLINE_IMAGE_FIRST_CHUNK_DELAY_MS * 1_000_000L
        frames.drop(1).forEachIndexed { offset, payload ->
            delayUntil(nextChunkAtNs)
            sendOfflineBusinessFrameWithAck(payload, "OFFLINE IMAGE #${offset + 1}", includeHex = true)
            sentPackets += 1
            onProgress(sentPackets, totalPackets)
            nextChunkAtNs += OFFLINE_IMAGE_CHUNK_PERIOD_MS * 1_000_000L
        }
    }

    suspend fun switchChannel(channel: Int): Boolean =
        sendCommandAwaitAck(At2Commands.selectChannel(channel), "SW CH$channel", channelSwitchAckCounter)

    suspend fun switchDualWatchChannel(side: At2Commands.Side, channel: Int): Boolean =
        sendCommandAwaitAck(At2Commands.selectDualWatchChannel(side, channel), "SW ${side.name} CH$channel", channelSwitchAckCounter)

    suspend fun switchDualWatchFocus(side: At2Commands.Side) {
        sendCommand(At2Commands.selectDualWatchFocus(side), "FOCUS ${side.name}")
    }

    suspend fun setOfflineSession(enabled: Boolean, tag: String = if (enabled) "CHAT SESSION ON" else "CHAT SESSION OFF") {
        sendPayload(byteArrayOf(0x02, 0x04, 0x07, if (enabled) 0x01 else 0x00), tag)
    }

    suspend fun setOfflineMode(ptt: Boolean, tag: String = if (ptt) "PTT PRESS ON" else "PTT PRESS OFF") {
        val beforeAck = pttAckCounter()
        sendPayload(byteArrayOf(0x02, 0x04, 0x02, if (ptt) 0x01 else 0x00), tag)
        withTimeoutOrNull(450) {
            while (pttAckCounter() == beforeAck) delay(10)
        }
    }

    suspend fun enterPttPreflight() {
        sendPayload(byteArrayOf(0x01, 0x02, 0x0D), "PTT Q@020D")
        sendPayload(byteArrayOf(0x02, 0x04, 0x07, 0x01), "PTT SESSION ON")
        sendPayload(byteArrayOf(0x01, 0x02, 0x0F), "PTT Q@020F")
        sendPayload(byteArrayOf(0x01, 0x02, 0x0E), "PTT Q@020E")
        sendPayload(byteArrayOf(0x01, 0x02, 0x02, 0x01, 0x01, 0x00, 0x01, 0x01, 0x00), "PTT Q@0202CUR")
        sendPayload(byteArrayOf(0x01, 0x03, 0x04), "PTT Q@0304")
    }

    suspend fun queryBootstrap(reason: String) {
        sendPayload(byteArrayOf(0x01, 0x02, 0x02), "Q@0202")
    }

    private suspend fun sendCommand(commandWith00: ByteArray, tag: String) {
        require(commandWith00.isNotEmpty() && commandWith00[0] == 0x00.toByte()) { "AT2 command must start with 0x00" }
        sendPayload(commandWith00.copyOfRange(1, commandWith00.size), tag)
    }

    private suspend fun sendCommandAwaitAck(
        commandWith00: ByteArray,
        tag: String,
        ackCounter: () -> Int,
    ): Boolean {
        require(commandWith00.isNotEmpty() && commandWith00[0] == 0x00.toByte()) { "AT2 command must start with 0x00" }
        val beforeAck = ackCounter()
        sendPayload(commandWith00.copyOfRange(1, commandWith00.size), tag)
        val ok = withTimeoutOrNull(1200) {
            while (ackCounter() == beforeAck) delay(10)
            true
        } ?: false
        return ok
    }

    private suspend fun sendOfflineBusinessFrameWithAck(
        payload: ByteArray,
        tag: String,
        includeHex: Boolean = false,
    ) {
        repeat(OFFLINE_IMAGE_ACK_RETRY_COUNT) { attempt ->
            val beforeAck = offlineAckCounter()
            val hexSuffix = if (includeHex) {
                " hex=" + payload.joinToString("") { "%02x".format(it) }
            } else {
                ""
            }
            AppLog.d(
                "AT2HT-OFFLINE",
                "offline tx tag=$tag attempt=${attempt + 1} ackBefore=$beforeAck bytes=${payload.size}$hexSuffix"
            )
            sendPayload(payload.copyOfRange(1, payload.size), "$tag TRY${attempt + 1}")
            val acked = withTimeoutOrNull(OFFLINE_IMAGE_ACK_TIMEOUT_MS) {
                while (offlineAckCounter() == beforeAck) delay(10)
                true
            } ?: false
            if (acked) {
                AppLog.d("AT2HT-OFFLINE", "offline ack ok tag=$tag attempt=${attempt + 1} ackAfter=${offlineAckCounter()}")
                return
            }
            AppLog.w("AT2HT-OFFLINE", "offline ack timeout tag=$tag attempt=${attempt + 1} ackNow=${offlineAckCounter()}")
            if (attempt + 1 < OFFLINE_IMAGE_ACK_RETRY_COUNT) {
                delay(OFFLINE_IMAGE_ACK_RETRY_BACKOFF_MS)
            }
        }
        AppLog.e("AT2HT-OFFLINE", "offline ack failed tag=$tag retries=$OFFLINE_IMAGE_ACK_RETRY_COUNT")
        throw IOException("offline business ack timeout: $tag")
    }

    private suspend fun delayUntil(targetTimeNs: Long) {
        val remainingNs = targetTimeNs - System.nanoTime()
        if (remainingNs <= 0L) return
        delay((remainingNs + 999_999L) / 1_000_000L)
    }

}
