package com.byf3332.at2ht.core.chat

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import com.ucchip.sdk.codec.talkie.TalkieCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream

data class OfflineVoiceRecording(
    val encodedBytes: ByteArray,
    val durationMs: Int,
)

class OfflineVoiceRecorder(
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
) {
    private val stateMutex = Mutex()
    private var job: Job? = null
    private var startedAtMs: Long = 0L
    private var encoded = ByteArrayOutputStream()

    suspend fun start(): Boolean = stateMutex.withLock {
        if (job?.isActive == true) return false
        encoded = ByteArrayOutputStream()
        startedAtMs = System.currentTimeMillis()
        job = scope.launch(Dispatchers.IO) {
            var recorder: AudioRecord? = null
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                TalkieCodec.ensureLoaded()
                val codec = TalkieCodec().apply { init() }
                val frameBytes = codec.getFrameSize()
                val encodedFrameBytes = codec.getEncodedFrameSize()
                val sampleRate = codec.getSampleRate()
                val minBuffer = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(frameBytes * 4)
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuffer
                )
                recorder.startRecording()
                val pcmFrame = ByteArray(frameBytes)
                while (isActive) {
                    val read = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        recorder.read(pcmFrame, 0, pcmFrame.size, AudioRecord.READ_BLOCKING)
                    } else {
                        recorder.read(pcmFrame, 0, pcmFrame.size)
                    }
                    if (read != frameBytes) continue
                    val frame = codec.encoder(pcmFrame) ?: continue
                    if (frame.size != encodedFrameBytes) continue
                    synchronized(this@OfflineVoiceRecorder) {
                        encoded.write(frame)
                    }
                }
            } catch (t: Throwable) {
                log("OFFLINE VOICE REC ERR ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                runCatching { recorder?.stop() }
                runCatching { recorder?.release() }
            }
        }
        true
    }

    suspend fun cancel() {
        stateMutex.withLock {
            job?.cancelAndJoin()
            job = null
            encoded = ByteArrayOutputStream()
            startedAtMs = 0L
        }
    }

    suspend fun finish(): OfflineVoiceRecording? = stateMutex.withLock {
        val activeJob = job ?: return null
        activeJob.cancelAndJoin()
        job = null
        val durationMs = (System.currentTimeMillis() - startedAtMs).toInt().coerceAtLeast(0)
        val bytes = synchronized(this@OfflineVoiceRecorder) { encoded.toByteArray() }
        encoded = ByteArrayOutputStream()
        startedAtMs = 0L
        if (bytes.isEmpty() || durationMs <= 0) return null
        OfflineVoiceRecording(bytes, durationMs)
    }
}
