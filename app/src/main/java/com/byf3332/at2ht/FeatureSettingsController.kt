package com.byf3332.at2ht

import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import com.byf3332.at2ht.core.protocol.At2Commands
import com.byf3332.at2ht.core.protocol.At2ProtocolExecutor
import com.byf3332.at2ht.core.protocol.FeatureSettingsState
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.launch

class FeatureSettingsController(
    private val scope: LifecycleCoroutineScope,
    private val protocolExecutor: At2ProtocolExecutor,
    private val tabChannel: TextView,
    private val tabSettings: TextView,
    private val rowDualWatch: LinearLayout,
    private val tvDualWatchValue: TextView,
    private val rowPromptLanguage: LinearLayout,
    private val tvPromptLanguageValue: TextView,
    private val switchPromptTone: SwitchMaterial,
    private val rowVolume: LinearLayout,
    private val tvVolumeValue: TextView,
    private val rowSquelch: LinearLayout,
    private val tvSquelchValue: TextView,
    private val rowTot: LinearLayout,
    private val tvTotValue: TextView,
    private val switchVox: SwitchMaterial,
    private val rowVoxSensitivity: LinearLayout,
    private val tvVoxSensitivityLabel: TextView,
    private val tvVoxSensitivityValue: TextView,
    private val tvVoxSensitivityArrow: TextView,
    private val switchTxInhibit: SwitchMaterial,
    private val rowTxInterval: LinearLayout,
    private val tvTxIntervalValue: TextView,
    private val switchNoiseReduction: SwitchMaterial,
    private val showChoiceDialog: (String, List<String>, Int, (Int) -> Unit) -> Unit,
    private val getState: () -> FeatureSettingsState,
    private val setState: (FeatureSettingsState) -> Unit,
    private val getCurrentCh: () -> Int,
    private val getDualWatchChannelA: () -> Int,
    private val setDualWatchChannelA: (Int) -> Unit,
    private val getDualWatchChannelB: () -> Int,
    private val setDualWatchChannelB: (Int) -> Unit,
    private val setDualWatchFocus: (At2Commands.Side) -> Unit,
    private val onTabsChanged: (TalkStage) -> Unit,
    private val onDualWatchDisabled: (Int) -> Unit,
    private val onRenderHome: () -> Unit,
) {
    fun bind() {
        tabChannel.setOnClickListener {
            onTabsChanged(TalkStage.Main)
        }
        tabSettings.setOnClickListener {
            onTabsChanged(TalkStage.FeatureSettings)
        }
        rowDualWatch.setOnClickListener {
            val state = getState()
            showChoiceDialog(
                tabChannel.context.getString(R.string.feature_dual_watch),
                listOf(tabChannel.context.getString(R.string.state_off), tabChannel.context.getString(R.string.state_on)),
                if (state.dualWatch) 1 else 0
            ) { which ->
                val enabled = which == 1
                scope.launch {
                    protocolExecutor.applyDualWatch(enabled)
                    setState(getState().copy(dualWatch = enabled))
                    if (enabled) {
                        val currentCh = getCurrentCh()
                        setDualWatchChannelA(currentCh)
                        if (getDualWatchChannelB() !in 1..30) {
                            setDualWatchChannelB(currentCh)
                        }
                    } else {
                        setDualWatchFocus(At2Commands.Side.A)
                        onDualWatchDisabled(getDualWatchChannelA())
                    }
                    render(getState())
                    onRenderHome()
                }
            }
        }
        rowPromptLanguage.setOnClickListener {
            val state = getState()
            showChoiceDialog(
                tabChannel.context.getString(R.string.feature_prompt_language),
                listOf(tabChannel.context.getString(R.string.language_chinese), tabChannel.context.getString(R.string.language_english)),
                if (state.promptLanguage == At2Commands.PromptLanguage.English) 1 else 0
            ) { which ->
                val value = if (which == 0) At2Commands.PromptLanguage.Chinese else At2Commands.PromptLanguage.English
                scope.launch {
                    protocolExecutor.applyPromptLanguage(value)
                    setState(getState().copy(promptLanguage = value))
                    render(getState())
                }
            }
        }
        switchPromptTone.setOnCheckedChangeListener { _, isChecked ->
            if (!switchPromptTone.isPressed) return@setOnCheckedChangeListener
            scope.launch {
                protocolExecutor.applyPromptTone(isChecked)
                setState(getState().copy(promptTone = isChecked))
                render(getState())
            }
        }
        rowVolume.setOnClickListener {
            val state = getState()
            val options = (1..8).map { it.toString() }
            showChoiceDialog(tabChannel.context.getString(R.string.feature_volume), options, state.volume - 1) { which ->
                val value = which + 1
                scope.launch {
                    protocolExecutor.applyVolume(value)
                    setState(getState().copy(volume = value))
                    render(getState())
                }
            }
        }
        rowSquelch.setOnClickListener {
            val state = getState()
            val options = (0..9).map { it.toString() }
            showChoiceDialog(tabChannel.context.getString(R.string.feature_squelch), options, state.squelch) { which ->
                scope.launch {
                    protocolExecutor.applySquelch(which)
                    setState(getState().copy(squelch = which))
                    render(getState())
                }
            }
        }
        rowTot.setOnClickListener {
            val state = getState()
            val options = listOf(0, 12, 30, 60, 90, 120, 150, 180, 210, 240)
            val labels = options.map {
                if (it == 0) tabChannel.context.getString(R.string.feature_tot_off)
                else tabChannel.context.getString(R.string.feature_seconds_format, it)
            }
            showChoiceDialog(tabChannel.context.getString(R.string.feature_tot), labels, options.indexOf(state.totSeconds).coerceAtLeast(0)) { which ->
                val value = options[which]
                scope.launch {
                    protocolExecutor.applyTotSeconds(value)
                    setState(getState().copy(totSeconds = value))
                    render(getState())
                }
            }
        }
        switchVox.setOnCheckedChangeListener { _, isChecked ->
            if (!switchVox.isPressed) return@setOnCheckedChangeListener
            scope.launch {
                protocolExecutor.applyVox(isChecked)
                setState(getState().copy(voxEnabled = isChecked))
                render(getState())
            }
        }
        rowVoxSensitivity.setOnClickListener {
            val state = getState()
            if (!state.voxEnabled) return@setOnClickListener
            val options = (1..5).map { it.toString() }
            showChoiceDialog(tabChannel.context.getString(R.string.feature_vox_sensitivity), options, state.voxSensitivity - 1) { which ->
                val value = which + 1
                scope.launch {
                    protocolExecutor.applyVoxSensitivity(value)
                    setState(getState().copy(voxSensitivity = value))
                    render(getState())
                }
            }
        }
        switchTxInhibit.setOnCheckedChangeListener { _, isChecked ->
            if (!switchTxInhibit.isPressed) return@setOnCheckedChangeListener
            scope.launch {
                protocolExecutor.applyTxInhibit(isChecked)
                setState(getState().copy(txInhibit = isChecked))
                render(getState())
            }
        }
        rowTxInterval.setOnClickListener {
            val state = getState()
            val options = listOf(0, 30, 60, 90, 120, 150, 180, 210, 240)
            val labels = options.map { "${it}s" }
            showChoiceDialog(tabChannel.context.getString(R.string.feature_tx_interval), labels, options.indexOf(state.txIntervalSeconds).coerceAtLeast(0)) { which ->
                val value = options[which]
                scope.launch {
                    protocolExecutor.applyTxIntervalSeconds(value)
                    setState(getState().copy(txIntervalSeconds = value))
                    render(getState())
                }
            }
        }
        switchNoiseReduction.setOnCheckedChangeListener { _, isChecked ->
            if (!switchNoiseReduction.isPressed) return@setOnCheckedChangeListener
            scope.launch {
                protocolExecutor.applyNoiseReduction(isChecked)
                setState(getState().copy(noiseReduction = isChecked))
                render(getState())
            }
        }
    }

    fun render(state: FeatureSettingsState) {
        tvDualWatchValue.text = if (state.dualWatch) tabChannel.context.getString(R.string.state_on) else tabChannel.context.getString(R.string.state_off)
        tvPromptLanguageValue.text = if (state.promptLanguage == At2Commands.PromptLanguage.Chinese) {
            tabChannel.context.getString(R.string.language_chinese)
        } else {
            tabChannel.context.getString(R.string.language_english)
        }
        if (switchPromptTone.isChecked != state.promptTone) {
            switchPromptTone.isChecked = state.promptTone
        }
        tvVolumeValue.text = state.volume.toString()
        tvSquelchValue.text = state.squelch.toString()
        tvTotValue.text = if (state.totSeconds == 0) "OFF" else "${state.totSeconds}s"
        if (switchVox.isChecked != state.voxEnabled) {
            switchVox.isChecked = state.voxEnabled
        }
        val voxColor = if (state.voxEnabled) 0xFF111827.toInt() else 0xFF9CA3AF.toInt()
        rowVoxSensitivity.isEnabled = state.voxEnabled
        rowVoxSensitivity.alpha = if (state.voxEnabled) 1.0f else 0.55f
        tvVoxSensitivityLabel.setTextColor(voxColor)
        tvVoxSensitivityValue.setTextColor(voxColor)
        tvVoxSensitivityValue.text = state.voxSensitivity.toString()
        tvVoxSensitivityArrow.setTextColor(if (state.voxEnabled) 0xFFD1D5DB.toInt() else 0xFFE5E7EB.toInt())
        if (switchTxInhibit.isChecked != state.txInhibit) {
            switchTxInhibit.isChecked = state.txInhibit
        }
        tvTxIntervalValue.text = "${state.txIntervalSeconds}s"
        if (switchNoiseReduction.isChecked != state.noiseReduction) {
            switchNoiseReduction.isChecked = state.noiseReduction
        }
    }
}
