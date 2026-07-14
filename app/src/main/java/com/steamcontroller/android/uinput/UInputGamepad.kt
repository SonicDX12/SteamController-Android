package com.steamcontroller.android.uinput

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.steamcontroller.android.Prefs
import com.steamcontroller.android.input.DEFAULT_MOUSE_MAPPING
import com.steamcontroller.android.input.MOUSE_LEFT_PAD_CLICK_BIT
import com.steamcontroller.android.input.MOUSE_MODE_FIXED_DPAD
import com.steamcontroller.android.input.MouseTarget
import com.steamcontroller.android.input.SteamButton
import com.steamcontroller.android.input.StickCalibration
import com.steamcontroller.android.input.SystemActions
import com.steamcontroller.android.input.XboxTarget
import com.steamcontroller.android.parser.Buttons
import com.steamcontroller.android.parser.SteamControllerState
import kotlin.math.abs
import rikka.shizuku.Shizuku

// High-level Kotlin API for the virtual Xbox 360 gamepad.
// Binds UInputService through Shizuku and translates SC2026 state into Xbox events.
class UInputGamepad(private val context: Context, initialProfile: GamepadProfile) {

    private val TAG = "UInputGamepad"

    companion object {
        // Sentinel meaning "the setting had no value before we touched it" (settings get
        // returns "null" as a string in that case) — restored by deleting the key, not by
        // writing the literal string "null".
        private const val SHOW_IME_UNSET_SENTINEL = "__unset__"
    }
    private var service: IUInputService? = null
    private var bound = false
    @Volatile private var profile: GamepadProfile = initialProfile

    /** Set by ControllerService to receive rumble commands from games. (strong, weak) ∈ [0, 65535]. */
    var onRumble: ((strong: Int, weak: Int) -> Unit)? = null

    private fun handleSpecialAction(target: XboxTarget) {
        when (target) {
            XboxTarget.SCREENSHOT -> SystemActions.takeScreenshot(context) { cmd ->
                try { service?.runShellCommand(cmd) ?: -1 } catch (_: Throwable) { -1 }
            }
            else -> {}
        }
    }
    private var rumbleThread: Thread? = null
    @Volatile private var rumbleThreadRunning = false

    // Calibration + mapping cache — refreshed every refreshIntervalMs instead of every frame
    @Volatile private var cachedLeftCal: StickCalibration = StickCalibration.DEFAULT
    @Volatile private var cachedRightCal: StickCalibration = StickCalibration.DEFAULT
    @Volatile private var cachedMapping: Map<SteamButton, XboxTarget> = emptyMap()
    @Volatile private var lastCalRefresh: Long = 0
    private val calRefreshIntervalMs = 250L  // ~4 Hz refresh, plenty for live tuning

    // Edge detection for special actions (screenshot etc.): we track the previous frame's
    // raw button bits so we can fire on 0 → 1 transitions only (not while held).
    private var lastFrameButtons: Int = 0

    // Mouse-mode state: previous trackpad position (for delta) and sensitivity cache.
    private var lastRightPadX: Int = 0
    private var lastRightPadY: Int = 0
    private var rightPadHadContact: Boolean = false
    // Left trackpad → scroll wheel state (used in gamepad sidecar mode).
    private var lastLeftPadY: Int = 0
    private var leftPadHadContact: Boolean = false
    @Volatile private var cachedMouseSensitivity: Float = 1f
    @Volatile private var cachedTrackpadAsMouse: Boolean = true
    // Trigger / pad scroll: accumulator so we can convert continuous 0..32767 deltas into discrete wheel ticks
    private var scrollAccumulator: Int = 0

    private val args = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, UInputService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("uinput")
        .debuggable(false)
        .version(1)

