package com.steamcontroller.android.input

import com.steamcontroller.android.parser.Buttons

/**
 * Targets available in Desktop / Mouse mode. The `bit` field MUST match the order
 * of the `MOUSE_KEYS` array in `uinput_jni.cpp`. Bits 15/16/17 are mouse buttons.
 *
 * NONE (bit = -1) means "no event emitted for this source".
 */
enum class MouseTarget(val bit: Int, val displayName: String) {
    NONE(-1, "(none)"),

    // Navigation keys
    KEY_UP   (0, "↑ Up"),
    KEY_DOWN (1, "↓ Down"),
    KEY_LEFT (2, "← Left"),
    KEY_RIGHT(3, "→ Right"),

    KEY_ENTER(4, "Enter / OK"),
    KEY_BACK (5, "Back (Android)"),
    KEY_TAB  (6, "Tab"),
    KEY_SPACE(7, "Space"),

    KEY_HOME(8, "Home"),
    KEY_ESC (9, "Escape"),

    KEY_VOLUME_UP  (10, "Volume +"),
    KEY_VOLUME_DOWN(11, "Volume -"),
    KEY_PLAY_PAUSE (12, "Play/Pause"),
    KEY_MENU       (13, "Menu (context)"),
    KEY_BACKSPACE  (14, "Backspace"),

    // bit 15 → Linux KEY_SELECT → AKEYCODE_DPAD_CENTER. Required to "click" a
    // focused key on the Android TV Leanback soft keyboard (ENTER alone is not
    // enough — the IME source-filters selection to DPAD_CENTER events).
    KEY_DPAD_CENTER(15, "DPAD Center / select"),

    // Mouse buttons (bits 16-18 in the native frame)
    BTN_LEFT  (16, "🖱 Left click"),
    BTN_RIGHT (17, "🖱 Right click"),
    BTN_MIDDLE(18, "🖱 Middle click"),
}

/** Hardcoded V1.1 mapping. Customisable mapping UI will land later. */
val DEFAULT_MOUSE_MAPPING: Map<SteamButton, MouseTarget> = mapOf(
    // A = DPAD_CENTER so it acts like an Android remote "OK": selects letters on the
    // Leanback IME, clicks focused UI buttons, and on text fields most apps treat it
    // the same as ENTER. The dedicated ENTER key is still reachable via remap if needed.
    SteamButton.A       to MouseTarget.KEY_DPAD_CENTER,
    SteamButton.B       to MouseTarget.KEY_BACK,
    SteamButton.X       to MouseTarget.KEY_SPACE,
    SteamButton.Y       to MouseTarget.KEY_TAB,
    SteamButton.LB      to MouseTarget.BTN_RIGHT,
    SteamButton.RB      to MouseTarget.BTN_LEFT,
    // Triggers default to NONE in Desktop too — scroll is already on the left trackpad.
    SteamButton.LT      to MouseTarget.NONE,
    SteamButton.RT      to MouseTarget.NONE,
    SteamButton.LS      to MouseTarget.KEY_HOME,
    SteamButton.RS      to MouseTarget.BTN_MIDDLE,
    SteamButton.MENU         to MouseTarget.KEY_MENU,
    SteamButton.VIEW         to MouseTarget.KEY_ESC,
    SteamButton.STEAM        to MouseTarget.KEY_HOME,
    SteamButton.QUICK_ACCESS to MouseTarget.KEY_PLAY_PAUSE,
    SteamButton.L4      to MouseTarget.KEY_VOLUME_DOWN,
    SteamButton.R4      to MouseTarget.KEY_VOLUME_UP,
    SteamButton.L5      to MouseTarget.KEY_BACKSPACE,
    SteamButton.R5      to MouseTarget.NONE,
    SteamButton.GRIP_LT to MouseTarget.NONE,
    SteamButton.GRIP_RT to MouseTarget.NONE,
)

/**
 * Source bits that route to *fixed* targets in mouse mode (not customisable in V1.1):
 *  - DPAD → arrow keys
 *  - Left trackpad click → right mouse click
 *  - Right trackpad motion → cursor delta (handled separately, not a button)
 */
val MOUSE_MODE_FIXED_DPAD = mapOf(
    Buttons.DPAD_UP    to MouseTarget.KEY_UP,
    Buttons.DPAD_DOWN  to MouseTarget.KEY_DOWN,
    Buttons.DPAD_LEFT  to MouseTarget.KEY_LEFT,
    Buttons.DPAD_RIGHT to MouseTarget.KEY_RIGHT,
)
const val MOUSE_LEFT_PAD_CLICK_BIT = Buttons.TP_LT_CLICK
