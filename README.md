<p align="center">
  <a href="#english"><b>English</b></a>
  ·
  <a href="#中文"><b>中文</b></a>
</p>

---

<a id="english"></a>

# AT2 HT

AT2 HT is an open-source Android control application for the Baofeng / ALERVITES AT2 handheld radio.

This project implements the AT2 BLE control and off-network messaging protocol, and replaces the closed-source media libraries used by the official Ola Radio app with open-source implementations:

- Replaces the official `libtalkie.so` with OpenCORE-AMR for AMR-NB MR475 voice encoding and decoding;
- Replaces the official `libgojni.so` image processing chain with an open-source Go implementation, with bit-exact compatibility against the original closed-source library output;
- Supports BLE control, channel programming, text / image / voice / location messages, real-time PTT, and SmartLink control.

This project does not require an Ola Radio account or any official server for normal local use.

This project is developed with Android Studio.


## Technical features

- Complete implementation of the AT2 BLE-side control and messaging protocol;
- Identified and verified that the AT2 / Baofeng GMSK voice path uses AMR-NB MR475;
- OpenCORE-AMR generated MR475 voice frames have been successfully decoded by real AT2 hardware;
- AT2 BLE voice frames are 12 bytes / 20 ms, corresponding to the 12-byte AMR-NB MR475 IETF payload with the 1-byte ToC/header removed;
- Open-source Go implementation reproduces the official image processing pipeline, with bit-exact output compared with the original closed-source `libgojni.so`.


## AT2 radio topology

```text
Main MCU (JieLi AC696X)
│   ├─── BLE connection
│   ├─── A2DP / HFP connection
│   └─── Controls UCChip UC8288
RF transceiver SoC (UCChip UC8288)
    ├─── Analog radio
    ├─── GMSK digital radio
    └─── AMR-NB MR475 voice codec
```


## Features

- BLE scanning, connection, known-device management, and automatic reconnection
- BLE control home page
  - Current channel display
  - Dual-watch status and A/B focus switching
  - Quick switching for 30 channels
  - Common function settings
- Channel read/write
  - Read 30 channels
  - Edit a single channel
  - Write or clear a single channel
  - Write the full channel table
- Off-network communication
  - Text messages
  - Image messages
  - Voice messages
  - Location messages
  - SOS messages
  - Vibration alert on receive
- Real-time PTT
  - Real-time receive and playback in chat mode
  - Transmit and receive on the PTT page
- SmartLink
  - Enable / disable control
  - Main PTT long-press mapping settings


## Current scope and limitations

This project focuses on the AT2 BLE-side daily-use functions, including control, channel programming, off-network messages, real-time PTT, and SmartLink.

The following are currently outside the scope of this project:

- USB-C channel programming and firmware update protocol;
- AC696X / UC8288 firmware modification;
- Direct control of non-BLE Baofeng GMSK 16-channel radios;
- Full RF air-interface decoder, FEC, and interleaving reverse engineering.

Firmware updates should still be performed with the official PC CPS.


## How to obtain the official CPS

This project does not redistribute the official CPS installer. If you need the official CPS for firmware updates, USB-C programming, or other official maintenance operations, obtain the download link from the official Ola Radio app.

Steps:

1. Open the official Ola Radio app.
2. Connect to the AT2 radio.
3. Tap the three-dot menu in the top-right corner of the device card.
4. Select `Device Detail`.
5. In the `Device Detail` page, tap `Firmware Update Tool`.
6. Copy the Google Drive link shown by the app.
7. Download and install the CPS package from that link.

After installation, the CPS frontend resources are located at:

```text
<install-dir>\Bluetooth_User_Series\resources\app\app\dist\assets
```

If you want to use the CPS dealer UI patch script provided by this repository (`tools\CPS_mod`), place the script into the `assets` directory above, in the same directory as `index-*.js`, and then run it.


## Requirements

- Minimum Android version: Android 8.1 (API 27)
- Device with audio input/output, BLE, and location capability


## Runtime permissions

The app requests the following permissions depending on Android version:

- Bluetooth scan
- Bluetooth connect
- Location
- Audio recording
- Storage access
- Vibration


## Compatibility

Tested device:

