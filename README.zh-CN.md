# Thor Volume Link

简体中文 · [English](README.md)

[![Android CI](https://github.com/pth2000/ThorVolumeLink/actions/workflows/android.yml/badge.svg)](https://github.com/pth2000/ThorVolumeLink/actions/workflows/android.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-5.0%2B-3DDC84?logo=android&logoColor=white)](#版本区别)

Thor Volume Link 是一款面向双屏 Android 掌机 AYN Thor 的开源音量控制工具。它可以让实体音量键控制主屏、副屏，或按相对比例联动两块屏幕的音量。

## 功能特性

- 主屏、副屏和双屏联动三种音量键模式
- 直接控制副屏音量，并按相对比例同步两块屏幕
- 可选择是否使用实体键切换模式，并配置按键行为
- 实时显示主屏与副屏音量

## 版本区别

| 版本 | 包名 | 最低 Android 版本 | 副屏音量后端 | 适用场景 |
| --- | --- | ---: | --- | --- |
| Lite | `io.github.thorvolume.control.lite` | Android 5.0 / API 21 | 旧版 `Settings.System` 兼容路径 | 推荐优先使用，不需要 Shizuku |
| Standard | `io.github.thorvolume.control` | Android 6.0 / API 23 | [Shizuku](https://shizuku.rikka.app/zh-hans/) 或 Root 二选一 | Lite 无法安装或不兼容时使用 |

建议首先尝试 Lite。它只需要 Android 的“修改系统设置”授权，不需要安装或授权 Shizuku。Lite 有意将目标 SDK 保持在 22：AOSP 为目标版本不高于 Android 5.1 的应用保留了兼容路径，允许它们修改自定义 `Settings.System` 项；目标 SDK 更高的应用则会被系统拒绝。AYN 的 `secondary_screen_volume_level` 正是厂商自定义设置项。

相关依据可参阅 [AOSP SettingsProvider 实现](https://android.googlesource.com/platform/frameworks/base/+/master/packages/SettingsProvider/src/com/android/providers/settings/SettingsProvider.java)和 Android 官方的 [`WRITE_SETTINGS` 文档](https://developer.android.com/reference/android/provider/Settings.System#canWrite(android.content.Context))。

Lite 在较新的 Android 系统上可能出现旧版应用提示或安装限制。如果 Lite 无法安装，或兼容路径在当前固件上不可用，请改用 Standard。Standard 可选择 Shizuku 或 Root，但应用只会启用当前选中的一种方式。两个版本可以同时安装，但不要同时启用它们的无障碍按键服务。

## 使用方法

1. 优先安装 Lite。
2. 打开 Thor Volume Link，允许应用修改系统设置。
3. 在 Android 无障碍设置中启用本应用的按键服务。
4. 打开**设置 → 按键与控制**，选择是否使用实体键切换模式。
5. 在主页面选择主屏、副屏或双屏联动模式。

如果 Lite 不可用，请安装 Standard，并在首页选择 Shizuku 或 Root；默认使用 Shizuku。只有用户明确选择并确认 Root 后，应用才会请求 Root 权限。切换到 Root 会停止 Shizuku UserService，切回 Shizuku 则会关闭 Root Shell。

关闭实体键模式切换后，已配置的模式键会恢复原有系统行为；音量增加和降低键仍会按照应用内选定的模式工作。

## 隐私与权限

无障碍服务只请求按键事件过滤能力，不获取窗口内容，也不会读取屏幕文字、触摸输入或其他应用数据。

- `MODIFY_AUDIO_SETTINGS`：调整主屏媒体音量。
- `VIBRATE`：在模式变化时提供振动反馈。
- `POST_NOTIFICATIONS`：让模式和副屏音量反馈可在应用后台显示。
- `INTERNET`：仅在用户手动检查 GitHub 最新 Release 时使用。
- Lite：请求修改系统设置权限，以使用兼容后端。
- Standard：只向用户当前选择的单一后端申请权限，即 Shizuku UserService 权限或 `su` Root 权限。

## 实现原理

AYN Thor 固件通过以下设置项提供副屏音量：

```text
Settings.System: secondary_screen_volume_level
范围：0–15
```

Lite 通过 Android 的目标 SDK 兼容行为访问该设置；Standard 可通过官方 Shizuku UserService，或由 [libsu](https://github.com/topjohnwu/libsu) 管理的 Root Shell 读写，但不会同时使用两种后端。模式、偏好、无障碍服务和界面等共用代码位于 `app/src/main`，后端实现分别位于 `app/src/lite` 与 `app/src/standard`。

## 构建项目

环境要求：

- Android Studio 或 Android SDK 命令行工具
- Android SDK 35
- 用于运行 Gradle 的 JDK 17

克隆仓库并构建两个版本：

```bash
git clone https://github.com/pth2000/ThorVolumeLink.git
cd ThorVolumeLink
./gradlew assembleLiteDebug assembleStandardDebug
```

Windows PowerShell：

```powershell
.\gradlew.bat assembleLiteDebug assembleStandardDebug
```

调试 APK 输出目录：

```text
app/build/outputs/apk/lite/debug/
app/build/outputs/apk/standard/debug/
```

运行静态检查：

```bash
./gradlew lintLiteDebug lintStandardDebug
```

应用版本号只有一处配置来源，即 `app/build.gradle.kts`：每次正式发布都应递增 `versionCode`，并修改面向用户显示的 `versionName`。Release 标签应在同一版本号前加 `v`，例如 `versionName = "0.2.0"` 对应标签 `v0.2.0`。

## 开源许可证

Thor Volume Link 基于 [MIT License](LICENSE) 开源。

Standard 使用采用 MIT License 的 Shizuku API，以及采用 Apache License 2.0 的 libsu。详见[第三方软件声明](docs/THIRD_PARTY_NOTICES.md)。
