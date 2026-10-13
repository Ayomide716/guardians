package com.ayomide.guardian.system

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.ayomide.guardian.data.Prefs

object Jobs {
    private const val CHARGE = 1
    private const val CHECK = 2

    fun scheduleAll(ctx: Context) {
        scheduleChargeJob(ctx)
        scheduleCheckJob(ctx)
    }

    /** Runs when the phone is plugged in, to start the charge alarm. */
    fun scheduleChargeJob(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        try {
            js.schedule(
                JobInfo.Builder(CHARGE, ComponentName(ctx, ChargeJob::class.java))
                    .setRequiresCharging(true)
                    .setPersisted(true)
                    .build()
            )
        } catch (e: Exception) {
            Log.w("Jobs", "charge job", e)
        }
    }

    /** Every 30 minutes: data limits, low battery and overheating. */
    private fun scheduleCheckJob(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        if (js.getPendingJob(CHECK) != null) return
        try {
            js.schedule(
                JobInfo.Builder(CHECK, ComponentName(ctx, CheckJob::class.java))
                    .setPeriodic(30 * 60 * 1000L)
                    .setPersisted(true)
                    .build()
            )
        } catch (e: Exception) {
            Log.w("Jobs", "check job", e)
        }
    }
}

class ChargeJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        if (Prefs(this).chargeAlarmOn) GuardService.start(this, GuardService.ACTION_CHARGE_WATCH)
        return false
    }

    override fun onStopJob(params: JobParameters?) = false
}

class CheckJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        Thread {
            try {
                DataWatch.check(this)
                BatteryWatch.check(this)
            } catch (t: Throwable) {
                Log.w("CheckJob", "check failed", t)
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters?) = true
}

object BatteryWatch {
    fun check(ctx: Context) {
        val prefs = Prefs(ctx)
        val b = BatteryInfo.read(ctx) ?: return
        val hour = System.currentTimeMillis() / 3_600_000L
        val day = hour / 24
        if (b.plugged && prefs.chargeAlarmOn && b.level < prefs.chargeTarget && !GuardService.chargeWatching) {
            GuardService.start(ctx, GuardService.ACTION_CHARGE_WATCH)
        }
        if (prefs.lowWarnOn && !b.plugged && b.level <= prefs.lowLevel && prefs.lastBatteryWarn != "low:$day") {
            prefs.lastBatteryWarn = "low:$day"
            Notifier.warn(ctx, Notifier.ID_BATTERY, "Battery low: ${b.level}%", "Charge soon or turn on power saving.", 3)
        }
        val t = b.tempC
        if (prefs.hotWarnOn && t != null && t >= prefs.hotC && prefs.lastBatteryWarn != "hot:$hour") {
            prefs.lastBatteryWarn = "hot:$hour"
            Notifier.warn(ctx, Notifier.ID_BATTERY, "Phone is hot: ${t.toInt()} °C",
                "Close heavy apps, take off the case and keep it out of the sun.", 3)
        }
    }
}
