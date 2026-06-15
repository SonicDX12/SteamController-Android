# Steam Controller for Android

Use the **Steam Controller 2026** (Valve, codename *Ibex*) as a standard Android gamepad — no root required. Connect via USB OTG / wireless Puck, or directly via Bluetooth.

The app reads the controller's proprietary HID protocol and exposes it to Android as a virtual gamepad through Linux `uinput` (accessed via Shizuku), so any game that supports controllers sees a real input device — Xbox 360, Xbox One, DualShock 4 or DualSense, your choice.

## Features

### Connection
- **USB OTG** — wired or via the wireless Puck dongle
- **Bluetooth LE** — direct pairing with the controller, no dongle needed
- **Live transport switching** in the UI (toggle group with USB and Bluetooth icons)
- **Refresh paired BT devices** without restarting the app
- **In-app help dialog** explaining the controller's wireless mode combos (Steam+A+R1, Steam+B+R1, etc.)

### Emulation
- **Four virtual gamepad profiles**: Xbox 360 (default), Xbox One, Sony DualShock 4, Sony DualSense
- **Cycle profiles directly from the notification** (`↻ → next profile`) without opening the app
- **Real `InputDevice`** via Linux `uinput` (UID shell via Shizuku UserService) — recognised by games as a real gamepad, not filtered like injected events
- **Automatic fallback** to `IInputManager.injectInputEvent` if `/dev/uinput` is denied (less compatible, kept as safety net)

### Tuning
- **Per-stick calibration** — radial dead zone (0–30%), center offset capture, Y-axis inversion, live 2D preview
- **Custom button mapping** — categorised list (Face / Bumpers / Stick clicks / System / Back paddles / Grips). Any source button to any target including back paddles L4/L5/R4/R5 and the Quick Access Menu button
- **Special actions** — map any button to **📸 Take screenshot** (saved in Pictures/Screenshots, visible immediately in the gallery)
- **Rumble forwarding pipeline** with adjustable intensity (0–100%) and a manual "Test rumble" button

### Debug & status
- **HID debug view** — every button, stick, trigger, trackpad, IMU quaternion and raw hex dump, updated at the controller's ~300 Hz
- **Battery indicator** in the status card and in the notification (USB only — BLE battery report parsing is post-V1 work)
- **Persistent foreground service notification** with the active emulation profile, battery, profile-cycle action, and stop action

### Platform
- **Material 3** design with Steam blue accents
- **Adaptive layouts** — phone (max-width 520dp) and tablet (`sw600dp` with two-column layouts)

## Requirements

