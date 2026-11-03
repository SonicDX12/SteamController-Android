package com.steamcontroller.android.uinput

import com.steamcontroller.android.ipc.IUInputService

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import android.view.MotionEvent
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
import com.steamcontroller.android.ui.TouchCursorOverlay
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
    @Volatile private var cachedCursorHoldSens: Float = 1f
    @Volatile private var cachedCursorHoldScrollSpeed: Float = 1f
    @Volatile private var cachedCursorHoldClickMask: Int = Buttons.A or Buttons.RT_FULL
    @Volatile private var cachedCursorHoldTouchMode: Boolean = false
    // Trigger / pad scroll: accumulator so we can convert continuous 0..32767 deltas into discrete wheel ticks
    private var scrollAccumulator: Int = 0

    // Joystick-driven Cursor Hold Mode state: fractional sub-pixel & scroll accumulators
    private var joyMouseAccX: Float = 0f
    private var joyMouseAccY: Float = 0f
    private var joyScrollAcc: Float = 0f
    private var wasCursorHoldActive: Boolean = false
    private var lastSidecarKeys: Int = 0
    // Base cursor speed in pixels per frame at full stick deflection (1.0x sensitivity).
    // At ~150Hz (BLE) or ~240Hz (USB), 8f * sens gives smooth, responsive pointer travel.
    private val joyCursorBaseSpeed = 8f

    // Touch Simulation mode state: screen coordinates, touch down state, and floating overlay
    private val touchOverlay = TouchCursorOverlay(context)
    private var touchCursorInitialized = false
    private var touchCursorX: Float = 0f
    private var touchCursorY: Float = 0f
    private var wasTouchClicking: Boolean = false
    private var wasTouchScrolling: Boolean = false
    private var touchScrollOriginX: Float = 0f
    private var touchScrollOriginY: Float = 0f

    private val args = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, UInputService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("uinput")
        .debuggable(false)
        // Bumped with every IUInputService change: Shizuku reuses a running service of the
        // same version, and an old one would not know the newer methods.
        .version(4)

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
        touchOverlay.destroy()
        try { service?.let { restoreShowImeOverride(it) } } catch (_: Throwable) {}
        try { service?.destroy() } catch (_: Throwable) {}
        Shizuku.unbindUserService(args, connection, true)
        bound = false
        service = null
    }

    // Android's InputManager treats a connected HID keyboard-classified device as a
    // hardware keyboard and suppresses the on-screen keyboard for every text field,
    // system-wide, for as long as it's attached. The Steam Controller's own USB/BT HID
    // interfaces can trigger this classification independently of our uinput device.
    // Forcing Settings.Secure.show_ime_with_hard_keyboard=1 via the shell UID makes Android
    // show the soft keyboard regardless. Reverted on unbind() so a real Bluetooth keyboard
    // paired later behaves normally.
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
            svc.runShellCommand(arrayOf("appops", "set", context.packageName, "SYSTEM_ALERT_WINDOW", "allow"))
            Log.i(TAG, "show_ime_with_hard_keyboard forced on & SYSTEM_ALERT_WINDOW allowed")
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
                lastSidecarKeys = 0
                wasCursorHoldActive = false
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
            cachedCursorHoldSens        = Prefs.getCursorHoldSensitivity(context)
            cachedCursorHoldScrollSpeed = Prefs.getCursorHoldScrollSpeed(context)
            cachedCursorHoldClickMask   = Prefs.getCursorHoldClickMask(context)
            cachedCursorHoldTouchMode   = Prefs.getCursorHoldTouchMode(context)
            lastCalRefresh = now
        }

        if (profile.isMouseMode) {
            pushMouseFrame(svc, state)
            return
        }

        val leftCal  = cachedLeftCal
        val rightCal = cachedRightCal

        // Check if any button mapped to CURSOR_HOLD is currently held down.
        var cursorHoldActive = false
        for ((source, target) in cachedMapping) {
            if (target == XboxTarget.CURSOR_HOLD && state.isButtonPressed(source.mask)) {
                cursorHoldActive = true
                break
            }
        }

        val clickMask = cachedCursorHoldClickMask
        val touchMode = cachedCursorHoldTouchMode
        var cursorClickPressed = false

        // Apply the user-configurable button mapping.
        //   target.mask > 0       → regular Xbox button bit (OR into the bitmask).
        //   target.keyBit >= 0    → sidecar keyboard/mouse key (OR into the sidecar key bitmask).
        //   target.triggerSide!=0 → force LT (1) / RT (2) axis to max.
        //   target.mask < 0       → special action, edge-triggered on press.
        var xboxButtons = 0
        var sidecarMappedKeys = 0
        var ltOverride = 0
        var rtOverride = 0
        for ((source, target) in cachedMapping) {
            val pressed = state.isButtonPressed(source.mask)
            if (target == XboxTarget.CURSOR_HOLD) continue

            // When Cursor Hold Mode is active, any button in `clickMask` acts as Click/Confirm
            // and is suppressed from reaching the virtual gamepad so the game doesn't react.
            if (cursorHoldActive && (clickMask and source.mask) != 0) {
                if (pressed) {
                    if (touchMode) {
                        cursorClickPressed = true
                    } else {
                        sidecarMappedKeys = sidecarMappedKeys or (1 shl MouseTarget.BTN_LEFT.bit)
                    }
                }
                continue
            }

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
        // In Cursor Hold Mode, if LT or RT is bound as a click button, its analog axis is suppressed
        // and pulling past 50% (>16384) also triggers a Click.
        val ltIsClick = cursorHoldActive && (clickMask and Buttons.LT_FULL) != 0
        val rtIsClick = cursorHoldActive && (clickMask and Buttons.RT_FULL) != 0
        val ltAnalog = if (ltIsClick) 0 else (state.leftTrigger  * 255 / 32767).coerceIn(0, 255)
        val rtAnalog = if (rtIsClick) 0 else (state.rightTrigger * 255 / 32767).coerceIn(0, 255)
        if ((rtIsClick && state.rightTrigger > 16384) || (ltIsClick && state.leftTrigger > 16384)) {
            if (touchMode) {
                cursorClickPressed = true
            } else {
                sidecarMappedKeys = sidecarMappedKeys or (1 shl MouseTarget.BTN_LEFT.bit)
            }
        }
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

        // When Cursor Hold Mode is active, suppress stick deflection from reaching the gamepad
        val padLx = if (cursorHoldActive) 0 else lxCal
        val padLy = if (cursorHoldActive) 0 else ly
        val padRx = if (cursorHoldActive) 0 else rxCal
        val padRy = if (cursorHoldActive) 0 else ry

        try {
            svc.sendFrame(
                xboxButtons,
                padLx, padLy,
                padRx, padRy,
                lt, rt,
                dpadX, dpadY
            )
        } catch (t: Throwable) {
            Log.e(TAG, "sendFrame IPC failed: ${t.message}")
        }

        // Sidecar mouse + keyboard while a gamepad profile is active.
        // - cachedTrackpadAsMouse gates trackpad-driven cursor + scroll
        // - cursorHoldActive drives cursor with Left Stick and scroll with Right Stick
        // - mapped keyboard/mouse keys (sidecarMappedKeys) always flow through
        pushSidecarFrame(
            svc, state, sidecarMappedKeys,
            cursorHoldActive, touchMode, cursorClickPressed,
            lxCal, ly, rxCal, ry, ryCalRaw
        )
    }

    /**
     * Sidecar frame for gamepad mode: trackpad-as-mouse, joystick cursor-hold mode,
     * and keyboard/mouse targets for back paddles. Skips emitting anything when nothing
     * happens this frame — keeping the mouse fd idle is critical so Android IME focus
     * isn't stolen by a phantom cursor (same rationale as in Desktop mode).
     */
    private fun pushSidecarFrame(
        svc: IUInputService,
        state: SteamControllerState,
        mappedKeys: Int,
        cursorHoldActive: Boolean,
        touchMode: Boolean,
        cursorClickPressed: Boolean,
        joyLx: Int,
        joyLyDown: Int,
        joyRx: Int,
        joyRyDown: Int,
        joyRyUp: Int,
    ) {
        val (padRelX, padRelY, padScrollTicks) = if (cachedTrackpadAsMouse) {
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

        var joyRelX = 0
        var joyRelY = 0
        var joyScrollTicks = 0
        var forceNudge = false

        if (cursorHoldActive) {
            val sens = cachedCursorHoldSens
            val scrollSpeed = cachedCursorHoldScrollSpeed
            // Left Stick → cursor delta (with quadratic response curve so fine adjustments are easy)
            val normX = (joyLx / 32767f).coerceIn(-1f, 1f)
            val normY = (joyLyDown / 32767f).coerceIn(-1f, 1f)
            val curvedX = normX * (0.4f + 0.6f * abs(normX))
            val curvedY = normY * (0.4f + 0.6f * abs(normY))

            if (touchMode) {
                val dm = context.resources.displayMetrics
                val screenW = dm.widthPixels.toFloat().coerceAtLeast(1f)
                val screenH = dm.heightPixels.toFloat().coerceAtLeast(1f)
                // Scale by display density so 1.0x sensitivity matches the physical screen speed
                // of Android's DPI-scaled + velocity-accelerated hardware mouse pointer.
                val densityScale = dm.density.coerceIn(1.5f, 3.5f)
                if (!touchCursorInitialized) {
                    touchCursorX = screenW / 2f
                    touchCursorY = screenH / 2f
                    touchCursorInitialized = true
                }

                val scrollNormX = (joyRx / 32767f).coerceIn(-1f, 1f)
                val scrollNormY = (joyRyDown / 32767f).coerceIn(-1f, 1f)
                val isTouchScrolling = !cursorClickPressed &&
                    (abs(scrollNormX) > 0.12f || abs(scrollNormY) > 0.12f)

                if (isTouchScrolling) {
                    if (wasTouchClicking) {
                        try { svc.injectTouch(MotionEvent.ACTION_UP, touchCursorX, touchCursorY) } catch (_: Throwable) {}
                        wasTouchClicking = false
                    }
                    if (!wasTouchScrolling) {
                        touchScrollOriginX = touchCursorX
                        touchScrollOriginY = touchCursorY
                        try { svc.injectTouch(MotionEvent.ACTION_DOWN, touchCursorX, touchCursorY) } catch (_: Throwable) {}
                        wasTouchScrolling = true
                    }
                    val scrollCurvedX = scrollNormX * (0.4f + 0.6f * abs(scrollNormX))
                    val scrollCurvedY = scrollNormY * (0.4f + 0.6f * abs(scrollNormY))
                    val stepX = scrollCurvedX * joyCursorBaseSpeed * densityScale * scrollSpeed
                    val stepY = scrollCurvedY * joyCursorBaseSpeed * densityScale * scrollSpeed
                    val nextX = touchCursorX + stepX
                    val nextY = touchCursorY + stepY
                    val edgeMargin = 32f * densityScale
                    val maxStroke = minOf(screenW, screenH) * 0.40f
                    val hitEdge = nextX <= edgeMargin || nextX >= screenW - edgeMargin ||
                        nextY <= edgeMargin || nextY >= screenH - edgeMargin
                    val hitMaxStroke = abs(nextX - touchScrollOriginX) > maxStroke ||
                        abs(nextY - touchScrollOriginY) > maxStroke

                    if (hitEdge || hitMaxStroke) {
                        try {
                            svc.injectTouch(MotionEvent.ACTION_UP, touchCursorX, touchCursorY)
                            touchCursorX = touchScrollOriginX
                            touchCursorY = touchScrollOriginY
                            svc.injectTouch(MotionEvent.ACTION_DOWN, touchCursorX, touchCursorY)
                        } catch (_: Throwable) {}
                    } else {
                        touchCursorX = nextX.coerceIn(0f, screenW - 1f)
                        touchCursorY = nextY.coerceIn(0f, screenH - 1f)
                        try {
                            svc.injectTouch(MotionEvent.ACTION_MOVE, touchCursorX, touchCursorY)
                        } catch (_: Throwable) {}
                    }
                    touchOverlay.update(true, touchCursorX, touchCursorY, true)
                } else {
                    if (wasTouchScrolling) {
                        try { svc.injectTouch(MotionEvent.ACTION_UP, touchCursorX, touchCursorY) } catch (_: Throwable) {}
                        touchCursorX = touchScrollOriginX
                        touchCursorY = touchScrollOriginY
                        wasTouchScrolling = false
                    }
                    val prevX = touchCursorX
                    val prevY = touchCursorY
                    touchCursorX = (touchCursorX + curvedX * joyCursorBaseSpeed * densityScale * sens).coerceIn(0f, screenW - 1f)
                    touchCursorY = (touchCursorY + curvedY * joyCursorBaseSpeed * densityScale * sens).coerceIn(0f, screenH - 1f)
                    touchOverlay.update(true, touchCursorX, touchCursorY, cursorClickPressed)

                    try {
                        if (cursorClickPressed && !wasTouchClicking) {
                            svc.injectTouch(MotionEvent.ACTION_DOWN, touchCursorX, touchCursorY)
                        } else if (cursorClickPressed && wasTouchClicking) {
                            if (abs(touchCursorX - prevX) >= 0.5f || abs(touchCursorY - prevY) >= 0.5f) {
                                svc.injectTouch(MotionEvent.ACTION_MOVE, touchCursorX, touchCursorY)
                            }
                        } else if (!cursorClickPressed && wasTouchClicking) {
                            svc.injectTouch(MotionEvent.ACTION_UP, touchCursorX, touchCursorY)
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "injectTouch IPC failed: ${t.message}")
                    }
                    wasTouchClicking = cursorClickPressed
                }
            } else {
                if (wasTouchScrolling) {
                    try { svc.injectTouch(MotionEvent.ACTION_UP, touchCursorX, touchCursorY) } catch (_: Throwable) {}
                    touchCursorX = touchScrollOriginX
                    touchCursorY = touchScrollOriginY
                    wasTouchScrolling = false
                }
                if (wasTouchClicking) {
                    try { svc.injectTouch(MotionEvent.ACTION_UP, touchCursorX, touchCursorY) } catch (_: Throwable) {}
                    wasTouchClicking = false
                }
                touchOverlay.update(false, touchCursorX, touchCursorY, false)
                if (!wasCursorHoldActive) {
                    try { svc.setMouseConnected(true) } catch (_: Throwable) {}
                }
                joyMouseAccX += curvedX * joyCursorBaseSpeed * sens
                joyMouseAccY += curvedY * joyCursorBaseSpeed * sens
                joyRelX = joyMouseAccX.toInt()
                joyRelY = joyMouseAccY.toInt()
                joyMouseAccX -= joyRelX
                joyMouseAccY -= joyRelY

                // Right Stick vertical → scroll wheel ticks
                val normScroll = (joyRyUp / 32767f).coerceIn(-1f, 1f)
                joyScrollAcc += normScroll * 0.25f * scrollSpeed
                if (abs(joyScrollAcc) >= 1f) {
                    joyScrollTicks = joyScrollAcc.toInt()
                    joyScrollAcc -= joyScrollTicks
                }

                // On the initial press frame of Cursor Hold Mode, nudge by 1px if stationary
                // so Android's system pointer immediately appears on screen.
                if (!wasCursorHoldActive && joyRelX == 0 && joyRelY == 0 && padRelX == 0 && padRelY == 0) {
                    joyRelX = 1
                    forceNudge = true
                }
            }
        } else {
            joyMouseAccX = 0f
            joyMouseAccY = 0f
            joyScrollAcc = 0f
            if (wasTouchScrolling) {
                try { svc.injectTouch(MotionEvent.ACTION_UP, touchCursorX, touchCursorY) } catch (_: Throwable) {}
                touchCursorX = touchScrollOriginX
                touchCursorY = touchScrollOriginY
                wasTouchScrolling = false
            }
            if (wasTouchClicking) {
                try { svc.injectTouch(MotionEvent.ACTION_UP, touchCursorX, touchCursorY) } catch (_: Throwable) {}
                wasTouchClicking = false
            }
            if (wasCursorHoldActive) {
                touchOverlay.update(false, touchCursorX, touchCursorY, false)
                // Destroy the sidecar mouse uinput device when the hold button is released
                // so Android's PointerController immediately hides the on-screen cursor.
                try { svc.setMouseConnected(false) } catch (_: Throwable) {}
            }
        }
        wasCursorHoldActive = cursorHoldActive

        val relX = padRelX + joyRelX
        val relY = padRelY + joyRelY
        val scrollTicks = padScrollTicks + joyScrollTicks

        var keys = mappedKeys
        // Left trackpad click → left mouse click (only active when sidecar mouse is on).
        if (cachedTrackpadAsMouse && state.isButtonPressed(MOUSE_LEFT_PAD_CLICK_BIT)) {
            keys = keys or (1 shl MouseTarget.BTN_LEFT.bit)
        }
        // Always emit if keys changed (e.g. releasing a mouse click when cursorHoldActive ends)
        if (relX == 0 && relY == 0 && scrollTicks == 0 && keys == lastSidecarKeys && !forceNudge) return
        lastSidecarKeys = keys
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
