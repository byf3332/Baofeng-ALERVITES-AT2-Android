package com.byf3332.at2ht

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.SharedPreferences
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleCoroutineScope
import com.byf3332.at2ht.core.ble.BleScanDevice
import com.byf3332.at2ht.core.ble.BleSessionState
import com.byf3332.at2ht.core.protocol.At2ProtocolExecutor
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class KnownDevicesController(
    private val context: Context,
    private val scope: LifecycleCoroutineScope,
    private val prefs: SharedPreferences,
    private val protocolExecutor: At2ProtocolExecutor,
    private val knownDevices: MutableList<BleScanDevice>,
    private val knownDevicesPrefKey: String,
    private val lastDevicePrefKey: String,
    private val getBleState: () -> BleSessionState,
    private val getSelectedDeviceAddress: () -> String?,
    private val getConnectingDeviceAddress: () -> String?,
    private val setSelectedDeviceAddress: (String?) -> Unit,
    private val onKnownDevicesChanged: () -> Unit,
    private val onReconnectRequested: (BleScanDevice, Boolean) -> Unit,
    private val stopConnectionBeforeDelete: suspend () -> Unit,
    private val styleDialog: (AlertDialog) -> Unit,
) {
    fun upsertKnownDevice(device: BleScanDevice) {
        val index = knownDevices.indexOfFirst { it.address == device.address }
        if (index >= 0) {
            knownDevices[index] = device
        } else {
            knownDevices.add(device)
        }
        saveKnownDevices()
        onKnownDevicesChanged()
    }

    fun loadKnownDevices() {
        knownDevices.clear()
        val raw = prefs.getString(knownDevicesPrefKey, null) ?: return
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val address = obj.optString("address")
                val name = obj.optString("name")
                val rssi = obj.optInt("rssi", -127)
                if (address.isBlank() || name.isBlank()) continue
                val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: continue
                knownDevices.add(
                    BleScanDevice(
                        device = device,
                        name = name,
                        address = address,
                        rssi = rssi,
                    )
                )
            }
        }
    }

    fun saveKnownDevices() {
        val array = JSONArray()
        knownDevices.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("name", item.name)
                    put("address", item.address)
                    put("rssi", item.rssi)
                }
            )
        }
        prefs.edit().putString(knownDevicesPrefKey, array.toString()).apply()
    }

    fun autoReconnectLastDevice() {
        val address = prefs.getString(lastDevicePrefKey, null) ?: return
        val device = knownDevices.firstOrNull { it.address == address } ?: return
        onReconnectRequested(device, false)
    }

    fun confirmDeleteDevice(device: BleScanDevice) {
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.device_delete_title)
            .setMessage(context.getString(R.string.device_delete_message, device.name))
            .setPositiveButton(R.string.common_confirm) { _, _ ->
                scope.launch {
                    if (getSelectedDeviceAddress() == device.address || getConnectingDeviceAddress() == device.address) {
                        stopConnectionBeforeDelete()
                    }
                    removeKnownDevice(device.address)
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .create()
        styleDialog(dialog)
        dialog.show()
    }

    fun removeKnownDevice(address: String) {
        val removedCurrent = getSelectedDeviceAddress() == address
        val removedLast = prefs.getString(lastDevicePrefKey, null) == address
        knownDevices.removeAll { it.address == address }
        saveKnownDevices()
        if (removedCurrent) {
            setSelectedDeviceAddress(null)
        }
        if (removedCurrent || removedLast) {
            prefs.edit().remove(lastDevicePrefKey).apply()
        }
        onKnownDevicesChanged()
    }

    fun showRenameDeviceDialog(device: BleScanDevice) {
        if (getBleState() != BleSessionState.Ready || getSelectedDeviceAddress() != device.address) return
        val input = EditText(context).apply {
            setText(device.name)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter { source, start, end, dest, dstart, dend ->
                val replacement = source?.subSequence(start, end)?.toString().orEmpty()
                val candidate = StringBuilder(dest.toString())
                    .replace(dstart, dend, replacement)
                    .toString()
                if (isValidDeviceNameInput(candidate)) null else ""
            })
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.device_rename_title)
            .setView(input)
            .setPositiveButton(R.string.common_confirm, null)
            .setNegativeButton(R.string.common_cancel, null)
            .create()
        styleDialog(dialog)
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val newName = input.text?.toString()?.trim().orEmpty()
            if (newName.isBlank()) {
                input.error = context.getString(R.string.device_name_empty_error)
                return@setOnClickListener
            }
            if (!isValidDeviceNameInput(newName)) {
                input.error = context.getString(R.string.device_name_too_long_error)
                return@setOnClickListener
            }
            if (newName == device.name) {
                dialog.dismiss()
                return@setOnClickListener
            }
            scope.launch {
                val applied = protocolExecutor.setDeviceName(newName)
                if (!applied) {
                    Toast.makeText(context, R.string.device_rename_timeout, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val index = knownDevices.indexOfFirst { it.address == device.address }
                if (index >= 0) {
                    knownDevices[index] = knownDevices[index].copy(name = newName)
                    saveKnownDevices()
                    onKnownDevicesChanged()
                }
                dialog.dismiss()
                val tipDialog = AlertDialog.Builder(context)
                    .setTitle(R.string.device_rename_applied_title)
                    .setMessage(R.string.device_rename_applied_message)
                    .setPositiveButton(R.string.common_understood, null)
                    .create()
                styleDialog(tipDialog)
                tipDialog.show()
            }
        }
    }

    private fun isValidDeviceNameInput(name: String): Boolean {
        val utf8Bytes = name.toByteArray(Charsets.UTF_8).size
        if (utf8Bytes < 0 || utf8Bytes > 30) return false
        if (utf8Bytes < 30) return true
        return name.any { it.code > 0x7F }
    }
}
