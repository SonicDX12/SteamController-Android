package com.steamcontroller.android.input

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Non-gamepad actions triggered by mapped Steam Controller buttons.
 * The actual shell command runs in the Shizuku UserService (UID shell, ~2000),
 * passed in as a `runShell` callback so this object stays decoupled from the IPC layer.
 */
object SystemActions {

    private const val TAG = "SystemActions"

    /**
     * Takes a screenshot via `screencap`, saves it under Pictures/Screenshots/,
     * notifies MediaScanner so it appears in the gallery, and toasts the user.
     * Runs the shell command on a background thread so the input pipeline isn't blocked.
     *
     * @param runShell function that executes a shell command via the Shizuku user service
     *                 and returns the exit code (or -1 on failure).
     */
    fun takeScreenshot(context: Context, runShell: (Array<String>) -> Int) {
        Thread({
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val dir = "/sdcard/Pictures/Screenshots"
                val path = "$dir/SteamCtrl_$timestamp.png"

                runShell(arrayOf("mkdir", "-p", dir))
                val exit = runShell(arrayOf("screencap", "-p", path))

                if (exit == 0) {
                    Log.i(TAG, "Screenshot saved: $path")
                    MediaScannerConnection.scanFile(
                        context, arrayOf(path), arrayOf("image/png"), null
                    )
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "Screenshot saved", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Log.e(TAG, "screencap failed, exit=$exit")
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "Screenshot failed (exit $exit)", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Screenshot threw: ${t.message}")
            }
        }, "screencap").apply { isDaemon = true; start() }
    }
}
