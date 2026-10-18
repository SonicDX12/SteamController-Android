# Steam Controller for Android

Use the **Steam Controller 2026** (Valve, codename *Ibex*) as a standard Android gamepad — no root required. Connect via USB OTG / wireless Puck, or directly via Bluetooth.

The app reads the controller's proprietary HID protocol and exposes it to Android as a virtual gamepad through Linux `uinput` (accessed via Shizuku), so any game that supports controllers sees a real input device — Xbox 360, Xbox One, DualShock 4 or DualSense, your choice. A **Desktop mode** turns the same controller into a virtual mouse + keyboard, ideal for Android TV boxes.

## Features

### Connection
- **USB OTG** — wired or via the wireless Puck dongle
- **Bluetooth LE** — direct pairing with the controller, no dongle needed
- **Transport choice on the Home tab**, with the paired Bluetooth controllers listed directly and a `↻` refresh button
- **Auto-start** (Settings) — the service starts by itself when the controller connects. Over Bluetooth it works with the app closed: the virtual pad appears about half a second after the controller connects and disappears when it is switched off
- **In-app help dialog** explaining the controller's wireless mode combos (Steam+A+R1, Steam+B+R1, etc.)

### Emulation
- **Five virtual profiles**: Xbox 360 (default), Xbox One, Sony DualShock 4, Sony DualSense, and **Desktop** (mouse + keyboard)
- **Cycle gamepad profiles directly from the notification** (`↻ → next profile`) without opening the app
- **Real `InputDevice`** via Linux `uinput` (UID shell via Shizuku UserService) — recognised by games as a real gamepad, not filtered like injected events
- **Automatic fallback** to `IInputManager.injectInputEvent` if `/dev/uinput` is denied (less compatible, kept as safety net)

### Desktop mode
- Turns the controller into a virtual **mouse + keyboard** — right trackpad drives the cursor, left trackpad scrolls, buttons map to common keys (volume, play/pause, back, home, enter, escape, tab, space, etc.)
- Even while a gamepad profile is active, the trackpads can double as a mouse sidecar (toggle in Calibration) so you can still navigate menus without switching profiles
- Full **Android TV** support: leanback banner, complete TV-remote navigation (clear focus ring, D-pad scrolling, sliders adjusted with left/right), on-screen keyboard shows up correctly when a text field is focused

### Game Profiles
- Save the current calibration, button mapping, rumble intensity and mouse sensitivity as a **named preset**
- Load, rename, duplicate or delete presets from a dedicated screen
- **Bind a preset to one or more apps** — the service automatically switches profile when you launch a bound game (foreground-app detection, no manual step)

### Tuning
- **Per-stick calibration** — radial dead zone (0–40%), center offset capture, Y-axis inversion, live 2D preview
- **Custom button mapping** — categorised list (Face / Bumpers / Triggers full-press / Stick clicks / System / Back paddles / Grips), using the official Steam Input button icons. Any source button to any target, including back paddles L4/L5/R4/R5, the Quick Access Menu button, and forcing a trigger to "fully pulled"
- **Special actions** — map any button to **📸 Take screenshot** (saved in Pictures/Screenshots, visible immediately in the gallery)
- **Rumble forwarding** over USB/Puck and Bluetooth, with adjustable intensity (0–100%) and a manual "Test rumble" button (the intensity only scales over USB — see Known limitations)

### Backup & restore
- **Export** every live setting and all Game Profiles to a single JSON file via the system file picker (Settings → Backup)
- **Import** that file back at any time — handy after reinstalling the app or moving to a new phone

### Auto-update
- Checks GitHub Releases for a newer version at launch (once every 24h) or on demand
- Shows the release notes and downloads the signed APK straight from GitHub, then hands off to the system installer

### Debug & status
- **HID debug view** — every button, stick, trigger, trackpad, raw accelerometer/gyroscope and the raw hex dump, live with the measured report rate (~66 Hz over Bluetooth, ~240 Hz over USB)
- **Battery indicator** in the status card and in the notification — works over both USB and Bluetooth, and shows "Charging" on the cable (the firmware pins the percentage to 100 while charging)
- **Persistent foreground service notification** with the active emulation profile, battery, profile-cycle action, and stop action

