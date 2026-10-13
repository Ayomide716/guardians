package com.ayomide.guardian.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import com.ayomide.guardian.core.SmsCommand
import com.ayomide.guardian.data.EventLog
import com.ayomide.guardian.data.Prefs
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Builds and sends the find-my-phone SMS reply (battery + location). */
object FindPhone {

    private const val TAG = "FindPhone"

    fun replyAsync(ctx: Context, to: String, done: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        Thread {
            try {
                reply(app, to)
            } catch (t: Throwable) {
                Log.w(TAG, "Reply failed", t)
            } finally {
                done?.invoke()
            }
        }.start()
    }

    private fun reply(ctx: Context, to: String) {
        val prefs = Prefs(ctx)
        val battery = if (prefs.replyBattery) BatteryInfo.read(ctx)?.level else null
        val loc = if (prefs.replyLocation) currentLocation(ctx) else null
        val text = SmsCommand.reply(battery, loc?.latitude, loc?.longitude, loc?.accuracy)
        if (sendSms(ctx, to, text)) EventLog.add(ctx, "Replied to find-my-phone text" + if (loc != null) " with location" else "")
    }

    fun sendSms(ctx: Context, to: String, text: String): Boolean {
        if (ctx.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            val sms = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else {
                @Suppress("DEPRECATION") SmsManager.getDefault()
            }
            val parts = sms.divideMessage(text)
            sms.sendMultipartTextMessage(to, null, parts, null, null)
            true
        } catch (e: Exception) {
            Log.w(TAG, "SMS failed", e)
            false
        }
    }

    private fun hasLocation(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** A fresh fix if possible within ~25 s, otherwise the best last-known one. */
    fun currentLocation(ctx: Context): Location? {
        if (!hasLocation(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                for (p in providers) {
                    var result: Location? = null
                    val latch = CountDownLatch(1)
                    val exec = Executors.newSingleThreadExecutor()
                    lm.getCurrentLocation(p, null, exec) { l -> result = l; latch.countDown() }
                    latch.await(if (p == LocationManager.GPS_PROVIDER) 25 else 10, TimeUnit.SECONDS)
                    exec.shutdown()
                    if (result != null) return result
                }
            }
            return providers.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        } catch (e: SecurityException) {
            return null
        }
    }
}
