package com.byf3332.at2ht

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.InsetDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import android.content.res.ColorStateList

class DialogUiController(
    private val context: Context,
    private val dp: (Int) -> Int,
) {
    fun buildLoadingDialogView(title: String): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(TextView(context).apply {
                text = title
                textSize = 20f
                setTextColor(0xFF111827.toInt())
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(ProgressBar(context).apply {
                indeterminateTintList = ColorStateList.valueOf(0xFF1D4ED8.toInt())
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = dp(16)
                lp.gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = lp
            })
        }

    fun buildDialogListAdapter(items: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                (view as? TextView)?.apply {
                    setTextColor(0xFF111827.toInt())
                    textSize = 16f
                    setPadding(dp(24), dp(16), dp(24), dp(16))
                }
                return view
            }
        }

    fun styleDialog(dialog: AlertDialog) {
        dialog.setOnShowListener {
            val base = ContextCompat.getDrawable(context, R.drawable.bg_card_white)
            if (base != null) {
                dialog.window?.setBackgroundDrawable(
                    InsetDrawable(base, dp(20), dp(12), dp(20), dp(20))
                )
            }
            dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.setTextColor(0xFF111827.toInt())
            dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(0xFF374151.toInt())
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(0xFF111827.toInt())
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(0xFF111827.toInt())
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(0xFF111827.toInt())
        }
    }

    fun showChoiceDialog(
        title: String,
        options: List<String>,
        checkedIndex: Int,
        onChosen: (Int) -> Unit,
    ) {
        var selectedIndex = checkedIndex
        val choiceAdapter = object : ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, options) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(24), dp(16), dp(24), dp(16))
                }
                val radio = RadioButton(context).apply {
                    isChecked = position == selectedIndex
                    buttonTintList = ColorStateList(
                        arrayOf(
                            intArrayOf(android.R.attr.state_checked),
                            intArrayOf()
                        ),
                        intArrayOf(
                            0xFF2563EB.toInt(),
                            0xFF4B5563.toInt()
                        )
                    )
                    isClickable = false
                    isFocusable = false
                }
                val label = TextView(context).apply {
                    text = options[position]
                    setTextColor(0xFF111827.toInt())
                    textSize = 16f
                    setPadding(dp(12), 0, 0, 0)
                }
                row.addView(radio)
                row.addView(label)
                return row
            }
        }
        val listView = ListView(context).apply {
            divider = null
            adapter = choiceAdapter
            setOnItemClickListener { _, _, position, _ ->
                selectedIndex = position
                choiceAdapter.notifyDataSetChanged()
            }
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(listView)
            .setPositiveButton(R.string.common_confirm) { _, _ -> onChosen(selectedIndex) }
            .setNegativeButton(R.string.common_cancel, null)
            .create()
        styleDialog(dialog)
        dialog.show()
    }
}