### Platform
- **Jetpack Compose + Material 3** UI with rounded outlined cards — **Steam** palette or **Dynamic** colours from your wallpaper (Android 12+), applied instantly
- **Three tabs** (Home, Profiles, Settings) — a floating bottom bar on phones, a side rail from 600dp wide (unfolded foldables, tablets, TVs) with two-column screens
- **16 KB page-size compatible** native library

## Requirements

- Android 8.0+ (API 26) — phone, tablet, or Android TV
- [Shizuku](https://shizuku.rikka.app/) installed and running
- For USB: USB Host (OTG) support on the phone/tablet
- For Bluetooth: standard BLE (available on every modern Android)
- A **Steam Controller 2026** (Valve Ibex). The older Steam Controller is not yet supported.

## Setup

1. Install [Shizuku](https://shizuku.rikka.app/) and start it (ADB Wireless on Android 11+, or one-time ADB cable for older versions).
2. Install this app and grant it the Shizuku permission when prompted.
3. Grant the **POST_NOTIFICATIONS** permission when asked (Android 13+) so the foreground status notification shows up.
4. **For USB**: plug the Puck (or the controller directly) into the OTG port. Android will ask for USB permission.
5. **For Bluetooth**: pair the controller via Android Settings → Bluetooth first, then select it in the **Bluetooth device** list on the Home tab. Use the `↻` refresh button if you just paired it.
6. Pick the emulated controller (Xbox 360 is the safest default for games — broadest compatibility. Pick Desktop for mouse + keyboard, e.g. on Android TV).
7. Hit **Start Service**. The status card switches to **Controller ready** with a `<profile> (uinput)` pill when everything is up.
8. Optional: tune everything to your liking (Settings → Calibration, Button mapping) and save it as a **Game Profile** — bind it to a game so it auto-loads next time you launch it.
9. Optional: turn on **Start automatically when the controller connects** in Settings, and accept the battery-optimisation prompt — Android needs it to start the service in the background.

If `uinput` is blocked by SELinux on your device (rare on stock Android, possible on some hardened ROMs), the app falls back to `injectInputEvent`, which works in most apps but is filtered by many games.

## How it works

```
Steam Controller (USB or BT)
         │
         ▼
HID report parser  (state report 0x45 / 0x42, 54B USB / 45B BLE —
                     plus a dedicated 0x43 battery status report)
         │  validated against SteamlessController.h + hardware capture
         ▼
ControllerService
   • debounce (15-bit injectable mask, 3 frames)
   • baseline state (ignore buttons held at startup)
   • mapping (Steam buttons → Xbox buttons or special actions)
   • per-stick calibration
   • rumble intensity scaling
   • foreground-app polling → Game Profile auto-switch
         │
         ▼
UInputGamepad → AIDL/Binder → UInputService (UID shell via Shizuku)
                                       │
                                       ▼
                                JNI uinput_jni.cpp
                                       │
                          ┌────────────┴────────────┐
                          ▼                          ▼
                  gamepad device            mouse+keyboard sidecar
                (Xbox/DS4/DualSense)      (Desktop mode, or trackpad-
                                            as-mouse alongside a gamepad)
                          │                          │
                          └────────────┬─────────────┘
                                       ▼
                              /dev/uinput → kernel
                                       │
                                       ▼
                        Android sees a real "Microsoft
                       X-Box 360 pad" (or DS4, mouse, etc.)
```

The HID protocol is parsed natively and translated into the chosen profile's button/axis layout before being written to a virtual gamepad created via Linux `uinput`. The Shizuku `UserService` runs as the `shell` user (UID 2000) which has access to `/dev/uinput` on most Android builds.

For BLE, the standard HID service (`0x1812`) is claimed by the OS, so the app uses Valve's vendor service (`100f6c32-1735-4313-b402-38567131e5f3`) directly. Connection priority is bumped to `HIGH` after connect to bring the BLE interval from ~50 ms down to ~11 ms.

## Build

Standard Android Gradle build, requires:

- Android Studio Ladybug or newer
- JDK 17+
- Android Gradle Plugin 8.13 (Gradle 8.13, via the wrapper)
- Kotlin 2.0+ with the Compose compiler plugin
- NDK + CMake 3.22.1 (for the native `uinput` JNI library)

**First time:** open the project in Android Studio and let it sync — this regenerates the Gradle wrapper. After that:

```bash
./gradlew assembleDebug
# APK lands in app/build/outputs/apk/debug/
```

The app supports `arm64-v8a`, `armeabi-v7a` and `x86_64` ABIs.

For signed release builds and publishing to GitHub Releases, see [RELEASING.md](RELEASING.md).

## Configuration

Live settings and Game Profiles are persisted in `SharedPreferences`:

- Selected transport (USB or Bluetooth) and paired BT device address
- Emulated profile (Xbox 360 / Xbox One / DS4 / DualSense / Desktop)
- Per-stick calibration (dead zone, center offset, invert Y)
- Per-button mapping (source buttons → Xbox targets, keyboard keys, or special actions like screenshot)
- Rumble intensity (0–100%) and mouse sensitivity (Desktop mode / trackpad sidecar)
- Named Game Profiles, each with its own copy of the settings above plus the list of apps it auto-switches on

You can tweak everything live — most changes apply within ~250 ms (next mapping cache refresh) without restarting the service. Changing transport or emulated profile requires restarting the service (or use the notification's profile cycle action). Use **Export backup** / **Import backup** in Settings to move all of this to a JSON file — useful before uninstalling the app or when setting it up on a new device.

## Known limitations

- **`shell` UID access to `/dev/uinput`** depends on the device's SELinux policy. Most stock Android builds allow it; some hardened ROMs may not. The app falls back to `injectInputEvent` automatically in that case.
- **Steam button** passes through as `KEYCODE_BUTTON_MODE`. Android handles it as the system "Guide" key which may open the launcher in some setups.
- **Rumble intensity over Bluetooth**: the only haptic command reachable over BLE is a pulse with no amplitude field, so the intensity slider barely changes the strength there. Over USB/Puck the intensity scales properly.
- **Trackpads**: usable as a mouse (Desktop mode, or as an optional sidecar cursor alongside a gamepad profile). Not yet exposed as a DualShock 4/DualSense touchpad to games that support one natively.
- **Gyroscope** (raw accelerometer + gyro) is parsed and shown in the debug view but not yet routed anywhere. Gyro aiming is planned for a future release, fits best with the DualShock 4 / DualSense profiles.
- **Auto-start over USB** doesn't work on some Samsung devices: Android doesn't open the app when the controller is plugged in. Bluetooth auto-start is not affected. With the Puck, Android only sees the dongle, so turning the controller on with the Puck already plugged in can't start the app either.
- **Shizuku at reboot**: the user must restart Shizuku after each reboot of the device (an Android limitation, not the app's). With auto-start on, a notification says so instead of failing silently.

## Roadmap

- Trackpad as a real touchpad input (DS4/DualSense profile) for games that support it
- Gyro aiming for DS4 / DualSense profiles
- Power off the controller from the app (Steam + Y equivalent)
- HID debug log export ("Log to File")

## Credits

The HID protocol reverse engineering credit goes to:

- [**SteamlessController**](https://github.com/ddeverill/SteamlessController) by ddeverill — the definitive `SteamController.h` byte layout reference for the SC2026
- The [**Linux kernel `hid-steam` driver**](https://github.com/torvalds/linux/blob/master/drivers/hid/hid-steam.c) for additional validation of button bit positions and the rumble command structure

Other key dependencies:

- [Shizuku](https://github.com/RikkaApps/Shizuku) by RikkaApps — the `uinput` access path without root
- [Android USB Host API](https://developer.android.com/guide/topics/connectivity/usb/host) and the BLE GATT stack
- [Jetpack Compose](https://developer.android.com/jetpack/compose) and Material 3 for the UI

## License

MIT — see [LICENSE](LICENSE).
