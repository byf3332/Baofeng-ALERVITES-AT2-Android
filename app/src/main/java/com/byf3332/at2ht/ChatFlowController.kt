package com.byf3332.at2ht

import android.graphics.BitmapFactory
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.content.Context
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.RecyclerView
import com.byf3332.at2ht.core.protocol.OfflineChunk
import com.byf3332.at2ht.core.protocol.OfflineEvent
import com.byf3332.at2ht.core.protocol.OfflineFrame
import com.byf3332.at2ht.core.protocol.OfflineStartFrame
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Base64

class ChatFlowController(
    private val context: Context,
    private val scope: LifecycleCoroutineScope,
    private val chatMessages: MutableList<OfflineChatMessage>,
    private val chatAdapter: OfflineChatAdapter,
    private val rvMessages: RecyclerView,
    private val persistChatMessage: (OfflineChatMessage) -> Unit,
    private val deleteChatMessage: (Long) -> Unit,
    private val nextChatLocalId: () -> Long,
    private val parseSpecialText: (String) -> ParsedSpecialText?,
    private val renderChat: (Boolean) -> Unit,
) {
    private val incomingPendingLengths = mutableMapOf<UInt, Int>()
    private val incomingPendingTypes = mutableMapOf<UInt, ChatMessageKind>()
    private val incomingPendingSenders = mutableMapOf<UInt, String>()
    private val incomingPendingChunks = mutableMapOf<UInt, MutableMap<Int, Int>>()
    private val incomingPendingImageChunks = mutableMapOf<UInt, MutableMap<Int, ByteArray>>()
    private val incomingPendingChunkPayloads = mutableMapOf<UInt, MutableMap<Int, ByteArray>>()
    private val incomingPendingFinalizeJobs = mutableMapOf<UInt, Job>()
    private val incomingPendingRenderedProgress = mutableMapOf<UInt, Int>()
    private val incomingPendingRenderedAt = mutableMapOf<UInt, Long>()

    fun submitToAdapter(scrollToBottom: Boolean = false) {
        chatAdapter.submit(chatMessages.toList())
        if (scrollToBottom) {
            rvMessages.post {
                if (chatMessages.isNotEmpty()) {
                    rvMessages.scrollToPosition(chatMessages.lastIndex)
                }
            }
        }
    }

    fun appendChatMessage(message: OfflineChatMessage) {
        chatMessages.add(message)
        persistChatMessage(message)
        renderChat(true)
    }

    fun replaceChatMessage(id: Long, message: OfflineChatMessage, scrollToBottom: Boolean = false) {
        val index = chatMessages.indexOfFirst { it.id == id }
        if (index >= 0) {
            chatMessages[index] = message
        } else {
            chatMessages.add(message)
        }
        persistChatMessage(message)
        renderChat(scrollToBottom)
    }

    fun updateChatMessageProgress(id: Long, progress: Int) {
        val index = chatMessages.indexOfFirst { it.id == id }
        if (index < 0) return
        val current = chatMessages[index]
        if (!current.pending || current.pendingProgress == progress) return
        chatMessages[index] = current.copy(pendingProgress = progress.coerceIn(0, 100))
        renderChat(false)
    }

    fun removeChatMessage(id: Long) {
        chatMessages.removeAll { it.id == id }
        deleteChatMessage(id)
        renderChat(false)
    }

    fun clearIncomingPendingState() {
        incomingPendingLengths.clear()
        incomingPendingTypes.clear()
        incomingPendingSenders.clear()
        incomingPendingChunks.clear()
        incomingPendingImageChunks.clear()
        incomingPendingChunkPayloads.clear()
        incomingPendingFinalizeJobs.values.forEach { it.cancel() }
        incomingPendingFinalizeJobs.clear()
        incomingPendingRenderedProgress.clear()
        incomingPendingRenderedAt.clear()
    }

    fun onOfflineFrame(frame: OfflineFrame) {
        when (frame) {
            is OfflineStartFrame -> {
                if ((frame.type == 0x01 || frame.type == 0x03 || frame.type == 0x05) && frame.totalParts > 1) {
                    val kind = when (frame.type) {
                        0x03 -> ChatMessageKind.Voice
                        0x05 -> ChatMessageKind.Image
                        else -> ChatMessageKind.Text
                    }
                    incomingPendingLengths[frame.msgId] = frame.declaredLength
                    incomingPendingTypes[frame.msgId] = kind
                    frame.sender.takeIf { it.isNotBlank() }?.let { incomingPendingSenders[frame.msgId] = it }
                    incomingPendingChunks.getOrPut(frame.msgId) { linkedMapOf() }
                    if (frame.type == 0x05) {
                        incomingPendingImageChunks.getOrPut(frame.msgId) { linkedMapOf() }
                    }
                    incomingPendingChunkPayloads.getOrPut(frame.msgId) { linkedMapOf() }
                    scheduleIncomingFinalize(frame.msgId)
                    ensureIncomingPending(
                        frame.msgId,
                        frame.sender,
                        0,
                        kind = kind,
                    )
                }
            }

            is OfflineChunk -> {
                if (frame.type == 0x02 || frame.type == 0x04 || frame.type == 0x06) {
                    val kind = when (frame.type) {
                        0x04 -> ChatMessageKind.Voice
                        0x06 -> ChatMessageKind.Image
                        else -> ChatMessageKind.Text
                    }
                    val chunks = incomingPendingChunks.getOrPut(frame.msgId) { linkedMapOf() }
                    chunks[frame.seq] = effectiveChunkLength(frame.data)
                    incomingPendingTypes.putIfAbsent(frame.msgId, kind)
                    incomingPendingChunkPayloads.getOrPut(frame.msgId) { linkedMapOf() }[frame.seq] = frame.data.copyOf()
                    val total = incomingPendingLengths[frame.msgId]
                    val received = chunks.values.sum()
                    val progress = if (total != null && total > 0) {
                        ((received * 100f) / total).toInt().coerceIn(0, 99)
                    } else {
                        0
                    }
                    val previewBase64 = if (frame.type == 0x06) {
                        val imageChunks = incomingPendingImageChunks.getOrPut(frame.msgId) { linkedMapOf() }
                        imageChunks[frame.seq] = frame.data.copyOf()
                        buildIncomingImagePreviewBase64(frame.msgId)
                    } else {
                        null
                    }
                    scheduleIncomingFinalize(frame.msgId)
                    ensureIncomingPending(
                        frame.msgId,
                        null,
                        progress,
                        kind = kind,
                        previewImageBase64 = previewBase64,
                    )
                }
            }
        }
    }

    fun onIncomingOfflineText(event: OfflineEvent.Text) {
        val text = event.text.trim()
        AppLog.d("AT2HT-OFFLINE", "incomingText sender=" + (event.sender ?: "") + " text=" + text)
        if (text.isEmpty()) return
        clearIncomingPending(event.msgId)
        val protocolId = event.msgId.toLong()
        val index = findIncomingMessageIndex(protocolId)
        val rowId = if (index >= 0) chatMessages[index].id else nextChatLocalId()
        val parsed = parseSpecialText(text)
        replaceChatMessage(
            rowId,
            OfflineChatMessage(
                id = rowId,
                protocolId = protocolId,
                self = false,
                sender = event.sender?.ifBlank { context.getString(R.string.chat_sender_default) } ?: context.getString(R.string.chat_sender_default),
                kind = parsed?.kind ?: ChatMessageKind.Text,
                title = parsed?.title ?: text,
                body = parsed?.body ?: "",
                pending = false,
            ),
            scrollToBottom = index < 0
        )
        vibrateForIncomingMessage()
    }

    fun onIncomingOfflineImage(event: OfflineEvent.Image) {
        if (event.jpeg.isEmpty()) return
        AppLog.d("AT2HT-OFFLINE", "incomingImage sender=${event.sender ?: ""} bytes=${event.jpeg.size}")
        clearIncomingPending(event.msgId)
        val protocolId = event.msgId.toLong()
        val index = findIncomingMessageIndex(protocolId)
        val rowId = if (index >= 0) chatMessages[index].id else nextChatLocalId()
        replaceChatMessage(
            rowId,
            OfflineChatMessage(
                id = rowId,
                protocolId = protocolId,
                self = false,
                sender = event.sender?.ifBlank { context.getString(R.string.chat_sender_default) } ?: context.getString(R.string.chat_sender_default),
                kind = ChatMessageKind.Image,
                title = "",
                imageBase64 = Base64.getEncoder().encodeToString(event.jpeg),
                pending = false,
            ),
            scrollToBottom = index < 0
        )
        vibrateForIncomingMessage()
    }

    fun onIncomingOfflineVoice(event: OfflineEvent.VoiceMessage) {
        val encoded = event.encoded
        if (encoded.isEmpty()) return
        clearIncomingPending(event.msgId)
        val protocolId = event.msgId.toLong()
        val index = findIncomingMessageIndex(protocolId)
        val rowId = if (index >= 0) chatMessages[index].id else nextChatLocalId()
        val durationMs = event.durationMs ?: ((encoded.size / 12f) * 20f).toInt()
        replaceChatMessage(
            rowId,
            OfflineChatMessage(
                id = rowId,
                protocolId = protocolId,
                self = false,
                sender = event.sender?.ifBlank { context.getString(R.string.chat_sender_default) } ?: context.getString(R.string.chat_sender_default),
                kind = ChatMessageKind.Voice,
                title = formatVoiceDuration(durationMs),
                voiceBase64 = Base64.getEncoder().encodeToString(encoded),
                voiceDurationMs = durationMs,
                voiceUnread = true,
                pending = false,
            ),
            scrollToBottom = index < 0
        )
        vibrateForIncomingMessage()
    }

    private fun clearIncomingPending(msgId: UInt) {
        incomingPendingLengths.remove(msgId)
        incomingPendingTypes.remove(msgId)
        incomingPendingSenders.remove(msgId)
        incomingPendingChunks.remove(msgId)
        incomingPendingImageChunks.remove(msgId)
        incomingPendingChunkPayloads.remove(msgId)
        incomingPendingFinalizeJobs.remove(msgId)?.cancel()
        incomingPendingRenderedProgress.remove(msgId)
        incomingPendingRenderedAt.remove(msgId)
    }

    private fun findIncomingMessageIndex(protocolId: Long): Int =
        chatMessages.indexOfFirst { !it.self && it.protocolId == protocolId }

    private fun buildIncomingImagePreviewBase64(msgId: UInt): String? {
        val chunks = incomingPendingImageChunks[msgId] ?: return null
        val orderedKeys = chunks.keys.sorted()
        if (orderedKeys.isEmpty() || orderedKeys.first() != 0) return null
        val prefixKeys = mutableListOf<Int>()
        var expected = 0
        while (chunks.containsKey(expected)) {
            prefixKeys.add(expected)
            expected += 1
        }
        if (prefixKeys.isEmpty()) return null
        val merged = mergeIncomingChunkPayloads(prefixKeys.mapNotNull { chunks[it] })
        val declaredLength = incomingPendingLengths[msgId]
        val trimmed = if (declaredLength != null && declaredLength > 0 && merged.size > declaredLength) {
            merged.copyOfRange(0, declaredLength)
        } else {
            merged
        }
        val soi = trimmed.indexOfBytePair(0xFF.toByte(), 0xD8.toByte())
        if (soi < 0) return null
        val eoi = trimmed.lastIndexOfBytePair(0xFF.toByte(), 0xD9.toByte())
        val candidate = if (eoi >= soi) {
            trimmed.copyOfRange(soi, eoi + 2)
        } else {
            trimmed.copyOfRange(soi, trimmed.size) + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        }
        val decoded = BitmapFactory.decodeByteArray(candidate, 0, candidate.size) ?: return null
        val out = java.io.ByteArrayOutputStream()
        if (!decoded.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)) return null
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    private fun scheduleIncomingFinalize(msgId: UInt) {
        incomingPendingFinalizeJobs.remove(msgId)?.cancel()
        incomingPendingFinalizeJobs[msgId] = scope.launch {
            delay(1800)
            val index = findIncomingMessageIndex(msgId.toLong())
            if (index < 0) return@launch
            val current = chatMessages[index]
            if (!current.pending) return@launch
            when (incomingPendingTypes[msgId] ?: current.kind) {
                ChatMessageKind.Image -> {
                    val preview = current.imageBase64 ?: buildIncomingImagePreviewBase64(msgId) ?: return@launch
                    clearIncomingPending(msgId)
                    replaceChatMessage(
                        current.id,
                        current.copy(
                            kind = ChatMessageKind.Image,
                            imageBase64 = preview,
                            pending = false,
                            pendingProgress = 100,
                        ),
                        scrollToBottom = false
                    )
                }
                ChatMessageKind.Text -> {
                    val partialText = buildIncomingTextPreview(msgId).ifBlank { context.getString(R.string.common_error_short) }
                    clearIncomingPending(msgId)
                    replaceChatMessage(
                        current.id,
                        current.copy(
                            kind = ChatMessageKind.Text,
                            title = partialText,
                            body = "",
                            pending = false,
                            pendingProgress = 100,
                        ),
                        scrollToBottom = false
                    )
                }
                ChatMessageKind.Voice -> {
                    clearIncomingPending(msgId)
                    replaceChatMessage(
                        current.id,
                        current.copy(
                            kind = ChatMessageKind.Voice,
                            title = context.getString(R.string.chat_incomplete_voice, current.pendingProgress.coerceIn(0, 99)),
                            voiceBase64 = null,
                            voiceData = null,
                            voiceDurationMs = null,
                            voiceUnread = false,
                            pending = false,
                            pendingProgress = 100,
                        ),
                        scrollToBottom = false
                    )
                }
                else -> Unit
            }
        }
    }

    private fun buildIncomingTextPreview(msgId: UInt): String {
        val chunks = incomingPendingChunkPayloads[msgId] ?: return ""
        val orderedKeys = chunks.keys.sorted()
        if (orderedKeys.isEmpty() || orderedKeys.first() != 0) return ""
        val prefixKeys = mutableListOf<Int>()
        var expected = 0
        while (chunks.containsKey(expected)) {
            prefixKeys.add(expected)
            expected += 1
        }
        if (prefixKeys.isEmpty()) return ""
        val merged = mergeIncomingChunkPayloads(prefixKeys.mapNotNull { chunks[it] })
        val declaredLength = incomingPendingLengths[msgId]
        val trimmed = if (declaredLength != null && declaredLength > 0 && merged.size > declaredLength) {
            merged.copyOfRange(0, declaredLength)
        } else {
            merged
        }
        return trimmed
            .filter { it != 0.toByte() }
            .toByteArray()
            .toString(Charsets.UTF_8)
            .trim()
            .ifBlank { "" }
    }

    private fun mergeIncomingChunkPayloads(values: List<ByteArray>): ByteArray {
        val stripped = values.map { part ->
            if (part.isNotEmpty() && part[0] == 0.toByte()) part.copyOfRange(1, part.size) else part
        }
        val total = stripped.sumOf { it.size }
        val out = ByteArray(total)
        var offset = 0
        stripped.forEach { part ->
            part.copyInto(out, offset)
            offset += part.size
        }
        return out
    }

    private fun effectiveChunkLength(data: ByteArray): Int {
        if (data.isEmpty()) return 0
        return if (data[0] == 0.toByte()) data.size - 1 else data.size
    }

    private fun ByteArray.indexOfBytePair(a: Byte, b: Byte): Int {
        for (i in 0 until size - 1) if (this[i] == a && this[i + 1] == b) return i
        return -1
    }

    private fun ByteArray.lastIndexOfBytePair(a: Byte, b: Byte): Int {
        for (i in size - 2 downTo 0) if (this[i] == a && this[i + 1] == b) return i
        return -1
    }

    private fun ensureIncomingPending(
        msgId: UInt,
        sender: String?,
        progress: Int = 0,
        kind: ChatMessageKind = ChatMessageKind.Text,
        previewImageBase64: String? = null,
    ) {
        val clampedProgress = progress.coerceIn(0, 100)
        val protocolId = msgId.toLong()
        val index = findIncomingMessageIndex(protocolId)
        var added = false
        if (index >= 0) {
            val current = chatMessages[index]
            if (current.pending) {
                chatMessages[index] = current.copy(
                    sender = sender?.ifBlank { current.sender } ?: current.sender,
                    kind = if (current.kind == ChatMessageKind.Image || kind == ChatMessageKind.Image) ChatMessageKind.Image else current.kind,
                    imageBase64 = previewImageBase64 ?: current.imageBase64,
                    pendingProgress = clampedProgress
                )
            }
        } else {
            added = true
            chatMessages.add(
                OfflineChatMessage(
                    id = nextChatLocalId(),
                    protocolId = protocolId,
                    self = false,
                    sender = sender?.ifBlank { context.getString(R.string.chat_sender_default) } ?: context.getString(R.string.chat_sender_default),
                    kind = kind,
                    title = "",
                    imageBase64 = previewImageBase64,
                    pending = true,
                    pendingProgress = clampedProgress,
                )
            )
        }
        val now = SystemClock.elapsedRealtime()
        val lastProgress = incomingPendingRenderedProgress[msgId]
        val lastAt = incomingPendingRenderedAt[msgId] ?: 0L
        val shouldRender = added ||
            previewImageBase64 != null ||
            clampedProgress >= 100 ||
            lastProgress == null ||
            clampedProgress - lastProgress >= 10 ||
            now - lastAt >= 200L
        if (!shouldRender) return
        incomingPendingRenderedProgress[msgId] = clampedProgress
        incomingPendingRenderedAt[msgId] = now
        renderChat(added)
    }

    private fun formatVoiceDuration(durationMs: Int): String {
        val seconds = ((durationMs + 500) / 1000).coerceAtLeast(1)
        return context.getString(R.string.voice_duration_seconds, seconds)
    }

    private fun vibrateForIncomingMessage() {
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        if (!vibrator.hasVibrator()) return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(200L, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(200L)
        }
    }
}
