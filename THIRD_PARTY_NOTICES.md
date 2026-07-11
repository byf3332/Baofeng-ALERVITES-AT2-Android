# Third-Party Notices

This repository includes or depends on third-party software.
Each third-party component remains under its own license.
This file is a convenience summary and does not replace the original license text.

## Repository-vendored source code

### OpenCORE-AMR 0.1.3

- Location: `app/src/main/cpp/opencore-amr/`
- Upstream: OpenCORE-AMR / PacketVideo derived code
- License: Apache License 2.0
- Required files kept in this repository:
  - `app/src/main/cpp/opencore-amr/LICENSE`
  - `app/src/main/cpp/opencore-amr/opencore/NOTICE`

Notes:

- The vendored OpenCORE-AMR tree includes additional attribution text in its upstream `NOTICE` file for historically imported code and standards-derived material.
- That upstream `NOTICE` file must continue to be distributed with the vendored source and with derivative source distributions that include this component.
- The upstream OpenCORE-AMR notices also warn that use of AMR and AMR-WB implementations may implicate patent rights in some jurisdictions. This repository does not provide any separate patent license beyond the licenses granted by the respective upstream authors.

## Go source dependencies

### github.com/nfnt/resize

- Location in source: declared in `app/src/main/go/go.mod`
- Version pinned in this repository: `v0.0.0-20180221191011-83c6a9932646`
- License: ISC / MIT-style permissive license
- Role: Lanczos3 image resizing in the open-source `libgojni.so` replacement

### golang.org/x/mobile

- Location in source: declared in `app/src/main/go/go.mod`
- Version pinned in this repository: `v0.0.0-20250305212854-3a7bc9f8a4de`
- License: BSD 3-Clause
- Role: GoMobile tooling and bindings used to build `libgojni.so`

## Android and UI dependencies

The Android application directly declares the following third-party dependencies in
`app/build.gradle.kts` and `gradle/libs.versions.toml`.
These are not vendored as source in this repository, but they are part of normal builds.

### AndroidX family

- Components used directly:
  - `androidx.appcompat:appcompat`
  - `androidx.recyclerview:recyclerview`
  - `androidx.core:core-ktx`
  - `androidx.lifecycle:lifecycle-runtime-ktx`
  - `androidx.activity:activity-compose`
  - `androidx.compose:*`
  - `androidx.test.ext:junit`
  - `androidx.test.espresso:espresso-core`
- Typical upstream license: Apache License 2.0
- Role: Android framework extensions, UI, Compose UI, and Android test support

### Material Components for Android

- Component used directly: `com.google.android.material:material`
- Typical upstream license: Apache License 2.0
- Role: Material UI components

## Test-only dependencies

### JUnit 4

- Component used directly: `junit:junit:4.13.2`
- License: Eclipse Public License 1.0
- Role: JVM unit tests only

## Build-time tools

The project build also relies on standard external tools that are not redistributed in this repository,
including Android Studio, Android SDK, Android NDK, CMake, Go, and GoMobile tooling.
Those tools remain under their own respective licenses.

## Distribution guidance

When redistributing this repository or a derivative source distribution:

- Keep the repository root `LICENSE`.
- Keep the repository root `NOTICE`.
- Keep `app/src/main/cpp/opencore-amr/LICENSE`.
- Keep `app/src/main/cpp/opencore-amr/opencore/NOTICE`.
- Preserve third-party copyright and attribution notices in source files.
- Mark modified files clearly where required by the applicable upstream license.
