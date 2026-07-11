package com.ucchip.sdk.codec.talkie

class TalkieCodec : AutoCloseable {
    @Suppress("unused")
    private var nativeHandle: Long = 0

    external fun init()
    external override fun close()
    external fun encoder(pcm: ByteArray): ByteArray?
    external fun decoder(encoded: ByteArray): ByteArray?
    external fun getFrameSize(): Int
    external fun getEncodedFrameSize(): Int
    external fun getSampleRate(): Int
    external fun getFrameDuration(): Int

    companion object {
        private var loaded = false
        fun ensureLoaded() {
            if (loaded) return
            System.loadLibrary("talkie")
            loaded = true
        }
    }
}
