package com.byf3332.at2ht.core.ptt

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.ucchip.sdk.codec.talkie.TalkieCodec

class PttVoiceReceiver(
    private val log: (String) -> Unit,
) {
    private var codec: TalkieCodec? = null
    private var audioTrack: AudioTrack? = null
    private var encodedFrameSize: Int = 12
    private var sampleRate: Int = 8000
    private var frameSizeBytes: Int = 320

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        try {
            TalkieCodec.ensureLoaded()
            val c = TalkieCodec().apply { init() }
            codec = c
            encodedFrameSize = c.getEncodedFrameSize()
            sampleRate = c.getSampleRate()
            frameSizeBytes = c.getFrameSize()

            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(frameSizeBytes * 8)

            audioTrack = AudioTrack(
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
            ).apply { play() }

            started = true
            log("PTT RX start frameBytes=$frameSizeBytes enc=$encodedFrameSize sr=$sampleRate")
        } catch (t: Throwable) {
            log("PTT RX ERR ${t.javaClass.simpleName}: ${t.message}")
            stop()
        }
    }

    fun stop() {
        started = false
        runCatching { audioTrack?.pause() }
        runCatching { audioTrack?.flush() }
        runCatching { audioTrack?.release() }
        audioTrack = null
        codec = null
    }

    fun onVoicePacket(encodedPayload: ByteArray) {
        if (!started) start()
        val c = codec ?: return
        val track = audioTrack ?: return
        val frameLen = encodedFrameSize
        if (encodedPayload.size < frameLen) return

        var offset = 0
        while (offset + frameLen <= encodedPayload.size) {
            val frame = encodedPayload.copyOfRange(offset, offset + frameLen)
            val pcm = c.decoder(frame)
            if (pcm != null && pcm.isNotEmpty()) {
                track.write(pcm, 0, pcm.size)
            }
            offset += frameLen
        }
    }
}
