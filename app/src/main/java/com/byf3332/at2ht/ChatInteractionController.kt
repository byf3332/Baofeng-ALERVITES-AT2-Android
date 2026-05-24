package com.byf3332.at2ht

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleCoroutineScope
import com.byf3332.at2ht.core.ble.BleSessionState
import com.byf3332.at2ht.core.chat.ChatVoicePlayer
import com.byf3332.at2ht.core.chat.OfflineImagePayload
import com.byf3332.at2ht.core.chat.OfflineImageProcessor
import com.byf3332.at2ht.core.chat.OfflineVoiceRecorder
import com.byf3332.at2ht.core.chat.OfflineVoiceRecording
import com.byf3332.at2ht.core.protocol.At2OfflineMessageCodec
import com.byf3332.at2ht.core.protocol.At2ProtocolExecutor
import com.byf3332.at2ht.core.protocol.ChannelConfig
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.util.Base64

class ChatInteractionController(
    private val context: Context,
    private val scope: LifecycleCoroutineScope,
    private val prefs: android.content.SharedPreferences,
    private val contentResolver: ContentResolver,
    private val packageNameProvider: () -> String,
    private val cacheDirProvider: () -> File,
    private val protocolExecutor: At2ProtocolExecutor,
    private val voiceRecorder: OfflineVoiceRecorder,
    private val voicePlayer: ChatVoicePlayer,
    private val getBleState: () -> BleSessionState,
    private val getTalkStage: () -> TalkStage,
    private val getAppSection: () -> AppSection,
    private val getCurrentChannel: () -> ChannelConfig,
    private val appendChatMessage: (OfflineChatMessage) -> Unit,
    private val replaceChatMessage: (Long, OfflineChatMessage, Boolean) -> Unit,
    private val updateChatMessageProgress: (Long, Int) -> Unit,
    private val removeChatMessage: (Long) -> Unit,
    private val renderChat: () -> Unit,
    private val nextChatLocalId: () -> Long,
    private val requestVoicePermission: () -> Unit,
    private val hasRecordAudioPermission: () -> Boolean,
    private val launchImagePicker: () -> Unit,
    private val launchCameraCapture: (Uri) -> Unit,
    private val styleDialog: (AlertDialog) -> Unit,
    private val dp: (Int) -> Int,
) {
    private var currentPlayingVoiceMessageId: Long? = null
    private var currentPlayingVoiceAlternate = false
    private var currentPlayingVoicePulseJob: Job? = null
    private var voiceRecordDialog: Dialog? = null
    private var pendingRecordAudioAction: (() -> Unit)? = null
    private var pendingCameraImageUri: Uri? = null
    private var outgoingTextSendJob: Job? = null
    private var outgoingVoiceSendJob: Job? = null
    private var outgoingImageSendJob: Job? = null
    private var chatUserName = At2OfflineMessageCodec.DEFAULT_USERNAME
    private var offlineMessageCounter = 1u

    fun loadPreferences() {
        chatUserName = prefs.getString(PREF_CHAT_USERNAME, At2OfflineMessageCodec.DEFAULT_USERNAME)
            ?.trim()
            ?.ifBlank { At2OfflineMessageCodec.DEFAULT_USERNAME }
            ?: At2OfflineMessageCodec.DEFAULT_USERNAME
    }

    fun loadOfflineMessageIdState() {
        val stored = prefs.getInt(PREF_OFFLINE_MESSAGE_COUNTER, 1)
        offlineMessageCounter = (stored.toUInt() and 0xFFu).let { if (it == 0u) 1u else it }
    }

    fun onVoicePermissionResult(granted: Boolean) {
        val action = pendingRecordAudioAction
        pendingRecordAudioAction = null
        if (granted) action?.invoke()
    }

    fun onVoicePlayerStateChanged(messageId: Long, playing: Boolean) {
        if (playing) {
            currentPlayingVoiceMessageId = messageId
            startVoicePlaybackPulse()
        } else {
            stopVoicePlaybackPulse()
            if (currentPlayingVoiceMessageId == messageId) {
                currentPlayingVoiceMessageId = null
            }
            renderChat()
        }
    }

    fun isVoicePlaying(id: Long): Boolean = currentPlayingVoiceMessageId == id

    fun isVoicePlayingAlternate(id: Long): Boolean =
        currentPlayingVoiceMessageId == id && currentPlayingVoiceAlternate

    fun hasActiveVoiceDialog(): Boolean = voiceRecordDialog?.isShowing == true

    fun hasDraftContent(text: String?): Boolean = text?.trim()?.isNotEmpty() == true

    fun cancelOutgoingTransfers() {
        outgoingTextSendJob?.cancel()
        outgoingTextSendJob = null
        outgoingVoiceSendJob?.cancel()
        outgoingVoiceSendJob = null
        outgoingImageSendJob?.cancel()
        outgoingImageSendJob = null
    }

    fun stopVoicePlayback() {
        voicePlayer.stop()
        stopVoicePlaybackPulse()
        currentPlayingVoiceMessageId = null
    }

    fun sendDraftMessage(content: String, clearDraft: () -> Unit, restoreDraft: () -> Unit) {
        val channel = getCurrentChannel()
        when {
            getBleState() != BleSessionState.Ready -> {
                Toast.makeText(context, R.string.offline_not_connected, Toast.LENGTH_SHORT).show()
                return
            }
            channel.rxMhz == null || channel.txMhz == null -> {
                Toast.makeText(context, R.string.offline_channel_unavailable, Toast.LENGTH_SHORT).show()
                return
            }
            !channel.modeDigital -> {
                Toast.makeText(context, R.string.offline_analog_unsupported, Toast.LENGTH_SHORT).show()
                return
            }
        }
        if (content.isEmpty()) return
        val msgId = allocateOfflineMessageId(chatUserName)
        val rowId = nextChatLocalId()
        val pendingMessage = OfflineChatMessage(
            id = rowId,
            protocolId = msgId.toLong(),
            self = true,
            sender = chatUserName,
            kind = ChatMessageKind.Text,
            title = content,
            pending = true,
            pendingProgress = 0,
        )
        appendChatMessage(pendingMessage)
        clearDraft()
        outgoingTextSendJob?.cancel()
        outgoingTextSendJob = scope.launch {
            runCatching {
                protocolExecutor.sendOfflineText(msgId, chatUserName, content) { sentFrames, totalFrames ->
                    val percent = ((sentFrames * 100f) / totalFrames.coerceAtLeast(1)).toInt().coerceIn(0, 100)
                    updateChatMessageProgress(rowId, percent)
                }
            }.onSuccess {
                outgoingTextSendJob = null
                replaceChatMessage(rowId, pendingMessage.copy(pending = false, pendingProgress = 100), false)
            }.onFailure { error ->
                outgoingTextSendJob = null
                removeChatMessage(rowId)
                if (error !is kotlinx.coroutines.CancellationException) {
                    restoreDraft()
                    Toast.makeText(context, R.string.chat_send_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun sendStructuredTextMessage(rawText: String, kind: ChatMessageKind, title: String, body: String = "") {
        val channel = getCurrentChannel()
        when {
            getBleState() != BleSessionState.Ready -> {
                Toast.makeText(context, R.string.offline_not_connected, Toast.LENGTH_SHORT).show()
                return
            }
            channel.rxMhz == null || channel.txMhz == null -> {
                Toast.makeText(context, R.string.offline_channel_unavailable, Toast.LENGTH_SHORT).show()
                return
            }
            !channel.modeDigital -> {
                Toast.makeText(context, R.string.offline_analog_unsupported, Toast.LENGTH_SHORT).show()
                return
            }
        }
        if (rawText.toByteArray(Charsets.UTF_8).size > MAX_OFFLINE_TEXT_UTF8_BYTES) {
            Toast.makeText(context, R.string.chat_send_too_long, Toast.LENGTH_SHORT).show()
            return
        }
        val msgId = allocateOfflineMessageId(chatUserName)
        val rowId = nextChatLocalId()
        val pendingMessage = OfflineChatMessage(
            id = rowId,
            protocolId = msgId.toLong(),
            self = true,
            sender = chatUserName,
            kind = kind,
            title = title,
            body = body,
            pending = true,
            pendingProgress = 0,
        )
        appendChatMessage(pendingMessage)
        outgoingTextSendJob?.cancel()
        outgoingTextSendJob = scope.launch {
            runCatching {
                protocolExecutor.sendOfflineText(msgId, chatUserName, rawText) { sentFrames, totalFrames ->
                    val percent = ((sentFrames * 100f) / totalFrames.coerceAtLeast(1)).toInt().coerceIn(0, 100)
                    updateChatMessageProgress(rowId, percent)
                }
            }.onSuccess {
                outgoingTextSendJob = null
                replaceChatMessage(rowId, pendingMessage.copy(pending = false, pendingProgress = 100), false)
            }.onFailure { error ->
                outgoingTextSendJob = null
                removeChatMessage(rowId)
                if (error !is kotlinx.coroutines.CancellationException) {
                    Toast.makeText(context, R.string.chat_send_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun showImageSourceDialog() {
        val dialog = BottomSheetDialog(context)
        dialog.setContentView(R.layout.dialog_image_source)
        dialog.findViewById<TextView>(R.id.actionPickImage)?.setOnClickListener {
            dialog.dismiss()
            launchImagePicker()
        }
        dialog.findViewById<TextView>(R.id.actionCaptureImage)?.setOnClickListener {
            dialog.dismiss()
            launchCameraCapture()
        }
        dialog.findViewById<TextView>(R.id.actionCancelImageSource)?.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    fun launchCameraCapture() {
        val photoFile = File(cacheDirProvider(), "images/capture_${System.currentTimeMillis()}.jpg").apply {
            parentFile?.mkdirs()
        }
        val authority = "${packageNameProvider()}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, photoFile)
        pendingCameraImageUri = uri
        launchCameraCapture(uri)
    }

    fun consumePendingCameraImageUri(): Uri? {
        val uri = pendingCameraImageUri
        pendingCameraImageUri = null
        return uri
    }

    fun processSelectedImage(uri: Uri, deleteAfter: Boolean = false) {
        scope.launch {
            if (getTalkStage() != TalkStage.Chat || getAppSection() != AppSection.Offline) {
                return@launch
            }
            AppLog.d("AT2HT-OFFLINE", "image pick uri=$uri deleteAfter=$deleteAfter")
            val result = withContext(Dispatchers.IO) {
                runCatching { OfflineImageProcessor.prepare(contentResolver, uri) }
            }
            if (deleteAfter) {
                runCatching {
                    if (uri.scheme == "file") File(uri.path ?: "").delete()
                    else contentResolver.delete(uri, null, null)
                }
            }
            val payload = result.getOrElse { error ->
                AppLog.e("AT2HT-OFFLINE", "image preprocess failed uri=$uri", error)
                null
            }
            if (payload == null) {
                AppLog.d("AT2HT-OFFLINE", "image preprocess returned null uri=$uri")
                Toast.makeText(context, R.string.chat_select_image_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            AppLog.d(
                "AT2HT-OFFLINE",
                "image prepared uri=$uri jpegBytes=${payload.jpegBytes.size} size=${payload.originalWidth}x${payload.originalHeight}"
            )
            sendPreparedImage(payload)
        }
    }

    fun sendPreparedImage(image: OfflineImagePayload) {
        if (getTalkStage() != TalkStage.Chat || getAppSection() != AppSection.Offline) {
            return
        }
        val msgId = allocateOfflineMessageId(chatUserName)
        val rowId = nextChatLocalId()
        AppLog.d(
            "AT2HT-OFFLINE",
            "image send start mid=${msgId.toString(16)} jpegBytes=${image.jpegBytes.size} size=${image.originalWidth}x${image.originalHeight}"
        )
        val pendingMessage = OfflineChatMessage(
            id = rowId,
            protocolId = msgId.toLong(),
            self = true,
            sender = chatUserName,
            kind = ChatMessageKind.Image,
            title = "",
            imageBase64 = Base64.getEncoder().encodeToString(image.jpegBytes),
            pending = true,
            pendingProgress = 0,
        )
        appendChatMessage(pendingMessage)
        outgoingImageSendJob?.cancel()
        outgoingImageSendJob = scope.launch {
            runCatching {
                protocolExecutor.sendOfflineImage(
                    msgId = msgId,
                    username = chatUserName,
                    jpegBytes = image.jpegBytes,
                    originalWidth = image.originalWidth,
                    originalHeight = image.originalHeight
                ) { sentPackets, totalPackets ->
                    val percent = ((sentPackets * 100f) / totalPackets.coerceAtLeast(1)).toInt().coerceIn(0, 100)
                    updateChatMessageProgress(rowId, percent)
                }
            }.onSuccess {
                outgoingImageSendJob = null
                replaceChatMessage(rowId, pendingMessage.copy(pending = false, pendingProgress = 100), false)
            }.onFailure { error ->
                outgoingImageSendJob = null
                removeChatMessage(rowId)
                if (error !is kotlinx.coroutines.CancellationException) {
                    AppLog.e("AT2HT-OFFLINE", "image send failed mid=${msgId.toString(16)}", error)
                    Toast.makeText(context, R.string.chat_send_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun showVoiceRecordDialog() {
        if (voiceRecordDialog?.isShowing == true) return
        if (!hasRecordAudioPermission()) {
            Toast.makeText(context, R.string.chat_record_permission_required, Toast.LENGTH_SHORT).show()
            pendingRecordAudioAction = { showVoiceRecordDialogInternal() }
            requestVoicePermission()
            return
        }
        showVoiceRecordDialogInternal()
    }

    private fun showVoiceRecordDialogInternal() {
        val channel = getCurrentChannel()
        when {
            getBleState() != BleSessionState.Ready -> {
                Toast.makeText(context, R.string.offline_not_connected, Toast.LENGTH_SHORT).show()
                return
            }
            channel.rxMhz == null || channel.txMhz == null -> {
                Toast.makeText(context, R.string.offline_channel_unavailable, Toast.LENGTH_SHORT).show()
                return
            }
            !channel.modeDigital -> {
                Toast.makeText(context, R.string.offline_analog_unsupported, Toast.LENGTH_SHORT).show()
                return
            }
        }

        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_voice_record)
        dialog.setCancelable(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.45f }
        }

        val hintView = dialog.findViewById<TextView>(R.id.tvVoiceRecordHint)
        val pulseView = dialog.findViewById<View>(R.id.viewVoiceRecordPulse)
        val holdButton = dialog.findViewById<FrameLayout>(R.id.btnVoiceRecordHold)
        val micView = dialog.findViewById<ImageView>(R.id.ivVoiceRecordMic)

        var recording = false
        var cancelledBySlide = false
        val cancelDistancePx = dp(84).toFloat()
        var downY = 0f
        var autoFinishJob: Job? = null
        var haloAnimator: ValueAnimator? = null

        fun stopHaloPulse() {
            haloAnimator?.cancel()
            haloAnimator = null
            pulseView.background = null
            pulseView.isVisible = false
            pulseView.alpha = 1f
            pulseView.scaleX = 1f
            pulseView.scaleY = 1f
        }

        fun startHaloPulse(@DrawableRes haloRes: Int) {
            haloAnimator?.cancel()
            pulseView.background = AppCompatResources.getDrawable(context, haloRes)
            pulseView.isVisible = true
            pulseView.alpha = 1f
            pulseView.scaleX = 1f
            pulseView.scaleY = 1f
            haloAnimator = ValueAnimator.ofFloat(0f, (Math.PI * 2.0).toFloat()).apply {
                duration = 1200L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { animator ->
                    val phase = animator.animatedValue as Float
                    val normalized = ((kotlin.math.sin(phase.toDouble()) + 1.0) / 2.0).toFloat()
                    val scale = 0.9f + (0.10f * normalized)
                    pulseView.scaleX = scale
                    pulseView.scaleY = scale
                    pulseView.alpha = 0.72f + (0.18f * normalized)
                }
                start()
            }
        }

        fun applyIdleState() {
            hintView.text = context.getString(R.string.voice_record_hold_to_talk)
            hintView.setTextColor(0xFFD1D5DB.toInt())
            holdButton.background = AppCompatResources.getDrawable(context, R.drawable.bg_voice_record_button_blue)
            micView.alpha = 1f
            stopHaloPulse()
        }

        fun applySendState() {
            hintView.text = context.getString(R.string.voice_record_release_to_send)
            hintView.setTextColor(0xFF9CA3AF.toInt())
            holdButton.background = AppCompatResources.getDrawable(context, R.drawable.bg_voice_record_button_green)
            micView.alpha = 1f
            startHaloPulse(R.drawable.bg_voice_record_halo_green)
        }

        fun applyCancelState() {
            hintView.text = context.getString(R.string.voice_record_release_to_cancel)
            hintView.setTextColor(0xFFEF4444.toInt())
            holdButton.background = AppCompatResources.getDrawable(context, R.drawable.bg_voice_record_button_red)
            micView.alpha = 1f
            startHaloPulse(R.drawable.bg_voice_record_halo_red)
        }

        suspend fun finishRecordingAndMaybeSend() {
            val result = voiceRecorder.finish()
            if (cancelledBySlide) return
            if (result == null) {
                Toast.makeText(context, R.string.voice_record_failed, Toast.LENGTH_SHORT).show()
                return
            }
            if (result.durationMs < MIN_OFFLINE_VOICE_DURATION_MS) {
                Toast.makeText(context, R.string.voice_record_too_short, Toast.LENGTH_SHORT).show()
                return
            }
            sendRecordedVoice(result)
        }

        holdButton.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    cancelledBySlide = false
                    if (!recording) {
                        scope.launch {
                            val started = voiceRecorder.start()
                            if (!started) {
                                Toast.makeText(context, R.string.voice_record_start_failed, Toast.LENGTH_SHORT).show()
                                dialog.dismiss()
                                return@launch
                            }
                            recording = true
                            applySendState()
                            autoFinishJob?.cancel()
                            autoFinishJob = scope.launch {
                                delay(MAX_OFFLINE_VOICE_DURATION_MS.toLong())
                                if (!recording) return@launch
                                recording = false
                                cancelledBySlide = false
                                finishRecordingAndMaybeSend()
                                if (voiceRecordDialog === dialog && dialog.isShowing) {
                                    dialog.dismiss()
                                }
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!recording) return@setOnTouchListener true
                    val draggingUp = downY - event.rawY
                    val shouldCancel = draggingUp >= cancelDistancePx
                    if (shouldCancel != cancelledBySlide) {
                        cancelledBySlide = shouldCancel
                        if (cancelledBySlide) applyCancelState() else applySendState()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!recording) {
                        dialog.dismiss()
                        return@setOnTouchListener true
                    }
                    recording = false
                    autoFinishJob?.cancel()
                    autoFinishJob = null
                    val shouldCancel = cancelledBySlide || event.actionMasked == MotionEvent.ACTION_CANCEL
                    scope.launch {
                        if (shouldCancel) {
                            voiceRecorder.cancel()
                        } else {
                            finishRecordingAndMaybeSend()
                        }
                    }
                    dialog.dismiss()
                    true
                }
                else -> false
            }
        }

        dialog.setOnDismissListener {
            voiceRecordDialog = null
            autoFinishJob?.cancel()
            autoFinishJob = null
            stopHaloPulse()
            if (recording) {
                recording = false
                scope.launch { voiceRecorder.cancel() }
            }
        }
        applyIdleState()
        voiceRecordDialog = dialog
        dialog.show()
    }

    private fun sendRecordedVoice(recording: OfflineVoiceRecording) {
        val msgId = allocateOfflineMessageId(chatUserName)
        val rowId = nextChatLocalId()
        val title = formatVoiceDuration(recording.durationMs)
        val pendingMessage = OfflineChatMessage(
            id = rowId,
            protocolId = msgId.toLong(),
            self = true,
            sender = chatUserName,
            kind = ChatMessageKind.Voice,
            title = title,
            voiceBase64 = Base64.getEncoder().encodeToString(recording.encodedBytes),
            voiceDurationMs = recording.durationMs,
            pending = true,
            pendingProgress = 0,
        )
        appendChatMessage(pendingMessage)
        outgoingVoiceSendJob?.cancel()
        outgoingVoiceSendJob = scope.launch {
            runCatching {
                protocolExecutor.sendOfflineVoice(msgId, chatUserName, recording.encodedBytes, recording.durationMs) { sentBytes, totalBytes ->
                    val percent = ((sentBytes * 100f) / totalBytes.coerceAtLeast(1)).toInt().coerceIn(0, 100)
                    updateChatMessageProgress(rowId, percent)
                }
            }.onSuccess {
                outgoingVoiceSendJob = null
                replaceChatMessage(rowId, pendingMessage.copy(pending = false, pendingProgress = 100), false)
            }.onFailure { error ->
                outgoingVoiceSendJob = null
                removeChatMessage(rowId)
                if (error !is kotlinx.coroutines.CancellationException) {
                    Toast.makeText(context, R.string.voice_send_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun handleVoiceMessageClick(message: OfflineChatMessage) {
        if (message.kind != ChatMessageKind.Voice || message.pending) return
        val encoded = message.voiceData ?: message.voiceBase64?.let {
            runCatching { Base64.getDecoder().decode(it) }.getOrNull()
        } ?: return
        if (!message.self && message.voiceUnread) {
            replaceChatMessage(message.id, message.copy(voiceUnread = false), false)
        }
        voicePlayer.toggle(message.id, encoded)
    }

    fun handleImageMessageClick(message: OfflineChatMessage) {
        if (message.kind != ChatMessageKind.Image || message.pending) return
        val imageBytes = extractChatImageBytes(message) ?: return
        context.startActivity(ImageViewerActivity.createIntent(context, imageBytes))
    }

    fun showChatUsernameDialog() {
        val input = EditText(context).apply {
            setText(chatUserName)
            hint = context.getString(R.string.chat_username_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSelection(text.length)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.chat_username_title)
            .setView(input)
            .setPositiveButton(R.string.chat_username_saved) { _, _ ->
                val value = input.text.toString().trim().ifBlank { At2OfflineMessageCodec.DEFAULT_USERNAME }
                chatUserName = value
                prefs.edit().putString(PREF_CHAT_USERNAME, value).apply()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .create()
        styleDialog(dialog)
        dialog.show()
    }

    fun allocateOfflineMessageId(username: String): UInt {
        val normalized = username.trim().ifBlank { At2OfflineMessageCodec.DEFAULT_USERNAME }
        val suffix = stableOfflineSenderSuffix(normalized)
        val msgId = ((offlineMessageCounter and 0xFFu) shl 24) or suffix
        offlineMessageCounter = ((offlineMessageCounter + 1u) and 0xFFu).let { if (it == 0u) 1u else it }
        prefs.edit().putInt(PREF_OFFLINE_MESSAGE_COUNTER, offlineMessageCounter.toInt()).apply()
        return msgId
    }

    private fun decodeChatImageBitmap(message: OfflineChatMessage): Bitmap? {
        val bytes = extractChatImageBytes(message) ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun extractChatImageBytes(message: OfflineChatMessage): ByteArray? {
        return message.imageData ?: message.imageBase64?.let {
            runCatching { Base64.getDecoder().decode(it) }.getOrNull()
        }
    }

    private fun saveBitmapToGallery(bitmap: Bitmap): Boolean {
        val values = ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "AT2HT_${System.currentTimeMillis()}.jpg")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + File.separator + "AT2HT")
                put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return runCatching {
            contentResolver.openOutputStream(uri)?.use { output: OutputStream ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) {
                    error("compress failed")
                }
            } ?: error("openOutputStream failed")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            }
            true
        }.getOrElse {
            contentResolver.delete(uri, null, null)
            false
        }
    }

    private fun startVoicePlaybackPulse() {
        currentPlayingVoicePulseJob?.cancel()
        currentPlayingVoiceAlternate = false
        renderChat()
        currentPlayingVoicePulseJob = scope.launch {
            while (currentPlayingVoiceMessageId != null) {
                delay(500)
                if (currentPlayingVoiceMessageId == null) break
                currentPlayingVoiceAlternate = !currentPlayingVoiceAlternate
                renderChat()
            }
        }
    }

    private fun stopVoicePlaybackPulse() {
        currentPlayingVoicePulseJob?.cancel()
        currentPlayingVoicePulseJob = null
        currentPlayingVoiceAlternate = false
    }

    private fun formatVoiceDuration(durationMs: Int): String {
        val seconds = ((durationMs + 999) / 1000).coerceAtLeast(1)
        return context.getString(R.string.voice_duration_seconds, seconds)
    }

    private fun stableOfflineSenderSuffix(username: String): UInt {
        var hash = 0x811C9DC5u
        username.toByteArray(Charsets.UTF_8).forEach { b ->
            hash = hash xor (b.toUInt() and 0xFFu)
            hash *= 0x01000193u
        }
        val suffix = hash and 0x00FFFFFFu
        return if (suffix == 0u) 0x000001u else suffix
    }

    companion object {
        private const val PREF_CHAT_USERNAME = "chat_username"
        private const val PREF_OFFLINE_MESSAGE_COUNTER = "offline_message_counter"
        private const val MAX_OFFLINE_TEXT_UTF8_BYTES = 1000
        private const val MIN_OFFLINE_VOICE_DURATION_MS = 600
        private const val MAX_OFFLINE_VOICE_DURATION_MS = 15_000
    }
}
