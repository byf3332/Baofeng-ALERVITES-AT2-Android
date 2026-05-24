# AT2 HT

AT2 HT 是一个面向宝锋 ALTERVITES AT2 手台的 Android 控制应用。

当前实现围绕 BLE 连接、蓝牙控制、读写频、无网通信、实时 PTT 和 SmartLink 模式展开，适合直接在 Android Studio 中打开并继续开发。

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

## 开发环境

- Android Studio 最新稳定版
- JDK 11
- Android SDK 36
- 最低 Android 版本：8.1（API 27）

## 构建

在 Android Studio 中直接打开项目根目录：

- `D:\MyProjects\AndroidStudioProjects\AT2HT`

常用命令：

- 调试编译：`./gradlew :app:compileDebugKotlin`
- 调试打包：`./gradlew :app:assembleDebug`

Windows 下也可以使用：

- `gradlew.bat :app:compileDebugKotlin`
- `gradlew.bat :app:assembleDebug`

## 运行权限

应用运行时会按系统版本申请以下权限：

- 蓝牙扫描
- 蓝牙连接
- 定位
- 录音
- 图片读取
- 振动

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

主要入口：

- `MainActivity.kt`：主界面装配与控制器初始化
- `BleEventController.kt`：BLE 回包分发
- `ChatFlowController.kt`：无网通信接收链
- `ChatInteractionController.kt`：无网通信发送链
- `ReadWriteUiController.kt`：读写频编辑与写回
- `SmartLinkUiController.kt`：SmartLink 页面控制

## 代码组织说明

界面层已经按功能拆成控制器，当前主要包括：

- 设备连接
- 首页控制
- 聊天页
- 读写频
- 功能设置
- SmartLink
- PTT
- 权限与导航

协议、媒体处理和 BLE 通信分别位于 `core` 目录下。

## 调试建议

- BLE 和协议相关日志仅在 `debug` 构建中输出
- 遇到中文乱码时，优先检查终端或脚本是否使用 UTF-8
- 修改协议相关逻辑后，优先验证以下场景：
  - 扫描与连接
  - 蓝牙控制页状态读取
  - 读写频读写
  - 无网通信文本、图片、语音
  - 实时 PTT
  - SmartLink 开关与主 PTT 映射

## 当前仓库定位

该仓库面向 Android 客户端实现本身，重点是：

- AT2 BLE 协议接入
- 设备控制 UI
- 无网通信与实时 PTT 体验

