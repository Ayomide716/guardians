package com.ayomide.guardian.screens

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import com.ayomide.guardian.system.DataWatch

/** One permission or special access the app can ask for. */
class Perm(
    val title: String,
    val why: String,
    val granted: (Activity) -> Boolean,
    val request: (Activity) -> Unit,
    /** True when the request opens a system settings page rather than a dialog. */
    val opensSettings: Boolean,
)

object Perms {
    const val REQ = 42

    private fun has(a: Activity, p: String) = a.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun open(a: Activity, intent: Intent) {
        try {
            a.startActivity(intent)
        } catch (e: Exception) {
            try {
                a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}")))
            } catch (e2: Exception) {
                Toast.makeText(a, "Open Settings → Apps → Guardian manually.", Toast.LENGTH_LONG).show()
            }
        }
    }

    val notifications = Perm("Notifications", "Alarms, data and battery warnings",
        { a -> Build.VERSION.SDK_INT < 33 || has(a, Manifest.permission.POST_NOTIFICATIONS) },
        { a -> if (Build.VERSION.SDK_INT >= 33) a.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ) },
        false)

    val fullScreen = Perm("Full-screen alarm", "Show the PIN screen over the lock screen",
        { a -> Build.VERSION.SDK_INT < 34 || a.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true },
        { a -> if (Build.VERSION.SDK_INT >= 34) open(a, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${a.packageName}"))) },
        true)

    val usage = Perm("Usage access", "See which apps use your mobile data",
        { a -> DataWatch.hasAccess(a) },
        { a -> open(a, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
        true)

    val sms = Perm("SMS", "Receive the secret word and text back",
        { a -> has(a, Manifest.permission.RECEIVE_SMS) && has(a, Manifest.permission.SEND_SMS) },
        { a -> a.requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS), REQ) },
        false)

    val location = Perm("Location", "Include where the phone is in the reply",
        { a -> has(a, Manifest.permission.ACCESS_FINE_LOCATION) },
        { a -> a.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ) },
        false)

    /** Needed because the reply happens with the app closed. Asked only after normal location. */
    val backgroundLocation = Perm("Location all the time", "Lets the reply include location while the app is closed",
        { a -> has(a, Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
        { a ->
            if (!has(a, Manifest.permission.ACCESS_FINE_LOCATION)) location.request(a)
            else a.requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ)
        },
        true)

    val dnd = Perm("Do Not Disturb access", "Ring loudly even on silent",
        { a -> a.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true },
        { a -> open(a, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) },
        true)

    @Suppress("BatteryLife")
    val battery = Perm("Unrestricted battery", "So Samsung doesn't put guards to sleep",
        { a -> a.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(a.packageName) == true },
        { a -> open(a, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${a.packageName}"))) },
        true)

    val all = listOf(notifications, fullScreen, usage, sms, location, backgroundLocation, dnd, battery)
}
