# Thor Volume Link

简体中文 · [English](README.md)

[![Android CI](https://github.com/pth2000/ThorVolumeLink/actions/workflows/android.yml/badge.svg)](https://github.com/pth2000/ThorVolumeLink/actions/workflows/android.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-5.0%2B-3DDC84?logo=android&logoColor=white)](#版本区别)

Thor Volume Link 是一款面向双屏 Android 掌机 AYN Thor 的开源音量控制工具。它可以让实体音量键控制主屏、副屏，或按相对比例联动两块屏幕的音量。

## 功能特性

- 主屏、副屏、双屏联动和跟随焦点四种音量键模式
- 直接控制副屏音量，并按相对比例同步两块屏幕
- 可选择是否使用实体键切换模式，并配置按键行为
- 实时显示主屏与副屏音量

## 音量模式

| 模式 | 音量键行为 |
| --- | --- |
| 主屏 | 保留 Android 的原生音量键行为，只调节主屏媒体音量，并显示系统音量面板。 |
| 副屏 | 由应用接管音量键，只调节副屏音量，不改变主屏音量。 |
| 双屏联动 | 调节主屏媒体音量，同时按照当前百分比将副屏映射到对应档位。例如主屏为最大音量的 50%，副屏会同步到约 50%。 |
| 跟随焦点 | 根据最近交互的屏幕决定控制目标：上屏使用主屏音量，下屏使用副屏音量。按住音量键期间目标保持不变，避免连续调节时在两块屏幕间跳转。 |

四种模式都支持短按和长按连续调节。**设置 → 按键与控制**中的“调节步长”用于应用接管的实体音量键操作，包括副屏、双屏联动，以及跟随焦点指向下屏时的调节；主屏和跟随焦点指向上屏时仍采用 Android 原生步长。若启用实体模式键，可通过长按该键依次切换模式；关闭后仍可在应用首页直接选择。

**锁屏使用：** 设备锁定后，只要屏幕仍处于点亮状态，各模式都可正常使用。屏幕熄灭后，音量键只能直接调节主屏音量；使用双屏联动时，副屏音量会随主屏同步变化。受系统限制，熄屏后长按可能无法连续调节，副屏模式和跟随焦点模式也无法控制下屏。

## 版本区别

| 版本 | 包名 | 最低 Android 版本 | 副屏音量后端 | 适用场景 |
| --- | --- | ---: | --- | --- |
| Lite | `io.github.thorvolume.control.lite` | Android 5.0 / API 21 | `Settings.System` 兼容路径 | AYN Thor 原厂固件用户，推荐使用 |
| Standard | `io.github.thorvolume.control` | Android 6.0 / API 23 | 授权后端 | 倾向现代目标 SDK 的用户 |

Lite 是面向 AYN Thor 原厂固件的推荐版本。它只需要 Android 的“修改系统设置”授权，无需配合其他应用或服务。Lite 有意将目标 SDK 保持在 22：AOSP 为目标版本不高于 Android 5.1 的应用保留了兼容路径，允许它们修改自定义 `Settings.System` 项；目标 SDK 更高的应用则会被系统拒绝。AYN 的 `secondary_screen_volume_level` 正是厂商自定义设置项。

相关依据可参阅 [AOSP SettingsProvider 实现](https://android.googlesource.com/platform/frameworks/base/+/master/packages/SettingsProvider/src/com/android/providers/settings/SettingsProvider.java)和 Android 官方的 [`WRITE_SETTINGS` 文档](https://developer.android.com/reference/android/provider/Settings.System#canWrite(android.content.Context))。

Standard 保持现代目标 SDK，通过额外授权完成相同的副屏音量控制。它适合更看重现代 Android 安全与兼容规则的用户，功能并不多于 Lite。两个版本可以同时安装，但不要同时启用它们的无障碍按键服务。

## 使用方法

1. 优先安装 Lite。
2. 打开 Thor Volume Link，允许应用修改系统设置。
3. 在 Android 无障碍设置中启用本应用的按键服务。
4. 打开**设置 → 按键与控制**，选择是否使用实体键切换模式。
5. 在主页面选择主屏、副屏、双屏联动或跟随焦点模式。

如果选择 Standard，请按照首页引导完成授权。应用始终只启用用户选定的一种授权方式。

关闭实体键模式切换后，已配置的模式键会恢复原有系统行为；音量增加和降低键仍会按照应用内选定的模式工作。

## 隐私与权限

无障碍服务用于过滤实体按键，并仅使用界面事件的屏幕 ID 校正跟随焦点模式。它不获取窗口内容，也不会读取屏幕文字、触摸输入或其他应用数据。

- `MODIFY_AUDIO_SETTINGS`：调整主屏媒体音量。
- `VIBRATE`：在模式变化时提供振动反馈。
- `INTERNET`：仅在用户手动检查 GitHub 最新 Release 时使用。
- `WAKE_LOCK`：仅在熄屏联动同步期间短暂保持处理器运行，以完成副屏音量写入。
- Lite：请求修改系统设置权限，以使用兼容后端。
- Standard：只向用户当前选择的单一后端申请权限，即 Shizuku UserService 权限或 `su` Root 权限。

## 实现原理

AYN Thor 固件通过以下设置项提供副屏音量：

```text
Settings.System: secondary_screen_volume_level
范围：0–15
```

跟随焦点模式监听 AYN 固件提供的 `Settings.System: focus_change`。该值在焦点切换时递增。设备重启后，应用会作废旧的奇偶映射，并通过可靠的屏幕信息完成一次校正；Standard 可使用当前系统焦点状态，应用窗口和无障碍界面事件则作为补充来源。校正成功后，到下次重启前只需根据 `focus_change` 判断目标屏幕。尚未获得可靠屏幕信息时会安全回退到主屏，按住音量键期间则会锁定按下时的目标屏幕。

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

## 开源许可证

Thor Volume Link 基于 [MIT License](LICENSE) 开源。

Standard 使用采用 MIT License 的 Shizuku API，以及采用 Apache License 2.0 的 libsu。详见[第三方软件声明](docs/THIRD_PARTY_NOTICES.md)。
