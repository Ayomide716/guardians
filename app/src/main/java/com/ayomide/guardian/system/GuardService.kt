package com.ayomide.guardian.system

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.ayomide.guardian.core.MotionDetector
import com.ayomide.guardian.core.PocketDetector
import com.ayomide.guardian.data.EventLog
import com.ayomide.guardian.data.Prefs
import com.ayomide.guardian.screens.AlarmActivity
import java.time.LocalDate
import java.time.temporal.IsoFields

/**
 * One foreground service for everything that must keep running:
 *  - the pocket / charger / movement theft alarm,
 *  - find-my-phone ringing,
 *  - watching the battery while charging for the charge alarm.
 * It stops itself as soon as none of these is active.
 */
class GuardService : Service(), SensorEventListener {

    enum class Phase { IDLE, ARMING, ARMED, TRIGGERED, SOUNDING }

    companion object {
        const val ACTION_ARM = "arm"
        const val ACTION_DISARM = "disarm"
        const val ACTION_RING = "ring"
        const val ACTION_STOP_RING = "stop_ring"
        const val ACTION_CHARGE_WATCH = "charge_watch"
        const val ACTION_STOP_CHARGE_ALERT = "stop_charge_alert"
        const val EXTRA_SENDER = "sender"
        const val ARM_DELAY_MS = 5000L
        private const val RING_MS = 2 * 60 * 1000L
        private const val TAG = "GuardService"

        @Volatile var phase = Phase.IDLE
            private set
        @Volatile var ringing = false
            private set
        @Volatile var chargeWatching = false
            private set

        /** Starts the service; returns false if Android refused (e.g. background limits). */
        fun start(ctx: Context, action: String, sender: String? = null): Boolean = try {
            ctx.startForegroundService(Intent(ctx, GuardService::class.java).setAction(action).putExtra(EXTRA_SENDER, sender))
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not start $action", e)
            false
        }

        fun modeName(mode: Int) = when (mode) {
            1 -> "Charger alarm"
            2 -> "Movement alarm"
            else -> "Pocket alarm"
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var siren: Siren
    private var sensors: SensorManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var pocket: PocketDetector? = null
    private var motion: MotionDetector? = null
    private var proximityMax = 5f
    private var powerReceiver: BroadcastReceiver? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var chargeAlerted = false
    private var lastFullSample = 0L
    private var savedDndFilter = -1

    private val beginMonitoring = Runnable { monitor() }
    private val sound = Runnable {
        if (phase == Phase.TRIGGERED) {
            phase = Phase.SOUNDING
            siren.start()
            refresh()
        }
    }
    private val ringTimeout = Runnable { stopRing() }
    private val chargeSoundOff = Runnable { if (!ringing && phase != Phase.SOUNDING) siren.stop() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        siren = Siren(this)
        sensors = getSystemService(SensorManager::class.java)
        Notifier.channels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifier.startForeground(this, Notifier.ID_GUARD, ongoing())
        when (intent?.action) {
            ACTION_ARM -> arm()
            ACTION_DISARM -> disarm()
            ACTION_RING -> ring(intent.getStringExtra(EXTRA_SENDER))
            ACTION_STOP_RING -> stopRing()
            ACTION_CHARGE_WATCH -> startChargeWatch()
            ACTION_STOP_CHARGE_ALERT -> {
                handler.removeCallbacks(chargeSoundOff); chargeSoundOff.run()
                Notifier.cancel(this, Notifier.ID_CHARGED)
            }
            null -> { // restarted by Android after being killed
                if (prefs.armed) arm(immediately = true)
                if (isPlugged()) startChargeWatch()
            }
        }
        refresh()
        return START_STICKY
    }

    // ---- theft alarm ---------------------------------------------------------

    private fun arm(immediately: Boolean = false) {
        if (!prefs.hasPin) return
        if (phase == Phase.ARMED || phase == Phase.ARMING || phase == Phase.TRIGGERED || phase == Phase.SOUNDING) return
        if (prefs.alarmMode == 1 && !isPlugged()) {
            Notifier.warn(this, Notifier.ID_BATTERY, "Plug in your charger first", "The charger alarm needs the phone to be charging.", 1)
            prefs.armed = false
            return
        }
        prefs.armed = true
        phase = Phase.ARMING
        acquireWake()
        handler.postDelayed(beginMonitoring, if (immediately) 0 else ARM_DELAY_MS)
    }

    private fun monitor() {
        if (phase != Phase.ARMING) return
        val sm = sensors
        when (prefs.alarmMode) {
            1 -> {
                powerReceiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) = trigger("charger unplugged")
                }.also { registerSystemReceiver(it, IntentFilter(Intent.ACTION_POWER_DISCONNECTED)) }
            }
            2 -> {
                val acc = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                if (sm == null || acc == null) return failMonitoring("This phone has no motion sensor.")
                motion = MotionDetector()
                sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_UI)
            }
            else -> {
                val prox = sm?.getDefaultSensor(Sensor.TYPE_PROXIMITY, true) ?: sm?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
                if (sm == null || prox == null) return failMonitoring("This phone has no proximity sensor. Use Movement mode instead.")
                proximityMax = prox.maximumRange
                pocket = PocketDetector()
                sm.registerListener(this, prox, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
        phase = Phase.ARMED
        EventLog.add(this, "${modeName(prefs.alarmMode)} armed")
        refresh()
    }

    private fun failMonitoring(message: String) {
        Notifier.warn(this, Notifier.ID_BATTERY, "Couldn't arm", message, 1)
        disarm()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (phase != Phase.ARMED) return
        when (event.sensor.type) {
            Sensor.TYPE_PROXIMITY -> {
                val near = event.values[0] < minOf(proximityMax, 5f)
                if (pocket?.onSample(near, System.currentTimeMillis()) == true) trigger("taken out of pocket")
            }
            Sensor.TYPE_ACCELEROMETER -> {
                if (motion?.onSample(event.values[0], event.values[1], event.values[2]) == true) trigger("phone moved")
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun stopSensors() {
        sensors?.unregisterListener(this)
        powerReceiver?.let { runCatching { unregisterReceiver(it) } }
        powerReceiver = null
        pocket = null
        motion = null
    }

    private fun trigger(reason: String) {
        if (phase != Phase.ARMED) return
        stopSensors()
        phase = Phase.TRIGGERED
        EventLog.add(this, "Alarm triggered: $reason")
        showAlarmScreen("Alarm: $reason", "Enter your PIN to stop it.")
        handler.postDelayed(sound, prefs.graceSec.coerceIn(0, 30) * 1000L)
        refresh()
    }

    private fun disarm() {
        handler.removeCallbacks(beginMonitoring)
        handler.removeCallbacks(sound)
        stopSensors()
        val wasSounding = phase == Phase.SOUNDING || phase == Phase.TRIGGERED
        phase = Phase.IDLE
        prefs.armed = false
        if (!ringing) siren.stop()
        Notifier.cancel(this, Notifier.ID_ALARM)
        if (wasSounding) EventLog.add(this, "Alarm stopped with PIN")
        releaseWake()
    }

    private fun showAlarmScreen(title: String, text: String) {
        val pi = PendingIntent.getActivity(
            this, 7,
            Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notifier.builder(this, Notifier.CH_ALARM, title, text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .build()
        Notifier.post(this, Notifier.ID_ALARM, n)
    }

    // ---- find my phone ------------------------------------------------------------

    private fun ring(sender: String?) {
        ringing = true
        overrideDnd()
        siren.start()
        val stop = PendingIntent.getService(
            this, 8, Intent(this, GuardService::class.java).setAction(ACTION_STOP_RING),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notifier.builder(this, Notifier.CH_ALARM, "Find my phone", "Someone texted your secret word, so your phone is ringing.")
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(Notifier.openApp(this, 1))
            .addAction(Notification.Action.Builder(null, "I found it", stop).build())
            .build()
        Notifier.post(this, Notifier.ID_ALARM, n)
        handler.removeCallbacks(ringTimeout)
        handler.postDelayed(ringTimeout, RING_MS)
        if (sender != null) FindPhone.replyAsync(this, sender)
    }

    private fun stopRing() {
        if (!ringing) return
        ringing = false
        handler.removeCallbacks(ringTimeout)
        if (phase != Phase.SOUNDING) siren.stop()
        restoreDnd()
        if (phase == Phase.IDLE) Notifier.cancel(this, Notifier.ID_ALARM)
        refresh()
    }

    private fun overrideDnd() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (!nm.isNotificationPolicyAccessGranted) return
        savedDndFilter = nm.currentInterruptionFilter
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
    }

    private fun restoreDnd() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (savedDndFilter > 0 && nm.isNotificationPolicyAccessGranted) nm.setInterruptionFilter(savedDndFilter)
        savedDndFilter = -1
    }

    // ---- charge alarm ---------------------------------------------------------------

    private fun isPlugged(): Boolean = BatteryInfo.read(this)?.plugged == true

    private fun startChargeWatch() {
        if (chargeWatching) return
        chargeWatching = true
        chargeAlerted = false
        lastFullSample = 0
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = onBattery(i)
        }.also { r -> registerSystemReceiver(r, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { onBattery(it) } }
    }

    private fun onBattery(i: Intent) {
        val info = BatteryInfo.from(i)
        if (!info.plugged) {
            stopChargeWatch(); return
        }
        trackFullTime(info.level)
        if (prefs.chargeAlarmOn && !chargeAlerted && info.level >= prefs.chargeTarget) {
            chargeAlerted = true
            EventLog.add(this, "Charged to ${info.level}%")
            val stop = PendingIntent.getService(
                this, 9, Intent(this, GuardService::class.java).setAction(ACTION_STOP_CHARGE_ALERT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            Notifier.post(this, Notifier.ID_CHARGED,
                Notifier.builder(this, Notifier.CH_WARN, "Charged to ${info.level}%", "Unplug now to help your battery last longer.")
                    .setAutoCancel(true)
                    .setContentIntent(Notifier.openApp(this, 3))
                    .addAction(Notification.Action.Builder(null, "Stop sound", stop).build())
                    .build())
            if (!siren.isPlaying) {
                siren.start()
                handler.postDelayed(chargeSoundOff, 15_000)
            }
        }
        refresh()
    }

    private fun trackFullTime(level: Int) {
        val now = System.currentTimeMillis()
        if (level >= 100 && lastFullSample > 0) {
            val today = LocalDate.now()
            val week = "${today.get(IsoFields.WEEK_BASED_YEAR)}-${today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}"
            val parts = prefs.fullMinutes.split(':')
            val base = if (parts.size == 2 && parts[0] == week) parts[1].toLongOrNull() ?: 0 else 0
            val add = ((now - lastFullSample) / 60_000).coerceIn(0, 60)
            if (add > 0) prefs.fullMinutes = "$week:${base + add}"
        }
        lastFullSample = if (level >= 100) (if (lastFullSample == 0L || now - lastFullSample >= 60_000) now else lastFullSample) else 0
    }

    private fun stopChargeWatch() {
        if (!chargeWatching) return
        chargeWatching = false
        batteryReceiver?.let { runCatching { unregisterReceiver(it) } }
        batteryReceiver = null
        handler.removeCallbacks(chargeSoundOff)
        chargeSoundOff.run()
        Notifier.cancel(this, Notifier.ID_CHARGED)
        Jobs.scheduleChargeJob(this) // wait for the next plug-in
        refresh()
    }

    // ---- plumbing ---------------------------------------------------------------------

    private fun registerSystemReceiver(r: BroadcastReceiver, f: IntentFilter): Intent? =
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(r, f)

    private fun acquireWake() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "guardian:armed")
            ?.apply { acquire(12 * 60 * 60 * 1000L) }
    }

    private fun releaseWake() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun ongoing(): Notification {
        val text = when {
            phase == Phase.SOUNDING || phase == Phase.TRIGGERED -> "Alarm going off. Enter your PIN to stop it."
            phase == Phase.ARMING -> "${modeName(prefs.alarmMode)} arms in a few seconds…"
            phase == Phase.ARMED -> "${modeName(prefs.alarmMode)} is armed. Tap to disarm with your PIN."
            ringing -> "Ringing because of a find-my-phone text."
            chargeWatching -> "Watching your charge: alarm at ${prefs.chargeTarget}%."
            else -> "Guardian is working…"
        }
        val tap = if (phase != Phase.IDLE) {
            PendingIntent.getActivity(this, 6, Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } else Notifier.openApp(this, if (chargeWatching) 3 else 0)
        return Notifier.builder(this, Notifier.CH_GUARD, "Guardian", text)
            .setOngoing(true)
            .setContentIntent(tap)
            .build()
    }

    /** Updates the ongoing notification, or stops the service when idle. */
    private fun refresh() {
        Tiles.refreshAll(this)
        if (phase == Phase.IDLE && !ringing && !chargeWatching) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            Notifier.post(this, Notifier.ID_GUARD, ongoing())
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopSensors()
        batteryReceiver?.let { runCatching { unregisterReceiver(it) } }
        siren.stop()
        restoreDnd()
        releaseWake()
        if (phase != Phase.IDLE) phase = Phase.IDLE // prefs.armed stays, so a restart re-arms
        ringing = false
        chargeWatching = false
        super.onDestroy()
    }
}
