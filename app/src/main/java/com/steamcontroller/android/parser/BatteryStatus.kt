package com.steamcontroller.android.parser

/**
 * Payload of REPORT_BATTERY_STATUS (0x43).
 *
 * The battery is NOT part of the 0x45/0x42 state report — it arrives in its own
 * report, on every transport, as [chargeState, percent]. `percent` is already a
 * 0..100 value, not a scaled UInt16.
 */
data class BatteryStatus(
    val chargeState: Int,
    val percent: Int,
) {
    val isCharging: Boolean get() = chargeState == CHARGING || chargeState == CHARGING_DONE

    /**
     * How this reading should be shown. While charging, the percent is worth less than the
     * fact of charging — it sticks at 100 on a full pack and tells the user nothing — so
     * the state leads and the level follows only while it still means something.
     */
    fun describe(): String = when {
        chargeState == CHARGING_DONE -> "Charged"
        chargeState == CHARGING      -> "Charging · $percent%"
        else                         -> "$percent%"
    }

    companion object {
        const val DISCHARGING = 1
        const val CHARGING = 2
        const val CHARGING_DONE = 4
    }
}
