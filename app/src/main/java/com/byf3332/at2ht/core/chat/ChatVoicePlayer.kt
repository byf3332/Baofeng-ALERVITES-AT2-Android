package com.byf3332.at2ht.core.chat

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.ucchip.sdk.codec.talkie.TalkieCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatVoicePlayer(
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val onStateChanged: (Long?, Boolean) -> Unit,
) {
    private var playJob: Job? = null
    private var currentMessageId: Long? = null
    private var audioTrack: AudioTrack? = null

    fun toggle(messageId: Long, encoded: ByteArray) {
        if (currentMessageId == messageId) {
            stop()
            return
        }
        stop()
        currentMessageId = messageId
        onStateChanged(messageId, true)
        playJob = scope.launch(Dispatchers.Default) {
            var codec: TalkieCodec? = null
            try {
                TalkieCodec.ensureLoaded()
                val activeCodec = TalkieCodec().apply { init() }
                codec = activeCodec
                val encodedFrameSize = activeCodec.getEncodedFrameSize()
                val sampleRate = activeCodec.getSampleRate()
                val frameSizeBytes = activeCodec.getFrameSize()

                val minBuffer = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ).coerceAtLeast(frameSizeBytes * 8)

                val track = AudioTrack(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                    minBuffer,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE,
                )
                audioTrack = track
                track.play()

                var offset = 0
                while (offset + encodedFrameSize <= encoded.size) {
                    if (!scope.coroutineContext[Job]!!.isActive) break
                    val frame = encoded.copyOfRange(offset, offset + encodedFrameSize)
                    val pcm = activeCodec.decoder(frame)
                    if (pcm != null && pcm.isNotEmpty()) {
                        track.write(pcm, 0, pcm.size)
                    }
                    offset += encodedFrameSize
                }
            } catch (t: Throwable) {
                log("CHAT VOICE ERR ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                runCatching { codec?.close() }
                withContext(Dispatchers.Main) {
                    releaseTrack()
                    val finishedId = currentMessageId
                    currentMessageId = null
                    onStateChanged(finishedId, false)
                }
            }
        }
    }

    fun stop() {
        playJob?.cancel()
        playJob = null
        val oldId = currentMessageId
        currentMessageId = null
        releaseTrack()
        if (oldId != null) onStateChanged(oldId, false)
    }

    private fun releaseTrack() {
        runCatching { audioTrack?.pause() }
        runCatching { audioTrack?.flush() }
        runCatching { audioTrack?.release() }
        audioTrack = null
    }
}
