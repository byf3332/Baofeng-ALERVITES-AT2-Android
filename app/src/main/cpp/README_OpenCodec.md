# OpenCodec Native Notes

## Purpose

This directory hosts the open-source replacement for the original closed-source
`libtalkie.so` used by the AT2 HT app clone.

The design goal is:

- keep the Kotlin/JNI boundary stable
- replace the native implementation fully
- preserve the app-facing frame contract

## Files

- `CMakeLists.txt`
  - builds vendored OpenCORE AMR-NB sources and the final `talkie` shared library
- `talkie_jni.cpp`
  - JNI compatibility layer for `TalkieCodec.kt`
- `opencore-amr`
  - vendored upstream codec source used for AMR-NB MR475 encode/decode

## App-facing contract

- frame size: `320` bytes PCM
- encoded frame size: `12` bytes
- sample rate: `8000`
- frame duration: `20` ms

## Internal contract

- encode path:
  - input: 160 samples / 320 bytes PCM
  - OpenCORE mode: `MR475`
  - OpenCORE output: 13-byte AMR IETF frame
  - app output: strip 1-byte TOC, return 12-byte payload
- decode path:
  - input: 12-byte payload
  - synthesize 1-byte TOC for `MR475`
  - OpenCORE decodes one frame
  - return 320-byte PCM

## Lifetime

`TalkieCodec.init()` owns one native `CodecState`, containing the OpenCORE
encoder and decoder handles. Call `TalkieCodec.close()` when the Kotlin owner
stops or leaves its coroutine. `close()` is idempotent: JNI clears
`nativeHandle` before deleting the state, and `CodecState` releases both
OpenCORE handles in its destructor.

The PTT sender/receiver, offline recorder, and chat voice player all close their
codec in their `finally` or `stop()` path.

## Optimization

OpenCORE-AMR and the JNI compatibility layer are compiled with `-O3` for every
Android build type. Fast-math is intentionally not enabled because it can alter
codec output. Native symbols are retained for crash diagnosis.

## Verification summary

Validated so far:

- JNI symbol names match the original `libtalkie.so`
- `debug` and `release` APKs both build successfully
- `debug` real-device test succeeded on an older Android device (`sdm845` class)
- AT2 radio hardware correctly decodes transmitted audio

## Scope

This native replacement currently targets only:

- `arm64-v8a`
- AMR-NB `MR475`

If future work expands ABI or codec behavior, update this document together with
`CMakeLists.txt` and `talkie_jni.cpp`.
