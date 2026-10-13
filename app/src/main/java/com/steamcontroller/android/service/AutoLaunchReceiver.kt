package com.steamcontroller.android.service

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.steamcontroller.android.Prefs
import com.steamcontroller.android.Transport

/**
 * Starts the service when the selected Bluetooth controller connects, app closed or not.
 *
 * Android 12+ forbids starting a foreground service from the background, with exemptions.
 * The one this relies on is "battery optimisation turned off for the app", which
 * SettingsActivity asks for when auto-launch is switched on. Without it the start below
 * throws and is logged, nothing worse.
 *
 * USB needs no receiver: the USB_DEVICE_ATTACHED filter on MainActivity already wakes us.
 */
class AutoLaunchReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        if (!Prefs.getAutoLaunch(context)) return
        if (Prefs.getTransport(context) != Transport.BLUETOOTH) return

        @Suppress("DEPRECATION")
        val device: BluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
        if (!device.address.equals(Prefs.getBluetoothAddress(context), ignoreCase = true)) return

        Log.i(TAG, "Selected controller connected, auto-launching service")
        try {
            context.startForegroundService(Intent(context, ControllerService::class.java))
        } catch (t: Throwable) {
            Log.e(TAG, "Auto-launch refused (battery optimisation still on?): ${t.message}")
        }
    }

    companion object {
        private const val TAG = "AutoLaunchReceiver"
    }
}
