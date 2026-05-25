package com.byf3332.at2ht

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.byf3332.at2ht.core.ble.BleScanDevice

class DeviceAdapter(
    private val items: List<BleScanDevice>,
    private val isConnected: (BleScanDevice) -> Boolean,
    private val isConnecting: (BleScanDevice) -> Boolean,
    private val onCardClick: (BleScanDevice) -> Unit,
    private val onRenameClick: (BleScanDevice) -> Unit,
    private val onCloseClick: (BleScanDevice) -> Unit,
) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device_card, parent, false)
        return DeviceViewHolder(view, onCardClick, onRenameClick, onCloseClick)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(items[position], isConnected(items[position]), isConnecting(items[position]))
    }

    class DeviceViewHolder(
        view: View,
        private val onCardClick: (BleScanDevice) -> Unit,
        private val onRenameClick: (BleScanDevice) -> Unit,
        private val onCloseClick: (BleScanDevice) -> Unit,
    ) : RecyclerView.ViewHolder(view) {
        private val deviceMainArea: LinearLayout = view.findViewById(R.id.deviceMainArea)
        private val tvDeviceName: TextView = view.findViewById(R.id.tvDeviceName)
        private val tvDeviceModel: TextView = view.findViewById(R.id.tvDeviceModel)
        private val tvDeviceStatus: TextView = view.findViewById(R.id.tvDeviceStatus)
        private val tvRenameDevice: TextView = view.findViewById(R.id.tvRenameDevice)
        private val deviceStatusSpinner: ProgressBar = view.findViewById(R.id.deviceStatusSpinner)
        private val btnDeviceClose: TextView = view.findViewById(R.id.btnDeviceClose)

        fun bind(item: BleScanDevice, connected: Boolean, connecting: Boolean) {
            tvDeviceName.text = item.name
            tvDeviceModel.text = item.address
            deviceStatusSpinner.indeterminateTintList = ColorStateList.valueOf(0xFF1D4ED8.toInt())
            deviceStatusSpinner.isVisible = connecting
            tvDeviceStatus.isVisible = !connecting
            tvDeviceStatus.text = itemView.context.getString(
                if (connected) R.string.device_status_connected else R.string.device_status_disconnected
            )
            tvDeviceStatus.setTextColor(if (connected) 0xFF2563EB.toInt() else 0xFF6B7280.toInt())
            tvRenameDevice.isVisible = connected && !connecting
            tvRenameDevice.paintFlags = tvRenameDevice.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            tvRenameDevice.setOnClickListener { onRenameClick(item) }
            deviceMainArea.setOnClickListener { onCardClick(item) }
            btnDeviceClose.isVisible = true
            btnDeviceClose.text = "×"
            btnDeviceClose.setTextColor(
                if (connected) 0xFFD11A2A.toInt() else 0xFF111827.toInt()
            )
            btnDeviceClose.setOnClickListener { onCloseClick(item) }
        }
    }
}