| Device | BLE control | Channel programming | Text | Image | Voice message | Real-time PTT | SmartLink |
|---|---|---|---|---|---|---|---|
| Baofeng / ALERVITES AT2 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

Other low-cost Baofeng GMSK radios like **AD-888SD and AD-V1D** are not supported by this app. These radios can talk with the AT2 in GMSK digital voice mode, but the digital channel parameters must match completely and the radios must also have matching `subSystemId` values.

`subSystemId` is a hidden parameter, determined by the radio dealer. It is not channel-specific; each radio has only one such value. Two radios must have the same value to interoperate in digital mode. Analog mode is not affected.

For these GMSK radios, to modify the `subSystemId`, please check [byf3332/Baofeng-GMSK-Walkie-Research](https://github.com/byf3332/Baofeng-GMSK-Walkie-Research/). An online doc is also avaliable at [Doc_Baofeng-GMSK-Walkie-Research](https://byf3332.github.io/Baofeng-GMSK-Walkie-Research/en/). For **AD-888SD and AD-V1D**, The `subSystemId` can be checked as follows: power off the radio, turn the channel knob to channel 3, hold PTT and SK1, and power on the radio. The voice prompt will say `X Power On (Encrypt) 3`, where `X` is a number, which is the `subSystemId`. Currently, apart from the AT2, the protocol has only been tested on **AD-888SD and AD-V1D**.

For the AT2, see the README under `tools\CPS_mod`. After patching the CPS to expose the dealer setting, the AT2 `subSystemId` can be changed.


## Project structure

```text
app/src/main/java/com/byf3332/at2ht
├─ core/ble         BLE session and connection
├─ core/protocol    AT2 protocol encoding/decoding and state parsing
├─ core/chat        Image, voice, and message playback
├─ core/ptt         Real-time PTT transmit and receive
├─ widget           Custom widgets
└─ *.kt             Page controllers and UI coordination layer
```

Native and Go source:

```text
app/src/main
├─ cpp
│  ├─ opencore-amr/    vendored OpenCORE-AMR 0.1.3
│  ├─ CMakeLists.txt
│  └─ talkie_jni.cpp
├─ go
│  ├─ resizer/         Image resizing implementation and golden tests
│  ├─ go.mod
│  ├─ go.sum
│  └─ build-android.ps1
└─ jniLibs/arm64-v8a
   └─ libgojni.so      Prebuilt artifact generated from this project's Go source
```

Main entry points:

- `MainActivity.kt`: main UI assembly and controller initialization
- `BleEventController.kt`: BLE response dispatch
- `ChatFlowController.kt`: off-network communication receive path
- `ChatInteractionController.kt`: off-network communication send path
- `ReadWriteUiController.kt`: channel editing and write-back
- `SmartLinkUiController.kt`: SmartLink page control


## Open-source `libtalkie.so` replacement

The official Ola Radio app uses `libtalkie.so` to encode and decode AT2 voice data. This project keeps the original JNI boundary unchanged and uses OpenCORE-AMR as a compatible replacement.

The Kotlin entry point remains:

- `app/src/main/java/com/ucchip/sdk/codec/talkie/TalkieCodec.kt`

Native implementation:

- `app/src/main/cpp/talkie_jni.cpp`
- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/cpp/opencore-amr`

Current fixed contract:

- `getFrameSize() = 320`
- `getEncodedFrameSize() = 12`
- `getSampleRate() = 8000`
- `getFrameDuration() = 20`
- `encoder()`: `320-byte PCM16LE @ 8 kHz -> 12-byte AT2 payload`
- `decoder()`: `12-byte AT2 payload -> 320-byte PCM16LE @ 8 kHz`

AT2 voice frames are 20 ms each. AMR-NB MR475 is 13 bytes in IETF storage format:

```text
[1-byte ToC/header][12-byte MR475 payload]
```

`0x04` means an MR475 good speech frame. The 12-byte voice frame transmitted by the AT2 BLE protocol is equivalent to the MR475 payload with the ToC/header removed. For decoding, simply prepend `0x04` to the 12-byte AT2 payload.

This project does not include the original closed-source `libtalkie.so` from Ola Radio.


## Open-source `libgojni.so` replacement

The official Ola Radio app uses a GoMobile native library to process images. This project reimplements that image processing chain and generates a new `libgojni.so` using open-source components.

Current pipeline:

```text
Android reads image
-> rotate according to EXIF orientation
-> re-encode as Android JPEG quality 100
-> Go image.Decode
-> nfnt/resize Lanczos3 resize, max long side 300 px
-> Go image/jpeg quality 75 encode
```

The implementation has passed golden-sample testing and can produce byte-for-byte identical output compared with the original closed-source `libgojni.so`. See `tools/verification/README.md` for details.

The `app/src/main/jniLibs/arm64-v8a/libgojni.so` file in this repository is a prebuilt artifact generated from this project's Go source. It is not the original closed-source library from Ola Radio.


## Build

### 1. Install tools

- Android Studio with bundled JDK
- Android SDK Platform 36
- Android SDK Build-Tools 36.1.0 or compatible version
- CMake 3.22.1
- Android NDK `28.2.13676358`
- Go `1.24.0` only if rebuilding `libgojni.so`
- gomobile `v0.0.0-20250305212854-3a7bc9f8a4de` only if rebuilding `libgojni.so`

The Android components above can be installed from **Android Studio -> SDK Manager -> SDK Tools -> Show Package Details**.

After cloning the repository, open the project with Android Studio and let the IDE generate `local.properties`. This file should not be committed.

Developers who need to rebuild the Go library should install Go themselves. Go, gomobile, Android SDK, and build caches may be placed on any disk. All paths are configured in:

`app/src/main/go/toolchain.properties`

```properties
GO_ROOT=/path/to/go
GO_TOOLS_ROOT=/path/to/go-tools
ANDROID_SDK_ROOT=/path/to/Android/Sdk
BUILD_CACHE_ROOT=/path/to/build-cache
```

When moving to another computer, only these four variables need to be changed.

The following example places gomobile and its install-time cache on drive D:. The directories should match `toolchain.properties`:

```powershell
$env:GOPATH='D:\path\to\go-tools\install-gopath'
$env:GOMODCACHE='D:\path\to\go-tools\install-gopath\pkg\mod'
$env:GOCACHE='D:\path\to\go-tools\install-cache'
$env:GOBIN='D:\path\to\go-tools\bin'
$env:TEMP='D:\path\to\go-tools\tmp'
$env:TMP=$env:TEMP
$env:GOTELEMETRY='off'
& 'D:\path\to\go\bin\go.exe' install golang.org/x/mobile/cmd/gomobile@v0.0.0-20250305212854-3a7bc9f8a4de
& 'D:\path\to\go\bin\go.exe' install golang.org/x/mobile/cmd/gobind@v0.0.0-20250305212854-3a7bc9f8a4de
```

Repository scripts do not install dependencies and do not modify system or user environment configuration. They only temporarily set `PATH` inside their own process so that gomobile can find Go, and restore the original value before exiting.

All writable caches are placed under `BUILD_CACHE_ROOT/libgojni/`, configurable through `toolchain.properties`:

- `GOPATH` and Go module cache
- Go build cache
- `TEMP` / `TMP`
- Android user home

`.toolchains/` is included in `.gitignore` and can be deleted at any time without polluting the source tree or user directories.


### 2. Generate the open-source `libgojni.so` when needed

Windows PowerShell:

```powershell
cd app/src/main/go
./build-android.ps1
```

Linux or Git Bash:

```bash
cd app/src/main/go
bash ./build-android.sh
```

The script performs the following steps:

1. Checks the installed Go 1.24.0 and gomobile versions.
2. Resolves pinned dependencies from `go.mod` / `go.sum`.
3. Generates an Android AAR for `arm64-v8a`, API 27.
4. Extracts `jni/arm64-v8a/libgojni.so` to `app/src/main/jniLibs/arm64-v8a/`.

For complete Windows/Linux build instructions, see `docs/BUILDING.md`.

> The repository already contains the generated `libgojni.so`. A normal Android Studio build does not require Go.


## License

This project is released under the Apache License 2.0. See the root `LICENSE` file for the full text.

Third-party components keep their own licenses, including:

- OpenCORE-AMR 0.1.3: located at `app/src/main/cpp/opencore-amr/`, with its upstream `LICENSE` and `opencore/NOTICE` retained;
- `github.com/nfnt/resize`: version pinned in `app/src/main/go/go.mod`, upstream uses an ISC-style license;
- `golang.org/x/mobile` / gomobile: version pinned in `app/src/main/go/go.mod` and the build scripts, upstream uses BSD-3-Clause;
- AndroidX, Material Components, JUnit, and other build dependencies: distributed under their respective upstream licenses.

Complete third-party attribution and distribution notes are available in the repository root:

- `NOTICE`
- `THIRD_PARTY_NOTICES.md`

Notes:

- `app/src/main/cpp/opencore-amr/opencore/NOTICE` must be retained when distributing source code that contains this component;
- The upstream OpenCORE-AMR notice includes patent-related reminders for AMR / AMR-WB standard implementations. Users should evaluate compliance requirements in their own jurisdictions.


## Debugging suggestions

- BLE and protocol logs are only printed in `debug` builds.
- After modifying protocol-related logic, test these scenarios first:
  - Scan and connect
  - BLE control page state reading
  - Channel read/write
  - Off-network text, image, and voice messages
  - Real-time PTT
  - SmartLink enable/disable and main PTT mapping


## Notice

Make sure that your frequency, power, and transmission behavior comply with the radio regulations in your jurisdiction.

This project is not affiliated with Baofeng, ALERVITES, Ola Radio, UCChip, or any related manufacturer or dealer.

Firmware update, USB-C programming, and AC696X / UC8288 firmware modification are outside the scope of this project. Firmware updates should still be performed with the official PC CPS.


---

<a id="中文"></a>

# AT2 HT

AT2 HT 是一个面向 Baofeng / ALERVITES AT2 手台的开源 Android 控制应用。

本项目实现了 AT2 的 BLE 控制与无网通信协议，并使用开源实现替代官方 Ola Radio App 中的闭源媒体库：

- 使用 OpenCORE-AMR 替代官方 `libtalkie.so`，实现 AMR-NB MR475 语音编解码；
- 使用开源 Go 图像处理链替代官方 `libgojni.so`，并与原始闭源库输出达到 bit-exact 兼容；
- 支持蓝牙控制、读写频、文本 / 图片 / 语音 / 定位消息、实时 PTT 与 SmartLink 控制。

本项目不依赖 Ola Radio 账号，也不需要官方服务器即可使用。

本项目使用 Android Studio 开发。


## 技术特性

- 完整实现 AT2 BLE 侧控制与消息协议；
- 识别并验证 AT2 / Baofeng GMSK 语音链路使用 AMR-NB MR475；
- OpenCORE-AMR 生成的 MR475 语音帧已通过真实 AT2 硬件解码测试；
- AT2 BLE 语音帧为 12 bytes / 20 ms，对应 AMR-NB MR475 IETF payload 去除 1-byte ToC/header 后的 12-byte payload；
- 使用开源 Go 实现复现官方图片处理流程，输出与原闭源 `libgojni.so` bit-exact。


## AT2 对讲机拓扑

```text
主 MCU（杰理 AC696X）
│   ├─── BLE 连接
│   ├─── A2DP / HFP 连接
│   └─── 控制御芯微 UC8288
射频收发器 SoC（御芯微 UC8288）
    ├─── 模拟对讲
    ├─── GMSK 数字对讲
    └─── AMR-NB MR475 语音编解码
```


## 功能

- 蓝牙扫描、连接、已知设备管理、自动回连
- 蓝牙控制主页
  - 当前信道显示
  - 双频守候状态与 A/B 焦点切换
  - 30 个信道快捷切换
  - 常用功能设置
- 读写频
  - 30 信道读取
  - 单信道编辑
  - 单信道写入与清空
  - 整表写入
- 无网通信
  - 文本消息
  - 图片消息
  - 语音消息
  - 定位消息
  - 求助消息
  - 接收振动提醒
- 实时 PTT
  - 聊天模式下实时接收播放
  - PTT 页面发射与接收
- SmartLink
  - 开关控制
  - 主 PTT 长按映射设置


## 当前范围与限制

当前项目聚焦 AT2 的 BLE 侧日常功能，包括控制、写频、无网消息、实时 PTT 与 SmartLink。

以下内容暂不在本项目范围内：

- USB-C 写频与固件升级协议；
- AC696X / UC8288 固件修改；
- 非 BLE Baofeng GMSK 16 信道机的直接控制；
- RF 空口完整解码器、FEC 与交织逆向。

固件升级建议继续使用官方 PC CPS。


## 官方 CPS 获取方式

本项目不重新分发官方 CPS 安装包。如需使用官方 CPS 进行固件升级、USB-C 写频或其他官方维护操作，可以从官方 Ola Radio App 获取下载链接。

获取方式：

1. 打开官方 Ola Radio App。
2. 连接 AT2 手台。
3. 在设备卡片右上角点击三个点菜单。
4. 选择 `Device Detail`。
5. 在 `Device Detail` 页面点击 `Firmware Update Tool`。
6. 复制弹出的 Google Drive 链接。
7. 从该链接下载 CPS 安装包并安装。

安装后 CPS 的前端资源位于：

```text
<install-dir>\Bluetooth_User_Series\resources\app\app\dist\assets
```

如果需要使用本仓库提供的 CPS dealer UI patch 脚本（`tools\CPS_mod`），应将脚本放入上述 `assets` 目录，也就是与 `index-*.js` 位于同一目录后再执行。


## 运行要求

- 最低 Android 版本：8.1（API 27）
- 拥有音频输入输出（扬声器、麦克风）、蓝牙 BLE、定位功能


## 运行权限

应用运行时会按系统版本申请以下权限：

- 蓝牙扫描
- 蓝牙连接
- 定位
- 录音
- 存储空间访问
- 振动


## 兼容性

已测试设备：

| 设备 | BLE 控制 | 读写频 | 文本 | 图片 | 语音消息 | 实时 PTT | SmartLink |
|---|---|---|---|---|---|---|---|
| Baofeng / ALERVITES AT2 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

本应用不支持其他廉价宝锋 GMSK 对讲机（如 **AD-888SD 和 AD-V1D**）。虽然这些对讲机可以在 GMSK 数字语音模式下与 AT2 通话，但前提是数字信道参数必须完全匹配，且对讲机的 `subSystemId`（子网 ID）值也必须一致。

`subSystemId` 是一个由经销商设定的隐藏参数。它不针对特定信道，而是每台对讲机仅有一个该数值。两台对讲机必须具有相同的 `subSystemId` 才能在数字模式下互通；模拟模式不受此影响。

若需修改这些 GMSK 对讲机的 `subSystemId`，请参阅 [byf3332/Baofeng-GMSK-Walkie-Research](https://github.com/byf3332/Baofeng-GMSK-Walkie-Research/)。您也可以访问在线文档：[Doc_Baofeng-GMSK-Walkie-Research](https://byf3332.github.io/Baofeng-GMSK-Walkie-Research/en/)。对于 **AD-888SD** 和 **AD-V1D**，查看 `subSystemId` 的方法如下：关闭对讲机电源，将信道旋钮转至信道 3，同时按住 PTT 键和 SK1 键并开机。此时会听到语音提示 `X 开机 (加密) 3`，其中的数字 `X` 即为 `subSystemId`。目前，除 AT2 外，仅针对 **AD-888SD** 和 **AD-V1D** 的通信协议进行了测试。

对于 AT2，参考 `tools\CPS_mod` 下的 README.md，对 CPS 进行修改，暴露经销商设置以后即可修改 `subSystemId`。


## 项目结构

```text
app/src/main/java/com/byf3332/at2ht
├─ core/ble         BLE 会话与连接
├─ core/protocol    AT2 协议编解码与状态解析
├─ core/chat        图片、语音、消息播放
├─ core/ptt         实时 PTT 发送与接收
├─ widget           自定义控件
└─ *.kt             页面控制器与界面协调层
```

Native 与 Go 源码：

```text
app/src/main
├─ cpp
│  ├─ opencore-amr/    vendored OpenCORE-AMR 0.1.3
│  ├─ CMakeLists.txt
│  └─ talkie_jni.cpp
├─ go
│  ├─ resizer/         图片缩放实现与黄金测试
│  ├─ go.mod
│  ├─ go.sum
│  └─ build-android.ps1
└─ jniLibs/arm64-v8a
   └─ libgojni.so      由本项目 Go 源码生成的预构建产物
```

主要入口：

- `MainActivity.kt`：主界面装配与控制器初始化
- `BleEventController.kt`：BLE 回包分发
- `ChatFlowController.kt`：无网通信接收链
- `ChatInteractionController.kt`：无网通信发送链
- `ReadWriteUiController.kt`：读写频编辑与写回
- `SmartLinkUiController.kt`：SmartLink 页面控制


## `libtalkie.so` 开源替换

官方 Ola Radio App 使用 `libtalkie.so` 对 AT2 的语音数据进行编解码。本项目保持原 JNI 边界不变，并使用 OpenCORE-AMR 实现兼容替代。

Kotlin 入口保持为：

- `app/src/main/java/com/ucchip/sdk/codec/talkie/TalkieCodec.kt`

Native 实现位于：

- `app/src/main/cpp/talkie_jni.cpp`
- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/cpp/opencore-amr`

当前固定契约：

- `getFrameSize() = 320`
- `getEncodedFrameSize() = 12`
- `getSampleRate() = 8000`
- `getFrameDuration() = 20`
- `encoder()`：`320-byte PCM16LE @ 8 kHz -> 12-byte AT2 payload`
- `decoder()`：`12-byte AT2 payload -> 320-byte PCM16LE @ 8 kHz`

AT2 的语音帧为 20 ms 一帧。AMR-NB MR475 在 IETF storage format 中为 13 bytes：

```text
[1-byte ToC/header][12-byte MR475 payload]
```

其中 `0x04` 表示 MR475 good speech frame。AT2 BLE 协议中传输的 12-byte 语音帧等价于去掉 ToC/header 后的 MR475 payload。解码时只需在 12-byte AT2 payload 前补回 `0x04`。

本项目不包含官方 Ola Radio 的原始闭源 `libtalkie.so`。


## `libgojni.so` 开源替换

官方 Ola Radio App 使用 GoMobile native 库处理图片。本项目重新实现该图片处理链，并使用开源组件生成新的 `libgojni.so`。

当前流程为：

```text
Android 读取图片
-> 按 EXIF orientation 旋转
-> Android JPEG quality 100 重编码
-> Go image.Decode
-> nfnt/resize Lanczos3 缩放，长边最大 300 px
-> Go image/jpeg quality 75 编码
```

该实现已通过黄金样本测试，输出可与原始闭源 `libgojni.so` 达到 byte-for-byte 一致。具体请参考 `tools/verification/README.md`。

本项目仓库中的 `app/src/main/jniLibs/arm64-v8a/libgojni.so` 是由本项目 Go 源码生成的预构建产物，不是官方 Ola Radio 的原始闭源库。


## 开始构建

### 1. 安装工具

- Android Studio（附带 JDK）
- Android SDK Platform 36
- Android SDK Build-Tools 36.1.0 或兼容版本
- CMake 3.22.1
- Android NDK `28.2.13676358`
- Go `1.24.0`（仅重新生成 `libgojni.so` 时需要）
- gomobile `v0.0.0-20250305212854-3a7bc9f8a4de`（仅重新生成 `libgojni.so` 时需要）

以上 Android 组件可在 Android Studio 的 **SDK Manager → SDK Tools → Show Package Details** 中安装。

克隆仓库后用 Android Studio 打开项目，让 IDE 生成 `local.properties`。该文件不应提交。

需要重建 Go 库的开发者应自行安装 Go。Go、gomobile、Android SDK 和构建缓存都可以位于用户选择的任意磁盘。所有路径集中配置在：

`app/src/main/go/toolchain.properties`

```properties
GO_ROOT=/path/to/go
GO_TOOLS_ROOT=/path/to/go-tools
ANDROID_SDK_ROOT=/path/to/Android/Sdk
BUILD_CACHE_ROOT=/path/to/build-cache
```

更换电脑时只需修改以上四个变量。

以下示例把 gomobile 及安装时产生的缓存放在 D 盘；目录应与 `toolchain.properties` 保持一致：

```powershell
$env:GOPATH='D:\path\to\go-tools\install-gopath'
$env:GOMODCACHE='D:\path\to\go-tools\install-gopath\pkg\mod'
$env:GOCACHE='D:\path\to\go-tools\install-cache'
$env:GOBIN='D:\path\to\go-tools\bin'
$env:TEMP='D:\path\to\go-tools\tmp'
$env:TMP=$env:TEMP
$env:GOTELEMETRY='off'
& 'D:\path\to\go\bin\go.exe' install golang.org/x/mobile/cmd/gomobile@v0.0.0-20250305212854-3a7bc9f8a4de
& 'D:\path\to\go\bin\go.exe' install golang.org/x/mobile/cmd/gobind@v0.0.0-20250305212854-3a7bc9f8a4de
```

仓库脚本不会安装依赖，也不会修改系统或用户环境配置。它只在自身进程中临时设置 PATH，以满足 gomobile 查找 go 的要求，并在退出前恢复原值。

以下所有可写缓存都集中到 `BUILD_CACHE_ROOT/libgojni/`，用户可通过 `toolchain.properties` 自行选择位置：

- `GOPATH` 与 Go module cache
- Go build cache
- `TEMP` / `TMP`
- Android user home

`.toolchains/` 已加入 `.gitignore`，可随时整体删除，不会污染源码或用户目录。


### 2. 生成开源 `libgojni.so`（首次发布或修改 Go 源码时）

Windows PowerShell：

```powershell
cd app/src/main/go
./build-android.ps1
```

Linux 或 Git Bash：

```bash
cd app/src/main/go
bash ./build-android.sh
```

脚本进行以下操作：

1. 检查用户已安装的 Go 1.24.0 与 gomobile。
2. 从 `go.mod` / `go.sum` 解析固定版本依赖。
3. 为 Android `arm64-v8a`、API 27 生成 AAR。
4. 提取 `jni/arm64-v8a/libgojni.so` 到 `app/src/main/jniLibs/arm64-v8a/`。

完整的 Windows/Linux 构建说明见 `docs/BUILDING.md`。

> 仓库已经包含生成后的 `libgojni.so`，普通 Android Studio 构建不需要 Go。


## 开源许可证

本项目整体以 Apache License 2.0 开源，许可证全文位于仓库根目录的 `LICENSE` 文件。

第三方组件仍分别保留各自许可证，主要包括：

- OpenCORE-AMR 0.1.3：位于 `app/src/main/cpp/opencore-amr/`，保留其上游 `LICENSE` 与 `opencore/NOTICE`；
- `github.com/nfnt/resize`：版本固定在 `app/src/main/go/go.mod`，上游采用 ISC 风格许可证；
- `golang.org/x/mobile` / gomobile：版本固定在 `app/src/main/go/go.mod` 与构建脚本，上游采用 BSD-3-Clause 许可证；
- AndroidX、Material Components、JUnit 等构建依赖：按各自上游许可证分发。

完整第三方归属与分发说明见仓库根目录：

- `NOTICE`
- `THIRD_PARTY_NOTICES.md`

注意：

- `app/src/main/cpp/opencore-amr/opencore/NOTICE` 必须随包含该组件的源码分发一起保留；
- OpenCORE-AMR 的上游说明中包含与 AMR / AMR-WB 标准实现相关的专利提醒，使用者应自行评估所在法域的合规要求。


## 调试建议

- BLE 和协议相关日志仅在 `debug` 构建中输出。
- 修改协议相关逻辑后，优先验证以下场景：
  - 扫描与连接
  - 蓝牙控制页状态读取
  - 读写频读写
  - 无网通信文本、图片、语音
  - 实时 PTT
  - SmartLink 开关与主 PTT 映射


## 注意事项

请确保使用频率、功率和发射行为符合所在地区的无线电法规。

本项目与 Baofeng、ALERVITES、Ola Radio、UCChip 等厂商无官方关联。

固件升级、USB-C 写频、AC696X / UC8288 固件修改不属于本项目范围。固件升级建议继续使用官方 PC CPS。