- Android 8.0+ (API 26)
- [Shizuku](https://shizuku.rikka.app/) installed and running
- For USB: USB Host (OTG) support on the phone/tablet
- For Bluetooth: standard BLE (available on every modern Android)
- A **Steam Controller 2026** (Valve Ibex). The older Steam Controller is not yet supported.

## Setup

1. Install [Shizuku](https://shizuku.rikka.app/) and start it (ADB Wireless on Android 11+, or one-time ADB cable for older versions).
2. Install this app and grant it the Shizuku permission when prompted.
3. Grant the **POST_NOTIFICATIONS** permission when asked (Android 13+) so the foreground status notification shows up.
4. **For USB**: plug the Puck (or the controller directly) into the OTG port. Android will ask for USB permission.
5. **For Bluetooth**: pair the controller via Android Settings → Bluetooth first, then select it from the Bluetooth device dropdown in the app. Use the `↻` refresh button if you just paired it.
6. Pick the emulated controller profile (Xbox 360 is the safest default — broadest compatibility).
7. Hit **Start Service**. The status card shows `Mode: <profile> (uinput) ✓` when everything is up.

If `uinput` is blocked by SELinux on your device (rare on stock Android, possible on some hardened ROMs), the app falls back to `injectInputEvent`, which works in most apps but is filtered by many games.

## How it works

```
Steam Controller (USB or BT)
         │
         ▼
HID report parser  (report 0x45, 53 bytes USB / 45 bytes BLE)
         │  validated against SteamlessController.h
         ▼
ControllerService
   • debounce (15-bit injectable mask, 3 frames)
   • baseline state (ignore buttons held at startup)
   • mapping (Steam buttons → Xbox buttons or special actions)
   • per-stick calibration
   • rumble intensity scaling
         │
         ▼
UInputGamepad → AIDL/Binder → UInputService (UID shell via Shizuku)
                                       │
                                       ▼
                                JNI uinput_jni.cpp
                                       │
                                       ▼
                              /dev/uinput → kernel
                                       │
                                       ▼
                          Android sees a real "Microsoft
                            X-Box 360 pad" (or DS4, etc.)
```

The HID protocol is parsed natively and translated into the chosen profile's button/axis layout before being written to a virtual gamepad created via Linux `uinput`. The Shizuku `UserService` runs as the `shell` user (UID 2000) which has access to `/dev/uinput` on most Android builds.

For BLE, the standard HID service (`0x1812`) is claimed by the OS, so the app uses Valve's vendor service (`100f6c32-1735-4313-b402-38567131e5f3`) directly. Connection priority is bumped to `HIGH` after connect to bring the BLE interval from ~50 ms down to ~11 ms.

## Build

Standard Android Gradle build, requires:

- Android Studio Hedgehog or newer
- Android Gradle Plugin 8.5+
- Kotlin 2.0+
- NDK + CMake 3.22.1 (for the native `uinput` JNI library)

**First time:** open the project in Android Studio and let it sync — this regenerates the Gradle wrapper. After that:

```bash
./gradlew assembleDebug
# APK lands in app/build/outputs/apk/debug/
```

The app supports `arm64-v8a`, `armeabi-v7a` and `x86_64` ABIs.

For signed release builds and publishing to GitHub Releases, see [RELEASING.md](RELEASING.md).

## Configuration

Settings are persisted in `SharedPreferences`:

- Selected transport (USB or Bluetooth) and paired BT device address
- Emulated controller profile (Xbox 360 / Xbox One / DS4 / DualSense)
- Per-stick calibration (dead zone, center offset, invert Y)
- Per-button mapping (17 source buttons → 12 Xbox targets + special actions like screenshot)
- Rumble intensity (0–100%)

You can tweak everything live — most changes apply within ~250 ms (next mapping cache refresh) without restarting the service. Changing transport or emulated profile requires restarting the service (or use the notification's profile cycle action).

## Known limitations

- **`shell` UID access to `/dev/uinput`** depends on the device's SELinux policy. Most stock Android builds allow it; some hardened ROMs may not. The app falls back to `injectInputEvent` automatically in that case.
- **Steam button** passes through as `KEYCODE_BUTTON_MODE`. Android handles it as the system "Guide" key which may open the launcher in some setups.
- **Rumble byte format** is an empirically-tuned best guess based on the Linux `hid-steam` driver — works for the BT transport but the exact command id and write characteristic may need adjusting for your firmware. USB rumble is not implemented yet.
- **Bluetooth battery level** is not parsed yet — the value comes from a separate 5-byte status report on a different characteristic that the V1 parser ignores. The UI shows `—` over BT.
- **Trackpads** (left and right) are parsed but not currently routed to any output. Future work could expose them as the DualShock 4 touchpad or as a virtual mouse.
- **Gyroscope** (quaternion IMU) is parsed but not yet routed. Gyro aiming is planned for a future release, fits best with the DualShock 4 / DualSense profiles.
- **Bluetooth auto-reconnect**: if the controller powers off, the app does not retry the GATT connection.
- **Shizuku at reboot**: the user must restart Shizuku after each reboot of the device (an Android limitation, not the app's).

## Roadmap

- **V1.1 — Desktop Mode** (mouse + keyboard emulation, for Android TV boxes)
- Bluetooth battery parsing (separate status report)
- USB rumble implementation
- Trackpad as touchpad (DS4 profile) or mouse (Desktop mode)
- Gyro aiming for DS4 / DualSense profiles
- USB / BT auto-reconnect

## Credits

The HID protocol reverse engineering credit goes to:

- [**SteamlessController**](https://github.com/ddeverill/SteamlessController) by ddeverill — the definitive `SteamController.h` byte layout reference for the SC2026
- The [**Linux kernel `hid-steam` driver**](https://github.com/torvalds/linux/blob/master/drivers/hid/hid-steam.c) for additional validation of button bit positions and the rumble command structure

Other key dependencies:

- [Shizuku](https://github.com/RikkaApps/Shizuku) by RikkaApps — the `uinput` access path without root
- [Android USB Host API](https://developer.android.com/guide/topics/connectivity/usb/host) and the BLE GATT stack
- [Material Components for Android](https://github.com/material-components/material-components-android) for the Material 3 UI

## License

MIT — see [LICENSE](LICENSE).
