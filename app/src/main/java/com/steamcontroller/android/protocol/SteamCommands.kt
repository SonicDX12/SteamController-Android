package com.steamcontroller.android.protocol

/**
 * SC2026 command channel — shared by the USB and BLE transports.
 *
 * Source: github.com/ddeverill/SteamlessController/blob/main/src/steam/SteamController.h
 *
 * Commands are NOT bare feature reports. They are wrapped inside feature report 0x01
 * (0x02 as a fallback) with the layout:
 *
 *     [feature_report_id | cmd_byte | payload_size | payload...]
 *
 * The previous implementation sent a lone 0x85 byte, which is the SC1-era spelling and,
 * in the current command set, is CMD_SET_DEFAULT_MAPPINGS — the command that turns lizard
 * mode back ON. See CLAUDE.md for the history.
 */
object SteamCommands {

    // Feature report IDs carrying commands
    const val FEATURE_REPORT_CMD = 0x01
    const val FEATURE_REPORT_CMD2 = 0x02  // fallback if 0x01 is rejected

    // Command bytes (buffer[1])
    const val CMD_SET_DIGITAL_MAPPINGS = 0x80
    const val CMD_CLEAR_DIGITAL_MAPPINGS = 0x81   // lizard OFF
    const val CMD_GET_DIGITAL_MAPPINGS = 0x82
    const val CMD_SET_DEFAULT_MAPPINGS = 0x85     // lizard ON
    const val CMD_SET_SETTINGS = 0x87
    const val CMD_GET_SETTINGS = 0x89
    const val CMD_LOAD_DEFAULT_SETTINGS = 0x8E    // restore settings for lizard ON

    // Setting keys (payload of CMD_SET_SETTINGS), each followed by a UInt16 LE value
    const val SETTING_RIGHT_TRACKPAD_MODE = 0x07
    const val SETTING_LEFT_TRACKPAD_MODE = 0x08
    const val SETTING_IMU_MODE = 0x30

    const val TRACKPAD_NONE = 0x0000
    const val IMU_MODE_OFF = 0x0000
    const val IMU_MODE_RAW_ACCEL_GYRO = 0x0018

    // Output report IDs (host → device), used for haptics
    const val OUT_HAPTIC_RUMBLE = 0x80   // continuous two-channel rumble
    const val OUT_HAPTIC_PULSE = 0x81    // one-shot pulse (grip actuators)
    const val OUT_HAPTIC_COMMAND = 0x82  // named command (tick/click) on the trackpad actuators

    /** Feature reports are fixed-size on this device; short buffers are rejected. */
    const val FEATURE_REPORT_SIZE = 64

    /**
     * Wrap a command + payload into a feature report buffer, zero-padded to
     * FEATURE_REPORT_SIZE. The buffer includes the feature report ID as byte 0.
     */
    fun buildCommand(
        cmd: Int,
        payload: ByteArray = ByteArray(0),
        featureReportId: Int = FEATURE_REPORT_CMD,
    ): ByteArray {
        require(payload.size <= FEATURE_REPORT_SIZE - 3) { "payload too large: ${payload.size}" }
        val buf = ByteArray(FEATURE_REPORT_SIZE)
        buf[0] = featureReportId.toByte()
        buf[1] = cmd.toByte()
        buf[2] = payload.size.toByte()
        payload.copyInto(buf, 3)
        return buf
    }

    /** Encode one or more (settingKey, UInt16 value) pairs as a CMD_SET_SETTINGS payload. */
    fun settingsPayload(vararg settings: Pair<Int, Int>): ByteArray {
        val buf = ByteArray(settings.size * 3)
        settings.forEachIndexed { i, (key, value) ->
            buf[i * 3] = key.toByte()
            buf[i * 3 + 1] = (value and 0xFF).toByte()
            buf[i * 3 + 2] = ((value shr 8) and 0xFF).toByte()
        }
        return buf
    }

    /**
     * Step 1 of leaving lizard mode: drop the firmware's keyboard/mouse button mappings.
     */
    fun clearDigitalMappings(featureReportId: Int = FEATURE_REPORT_CMD): ByteArray =
        buildCommand(CMD_CLEAR_DIGITAL_MAPPINGS, featureReportId = featureReportId)

    /**
     * Step 2: take the trackpads out of firmware mouse mode so they report raw positions.
     */
    fun disableTrackpadModes(featureReportId: Int = FEATURE_REPORT_CMD): ByteArray =
        buildCommand(
            CMD_SET_SETTINGS,
            settingsPayload(
                SETTING_RIGHT_TRACKPAD_MODE to TRACKPAD_NONE,
                SETTING_LEFT_TRACKPAD_MODE to TRACKPAD_NONE,
            ),
            featureReportId,
        )

    /** Enable or disable the raw accel/gyro block at bytes 30-45 of the state report. */
    fun setImuMode(enabled: Boolean, featureReportId: Int = FEATURE_REPORT_CMD): ByteArray =
        buildCommand(
            CMD_SET_SETTINGS,
            settingsPayload(
                SETTING_IMU_MODE to if (enabled) IMU_MODE_RAW_ACCEL_GYRO else IMU_MODE_OFF,
            ),
            featureReportId,
        )

    /** Restore firmware keyboard/mouse emulation. Send on shutdown so Steam/the OS get it back. */
    fun restoreDefaultMappings(featureReportId: Int = FEATURE_REPORT_CMD): ByteArray =
        buildCommand(CMD_SET_DEFAULT_MAPPINGS, featureReportId = featureReportId)

    fun loadDefaultSettings(featureReportId: Int = FEATURE_REPORT_CMD): ByteArray =
        buildCommand(CMD_LOAD_DEFAULT_SETTINGS, featureReportId = featureReportId)

    /**
     * Continuous two-channel rumble, as an output report.
     *
     * Upstream's SendRumbleOutput(uint16 leftSpeed, uint16 rightSpeed) writes
     * OUT_HAPTIC_RUMBLE with the two speeds as UInt16 LE, which is what we mirror here.
     * Send (0, 0) to stop.
     */
    fun rumble(leftSpeed: Int, rightSpeed: Int): ByteArray {
        val l = leftSpeed.coerceIn(0, 0xFFFF)
        val r = rightSpeed.coerceIn(0, 0xFFFF)
        return byteArrayOf(
            OUT_HAPTIC_RUMBLE.toByte(),
            (l and 0xFF).toByte(), ((l shr 8) and 0xFF).toByte(),
            (r and 0xFF).toByte(), ((r shr 8) and 0xFF).toByte(),
        )
    }

    /**
     * The firmware silently reverts to lizard mode after a spell without host feature
     * reports, so the lizard-off state has to be re-asserted periodically. Upstream uses
     * a 2s cadence; we match it (the old 800ms was needlessly chatty).
     */
    const val KEEPALIVE_INTERVAL_MS = 2000L
}
