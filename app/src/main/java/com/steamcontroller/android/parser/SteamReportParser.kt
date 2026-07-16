package com.steamcontroller.android.parser

import android.util.Log

/**
 * Dedicated battery/charge report (id 0x43). Two distinct physical sources feed this,
 * with different layouts — NOT the single [id, percent, chargeState] layout documented
 * for the older Steam Controller in SteamlessController's ControllerManager.cpp:
 *  - USB: a genuine 15-byte 0x43 report on the bulk endpoint, sent roughly every 3.5s.
 *    Empirically decoded from a real capture (percent pinned at 100 while charging over
 *    USB, byte[2] constant at 0x64=100 across all samples): percent is at byte[2].
 *  - BLE: BluetoothHidManager already extracts percent from the 14-byte 100f6c78
 *    characteristic and re-prefixes a compact synthetic [0x43, percent, 0x00] (3 bytes)
 *    before it reaches this parser — see its onCharacteristicChanged.
 * chargeState's real offset/encoding is unconfirmed on either path (unused elsewhere in
 * the app) — kept as a best-effort placeholder, 0 on the BLE synthetic report.
 */
data class BatteryStatus(val percent: Int, val chargeState: Int)

object SteamReportParser {

    private const val TAG = "SteamReportParser"
    private const val EXPECTED_REPORT_ID = 0x45
    private const val MIN_REPORT_LEN = 40
    private const val REPORT_BATTERY_STATUS = 0x43
    private const val USB_BATTERY_REPORT_LEN = 15

    fun parseBatteryStatus(report: ByteArray): BatteryStatus? {
        if (report.size < 3) return null
        if ((report[0].toInt() and 0xFF) != REPORT_BATTERY_STATUS) return null
        return if (report.size >= USB_BATTERY_REPORT_LEN) {
            BatteryStatus(percent = report[2].toInt() and 0xFF, chargeState = report[3].toInt() and 0xFF)
        } else {
            BatteryStatus(percent = report[1].toInt() and 0xFF, chargeState = 0)
        }
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
