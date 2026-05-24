package com.byf3332.at2ht

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.provider.Settings
import android.view.View
import android.view.Window
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleCoroutineScope
import com.byf3332.at2ht.core.ble.BleScanDevice
import com.byf3332.at2ht.core.ble.BleSession
import com.byf3332.at2ht.core.ble.BleSessionState
import com.byf3332.at2ht.core.protocol.FeatureSettingsState
import com.byf3332.at2ht.core.protocol.At2ProtocolExecutor
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class DeviceConnectionController(
    private val context: Context,
    private val scope: LifecycleCoroutineScope,
    private val session: BleSession,
    private val protocolExecutor: At2ProtocolExecutor,
    private val getBleState: () -> BleSessionState,
    private val hasScanPermissions: () -> Boolean,
    private val requestPermissions: () -> Unit,
    private val isLocationServiceEnabled: () -> Boolean,
    private val getSelectedDeviceAddress: () -> String?,
    private val setSelectedDeviceAddress: (String?) -> Unit,
    private val getPendingEntryTarget: () -> DeviceEntryTarget?,
    private val setPendingEntryTarget: (DeviceEntryTarget?) -> Unit,
    private val setIssuedBootstrap: (Boolean) -> Unit,
    private val setAutoRetryDone: (Boolean) -> Unit,
    private val setShowLoadingOnly: (Boolean) -> Unit,
    private val setFullScreenLoadingText: (String) -> Unit,
    private val getConnectingDeviceAddress: () -> String?,
    private val setConnectingDeviceAddress: (String?) -> Unit,
    private val setAllowStatusChannelSync: (Boolean) -> Unit,
    private val getFeatureSettings: () -> FeatureSettingsState,
    private val getSmartLinkEnabled: () -> Boolean,
    private val setAppSectionState: (AppSection) -> Unit,
    private val setTalkStageState: (TalkStage) -> Unit,
    private val syncReadWriteFromDevice: () -> Unit,
    private val loadChatMessagesForCurrentThread: suspend () -> Unit,
    private val clearOfflineAssembler: () -> Unit,
    private val clearOfflineFramePending: () -> Unit,
    private val refreshSmartLinkState: suspend () -> Unit,
    private val upsertKnownDevice: (BleScanDevice) -> Unit,
    private val knownDevicesProvider: () -> List<BleScanDevice>,
    private val renderAll: () -> Unit,
    private val renderConnection: () -> Unit,
    private val showDeviceEntryDialog: (BleScanDevice) -> Unit,
    private val styleDialog: (AlertDialog) -> Unit,
    private val buildDialogListAdapter: (List<String>) -> ArrayAdapter<String>,
    private val buildLoadingDialogView: (String) -> View,
    private val onContinueScanNeededCleared: () -> Unit = {},
    private val putLastDeviceAddress: (String) -> Unit,
) {
    private var pendingDiscoveredDevice: BleScanDevice? = null
    private var connectingFromScan = false
    private var connectDialog: AlertDialog? = null
    private var pendingDialogDeviceAddress: String? = null
    private var connectTimeoutJob: Job? = null
    private var connectionFlagsRefreshInFlight = false

    fun cleanup() {
        connectTimeoutJob?.cancel()
        dismissConnectDialog()
    }

    fun onBleStateUpdated() {
        val bleState = getBleState()
        if (bleState == BleSessionState.Ready && getPendingEntryTarget() != null) {
            if (!connectionFlagsRefreshInFlight) {
                connectionFlagsRefreshInFlight = true
                scope.launch {
                    refreshConnectionFlags()
                    connectionFlagsRefreshInFlight = false
                    if (getBleState() == BleSessionState.Ready) {
                        val target = getPendingEntryTarget() ?: return@launch
                        setPendingEntryTarget(null)
                        openDeviceEntryTarget(target)
                    }
                }
            }
            return
        }

        if (bleState == BleSessionState.Ready) {
            if (!connectionFlagsRefreshInFlight) {
                connectionFlagsRefreshInFlight = true
                scope.launch {
                    refreshConnectionFlags()
                    connectionFlagsRefreshInFlight = false
                }
            }
            connectTimeoutJob?.cancel()
            setConnectingDeviceAddress(null)
            getSelectedDeviceAddress()?.let { putLastDeviceAddress(it) }
            pendingDiscoveredDevice?.let { device ->
                upsertKnownDevice(device)
                pendingDiscoveredDevice = null
                connectingFromScan = false
                dismissConnectDialog()
                if (getPendingEntryTarget() == null) {
                    setShowLoadingOnly(false)
                    setTalkStageState(TalkStage.Connection)
                    Toast.makeText(context, context.getString(R.string.device_connected_toast, device.name), Toast.LENGTH_SHORT).show()
                }
            }
            pendingDialogDeviceAddress?.let { address ->
                val device = knownDevicesProvider().firstOrNull { it.address == address }
                pendingDialogDeviceAddress = null
                if (device != null && getPendingEntryTarget() == null) {
                    showDeviceEntryDialog(device)
                }
            }
            renderConnection()
        }

        if (bleState == BleSessionState.Disconnected || bleState == BleSessionState.Idle) {
            connectionFlagsRefreshInFlight = false
            if (connectingFromScan && pendingDiscoveredDevice != null) {
                dismissConnectDialog()
                Toast.makeText(context, R.string.device_connect_failed, Toast.LENGTH_SHORT).show()
            }
            setIssuedBootstrap(false)
            setAutoRetryDone(false)
            setShowLoadingOnly(false)
            connectTimeoutJob?.cancel()
            setConnectingDeviceAddress(null)
            connectingFromScan = false
            dismissConnectDialog()
            pendingDialogDeviceAddress = null
            pendingDiscoveredDevice = null
            renderAll()
        }
    }

    fun scanDevicesFromUserAction() {
        if (!hasScanPermissions()) {
            requestPermissions()
            return
        }
        continueScanDevicesFromUserAction()
    }

    fun continueScanDevicesFromUserAction() {
        scope.launch {
            onContinueScanNeededCleared()
            if (!hasScanPermissions()) return@launch
            val requiresLocationForScan = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S
            if (requiresLocationForScan && !isLocationServiceEnabled()) {
                val dialog = AlertDialog.Builder(context)
                    .setTitle(R.string.device_scan_need_location_title)
                    .setMessage(R.string.device_scan_need_location_message)
                    .setPositiveButton(R.string.device_scan_open_settings) { _, _ ->
                        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }
                    .setNegativeButton(R.string.common_cancel, null)
                    .create()
                styleDialog(dialog)
                dialog.show()
                return@launch
            }
            disconnectBeforeScan()
            scanDevices()
        }
    }

    fun showEntryDialog(device: BleScanDevice) {
        if (getBleState() != BleSessionState.Ready || getSelectedDeviceAddress() != device.address) {
            reconnectKnownDevice(device, openDialogAfterConnect = true)
            return
        }
        val options = listOf(
            context.getString(R.string.device_option_control),
            context.getString(R.string.device_option_read_write),
            context.getString(R.string.device_option_offline),
            context.getString(R.string.device_option_smart_link_beta),
        )
        val dialog = AlertDialog.Builder(context)
            .setTitle(device.name)
            .setAdapter(buildDialogListAdapter(options)) { _, which ->
                val target = when (which) {
                    0 -> DeviceEntryTarget.Control
                    1 -> DeviceEntryTarget.ReadWrite
                    2 -> DeviceEntryTarget.Offline
                    else -> DeviceEntryTarget.SmartLink
                }
                ensureDeviceConnectedThen(device, target)
            }
            .setNegativeButton(R.string.common_cancel, null)
            .create()
        styleDialog(dialog)
        dialog.show()
    }

    fun connectScannedDevice(device: BleScanDevice) {
        pendingDiscoveredDevice = device
        setPendingEntryTarget(null)
        setSelectedDeviceAddress(device.address)
        setIssuedBootstrap(false)
        setAutoRetryDone(false)
        connectingFromScan = true
        setShowLoadingOnly(false)
        showConnectDialog()
        session.connect(device.device)
        setConnectingDeviceAddress(device.address)
        startConnectTimeout(device.address)
        renderAll()
    }

    fun reconnectKnownDevice(device: BleScanDevice, openDialogAfterConnect: Boolean) {
        scope.launch {
            val currentAddress = getSelectedDeviceAddress() ?: getConnectingDeviceAddress()
            val switchingDevice =
                currentAddress != null &&
                    currentAddress != device.address &&
                    (getBleState() == BleSessionState.Ready ||
                        getBleState() == BleSessionState.Connecting ||
                        getConnectingDeviceAddress() != null)
            if (switchingDevice) {
                session.disconnect()
                connectTimeoutJob?.cancel()
                dismissConnectDialog()
                setConnectingDeviceAddress(null)
                connectingFromScan = false
                pendingDialogDeviceAddress = null
                pendingDiscoveredDevice = null
                setShowLoadingOnly(false)
                withTimeoutOrNull(1200) {
                    while (getBleState() != BleSessionState.Disconnected && getBleState() != BleSessionState.Idle) {
                        delay(50)
                    }
                }
            }
            pendingDiscoveredDevice = null
            connectingFromScan = false
            pendingDialogDeviceAddress = if (openDialogAfterConnect) device.address else null
            setPendingEntryTarget(null)
            setSelectedDeviceAddress(device.address)
            setIssuedBootstrap(false)
            setAutoRetryDone(false)
            setShowLoadingOnly(false)
            session.connect(device.device)
            setConnectingDeviceAddress(device.address)
            startConnectTimeout(device.address)
            renderConnection()
        }
    }

    suspend fun disconnectBeforeScan() {
        if (getBleState() == BleSessionState.Ready || getBleState() == BleSessionState.Connecting || getConnectingDeviceAddress() != null) {
            session.disconnect()
            connectTimeoutJob?.cancel()
            dismissConnectDialog()
            setConnectingDeviceAddress(null)
            connectingFromScan = false
            pendingDialogDeviceAddress = null
            pendingDiscoveredDevice = null
            setPendingEntryTarget(null)
            setShowLoadingOnly(false)
            setSelectedDeviceAddress(null)
            withTimeoutOrNull(1200) {
                while (getBleState() != BleSessionState.Disconnected && getBleState() != BleSessionState.Idle) {
                    delay(50)
                }
            }
            renderAll()
        }
    }

    private fun scanDevices() {
        scope.launch {
            val loadingDialog = AlertDialog.Builder(context)
                .setView(buildLoadingDialogView(context.getString(R.string.common_scanning)))
                .setCancelable(false)
                .create()
            styleDialog(loadingDialog)
            loadingDialog.show()
            val devices = session.scanNamedBleDevices()
            runCatching { loadingDialog.dismiss() }
            if (devices.isEmpty()) {
                val dialog = AlertDialog.Builder(context)
                    .setTitle(R.string.device_not_found_title)
                    .setMessage(R.string.device_not_found_message)
                    .setPositiveButton(R.string.common_understood, null)
                    .create()
                styleDialog(dialog)
                dialog.show()
            } else {
                val labels = devices.map { "${it.name}\n${it.address} · RSSI ${it.rssi}" }
                val dialog = AlertDialog.Builder(context)
                    .setTitle(R.string.device_select_title)
                    .setAdapter(buildDialogListAdapter(labels)) { _, which ->
                        connectScannedDevice(devices[which])
                    }
                    .setNegativeButton(R.string.common_cancel, null)
                    .create()
                styleDialog(dialog)
                dialog.show()
            }
            renderConnection()
        }
    }

    private fun ensureDeviceConnectedThen(device: BleScanDevice, target: DeviceEntryTarget) {
        if (getBleState() == BleSessionState.Ready && getSelectedDeviceAddress() == device.address) {
            openDeviceEntryTarget(target)
            return
        }
        setSelectedDeviceAddress(device.address)
        setPendingEntryTarget(target)
        session.connect(device.device)
        setIssuedBootstrap(false)
        setAutoRetryDone(false)
        setShowLoadingOnly(target == DeviceEntryTarget.Control)
        setFullScreenLoadingText(
            context.getString(
                if (target == DeviceEntryTarget.Control) R.string.device_reading_channels
                else R.string.device_connecting
            )
        )
        renderAll()
    }

    private fun openDeviceEntryTarget(target: DeviceEntryTarget) {
        when (target) {
            DeviceEntryTarget.Control -> {
                scope.launch {
                    setShowLoadingOnly(true)
                    setFullScreenLoadingText(context.getString(R.string.device_entry_loading))
                    renderAll()
                    setAllowStatusChannelSync(true)
                    protocolExecutor.queryBootstrap("entry-control")
                    delay(120)
                    protocolExecutor.queryCurrentChannelInfo()
                    delay(300)
                    setShowLoadingOnly(false)
                    setAppSectionState(AppSection.Offline)
                    setTalkStageState(TalkStage.Main)
                    renderAll()
                }
                return
            }
            DeviceEntryTarget.ReadWrite -> {
                scope.launch {
                    setShowLoadingOnly(true)
                    setFullScreenLoadingText(context.getString(R.string.device_reading_channels))
                    renderAll()
                    setAllowStatusChannelSync(true)
                    protocolExecutor.queryBootstrap("entry-readwrite")
                    delay(350)
                    syncReadWriteFromDevice()
                    setShowLoadingOnly(false)
                    setAppSectionState(AppSection.ReadWrite)
                    renderAll()
                }
                return
            }
            DeviceEntryTarget.Offline -> {
                if (getFeatureSettings().dualWatch) {
                    Toast.makeText(context, R.string.device_dual_watch_block_offline, Toast.LENGTH_SHORT).show()
                    return
                }
                scope.launch {
                    protocolExecutor.querySmartLink()
                    delay(120)
                    if (getSmartLinkEnabled()) {
                        Toast.makeText(context, R.string.device_smart_link_block_offline, Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    setShowLoadingOnly(true)
                    setFullScreenLoadingText(context.getString(R.string.device_entry_loading))
                    renderAll()
                    setAllowStatusChannelSync(true)
                    protocolExecutor.queryBootstrap("entry-offline")
                    delay(300)
                    loadChatMessagesForCurrentThread()
                    clearOfflineAssembler()
                    clearOfflineFramePending()
                    protocolExecutor.setOfflineSession(enabled = true, tag = "CHAT SESSION ON")
                    delay(300)
                    setShowLoadingOnly(false)
                    setAppSectionState(AppSection.Offline)
                    setTalkStageState(TalkStage.Chat)
                    renderAll()
                }
                return
            }
            DeviceEntryTarget.SmartLink -> {
                scope.launch {
                    setAppSectionState(AppSection.SmartLink)
                    renderAll()
                    refreshSmartLinkState()
                }
                return
            }
        }
    }

    private fun showConnectDialog() {
        runCatching { connectDialog?.dismiss() }
        val progressBar = ProgressBar(context).apply {
            indeterminateTintList = ColorStateList.valueOf(0xFF1D4ED8.toInt())
        }
        connectDialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.device_connecting)
            .setView(progressBar)
            .setCancelable(false)
            .create()
        connectDialog?.let { styleDialog(it) }
        connectDialog?.show()
    }

    private fun dismissConnectDialog() {
        runCatching { connectDialog?.dismiss() }
        connectDialog = null
    }

    private fun startConnectTimeout(address: String) {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = scope.launch {
            delay(10_000)
            if (getConnectingDeviceAddress() == address && !(getBleState() == BleSessionState.Ready && getSelectedDeviceAddress() == address)) {
                session.disconnect()
                setConnectingDeviceAddress(null)
                connectingFromScan = false
                pendingDialogDeviceAddress = null
                pendingDiscoveredDevice = null
                dismissConnectDialog()
                Toast.makeText(context, R.string.device_connect_timeout, Toast.LENGTH_SHORT).show()
                renderConnection()
            }
        }
    }

    private suspend fun refreshConnectionFlags() {
        protocolExecutor.queryDualWatchState()
        delay(120)
        protocolExecutor.querySmartLink()
        delay(120)
    }
}
