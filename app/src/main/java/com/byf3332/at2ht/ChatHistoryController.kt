package com.byf3332.at2ht

import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatHistoryController(
    private val scope: LifecycleCoroutineScope,
    private val chatStore: OfflineChatStore,
    private val chatMessages: MutableList<OfflineChatMessage>,
    private val chatAdapter: OfflineChatAdapter,
    private val rvMessages: RecyclerView,
    private val currentChatStorageKey: () -> String,
    private val styleDialog: (AlertDialog) -> Unit,
    private val stopVoicePlayback: () -> Unit,
    private val renderChat: () -> Unit,
    private val clearIncomingPendingState: () -> Unit,
    private val initialLoadLimit: Int,
    private val pageSize: Int,
    private val perThreadLimit: Int,
) {
    var hasOlderChatMessages: Boolean = false
        private set
    var isLoadingOlderChatMessages: Boolean = false
        private set

    suspend fun loadCurrentThread() {
        chatMessages.clear()
        hasOlderChatMessages = false
        isLoadingOlderChatMessages = false
        clearIncomingPendingState()
        val threadKey = currentChatStorageKey()
        val stored = withContext(Dispatchers.IO) {
            chatStore.loadThread(threadKey, limit = initialLoadLimit)
        }
        if (stored.isNotEmpty()) {
            chatMessages.addAll(stored)
            val oldestId = stored.firstOrNull()?.id
            hasOlderChatMessages = oldestId != null && withContext(Dispatchers.IO) {
                chatStore.hasThreadBefore(threadKey, oldestId)
            }
        }
    }

    fun loadOlderMessages() {
        if (isLoadingOlderChatMessages || !hasOlderChatMessages || chatMessages.isEmpty()) return
        val layoutManager = rvMessages.layoutManager as? LinearLayoutManager ?: return
        val anchorPosition = layoutManager.findFirstVisibleItemPosition().coerceAtLeast(0)
        val anchorView = layoutManager.findViewByPosition(anchorPosition)
        val anchorTop = anchorView?.top ?: 0
        val oldestId = chatMessages.firstOrNull()?.id ?: return
        val threadKey = currentChatStorageKey()
        isLoadingOlderChatMessages = true
        scope.launch {
            val older = withContext(Dispatchers.IO) {
                chatStore.loadThreadBefore(threadKey, oldestId, pageSize)
            }
            if (older.isNotEmpty()) {
                chatMessages.addAll(0, older)
                chatAdapter.submit(chatMessages.toList())
                rvMessages.post {
                    layoutManager.scrollToPositionWithOffset(anchorPosition + older.size, anchorTop)
                }
            }
            hasOlderChatMessages = older.isNotEmpty() && withContext(Dispatchers.IO) {
                chatStore.hasThreadBefore(threadKey, chatMessages.firstOrNull()?.id ?: Long.MIN_VALUE)
            }
            isLoadingOlderChatMessages = false
        }
    }

    fun persistChatMessage(message: OfflineChatMessage) {
        if (message.pending) return
        val threadKey = currentChatStorageKey()
        val snapshot = message.copy()
        scope.launch(Dispatchers.IO) {
            chatStore.upsertMessage(threadKey, snapshot)
            chatStore.trimThreadToLatest(threadKey, perThreadLimit)
        }
    }

    fun deleteChatMessage(id: Long) {
        val threadKey = currentChatStorageKey()
        scope.launch(Dispatchers.IO) {
            chatStore.deleteMessage(threadKey, id)
        }
    }

    fun clearChatHistory() {
        val dialog = AlertDialog.Builder(rvMessages.context)
            .setTitle(R.string.chat_clear_history_title)
            .setMessage(R.string.chat_clear_history_message)
            .setPositiveButton(R.string.chat_clear_history_confirm) { _, _ ->
                stopVoicePlayback()
                chatMessages.clear()
                val threadKey = currentChatStorageKey()
                scope.launch(Dispatchers.IO) {
                    chatStore.clearThread(threadKey)
                }
                renderChat()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .create()
        styleDialog(dialog)
        dialog.show()
    }
}
