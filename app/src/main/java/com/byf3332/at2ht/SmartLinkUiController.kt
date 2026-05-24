package com.byf3332.at2ht

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleCoroutineScope
import com.byf3332.at2ht.core.ble.BleSessionState
import com.byf3332.at2ht.core.protocol.At2Commands
import com.byf3332.at2ht.core.protocol.At2ProtocolExecutor
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SmartLinkUiController(
    private val context: Context,
    private val scope: LifecycleCoroutineScope,
    private val protocolExecutor: At2ProtocolExecutor,
    private val getBleState: () -> BleSessionState,
    private val switchSmartLinkMode: SwitchMaterial,
    private val tvSmartLinkStatus: TextView,
    private val btnSmartLinkCompatibility: TextView,
    private val rowSmartLinkPttSetting: LinearLayout,
    private val tvSmartLinkPttSettingValue: TextView,
    private val getSmartLinkEnabled: () -> Boolean,
    private val setSmartLinkEnabled: (Boolean) -> Unit,
    private val getMainPttTarget: () -> At2Commands.MainPttTarget?,
    private val setMainPttTarget: (At2Commands.MainPttTarget?) -> Unit,
) {
    fun bind() {
        switchSmartLinkMode.setOnCheckedChangeListener { _, isChecked ->
            if (!switchSmartLinkMode.isPressed) return@setOnCheckedChangeListener
            scope.launch {
                val applied = protocolExecutor.applySmartLink(isChecked)
                if (!applied) {
                    Toast.makeText(context, R.string.smart_link_switch_timeout, Toast.LENGTH_SHORT).show()
                    refreshState()
                    return@launch
                }
                setSmartLinkEnabled(isChecked)
                render()
                refreshState()
            }
        }
        btnSmartLinkCompatibility.setOnClickListener {
            showCompatibilityDialog()
        }
        rowSmartLinkPttSetting.setOnClickListener {
            showPttSettingDialog()
        }
        render()
    }

    fun render() {
        val ready = getBleState() == BleSessionState.Ready
        switchSmartLinkMode.isEnabled = ready
        rowSmartLinkPttSetting.isEnabled = ready
        rowSmartLinkPttSetting.alpha = if (ready) 1.0f else 0.55f
        if (switchSmartLinkMode.isChecked != getSmartLinkEnabled()) {
            switchSmartLinkMode.isChecked = getSmartLinkEnabled()
        }
        tvSmartLinkStatus.text = when {
            !ready -> context.getString(R.string.smart_link_current_disconnected)
            getSmartLinkEnabled() -> context.getString(R.string.smart_link_current_enabled)
            else -> context.getString(R.string.smart_link_current_disabled)
        }
        tvSmartLinkPttSettingValue.text = getMainPttTarget()?.let(::smartLinkPttTargetLabel) ?: context.getString(R.string.smart_link_reading)
    }

    suspend fun refreshState() {
        if (getBleState() != BleSessionState.Ready) return
        protocolExecutor.querySmartLink()
        delay(120)
        protocolExecutor.querySmartLinkMainPttTarget()
    }

    fun showCompatibilityDialog() {
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.smart_link_compatibility_title)
            .setMessage(R.string.smart_link_compatibility_body)
            .setPositiveButton(R.string.common_close, null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(R.drawable.bg_card_white)
            dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(Color.BLACK)
            dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.setTextColor(Color.BLACK)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.BLACK)
        }
        dialog.show()
    }

    fun showPttSettingDialog() {
        if (getBleState() != BleSessionState.Ready) {
            Toast.makeText(context, R.string.offline_not_connected, Toast.LENGTH_SHORT).show()
            return
        }
        val targets = listOf(
            At2Commands.MainPttTarget.Zello,
            At2Commands.MainPttTarget.Other1,
            At2Commands.MainPttTarget.Other2,
            At2Commands.MainPttTarget.Other3,
            At2Commands.MainPttTarget.Other4,
            At2Commands.MainPttTarget.Olaradio,
        )
        val labels = targets.map(::smartLinkPttTargetLabel)
        val dialog = BottomSheetDialog(context)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(20))
            setBackgroundColor(Color.WHITE)
        }
        val titleView = TextView(context).apply {
            text = context.getString(R.string.ptt_settings)
            setTextColor(Color.parseColor("#111827"))
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
        }
        val checkedIndex = getMainPttTarget()?.let { targets.indexOf(it) } ?: -1
        var selectedIndex = checkedIndex
        val choiceAdapter = object : ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundColor(Color.WHITE)
                    setPadding(dp(20), dp(16), dp(20), dp(16))
                }
                val radio = RadioButton(context).apply {
                    isChecked = position == selectedIndex
                    buttonTintList = ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(Color.BLACK, Color.BLACK)
                    )
                    isClickable = false
                    isFocusable = false
                }
                val label = TextView(context).apply {
                    text = labels[position]
                    setTextColor(Color.BLACK)
                    textSize = 18f
                    setPadding(dp(12), 0, 0, 0)
                }
                row.addView(radio)
                row.addView(label)
                return row
            }
        }
        val listView = ListView(context).apply {
            choiceMode = ListView.CHOICE_MODE_SINGLE
            dividerHeight = 0
            setBackgroundColor(Color.WHITE)
            adapter = choiceAdapter
        }
        if (checkedIndex >= 0) {
            listView.setItemChecked(checkedIndex, true)
        }
        listView.setOnItemClickListener { _, _, position, _ ->
            selectedIndex = position
            choiceAdapter.notifyDataSetChanged()
            val target = targets[position]
            scope.launch {
                val applied = protocolExecutor.applySmartLinkMainPttTarget(target)
                if (!applied) {
                    Toast.makeText(context, R.string.smart_link_ptt_setting_timeout, Toast.LENGTH_SHORT).show()
                    refreshState()
                    return@launch
                }
                setMainPttTarget(target)
                render()
                dialog.dismiss()
                refreshState()
            }
        }
        container.addView(titleView)
        container.addView(
            listView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            }
        )
        dialog.setContentView(container)
        dialog.show()
    }

    private fun smartLinkPttTargetLabel(target: At2Commands.MainPttTarget): String = when (target) {
        At2Commands.MainPttTarget.Zello -> context.getString(R.string.smart_link_zello)
        At2Commands.MainPttTarget.Other1 -> context.getString(R.string.smart_link_other1)
        At2Commands.MainPttTarget.Other2 -> context.getString(R.string.smart_link_other2)
        At2Commands.MainPttTarget.Other3 -> context.getString(R.string.smart_link_other3)
        At2Commands.MainPttTarget.Other4 -> context.getString(R.string.smart_link_other4)
        At2Commands.MainPttTarget.Olaradio -> "Ola Radio"
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
