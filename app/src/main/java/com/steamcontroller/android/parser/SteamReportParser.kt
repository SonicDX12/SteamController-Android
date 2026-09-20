package com.steamcontroller.android.parser

import android.util.Log

object SteamReportParser {

    private const val TAG = "SteamReportParser"

    // Input report IDs (device → host)
    const val REPORT_STATE = 0x45           // current firmware
    const val REPORT_STATE_LEGACY = 0x42    // pre-update firmware, identical layout
    const val REPORT_BATTERY_STATUS = 0x43  // [chargeState, percent]
    const val REPORT_STATUS = 0x44          // 5 bytes, connection status

    private const val MIN_REPORT_LEN = 40

    /** True for any report ID that carries controller state. */
    fun isStateReportId(id: Int) = id == REPORT_STATE || id == REPORT_STATE_LEGACY

    // The IMU block spans bytes 30-45, so the report must be at least 46 bytes to hold it.
    private const val IMU_REPORT_LEN = 46

    fun parse(report: ByteArray): SteamControllerState? {
        if (report.size < MIN_REPORT_LEN) return null
        val id = report[0].toInt() and 0xFF
        if (!isStateReportId(id)) {
            Log.v(TAG, "Unexpected report ID: 0x${id.toString(16)}")
        }
        return doParse(report)
    }

    fun parseRaw(report: ByteArray): SteamControllerState {
        if (report.size < MIN_REPORT_LEN) return SteamControllerState()
        return doParse(report)
    }

    /**
     * Parse a battery status report (0x43, "TritonBatteryStatus_t"). Returns null if
     * this isn't one.
     *
     * Payload is [chargeState, percent] on every transport (wired, dongle and BLE), and
     * percent is already 0-100 — no scaling. Upstream originally assumed the opposite
     * order and corrected it after reading 1/92 and 1/91 off a dongle: a constant 1
     * (DISCHARGING) followed by the real level. See ControllerManager.cpp.
     *
     * USB carries the id as report[0]; BLE strips report IDs on notify, so
     * BluetoothHidManager re-prepends it from the characteristic the report arrived on.
     */
    fun parseBatteryStatus(report: ByteArray): BatteryStatus? {
        if (report.size < 3) return null
        if ((report[0].toInt() and 0xFF) != REPORT_BATTERY_STATUS) return null
        val chargeState = report[1].toInt() and 0xFF
        val percent = (report[2].toInt() and 0xFF).coerceIn(0, 100)
        return BatteryStatus(chargeState, percent)
    }

    private fun doParse(report: ByteArray): SteamControllerState {
        // byte[1] = sequence counter (rolling 0-255), ignored
        // buttons span bytes 2-5 (4 bytes = 32 bits)
        val buttons = (report[2].toInt() and 0xFF) or
                      ((report[3].toInt() and 0xFF) shl 8) or
                      ((report[4].toInt() and 0xFF) shl 16) or
                      ((report[5].toInt() and 0xFF) shl 24)

        // The IMU block is only appended when raw accel/gyro output has been enabled
        // (SETTING_IMU_MODE = IMU_MODE_RAW_ACCEL_GYRO). Without it the report stops
        // short and bytes 30+ are absent or padding — reading them yields garbage,
        // which is exactly how the old code mistook gyro Z for a battery level.
        val hasImu = report.size >= IMU_REPORT_LEN

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
            imuValid     = hasImu,
            imuTimestamp = if (hasImu) readUInt32LE(report, 30) else 0,
            accelX       = if (hasImu) readInt16LE(report, 34) else 0,
            accelY       = if (hasImu) readInt16LE(report, 36) else 0,
            accelZ       = if (hasImu) readInt16LE(report, 38) else 0,
            gyroX        = if (hasImu) readInt16LE(report, 40) else 0,
            gyroY        = if (hasImu) readInt16LE(report, 42) else 0,
            gyroZ        = if (hasImu) readInt16LE(report, 44) else 0,
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

    private fun readUInt32LE(buf: ByteArray, offset: Int): Int {
        return (buf[offset].toInt() and 0xFF) or
               ((buf[offset + 1].toInt() and 0xFF) shl 8) or
               ((buf[offset + 2].toInt() and 0xFF) shl 16) or
               ((buf[offset + 3].toInt() and 0xFF) shl 24)
    }
}
