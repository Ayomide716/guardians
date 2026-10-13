package com.ayomide.guardian.system

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import com.ayomide.guardian.R
import com.ayomide.guardian.screens.MainActivity

object Notifier {
    const val CH_GUARD = "guard"       // ongoing: armed, ad blocker, charge watch
    const val CH_ALARM = "alarm"       // full-screen alarm / ring
    const val CH_WARN = "warnings"     // data and battery warnings

    const val ID_GUARD = 1
    const val ID_ALARM = 2
    const val ID_VPN = 3
    const val ID_DATA = 10
    const val ID_BATTERY = 11
    const val ID_CHARGED = 12

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CH_GUARD, "Guards running", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while a guard is active"; setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_ALARM, "Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Theft alarm and find-my-phone"; setSound(null, null); enableVibration(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_WARN, "Data & battery warnings", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Data limits, charge level, low battery and overheating"
        })
    }

    fun canPost(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return ctx.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true
    }

    fun openApp(ctx: Context, tab: Int = 0): PendingIntent = PendingIntent.getActivity(
        ctx, 100 + tab,
        Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, tab)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun builder(ctx: Context, channel: String, title: String, text: String): Notification.Builder =
        Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))

    fun warn(ctx: Context, id: Int, title: String, text: String, tab: Int) {
        if (!canPost(ctx)) return
        channels(ctx)
        post(ctx, id, builder(ctx, CH_WARN, title, text).setAutoCancel(true).setContentIntent(openApp(ctx, tab)).build())
    }

    fun post(ctx: Context, id: Int, n: Notification) {
        try {
            ctx.getSystemService(NotificationManager::class.java)?.notify(id, n)
        } catch (e: SecurityException) {
            // Notifications not allowed.
        }
    }

    fun cancel(ctx: Context, id: Int) {
        ctx.getSystemService(NotificationManager::class.java)?.cancel(id)
    }

    /** startForeground with the "special use" type required on Android 14+. */
    fun startForeground(service: Service, id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            service.startForeground(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            service.startForeground(id, n)
        }
    }
}
