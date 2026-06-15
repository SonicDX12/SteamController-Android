package com.steamcontroller.android.input

import kotlin.math.abs
import kotlin.math.sqrt

// Per-stick calibration applied to raw SC2026 Int16 values (-32768..32767)
// before they go to uinput / GamepadMapper.
data class StickCalibration(
    val centerX: Int = 0,
    val centerY: Int = 0,
    val deadzonePercent: Int = 8,   // 0..100 — radial deadzone as % of full range
    val invertY: Boolean = false
) {
    // Returns calibrated (x, y) in the same Int16 range, with deadzone applied radially.
    fun apply(rawX: Int, rawY: Int): Pair<Int, Int> {
        // 1. Subtract center offset
        var x = rawX - centerX
        var y = rawY - centerY

        // 2. Invert Y if requested
        if (invertY) y = -y

        // 3. Radial deadzone — if magnitude < deadzone, output zero;
        //    otherwise rescale so the deadzone edge becomes the new zero,
        //    keeping max deflection intact.
        val dz = (32767.0 * deadzonePercent / 100.0)
        if (dz > 0) {
            val mag = sqrt((x * x + y * y).toDouble())
            if (mag < dz) {
                return 0 to 0
            }
            val scale = (mag - dz) / (32767.0 - dz) * 32767.0 / mag
            x = (x * scale).toInt()
            y = (y * scale).toInt()
        }

        // Clamp to Int16
        x = x.coerceIn(-32767, 32767)
        y = y.coerceIn(-32767, 32767)
        return x to y
    }

    companion object {
        val DEFAULT = StickCalibration()
    }
}
