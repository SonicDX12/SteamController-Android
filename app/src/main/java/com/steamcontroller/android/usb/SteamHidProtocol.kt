package com.steamcontroller.android.usb

import android.hardware.usb.UsbDeviceConnection
import android.util.Log

object SteamHidProtocol {

    private const val TAG = "SteamHidProtocol"

    // Feature report 0x85: disable lizard mode (keyboard/mouse emulation)
    private val DISABLE_LIZARD = byteArrayOf(0x85.toByte())

    // HID Set_Report control transfer parameters
    private const val BM_REQUEST_TYPE = 0x21  // host→device, class, interface
    private const val B_REQUEST_SET_REPORT = 0x09
    private const val W_VALUE_FEATURE_REPORT = 0x0300  // Feature (0x03) + report ID (0x00)
    private const val TIMEOUT_MS = 1000

    fun disableLizardMode(connection: UsbDeviceConnection): Boolean {
        val result = connection.controlTransfer(
            BM_REQUEST_TYPE,
            B_REQUEST_SET_REPORT,
            W_VALUE_FEATURE_REPORT,
            0,
            DISABLE_LIZARD,
            DISABLE_LIZARD.size,
            TIMEOUT_MS
        )
        if (result < 0) {
            Log.e(TAG, "Failed to disable lizard mode: $result")
            return false
        }
        Log.i(TAG, "Lizard mode disabled")
        return true
    }

    // Call heartbeat() every 800ms to keep lizard mode disabled
    fun heartbeat(connection: UsbDeviceConnection) {
        connection.controlTransfer(
            BM_REQUEST_TYPE,
            B_REQUEST_SET_REPORT,
            W_VALUE_FEATURE_REPORT,
            0,
            DISABLE_LIZARD,
            DISABLE_LIZARD.size,
            TIMEOUT_MS
        )
    }
}
