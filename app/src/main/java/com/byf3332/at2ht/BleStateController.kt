package com.byf3332.at2ht

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.byf3332.at2ht.core.ble.BleSession
import com.byf3332.at2ht.core.ble.BleSessionState
import com.byf3332.at2ht.core.ptt.PttVoiceReceiver
import kotlinx.coroutines.launch

class BleStateController(
    private val lifecycleOwner: LifecycleOwner,
    private val scope: LifecycleCoroutineScope,
    private val session: BleSession,
    private val pttReceiver: PttVoiceReceiver,
    private val setBleState: (BleSessionState) -> Unit,
    private val renderConnection: () -> Unit,
    private val renderHome: () -> Unit,
    private val renderChatBanner: () -> Unit,
    private val renderPtt: () -> Unit,
    private val stopPttUi: () -> Unit,
    private val onBleStateUpdated: () -> Unit,
) {
    fun observe() {
        scope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                session.state.collect { state ->
                    setBleState(state)
                    renderConnection()
                    renderHome()
                    renderChatBanner()
                    renderPtt()

                    if (state == BleSessionState.Disconnected || state == BleSessionState.Idle) {
                        stopPttUi()
                        pttReceiver.stop()
                    }
                    onBleStateUpdated()
                }
            }
        }
    }
}
