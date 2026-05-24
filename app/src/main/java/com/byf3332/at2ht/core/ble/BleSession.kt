package com.byf3332.at2ht.core.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

data class BleScanDevice(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val rssi: Int,
)

@SuppressLint("MissingPermission")
class BleSession(
    private val context: Context,
    private val serviceUuid: UUID = BleConstants.SERVICE_UUID,
    private val txCharUuid: UUID = BleConstants.TX_CHAR_UUID,
    private val rxCharUuid: UUID = BleConstants.RX_CHAR_UUID,
) {
    private val cccdUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val _state = MutableStateFlow<BleSessionState>(BleSessionState.Idle)
    val state: StateFlow<BleSessionState> = _state.asStateFlow()

    var onRxPayload: ((ByteArray) -> Unit)? = null

    private var gatt: BluetoothGatt? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var pendingWrite: CompletableDeferred<Int>? = null
    private var servicesStarted: Boolean = false
    private var currentMtu: Int = 23

    fun connect(device: BluetoothDevice) {
        close()
        servicesStarted = false
        currentMtu = 23
        _state.value = BleSessionState.Connecting
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun connect(macAddress: String): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        val device = runCatching { adapter.getRemoteDevice(macAddress) }.getOrNull() ?: return false
        connect(device)
        return true
    }

    fun disconnect() {
        gatt?.disconnect()
    }

    fun close() {
        pendingWrite?.cancel()
        pendingWrite = null
        txChar = null
        rxChar = null
        servicesStarted = false
        currentMtu = 23
        gatt?.close()
        gatt = null
        _state.value = BleSessionState.Idle
    }

    suspend fun writeAwait(payload: ByteArray, timeoutMs: Long = 1200L): Boolean {
        val g = gatt ?: return false
        val c = txChar ?: return false
        if (pendingWrite?.isActive == true) return false
        val deferred = CompletableDeferred<Int>()
        pendingWrite = deferred
        val started = startWrite(g, c, payload)
        if (!started) {
            pendingWrite = null
            return false
        }
        val status = withTimeoutOrNull(timeoutMs) { deferred.await() }
        if (status == null) {
            if (pendingWrite === deferred) pendingWrite = null
            return false
        }
        if (pendingWrite === deferred) pendingWrite = null
        return status == BluetoothGatt.GATT_SUCCESS
    }

    fun write(payload: ByteArray): Boolean {
        val g = gatt ?: return false
        val c = txChar ?: return false
        return startWrite(g, c, payload)
    }

    suspend fun scanNamedBleDevices(timeoutMs: Long = 3200L): List<BleScanDevice> {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
        val scanner = adapter.bluetoothLeScanner ?: return emptyList()
        val found = linkedMapOf<String, BleScanDevice>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val name = result.scanRecord?.deviceName ?: device.name
                if (name.isNullOrBlank()) return
                if (device.type == BluetoothDevice.DEVICE_TYPE_CLASSIC) return
                val item = BleScanDevice(
                    device = device,
                    name = name,
                    address = device.address ?: return,
                    rssi = result.rssi,
                )
                val old = found[item.address]
                if (old == null || item.rssi > old.rssi) {
                    found[item.address] = item
                }
            }
        }
        runCatching { scanner.startScan(callback) }
        delay(timeoutMs)
        runCatching { scanner.stopScan(callback) }
        return found.values.sortedWith(
            compareByDescending<BleScanDevice> { it.rssi }.thenBy { it.name.lowercase() }
        )
    }

    private fun chooseWriteType(c: BluetoothGattCharacteristic): Int {
        return when {
            c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ->
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 ->
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
    }

    private fun startWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, payload: ByteArray): Boolean {
        val writeType = chooseWriteType(c)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, payload, writeType) == BluetoothGatt.GATT_SUCCESS
        } else {
            c.writeType = writeType
            c.value = payload
            g.writeCharacteristic(c)
        }
    }

    private fun maybeStartServiceDiscovery(gatt: BluetoothGatt) {
        if (servicesStarted) return
        servicesStarted = true
        _state.value = BleSessionState.DiscoveringServices
        gatt.discoverServices()
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        runCatching {
                            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        }
                        val mtuRequested = runCatching { gatt.requestMtu(247) }.getOrDefault(false)
                        if (!mtuRequested) {
                            maybeStartServiceDiscovery(gatt)
                        }
                    } else {
                        maybeStartServiceDiscovery(gatt)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    pendingWrite?.cancel()
                    pendingWrite = null
                    _state.value = BleSessionState.Disconnected
                    close()
                }
                else -> Unit
            }
            if (status != BluetoothGatt.GATT_SUCCESS && newState != BluetoothProfile.STATE_CONNECTED) {
                pendingWrite?.cancel()
                pendingWrite = null
                _state.value = BleSessionState.Failed("Connection state error: $status")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            currentMtu = mtu
            maybeStartServiceDiscovery(gatt)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.value = BleSessionState.Failed("Service discovery failed: $status")
                return
            }
            val service = gatt.getService(serviceUuid)
            txChar = service?.getCharacteristic(txCharUuid)
            rxChar = service?.getCharacteristic(rxCharUuid)

            val tx = txChar
            val rx = rxChar
            if (service == null || tx == null || rx == null) {
                _state.value = BleSessionState.Failed("Required GATT UUIDs not found")
                return
            }

            gatt.setCharacteristicNotification(rx, true)
            val cccd = rx.getDescriptor(cccdUuid)
            if (cccd != null) {
                _state.value = BleSessionState.EnablingNotifications
                cccd.value = if (rx.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                } else {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                }
                gatt.writeDescriptor(cccd)
            } else {
                _state.value = BleSessionState.Ready
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == rxChar?.uuid) {
                val payload = characteristic.value?.copyOf() ?: return
                onRxPayload?.invoke(payload)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (characteristic.uuid == txChar?.uuid) {
                pendingWrite?.complete(status)
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.value = BleSessionState.Failed("Write failed: $status")
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid == cccdUuid) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    _state.value = BleSessionState.Ready
                } else {
                    _state.value = BleSessionState.Failed("CCCD write failed: $status")
                }
            }
        }
    }
}


