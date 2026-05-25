package com.byf3332.at2ht

import com.byf3332.at2ht.core.protocol.At2Commands
import com.byf3332.at2ht.core.protocol.At2ProtocolDecoder
import com.byf3332.at2ht.core.protocol.At2RxState
import com.byf3332.at2ht.core.protocol.ChannelConfig
import com.byf3332.at2ht.core.protocol.FeatureSettingsState
import com.byf3332.at2ht.core.protocol.OfflineEvent
import com.byf3332.at2ht.core.protocol.OfflineFrame
import com.byf3332.at2ht.core.protocol.OfflineMessageAssembler
import com.byf3332.at2ht.core.protocol.OfflineStartFrame
import com.byf3332.at2ht.core.ptt.PttVoiceReceiver

class BleEventController(
    private val protocolDecoder: At2ProtocolDecoder,
    private val rxState: At2RxState,
    private val offlineAssembler: OfflineMessageAssembler,
    private val offlineFramePending: ArrayList<Byte>,
    private val channels: MutableList<ChannelConfig>,
    private val chatFlowController: ChatFlowController,
    private val pttReceiver: PttVoiceReceiver,
    private val getTalkStage: () -> TalkStage,
    private val getCurrentCh: () -> Int,
    private val setCurrentCh: (Int) -> Unit,
    private val getFeatureSettings: () -> FeatureSettingsState,
    private val setFeatureSettings: (FeatureSettingsState) -> Unit,
    private val getDualWatchFocus: () -> At2Commands.Side,
    private val getDualWatchChannelA: () -> Int,
    private val setDualWatchChannelA: (Int) -> Unit,
    private val getDualWatchChannelB: () -> Int,
    private val setDualWatchChannelB: (Int) -> Unit,
    private val getAllowStatusChannelSync: () -> Boolean,
    private val setAllowStatusChannelSync: (Boolean) -> Unit,
    private val setSmartLinkEnabled: (Boolean) -> Unit,
    private val setSmartLinkMainPttTarget: (At2Commands.MainPttTarget?) -> Unit,
    private val onSmartLinkStateChanged: () -> Unit,
    private val incrementPttModeAckCounter: () -> Unit,
    private val incrementOfflineBusinessAckCounter: () -> Unit,
    private val incrementChannelSwitchAckCounter: () -> Unit,
    private val incrementSmartLinkModeAckCounter: () -> Unit,
    private val incrementSmartLinkPttAckCounter: () -> Unit,
    private val incrementDeviceNameAckCounter: () -> Unit,
    private val incrementCodeplugWriteAckCounter: () -> Unit,
    private val renderFrequencyCard: () -> Unit,
    private val renderChannelGridSelection: () -> Unit,
) {
    fun handleRxPacket(packet: ByteArray) {
        val at2Packet = protocolDecoder.decodeFrame(packet)
        val offlineFrames = extractOfflineTextFrames(packet)
        offlineFrames.forEach { offlineFrame ->
            AppLog.d("AT2HT-OFFLINE", "offlineFrame=$offlineFrame")
            chatFlowController.onOfflineFrame(offlineFrame)
            val offlineEvent = offlineAssembler.consume(offlineFrame)
            if (offlineEvent != null) AppLog.d("AT2HT-OFFLINE", "offlineEvent=$offlineEvent")
            when (offlineEvent) {
                is OfflineEvent.Text -> chatFlowController.onIncomingOfflineText(offlineEvent)
                is OfflineEvent.Image -> chatFlowController.onIncomingOfflineImage(offlineEvent)
                is OfflineEvent.VoiceMessage -> chatFlowController.onIncomingOfflineVoice(offlineEvent)
                else -> Unit
            }
        }

        val voice = at2Packet?.let { protocolDecoder.decodeRealtimeVoiceChunk(it) }
        if ((getTalkStage() == TalkStage.Ptt || getTalkStage() == TalkStage.Chat) && voice != null) {
            pttReceiver.onVoicePacket(voice)
        }

        at2Packet?.let { decoded ->
            when {
                decoded.family == 0x81 && decoded.command == 0x04 &&
                    decoded.payload.size >= 2 && decoded.payload[0] == 0x09.toByte() -> {
                    setSmartLinkEnabled(decoded.payload[1] != 0x00.toByte())
                    onSmartLinkStateChanged()
                }

                decoded.family == 0x81 && decoded.command == 0x04 &&
                    decoded.payload.size >= 3 &&
                    decoded.payload[0] == 0x0A.toByte() &&
                    decoded.payload[1] == 0x01.toByte() -> {
                    At2Commands.MainPttTarget.fromCodeOrNull(decoded.payload[2])?.let {
                        setSmartLinkMainPttTarget(it)
                        onSmartLinkStateChanged()
                    }
                }

                decoded.family == 0x81 && decoded.command == 0x02 &&
                    decoded.payload.size >= 2 &&
                    decoded.payload[0] == 0x0D.toByte() -> {
                    val enabled = decoded.payload[1] == 0x02.toByte()
                    if (getFeatureSettings().dualWatch != enabled) {
                        setFeatureSettings(getFeatureSettings().copy(dualWatch = enabled))
                        renderFrequencyCard()
                    }
                }

                decoded.family == 0x81 && decoded.command == 0x01 &&
                    decoded.payload.size >= 2 &&
                    decoded.payload[0] == 0x03.toByte() -> {
                    val language = if (decoded.payload[1] == 0x01.toByte()) {
                        At2Commands.PromptLanguage.English
                    } else {
                        At2Commands.PromptLanguage.Chinese
                    }
                    if (getFeatureSettings().promptLanguage != language) {
                        setFeatureSettings(getFeatureSettings().copy(promptLanguage = language))
                    }
                }

                decoded.family == 0x81 && decoded.command == 0x01 &&
                    decoded.payload.size >= 2 &&
                    decoded.payload[0] == 0x04.toByte() -> {
                    val enabled = decoded.payload[1] != 0x00.toByte()
                    if (getFeatureSettings().promptTone != enabled) {
                        setFeatureSettings(getFeatureSettings().copy(promptTone = enabled))
                    }
                }

                decoded.family == 0x81 && decoded.command == 0x01 &&
                    decoded.payload.size >= 2 &&
                    decoded.payload[0] == 0x01.toByte() -> {
                    val value = decoded.payload[1].toInt() and 0xFF
                    if (value in 1..8 && getFeatureSettings().volume != value) {
                        setFeatureSettings(getFeatureSettings().copy(volume = value))
                    }
                }

                decoded.family == 0x81 && decoded.command == 0x02 &&
                    decoded.payload.size >= 2 &&
                    decoded.payload[0] == 0x04.toByte() -> {
                    val value = decoded.payload[1].toInt() and 0xFF
                    if (value in 0..9 && getFeatureSettings().squelch != value) {
                        setFeatureSettings(getFeatureSettings().copy(squelch = value))
                    }
                }

                decoded.family == 0x81 && decoded.command == 0x02 &&
                    decoded.payload.size >= 3 &&
                    decoded.payload[0] == 0x05.toByte() -> {
                    val value =
                        (decoded.payload[1].toInt() and 0xFF) or
                            ((decoded.payload[2].toInt() and 0xFF) shl 8)
                    if (value in 0..240 && getFeatureSettings().totSeconds != value) {
                        setFeatureSettings(getFeatureSettings().copy(totSeconds = value))
                    }
                }

                decoded.family == 0x81 && decoded.command == 0x02 &&
                    decoded.payload.size >= 2 &&
                    decoded.payload[0] == 0x06.toByte() -> {
                    val enabled = decoded.payload[1] != 0x00.toByte()
                    if (getFeatureSettings().voxEnabled != enabled) {
                        setFeatureSettings(getFeatureSettings().copy(voxEnabled = enabled))
                    }
                }

                decoded.family == 0x81 && decoded.command == 0x02 &&
                    decoded.payload.size >= 2 &&
                    decoded.payload[0] == 0x07.toByte() -> {
                    val value = decoded.payload[1].toInt() and 0xFF
                    if (value in 1..5 && getFeatureSettings().voxSensitivity != value) {
                        setFeatureSettings(getFeatureSettings().copy(voxSensitivity = value))
                    }
                }

                decoded.family == 0x81 && decoded.command == 0x02 &&
                    decoded.payload.size >= 2 &&
                    decoded.payload[0] == 0x09.toByte() -> {
                    val enabled = decoded.payload[1] != 0x00.toByte()
                    if (getFeatureSettings().txInhibit != enabled) {
                        setFeatureSettings(getFeatureSettings().copy(txInhibit = enabled))
                    }
                }

                decoded.family == 0x81 && decoded.command == 0x02 &&
                    decoded.payload.size >= 3 &&
                    decoded.payload[0] == 0x0A.toByte() -> {
                    val value =
                        (decoded.payload[1].toInt() and 0xFF) or
                            ((decoded.payload[2].toInt() and 0xFF) shl 8)
                    if (value in 0..240 && getFeatureSettings().txIntervalSeconds != value) {
                        setFeatureSettings(getFeatureSettings().copy(txIntervalSeconds = value))
                    }
                }

                decoded.family == 0x81 && decoded.command == 0x02 &&
                    decoded.payload.size >= 2 &&
                    decoded.payload[0] == 0x11.toByte() -> {
                    val enabled = decoded.payload[1] != 0x00.toByte()
                    if (getFeatureSettings().noiseReduction != enabled) {
                        setFeatureSettings(getFeatureSettings().copy(noiseReduction = enabled))
                    }
                }

                decoded.family == 0x81 && decoded.command == 0x02 &&
                    decoded.payload.size >= 7 &&
                    decoded.payload[0] == 0x0E.toByte() &&
                    decoded.payload[1] == 0x01.toByte() &&
                    decoded.payload[3] == 0x00.toByte() &&
                    decoded.payload[4] == 0x02.toByte() -> {
                    val channelA = decoded.payload[2].toInt() and 0xFF
                    val channelB = decoded.payload[5].toInt() and 0xFF
                    var changed = false
                    if (channelA in 1..30 && getDualWatchChannelA() != channelA) {
                        setDualWatchChannelA(channelA)
                        changed = true
                    }
                    if (channelB in 1..30 && getDualWatchChannelB() != channelB) {
                        setDualWatchChannelB(channelB)
                        changed = true
                    }
                    if (changed) {
                        renderFrequencyCard()
                        renderChannelGridSelection()
                    }
                }

                decoded.family == 0x82 && decoded.command == 0x04 &&
                    decoded.payload.size >= 2 &&
                    decoded.payload[0] == 0x09.toByte() &&
                    decoded.payload[1] == 0x00.toByte() -> {
                    incrementSmartLinkModeAckCounter()
                }

                decoded.family == 0x82 && decoded.command == 0x04 &&
                    decoded.payload.size >= 2 &&
                    decoded.payload[0] == 0x0A.toByte() &&
                    decoded.payload[1] == 0x00.toByte() -> {
                    incrementSmartLinkPttAckCounter()
                }

                decoded.family == 0x82 && decoded.command == 0x03 &&
                    decoded.payload.size >= 2 &&
                    decoded.payload[0] == 0x01.toByte() &&
                    decoded.payload[1] == 0x00.toByte() -> {
                    incrementDeviceNameAckCounter()
                }
            }
        }

        if (packet.containsSubsequence(byteArrayOf(0x82.toByte(), 0x04, 0x02))) {
            incrementPttModeAckCounter()
        }
        if (packet.containsSubsequence(byteArrayOf(0x82.toByte(), 0x02, 0x0E, 0x00))) {
            incrementChannelSwitchAckCounter()
        }
        if (packet.containsSubsequence(byteArrayOf(0x82.toByte(), 0x02, 0x02, 0x00))) {
            incrementCodeplugWriteAckCounter()
        }
        if (packet.containsSubsequence(byteArrayOf(0x82.toByte(), 0x04, 0x01, 0x00))) {
            incrementOfflineBusinessAckCounter()
        }

        val updates = if (offlineFrames.isEmpty()) rxState.feed(packet) else emptyList()
        if (updates.isNotEmpty()) {
            updates.forEach { (ch, st) -> channels[ch - 1] = st }
            renderFrequencyCard()
            renderChannelGridSelection()
        }

        val statusCh = rxState.lastCurrentChannel
        if (getAllowStatusChannelSync() && statusCh in 1..30 && statusCh != getCurrentCh()) {
            setCurrentCh(statusCh)
            if (getFeatureSettings().dualWatch) {
                if (getDualWatchFocus() == At2Commands.Side.A) {
                    setDualWatchChannelA(statusCh)
                } else {
                    setDualWatchChannelB(statusCh)
                }
            } else {
                setDualWatchChannelA(statusCh)
            }
            setAllowStatusChannelSync(false)
            renderFrequencyCard()
            renderChannelGridSelection()
        }
    }

    private fun extractOfflineTextFrames(buffer: ByteArray): List<OfflineFrame> {
        buffer.forEach { offlineFramePending.add(it) }
        val frames = mutableListOf<OfflineFrame>()
        while (offlineFramePending.size >= 7) {
            var start = -1
            for (i in 0 until offlineFramePending.size - 1) {
                if (offlineFramePending[i] == 0xAA.toByte() && offlineFramePending[i + 1] == 0x55.toByte()) {
                    start = i
                    break
                }
            }
            if (start < 0) {
                val keepLastAa = offlineFramePending.lastOrNull() == 0xAA.toByte()
                offlineFramePending.clear()
                if (keepLastAa) offlineFramePending.add(0xAA.toByte())
                break
            }
            if (start > 0) {
                repeat(start) { offlineFramePending.removeAt(0) }
            }
            if (offlineFramePending.size < 7) break

            val len = offlineFramePending[2].toInt() and 0xFF
            val totalWith00 = 2 + 1 + (len + 1) + 2 + 2
            val totalNo00 = 2 + 1 + len + 2 + 2
            val total = when {
                hasAt2Tail(offlineFramePending, 0, totalWith00) -> totalWith00
                hasAt2Tail(offlineFramePending, 0, totalNo00) -> totalNo00
                offlineFramePending.size < minOf(totalWith00, totalNo00) -> break
                else -> {
                    offlineFramePending.removeAt(0)
                    continue
                }
            }

            val frameBytes = ByteArray(total) { i -> offlineFramePending[i] }
            repeat(total) { offlineFramePending.removeAt(0) }
            val hex = frameBytes.joinToString("") { "%02x".format(it) }
            if (hex.contains("020401")) {
                AppLog.d("AT2HT-OFFLINE", "rx frame=$hex")
            }
            protocolDecoder.decodeFrame(frameBytes)?.let { decoded ->
                protocolDecoder.decodeOfflineFrame(decoded)?.let { frames += it }
            }
        }
        return frames
    }

    private fun hasAt2Tail(buffer: List<Byte>, start: Int, total: Int): Boolean {
        val end = start + total
        return end <= buffer.size &&
            buffer[end - 2] == 0x77.toByte() &&
            buffer[end - 1] == 0xEE.toByte()
    }
}
