# Building AT2 HT OpenCodec

## Requirements

- Android Studio and JDK 17+
- Android SDK Platform 36 and Build-Tools 36.1.0 or compatible
- CMake 3.22.1 and Android NDK 28.2.13676358
- Go 1.24.0, gomobile, and gobind when rebuilding `libgojni.so`
- PowerShell on Windows, or Bash and `unzip` on Linux

## Configure the toolchain

Copy `app/src/main/go/toolchain.properties.example` to
`app/src/main/go/toolchain.properties`, then edit only:

```properties
GO_ROOT=/path/to/go
GO_TOOLS_ROOT=/path/to/go-tools
ANDROID_SDK_ROOT=/path/to/android-sdk
BUILD_CACHE_ROOT=/path/to/build-cache
```

The local properties file is ignored by Git. Tools and caches may be placed on
any drive or mount. Every writable Go, gomobile, Android-user, and temporary
build directory is redirected below `BUILD_CACHE_ROOT/libgojni`.

## Install the Go mobile tools

Install both commands at the pinned revision and place the resulting binaries
in `GO_TOOLS_ROOT/bin`:

```bash
go install golang.org/x/mobile/cmd/gomobile@v0.0.0-20250305212854-3a7bc9f8a4de
go install golang.org/x/mobile/cmd/gobind@v0.0.0-20250305212854-3a7bc9f8a4de
```

While installing, point `GOBIN`, `GOPATH`, `GOCACHE`, and the temporary
directory at locations selected for your machine.

## Rebuild libgojni.so

Windows PowerShell:

```powershell
cd app/src/main/go
.\build-android.ps1
```

Linux or Git Bash:

```bash
cd app/src/main/go
bash ./build-android.sh
```

Both scripts update `app/src/main/jniLibs/arm64-v8a/libgojni.so`. The AAR and
all intermediate files stay below `BUILD_CACHE_ROOT/libgojni`.

Go compiler optimization and inlining remain enabled. The scripts use
`-trimpath` for reproducible paths, never pass the debug-only `-N -l` flags,
and retain symbols for diagnosis.

## Build the app

The checked-in `libgojni.so` means ordinary Android builds do not require Go.
CMake builds `libtalkie.so` from `app/src/main/cpp/opencore-amr`.
Both OpenCORE-AMR and the JNI layer are explicitly compiled with `-O3` without
fast-math, including debug APKs used for real-time radio testing.

Windows:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```

Linux:

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

Android Studio can build and sign the project normally on either OS.
