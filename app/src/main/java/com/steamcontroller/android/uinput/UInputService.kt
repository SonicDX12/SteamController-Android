package com.steamcontroller.android.uinput

import com.steamcontroller.android.ipc.IUInputService

import android.content.Context
import android.util.Log
import java.io.File

// Bound by Shizuku.bindUserService() — this code runs in a separate process
// with shell UID (2000), which on most Android versions can open /dev/uinput.
//
// IMPORTANT: must have a no-arg constructor. Shizuku v13+ also tries the Context
// constructor first; either is acceptable.
class UInputService : IUInputService.Stub {

    companion object {
        private const val TAG = "UInputService"

        /**
         * Kills every other process running this same user service.
         *
         * When the app dies without unbinding (reinstall, update, force-stop, crash) Shizuku
         * leaves the old service alive, still holding its uinput devices: games then see
         * several Xbox pads, and an old enough one keeps a full-alphabet keyboard that hides
         * the soft keyboard everywhere. Measured 2026-10-18: five services alive at once after
         * a day of reinstalls. Their devices go away with them, as the kernel drops a uinput
         * device when its fd closes. Every instance runs as shell, so it may kill the others.
         */
        private fun killStaleInstances() {
            try {
                val myPid = android.os.Process.myPid()
                val myName = cmdline(myPid) ?: return
                File("/proc").listFiles()?.forEach { dir ->
                    val pid = dir.name.toIntOrNull() ?: return@forEach
                    if (pid != myPid && cmdline(pid) == myName) {
                        Log.w(TAG, "Killing stale $myName (pid $pid)")
                        android.os.Process.killProcess(pid)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "killStaleInstances failed: ${t.message}")
            }
        }

        private fun cmdline(pid: Int): String? = try {
            File("/proc/$pid/cmdline").readText().substringBefore('\u0000').takeIf { it.isNotEmpty() }
        } catch (_: Exception) { null }
    }

    @Suppress("unused")
    constructor() : super() {
        Log.i(TAG, "UInputService instantiated (no-arg)")
        killStaleInstances()
    }

    @Suppress("unused")
    constructor(context: Context?) : super() {
        Log.i(TAG, "UInputService instantiated (Context=$context)")
        killStaleInstances()
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

    override fun sendMouseFrame(relX: Int, relY: Int, scrollY: Int, keys: Int) {
        try {
            UInputNative.sendMouseFrame(relX, relY, scrollY, keys)
        } catch (t: Throwable) {
            Log.e(TAG, "sendMouseFrame failed: ${t.message}")
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

    override fun runShellCommandForOutput(cmd: Array<String>?): String? {
        if (cmd.isNullOrEmpty()) return null
        return try {
            val proc = ProcessBuilder(cmd.toList())
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            output.trim()
        } catch (t: Throwable) {
            Log.e(TAG, "runShellCommandForOutput failed: ${t.message}")
            null
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
