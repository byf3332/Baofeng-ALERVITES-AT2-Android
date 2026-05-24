package com.byf3332.at2ht

import android.Manifest
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.isVisible
import kotlinx.coroutines.launch
import com.byf3332.at2ht.core.ble.BleSessionState
import com.byf3332.at2ht.core.protocol.ChannelConfig

class ChatUiController(
    private val scope: LifecycleCoroutineScope,
    private val rvMessages: RecyclerView,
    private val etDraft: EditText,
    private val btnOpenPtt: TextView,
    private val btnToggleOptions: TextView,
    private val chatOptionsRow: LinearLayout,
    private val actionHelp: LinearLayout,
    private val actionLocation: LinearLayout,
    private val actionImage: LinearLayout,
    private val actionVoice: LinearLayout,
    private val tvChatBanner: TextView,
    private val getBleState: () -> BleSessionState,
    private val channels: List<ChannelConfig>,
    private val getCurrentCh: () -> Int,
    private val getChatMenuExpanded: () -> Boolean,
    private val setChatMenuExpanded: (Boolean) -> Unit,
    private val hasRecordAudioPermission: () -> Boolean,
    private val requestVoicePermissionLauncher: ActivityResultLauncher<String>,
    private val setPendingVoicePermissionAction: (((() -> Unit)?) -> Unit),
    private val hasDraftContent: () -> Boolean,
    private val fitsUtf8ByteLimit: (String, Int) -> Boolean,
    private val maxOfflineTextUtf8Bytes: Int,
    private val loadOlderChatMessages: () -> Unit,
    private val hasOlderChatMessages: () -> Boolean,
    private val isLoadingOlderChatMessages: () -> Boolean,
    private val sendDraftMessage: () -> Unit,
    private val triggerLocationShare: (PendingLocationShareKind) -> Unit,
    private val showImageSourceDialog: () -> Unit,
    private val showVoiceRecordDialog: () -> Unit,
    private val hideKeyboard: () -> Unit,
    private val enterPtt: suspend () -> Unit,
    private val renderAll: () -> Unit,
    private val renderChatComposer: () -> Unit,
    private val isVoicePlaying: (Long) -> Boolean,
    private val isVoicePlayingAlternate: (Long) -> Boolean,
    private val onVoiceClick: (OfflineChatMessage) -> Unit,
    private val onImageClick: (OfflineChatMessage) -> Unit,
) {
    lateinit var chatAdapter: OfflineChatAdapter
        private set

    fun bind() {
        chatAdapter = OfflineChatAdapter(
            onVoiceClick = onVoiceClick,
            onImageClick = onImageClick,
            isVoicePlaying = isVoicePlaying,
            isVoicePlayingAlternate = isVoicePlayingAlternate,
        )
        rvMessages.layoutManager = LinearLayoutManager(rvMessages.context).apply {
            stackFromEnd = true
            isItemPrefetchEnabled = false
            initialPrefetchItemCount = 0
        }
        rvMessages.adapter = chatAdapter
        rvMessages.setItemViewCacheSize(0)
        rvMessages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy >= 0 || !hasOlderChatMessages() || isLoadingOlderChatMessages()) return
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                if (layoutManager.findFirstVisibleItemPosition() <= 8) {
                    loadOlderChatMessages()
                }
            }
        })
        etDraft.filters = arrayOf(InputFilter { source, start, end, dest, dstart, dend ->
            val replacement = source?.subSequence(start, end)?.toString().orEmpty()
            if (replacement.isEmpty()) return@InputFilter null
            val candidate = StringBuilder(dest.toString())
                .replace(dstart, dend, replacement)
                .toString()
            if (fitsUtf8ByteLimit(candidate, maxOfflineTextUtf8Bytes)) return@InputFilter null
            var accepted = ""
            replacement.forEach { ch ->
                val next = StringBuilder(dest.toString())
                    .replace(dstart, dend, accepted + ch)
                    .toString()
                if (!fitsUtf8ByteLimit(next, maxOfflineTextUtf8Bytes)) return@forEach
                accepted += ch
            }
            accepted
        })
        etDraft.setOnEditorActionListener { _, actionId, event ->
            val imeSend = actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE
            val enterDown = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            val handled = imeSend || enterDown
            if (handled) sendDraftMessage()
            handled
        }
        etDraft.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (hasDraftContent()) setChatMenuExpanded(false)
                renderChatComposer()
            }
        })
        btnOpenPtt.setOnClickListener {
            val channel = channels.getOrNull(getCurrentCh() - 1) ?: ChannelConfig()
            when {
                getBleState() != BleSessionState.Ready -> {
                    Toast.makeText(rvMessages.context, R.string.offline_not_connected, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                channel.rxMhz == null || channel.txMhz == null -> {
                    Toast.makeText(rvMessages.context, R.string.offline_channel_unavailable, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }
            val action = {
                hideKeyboard()
                scope.launch {
                    enterPtt()
                    renderAll()
                }
                Unit
            }
            if (hasRecordAudioPermission()) {
                action()
            } else {
                Toast.makeText(rvMessages.context, R.string.chat_record_permission_required, Toast.LENGTH_SHORT).show()
                setPendingVoicePermissionAction(action)
                requestVoicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        btnToggleOptions.setOnClickListener {
            if (hasDraftContent()) {
                sendDraftMessage()
            } else {
                setChatMenuExpanded(!getChatMenuExpanded())
                renderChatComposer()
            }
        }
        actionHelp.setOnClickListener {
            setChatMenuExpanded(false)
            renderChatComposer()
            triggerLocationShare(PendingLocationShareKind.Help)
        }
        actionLocation.setOnClickListener {
            setChatMenuExpanded(false)
            renderChatComposer()
            triggerLocationShare(PendingLocationShareKind.Location)
        }
        actionImage.setOnClickListener {
            setChatMenuExpanded(false)
            renderChatComposer()
            showImageSourceDialog()
        }
        actionVoice.setOnClickListener {
            setChatMenuExpanded(false)
            renderChatComposer()
            showVoiceRecordDialog()
        }
    }

    fun renderChatBanner() {
        val channel = channels.getOrNull(getCurrentCh() - 1) ?: ChannelConfig()
        val (text, color) = when {
            channel.rxMhz == null || channel.txMhz == null -> rvMessages.context.getString(R.string.chat_banner_channel_invalid) to 0xFFDC2626.toInt()
            !channel.modeDigital -> rvMessages.context.getString(R.string.chat_banner_analog_unsupported) to 0xFFDC2626.toInt()
            else -> rvMessages.context.getString(R.string.chat_banner_ready) to 0xFF047857.toInt()
        }
        tvChatBanner.text = text
        tvChatBanner.setTextColor(color)
    }

    fun renderChatComposer() {
        val channel = channels.getOrNull(getCurrentCh() - 1) ?: ChannelConfig()
        val ready = getBleState() == BleSessionState.Ready &&
            channel.rxMhz != null &&
            channel.txMhz != null &&
            channel.modeDigital
        val hasDraft = hasDraftContent()
        if (!ready || hasDraft) setChatMenuExpanded(false)
        chatOptionsRow.isVisible = ready && getChatMenuExpanded() && !hasDraft
        btnToggleOptions.text = if (hasDraft) rvMessages.context.getString(R.string.chat_menu_send) else if (getChatMenuExpanded()) "×" else "+"
        btnToggleOptions.textSize = if (hasDraft) 16f else 28f
        btnOpenPtt.alpha = if (ready) 1f else 0.4f
        btnOpenPtt.isEnabled = ready
        btnToggleOptions.alpha = if (ready) 1f else 0.45f
        btnToggleOptions.isEnabled = ready
        etDraft.isEnabled = ready
        etDraft.alpha = if (ready) 1f else 0.65f
        etDraft.hint = when {
            channel.rxMhz == null || channel.txMhz == null -> rvMessages.context.getString(R.string.chat_hint_unsupported_channel)
            !channel.modeDigital -> rvMessages.context.getString(R.string.chat_hint_analog_unsupported)
            else -> rvMessages.context.getString(R.string.chat_hint_default)
        }
    }
}
