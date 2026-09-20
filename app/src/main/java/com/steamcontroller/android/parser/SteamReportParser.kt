package com.steamcontroller.android.parser

import android.util.Log

/**
 * Dedicated battery/charge report (id 0x43, "TritonBatteryStatus_t").
 * percent is already 0-100 (no scaling needed, unlike the legacy guess in the
 * 0x45 state report). chargeState: 1=discharging, 2=charging, 4=charging done.
 * Source: github.com/ddeverill/SteamlessController ControllerManager.cpp
 * (`buf[0]==REPORT_BATTERY_STATUS` → `buf[1]`=percent, `buf[2]`=chargeState).
 */
data class BatteryStatus(val percent: Int, val chargeState: Int)

object SteamReportParser {

    private const val TAG = "SteamReportParser"
    private const val EXPECTED_REPORT_ID = 0x45
    private const val MIN_REPORT_LEN = 40
    private const val REPORT_BATTERY_STATUS = 0x43

    /**
     * Parses report id 0x43 if present. USB carries the id as report[0]; BLE
     * strips report IDs on notify, so BluetoothHidManager re-prepends 0x43 to
     * short (2-byte) vendor-char payloads before they reach this parser — see
     * its onCharacteristicChanged. Returns null for any other report.
     */
    fun parseBatteryStatus(report: ByteArray): BatteryStatus? {
        if (report.size < 3) return null
        if ((report[0].toInt() and 0xFF) != REPORT_BATTERY_STATUS) return null
        return BatteryStatus(
            percent = report[1].toInt() and 0xFF,
            chargeState = report[2].toInt() and 0xFF
        )
    }

    fun parse(report: ByteArray): SteamControllerState? {
        if (report.size < MIN_REPORT_LEN) return null
        val id = report[0].toInt() and 0xFF
        if (id != EXPECTED_REPORT_ID) {
            Log.v(TAG, "Unexpected report ID: 0x${id.toString(16)}")
        }
        return doParse(report)
    }

    fun parseRaw(report: ByteArray): SteamControllerState {
        if (report.size < MIN_REPORT_LEN) return SteamControllerState()
        return doParse(report)
    }

    private fun doParse(report: ByteArray): SteamControllerState {
        // byte[1] = sequence counter (rolling 0-255), ignored
        // buttons span bytes 2-5 (4 bytes = 32 bits)
        val buttons = (report[2].toInt() and 0xFF) or
                      ((report[3].toInt() and 0xFF) shl 8) or
                      ((report[4].toInt() and 0xFF) shl 16) or
                      ((report[5].toInt() and 0xFF) shl 24)

        return SteamControllerState(
            buttons      = buttons,
            leftTrigger  = readUInt16LE(report, 6),
            rightTrigger = readUInt16LE(report, 8),
            leftJoyX     = readInt16LE(report, 10),
            leftJoyY     = readInt16LE(report, 12),
            rightJoyX    = readInt16LE(report, 14),
            rightJoyY    = readInt16LE(report, 16),
            leftPadX     = readInt16LE(report, 18),
            leftPadY     = readInt16LE(report, 20),
            leftPadContact  = readUInt16LE(report, 22),
            rightPadX    = readInt16LE(report, 24),
            rightPadY    = readInt16LE(report, 26),
            rightPadContact = readUInt16LE(report, 28),
            quatW        = readInt16LE(report, 32),
            quatX        = readInt16LE(report, 34),
            quatY        = readInt16LE(report, 36),
            quatZ        = readInt16LE(report, 38),
            // Battery at bytes 44-45 is only valid on FULL USB state reports (≥54 bytes incl. 0x45 prefix).
            // On BLE the report is truncated to ~46 bytes (after prefix) and bytes 44-45 fall in
            // the gyro-rest / pad-contact zone instead — reading there gives spurious 0/0xFFFF.
            // The actual BLE battery is in a separate 5-byte status report from char 100f6c79 (TODO).
            battery      = if (report.size >= 54) readUInt16LE(report, 44) else -1
        )
    }

    private fun readInt16LE(buf: ByteArray, offset: Int): Short {
        val lo = buf[offset].toInt() and 0xFF
        val hi = buf[offset + 1].toInt() and 0xFF
        return ((hi shl 8) or lo).toShort()
    }

    private fun readUInt16LE(buf: ByteArray, offset: Int): Int {
        val lo = buf[offset].toInt() and 0xFF
        val hi = buf[offset + 1].toInt() and 0xFF
        return (hi shl 8) or lo
    }
}
