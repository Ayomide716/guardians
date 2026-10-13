package com.ayomide.guardian.screens

import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import com.ayomide.guardian.R
import com.ayomide.guardian.data.EventLog
import com.ayomide.guardian.data.Prefs
import com.ayomide.guardian.system.AdBlockVpn
import com.ayomide.guardian.system.Tiles
import com.ayomide.guardian.ui.BaseScreen
import com.ayomide.guardian.ui.dp
import com.ayomide.guardian.ui.marginTop
import com.ayomide.guardian.ui.ripple
import com.ayomide.guardian.ui.rounded
import java.util.concurrent.Executor

class SettingsActivity : BaseScreen() {

    override val screenTitle = "Settings"

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    override fun render() {
        val prefs = Prefs(this)

        add(ui.section("Quick Settings tiles"))
        add(ui.card().apply {
            addView(ui.small("Control Guardian from the swipe-down panel without opening the app."))
            val preview = listOf(
                Triple("Pocket alarm", R.drawable.ic_phone, prefs.armed),
                Triple("Ad blocker", R.drawable.ic_block, AdBlockVpn.running),
                Triple("Data today", R.drawable.ic_chart, false),
                Triple("Charge alarm", R.drawable.ic_battery, prefs.chargeAlarmOn),
            )
            val grid = ui.column().apply {
                background = rounded(R.color.bg, 22)
                setPadding(dp(10), dp(10), dp(10), dp(10))
            }
            for (r in 0..1) {
                grid.addView(ui.row().apply {
                    for (c in 0..1) {
                        val (label, icon, on) = preview[r * 2 + c]
                        addView(ui.tonalCard(if (on) R.color.accent else R.color.card_high, null, 18).apply {
                            setPadding(dp(12), dp(12), dp(12), dp(12))
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                                setMargins(dp(4), dp(4), dp(4), dp(4))
                            }
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            addView(ui.iconBadge(icon, if (on) R.color.on_accent else R.color.card, if (on) R.color.accent else R.color.text_primary, 34))
                            addView(ui.text(label, 13f, if (on) R.color.on_accent else R.color.text_primary, true).apply {
                                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) }
                            })
                        })
                    }
                })
            }
            addView(grid.marginTop(dp(12)))
            addView(ui.button("Add tiles to my panel") { addTiles() })
            addView(ui.small(if (Build.VERSION.SDK_INT >= 33) "Android asks you to confirm each tile." else
                "Swipe down twice, tap the pencil, and drag Guardian's tiles into your panel.").marginTop(dp(10)))
        })

        add(ui.section("Permissions"))
        add(ui.card().apply {
            setPadding(0, dp(6), 0, dp(6))
            for (p in Perms.all) {
                val ok = p.granted(this@SettingsActivity)
                addView(ui.row().apply {
                    setPadding(dp(20), dp(14), dp(20), dp(14))
                    background = ripple(null, 0)
                    isClickable = true
                    setOnClickListener { if (!ok) p.request(this@SettingsActivity) else Perms.open(this@SettingsActivity,
                        android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) }
                    addView(ui.column().apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
                        addView(ui.text(p.title, 15f, R.color.text_primary, true))
                        addView(ui.small(p.why))
                    })
                    addView(ui.text(if (ok) "Allowed" else if (p.opensSettings) "Open" else "Allow", 14f, if (ok) R.color.accent else R.color.danger, true).apply {
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    })
                })
            }
        })

        add(ui.section("General"))
        add(ui.card().apply {
            setPadding(0, dp(6), 0, dp(6))
            fun row(title: String, value: String, onClick: () -> Unit) = addView(ui.row().apply {
                setPadding(dp(20), dp(16), dp(20), dp(16))
                background = ripple(null, 0); isClickable = true; setOnClickListener { onClick() }
                addView(ui.text(title, 15f, R.color.text_primary, true).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
                addView(ui.small(value).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) })
            })
            row("Stop PIN", if (prefs.hasPin) "Change" else "Set") { PinDialog.show(ui) { refresh() } }
            row("Theme", "Follows your phone") { Toast.makeText(this@SettingsActivity, "Guardian follows your phone's light/dark setting.", Toast.LENGTH_SHORT).show() }
            row("Clear history", "Events & stats") {
                ui.confirm("Clear history?", "Removes the event list and today's blocked count. Settings are kept.", "Clear") {
                    EventLog.clear(this@SettingsActivity); prefs.addBlocked(-prefs.blockedToday()); refresh()
                }
            }
        })
        add(ui.small("Guardian 1.0 · Everything is stored only on this phone and excluded from cloud backups. The only download is the optional full ad block list.").apply {
            setPadding(dp(4), dp(16), dp(4), 0)
        })
    }

    private fun addTiles(index: Int = 0) {
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this, "Swipe down twice, tap the pencil and drag Guardian's tiles in.", Toast.LENGTH_LONG).show()
            return
        }
        if (index >= Tiles.ALL.size) {
            Tiles.refreshAll(this); return
        }
        val (cls, label) = Tiles.ALL[index]
        val icon = when (index) {
            0 -> R.drawable.ic_phone
            1 -> R.drawable.ic_block
            2 -> R.drawable.ic_chart
            else -> R.drawable.ic_battery
        }
        val sbm = getSystemService(StatusBarManager::class.java) ?: return
        val main = Executor { r -> runOnUiThread(r) }
        try {
            sbm.requestAddTileService(ComponentName(this, cls), label, Icon.createWithResource(this, icon), main) { addTiles(index + 1) }
        } catch (e: Exception) {
            addTiles(index + 1)
        }
    }
}
