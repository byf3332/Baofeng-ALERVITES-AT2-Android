package com.ucchip.sdk.codec.talkie

class TalkieCodec {
    external fun init()
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
            // Bundled in app/src/main/jniLibs/*/libtalkie.so
            System.loadLibrary("talkie")
            loaded = true
        }
    }
}
