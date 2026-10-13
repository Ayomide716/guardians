package com.ayomide.guardian.system

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.ayomide.guardian.R
import com.ayomide.guardian.core.DataMath
import com.ayomide.guardian.data.Prefs
import com.ayomide.guardian.screens.AlarmActivity
import com.ayomide.guardian.screens.MainActivity

object Tiles {
    val ALL = listOf(
        PocketTile::class.java to "Pocket alarm",
        AdBlockTile::class.java to "Ad blocker",
        DataTile::class.java to "Data today",
        ChargeTile::class.java to "Charge alarm",
    )

    fun refreshAll(ctx: Context) {
        for ((cls, _) in ALL) {
            try {
                TileService.requestListeningState(ctx, ComponentName(ctx, cls))
            } catch (e: Exception) {
                // tile not added; nothing to update
            }
        }
    }
}

/** Shared tile helpers: update state and open the app from the shade. */
abstract class BaseTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        update()
    }

    abstract fun update()

    protected fun apply(active: Boolean, subtitle: String?) {
        val t = qsTile ?: return
        t.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.subtitle = subtitle
        t.updateTile()
    }

    // The Intent overload is only used below Android 14, where it is the supported API.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    protected fun openActivity(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    protected fun openTab(tab: Int) = openActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, tab))
}

class PocketTile : BaseTile() {
    override fun update() {
        val armed = Prefs(this).armed
        apply(armed, if (armed) "Armed" else "Off")
    }

    override fun onClick() {
        val prefs = Prefs(this)
        when {
            prefs.armed -> openActivity(Intent(this, AlarmActivity::class.java)) // disarming needs the PIN
            !prefs.hasPin -> openTab(1)
            GuardService.start(this, GuardService.ACTION_ARM) -> update()
            else -> openTab(1)
        }
    }
}

class AdBlockTile : BaseTile() {
    override fun update() {
        val on = AdBlockVpn.running
        apply(on, if (on) "${Prefs(this).adApps.size} apps" else "Off")
    }

    override fun onClick() {
        val prefs = Prefs(this)
        if (AdBlockVpn.running) {
            prefs.adEnabled = false
            AdBlockVpn.stop(this)
        } else if (prefs.adApps.isEmpty() || VpnService.prepare(this) != null) {
            openTab(2) // choose apps / give VPN permission first
            return
        } else {
            prefs.adEnabled = true
            AdBlockVpn.start(this)
        }
        apply(!AdBlockVpn.running, if (!AdBlockVpn.running) "Starting…" else "Off")
    }
}

class DataTile : BaseTile() {
    override fun update() {
        val used = DataWatch.usage(this, DataWatch.todayStart())
        val t = qsTile ?: return
        t.state = Tile.STATE_INACTIVE
        t.subtitle = used?.let { DataMath.format(it) } ?: "Allow usage access"
        t.icon = Icon.createWithResource(this, R.drawable.ic_chart)
        t.updateTile()
    }

    override fun onClick() = openTab(2)
}

class ChargeTile : BaseTile() {
    override fun update() {
        val p = Prefs(this)
        apply(p.chargeAlarmOn, if (p.chargeAlarmOn) "At ${p.chargeTarget}%" else "Off")
    }

    override fun onClick() {
        val p = Prefs(this)
        p.chargeAlarmOn = !p.chargeAlarmOn
        if (p.chargeAlarmOn) {
            Jobs.scheduleChargeJob(this)
            if (BatteryInfo.read(this)?.plugged == true) GuardService.start(this, GuardService.ACTION_CHARGE_WATCH)
        }
        update()
    }
}
