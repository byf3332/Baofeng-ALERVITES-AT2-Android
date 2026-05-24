package com.byf3332.at2ht.core.protocol

import com.byf3332.at2ht.core.ble.BleSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

class At2SessionProtocol(
    private val session: BleSession,
    private val codec: At2FrameCodec = At2FrameCodec(),
    private val txMutex: Mutex,
    private val lastPtt403TxAtMs: AtomicLong,
) {
    suspend fun sendPayload(payloadNo00: ByteArray, tag: String) {
        val isPttVoice = payloadNo00.size >= 3 &&
            payloadNo00[0] == 0x02.toByte() &&
            payloadNo00[1] == 0x04.toByte() &&
            payloadNo00[2] == 0x03.toByte()
        txMutex.withLock {
            val frame = codec.encode(byteArrayOf(0x00) + payloadNo00)
            session.writeAwait(frame)
            if (isPttVoice) {
                lastPtt403TxAtMs.set(System.currentTimeMillis())
            }
        }
    }
}
