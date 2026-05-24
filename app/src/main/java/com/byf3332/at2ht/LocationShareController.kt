package com.byf3332.at2ht

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Toast
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.util.concurrent.Executor
import kotlin.coroutines.resume

class LocationShareController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: SharedPreferences,
    private val requestLocationPermission: () -> Unit,
    private val sendStructuredTextMessage: (rawText: String, kind: ChatMessageKind, title: String, body: String) -> Unit,
    private val buildLoadingDialogView: (title: String) -> View,
    private val styleDialog: (AlertDialog) -> Unit,
) {
    private var pendingLocationShareKind: PendingLocationShareKind? = null
    private var pendingHelpMessage: String? = null
    private var activeLocationShareJob: Job? = null
    private var activeLocationDialog: AlertDialog? = null
    private var activeHelpPresetDialog: Dialog? = null
    private var activeHelpEditorDialog: Dialog? = null

    fun onLocationPermissionResult(granted: Boolean) {
        val kind = pendingLocationShareKind
        val helpMessage = pendingHelpMessage
        pendingLocationShareKind = null
        pendingHelpMessage = null
        if (granted && kind != null) {
            scope.launch {
                sendCurrentLocationMessage(kind, helpMessage)
            }
        }
    }

    fun triggerLocationShare(kind: PendingLocationShareKind) {
        if (kind == PendingLocationShareKind.Help) {
            showHelpPresetDialog()
            return
        }
        startLocationShare(kind, null)
    }

    fun parseSpecialText(text: String): ParsedSpecialText? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val helpParts = trimmed.split(HELP_TEXT_SEPARATOR, limit = 2)
        return if (helpParts.size == 2) {
            val body = buildLocationBody(helpParts[1]) ?: return null
            ParsedSpecialText(
                kind = ChatMessageKind.Help,
                title = helpParts[0].trim(),
                body = body,
            )
        } else {
            val body = buildLocationBody(trimmed) ?: return null
            ParsedSpecialText(
                kind = ChatMessageKind.Location,
                title = context.getString(R.string.location_title),
                body = body,
            )
        }
    }

    fun isLocationServiceEnabled(): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.isLocationEnabled
        } else {
            val gps = runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
            val network = runCatching { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
            gps || network
        }
    }

    private fun startLocationShare(kind: PendingLocationShareKind, helpMessage: String?) {
        if (!hasLocationPermission()) {
            pendingLocationShareKind = kind
            pendingHelpMessage = helpMessage
            requestLocationPermission()
            return
        }
        activeLocationShareJob?.cancel()
        runCatching { activeLocationDialog?.dismiss() }
        activeLocationDialog = null
        activeLocationShareJob = scope.launch {
            val dialog = AlertDialog.Builder(context)
                .setView(buildLoadingDialogView(context.getString(R.string.location_loading)))
                .setNegativeButton(R.string.common_cancel, null)
                .setCancelable(true)
                .create()
            activeLocationDialog = dialog
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
                    activeLocationShareJob?.cancel()
                    dialog.dismiss()
                }
            }
            dialog.setOnCancelListener {
                activeLocationShareJob?.cancel()
            }
            styleDialog(dialog)
            dialog.show()
            try {
                sendCurrentLocationMessage(kind, helpMessage)
            } finally {
                if (activeLocationDialog === dialog) {
                    activeLocationDialog = null
                }
                runCatching { dialog.dismiss() }
            }
        }
    }

    private fun showHelpPresetDialog() {
        runCatching { activeHelpPresetDialog?.dismiss() }
        activeHelpPresetDialog = null
        val presets = loadHelpPresets()
        if (presets.isEmpty()) {
            showHelpEditorDialog()
            return
        }
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(20))
            background = ContextCompat.getDrawable(context, R.drawable.bg_card_white)
            addView(TextView(context).apply {
                text = context.getString(R.string.location_select_help_title)
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFF111111.toInt())
            })
        }
        val listView = ListView(context).apply {
            dividerHeight = 1
            divider = android.graphics.drawable.ColorDrawable(0xFFE5E7EB.toInt())
            adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, presets) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = super.getView(position, convertView, parent) as TextView
                    view.setTextColor(0xFF111111.toInt())
                    view.textSize = 16f
                    view.setPadding(dp(2), dp(20), dp(2), dp(20))
                    return view
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(72) * 3
            ).also { params ->
                params.topMargin = dp(12)
            }
            setOnItemClickListener { _, _, position, _ ->
                val message = presets.getOrNull(position)?.trim().orEmpty()
                if (message.isNotEmpty()) {
                    dialog.dismiss()
                    startLocationShare(PendingLocationShareKind.Help, message)
                }
            }
        }
        root.addView(listView)
        root.addView(TextView(context).apply {
            text = context.getString(R.string.common_edit)
            gravity = android.view.Gravity.CENTER
            textSize = 16f
            setTextColor(0xFF111111.toInt())
            background = ContextCompat.getDrawable(context, R.drawable.bg_round_outline_gray)
            backgroundTintList = ColorStateList.valueOf(0xFFF3F4F6.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(108), dp(40)).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                topMargin = dp(16)
            }
            setOnClickListener {
                dialog.dismiss()
                showHelpEditorDialog()
            }
        })
        dialog.setContentView(root)
        dialog.setCancelable(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.45f }
        }
        dialog.setOnDismissListener {
            if (activeHelpPresetDialog === dialog) activeHelpPresetDialog = null
        }
        activeHelpPresetDialog = dialog
        dialog.show()
    }

    private fun showHelpEditorDialog() {
        runCatching { activeHelpEditorDialog?.dismiss() }
        activeHelpEditorDialog = null
        val existing = loadHelpPresets()
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(20))
            background = ContextCompat.getDrawable(context, R.drawable.bg_card_white)
        }
        root.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = context.getString(R.string.location_edit_help_title)
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFF111111.toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(context).apply {
                text = context.getString(R.string.common_complete)
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFF2563EB.toInt())
                setOnClickListener {
                    val values = editFields.map { it.text?.toString()?.trim().orEmpty() }
                        .filter { it.isNotEmpty() }
                        .take(MAX_HELP_PRESETS)
                    saveHelpPresets(values)
                    dialog.dismiss()
                    if (values.isNotEmpty()) {
                        showHelpPresetDialog()
                    }
                }
            })
        })
        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val fields = mutableListOf<EditText>()
        repeat(MAX_HELP_PRESETS) { index ->
            content.addView(EditText(context).apply {
                setText(existing.getOrNull(index).orEmpty())
                setTextColor(0xFF111111.toInt())
                setHintTextColor(0xFF9CA3AF.toInt())
                hint = context.getString(R.string.location_help_input_hint)
                background = null
                textSize = 16f
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 1
                maxLines = 4
                filters = arrayOf(utf8ByteLimitFilter(MAX_HELP_MESSAGE_UTF8_BYTES))
                setPadding(0, dp(18), 0, dp(18))
            }.also { editText ->
                fields += editText
            })
            if (index != MAX_HELP_PRESETS - 1) {
                content.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(1)
                    )
                    setBackgroundColor(0xFFE5E7EB.toInt())
                })
            }
        }
        editFields = fields
        scrollView.addView(content)
        root.addView(scrollView)
        dialog.setContentView(root)
        dialog.setCancelable(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.45f }
        }
        dialog.setOnDismissListener {
            if (activeHelpEditorDialog === dialog) activeHelpEditorDialog = null
            editFields = emptyList()
        }
        activeHelpEditorDialog = dialog
        dialog.show()
    }

    private var editFields: List<EditText> = emptyList()

    private suspend fun sendCurrentLocationMessage(kind: PendingLocationShareKind, helpMessage: String?) {
        if (!isLocationServiceEnabled()) {
            Toast.makeText(context, R.string.location_service_required, Toast.LENGTH_SHORT).show()
            return
        }
        val location = getCurrentLocation()
        if (location == null) {
            Toast.makeText(context, R.string.location_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val latitudeText = formatCoordinateValue(location.latitude)
        val longitudeText = formatCoordinateValue(location.longitude)
        val rawText = when (kind) {
            PendingLocationShareKind.Location ->
                latitudeText + LOCATION_COORD_SEPARATOR + longitudeText
            PendingLocationShareKind.Help ->
                (helpMessage?.trim().orEmpty()) + HELP_TEXT_SEPARATOR + latitudeText + LOCATION_COORD_SEPARATOR + longitudeText
        }
        val parsed = parseSpecialText(rawText)
        if (parsed == null) {
            Toast.makeText(context, R.string.location_message_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        sendStructuredTextMessage(rawText, parsed.kind, parsed.title, parsed.body)
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private suspend fun getCurrentLocation(): Location? {
        if (!hasLocationPermission()) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = runCatching { manager.getProviders(true) }.getOrDefault(emptyList())
        val providerOrder = buildList {
            if (providers.contains(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
            if (providers.contains(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
            providers.forEach { provider ->
                if (!contains(provider)) add(provider)
            }
        }
        if (providerOrder.isEmpty()) return null
        providerOrder.forEach { provider ->
            val location = requestCurrentLocationFromProvider(manager, provider)
            if (location != null) return location
        }
        return null
    }

    private suspend fun requestCurrentLocationFromProvider(
        manager: LocationManager,
        provider: String,
    ): Location? {
        return withTimeoutOrNull(LOCATION_REQUEST_TIMEOUT_MS) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                suspendCancellableCoroutine { continuation ->
                    val signal = CancellationSignal()
                    continuation.invokeOnCancellation { signal.cancel() }
                    val executor = Executor { runnable -> runnable.run() }
                    runCatching {
                        manager.getCurrentLocation(provider, signal, executor) { location ->
                            if (continuation.isActive) continuation.resume(location)
                        }
                    }.onFailure {
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            } else {
                suspendCancellableCoroutine { continuation ->
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            manager.removeUpdates(this)
                            if (continuation.isActive) continuation.resume(location)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit

                        override fun onProviderEnabled(provider: String) = Unit

                        override fun onProviderDisabled(provider: String) = Unit
                    }
                    continuation.invokeOnCancellation {
                        runCatching { manager.removeUpdates(listener) }
                    }
                    runCatching {
                        @Suppress("DEPRECATION")
                        manager.requestSingleUpdate(provider, listener, null)
                    }.onFailure {
                        runCatching { manager.removeUpdates(listener) }
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            }
        }
    }

    private fun formatCoordinateValue(value: Double): String =
        String.format(java.util.Locale.US, "%.6f", value).trimEnd('0').trimEnd('.')

    private fun utf8ByteLimitFilter(maxBytes: Int): InputFilter =
        InputFilter { source, start, end, dest, dstart, dend ->
            val replacement = source?.subSequence(start, end)?.toString().orEmpty()
            if (replacement.isEmpty()) return@InputFilter null
            val candidate = StringBuilder(dest.toString())
                .replace(dstart, dend, replacement)
                .toString()
            if (candidate.toByteArray(Charsets.UTF_8).size <= maxBytes) return@InputFilter null
            val accepted = StringBuilder()
            replacement.forEach { ch ->
                val next = StringBuilder(dest.toString())
                    .replace(dstart, dend, accepted.toString() + ch)
                    .toString()
                if (next.toByteArray(Charsets.UTF_8).size > maxBytes) return@forEach
                accepted.append(ch)
            }
            accepted.toString()
        }

    private fun loadHelpPresets(): List<String> {
        val stored = prefs.getString(PREF_HELP_PRESETS, null)
        if (stored.isNullOrBlank()) return DEFAULT_HELP_PRESETS
        return runCatching {
            val array = JSONArray(stored)
            buildList {
                repeat(array.length()) { index ->
                    val value = array.optString(index).trim()
                    if (value.isNotEmpty()) add(value)
                }
            }.take(MAX_HELP_PRESETS)
        }.getOrElse { DEFAULT_HELP_PRESETS }
    }

    private fun saveHelpPresets(items: List<String>) {
        val array = JSONArray()
        items.take(MAX_HELP_PRESETS).forEach { value ->
            val trimmed = value.trim()
            if (trimmed.isNotEmpty()) {
                array.put(trimmed)
            }
        }
        prefs.edit().putString(PREF_HELP_PRESETS, array.toString()).apply()
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun buildLocationBody(text: String): String? {
        val parts = text.split(LOCATION_COORD_SEPARATOR, limit = 2)
        if (parts.size != 2) return null
        val lat = parts[0].trim()
        val lon = parts[1].trim()
        if (lat.isEmpty() || lon.isEmpty()) return null
        return "${formatCoordinateLabel(lat, true)}，${formatCoordinateLabel(lon, false)}"
    }

    private fun formatCoordinateLabel(raw: String, latitude: Boolean): String {
        val negative = raw.startsWith("-")
        val value = raw.removePrefix("-")
        return if (latitude) {
            if (negative) context.getString(R.string.location_lat_south, value) else context.getString(R.string.location_lat_north, value)
        } else {
            if (negative) context.getString(R.string.location_lon_west, value) else context.getString(R.string.location_lon_east, value)
        }
    }

    companion object {
        private const val LOCATION_REQUEST_TIMEOUT_MS = 12_000L
        private const val LOCATION_COORD_SEPARATOR = "*-#-*-#-*-#-*"
        private const val HELP_TEXT_SEPARATOR = "#-*-#-*-#-*-#"
        private const val PREF_HELP_PRESETS = "help_presets"
        private const val MAX_HELP_PRESETS = 10
        private const val MAX_HELP_MESSAGE_UTF8_BYTES = 800
        private val DEFAULT_HELP_PRESETS = listOf(
            "Lost, need directions",
            "Injured, urgently need medicine",
            "Trapped in heavy rain, need rescue",
            "Hypothermia, urgently need warm supplies",
            "No signal, need location information",
            "It’s dark, can’t find the campsite",
            "Lack of water, where is the nearest water source?",
            "Car stuck, need help getting out",
        )
    }
}
