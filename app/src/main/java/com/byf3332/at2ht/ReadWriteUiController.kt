package com.byf3332.at2ht

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.InsetDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.content.res.AppCompatResources.getDrawable
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.RecyclerView
import com.byf3332.at2ht.core.ble.BleSessionState
import com.byf3332.at2ht.core.protocol.At2ChannelCodec
import com.byf3332.at2ht.core.protocol.At2ProtocolExecutor
import com.byf3332.at2ht.core.protocol.ChannelConfig
import com.byf3332.at2ht.widget.SyncHorizontalScrollView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ReadWriteUiController(
    private val context: Context,
    private val scope: LifecycleCoroutineScope,
    private val protocolExecutor: At2ProtocolExecutor,
    private val getBleState: () -> BleSessionState,
    private val getReadWriteLoaded: () -> Boolean,
    private val setReadWriteLoaded: (Boolean) -> Unit,
    private val channels: MutableList<ChannelConfig>,
    private val readWriteChannels: MutableList<ChannelConfig>,
    private val readWriteBaselineChannels: MutableList<ChannelConfig>,
    private val fixedAdapter: ReadWriteChannelFixedAdapter,
    private val tableAdapter: ReadWriteChannelTableAdapter,
    private val selectorAdapterFactory: ((Int) -> Unit) -> ReadWriteSelectorChannelAdapter,
    private val renderFrequencyCard: () -> Unit,
    private val renderChannelGridSelection: () -> Unit,
    private val renderReadWrite: () -> Unit,
    private val renderAll: () -> Unit,
    private val setAllowStatusChannelSync: (Boolean) -> Unit,
    private val queryBootstrapAndCurrentChannel: suspend (String) -> Unit,
    private val setShowLoading: (Boolean, String) -> Unit,
    private val styleDialog: (AlertDialog) -> Unit,
    private val buildDialogListAdapter: (List<String>) -> ArrayAdapter<String>,
) {
    private var syncingReadWriteVerticalScroll = false

    fun bindScrollSync(
        hsvReadWriteHeader: SyncHorizontalScrollView,
        hsvReadWriteBody: SyncHorizontalScrollView,
        rvReadWriteFixedChannel: RecyclerView,
        rvReadWriteRows: RecyclerView,
    ) {
        var syncingHorizontal = false
        hsvReadWriteHeader.onScrollChangedListener = { scrollX ->
            if (!syncingHorizontal) {
                syncingHorizontal = true
                hsvReadWriteBody.scrollTo(scrollX, 0)
                syncingHorizontal = false
            }
        }
        hsvReadWriteBody.onScrollChangedListener = { scrollX ->
            if (!syncingHorizontal) {
                syncingHorizontal = true
                hsvReadWriteHeader.scrollTo(scrollX, 0)
                syncingHorizontal = false
            }
        }
        rvReadWriteFixedChannel.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy == 0 || syncingReadWriteVerticalScroll) return
                syncingReadWriteVerticalScroll = true
                rvReadWriteRows.scrollBy(0, dy)
                syncingReadWriteVerticalScroll = false
            }
        })
        rvReadWriteRows.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy == 0 || syncingReadWriteVerticalScroll) return
                syncingReadWriteVerticalScroll = true
                rvReadWriteFixedChannel.scrollBy(0, dy)
                syncingReadWriteVerticalScroll = false
            }
        })
    }

    fun syncFromDevice() {
        for (i in channels.indices) {
            readWriteChannels[i] = channels[i]
            readWriteBaselineChannels[i] = channels[i]
        }
        setReadWriteLoaded(true)
        renderReadWrite()
    }

    fun showSelectorDialog(onChannelChosen: (Int) -> Unit) {
        if (!getReadWriteLoaded()) {
            Toast.makeText(context, R.string.read_write_channel_data_not_loaded, Toast.LENGTH_SHORT).show()
            return
        }
        val view = View.inflate(context, R.layout.dialog_read_write_selector, null)
        val tvSubtitle = view.findViewById<TextView>(R.id.tvReadWriteSelectorSubtitle)
        val rvSelector = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvReadWriteSelectorChannels)
        val btnClose = view.findViewById<TextView>(R.id.btnReadWriteSelectorClose)
        val btnWrite = view.findViewById<TextView>(R.id.btnReadWriteSelectorWrite)
        val dialog = androidx.appcompat.app.AppCompatDialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(view)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        val adapter = selectorAdapterFactory { channel ->
            dialog.dismiss()
            onChannelChosen(channel)
        }
        rvSelector.layoutManager = androidx.recyclerview.widget.GridLayoutManager(context, 4)
        rvSelector.adapter = adapter
        adapter.submit(readWriteChannels, readWriteBaselineChannels)
        fun updateSubtitle() {
            val dirtyCount = readWriteChannels.indices.count { readWriteChannels[it] != readWriteBaselineChannels[it] }
            tvSubtitle.text = if (dirtyCount == 0) {
                context.getString(R.string.read_write_select_channel)
            } else {
                context.getString(R.string.read_write_dirty_summary, dirtyCount)
            }
            btnWrite.alpha = if (dirtyCount > 0 && getBleState() == BleSessionState.Ready) 1f else 0.5f
        }
        updateSubtitle()
        btnClose.setOnClickListener {
            renderReadWrite()
            dialog.dismiss()
        }
        btnWrite.setOnClickListener {
            val dirtyCount = readWriteChannels.indices.count { readWriteChannels[it] != readWriteBaselineChannels[it] }
            if (dirtyCount == 0) {
                Toast.makeText(context, R.string.read_write_no_changes_to_write, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (getBleState() != BleSessionState.Ready) {
                Toast.makeText(context, R.string.read_write_device_not_connected, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            scope.launch {
                dialog.dismiss()
                setShowLoading(true, context.getString(R.string.read_write_writing))
                renderAll()
                val ok = protocolExecutor.writeAllChannelConfigs(readWriteChannels)
                setShowLoading(false, "")
                if (!ok) {
                    Toast.makeText(context, R.string.read_write_write_failed, Toast.LENGTH_SHORT).show()
                    renderAll()
                    return@launch
                }
                for (i in readWriteChannels.indices) {
                    channels[i] = readWriteChannels[i]
                    readWriteBaselineChannels[i] = readWriteChannels[i]
                }
                Toast.makeText(context, R.string.read_write_write_success, Toast.LENGTH_SHORT).show()
                renderAll()
            }
        }
        dialog.setOnDismissListener {
            renderReadWrite()
        }
        dialog.show()
    }

    fun showHomeChannelEditDialog(channel: Int) {
        val config = channels.getOrNull(channel - 1) ?: ChannelConfig()
        val view = View.inflate(context, R.layout.dialog_channel_edit, null)
        val tvChannel = view.findViewById<TextView>(R.id.tvEditChannelName)
        val etRx = view.findViewById<EditText>(R.id.etRxFreq)
        val etTx = view.findViewById<EditText>(R.id.etTxFreq)
        val tvTxFreqHint = view.findViewById<TextView>(R.id.tvTxFreqHint)
        val etMode = view.findViewById<EditText>(R.id.etTalkMode)
        val tvRxToneLabel = view.findViewById<TextView>(R.id.tvRxToneLabel)
        val etRxTone = view.findViewById<EditText>(R.id.etRxTone)
        val tvTxToneLabel = view.findViewById<TextView>(R.id.tvTxToneLabel)
        val etTxTone = view.findViewById<EditText>(R.id.etTxTone)
        val tvHopLabel = view.findViewById<TextView>(R.id.tvHopLabel)
        val switchHop = view.findViewById<SwitchMaterial>(R.id.switchHop)
        val tvEncryptLabel = view.findViewById<TextView>(R.id.tvEncryptLabel)
        val etEncrypt = view.findViewById<EditText>(R.id.etEncryptKey)
        val etBandwidth = view.findViewById<EditText>(R.id.etBandwidth)
        val switchScanAdd = view.findViewById<SwitchMaterial>(R.id.switchScanAdd)
        val switchBusyLock = view.findViewById<SwitchMaterial>(R.id.switchBusyLock)
        val etPower = view.findViewById<EditText>(R.id.etPower)

        tvChannel.text = "CH$channel"
        bindConfigToEditor(config, etRx, etTx, etMode, etRxTone, etTxTone, switchHop, etEncrypt, etBandwidth, switchScanAdd, switchBusyLock, etPower)
        val talkModeApplier = buildTalkModeConstraintApplier(etMode, etRx, etTx, tvTxFreqHint, etRxTone, etTxTone, switchHop, etEncrypt, tvRxToneLabel, tvTxToneLabel, tvHopLabel, tvEncryptLabel)
        etRx.addTextChangedListener(rxMirrorWatcher(etMode, etTx))
        configureChoiceField(etMode, listOf(context.getString(R.string.read_write_mode_analog), context.getString(R.string.read_write_mode_digital))) { talkModeApplier(true) }
        configureChoiceField(etRxTone, At2ChannelCodec.toneOptions())
        configureChoiceField(etTxTone, At2ChannelCodec.toneOptions())
        configureChoiceField(etEncrypt, At2ChannelCodec.encryptOptions())
        configureChoiceField(etBandwidth, listOf(context.getString(R.string.read_write_bandwidth_wide), context.getString(R.string.read_write_bandwidth_narrow)))
        configureChoiceField(etPower, listOf(context.getString(R.string.read_write_power_low), context.getString(R.string.read_write_power_high)))
        talkModeApplier(false)

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(view)
            .create()

        val btnClearChannel = view.findViewById<Button>(R.id.btnClearChannel)
        btnClearChannel.backgroundTintList = ColorStateList.valueOf(0xFFDC2626.toInt())
        btnClearChannel.setTextColor(Color.WHITE)
        btnClearChannel.setOnClickListener {
            val confirmDialog = MaterialAlertDialogBuilder(context)
                .setTitle(R.string.read_write_clear_channel_title)
                .setMessage(context.getString(R.string.read_write_clear_channel_message, channel))
                .setPositiveButton(R.string.common_confirm) { _, _ ->
                    scope.launch {
                        try {
                            protocolExecutor.writeEmptyChannel(channel)
                            channels[channel - 1] = ChannelConfig()
                            renderFrequencyCard()
                            renderChannelGridSelection()
                            dialog.dismiss()
                            Toast.makeText(context, context.getString(R.string.read_write_channel_cleared, channel), Toast.LENGTH_SHORT).show()
                            delay(180)
                            setAllowStatusChannelSync(true)
                            queryBootstrapAndCurrentChannel("channel-clear")
                        } catch (t: Throwable) {
                            Toast.makeText(context, context.getString(R.string.read_write_clear_failed, t.message ?: context.getString(R.string.read_write_unknown_error)), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton(R.string.common_cancel, null)
                .create()
            styleDialog(confirmDialog)
            confirmDialog.show()
        }

        view.findViewById<TextView>(R.id.btnSaveChannel).setOnClickListener {
            val parsed = parseEditedChannelConfig(
                base = config,
                etRx = etRx,
                etTx = etTx,
                etMode = etMode,
                etRxTone = etRxTone,
                etTxTone = etTxTone,
                switchHop = switchHop,
                etEncrypt = etEncrypt,
                etBandwidth = etBandwidth,
                switchScanAdd = switchScanAdd,
                switchBusyLock = switchBusyLock,
                etPower = etPower,
            ) ?: return@setOnClickListener
            scope.launch {
                try {
                    protocolExecutor.writeSingleChannelConfig(channel, parsed)
                    channels[channel - 1] = parsed
                    renderFrequencyCard()
                    renderChannelGridSelection()
                    dialog.dismiss()
                    Toast.makeText(context, context.getString(R.string.read_write_channel_saved, channel), Toast.LENGTH_SHORT).show()
                    delay(180)
                    setAllowStatusChannelSync(true)
                    queryBootstrapAndCurrentChannel("channel-edit-save")
                } catch (t: Throwable) {
                    Toast.makeText(context, context.getString(R.string.read_write_save_failed, t.message ?: context.getString(R.string.read_write_unknown_error)), Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show()
    }

    fun showReadWriteChannelEditDialog(channel: Int) {
        if (!getReadWriteLoaded()) {
            Toast.makeText(context, R.string.read_write_channel_data_not_loaded, Toast.LENGTH_SHORT).show()
            return
        }
        val editorView = View.inflate(context, R.layout.dialog_channel_edit, null)
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(20))
            addView(editorView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, 0)
                val prevButton = TextView(context).apply {
                    id = View.generateViewId()
                    background = ContextCompat.getDrawable(context, R.drawable.bg_card_white)
                    gravity = Gravity.CENTER
                    text = context.getString(R.string.read_write_prev_channel)
                    setTextColor(0xFF111827.toInt())
                    textSize = 16f
                    setTypeface(null, Typeface.BOLD)
                }
                val nextButton = TextView(context).apply {
                    id = View.generateViewId()
                    background = ContextCompat.getDrawable(context, R.drawable.bg_card_white)
                    gravity = Gravity.CENTER
                    text = context.getString(R.string.read_write_next_channel)
                    setTextColor(0xFF111827.toInt())
                    textSize = 16f
                    setTypeface(null, Typeface.BOLD)
                }
                val lp = LinearLayout.LayoutParams(0, dp(52), 1f)
                addView(prevButton, lp)
                addView(Space(context).apply { layoutParams = LinearLayout.LayoutParams(dp(12), 1) })
                addView(nextButton, lp)
                tag = Pair(prevButton, nextButton)
            })
        }
        val navRow = wrapper.getChildAt(1) as LinearLayout
        val navPair = navRow.tag as Pair<*, *>
        val btnPrev = navPair.first as TextView
        val btnNext = navPair.second as TextView
        var currentChannel = channel

        val tvChannel = editorView.findViewById<TextView>(R.id.tvEditChannelName)
        val etRx = editorView.findViewById<EditText>(R.id.etRxFreq)
        val etTx = editorView.findViewById<EditText>(R.id.etTxFreq)
        val tvTxFreqHint = editorView.findViewById<TextView>(R.id.tvTxFreqHint)
        val etMode = editorView.findViewById<EditText>(R.id.etTalkMode)
        val tvRxToneLabel = editorView.findViewById<TextView>(R.id.tvRxToneLabel)
        val etRxTone = editorView.findViewById<EditText>(R.id.etRxTone)
        val tvTxToneLabel = editorView.findViewById<TextView>(R.id.tvTxToneLabel)
        val etTxTone = editorView.findViewById<EditText>(R.id.etTxTone)
        val tvHopLabel = editorView.findViewById<TextView>(R.id.tvHopLabel)
        val switchHop = editorView.findViewById<SwitchMaterial>(R.id.switchHop)
        val tvEncryptLabel = editorView.findViewById<TextView>(R.id.tvEncryptLabel)
        val etEncrypt = editorView.findViewById<EditText>(R.id.etEncryptKey)
        val etBandwidth = editorView.findViewById<EditText>(R.id.etBandwidth)
        val switchScanAdd = editorView.findViewById<SwitchMaterial>(R.id.switchScanAdd)
        val switchBusyLock = editorView.findViewById<SwitchMaterial>(R.id.switchBusyLock)
        val etPower = editorView.findViewById<EditText>(R.id.etPower)
        val talkModeApplier = buildTalkModeConstraintApplier(etMode, etRx, etTx, tvTxFreqHint, etRxTone, etTxTone, switchHop, etEncrypt, tvRxToneLabel, tvTxToneLabel, tvHopLabel, tvEncryptLabel)
        etRx.addTextChangedListener(rxMirrorWatcher(etMode, etTx))
        configureChoiceField(etMode, listOf(context.getString(R.string.read_write_mode_analog), context.getString(R.string.read_write_mode_digital))) { talkModeApplier(true) }
        configureChoiceField(etRxTone, At2ChannelCodec.toneOptions())
        configureChoiceField(etTxTone, At2ChannelCodec.toneOptions())
        configureChoiceField(etEncrypt, At2ChannelCodec.encryptOptions())
        configureChoiceField(etBandwidth, listOf(context.getString(R.string.read_write_bandwidth_wide), context.getString(R.string.read_write_bandwidth_narrow)))
        configureChoiceField(etPower, listOf(context.getString(R.string.read_write_power_low), context.getString(R.string.read_write_power_high)))

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(wrapper)
            .create()

        fun bindChannelUi(targetChannel: Int) {
            currentChannel = targetChannel
            val config = readWriteChannels.getOrNull(targetChannel - 1) ?: ChannelConfig()
            tvChannel.text = "CH$targetChannel"
            bindConfigToEditor(config, etRx, etTx, etMode, etRxTone, etTxTone, switchHop, etEncrypt, etBandwidth, switchScanAdd, switchBusyLock, etPower)
            talkModeApplier(false)
            btnPrev.alpha = if (targetChannel > 1) 1f else 0.55f
            btnNext.alpha = if (targetChannel < 30) 1f else 0.55f
        }

        btnPrev.setOnClickListener { if (currentChannel > 1) bindChannelUi(currentChannel - 1) }
        btnNext.setOnClickListener { if (currentChannel < 30) bindChannelUi(currentChannel + 1) }

        val btnClearChannel = editorView.findViewById<Button>(R.id.btnClearChannel)
        btnClearChannel.backgroundTintList = ColorStateList.valueOf(0xFFDC2626.toInt())
        btnClearChannel.setTextColor(Color.WHITE)
        btnClearChannel.setOnClickListener {
            val confirmDialog = MaterialAlertDialogBuilder(context)
                .setTitle(R.string.read_write_clear_channel_title)
                .setMessage(context.getString(R.string.read_write_local_clear_channel_message, currentChannel))
                .setPositiveButton(R.string.common_confirm) { _, _ ->
                    readWriteChannels[currentChannel - 1] = ChannelConfig()
                    renderReadWrite()
                    bindChannelUi(currentChannel)
                    Toast.makeText(context, context.getString(R.string.read_write_channel_locally_cleared, currentChannel), Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.common_cancel, null)
                .create()
            styleDialog(confirmDialog)
            confirmDialog.show()
        }

        editorView.findViewById<TextView>(R.id.btnSaveChannel).setOnClickListener {
            val parsed = parseEditedChannelConfig(
                base = readWriteChannels.getOrNull(currentChannel - 1) ?: ChannelConfig(),
                etRx = etRx,
                etTx = etTx,
                etMode = etMode,
                etRxTone = etRxTone,
                etTxTone = etTxTone,
                switchHop = switchHop,
                etEncrypt = etEncrypt,
                etBandwidth = etBandwidth,
                switchScanAdd = switchScanAdd,
                switchBusyLock = switchBusyLock,
                etPower = etPower,
            ) ?: return@setOnClickListener
            readWriteChannels[currentChannel - 1] = parsed
            renderReadWrite()
            bindChannelUi(currentChannel)
            Toast.makeText(context, context.getString(R.string.read_write_channel_locally_saved, currentChannel), Toast.LENGTH_SHORT).show()
        }

        bindChannelUi(channel)
        dialog.setOnShowListener {
            val base = ContextCompat.getDrawable(context, R.drawable.bg_card_read_write)
            if (base != null) {
                dialog.window?.setBackgroundDrawable(InsetDrawable(base, dp(20), dp(12), dp(20), dp(20)))
            }
        }
        dialog.show()
    }

    private fun bindConfigToEditor(
        config: ChannelConfig,
        etRx: EditText,
        etTx: EditText,
        etMode: EditText,
        etRxTone: EditText,
        etTxTone: EditText,
        switchHop: SwitchMaterial,
        etEncrypt: EditText,
        etBandwidth: EditText,
        switchScanAdd: SwitchMaterial,
        switchBusyLock: SwitchMaterial,
        etPower: EditText,
    ) {
        etRx.setText(config.rxMhz?.let { String.format("%.5f", it) } ?: "")
        etTx.setText(config.txMhz?.let { String.format("%.5f", it) } ?: "")
        etMode.setText(if (config.modeDigital) context.getString(R.string.read_write_mode_digital) else context.getString(R.string.read_write_mode_analog))
        etRxTone.setText(At2ChannelCodec.toneLabel(config.rxTone, config.rxToneType, config.rxTonePolarity))
        etTxTone.setText(At2ChannelCodec.toneLabel(config.txTone, config.txToneType, config.txTonePolarity))
        switchHop.isChecked = config.hopOn
        etEncrypt.setText(if (config.encryptKey == 0) "OFF" else config.encryptKey.toString())
        etBandwidth.setText(if (config.bandwidthNarrow) context.getString(R.string.read_write_bandwidth_narrow) else context.getString(R.string.read_write_bandwidth_wide))
        switchScanAdd.isChecked = config.scanAdd
        switchBusyLock.isChecked = config.busyLock
        etPower.setText(if (config.highPower) context.getString(R.string.read_write_power_high) else context.getString(R.string.read_write_power_low))
    }

    private fun buildTalkModeConstraintApplier(
        etMode: EditText,
        etRx: EditText,
        etTx: EditText,
        tvTxFreqHint: TextView,
        etRxTone: EditText,
        etTxTone: EditText,
        switchHop: SwitchMaterial,
        etEncrypt: EditText,
        tvRxToneLabel: TextView,
        tvTxToneLabel: TextView,
        tvHopLabel: TextView,
        tvEncryptLabel: TextView,
    ): (Boolean) -> Unit = { syncTxFromRx ->
        val digitalMode = etMode.text.toString().trim() == context.getString(R.string.read_write_mode_digital)
        val activeColor = 0xFF111827.toInt()
        val inactiveColor = 0xFF9CA3AF.toInt()
        etTx.isEnabled = !digitalMode
        etTx.isFocusable = !digitalMode
        etTx.isFocusableInTouchMode = !digitalMode
        etTx.isClickable = !digitalMode
        etTx.isLongClickable = !digitalMode
        etTx.alpha = if (digitalMode) 0.55f else 1.0f
        tvTxFreqHint.isVisible = digitalMode
        setChoiceEnabled(etRxTone, !digitalMode)
        setChoiceEnabled(etTxTone, !digitalMode)
        switchHop.isEnabled = !digitalMode
        switchHop.alpha = if (digitalMode) 0.55f else 1.0f
        tvRxToneLabel.setTextColor(if (digitalMode) inactiveColor else activeColor)
        tvTxToneLabel.setTextColor(if (digitalMode) inactiveColor else activeColor)
        tvHopLabel.setTextColor(if (digitalMode) inactiveColor else activeColor)
        setChoiceEnabled(etEncrypt, digitalMode)
        tvEncryptLabel.setTextColor(if (digitalMode) activeColor else inactiveColor)
        if (digitalMode && syncTxFromRx) {
            etTx.setText(etRx.text.toString())
        }
    }

    private fun rxMirrorWatcher(etMode: EditText, etTx: EditText): TextWatcher =
        object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (etMode.text.toString().trim() == context.getString(R.string.read_write_mode_digital) && etTx.text.toString() != s?.toString().orEmpty()) {
                    etTx.setText(s?.toString().orEmpty())
                }
            }
        }

    private fun setChoiceEnabled(editText: EditText, enabled: Boolean) {
        val activeColor = 0xFF111827.toInt()
        val inactiveColor = 0xFF9CA3AF.toInt()
        editText.isEnabled = enabled
        editText.isClickable = enabled
        editText.isFocusable = false
        editText.isFocusableInTouchMode = false
        editText.alpha = if (enabled) 1.0f else 0.55f
        editText.setTextColor(if (enabled) activeColor else inactiveColor)
    }

    private fun configureChoiceField(editText: EditText, options: List<String>, onChosen: (() -> Unit)? = null) {
        editText.inputType = InputType.TYPE_NULL
        editText.keyListener = null
        editText.isFocusable = false
        editText.isClickable = true
        editText.setOnClickListener {
            val listView = ListView(context).apply {
                divider = null
                adapter = buildDialogListAdapter(options)
                setOnItemClickListener { _, _, position, _ ->
                    editText.setText(options[position])
                    onChosen?.invoke()
                    (tag as? AlertDialog)?.dismiss()
                }
            }
            val dialog = AlertDialog.Builder(context)
                .setView(listView)
                .create()
            listView.tag = dialog
            styleDialog(dialog)
            dialog.show()
        }
    }

    private fun parseEditedChannelConfig(
        base: ChannelConfig,
        etRx: EditText,
        etTx: EditText,
        etMode: EditText,
        etRxTone: EditText,
        etTxTone: EditText,
        switchHop: SwitchMaterial,
        etEncrypt: EditText,
        etBandwidth: EditText,
        switchScanAdd: SwitchMaterial,
        switchBusyLock: SwitchMaterial,
        etPower: EditText,
    ): ChannelConfig? {
        val rx = At2ChannelCodec.parseFreqOrNull(etRx.text.toString())
        val digitalMode = etMode.text.toString().trim() == context.getString(R.string.read_write_mode_digital)
        val tx = if (digitalMode) rx else At2ChannelCodec.parseFreqOrNull(etTx.text.toString())
        if (rx == null || tx == null) {
            Toast.makeText(context, R.string.read_write_freq_empty, Toast.LENGTH_SHORT).show()
            return null
        }
        if (rx !in 30.0..520.0 || tx !in 30.0..520.0) {
            Toast.makeText(context, R.string.read_write_freq_out_of_range, Toast.LENGTH_SHORT).show()
            return null
        }
        val rxTone = At2ChannelCodec.parseToneLabel(etRxTone.text.toString()) ?: run {
            Toast.makeText(context, R.string.read_write_rx_subtone_invalid, Toast.LENGTH_SHORT).show()
            return null
        }
        val txTone = At2ChannelCodec.parseToneLabel(etTxTone.text.toString()) ?: run {
            Toast.makeText(context, R.string.read_write_tx_subtone_invalid, Toast.LENGTH_SHORT).show()
            return null
        }
        val encryptKey = At2ChannelCodec.parseEncryptLabel(etEncrypt.text.toString()) ?: run {
            Toast.makeText(context, R.string.read_write_scrambler_invalid, Toast.LENGTH_SHORT).show()
            return null
        }
        return base.copy(
            rxMhz = rx,
            txMhz = tx,
            modeDigital = digitalMode,
            rxTone = rxTone.value,
            rxToneType = rxTone.type,
            txTone = txTone.value,
            txToneType = txTone.type,
            rxTonePolarity = rxTone.polarity,
            txTonePolarity = txTone.polarity,
            hopOn = switchHop.isChecked,
            encryptKey = encryptKey,
            bandwidthNarrow = etBandwidth.text.toString().trim() == context.getString(R.string.read_write_bandwidth_narrow),
            scanAdd = switchScanAdd.isChecked,
            busyLock = switchBusyLock.isChecked,
            highPower = etPower.text.toString().trim() == context.getString(R.string.read_write_power_high),
        )
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
