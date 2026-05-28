package com.byf3332.at2ht

import android.content.res.ColorStateList
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.core.view.isVisible
import com.byf3332.at2ht.core.ble.BleScanDevice
import com.byf3332.at2ht.core.ble.BleSessionState
import com.byf3332.at2ht.core.protocol.At2Commands
import com.byf3332.at2ht.core.protocol.ChannelConfig
import com.byf3332.at2ht.core.protocol.FeatureSettingsState
import kotlinx.coroutines.launch

class HomeUiController(
    private val scope: LifecycleCoroutineScope,
    private val rvDevices: RecyclerView,
    private val fullScreenLoadingSpinner: ProgressBar,
    private val btnEditChannel: TextView,
    private val btnFeatureSettings: TextView,
    private val btnDualWatchFocus: TextView,
    private val channelGrid: GridLayout,
    private val tvCurrentChannel: TextView,
    private val tvFrequencyMain: TextView,
    private val tvFrequencySub: TextView,
    private val getKnownDevices: () -> MutableList<BleScanDevice>,
    private val getBleState: () -> BleSessionState,
    private val getSelectedDeviceAddress: () -> String?,
    private val getConnectingDeviceAddress: () -> String?,
    private val getCurrentCh: () -> Int,
    private val setCurrentCh: (Int) -> Unit,
    private val getFeatureSettings: () -> FeatureSettingsState,
    private val getDualWatchFocus: () -> At2Commands.Side,
    private val setDualWatchFocus: (At2Commands.Side) -> Unit,
    private val getDualWatchChannelA: () -> Int,
    private val getDualWatchChannelB: () -> Int,
    private val setAllowStatusChannelSync: (Boolean) -> Unit,
    private val channels: List<ChannelConfig>,
    private val showDeviceEntryDialog: (BleScanDevice) -> Unit,
    private val showRenameDeviceDialog: (BleScanDevice) -> Unit,
    private val disconnectCurrentKeepDevice: suspend () -> Unit,
    private val confirmDeleteDevice: (BleScanDevice) -> Unit,
    private val showChannelEditDialog: (Int) -> Unit,
    private val enterFeatureSettings: () -> Unit,
    private val switchDualWatchFocus: suspend (At2Commands.Side) -> Unit,
    private val renderFrequencyCard: () -> Unit,
    private val renderChannelGridSelection: () -> Unit,
    private val populateChannelGrid: () -> Unit,
) {
    lateinit var deviceAdapter: DeviceAdapter
        private set

    fun bind() {
        deviceAdapter = DeviceAdapter(
            items = getKnownDevices(),
            isConnected = { item -> getBleState() == BleSessionState.Ready && getSelectedDeviceAddress() == item.address },
            isConnecting = { item -> getConnectingDeviceAddress() == item.address },
            onCardClick = showDeviceEntryDialog,
            onRenameClick = showRenameDeviceDialog,
            onCloseClick = { item ->
                if (getBleState() == BleSessionState.Ready && getSelectedDeviceAddress() == item.address) {
                    scope.launch { disconnectCurrentKeepDevice() }
                } else {
                    confirmDeleteDevice(item)
                }
            },
        )
        rvDevices.layoutManager = LinearLayoutManager(rvDevices.context)
        rvDevices.adapter = deviceAdapter
        fullScreenLoadingSpinner.indeterminateTintList = ColorStateList.valueOf(0xFF1D4ED8.toInt())

        btnEditChannel.setOnClickListener {
            if (getBleState() != BleSessionState.Ready) {
                Toast.makeText(rvDevices.context, R.string.device_card_no_connection, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showChannelEditDialog(getCurrentCh())
        }
        btnFeatureSettings.setOnClickListener { enterFeatureSettings() }
        btnDualWatchFocus.setOnClickListener {
            if (!getFeatureSettings().dualWatch || getBleState() != BleSessionState.Ready) return@setOnClickListener
            scope.launch {
                val next = if (getDualWatchFocus() == At2Commands.Side.A) At2Commands.Side.B else At2Commands.Side.A
                switchDualWatchFocus(next)
                setDualWatchFocus(next)
                setCurrentCh(if (next == At2Commands.Side.A) getDualWatchChannelA() else getDualWatchChannelB())
                setAllowStatusChannelSync(false)
                renderFrequencyCard()
                renderChannelGridSelection()
            }
        }
        populateChannelGrid()
    }

    fun renderFrequencyCard() {
        val currentCh = getCurrentCh()
        val featureSettings = getFeatureSettings()
        tvCurrentChannel.text = rvDevices.context.getString(R.string.channel_label, currentCh)
        btnDualWatchFocus.isVisible = featureSettings.dualWatch
        if (featureSettings.dualWatch) {
            btnDualWatchFocus.text = if (getDualWatchFocus() == At2Commands.Side.A) {
                rvDevices.context.getString(R.string.channel_a)
            } else {
                rvDevices.context.getString(R.string.channel_b)
            }
        }
        val st = ChannelUiState(channels[currentCh - 1].rxMhz, channels[currentCh - 1].txMhz)
        val rx = st.rxMhz
        val tx = st.txMhz
        if (rx == null || tx == null) {
            tvFrequencyMain.text = rvDevices.context.getString(R.string.placeholder_empty_capitalized)
            tvFrequencySub.text = ""
        } else if (kotlin.math.abs(rx - tx) < 0.000001) {
            tvFrequencyMain.text = String.format("%.5f", rx)
            tvFrequencySub.text = ""
        } else {
            tvFrequencyMain.text = rvDevices.context.getString(R.string.frequency_rx_only, rx)
            tvFrequencySub.text = rvDevices.context.getString(R.string.frequency_tx_only, tx)
        }
    }

    fun renderChannelGridSelection() {
        val currentCh = getCurrentCh()
        for (i in 0 until channelGrid.childCount) {
            val child = channelGrid.getChildAt(i)
            val tagValue = child.tag as? Int ?: continue
            child.setBackgroundResource(if (tagValue == currentCh) R.drawable.bg_channel_selected else R.drawable.bg_channel_normal)
        }
    }
}
