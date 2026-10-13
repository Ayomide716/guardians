package com.ayomide.guardian.system

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Process
import android.util.Log
import com.ayomide.guardian.core.DataMath
import com.ayomide.guardian.data.Prefs
import java.time.LocalDate
import java.time.ZoneId

/** Mobile-data totals from Android's own counters (needs Usage access). */
object DataWatch {

    private const val TAG = "DataWatch"

    fun hasAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun startOf(day: LocalDate) = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun todayStart() = startOf(LocalDate.now())
    fun periodStart(prefs: Prefs) = startOf(DataMath.periodStart(prefs.renewalDay, LocalDate.now()))

    /** Mobile bytes (down + up) between two times, or null without access. */
    fun usage(ctx: Context, from: Long, to: Long = System.currentTimeMillis()): Long? {
        if (!hasAccess(ctx)) return null
        val nsm = ctx.getSystemService(NetworkStatsManager::class.java) ?: return null
        return try {
            @Suppress("DEPRECATION")
            val b = nsm.querySummaryForDevice(ConnectivityManager.TYPE_MOBILE, null, from, to)
            b.rxBytes + b.txBytes
        } catch (e: Exception) {
            Log.w(TAG, "usage", e)
            null
        }
    }

    data class AppUsage(val label: String, val bytes: Long)

    /** Biggest mobile-data users in the period, labelled by app name. */
    fun topApps(ctx: Context, from: Long, limit: Int = 5): List<AppUsage> {
        if (!hasAccess(ctx)) return emptyList()
        val nsm = ctx.getSystemService(NetworkStatsManager::class.java) ?: return emptyList()
        val byUid = HashMap<Int, Long>()
        try {
            @Suppress("DEPRECATION")
            val stats = nsm.querySummary(ConnectivityManager.TYPE_MOBILE, null, from, System.currentTimeMillis())
            val bucket = NetworkStats.Bucket()
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                byUid[bucket.uid] = (byUid[bucket.uid] ?: 0L) + bucket.rxBytes + bucket.txBytes
            }
            stats.close()
        } catch (e: Exception) {
            Log.w(TAG, "topApps", e)
            return emptyList()
        }
        val pm = ctx.packageManager
        return byUid.entries.sortedByDescending { it.value }.take(limit).map { (uid, bytes) -> AppUsage(labelFor(pm, uid), bytes) }
    }

    private fun labelFor(pm: PackageManager, uid: Int): String = when (uid) {
        NetworkStats.Bucket.UID_REMOVED -> "Removed apps"
        NetworkStats.Bucket.UID_TETHERING -> "Hotspot & tethering"
        Process.SYSTEM_UID -> "Android system"
        else -> {
            val pkg = pm.getPackagesForUid(uid)?.firstOrNull()
            pkg?.let { runCatching { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() }.getOrNull() }
                ?: pkg ?: "Other (uid $uid)"
        }
    }

    /** Fires daily-limit, bundle and data-hog warnings, each once. */
    fun check(ctx: Context) {
        val prefs = Prefs(ctx)
        if (!prefs.dataWarnOn) return
        val now = System.currentTimeMillis()
        val today = usage(ctx, todayStart(), now) ?: return
        val periodFrom = periodStart(prefs)
        val period = usage(ctx, periodFrom, now) ?: return
        val day = LocalDate.now().toEpochDay()

        val dLevel = DataMath.level(today, prefs.dailyLimitMb * DataMath.MB, prefs.warnPct)
        if (dLevel > 0 && !alreadyWarned(prefs.lastDailyWarn, "$day", dLevel)) {
            prefs.lastDailyWarn = "$day:$dLevel"
            Notifier.warn(ctx, Notifier.ID_DATA,
                if (dLevel == 2) "Daily data limit reached" else "${prefs.warnPct}% of today's data used",
                "You've used ${DataMath.format(today)} of ${DataMath.format(prefs.dailyLimitMb * DataMath.MB)} today.", 2)
        }

        val bLevel = DataMath.level(period, prefs.bundleMb * DataMath.MB, prefs.warnPct)
        if (bLevel > 0 && !alreadyWarned(prefs.lastBundleWarn, "$periodFrom", bLevel)) {
            prefs.lastBundleWarn = "$periodFrom:$bLevel"
            Notifier.warn(ctx, Notifier.ID_DATA + 1,
                if (bLevel == 2) "Data bundle used up" else "${prefs.warnPct}% of your bundle used",
                "${DataMath.format(period)} of ${DataMath.format(prefs.bundleMb * DataMath.MB)} since your bundle renewed.", 2)
        }

        // Data hog: compare with the reading from up to ~70 minutes ago.
        val mark = prefs.hogMark.split(':')
        val markTime = mark.getOrNull(0)?.toLongOrNull()
        val markBytes = mark.getOrNull(1)?.toLongOrNull()
        if (markTime != null && markBytes != null && now - markTime in 1..70 * 60_000L && period >= markBytes) {
            if (period - markBytes >= prefs.hogMb * DataMath.MB) {
                Notifier.warn(ctx, Notifier.ID_DATA + 2, "Heavy data use",
                    "${DataMath.format(period - markBytes)} used in the last hour. Check Guardian to see which app.", 2)
                prefs.hogMark = "$now:$period"
            } else if (now - markTime < 50 * 60_000L) {
                return // keep the older mark for a full-hour comparison
            }
        }
        prefs.hogMark = "$now:$period"
    }

    private fun alreadyWarned(stored: String, key: String, level: Int): Boolean {
        val parts = stored.split(':')
        return parts.size == 2 && parts[0] == key && (parts[1].toIntOrNull() ?: 0) >= level
    }
}
