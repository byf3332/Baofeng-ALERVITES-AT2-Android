package com.byf3332.at2ht

import com.byf3332.at2ht.core.ble.BleSession
import com.byf3332.at2ht.core.ble.BleSessionState
import com.byf3332.at2ht.core.protocol.At2ProtocolExecutor
import com.byf3332.at2ht.core.ptt.PttVoiceReceiver
import com.byf3332.at2ht.core.ptt.PttVoiceSender

class AppNavigationController(
    private val session: BleSession,
    private val protocolExecutor: At2ProtocolExecutor,
    private val pttSender: PttVoiceSender,
    private val pttReceiver: PttVoiceReceiver,
    private val getBleState: () -> BleSessionState,
    private val getShowLoadingOnly: () -> Boolean,
    private val setShowLoadingOnly: (Boolean) -> Unit,
    private val getPendingEntryTarget: () -> DeviceEntryTarget?,
    private val setPendingEntryTarget: (DeviceEntryTarget?) -> Unit,
    private val getControlEntryToken: () -> Long,
    private val setControlEntryToken: (Long) -> Unit,
    private val getAppSection: () -> AppSection,
    private val setAppSection: (AppSection) -> Unit,
    private val getTalkStage: () -> TalkStage,
    private val setTalkStage: (TalkStage) -> Unit,
    private val setChatMenuExpanded: (Boolean) -> Unit,
    private val setSelectedDeviceAddress: (String?) -> Unit,
    private val setConnectingDeviceAddress: (String?) -> Unit,
    private val stopVoicePlayback: () -> Unit,
    private val cancelOutgoingChatTransfers: () -> Unit,
    private val stopPttUi: () -> Unit,
    private val stopAndJoinPttUi: suspend () -> Unit,
    private val cleanupDeviceConnection: () -> Unit,
    private val renderAll: () -> Unit,
) {
    suspend fun handleAppBack(): Boolean {
        if (getShowLoadingOnly() && getPendingEntryTarget() == DeviceEntryTarget.Control) {
            setControlEntryToken(getControlEntryToken() + 1L)
            setPendingEntryTarget(null)
            setShowLoadingOnly(false)
            setTalkStage(TalkStage.Connection)
            renderAll()
            return true
        }
        if (getAppSection() != AppSection.Offline) {
            setAppSection(AppSection.Offline)
            setTalkStage(TalkStage.Connection)
            setShowLoadingOnly(false)
            renderAll()
            return true
        }
        when (getTalkStage()) {
            TalkStage.Connection -> return false
            TalkStage.Main -> {
                setAppSection(AppSection.Offline)
                setTalkStage(TalkStage.Connection)
                setShowLoadingOnly(false)
            }
            TalkStage.FeatureSettings -> {
                setAppSection(AppSection.Offline)
                setTalkStage(TalkStage.Main)
            }
            TalkStage.Chat -> {
                stopVoicePlayback()
                cancelOutgoingChatTransfers()
                if (getBleState() == BleSessionState.Ready) {
                    protocolExecutor.setOfflineSession(enabled = false, tag = "CHAT SESSION OFF")
                }
                setChatMenuExpanded(false)
                setAppSection(AppSection.Offline)
                setTalkStage(TalkStage.Connection)
            }
            TalkStage.Ptt -> {
                stopAndJoinPttUi()
                setTalkStage(TalkStage.Chat)
            }
        }
        renderAll()
        return true
    }

    fun disconnectCurrentKeepDevice() {
        session.disconnect()
        stopVoicePlayback()
        cancelOutgoingChatTransfers()
        stopPttUi()
        pttReceiver.stop()
        setTalkStage(TalkStage.Connection)
        setAppSection(AppSection.Offline)
        setShowLoadingOnly(false)
        setSelectedDeviceAddress(null)
        setConnectingDeviceAddress(null)
        cleanupDeviceConnection()
        renderAll()
    }

    suspend fun leaveOfflineChat() {
        stopVoicePlayback()
        cancelOutgoingChatTransfers()
        stopAndJoinPttUi()
        pttReceiver.stop()
        if (getBleState() == BleSessionState.Ready) {
            protocolExecutor.setOfflineSession(enabled = false, tag = "CHAT SESSION OFF")
        }
        setTalkStage(TalkStage.Main)
        setChatMenuExpanded(false)
    }
}
