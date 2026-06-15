package com.steamcontroller.android.input

import com.steamcontroller.android.parser.Buttons
import com.steamcontroller.android.uinput.XboxButtons

enum class ButtonCategory(val title: String) {
    FACE("Face buttons"),
    BUMPERS("Bumpers"),
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
 * `mask` is the bit position in the IPC payload (see XboxButtons).
 * NONE (mask=0) means "disabled" — the source bit is ignored.
 */
enum class XboxTarget(val mask: Int, val displayName: String) {
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

    // Special actions (mask < 0). Don't OR into the Xbox button bitmask; they trigger
    // a Kotlin-side function instead, edge-triggered on press.
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