    @Volatile private var deviceReady = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = IUInputService.Stub.asInterface(binder)
            service = svc
            Log.i(TAG, "UInputService connected")
            // Binder calls can block — do them off the main thread
            Thread {
                try {
                    if (svc.canCreateDevice()) {
                        val ok = svc.createGamepad(profile.id)
                        deviceReady = ok
                        Log.i(TAG, "createGamepad(${profile.displayName}) → $ok")
                        if (ok) applyShowImeOverride(svc)
                    } else {
                        Log.e(TAG, "Cannot open /dev/uinput from shell UID — SELinux likely blocks it on this device")
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "init failed: ${t.message}")
                }
            }.start()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            deviceReady = false
            Log.w(TAG, "UInputService disconnected")
        }
    }

    fun bind() {
        if (bound) return
        Shizuku.bindUserService(args, connection)
        bound = true
        startRumbleThread()
    }

    fun unbind() {
        if (!bound) return
        stopRumbleThread()
        try { service?.let { restoreShowImeOverride(it) } } catch (_: Throwable) {}
        try { service?.destroy() } catch (_: Throwable) {}
        Shizuku.unbindUserService(args, connection, true)
        bound = false
        service = null
    }

    // Android's InputManager treats a paired/connected HID keyboard-classified device as a
    // hardware keyboard and suppresses the on-screen keyboard for every text field, system-wide,
    // for as long as it's attached. The Steam Controller's own USB/BT HID interfaces can trigger
    // this classification independently of our uinput device (e.g. a legacy "boot keyboard" HID
    // interface used for lizard mode, auto-bound by the kernel/Bluetooth stack outside our app's
    // control). Forcing Settings.Secure.show_ime_with_hard_keyboard=1 via the shell UID makes
    // Android show the soft keyboard regardless. Reverted on unbind() so a real Bluetooth
    // keyboard paired later behaves normally.
    private fun applyShowImeOverride(svc: IUInputService) {
        try {
            if (Prefs.getSavedShowImeHardKeyboard(context) == null) {
                val current = try {
                    svc.runShellCommandForOutput(arrayOf("settings", "get", "secure", "show_ime_with_hard_keyboard"))
                } catch (_: Throwable) { null }
                val toSave = current?.takeIf { it.isNotBlank() && it != "null" } ?: SHOW_IME_UNSET_SENTINEL
                Prefs.setSavedShowImeHardKeyboard(context, toSave)
            }
            svc.runShellCommand(arrayOf("settings", "put", "secure", "show_ime_with_hard_keyboard", "1"))
            Log.i(TAG, "show_ime_with_hard_keyboard forced on")
        } catch (t: Throwable) {
            Log.w(TAG, "applyShowImeOverride failed: ${t.message}")
        }
    }

    private fun restoreShowImeOverride(svc: IUInputService) {
        try {
            val saved = Prefs.getSavedShowImeHardKeyboard(context) ?: return
            if (saved == SHOW_IME_UNSET_SENTINEL) {
                svc.runShellCommand(arrayOf("settings", "delete", "secure", "show_ime_with_hard_keyboard"))
            } else {
                svc.runShellCommand(arrayOf("settings", "put", "secure", "show_ime_with_hard_keyboard", saved))
            }
            Prefs.clearSavedShowImeHardKeyboard(context)
            Log.i(TAG, "show_ime_with_hard_keyboard restored to '$saved'")
        } catch (t: Throwable) {
            Log.w(TAG, "restoreShowImeOverride failed: ${t.message}")
        }
    }

    /**
     * Poll the user service for FF (rumble) events triggered by games.
     * Runs at 50 Hz — Android emits FF events at the game's frame rate (~60 Hz)
     * so this is fast enough without burning binder calls.
     */
    private fun startRumbleThread() {
        if (rumbleThreadRunning) return
        rumbleThreadRunning = true
        rumbleThread = Thread({
            while (rumbleThreadRunning) {
                try {
                    val svc = service
                    if (svc != null && deviceReady) {
                        val rumble = svc.pollForceFeedback()
                        if (rumble != null && rumble.size == 2) {
                            onRumble?.invoke(rumble[0], rumble[1])
                        }
                    }
                } catch (_: Throwable) { /* IPC may fail during unbind, ignore */ }
                try { Thread.sleep(20) } catch (_: InterruptedException) { break }
            }
        }, "uinput-ff-poll").apply { isDaemon = true; start() }
    }

    private fun stopRumbleThread() {
        rumbleThreadRunning = false
        rumbleThread?.interrupt()
        rumbleThread = null
    }

    val isReady get() = service != null && deviceReady

    /**
     * Swap the emulated controller profile without unbinding the user service.
     * The native code recreates the /dev/uinput device with the new VID/PID.
     * Returns true on success.
     */
    fun switchProfile(newProfile: GamepadProfile): Boolean {
        val svc = service ?: return false
        return try {
            val ok = svc.createGamepad(newProfile.id)
            if (ok) {
                profile = newProfile
                Log.i(TAG, "Switched profile to ${newProfile.displayName}")
            } else {
                Log.e(TAG, "createGamepad(${newProfile.displayName}) returned false")
            }
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "switchProfile failed: ${t.message}")
            false
        }
    }

    // Push one HID frame from the SC2026 parser, translated to Xbox 360 layout.
    fun pushFrame(state: SteamControllerState) {
        val svc = service ?: return

        // Refresh cached calibrations + button mapping from prefs at most every 250ms
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastCalRefresh > calRefreshIntervalMs) {
            cachedLeftCal  = Prefs.getLeftCalibration(context)
            cachedRightCal = Prefs.getRightCalibration(context)
            cachedMapping  = Prefs.getAllMappings(context)
            cachedMouseSensitivity = Prefs.getMouseSensitivity(context)
            cachedTrackpadAsMouse  = Prefs.getTrackpadAsMouseInGamepad(context)
            lastCalRefresh = now
        }

        if (profile.isMouseMode) {
            pushMouseFrame(svc, state)
            return
        }

        val leftCal  = cachedLeftCal
        val rightCal = cachedRightCal

        // Apply the user-configurable button mapping.
        //   target.mask > 0       → regular Xbox button bit (OR into the bitmask).
        //   target.keyBit >= 0    → sidecar keyboard key (OR into the sidecar key bitmask).
        //   target.triggerSide!=0 → force LT (1) / RT (2) axis to max.
        //   target.mask < 0       → special action, edge-triggered on press.
        var xboxButtons = 0
        var sidecarMappedKeys = 0
        var ltOverride = 0
        var rtOverride = 0
        for ((source, target) in cachedMapping) {
            val pressed = state.isButtonPressed(source.mask)
            when {
                target.mask > 0 && pressed -> {
                    xboxButtons = xboxButtons or target.mask
                }
                target.keyBit >= 0 && pressed -> {
                    sidecarMappedKeys = sidecarMappedKeys or (1 shl target.keyBit)
                }
                target.triggerSide == 1 && pressed -> { ltOverride = 255 }
                target.triggerSide == 2 && pressed -> { rtOverride = 255 }
                target.mask < 0 && target.keyBit < 0 && target.triggerSide == 0 -> {
                    val wasPressed = (lastFrameButtons and source.mask) != 0
                    if (pressed && !wasPressed) handleSpecialAction(target)
                }
            }
        }
        lastFrameButtons = state.buttons

        // SC2026 sticks are already in Int16 range — direct passthrough
        // SC2026 triggers are 0-32767 → scale down to Xbox 0-255.
        // ltOverride/rtOverride bump the axis to max when a remapped source is pressed.
        val ltAnalog = (state.leftTrigger  * 255 / 32767).coerceIn(0, 255)
        val rtAnalog = (state.rightTrigger * 255 / 32767).coerceIn(0, 255)
        val lt = maxOf(ltAnalog, ltOverride)
        val rt = maxOf(rtAnalog, rtOverride)

        val dpadX = when {
            state.isButtonPressed(Buttons.DPAD_RIGHT) ->  1
            state.isButtonPressed(Buttons.DPAD_LEFT)  -> -1
            else -> 0
        }
        val dpadY = when {
            state.isButtonPressed(Buttons.DPAD_DOWN) ->  1
            state.isButtonPressed(Buttons.DPAD_UP)   -> -1
            else -> 0
        }
        val (lxCal, lyCalRaw) = leftCal.apply(state.leftJoyX.toInt(),  state.leftJoyY.toInt())
        val (rxCal, ryCalRaw) = rightCal.apply(state.rightJoyX.toInt(), state.rightJoyY.toInt())

        // SC2026 reports Y positive = up; Linux input ABS_Y convention is Y positive = down.
        val ly = -lyCalRaw.coerceAtLeast(-32767)
        val ry = -ryCalRaw.coerceAtLeast(-32767)

        try {
            svc.sendFrame(
                xboxButtons,
                lxCal, ly,
                rxCal, ry,
                lt, rt,
                dpadX, dpadY
            )
        } catch (t: Throwable) {
            Log.e(TAG, "sendFrame IPC failed: ${t.message}")
        }

        // Sidecar mouse + keyboard while a gamepad profile is active.
        // - cachedTrackpadAsMouse gates trackpad-driven cursor + scroll
        // - mapped keyboard keys (sidecarMappedKeys) always flow through, even when
        //   the trackpad-as-mouse toggle is off, so back-paddle keyboard mappings work.
        pushSidecarFrame(svc, state, sidecarMappedKeys)
    }

    /**
     * Sidecar frame for gamepad mode: trackpad-as-mouse + keyboard targets for
     * back paddles. Skips emitting anything when nothing happens this frame —
     * keeping the mouse fd idle is critical so Android IME focus isn't stolen by
     * a phantom cursor (same rationale as in Desktop mode).
     */
    private fun pushSidecarFrame(svc: IUInputService, state: SteamControllerState, mappedKeys: Int) {
        val (relX, relY, scrollTicks) = if (cachedTrackpadAsMouse) {
            val (rx, ry) = computeRightPadDelta(state)
            Triple(rx, ry, computeLeftPadScroll(state))
        } else {
            // Still reset accumulators / contact flags so a re-enable mid-session
            // doesn't trigger a phantom delta on first touch.
            rightPadHadContact = false
            leftPadHadContact = false
            scrollAccumulator = 0
            Triple(0, 0, 0)
        }

        var keys = mappedKeys
        // Left trackpad click → left mouse click (only active when sidecar mouse is on).
        if (cachedTrackpadAsMouse && state.isButtonPressed(MOUSE_LEFT_PAD_CLICK_BIT)) {
            keys = keys or (1 shl MouseTarget.BTN_LEFT.bit)
        }
        if (relX == 0 && relY == 0 && scrollTicks == 0 && keys == 0) return
        try {
            svc.sendMouseFrame(relX, relY, scrollTicks, keys)
        } catch (t: Throwable) {
            Log.e(TAG, "sendMouseFrame (sidecar) IPC failed: ${t.message}")
        }
    }

    /** Right trackpad delta (in mouse-cursor units). Resets cleanly on lift-off. */
    private fun computeRightPadDelta(state: SteamControllerState): Pair<Int, Int> {
        val touching = state.isButtonPressed(Buttons.TP_RT)
        if (!touching) {
            rightPadHadContact = false
            return 0 to 0
        }
        val curX = state.rightPadX.toInt()
        val curY = state.rightPadY.toInt()
        var relX = 0
        var relY = 0
        if (rightPadHadContact) {
            val sens = cachedMouseSensitivity
            relX = ((curX - lastRightPadX) / 128f * sens).toInt()
            // SC2026 Y up positive → mouse Y down positive: invert
            relY = (-(curY - lastRightPadY) / 128f * sens).toInt()
        }
        lastRightPadX = curX
        lastRightPadY = curY
        rightPadHadContact = true
        return relX to relY
    }

    /** Left trackpad vertical → wheel ticks. One tick per ~1000 accumulator units. */
    private fun computeLeftPadScroll(state: SteamControllerState): Int {
        val touching = state.isButtonPressed(Buttons.TP_LT)
        if (!touching) {
            leftPadHadContact = false
            scrollAccumulator = 0
            return 0
        }
        val curY = state.leftPadY.toInt()
        if (leftPadHadContact) {
            // Y positive = up on SC2026; scroll wheel positive = up → keep sign.
            scrollAccumulator += (curY - lastLeftPadY) / 8
        }
        lastLeftPadY = curY
        leftPadHadContact = true
        if (abs(scrollAccumulator) < 1000) return 0
        val ticks = scrollAccumulator / 1000
        scrollAccumulator -= ticks * 1000
        return ticks
    }

    /**
     * Desktop / mouse mode: right trackpad → cursor delta, triggers → scroll wheel,
     * face/system buttons → mapped keys, DPAD → arrow keys, left pad click → right mouse.
     * Special actions (e.g. SCREENSHOT) still fire via the gamepad mapping.
     */
    private fun pushMouseFrame(svc: IUInputService, state: SteamControllerState) {
        // Right trackpad → cursor delta; left trackpad vertical → scroll wheel.
        // Same helpers as the gamepad sidecar mode so the gesture is identical.
        val (relX, relY)   = computeRightPadDelta(state)
        val scrollTicks    = computeLeftPadScroll(state)

        // ── Key/mouse-button bitmask ─────────────────────────────────────────
        var keys = 0

        // Customisable face/system mapping (uses MOUSE-mode defaults, no Prefs persistence in V1.1).
        for ((source, target) in DEFAULT_MOUSE_MAPPING) {
            if (target.bit >= 0 && state.isButtonPressed(source.mask)) {
                keys = keys or (1 shl target.bit)
            }
        }

        // Fixed DPAD → arrow keys
        for ((mask, target) in MOUSE_MODE_FIXED_DPAD) {
            if (state.isButtonPressed(mask)) keys = keys or (1 shl target.bit)
        }

        // Left trackpad click → right mouse click
        if (state.isButtonPressed(MOUSE_LEFT_PAD_CLICK_BIT)) {
            keys = keys or (1 shl MouseTarget.BTN_RIGHT.bit)
        }

        // Special actions (screenshot) still honoured via the gamepad mapping table —
        // keeps QA → screenshot working even in mouse mode.
        for ((source, target) in cachedMapping) {
            if (target.mask < 0) {
                val pressed = state.isButtonPressed(source.mask)
                val wasPressed = (lastFrameButtons and source.mask) != 0
                if (pressed && !wasPressed) handleSpecialAction(target)
            }
        }
        lastFrameButtons = state.buttons

        try {
            svc.sendMouseFrame(relX, relY, scrollTicks, keys)
        } catch (t: Throwable) {
            Log.e(TAG, "sendMouseFrame IPC failed: ${t.message}")
        }
    }
}
