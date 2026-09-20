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
     * How this reading should be shown.
     *
     * On the charger the firmware pins the percent to 100 regardless of the real level —
     * observed on hardware 2026-09-20: a pack reading 92% unplugged reports "04 64" the
     * moment it is plugged back in, and stays there. So the percent is only meaningful
     * while discharging, and it is suppressed otherwise rather than shown as a level the
     * user can see is wrong.
     *
     * The state value is 4 whenever the cable is in. Upstream names that constant
     * CHARGING_DONE, but they have only ever observed 1 (discharging), and on the SC2026
     * a charge starting from 92% reports it too — so here it reads as "on external power",
     * and calling it "Charged" was wrong.
     */
    fun describe(): String = if (chargeState == DISCHARGING) "$percent%" else "Charging"

    companion object {
        const val DISCHARGING = 1
        const val CHARGING = 2
        const val CHARGING_DONE = 4
    }
}
