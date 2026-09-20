package com.steamcontroller.android.usb

import android.hardware.usb.UsbDeviceConnection
import android.util.Log
import com.steamcontroller.android.protocol.SteamCommands

object SteamHidProtocol {

    private const val TAG = "SteamHidProtocol"

    // HID Set_Report control transfer parameters
    private const val BM_REQUEST_TYPE = 0x21  // host→device, class, interface
    private const val B_REQUEST_SET_REPORT = 0x09
    private const val REPORT_TYPE_OUTPUT = 0x02
    private const val REPORT_TYPE_FEATURE = 0x03
    private const val TIMEOUT_MS = 1000

    /**
     * Which feature report ID the firmware accepted. 0x01 is the documented one; some
     * firmware revisions only answer on 0x02, so we latch whichever works on first use.
     */
    @Volatile
    private var featureReportId = SteamCommands.FEATURE_REPORT_CMD

    private fun sendFeature(connection: UsbDeviceConnection, buf: ByteArray): Boolean {
        val wValue = (REPORT_TYPE_FEATURE shl 8) or (buf[0].toInt() and 0xFF)
        val result = connection.controlTransfer(
            BM_REQUEST_TYPE, B_REQUEST_SET_REPORT, wValue, 0, buf, buf.size, TIMEOUT_MS
        )
        return result >= 0
    }

    private fun sendOutput(connection: UsbDeviceConnection, buf: ByteArray): Boolean {
        val wValue = (REPORT_TYPE_OUTPUT shl 8) or (buf[0].toInt() and 0xFF)
        val result = connection.controlTransfer(
            BM_REQUEST_TYPE, B_REQUEST_SET_REPORT, wValue, 0, buf, buf.size, TIMEOUT_MS
        )
        return result >= 0
    }

    /**
     * Leave lizard mode (the firmware's keyboard/mouse emulation).
     *
     * Two steps, per the upstream reference: clear the digital button mappings, then put
     * both trackpads in raw mode. Until this lands the controller behaves as a keyboard
     * and mouse and the state reports carry firmware-processed trackpad data.
     *
     * On the first call, falls back to feature report 0x02 if 0x01 is rejected.
     */
    fun disableLizardMode(connection: UsbDeviceConnection): Boolean {
        if (!sendFeature(connection, SteamCommands.clearDigitalMappings(featureReportId))) {
            val fallback = SteamCommands.FEATURE_REPORT_CMD2
            if (featureReportId == fallback) {
                Log.e(TAG, "Failed to disable lizard mode on feature report 0x$fallback")
                return false
            }
            Log.w(TAG, "Feature report 0x$featureReportId rejected, retrying on 0x$fallback")
            featureReportId = fallback
            if (!sendFeature(connection, SteamCommands.clearDigitalMappings(featureReportId))) {
                Log.e(TAG, "Failed to disable lizard mode on both feature report IDs")
                return false
            }
        }
        val padsOk = sendFeature(connection, SteamCommands.disableTrackpadModes(featureReportId))
        if (!padsOk) Log.w(TAG, "Trackpad raw-mode command rejected")
        Log.i(TAG, "Lizard mode disabled (feature report 0x$featureReportId, trackpads=$padsOk)")
        return true
    }

    /**
     * Re-assert the lizard-off state. Must be called every
     * SteamCommands.KEEPALIVE_INTERVAL_MS while we are driving the controller: the
     * firmware reverts on its own after a silence, which brings back its autonomous
     * click haptics and keyboard/mouse emulation on top of ours.
     */
    fun heartbeat(connection: UsbDeviceConnection) {
        sendFeature(connection, SteamCommands.clearDigitalMappings(featureReportId))
        sendFeature(connection, SteamCommands.disableTrackpadModes(featureReportId))
    }

    /** Enable or disable the raw accel/gyro block in the state report. */
    fun setImuEnabled(connection: UsbDeviceConnection, enabled: Boolean): Boolean {
        val ok = sendFeature(connection, SteamCommands.setImuMode(enabled, featureReportId))
        Log.i(TAG, "setImuEnabled($enabled) → $ok")
        return ok
    }

    /** Hand the controller back to its firmware emulation. Call before disconnecting. */
    fun enableLizardMode(connection: UsbDeviceConnection) {
        sendFeature(connection, SteamCommands.restoreDefaultMappings(featureReportId))
        sendFeature(connection, SteamCommands.loadDefaultSettings(featureReportId))
        Log.i(TAG, "Lizard mode restored")
    }

    /** Continuous rumble. Magnitudes are 0..65535; (0, 0) stops. */
    fun sendRumble(connection: UsbDeviceConnection, strong: Int, weak: Int): Boolean =
        sendOutput(connection, SteamCommands.rumble(strong, weak))
}
