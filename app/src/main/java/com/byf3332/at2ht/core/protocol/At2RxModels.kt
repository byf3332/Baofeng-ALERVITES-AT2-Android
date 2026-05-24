package com.byf3332.at2ht.core.protocol

data class At2Packet(
    val family: Int,
    val command: Int,
    val payload: ByteArray,
)

sealed interface OfflineFrame

data class OfflineStartFrame(
    val type: Int,
    val msgId: UInt,
    val sender: String,
    val declaredLength: Int,
    val totalParts: Int,
    val seq: Int,
    val inlineData: ByteArray,
    val durationMs: Int? = null,
) : OfflineFrame

data class OfflineChunk(
    val type: Int,
    val msgId: UInt,
    val seq: Int,
    val data: ByteArray,
) : OfflineFrame

sealed interface OfflineEvent {
    data class Text(
        val msgId: UInt,
        val sender: String?,
        val text: String,
    ) : OfflineEvent

    data class Image(
        val msgId: UInt,
        val sender: String?,
        val jpeg: ByteArray,
    ) : OfflineEvent

    data class VoiceMessage(
        val msgId: UInt,
        val sender: String?,
        val encoded: ByteArray,
        val durationMs: Int? = null,
    ) : OfflineEvent

    data class UnknownStartFrame(
        val msgId: UInt,
        val type: Int,
        val sender: String?,
        val raw: ByteArray,
    ) : OfflineEvent
}
