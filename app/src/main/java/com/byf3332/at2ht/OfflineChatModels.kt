package com.byf3332.at2ht

enum class ChatMessageKind { Text, Location, Help, Image, Voice }

data class ParsedSpecialText(
    val kind: ChatMessageKind,
    val title: String,
    val body: String,
)

data class OfflineChatMessage(
    val id: Long,
    val protocolId: Long? = null,
    val self: Boolean,
    val sender: String,
    val kind: ChatMessageKind,
    val title: String,
    val body: String = "",
    val imageBase64: String? = null,
    val imageData: ByteArray? = null,
    val voiceBase64: String? = null,
    val voiceData: ByteArray? = null,
    val voiceDurationMs: Int? = null,
    val voiceUnread: Boolean = false,
    val pending: Boolean = false,
    val pendingProgress: Int = 0,
)
