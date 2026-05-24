package com.byf3332.at2ht

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import com.byf3332.at2ht.core.ble.BleSessionState
import com.byf3332.at2ht.core.chat.OfflineVoiceRecording
import com.byf3332.at2ht.core.protocol.At2Commands
import com.byf3332.at2ht.core.protocol.At2ProtocolExecutor
import com.byf3332.at2ht.core.protocol.ChannelConfig
import com.byf3332.at2ht.core.ptt.PttVoiceSender
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class PttUiController(
    private val context: Context,
    private val scope: LifecycleCoroutineScope,
    private val protocolExecutor: At2ProtocolExecutor,
    private val pttSender: PttVoiceSender,
    private val getBleState: () -> BleSessionState,
    private val channels: List<ChannelConfig>,
    private val getCurrentCh: () -> Int,
    private val setCurrentCh: (Int) -> Unit,
    private val getFeatureSettings: () -> com.byf3332.at2ht.core.protocol.FeatureSettingsState,
    private val getDualWatchFocus: () -> At2Commands.Side,
    private val getDualWatchChannelA: () -> Int,
    private val setDualWatchChannelA: (Int) -> Unit,
    private val getDualWatchChannelB: () -> Int,
    private val setDualWatchChannelB: (Int) -> Unit,
    private val setAllowStatusChannelSync: (Boolean) -> Unit,
    private val renderFrequencyCard: () -> Unit,
    private val renderChannelGridSelection: () -> Unit,
    private val tvPttHint: TextView,
    private val pttPressArea: LinearLayout,
    private val tvPttChannelLabel: TextView,
    private val tvPttFrequency: TextView,
    private val tvPttState: TextView,
    private val tvPttSubState: TextView,
    private val btnPttPrevChannel: TextView,
    private val btnPttNextChannel: TextView,
    private val channelGrid: GridLayout,
    private val dp: (Int) -> Int,
) {
    private var pttRunning = false
    private var pttPressHeld = false
    private var pttStartJob: Job? = null
    private var channelSwitchJob: Job? = null

    fun bind() {
        pttPressArea.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pttPressHeld = true
                    pttStartJob?.cancel()
                    pttStartJob = scope.launch {
                        val channel = channels.getOrNull(getCurrentCh() - 1) ?: ChannelConfig()
                        if (!channel.modeDigital) {
                            Toast.makeText(context, R.string.ptt_mode_not_supported, Toast.LENGTH_SHORT).show()
                            render()
                            return@launch
                        }
                        if (pttPressHeld && !pttRunning && getBleState() == BleSessionState.Ready) {
                            protocolExecutor.setOfflineMode(ptt = true, tag = "PTT PRESS ON")
                            delay(20)
                            if (!pttPressHeld) {
                                protocolExecutor.setOfflineMode(ptt = false, tag = "PTT PRESS CANCEL")
                                return@launch
                            }
                            pttSender.start()
                            pttRunning = true
                            render()
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pttPressHeld = false
                    pttStartJob?.cancel()
                    pttStartJob = null
                    scope.launch {
                        if (pttRunning) {
                            pttSender.stopAndJoin()
                            pttRunning = false
                            protocolExecutor.setOfflineMode(ptt = false, tag = "PTT PRESS OFF")
                            render()
                        }
                    }
                    true
                }

                else -> false
            }
        }
        btnPttPrevChannel.setOnClickListener { changeChannel(-1) }
        btnPttNextChannel.setOnClickListener { changeChannel(1) }
    }

    fun render() {
        val channel = channels.getOrNull(getCurrentCh() - 1) ?: ChannelConfig()
        val ready = getBleState() == BleSessionState.Ready
        val supported = channel.modeDigital && channel.rxMhz != null && channel.txMhz != null
        tvPttHint.text = when {
            !ready -> context.getString(R.string.ptt_status_not_ready)
            !supported -> context.getString(R.string.ptt_status_channel_unsupported)
            pttRunning -> context.getString(R.string.ptt_status_running)
            else -> context.getString(R.string.ptt_status_ready)
        }
        tvPttChannelLabel.text = context.getString(R.string.channel_label, getCurrentCh())
        tvPttFrequency.text = channel.rxMhz?.let { String.format(Locale.US, "%.4f", it) } ?: "--.----"
        tvPttState.text = if (supported) context.getString(R.string.ptt_label) else context.getString(R.string.ptt_not_supported)
        tvPttSubState.text = when {
            !supported -> context.getString(R.string.ptt_state_not_supported)
            pttRunning -> context.getString(R.string.ptt_state_release_to_stop)
            else -> context.getString(R.string.ptt_state_hold_to_talk)
        }
        pttPressArea.isEnabled = ready && supported
        pttPressArea.alpha = if (pttPressArea.isEnabled) 1f else 0.7f
        pttPressArea.setBackgroundResource(if (pttPressArea.isEnabled) R.drawable.bg_ptt_press_button else R.drawable.bg_ptt_press_button_disabled)
        val canSwitchChannel = channelSwitchJob?.isActive != true
        btnPttPrevChannel.alpha = if (canSwitchChannel) 1f else 0.45f
        btnPttNextChannel.alpha = if (canSwitchChannel) 1f else 0.45f
        btnPttPrevChannel.isEnabled = canSwitchChannel
        btnPttNextChannel.isEnabled = canSwitchChannel
    }

    fun populateChannelGrid() {
        channelGrid.removeAllViews()
        for (ch in 1..30) {
            val cell = TextView(context).apply {
                tag = ch
                text = context.getString(R.string.channel_label, ch)
                gravity = Gravity.CENTER
                setTextColor(androidx.core.content.ContextCompat.getColor(context, android.R.color.black))
                textSize = 15f
                background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_channel_normal)
                setPadding(0, dp(14), 0, dp(14))
                typeface = Typeface.DEFAULT_BOLD
                setOnClickListener {
                    if (channelSwitchJob?.isActive == true) {
                        Toast.makeText(context, R.string.ptt_switching_channel, Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    if (!getFeatureSettings().dualWatch && ch == getCurrentCh()) {
                        return@setOnClickListener
                    }
                    setCurrentCh(ch)
                    if (getFeatureSettings().dualWatch) {
                        if (getDualWatchFocus() == At2Commands.Side.A) setDualWatchChannelA(ch) else setDualWatchChannelB(ch)
                        channelSwitchJob = scope.launch {
                            val ok = protocolExecutor.switchDualWatchChannel(getDualWatchFocus(), ch)
                            if (!ok) {
                                Toast.makeText(context, R.string.ptt_switch_channel_failed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        setDualWatchChannelA(ch)
                        channelSwitchJob = scope.launch {
                            val ok = protocolExecutor.switchChannel(ch)
                            if (!ok) {
                                Toast.makeText(context, R.string.ptt_switch_channel_failed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    setAllowStatusChannelSync(false)
                    renderChannelGridSelection()
                    renderFrequencyCard()
                }
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(0, 0, 0, dp(8))
            }
            channelGrid.addView(cell, params)
        }
    }

    fun changeChannel(delta: Int) {
        if (channelSwitchJob?.isActive == true) {
            Toast.makeText(context, R.string.ptt_switching_channel, Toast.LENGTH_SHORT).show()
            return
        }
        val target = (((getCurrentCh() - 1 + delta) % 30) + 30) % 30 + 1
        setCurrentCh(target)
        setDualWatchChannelA(target)
        setAllowStatusChannelSync(false)
        render()
        renderFrequencyCard()
        renderChannelGridSelection()
        channelSwitchJob = scope.launch {
            val ok = protocolExecutor.switchChannel(target)
            if (!ok) {
                Toast.makeText(context, R.string.ptt_switch_channel_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun stop() {
        pttPressHeld = false
        pttStartJob?.cancel()
        pttStartJob = null
        channelSwitchJob?.cancel()
        channelSwitchJob = null
        pttSender.stop()
        pttRunning = false
    }

    suspend fun stopAndJoin() {
        pttPressHeld = false
        pttStartJob?.cancel()
        pttStartJob = null
        channelSwitchJob?.cancel()
        channelSwitchJob = null
        pttSender.stopAndJoin()
        pttRunning = false
    }
}
