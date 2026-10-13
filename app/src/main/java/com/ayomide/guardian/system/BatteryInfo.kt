package com.ayomide.guardian.system

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build

data class BatteryInfo(
    val level: Int,
    val plugged: Boolean,
    val charging: Boolean,
    val tempC: Float?,
    val health: String,
    val voltageMv: Int?,
    /** Only reported on Android 14+ and only by some phones. */
    val cycles: Int?,
    val source: String,
) {
    companion object {
        /** Reads the last battery broadcast without registering a listener. */
        fun read(ctx: Context): BatteryInfo? = try {
            ctx.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { from(it) }
        } catch (e: Exception) {
            null
        }

        fun from(i: Intent): BatteryInfo {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val pct = if (level < 0) 0 else level * 100 / scale
            val pluggedType = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            val volt = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            val cycles = if (Build.VERSION.SDK_INT >= 34) i.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1) else -1
            val health = when (i.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheating"
                BatteryManager.BATTERY_HEALTH_DEAD -> "Worn out"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
                BatteryManager.BATTERY_HEALTH_COLD -> "Too cold"
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Problem reported"
                else -> "Not reported"
            }
            val source = when (pluggedType) {
                BatteryManager.BATTERY_PLUGGED_AC -> "charger"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless charger"
                else -> ""
            }
            return BatteryInfo(
                level = pct,
                plugged = pluggedType != 0,
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
                tempC = if (temp == Int.MIN_VALUE) null else temp / 10f,
                health = health,
                voltageMv = if (volt > 0) volt else null,
                cycles = if (cycles >= 0) cycles else null,
                source = source,
            )
        }
    }
}
