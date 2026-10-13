package com.ayomide.guardian.screens

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import com.ayomide.guardian.R
import com.ayomide.guardian.data.Prefs
import com.ayomide.guardian.ui.BaseScreen
import com.ayomide.guardian.ui.dp
import com.ayomide.guardian.ui.marginTop

/** First-run setup: explains and requests each permission; everything is skippable. */
class OnboardingActivity : BaseScreen() {

    override val screenTitle = "Set up Guardian"
    override val showBack = false

    private val items = listOf(
        Triple(Perms.notifications, R.color.accent_container, R.color.accent) to R.drawable.ic_bell,
        Triple(Perms.fullScreen, R.color.accent_container, R.color.accent) to R.drawable.ic_lock,
        Triple(Perms.usage, R.color.dat, R.color.dat_strong) to R.drawable.ic_chart,
        Triple(Perms.sms, R.color.sec, R.color.sec_strong) to R.drawable.ic_message,
        Triple(Perms.location, R.color.sec, R.color.sec_strong) to R.drawable.ic_pin,
        Triple(Perms.dnd, R.color.sec, R.color.sec_strong) to R.drawable.ic_volume,
        Triple(Perms.battery, R.color.bat, R.color.bat_strong) to R.drawable.ic_battery,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs(this).onboarded = true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    override fun render() {
        add(ui.iconBadge(R.drawable.ic_shield, R.color.accent, R.color.on_accent, 64).apply {
            (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(4)
        })
        add(ui.text("Each guard asks only for what it needs. You can skip any of these and allow them later in Settings.", 15f).marginTop(dp(14)))
        add(ui.card().apply {
            setPadding(0, dp(6), 0, dp(6))
            for ((t, icon) in items) {
                val (perm, bg, fg) = t
                val ok = perm.granted(this@OnboardingActivity)
                addView(ui.row().apply {
                    setPadding(dp(18), dp(12), dp(18), dp(12))
                    addView(ui.iconBadge(icon, bg, fg, 40))
                    addView(ui.column().apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14); marginEnd = dp(8) }
                        addView(ui.text(perm.title, 15f, R.color.text_primary, true))
                        addView(ui.small(perm.why))
                    })
                    if (ok) addView(ui.text("Allowed", 13.5f, R.color.accent, true).apply {
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    })
                    else addView(ui.chip(if (perm.opensSettings) "Open" else "Allow",
                        if (perm.opensSettings) R.color.accent_container else R.color.accent,
                        if (perm.opensSettings) R.color.on_accent_container else R.color.on_accent).apply {
                        (layoutParams as LinearLayout.LayoutParams).topMargin = 0
                        isClickable = true
                        setOnClickListener { perm.request(this@OnboardingActivity) }
                    })
                })
            }
        })
        add(ui.small("Your settings, history and location never leave your phone. The ad blocker's VPN runs on the phone itself: it drops ad lookups and passes everything else straight through to your normal DNS.").marginTop(dp(14)))
        add(ui.button("Continue") { finish() })
        add(ui.flatButton("Skip for now") { finish() })
    }
}
