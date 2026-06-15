package com.steamcontroller.android.uinput

import android.content.Context
import android.util.Log

// Bound by Shizuku.bindUserService() — this code runs in a separate process
// with shell UID (2000), which on most Android versions can open /dev/uinput.
//
// IMPORTANT: must have a no-arg constructor. Shizuku v13+ also tries the Context
// constructor first; either is acceptable.
class UInputService : IUInputService.Stub {

    companion object { private const val TAG = "UInputService" }

    @Suppress("unused")
    constructor() : super() {
        Log.i(TAG, "UInputService instantiated (no-arg)")
    }

    @Suppress("unused")
    constructor(context: Context?) : super() {
        Log.i(TAG, "UInputService instantiated (Context=$context)")
    }

    override fun canCreateDevice(): Boolean {
        return try {
            UInputNative.canOpen()
        } catch (t: Throwable) {
            Log.e(TAG, "canOpen failed: ${t.message}")
            false
        }
    }

    override fun createGamepad(profileId: Int): Boolean {
        return try {
            UInputNative.createDevice(profileId)
        } catch (t: Throwable) {
            Log.e(TAG, "createDevice failed: ${t.message}")
            false
        }
    }

    override fun sendFrame(
        buttons: Int,
        leftStickX: Int, leftStickY: Int,
        rightStickX: Int, rightStickY: Int,
        leftTrigger: Int, rightTrigger: Int,
        dpadX: Int, dpadY: Int
    ) {
        try {
            UInputNative.sendFrame(
                buttons,
                leftStickX, leftStickY,
                rightStickX, rightStickY,
                leftTrigger, rightTrigger,
                dpadX, dpadY
            )
        } catch (t: Throwable) {
            Log.e(TAG, "sendFrame failed: ${t.message}")
        }
    }

    override fun pollForceFeedback(): IntArray? {
        return try {
            UInputNative.pollFFEvent()
        } catch (t: Throwable) {
            Log.e(TAG, "pollFFEvent failed: ${t.message}")
            null
        }
    }

    override fun runShellCommand(cmd: Array<String>?): Int {
        if (cmd.isNullOrEmpty()) return -1
        return try {
            val proc = ProcessBuilder(cmd.toList())
                .redirectErrorStream(true)
                .start()
            val exit = proc.waitFor()
            Log.i(TAG, "runShellCommand ${cmd.joinToString(" ")} → exit=$exit")
            exit
        } catch (t: Throwable) {
            Log.e(TAG, "runShellCommand failed: ${t.message}")
            -1
        }
    }

    override fun destroy() {
        try { UInputNative.destroy() } catch (t: Throwable) {
            Log.e(TAG, "destroy native failed: ${t.message}")
        }
        // Shizuku contract: destroy() must terminate the process
        System.exit(0)
    }
}
