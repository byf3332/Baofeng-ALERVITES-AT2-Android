#include <jni.h>
#include <cstdint>
#include <cstring>
#include <memory>
#include <vector>

#include "interf_dec.h"
#include "interf_enc.h"

namespace {
constexpr jint kFrameSizeBytes = 320;
constexpr jint kEncodedFrameSizeBytes = 12;
constexpr jint kSampleRate = 8000;
constexpr jint kFrameDurationMs = 20;
constexpr unsigned char kMr475Toc = 0x04;

struct CodecState {
    void* encoder = nullptr;
    void* decoder = nullptr;

    CodecState() {
        encoder = Encoder_Interface_init(0);
        decoder = Decoder_Interface_init();
    }

    ~CodecState() {
        if (encoder) {
            Encoder_Interface_exit(encoder);
            encoder = nullptr;
        }
        if (decoder) {
            Decoder_Interface_exit(decoder);
            decoder = nullptr;
        }
    }
};

jfieldID get_handle_field(JNIEnv* env, jobject thiz) {
    jclass cls = env->GetObjectClass(thiz);
    return env->GetFieldID(cls, "nativeHandle", "J");
}

CodecState* get_state(JNIEnv* env, jobject thiz) {
    jlong handle = env->GetLongField(thiz, get_handle_field(env, thiz));
    return reinterpret_cast<CodecState*>(handle);
}

CodecState* require_state(JNIEnv* env, jobject thiz) {
    CodecState* state = get_state(env, thiz);
    if (state) {
        return state;
    }
    auto fresh = std::make_unique<CodecState>();
    state = fresh.release();
    env->SetLongField(thiz, get_handle_field(env, thiz), reinterpret_cast<jlong>(state));
    return state;
}

void release_state(JNIEnv* env, jobject thiz) {
    const jfieldID field = get_handle_field(env, thiz);
    const jlong handle = env->GetLongField(thiz, field);
    if (handle == 0) {
        return;
    }
    env->SetLongField(thiz, field, 0);
    delete reinterpret_cast<CodecState*>(handle);
}

std::vector<int16_t> pcm_bytes_to_samples(JNIEnv* env, jbyteArray pcm) {
    const jsize len = env->GetArrayLength(pcm);
    std::vector<int16_t> samples(static_cast<size_t>(len / 2));
    std::vector<jbyte> bytes(static_cast<size_t>(len));
    env->GetByteArrayRegion(pcm, 0, len, bytes.data());
    for (jsize i = 0; i < len / 2; ++i) {
        const uint8_t lo = static_cast<uint8_t>(bytes[2 * i]);
        const uint8_t hi = static_cast<uint8_t>(bytes[2 * i + 1]);
        samples[static_cast<size_t>(i)] = static_cast<int16_t>(lo | (hi << 8));
    }
    return samples;
}

jbyteArray samples_to_pcm_bytes(JNIEnv* env, const int16_t* samples, size_t sample_count) {
    const jsize len = static_cast<jsize>(sample_count * 2);
    jbyteArray out = env->NewByteArray(len);
    if (!out) {
        return nullptr;
    }
    std::vector<jbyte> bytes(static_cast<size_t>(len));
    for (size_t i = 0; i < sample_count; ++i) {
        const uint16_t v = static_cast<uint16_t>(samples[i]);
        bytes[2 * i] = static_cast<jbyte>(v & 0xff);
        bytes[2 * i + 1] = static_cast<jbyte>((v >> 8) & 0xff);
    }
    env->SetByteArrayRegion(out, 0, len, bytes.data());
    return out;
}
}

extern "C" JNIEXPORT void JNICALL
Java_com_ucchip_sdk_codec_talkie_TalkieCodec_init(JNIEnv* env, jobject thiz) {
    release_state(env, thiz);
    auto state = std::make_unique<CodecState>();
    env->SetLongField(thiz, get_handle_field(env, thiz), reinterpret_cast<jlong>(state.release()));
}

extern "C" JNIEXPORT void JNICALL
Java_com_ucchip_sdk_codec_talkie_TalkieCodec_close(JNIEnv* env, jobject thiz) {
    release_state(env, thiz);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_ucchip_sdk_codec_talkie_TalkieCodec_encoder(JNIEnv* env, jobject thiz, jbyteArray pcm) {
    if (!pcm || env->GetArrayLength(pcm) != kFrameSizeBytes) {
        return nullptr;
    }
    CodecState* state = require_state(env, thiz);
    auto samples = pcm_bytes_to_samples(env, pcm);
    unsigned char encoded[32] = {0};
    const int produced = Encoder_Interface_Encode(state->encoder, MR475, samples.data(), encoded, 0);
    if (produced != kEncodedFrameSizeBytes + 1) {
        return nullptr;
    }
    jbyteArray out = env->NewByteArray(kEncodedFrameSizeBytes);
    if (!out) {
        return nullptr;
    }
    env->SetByteArrayRegion(out, 0, kEncodedFrameSizeBytes, reinterpret_cast<const jbyte*>(encoded + 1));
    return out;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_ucchip_sdk_codec_talkie_TalkieCodec_decoder(JNIEnv* env, jobject thiz, jbyteArray encoded) {
    if (!encoded || env->GetArrayLength(encoded) != kEncodedFrameSizeBytes) {
        return nullptr;
    }
    CodecState* state = require_state(env, thiz);
    unsigned char frame[kEncodedFrameSizeBytes + 1] = {0};
    frame[0] = kMr475Toc;
    env->GetByteArrayRegion(encoded, 0, kEncodedFrameSizeBytes, reinterpret_cast<jbyte*>(frame + 1));
    int16_t pcm[160] = {0};
    Decoder_Interface_Decode(state->decoder, frame, reinterpret_cast<short*>(pcm), 0);
    return samples_to_pcm_bytes(env, pcm, 160);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ucchip_sdk_codec_talkie_TalkieCodec_getFrameSize(JNIEnv*, jobject) {
    return kFrameSizeBytes;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ucchip_sdk_codec_talkie_TalkieCodec_getEncodedFrameSize(JNIEnv*, jobject) {
    return kEncodedFrameSizeBytes;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ucchip_sdk_codec_talkie_TalkieCodec_getSampleRate(JNIEnv*, jobject) {
    return kSampleRate;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ucchip_sdk_codec_talkie_TalkieCodec_getFrameDuration(JNIEnv*, jobject) {
    return kFrameDurationMs;
}
