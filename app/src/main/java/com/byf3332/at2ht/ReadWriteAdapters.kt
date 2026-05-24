package com.byf3332.at2ht

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.byf3332.at2ht.core.protocol.At2ChannelCodec
import com.byf3332.at2ht.core.protocol.ChannelConfig

data class ReadWriteRowItem(
    val channel: Int,
    val current: ChannelConfig,
    val baseline: ChannelConfig,
)

class ReadWriteChannelFixedAdapter(
    private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<ReadWriteChannelFixedAdapter.FixedViewHolder>() {

    private val items = mutableListOf<ReadWriteRowItem>()

    fun submit(current: List<ChannelConfig>, baseline: List<ChannelConfig>) {
        items.clear()
        val count = minOf(current.size, baseline.size, 30)
        repeat(count) { index ->
            items += ReadWriteRowItem(index + 1, current[index], baseline[index])
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FixedViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_read_write_channel_fixed, parent, false)
        return FixedViewHolder(view, onClick)
    }

    override fun onBindViewHolder(holder: FixedViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class FixedViewHolder(
        view: View,
        private val onClick: (Int) -> Unit,
    ) : RecyclerView.ViewHolder(view) {
        private val tvChannel: TextView = view.findViewById(R.id.tvRwFixedChannel)

        fun bind(item: ReadWriteRowItem) {
            val modified = item.current != item.baseline
            tvChannel.text = "CH${item.channel}"
            tvChannel.setTextColor(if (modified) 0xFF1D4ED8.toInt() else 0xFF2563EB.toInt())
            itemView.setBackgroundColor(if (item.channel % 2 == 0) 0xFFF7FAFF.toInt() else Color.WHITE)
            itemView.alpha = if (modified) 1f else 0.96f
            itemView.setOnClickListener { onClick(item.channel) }
        }
    }
}

class ReadWriteChannelTableAdapter(
    private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<ReadWriteChannelTableAdapter.TableViewHolder>() {

    private val items = mutableListOf<ReadWriteRowItem>()

    fun submit(current: List<ChannelConfig>, baseline: List<ChannelConfig>) {
        items.clear()
        val count = minOf(current.size, baseline.size, 30)
        repeat(count) { index ->
            items += ReadWriteRowItem(index + 1, current[index], baseline[index])
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TableViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_read_write_row, parent, false)
        return TableViewHolder(view, onClick)
    }

    override fun onBindViewHolder(holder: TableViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class TableViewHolder(
        view: View,
        private val onClick: (Int) -> Unit,
    ) : RecyclerView.ViewHolder(view) {
        private val rowRoot: LinearLayout = view.findViewById(R.id.readWriteRowRoot)
        private val tvRx: TextView = view.findViewById(R.id.tvRwRx)
        private val tvTx: TextView = view.findViewById(R.id.tvRwTx)
        private val tvMode: TextView = view.findViewById(R.id.tvRwMode)
        private val tvRxTone: TextView = view.findViewById(R.id.tvRwRxTone)
        private val tvTxTone: TextView = view.findViewById(R.id.tvRwTxTone)
        private val tvHop: TextView = view.findViewById(R.id.tvRwHop)
        private val tvEncrypt: TextView = view.findViewById(R.id.tvRwEncrypt)
        private val tvBandwidth: TextView = view.findViewById(R.id.tvRwBandwidth)
        private val tvScanAdd: TextView = view.findViewById(R.id.tvRwScanAdd)
        private val tvBusyLock: TextView = view.findViewById(R.id.tvRwBusyLock)
        private val tvPower: TextView = view.findViewById(R.id.tvRwPower)

        fun bind(item: ReadWriteRowItem) {
            val modified = item.current != item.baseline
            tvRx.text = item.current.rxMhz?.let { String.format("%.5f", it) } ?: itemView.context.getString(R.string.common_empty)
            tvTx.text = item.current.txMhz?.let { String.format("%.5f", it) } ?: itemView.context.getString(R.string.common_empty)
            tvMode.text = if (item.current.modeDigital) itemView.context.getString(R.string.read_write_mode_digital) else itemView.context.getString(R.string.read_write_mode_analog)
            tvRxTone.text = At2ChannelCodec.toneLabel(item.current.rxTone, item.current.rxToneType, item.current.rxTonePolarity)
            tvTxTone.text = At2ChannelCodec.toneLabel(item.current.txTone, item.current.txToneType, item.current.txTonePolarity)
            tvHop.text = if (item.current.hopOn) itemView.context.getString(R.string.state_on) else itemView.context.getString(R.string.state_off)
            tvEncrypt.text = if (item.current.encryptKey == 0) "OFF" else item.current.encryptKey.toString()
            tvBandwidth.text = if (item.current.bandwidthNarrow) itemView.context.getString(R.string.read_write_bandwidth_narrow) else itemView.context.getString(R.string.read_write_bandwidth_wide)
            tvScanAdd.text = if (item.current.scanAdd) itemView.context.getString(R.string.state_on) else itemView.context.getString(R.string.state_off)
            tvBusyLock.text = if (item.current.busyLock) itemView.context.getString(R.string.state_on) else itemView.context.getString(R.string.state_off)
            tvPower.text = if (item.current.highPower) itemView.context.getString(R.string.read_write_power_high) else itemView.context.getString(R.string.read_write_power_low)
            rowRoot.setBackgroundColor(if (item.channel % 2 == 0) 0xFFF7FAFF.toInt() else Color.WHITE)
            rowRoot.alpha = if (modified) 1f else 0.96f
            rowRoot.setOnClickListener { onClick(item.channel) }
        }
    }
}

class ReadWriteSelectorChannelAdapter(
    private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<ReadWriteSelectorChannelAdapter.SelectorViewHolder>() {

    private val items = mutableListOf<ReadWriteRowItem>()

    fun submit(current: List<ChannelConfig>, baseline: List<ChannelConfig>) {
        items.clear()
        val count = minOf(current.size, baseline.size, 30)
        repeat(count) { index ->
            items += ReadWriteRowItem(index + 1, current[index], baseline[index])
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SelectorViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_read_write_selector_channel, parent, false)
        return SelectorViewHolder(view, onClick)
    }

    override fun onBindViewHolder(holder: SelectorViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class SelectorViewHolder(
        view: View,
        private val onClick: (Int) -> Unit,
    ) : RecyclerView.ViewHolder(view) {
        private val tvChannel: TextView = view.findViewById(R.id.tvReadWriteSelectorChannel)

        fun bind(item: ReadWriteRowItem) {
            val modified = item.current != item.baseline
            tvChannel.text = "CH${item.channel}"
            tvChannel.setTextColor(if (modified) 0xFF1D4ED8.toInt() else 0xFF111827.toInt())
            tvChannel.alpha = if (modified) 1f else 0.96f
            tvChannel.setOnClickListener { onClick(item.channel) }
        }
    }
}
