package com.byf3332.at2ht.core.ble

sealed interface BleSessionState {
    data object Idle : BleSessionState
    data object Connecting : BleSessionState
    data object DiscoveringServices : BleSessionState
    data object EnablingNotifications : BleSessionState
    data object Ready : BleSessionState
    data class Failed(val reason: String) : BleSessionState
    data object Disconnected : BleSessionState
}
