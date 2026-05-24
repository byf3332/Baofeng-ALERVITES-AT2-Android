package com.byf3332.at2ht.core.ptt

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import com.ucchip.sdk.codec.talkie.TalkieCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ArrayDeque

class PttVoiceSender(
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val sendPayload: suspend (ByteArray, String) -> Unit,
) {
    private var job: Job? = null
    private var lastPacketTxMs: Long = 0L
    @Volatile private var stopNow: Boolean = false

    fun isRunning(): Boolean = job?.isActive == true

    fun start() {
        if (isRunning()) return
        lastPacketTxMs = 0L
        stopNow = false
        job = scope.launch(Dispatchers.IO) {
            var recorder: AudioRecord? = null
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                TalkieCodec.ensureLoaded()
                val codec = TalkieCodec().apply { init() }
                val frameBytes = codec.getFrameSize() // official trace shows encoder input len=320 bytes
                val encodedFrame = codec.getEncodedFrameSize() // expected 12
                val sampleRate = codec.getSampleRate() // expected 8000
                log("PTT codec frameBytes=$frameBytes enc=$encodedFrame sr=$sampleRate")

                val minBuf = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(frameBytes * 4)

                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf
                )
                recorder.startRecording()

                val pcmFrame = ByteArray(frameBytes)
                val encFrames = ArrayDeque<ByteArray>(256)
                val lock = Any()
                coroutineScope {
                    val producer = launch(Dispatchers.IO) {
                        while (isActive) {
                            if (stopNow) break
                            val n = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                recorder.read(pcmFrame, 0, pcmFrame.size, AudioRecord.READ_BLOCKING)
                            } else {
                                recorder.read(pcmFrame, 0, pcmFrame.size)
                            }
                            if (n <= 0) continue
                            if (n != frameBytes) {
                                log("PTT WARN partial read=$n expected=$frameBytes")
                                continue
                            }

                            val enc = codec.encoder(pcmFrame)
                            if (enc == null) {
                                log("PTT WARN encoder returned null frame in=${pcmFrame.size}")
                                continue
                            }
                            if (enc.size != encodedFrame) {
                                log("PTT WARN encoder size=${enc.size} expected=$encodedFrame in=${pcmFrame.size}")
                                continue
                            }
                            synchronized(lock) {
                                encFrames.addLast(enc.copyOf())
                                val maxQueuedFrames = 50
                                while (encFrames.size > maxQueuedFrames) {
                                    encFrames.removeFirst()
                                }
                            }
                        }
                    }

                    val sender = launch(Dispatchers.IO) {
                        while (isActive) {
                            val chunk = synchronized(lock) {
                                if (encFrames.size < 5) {
                                    null
                                } else {
                                    // 5 * 12 = 60
                                    val out = ByteArray(60)
                                    var p = 0
                                    repeat(5) {
                                        val f = encFrames.removeFirst()
                                        System.arraycopy(f, 0, out, p, encodedFrame)
                                        p += encodedFrame
                                    }
                                    out
                                }
                            }
                            if (chunk == null) {
                                if (stopNow) break
                                delay(5)
                                continue
                            }
                            if (stopNow) break
                            pacePttPacket()
                            sendPayload(byteArrayOf(0x02, 0x04, 0x03, 0x00, 0x00) + chunk, "PTT DATA 60")
                        }

                        // Official trace shows the tail can be a 4-frame packet (len=61).
                        val tail = synchronized(lock) {
                            if (encFrames.size < 4) {
                                null
                            } else {
                                val out = ByteArray(encodedFrame * 4)
                                var p = 0
                                repeat(4) {
                                    val f = encFrames.removeFirst()
                                    System.arraycopy(f, 0, out, p, encodedFrame)
                                    p += encodedFrame
                                }
                                out
                            }
                        }
                        if (tail != null) {
                            pacePttPacket()
                            sendPayload(byteArrayOf(0x02, 0x04, 0x03, 0x00, 0x00) + tail, "PTT DATA 48 TAIL")
                        }
                    }

                    try {
                        while (isActive) delay(50)
                    } finally {
                        sender.cancelAndJoin()
                        producer.cancelAndJoin()
                    }
                }
            } catch (t: Throwable) {
                log("PTT ERR ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                runCatching { recorder?.stop() }
                runCatching { recorder?.release() }
                withContext(NonCancellable) { delay(10) }
            }
        }
    }

    fun stop() {
        stopNow = true
        job?.cancel()
        job = null
    }

    suspend fun stopAndJoin() {
        stopNow = true
        val j = job
        if (j != null) {
            j.cancelAndJoin()
        }
        job = null
    }

    private suspend fun pacePttPacket() {
        // Match official hci-16 pacing: roughly one 65-byte packet every ~100ms.
        val now = System.currentTimeMillis()
        if (lastPacketTxMs != 0L) {
            val gap = now - lastPacketTxMs
            val waitMs = 100L - gap
            if (waitMs > 0) delay(waitMs)
        }
        lastPacketTxMs = System.currentTimeMillis()
    }
}
