# Thor Volume Link

[简体中文](README.zh-CN.md) · English

[![Android CI](https://github.com/pth2000/ThorVolumeLink/actions/workflows/android.yml/badge.svg)](https://github.com/pth2000/ThorVolumeLink/actions/workflows/android.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-5.0%2B-3DDC84?logo=android&logoColor=white)](#editions)

Thor Volume Link is an open-source volume controller for the dual-screen AYN Thor handheld. It routes the hardware volume keys to the primary screen, the secondary screen, or both screens at a linked relative level.

## Features

- Primary, secondary, linked, and follow-focus volume-key modes
- Direct control and proportional synchronization of the secondary-screen volume
- Optional hardware-key mode switching with configurable key behavior
- Live primary and secondary volume status

## Volume modes

| Mode | Volume-key behavior |
| --- | --- |
| Primary | Keeps Android's native volume-key behavior, adjusts only the primary media volume, and shows the system volume panel. |
| Secondary | Lets the app handle the volume keys and adjust only the secondary-screen volume without changing the primary volume. |
| Linked | Adjusts the primary media volume and maps the secondary screen to the same relative percentage. For example, 50% on the primary screen becomes approximately 50% on the secondary screen. |
| Follow focus | Controls the most recently interacted-with display: the upper display uses primary volume and the lower display uses secondary volume. The target remains fixed while a volume key is held. |

All four modes support short presses and native-style press-and-hold adjustment. **Adjustment step** under **Settings → Key & controls** applies when the app handles the hardware volume keys: in Secondary and Linked modes, and when Follow focus targets the lower display. Primary mode and Follow focus on the upper display retain Android's native step size. When the hardware mode key is enabled, holding it cycles through the modes; otherwise, modes remain selectable from the app's main screen.

**Using the app while locked:** All modes work normally while the device is locked as long as the display remains on. After the display turns off, the volume keys can directly adjust only the primary volume; in Linked mode, the secondary volume follows those changes. Due to system limitations, holding a volume key may not adjust continuously, and Secondary or Follow focus cannot control the lower display in this state.

## Editions

| Edition | Package | Minimum Android | Secondary-volume backend | Recommended for |
| --- | --- | ---: | --- | --- |
| Lite | `io.github.thorvolume.control.lite` | Android 5.0 / API 21 | `Settings.System` compatibility path | Recommended for the stock AYN Thor firmware |
| Standard | `io.github.thorvolume.control` | Android 6.0 / API 23 | Authorized backend | Users who prefer a modern target SDK |

Lite is the recommended edition for the stock AYN Thor firmware. It only needs Android's modify-system-settings approval and does not depend on a companion app or service. Lite intentionally targets SDK 22: AOSP keeps a compatibility path that lets apps targeting Android 5.1 or lower modify custom `Settings.System` entries, while apps targeting newer SDK levels are rejected. AYN's `secondary_screen_volume_level` is such a vendor-defined entry.

See the relevant [AOSP SettingsProvider implementation](https://android.googlesource.com/platform/frameworks/base/+/master/packages/SettingsProvider/src/com/android/providers/settings/SettingsProvider.java) and Android's [`WRITE_SETTINGS` documentation](https://developer.android.com/reference/android/provider/Settings.System#canWrite(android.content.Context)).

Standard targets a modern Android SDK and uses additional authorization to provide the same secondary-volume controls. It is intended for users who value current Android security and compatibility behavior; it is not a more feature-complete edition. The editions can be installed together, but do not enable both accessibility key services at the same time.

## Getting started

1. Install Lite first.
2. Open Thor Volume Link and allow modify-system-settings access.
3. Enable the app's key service in Android Accessibility settings.
4. Open **Settings → Key & controls** and decide whether a hardware key should switch modes.
5. Select Primary, Secondary, Linked, or Follow focus on the main screen.

If you choose Standard, follow the authorization guidance on the main screen. Only the method selected by the user is active.

When hardware-key mode switching is disabled, the configured mode key keeps its original system behavior. Volume Up and Volume Down continue to follow the mode selected in the app.

## Privacy and permissions

The accessibility service filters hardware keys and uses only the display ID attached to UI events to calibrate Follow Focus. It does not retrieve window content or read screen text, touch input, or data from other apps.

- `MODIFY_AUDIO_SETTINGS` adjusts the primary media volume.
- `VIBRATE` provides mode-change feedback.
- `INTERNET` is used only when the user manually checks the latest GitHub Release.
- `WAKE_LOCK` briefly keeps the CPU active only while completing linked-volume synchronization with the screen off.
- Lite requests modify-system-settings access for its compatibility backend.
- Standard requests permission from the single backend selected by the user: either Shizuku for its UserService or `su` for Root access.

## How it works

AYN Thor firmware exposes the secondary-screen volume through:

```text
Settings.System: secondary_screen_volume_level
Range: 0–15
```

Follow Focus observes the AYN firmware’s `Settings.System: focus_change` value, which increments as focus changes between displays. After a reboot, the app invalidates the old odd/even mapping and calibrates it once from a reliable display source. Standard can use the current system focus state; the app window and accessibility display events provide fallback anchors. Once calibrated, only `focus_change` is used until the next reboot. Until an anchor is available, the app safely falls back to the primary display. The target is locked for the duration of each volume-key press.

Lite accesses this setting through Android's target-SDK compatibility behavior. Standard reads and writes it through either an official Shizuku UserService or a Root shell powered by [libsu](https://github.com/topjohnwu/libsu). It never uses both backends at once. Shared mode, preference, accessibility-service, and UI code lives in `app/src/main`; backend-specific code lives in `app/src/lite` and `app/src/standard`.

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

## License

Thor Volume Link is available under the [MIT License](LICENSE).

The Standard edition uses Shizuku API under the MIT License and libsu under the Apache License 2.0. See [third-party notices](docs/THIRD_PARTY_NOTICES.md).
