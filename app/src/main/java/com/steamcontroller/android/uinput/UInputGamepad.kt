package com.steamcontroller.android.uinput

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.steamcontroller.android.Prefs
import com.steamcontroller.android.input.SteamButton
import com.steamcontroller.android.input.StickCalibration
import com.steamcontroller.android.input.SystemActions
import com.steamcontroller.android.input.XboxTarget
import com.steamcontroller.android.parser.Buttons
import com.steamcontroller.android.parser.SteamControllerState
import rikka.shizuku.Shizuku

// High-level Kotlin API for the virtual Xbox 360 gamepad.
// Binds UInputService through Shizuku and translates SC2026 state into Xbox events.
class UInputGamepad(private val context: Context, initialProfile: GamepadProfile) {

    private val TAG = "UInputGamepad"
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
        try { service?.destroy() } catch (_: Throwable) {}
        Shizuku.unbindUserService(args, connection, true)
        bound = false
        service = null
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
            lastCalRefresh = now
        }
        val leftCal  = cachedLeftCal
        val rightCal = cachedRightCal

        // Apply the user-configurable button mapping.
        // target.mask > 0 → regular Xbox button bit (OR into the bitmask).
        // target.mask < 0 → special action (screenshot etc.), edge-triggered on press.
        var xboxButtons = 0
        for ((source, target) in cachedMapping) {
            val pressed = state.isButtonPressed(source.mask)
            when {
                target.mask > 0 && pressed -> {
                    xboxButtons = xboxButtons or target.mask
                }
                target.mask < 0 -> {
                    val wasPressed = (lastFrameButtons and source.mask) != 0
                    if (pressed && !wasPressed) handleSpecialAction(target)
                }
            }
        }
        lastFrameButtons = state.buttons

        // SC2026 sticks are already in Int16 range — direct passthrough
        // SC2026 triggers are 0-32767 → scale down to Xbox 0-255
        val lt = (state.leftTrigger  * 255 / 32767).coerceIn(0, 255)
        val rt = (state.rightTrigger * 255 / 32767).coerceIn(0, 255)

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
    }
}
