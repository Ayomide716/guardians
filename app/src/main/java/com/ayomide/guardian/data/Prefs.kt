package com.ayomide.guardian.data

import android.content.Context
import android.content.SharedPreferences
import com.ayomide.guardian.core.Pin
import java.time.LocalDate
import java.util.UUID

/** All settings and small counters, private to the app. */
class Prefs(context: Context) {

    private val p: SharedPreferences =
        context.applicationContext.getSharedPreferences("guardian", Context.MODE_PRIVATE)

    private fun bool(k: String, d: Boolean) = p.getBoolean(k, d)
    private fun setBool(k: String, v: Boolean) = p.edit().putBoolean(k, v).apply()
    private fun int(k: String, d: Int) = p.getInt(k, d)
    private fun setInt(k: String, v: Int) = p.edit().putInt(k, v).apply()
    private fun str(k: String, d: String) = p.getString(k, d) ?: d
    private fun setStr(k: String, v: String) = p.edit().putString(k, v).apply()

    var onboarded: Boolean get() = bool("onboarded", false); set(v) = setBool("onboarded", v)

    // ---- Pocket / charger / movement alarm ----
    /** 0 = pocket, 1 = charger, 2 = movement. */
    var alarmMode: Int get() = int("alarm_mode", 0); set(v) = setInt("alarm_mode", v)
    var armed: Boolean get() = bool("armed", false); set(v) = setBool("armed", v)
    var graceSec: Int get() = int("grace_sec", 3); set(v) = setInt("grace_sec", v)

    val hasPin get() = p.getString("pin_hash", null) != null

    fun setPin(pin: String) {
        val salt = UUID.randomUUID().toString()
        p.edit().putString("pin_salt", salt).putString("pin_hash", Pin.hash(pin, salt)).apply()
    }

    fun checkPin(pin: String): Boolean {
        val salt = p.getString("pin_salt", null) ?: return false
        return Pin.hash(pin, salt) == p.getString("pin_hash", null)
    }

    // ---- Find my phone ----
    var findEnabled: Boolean get() = bool("find_on", false); set(v) = setBool("find_on", v)
    var secret: String get() = str("secret", ""); set(v) = setStr("secret", v)
    var trusted: String get() = str("trusted", ""); set(v) = setStr("trusted", v)
    val trustedList get() = trusted.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    var replyLocation: Boolean get() = bool("reply_loc", true); set(v) = setBool("reply_loc", v)
    var replyBattery: Boolean get() = bool("reply_bat", true); set(v) = setBool("reply_bat", v)

    // ---- Ad blocker ----
    var adEnabled: Boolean get() = bool("ad_on", false); set(v) = setBool("ad_on", v)
    var adApps: Set<String>
        get() = p.getStringSet("ad_apps", emptySet())!!.toSet()
        set(v) = p.edit().putStringSet("ad_apps", v.toSet()).apply()
    var allowSites: Set<String>
        get() = p.getStringSet("allow_sites", emptySet())!!.toSet()
        set(v) = p.edit().putStringSet("allow_sites", v.toSet()).apply()
    var useFullList: Boolean get() = bool("full_list", false); set(v) = setBool("full_list", v)

    fun blockedToday(): Int =
        if (p.getLong("blocked_day", -1) == LocalDate.now().toEpochDay()) int("blocked_count", 0) else 0

    @Synchronized
    fun addBlocked(n: Int) {
        val today = LocalDate.now().toEpochDay()
        val base = if (p.getLong("blocked_day", -1) == today) int("blocked_count", 0) else 0
        p.edit().putLong("blocked_day", today).putInt("blocked_count", base + n).apply()
    }

    // ---- Data usage ----
    var dataWarnOn: Boolean get() = bool("data_warn", true); set(v) = setBool("data_warn", v)
    var dailyLimitMb: Int get() = int("daily_mb", 1024); set(v) = setInt("daily_mb", v)
    var warnPct: Int get() = int("warn_pct", 80); set(v) = setInt("warn_pct", v)
    var bundleMb: Int get() = int("bundle_mb", 10 * 1024); set(v) = setInt("bundle_mb", v)
    var renewalDay: Int get() = int("renew_day", 1); set(v) = setInt("renew_day", v)
    var hogMb: Int get() = int("hog_mb", 100); set(v) = setInt("hog_mb", v)
    /** Bookkeeping so each warning fires once per day/period/hour. */
    var lastDailyWarn: String get() = str("last_daily", ""); set(v) = setStr("last_daily", v)
    var lastBundleWarn: String get() = str("last_bundle", ""); set(v) = setStr("last_bundle", v)
    var hogMark: String get() = str("hog_mark", ""); set(v) = setStr("hog_mark", v)

    // ---- Battery ----
    var chargeAlarmOn: Boolean get() = bool("charge_on", true); set(v) = setBool("charge_on", v)
    var chargeTarget: Int get() = int("charge_target", 80); set(v) = setInt("charge_target", v)
    var lowWarnOn: Boolean get() = bool("low_on", true); set(v) = setBool("low_on", v)
    var lowLevel: Int get() = int("low_level", 15); set(v) = setInt("low_level", v)
    var hotWarnOn: Boolean get() = bool("hot_on", true); set(v) = setBool("hot_on", v)
    var hotC: Int get() = int("hot_c", 42); set(v) = setInt("hot_c", v)
    var lastBatteryWarn: String get() = str("last_bat", ""); set(v) = setStr("last_bat", v)
    /** Minutes spent plugged in at 100% this ISO week ("week:minutes"). */
    var fullMinutes: String get() = str("full_min", ""); set(v) = setStr("full_min", v)
}

/** Small local event history (alarm triggers, wrong PINs, find-my-phone texts). */
object EventLog {
    private const val KEY = "events"
    private const val MAX = 50

    @Synchronized
    fun add(context: Context, text: String) {
        val p = context.applicationContext.getSharedPreferences("guardian_events", Context.MODE_PRIVATE)
        val list = p.getString(KEY, "")!!.split('\n').filter { it.isNotBlank() }.toMutableList()
        list.add(0, "${System.currentTimeMillis()}\t${text.replace('\n', ' ').replace('\t', ' ')}")
        p.edit().putString(KEY, list.take(MAX).joinToString("\n")).apply()
    }

    fun all(context: Context): List<Pair<Long, String>> {
        val p = context.applicationContext.getSharedPreferences("guardian_events", Context.MODE_PRIVATE)
        return p.getString(KEY, "")!!.split('\n').mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) null else line.substring(0, tab).toLongOrNull()?.let { it to line.substring(tab + 1) }
        }
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences("guardian_events", Context.MODE_PRIVATE).edit().clear().apply()
    }
}
