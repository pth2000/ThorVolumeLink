# Thor Volume Link

[简体中文](README.zh-CN.md) · English

[![Android CI](https://github.com/pth2000/ThorVolumeLink/actions/workflows/android.yml/badge.svg)](https://github.com/pth2000/ThorVolumeLink/actions/workflows/android.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-5.0%2B-3DDC84?logo=android&logoColor=white)](#editions)

Thor Volume Link is an open-source volume controller for the dual-screen AYN Thor handheld. It routes the hardware volume keys to the primary screen, the secondary screen, or both screens at a linked relative level.

## Features

- Primary, secondary, and linked volume-key modes
- Direct control and proportional synchronization of the secondary-screen volume
- Optional hardware-key mode switching with configurable key behavior
- Live primary and secondary volume status

## Editions

| Edition | Package | Minimum Android | Secondary-volume backend | Recommended for |
| --- | --- | ---: | --- | --- |
| Lite | `io.github.thorvolume.control.lite` | Android 5.0 / API 21 | Legacy `Settings.System` compatibility path | Recommended first; no Shizuku required |
| Standard | `io.github.thorvolume.control` | Android 6.0 / API 23 | [Shizuku](https://shizuku.rikka.app/) UserService | Fallback when Lite is unavailable or incompatible |

Try Lite first. It only needs Android's modify-system-settings approval and does not require Shizuku installation or authorization. Lite intentionally targets SDK 22: AOSP keeps a compatibility path that lets apps targeting Android 5.1 or lower modify custom `Settings.System` entries, while apps targeting newer SDK levels are rejected. AYN's `secondary_screen_volume_level` is such a vendor-defined entry.

See the relevant [AOSP SettingsProvider implementation](https://android.googlesource.com/platform/frameworks/base/+/master/packages/SettingsProvider/src/com/android/providers/settings/SettingsProvider.java) and Android's [`WRITE_SETTINGS` documentation](https://developer.android.com/reference/android/provider/Settings.System#canWrite(android.content.Context)).

Lite can trigger legacy-app warnings or installation restrictions on newer Android versions. Use Standard with Shizuku if Lite cannot be installed or the compatibility path does not work on your firmware. The editions can be installed together, but do not enable both accessibility key services at the same time.

## Getting started

1. Install Lite first.
2. Open Thor Volume Link and allow modify-system-settings access.
3. Enable the app's key service in Android Accessibility settings.
4. Open **Settings → Key & controls** and decide whether a hardware key should switch modes.
5. Select Primary, Secondary, or Linked on the main screen.

If Lite is unavailable, install Standard, start Shizuku, and grant Thor Volume Link access when requested.

When hardware-key mode switching is disabled, the configured mode key keeps its original system behavior. Volume Up and Volume Down continue to follow the mode selected in the app.

## Privacy and permissions

The accessibility service requests key-event filtering only. It does not retrieve window content and does not read screen text, touch input, or data from other apps.

- `MODIFY_AUDIO_SETTINGS` adjusts the primary media volume.
- `VIBRATE` provides mode-change feedback.
- `POST_NOTIFICATIONS` allows mode and secondary-volume feedback to remain visible in the background.
- `INTERNET` is used only when the user manually checks the latest GitHub Release.
- Lite requests modify-system-settings access for its compatibility backend.
- Standard requests Shizuku permission for the secondary-volume UserService.

## How it works

AYN Thor firmware exposes the secondary-screen volume through:

```text
Settings.System: secondary_screen_volume_level
Range: 0–15
```

Lite accesses this setting through Android's target-SDK compatibility behavior. Standard reads and writes it through an official Shizuku UserService. Shared mode, preference, accessibility-service, and UI code lives in `app/src/main`; backend-specific code lives in `app/src/lite` and `app/src/standard`.

## Building

Requirements:

- Android Studio or Android SDK command-line tools
- Android SDK 35
- JDK 17 for Gradle

Clone the repository and build both editions:

```bash
git clone https://github.com/pth2000/ThorVolumeLink.git
cd ThorVolumeLink
./gradlew assembleLiteDebug assembleStandardDebug
```

On Windows PowerShell:

```powershell
.\gradlew.bat assembleLiteDebug assembleStandardDebug
```

Debug APKs are written to:

```text
app/build/outputs/apk/lite/debug/
app/build/outputs/apk/standard/debug/
```

Run static checks with:

```bash
./gradlew lintLiteDebug lintStandardDebug
```

The app version has a single source of truth in `app/build.gradle.kts`: increment `versionCode` for every published build and set the user-facing `versionName`. A release tag should match it with a `v` prefix, for example `versionName = "0.2.0"` and tag `v0.2.0`.

## License

Thor Volume Link is available under the [MIT License](LICENSE).
