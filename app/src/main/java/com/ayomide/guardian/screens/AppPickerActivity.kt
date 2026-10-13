package com.ayomide.guardian.screens

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.ayomide.guardian.R
import com.ayomide.guardian.data.Prefs
import com.ayomide.guardian.system.AdBlockVpn
import com.ayomide.guardian.ui.BaseScreen
import com.ayomide.guardian.ui.dp
import com.ayomide.guardian.ui.rounded

/** Choose which installed apps get ad blocking. */
class AppPickerActivity : BaseScreen() {

    override val screenTitle = "Choose apps"

    private class App(val pkg: String, val label: String, val icon: Drawable?)

    private lateinit var prefs: Prefs
    private var apps: List<App>? = null
    private var query = ""
    private var changed = false
    private val search by lazy {
        ui.input("Search apps", "").apply {
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString()?.trim()?.lowercase().orEmpty(); renderList()
                }
            })
        }
    }
    private val list by lazy { ui.column() }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        super.onCreate(savedInstanceState)
        Thread {
            val pm = packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val loaded = pm.queryIntentActivities(launcher, 0)
                .map { it.activityInfo.packageName }
                .distinct()
                .filter { it != packageName }
                .mapNotNull { pkg ->
                    runCatching {
                        val info = pm.getApplicationInfo(pkg, 0)
                        App(pkg, pm.getApplicationLabel(info).toString(), pm.getApplicationIcon(info))
                    }.getOrNull()
                }
                .sortedBy { it.label.lowercase() }
            runOnUiThread { apps = loaded; refresh() }
        }.start()
    }

    override fun onPause() {
        super.onPause()
        if (changed && AdBlockVpn.running) {
            changed = false
            AdBlockVpn.stop(this)
            window.decorView.postDelayed({ if (prefs.adEnabled && prefs.adApps.isNotEmpty()) AdBlockVpn.start(applicationContext) }, 800)
        }
    }

    override fun render() {
        add(ui.small("Ads are blocked only inside the apps you switch on. Good picks: browsers, games and free apps with lots of ads. Avoid banking apps."))
        (search.parent as? ViewGroup)?.removeView(search)
        add(search)
        (list.parent as? ViewGroup)?.removeView(list)
        add(list)
        renderList()
    }

    private fun renderList() {
        list.removeAllViews()
        val all = apps
        if (all == null) {
            list.addView(ui.empty("Loading your apps…")); return
        }
        val chosen = prefs.adApps
        val shown = all.filter { query.isEmpty() || it.label.lowercase().contains(query) }
            .sortedByDescending { it.pkg in chosen }
        list.addView(ui.card().apply {
            setPadding(0, dp(6), 0, dp(6))
            if (shown.isEmpty()) addView(ui.small("No apps match.").apply { setPadding(dp(20), dp(12), dp(20), dp(12)) })
            for (app in shown) {
                val on = app.pkg in chosen
                addView(ui.row().apply {
                    setPadding(dp(20), dp(10), dp(20), dp(10))
                    isClickable = true
                    setOnClickListener {
                        prefs.adApps = if (on) prefs.adApps - app.pkg else prefs.adApps + app.pkg
                        changed = true
                        renderList()
                    }
                    addView(ImageView(context).apply {
                        setImageDrawable(app.icon)
                        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) }
                    })
                    addView(ui.text(app.label, 15f, R.color.text_primary, true).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    addView(FrameLayout(context).apply {
                        background = rounded(if (on) R.color.net_strong else R.color.card_high, 100)
                        layoutParams = LinearLayout.LayoutParams(dp(56), dp(32))
                        contentDescription = if (on) "${app.label}: on" else "${app.label}: off"
                        addView(View(context).apply {
                            background = rounded(if (on) R.color.on_net_strong else R.color.text_secondary, 100)
                            layoutParams = FrameLayout.LayoutParams(dp(24), dp(24),
                                (if (on) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL).apply { marginStart = dp(4); marginEnd = dp(4) }
                        })
                    })
                })
            }
        })
    }
}
