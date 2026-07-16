package com.steamcontroller.android.input

import com.steamcontroller.android.parser.Buttons
import com.steamcontroller.android.uinput.XboxButtons

enum class ButtonCategory(val title: String) {
    FACE("Face buttons"),
    BUMPERS("Bumpers"),
    TRIGGERS("Triggers (full press)"),
    STICKS("Stick clicks"),
    SYSTEM("System"),
    BACK_PADDLES("Back paddles"),
    GRIPS("Grips (capacitive)"),
}

/**
 * Physical buttons on the Steam Controller 2026 that can be remapped.
 * `mask` is the bit position in `SteamControllerState.buttons`.
 *
 * DPAD, triggers and trackpads are intentionally NOT here — they're routed as
 * axes (HAT / LTRIGGER / RTRIGGER), not as remappable button bits.
 */
enum class SteamButton(
    val mask: Int,
    val displayName: String,
    val category: ButtonCategory,
) {
    A      (Buttons.A,       "A",                  ButtonCategory.FACE),
    B      (Buttons.B,       "B",                  ButtonCategory.FACE),
    X      (Buttons.X,       "X",                  ButtonCategory.FACE),
    Y      (Buttons.Y,       "Y",                  ButtonCategory.FACE),
    LB     (Buttons.LB,      "L1 / LB",            ButtonCategory.BUMPERS),
    RB     (Buttons.RB,      "R1 / RB",            ButtonCategory.BUMPERS),
    // Trigger full-press = digital bit fired when the analog trigger reaches max travel.
    // The analog 0..32767 value still routes automatically to LT/RT axes on the
    // emulated gamepad — these entries only matter if you remap "fully pulled" to a
    // different action (e.g. RT-full → A button). LT_FULL bit at 0x400000 is V1.2-empirical.
    LT     (Buttons.LT_FULL, "L2 / LT (full press)", ButtonCategory.TRIGGERS),
    RT     (Buttons.RT_FULL, "R2 / RT (full press)", ButtonCategory.TRIGGERS),
    LS     (Buttons.LS,      "L3 (stick click)",   ButtonCategory.STICKS),
    RS     (Buttons.RS,      "R3 (stick click)",   ButtonCategory.STICKS),
    MENU         (Buttons.MENU,         "Menu (Start)",       ButtonCategory.SYSTEM),
    VIEW         (Buttons.VIEW,         "View (Select)",      ButtonCategory.SYSTEM),
    STEAM        (Buttons.STEAM,        "Steam (Guide)",      ButtonCategory.SYSTEM),
    QUICK_ACCESS (Buttons.QUICK_ACCESS, "Quick Access Menu",  ButtonCategory.SYSTEM),
    L4     (Buttons.L4,      "L4 back paddle",     ButtonCategory.BACK_PADDLES),
    R4     (Buttons.R4,      "R4 back paddle",     ButtonCategory.BACK_PADDLES),
    L5     (Buttons.L5,      "L5 back paddle",     ButtonCategory.BACK_PADDLES),
    R5     (Buttons.R5,      "R5 back paddle",     ButtonCategory.BACK_PADDLES),
    GRIP_LT(Buttons.GRIP_LT, "Left grip",          ButtonCategory.GRIPS),
    GRIP_RT(Buttons.GRIP_RT, "Right grip",         ButtonCategory.GRIPS);

    /** Short label fits inside a chip badge (≤4 chars) */
    val shortLabel: String get() = when (this) {
        A -> "A"; B -> "B"; X -> "X"; Y -> "Y"
        LB -> "L1"; RB -> "R1"
        LT -> "L2"; RT -> "R2"
        LS -> "L3"; RS -> "R3"
        MENU -> "≡"; VIEW -> "···"; STEAM -> "◆"
        L4 -> "L4"; R4 -> "R4"; L5 -> "L5"; R5 -> "R5"
        GRIP_LT -> "LG"; GRIP_RT -> "RG"
        QUICK_ACCESS -> "QA"
    }
}

/**
 * Target buttons exposed by the virtual gamepad (Xbox layout — Android maps
 * these consistently across all profiles).
 *
 * Four flavours coexist:
 *  - `mask > 0`            : regular Xbox button (OR into the gamepad button mask).
 *  - `keyBit >= 0`         : sidecar keyboard key — emitted via the mouse+kbd sidecar
 *                            device that always runs alongside a gamepad profile.
 *                            Bit value matches MouseTarget so we reuse the same JNI path.
 *  - `triggerSide != 0`    : forces an analog trigger axis to max (1=LT, 2=RT) when the
 *                            source is pressed. OR'd with the real analog reading via max().
 *  - `mask < 0` (no other) : special action handled in Kotlin (e.g. SCREENSHOT).
 *
 * NONE (mask=0, keyBit=-1, triggerSide=0) means "disabled".
 */
enum class XboxTarget(val mask: Int, val displayName: String, val keyBit: Int = -1, val triggerSide: Int = 0) {
    NONE  (0,                  "(none)"),
    A     (XboxButtons.A,      "A"),
    B     (XboxButtons.B,      "B"),
    X     (XboxButtons.X,      "X"),
    Y     (XboxButtons.Y,      "Y"),
    LB    (XboxButtons.LB,     "LB"),
    RB    (XboxButtons.RB,     "RB"),
    SELECT(XboxButtons.SELECT, "View / Select"),
    START (XboxButtons.START,  "Menu / Start"),
    MODE  (XboxButtons.MODE,   "Guide"),
    THUMBL(XboxButtons.THUMBL, "L3"),
    THUMBR(XboxButtons.THUMBR, "R3"),

    // ── Trigger axes (digital → forced max) ──────────────────────────────────
    // Lets the user remap any button (face, paddle, bumper…) to "trigger fully
    // pulled". Useful e.g. to remap a back paddle to LT so you can drive + sip a
    // potion at the same time. Mask is negative so it doesn't OR into face buttons.
    LT_TRIGGER(-30, "L2 / LT (full press)", triggerSide = 1),
    RT_TRIGGER(-31, "R2 / RT (full press)", triggerSide = 2),

    // ── Sidecar keyboard keys (great for back paddles in gamepad mode) ───────
    // keyBit values must match MouseTarget.bit positions (see MouseTarget.kt).
    KB_VOLUME_UP  (-10, "🔊 Volume +",       keyBit = 10),
    KB_VOLUME_DOWN(-11, "🔉 Volume -",       keyBit = 11),
    KB_PLAY_PAUSE (-12, "⏯ Play / Pause",     keyBit = 12),
    KB_BACK       (-13, "⮌ Back",             keyBit = 5),
    KB_HOME       (-14, "🏠 Home",            keyBit = 8),
    KB_ENTER      (-15, "↵ Enter",            keyBit = 4),
    KB_DPAD_CENTER(-16, "● DPAD Center",      keyBit = 15),
    KB_ESCAPE     (-17, "ESC",                keyBit = 9),
    KB_TAB        (-18, "⇥ Tab",              keyBit = 6),
    KB_SPACE      (-19, "␣ Space",            keyBit = 7),
    KB_BACKSPACE  (-20, "⌫ Backspace",        keyBit = 14),
    KB_MENU       (-21, "☰ Menu (context)",   keyBit = 13),

    // Special actions (mask < 0, no keyBit). Edge-triggered on press in Kotlin.
    SCREENSHOT(-1, "📸 Take screenshot"),
}

/** Default mapping — reproduces the hardcoded mapping that existed before this feature. */
val DEFAULT_MAPPING: Map<SteamButton, XboxTarget> = mapOf(
    SteamButton.A       to XboxTarget.A,
    SteamButton.B       to XboxTarget.B,
    SteamButton.X       to XboxTarget.X,
    SteamButton.Y       to XboxTarget.Y,
    SteamButton.LB      to XboxTarget.LB,
    SteamButton.RB      to XboxTarget.RB,
    // Full-press trigger → itself by default (forces the axis fully to max on a hard
    // pull, on top of the analog 0..32767 that already routes to LT/RT via sendFrame()).
    SteamButton.LT      to XboxTarget.LT_TRIGGER,
    SteamButton.RT      to XboxTarget.RT_TRIGGER,
    SteamButton.LS      to XboxTarget.THUMBL,
    SteamButton.RS      to XboxTarget.THUMBR,
    SteamButton.MENU         to XboxTarget.START,
    SteamButton.VIEW         to XboxTarget.SELECT,
    SteamButton.STEAM        to XboxTarget.MODE,
    SteamButton.QUICK_ACCESS to XboxTarget.NONE,  // No default — user assigns
    // Extras have no default assignment — user must pick one
    SteamButton.L4      to XboxTarget.NONE,
    SteamButton.L5      to XboxTarget.NONE,
    SteamButton.R4      to XboxTarget.NONE,
    SteamButton.R5      to XboxTarget.NONE,
    SteamButton.GRIP_LT to XboxTarget.NONE,
    SteamButton.GRIP_RT to XboxTarget.NONE,
)
