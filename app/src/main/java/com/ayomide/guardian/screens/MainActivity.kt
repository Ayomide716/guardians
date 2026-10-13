package com.ayomide.guardian.screens

import android.content.Intent
import android.content.res.ColorStateList
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.ayomide.guardian.R
import com.ayomide.guardian.core.Blocklist
import com.ayomide.guardian.core.DataMath
import com.ayomide.guardian.core.SmsCommand
import com.ayomide.guardian.data.EventLog
import com.ayomide.guardian.data.Prefs
import com.ayomide.guardian.system.AdBlockVpn
import com.ayomide.guardian.system.BatteryInfo
import com.ayomide.guardian.system.DataWatch
import com.ayomide.guardian.system.GuardService
import com.ayomide.guardian.system.Jobs
import com.ayomide.guardian.system.Notifier
import com.ayomide.guardian.system.Tiles
import com.ayomide.guardian.ui.BaseScreen
import com.ayomide.guardian.ui.dp
import com.ayomide.guardian.ui.marginTop
import com.ayomide.guardian.ui.ripple
import com.ayomide.guardian.ui.rounded
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

class MainActivity : BaseScreen() {

    companion object {
        const val EXTRA_TAB = "tab"
        private const val REQ_VPN = 7
        private const val FULL_LIST_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
        private val TAB_NAMES = listOf("Guardian", "Security", "Network", "Battery")
    }

    override val screenTitle = "Guardian"
    override val showBack = false

    private lateinit var prefs: Prefs
    private var tab = 0
    private var networkSub = 0
    private val navItems = ArrayList<Triple<View, ImageView, TextView>>()
    private val handler = Handler(Looper.getMainLooper())
    private var lastState = ""
    private var downloading = false

    private val poll = object : Runnable {
        override fun run() {
            val s = stateKey()
            if (s != lastState) {
                lastState = s; refresh()
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun stateKey() = "${GuardService.phase}${GuardService.ringing}${GuardService.chargeWatching}${AdBlockVpn.running}"

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        tab = intent.getIntExtra(EXTRA_TAB, 0).coerceIn(0, 3)
        super.onCreate(savedInstanceState)
        Notifier.channels(this)
        Jobs.scheduleAll(this)
        actions.addView(iconButton(R.drawable.ic_tune, "Settings") { startActivity(Intent(this, SettingsActivity::class.java)) })
        if (!prefs.onboarded) startActivity(Intent(this, OnboardingActivity::class.java))
        selectTab(tab)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra(EXTRA_TAB)) selectTab(intent.getIntExtra(EXTRA_TAB, 0).coerceIn(0, 3))
    }

    override fun onResume() {
        super.onResume()
        lastState = stateKey()
        handler.postDelayed(poll, 1000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poll)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) startAdBlock() else toast("Android didn't allow the ad blocker's VPN.")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ---- navigation -------------------------------------------------------------

    override fun bottomBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(getColor(R.color.card))
            setPadding(dp(8), dp(10), dp(8), dp(12))
        }
        val items = listOf(R.drawable.ic_home to "Home", R.drawable.ic_shield to "Security", R.drawable.ic_globe to "Network", R.drawable.ic_battery to "Battery")
        for ((i, item) in items.withIndex()) {
            val icon = ImageView(this).apply {
                setImageResource(item.first)
                setPadding(dp(20), dp(4), dp(20), dp(4))
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(32))
            }
            val label = TextView(this).apply {
                text = item.second
                textSize = 12f
                gravity = Gravity.CENTER
            }
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                contentDescription = item.second
                isClickable = true
                isFocusable = true
                background = ripple(null, 20)
                addView(icon); addView(label)
                setOnClickListener { selectTab(i) }
            }
            navItems += Triple(cell, icon, label)
            bar.addView(cell)
        }
        return bar
    }

    private fun selectTab(t: Int) {
        tab = t
        titleView.text = TAB_NAMES[t]
        for ((i, item) in navItems.withIndex()) {
            val (_, icon, label) = item
            val active = i == t
            icon.background = if (active) rounded(R.color.accent_container, 100) else null
            icon.imageTintList = ColorStateList.valueOf(getColor(if (active) R.color.accent else R.color.text_secondary))
            label.setTextColor(getColor(if (active) R.color.text_primary else R.color.text_secondary))
            label.typeface = if (active) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        }
        resetScroll = true
        refresh()
    }

    override fun render() {
        when (tab) {
            1 -> renderSecurity()
            2 -> if (networkSub == 0) renderAdBlock() else renderData()
            3 -> renderBattery()
            else -> renderHome()
        }
    }

    // ---- shared building blocks ---------------------------------------------------

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    private fun badge(icon: Int, bg: Int, fg: Int, size: Int = 44) = ui.iconBadge(icon, bg, fg, size)

    /** Large tinted card with an icon, label and big headline. */
    private fun hero(bg: Int, fg: Int, strong: Int, onStrong: Int, icon: Int, label: String, headline: String, body: String?): LinearLayout =
        ui.tonalCard(bg, null, 28).apply {
            setPadding(dp(22), dp(22), dp(22), dp(22))
            addView(ui.row().apply {
                addView(badge(icon, strong, onStrong))
                addView(ui.text(label, 14f, fg, true).apply { layoutParams = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) } })
            })
            addView(ui.text(headline, 28f, fg, true).marginTop(dp(14)))
            if (body != null) addView(ui.text(body, 15f, fg).marginTop(dp(4)))
        }

    /** Pill button in a feature colour. */
    private fun strongButton(label: String, bg: Int, fg: Int, onClick: () -> Unit) = ui.button(label) { onClick() }.apply {
        background = ripple(rounded(bg, 100), 100)
        setTextColor(getColor(fg))
        minHeight = dp(56)
    }

    private fun section(s: String) = add(ui.section(s))

    /** A list card: rows separated visually by padding. */
    private fun listCard(build: LinearLayout.() -> Unit) = add(ui.card().apply {
        setPadding(0, dp(6), 0, dp(6))
        build()
    })

    private fun LinearLayout.listRow(title: String, value: String, valueColor: Int = R.color.text_secondary, onClick: (() -> Unit)? = null) {
        addView(ui.row().apply {
            setPadding(dp(20), dp(14), dp(20), dp(14))
            minimumHeight = dp(52)
            if (onClick != null) {
                background = ripple(null, 0); isClickable = true; setOnClickListener { onClick() }
            }
            addView(ui.text(title, 15f, R.color.text_primary, true).apply { layoutParams = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
            addView(ui.text(value, 14f, valueColor, valueColor != R.color.text_secondary).apply {
                layoutParams = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                gravity = Gravity.END
            })
        })
    }

    /** Switch drawn to match the mockup. */
    private fun switchView(on: Boolean, onColor: Int, knobOn: Int, onToggle: () -> Unit): FrameLayout =
        FrameLayout(this).apply {
            background = rounded(if (on) onColor else R.color.card_high, 100)
            layoutParams = lp(dp(56), dp(32))
            contentDescription = if (on) "On" else "Off"
            isClickable = true
            isFocusable = true
            setOnClickListener { onToggle() }
            addView(View(context).apply {
                background = rounded(if (on) knobOn else R.color.text_secondary, 100)
                layoutParams = FrameLayout.LayoutParams(dp(24), dp(24), if (on) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.START or Gravity.CENTER_VERTICAL).apply {
                    marginStart = dp(4); marginEnd = dp(4)
                }
            })
        }

    private fun LinearLayout.toggleRow(title: String, sub: String?, on: Boolean, onToggle: () -> Unit) {
        addView(ui.row().apply {
            setPadding(dp(20), dp(12), dp(20), dp(12))
            addView(ui.column().apply {
                layoutParams = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) }
                addView(ui.text(title, 15f, R.color.text_primary, true))
                if (sub != null) addView(ui.small(sub))
            })
            addView(switchView(on, R.color.accent, R.color.on_accent, onToggle))
        })
    }

    /** Small grid tile used on Home. */
    private fun guardTile(bg: Int, fg: Int, icon: Int, label: String, value: String, sub: String, percent: Int?, onClick: () -> Unit) =
        ui.tonalCard(bg, onClick).apply {
            minimumHeight = dp(150)
            addView(ImageView(this@MainActivity).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(getColor(fg))
                layoutParams = lp(dp(22), dp(22))
            })
            addView(ui.text(label, 14f, fg, true).marginTop(dp(12)))
            addView(ui.text(value, 24f, fg, true).apply { maxLines = 1 })
            if (percent != null) addView(ui.progress(percent, fg))
            addView(ui.text(sub, 13f, fg).marginTop(dp(4)))
        }

    private fun pairRow(a: View, b: View) = add(ui.row().apply {
        gravity = Gravity.TOP
        marginTop(dp(12))
        a.layoutParams = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
        b.layoutParams = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) }
        addView(a); addView(b)
    })

    private fun note(s: String) = add(ui.small(s).apply { setPadding(dp(4), dp(10), dp(4), 0) })

    // ---- Home -------------------------------------------------------------------

    private fun renderHome() {
        add(ui.small(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()))).apply { setPadding(dp(4), 0, 0, 0) })
        val phase = GuardService.phase
        val armed = prefs.armed
        val adOn = AdBlockVpn.running
        val guards = listOf(armed, prefs.findEnabled && prefs.secret.isNotBlank(), adOn, prefs.dataWarnOn && DataWatch.hasAccess(this), prefs.chargeAlarmOn)
        val on = guards.count { it }

        if (phase == GuardService.Phase.TRIGGERED || phase == GuardService.Phase.SOUNDING || GuardService.ringing) {
            add(hero(R.color.danger_container, R.color.danger, R.color.danger, R.color.on_accent, R.drawable.ic_volume, "Alarm",
                if (GuardService.ringing) "Phone is ringing" else "Alarm going off", "Enter your PIN to stop it."))
            add(strongButton("Enter PIN", R.color.danger, R.color.on_accent) { startActivity(Intent(this, AlarmActivity::class.java)) })
        } else {
            add(ui.tonalCard(R.color.accent_container, null, 28).apply {
                setPadding(dp(22), dp(22), dp(22), dp(22))
                marginTop(dp(12))
                addView(ui.row().apply {
                    addView(badge(R.drawable.ic_shield, R.color.accent, R.color.on_accent))
                    addView(ui.text("Status", 14f, R.color.on_accent_container, true).apply { layoutParams = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) } })
                })
                addView(ui.text(when {
                    on >= 4 -> "You're protected"
                    on >= 2 -> "Partly protected"
                    else -> "Turn on your guards"
                }, 28f, R.color.on_accent_container, true).marginTop(dp(14)))
                addView(ui.text("$on of 5 guards are on." + if (!armed) " Arm the pocket alarm when you're out." else "", 15f, R.color.on_accent_container))
                val chips = ui.row().apply { setPadding(0, dp(4), 0, 0) }
                if (adOn) chips.addView(ui.chip("Ad blocker on").apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) })
                if (prefs.findEnabled && prefs.secret.isNotBlank()) chips.addView(ui.chip("Find my phone on"))
                if (chips.childCount > 0) addView(chips)
            })
            add(strongButton(if (armed) "Disarm ${GuardService.modeName(prefs.alarmMode).lowercase()}" else "Arm ${GuardService.modeName(prefs.alarmMode).lowercase()}",
                R.color.accent, R.color.on_accent) { if (armed) startActivity(Intent(this, AlarmActivity::class.java)) else armFlow() })
        }

        section("Your guards")
        pairRow(
            guardTile(R.color.sec, R.color.on_sec, R.drawable.ic_phone, GuardService.modeName(prefs.alarmMode),
                when (phase) {
                    GuardService.Phase.ARMING -> "Arming"
                    GuardService.Phase.ARMED -> "Armed"
                    GuardService.Phase.TRIGGERED, GuardService.Phase.SOUNDING -> "Alarm!"
                    else -> if (armed) "Armed" else "Off"
                }, if (armed) "Tap to manage" else "Tap to arm", null) { selectTab(1) },
            guardTile(R.color.sec, R.color.on_sec, R.drawable.ic_pin, "Find my phone",
                if (prefs.findEnabled && prefs.secret.isNotBlank()) "On" else "Off",
                if (prefs.secret.isBlank()) "Set a secret word" else "Secret word set", null) { selectTab(1) },
        )
        val today = DataWatch.usage(this, DataWatch.todayStart())
        val limit = prefs.dailyLimitMb * DataMath.MB
        pairRow(
            guardTile(R.color.net, R.color.on_net, R.drawable.ic_block, "Ad blocker",
                if (adOn) "${prefs.adApps.size} app${if (prefs.adApps.size == 1) "" else "s"}" else "Off",
                if (adOn) "${prefs.blockedToday()} blocked today" else "Tap to set up", null) { networkSub = 0; selectTab(2) },
            guardTile(R.color.dat, R.color.on_dat, R.drawable.ic_chart, "Data today",
                today?.let { DataMath.format(it) } ?: "—",
                if (today == null) "Allow usage access" else "of ${DataMath.format(limit)} daily limit",
                today?.let { DataMath.percent(it, limit) }) { networkSub = 1; selectTab(2) },
        )
        val b = BatteryInfo.read(this)
        add(ui.tonalCard(R.color.bat, { selectTab(3) }).apply {
            addView(ui.row().apply {
                addView(badge(R.drawable.ic_battery, R.color.bat_strong, R.color.on_bat_strong, 52))
                addView(ui.column().apply {
                    layoutParams = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16) }
                    addView(ui.text(if (b?.plugged == true) "Battery · charging" else "Battery", 14f, R.color.on_bat, true))
                    addView(ui.text("${b?.level ?: "–"}%", 24f, R.color.on_bat, true))
                    addView(ui.text(if (prefs.chargeAlarmOn) "Alarm rings at ${prefs.chargeTarget}% to protect your battery" else "Charge alarm is off", 13f, R.color.on_bat))
                })
            })
        })
        note("All checks run on your phone. Nothing is uploaded.")
    }

    // ---- Security -------------------------------------------------------------------

    private fun armFlow() {
        if (!prefs.hasPin) {
            PinDialog.show(ui) { armFlow() }
            return
        }
        if (prefs.alarmMode == 1 && BatteryInfo.read(this)?.plugged != true) {
            toast("Plug in your charger first, then arm the charger alarm.")
            return
        }
        if (GuardService.start(this, GuardService.ACTION_ARM)) {
            prefs.armed = true
            toast("Arming in 5 seconds. Put your phone away.")
        } else {
            toast("Android didn't let Guardian start. Allow notifications and unrestricted battery in Settings.")
        }
        refresh()
    }

    private fun renderSecurity() {
        val phase = GuardService.phase
        val armed = prefs.armed
        add(hero(R.color.sec, R.color.on_sec, R.color.sec_strong, R.color.on_sec_strong, R.drawable.ic_phone,
            "Pocket & charger alarm",
            when {
                phase == GuardService.Phase.ARMING -> "Arming…"
                armed -> "Armed"
                else -> "Off"
            },
            "Sounds a loud alarm if someone takes your phone without your PIN.").apply {
            marginTop(dp(4))
            val modes = listOf("Pocket", "Charger", "Movement")
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                background = rounded(R.color.bg, 100)
                setPadding(dp(4), dp(4), dp(4), dp(4))
                layoutParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) }
                for ((i, m) in modes.withIndex()) {
                    val sel = prefs.alarmMode == i
                    addView(TextView(context).apply {
                        text = m
                        gravity = Gravity.CENTER
                        textSize = 14f
                        typeface = if (sel) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
                        setTextColor(getColor(if (sel) R.color.on_sec_strong else R.color.text_primary))
                        background = if (sel) rounded(R.color.sec_strong, 100) else ripple(null, 100)
                        setPadding(0, dp(10), 0, dp(10))
                        layoutParams = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        isClickable = true
                        setOnClickListener {
                            if (armed) toast("Disarm first to change the mode.") else {
                                prefs.alarmMode = i; refresh()
                            }
                        }
                    })
                }
            })
            addView(ui.text(when (prefs.alarmMode) {
                1 -> "Charger: rings when the charger is unplugged."
                2 -> "Movement: rings when the phone is picked up or moved."
                else -> "Pocket: put it in your pocket or bag; rings when it's taken out."
            }, 13.5f, R.color.on_sec).marginTop(dp(10)))
            addView(strongButton(if (armed) "Disarm with PIN" else "Arm now", R.color.sec_strong, R.color.on_sec_strong) {
                if (armed) startActivity(Intent(this@MainActivity, AlarmActivity::class.java)) else armFlow()
            })
            if (!armed) addView(ui.text("Arms after 5 seconds so you can put the phone away.", 13f, R.color.on_sec).apply { gravity = Gravity.CENTER }.marginTop(dp(8)))
        })

        listCard {
            listRow("Stop PIN", if (prefs.hasPin) "•••• · Change" else "Not set", if (prefs.hasPin) R.color.text_secondary else R.color.danger) { PinDialog.show(ui) { refresh() } }
            listRow("Alarm delay", "${prefs.graceSec} s to enter PIN") {
                val options = listOf(0, 3, 5, 10)
                val sp = ui.spinner(options.map { if (it == 0) "Ring immediately" else "$it seconds" }, options.indexOf(prefs.graceSec).coerceAtLeast(0))
                ui.form("Alarm delay", ui.column().apply {
                    addView(ui.small("Time to type your PIN before the siren starts."))
                    addView(sp)
                }) { prefs.graceSec = options[sp.selectedItemPosition]; refresh(); null }
            }
            if (!Perms.fullScreen.granted(this@MainActivity)) listRow("Full-screen alarm", "Allow", R.color.danger) { Perms.fullScreen.request(this@MainActivity) }
        }

        section("Find my phone")
        val findOn = prefs.findEnabled
        add(ui.card().apply {
            addView(ui.row().apply {
                addView(ui.column().apply {
                    layoutParams = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(ui.title("Secret word"))
                    addView(ui.small(if (prefs.secret.isBlank()) "Not set" else "${prefs.secret.first()}${"•".repeat((prefs.secret.length - 1).coerceAtMost(8))}"))
                })
                addView(switchView(findOn, R.color.accent, R.color.on_accent) {
                    if (!findOn && prefs.secret.length < SmsCommand.MIN_SECRET) editSecret()
                    else {
                        prefs.findEnabled = !findOn
                        if (prefs.findEnabled && !Perms.sms.granted(this@MainActivity)) Perms.sms.request(this@MainActivity)
                        refresh()
                    }
                })
            })
            addView(ui.small("Lost it? Text the secret word to your number from any phone. Guardian will:").marginTop(dp(10)))
            fun check(text: String, ok: Boolean, toggle: (() -> Unit)?) = addView(ui.row().apply {
                setPadding(0, dp(8), 0, 0)
                addView(ImageView(context).apply {
                    setImageResource(if (ok) R.drawable.ic_check else R.drawable.ic_close)
                    imageTintList = ColorStateList.valueOf(getColor(if (ok) R.color.accent else R.color.text_secondary))
                    layoutParams = lp(dp(20), dp(20)).apply { marginEnd = dp(10) }
                })
                addView(ui.primary(text).apply { layoutParams = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
                if (toggle != null) { isClickable = true; background = ripple(null, 12); setOnClickListener { toggle() } }
            })
            check("Ring at full volume, even on silent", true, null)
            check("Text back its location (map link)", prefs.replyLocation) { prefs.replyLocation = !prefs.replyLocation; refresh() }
            check("Text back battery level", prefs.replyBattery) { prefs.replyBattery = !prefs.replyBattery; refresh() }
            addView(ui.row().apply {
                setPadding(0, dp(4), 0, 0)
                addView(ui.tonalButton("Secret word") { editSecret() }.apply { layoutParams = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { topMargin = dp(12); marginEnd = dp(6) } })
                addView(ui.tonalButton("Trusted numbers") { editTrusted() }.apply { layoutParams = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { topMargin = dp(12); marginStart = dp(6) } })
            })
            addView(ui.tonalButton("Test: ring now") {
                if (GuardService.start(this@MainActivity, GuardService.ACTION_RING)) toast("Ringing. Tap \"I found it\" in the notification to stop.")
            })
            addView(ui.small("Replies are normal SMS from your SIM and cost a little airtime.").marginTop(dp(10)))
        })
        val missing = listOf(Perms.sms, Perms.location, Perms.backgroundLocation, Perms.dnd).filter { !it.granted(this) }
        if (findOn && missing.isNotEmpty()) {
            listCard {
                for (p in missing) listRow(p.title, if (p.opensSettings) "Open" else "Allow", R.color.danger) { p.request(this@MainActivity) }
            }
        }

        section("Recent events")
        val events = EventLog.all(this).take(6)
        if (events.isEmpty()) add(ui.empty("Nothing yet."))
        else add(ui.card().apply {
            val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            for ((t, e) in events) {
                addView(ui.text(e, 15f, R.color.text_primary, true).marginTop(dp(4)))
                addView(ui.small(fmt.format(Date(t))))
            }
        })
    }

    private fun editSecret() {
        val word = ui.input("Secret word (at least ${SmsCommand.MIN_SECRET} letters)", prefs.secret,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        ui.form("Secret word", ui.column().apply {
            addView(ui.small("Pick a word nobody would text by accident, like \"pineapple-42\". The whole text must be exactly this word (capitals don't matter)."))
            addView(word)
        }) {
            val w = word.text.toString().trim()
            if (w.length < SmsCommand.MIN_SECRET || w.contains(' ')) "Use at least ${SmsCommand.MIN_SECRET} characters and no spaces." else {
                prefs.secret = w
                prefs.findEnabled = true
                if (!Perms.sms.granted(this)) Perms.sms.request(this)
                refresh(); null
            }
        }
    }

    private fun editTrusted() {
        val list = ui.multiline("One number per line", prefs.trustedList.joinToString("\n"), 3).apply {
            inputType = InputType.TYPE_CLASS_PHONE or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        ui.form("Trusted numbers", ui.column().apply {
            addView(ui.small("Leave empty to accept the secret word from any number (best if you lose your phone and borrow someone's). Or list the numbers allowed to use it."))
            addView(list)
        }) {
            prefs.trusted = list.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")
            refresh(); null
        }
    }

    // ---- Network: ad blocker ------------------------------------------------------------

    private fun networkTabs() {
        add(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(R.color.card, 100)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
            for ((i, name) in listOf("Ad blocker", "Data usage").withIndex()) {
                val sel = networkSub == i
                val (bg, fg) = if (i == 0) R.color.net to R.color.on_net else R.color.dat to R.color.on_dat
                addView(TextView(context).apply {
                    text = name
                    gravity = Gravity.CENTER
                    textSize = 14.5f
                    typeface = if (sel) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
                    setTextColor(getColor(if (sel) fg else R.color.text_secondary))
                    background = if (sel) rounded(bg, 100) else ripple(null, 100)
                    setPadding(0, dp(11), 0, dp(11))
                    layoutParams = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    isClickable = true
                    setOnClickListener { networkSub = i; resetScroll = true; refresh() }
                })
            }
        })
    }

    private fun startAdBlock() {
        if (prefs.adApps.isEmpty()) {
            toast("Choose at least one app first.")
            startActivity(Intent(this, AppPickerActivity::class.java)); return
        }
        val consent = VpnService.prepare(this)
        if (consent != null) {
            startActivityForResult(consent, REQ_VPN); return
        }
        prefs.adEnabled = true
        AdBlockVpn.start(this)
        handler.postDelayed({ refresh() }, 600)
    }

    private fun renderAdBlock() {
        networkTabs()
        val on = AdBlockVpn.running
        add(hero(R.color.net, R.color.on_net, R.color.net_strong, R.color.on_net_strong, R.drawable.ic_block, "Ad blocker",
            if (on) "On in ${prefs.adApps.size} app${if (prefs.adApps.size == 1) "" else "s"}" else "Off",
            if (on) "${prefs.blockedToday()} ads and trackers blocked today." else "Blocks ads and trackers only in the apps you choose.").apply {
            addView(strongButton(if (on) "Turn off" else "Turn on", R.color.net_strong, R.color.on_net_strong) {
                if (on) {
                    prefs.adEnabled = false; AdBlockVpn.stop(this@MainActivity); handler.postDelayed({ refresh() }, 600)
                } else startAdBlock()
            })
        })
        if (privateDnsStrict()) {
            add(ui.tonalCard(R.color.danger_container, null).apply {
                addView(ui.text("Private DNS is on", 15f, R.color.danger, true))
                addView(ui.small("Your phone sends lookups to a private DNS server, which goes around the ad blocker. Set Settings → Connections → More connection settings → Private DNS to Off or Automatic."))
            })
        }

        section("Block ads in these apps")
        val pm = packageManager
        val apps = prefs.adApps.mapNotNull { pkg ->
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() to pkg }.getOrNull()
        }.sortedBy { it.first.lowercase() }
        add(ui.card().apply {
            setPadding(0, dp(6), 0, dp(6))
            if (apps.isEmpty()) addView(ui.small("No apps chosen yet.").apply { setPadding(dp(20), dp(12), dp(20), dp(4)) })
            for ((label, pkg) in apps) {
                addView(ui.row().apply {
                    setPadding(dp(20), dp(10), dp(12), dp(10))
                    addView(ImageView(context).apply {
                        setImageDrawable(runCatching { pm.getApplicationIcon(pkg) }.getOrNull())
                        layoutParams = lp(dp(40), dp(40)).apply { marginEnd = dp(14) }
                    })
                    addView(ui.text(label, 15f, R.color.text_primary, true).apply { layoutParams = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
                    addView(ImageView(context).apply {
                        setImageResource(R.drawable.ic_close)
                        imageTintList = ColorStateList.valueOf(getColor(R.color.text_secondary))
                        contentDescription = "Remove $label"
                        setPadding(dp(12), dp(12), dp(12), dp(12))
                        background = ripple(null, 100)
                        layoutParams = lp(dp(48), dp(48))
                        setOnClickListener {
                            prefs.adApps = prefs.adApps - pkg
                            if (AdBlockVpn.running) restartVpn()
                            refresh()
                        }
                    })
                })
            }
            addView(ui.flatButton("+ Choose apps") { startActivity(Intent(this@MainActivity, AppPickerActivity::class.java)) }.apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dp(20), 0, dp(20), 0)
            })
        })

        val fullFile = File(filesDir, AdBlockVpn.FULL_LIST_FILE)
        listCard {
            listRow("Block list", if (prefs.useFullList && fullFile.exists()) "Full list" else "Built-in") { blockListDialog(fullFile) }
            listRow("Always allow a site", "${prefs.allowSites.size} site${if (prefs.allowSites.size == 1) "" else "s"}") { allowDialog() }
        }
        note("Only the apps you choose go through the blocker; everything else is untouched. Some apps (like YouTube) serve ads from the same servers as videos, so a few ads may still appear.")
    }

    private fun restartVpn() {
        AdBlockVpn.stop(this)
        handler.postDelayed({ if (prefs.adEnabled) AdBlockVpn.start(this) }, 800)
    }

    private fun privateDnsStrict(): Boolean = try {
        val cm = getSystemService(ConnectivityManager::class.java)
        val lp = cm?.activeNetwork?.let { cm.getLinkProperties(it) }
        lp?.privateDnsServerName != null
    } catch (e: Exception) {
        false
    }

    private fun blockListDialog(fullFile: File) {
        val body = ui.column().apply {
            addView(ui.small("Built-in: about 85 big ad and tracking networks.\n\nFull list: about 100,000 known ad, tracker and malware sites (a free public list by StevenBlack). Downloads about 3 MB once; only the list is downloaded, nothing is uploaded."))
            if (fullFile.exists()) addView(ui.small("Full list downloaded.").marginTop(dp(8)))
        }
        val dialog = android.app.AlertDialog.Builder(this).setTitle("Block list").setView(android.widget.ScrollView(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0); addView(body)
        })
        if (fullFile.exists()) {
            dialog.setPositiveButton(if (prefs.useFullList) "Use built-in only" else "Use full list") { _, _ ->
                prefs.useFullList = !prefs.useFullList
                if (AdBlockVpn.running) restartVpn()
                refresh()
            }
            dialog.setNeutralButton("Update") { _, _ -> downloadFullList(fullFile) }
        } else {
            dialog.setPositiveButton("Download full list") { _, _ -> downloadFullList(fullFile) }
        }
        dialog.setNegativeButton("Close", null).show()
    }

    private fun downloadFullList(target: File) {
        if (downloading) return
        downloading = true
        toast("Downloading the full block list…")
        Thread {
            val result = try {
                val conn = URL(FULL_LIST_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                val tmp = File(target.parentFile, target.name + ".tmp")
                conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                conn.disconnect()
                val count = Blocklist.parse(tmp.readText()).size
                if (count < 1000) {
                    tmp.delete(); "The download looked wrong. Try again later."
                } else {
                    tmp.renameTo(target)
                    prefs.useFullList = true
                    "Full list ready: $count sites."
                }
            } catch (e: Exception) {
                "Download failed. Check your connection and try again."
            }
            handler.post {
                downloading = false
                toast(result)
                if (AdBlockVpn.running) restartVpn()
                refresh()
            }
        }.start()
    }

    private fun allowDialog() {
        val input = ui.input("e.g. example.com", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val body = ui.column().apply {
            addView(ui.small("If a site or app breaks, add its address here so it's never blocked."))
            for (s in prefs.allowSites.sorted()) addView(ui.flatButton("✕  $s") {
                prefs.allowSites = prefs.allowSites - s
                if (AdBlockVpn.running) restartVpn()
                toast("Removed $s"); refresh()
            }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL })
            addView(input)
        }
        ui.form("Always allow", body, saveLabel = "Add") {
            val host = input.text.toString().trim().lowercase()
                .removePrefix("https://").removePrefix("http://").substringBefore('/')
            if (!Blocklist.isDomain(host)) "Enter a site address like example.com" else {
                prefs.allowSites = prefs.allowSites + host
                if (AdBlockVpn.running) restartVpn()
                refresh(); null
            }
        }
    }

    // ---- Network: data usage ------------------------------------------------------------

    private fun renderData() {
        networkTabs()
        if (!DataWatch.hasAccess(this)) {
            add(hero(R.color.dat, R.color.on_dat, R.color.dat_strong, R.color.on_dat_strong, R.drawable.ic_chart, "Data usage",
                "Needs usage access", "Android keeps a count of the mobile data each app uses. Allow Guardian to read it. Nothing leaves your phone.").apply {
                addView(strongButton("Allow usage access", R.color.dat_strong, R.color.on_dat_strong) { Perms.usage.request(this@MainActivity) })
                addView(ui.text("Find Guardian in the list and switch it on, then come back.", 13f, R.color.on_dat).marginTop(dp(8)))
            })
            return
        }
        val today = DataWatch.usage(this, DataWatch.todayStart()) ?: 0
        val periodFrom = DataWatch.periodStart(prefs)
        val period = DataWatch.usage(this, periodFrom) ?: 0
        val limit = prefs.dailyLimitMb * DataMath.MB
        val bundle = prefs.bundleMb * DataMath.MB
        add(ui.tonalCard(R.color.dat, null, 28).apply {
            setPadding(dp(22), dp(22), dp(22), dp(22))
            marginTop(dp(12))
            addView(ui.text("Mobile data today", 14f, R.color.on_dat, true))
            addView(ui.text("${DataMath.format(today)}  of ${DataMath.format(limit)}", 30f, R.color.on_dat, true).marginTop(dp(6)))
            addView(ui.progress(DataMath.percent(today, limit), R.color.dat_strong))
            addView(ui.text("You'll be warned at ${prefs.warnPct}% · resets at midnight", 13f, R.color.on_dat).marginTop(dp(8)))
            addView(View(context).apply {
                setBackgroundColor(getColor(R.color.on_dat)); alpha = 0.2f
                layoutParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(16); bottomMargin = dp(12) }
            })
            addView(ui.text("This bundle", 14f, R.color.on_dat, true))
            addView(ui.text("${DataMath.format(period)}  of ${DataMath.format(bundle)}", 22f, R.color.on_dat, true).marginTop(dp(4)))
            addView(ui.progress(DataMath.percent(period, bundle), R.color.dat_strong))
            addView(ui.text("Renews on day ${prefs.renewalDay} of each month", 13f, R.color.on_dat).marginTop(dp(8)))
        })

        section("Top apps today")
        val top = DataWatch.topApps(this, DataWatch.todayStart())
        if (top.isEmpty() || top.first().bytes == 0L) add(ui.empty("No mobile data used yet today."))
        else add(ui.card().apply {
            val max = top.first().bytes.coerceAtLeast(1)
            for ((i, a) in top.withIndex()) {
                addView(ui.row().apply {
                    if (i > 0) marginTop(dp(12))
                    addView(ui.text(a.label, 15f, R.color.text_primary, true).apply { layoutParams = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
                    addView(ui.small(DataMath.format(a.bytes)).apply { layoutParams = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) })
                })
                addView(ui.progress((a.bytes * 100 / max).toInt(), R.color.dat_strong).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(6) })
            }
        })

        section("Warnings")
        listCard {
            toggleRow("Data warnings", "Checked about every 30 minutes", prefs.dataWarnOn) { prefs.dataWarnOn = !prefs.dataWarnOn; refresh() }
            listRow("Daily limit", "${DataMath.format(limit)} · warn at ${prefs.warnPct}%") { limitsDialog() }
            listRow("Bundle size & renewal", "${DataMath.format(bundle)} · day ${prefs.renewalDay}") { limitsDialog() }
            listRow("Data-hog alert", "${prefs.hogMb} MB in 1 hour") { limitsDialog() }
        }
        note("Counts mobile data only; Wi-Fi isn't counted. Your network's own balance (USSD) may differ slightly.")
    }

    private fun limitsDialog() {
        val daily = ui.numberInput("Daily limit in MB", prefs.dailyLimitMb.toString())
        val pct = ui.numberInput("Warn at %", prefs.warnPct.toString())
        val bundle = ui.numberInput("Bundle size in MB", prefs.bundleMb.toString())
        val day = ui.numberInput("Renewal day (1–31)", prefs.renewalDay.toString())
        val hog = ui.numberInput("MB in one hour", prefs.hogMb.toString())
        ui.form("Data limits", ui.column().apply {
            addView(ui.label("Daily limit (MB, 1 GB = 1024)")); addView(daily)
            addView(ui.label("Warn me at this % of a limit")); addView(pct)
            addView(ui.label("Bundle size (MB)")); addView(bundle)
            addView(ui.label("Bundle renews on day")); addView(day)
            addView(ui.label("Data-hog alert: MB used within an hour")); addView(hog)
        }) {
            val d = daily.text.toString().toIntOrNull()
            val p = pct.text.toString().toIntOrNull()
            val b = bundle.text.toString().toIntOrNull()
            val r = day.text.toString().toIntOrNull()
            val h = hog.text.toString().toIntOrNull()
            when {
                d == null || d !in 1..1_000_000 -> "Enter a daily limit in MB."
                p == null || p !in 10..100 -> "Warning % must be 10–100."
                b == null || b !in 1..10_000_000 -> "Enter your bundle size in MB."
                r == null || r !in 1..31 -> "Renewal day must be 1–31."
                h == null || h !in 1..100_000 -> "Enter the data-hog amount in MB."
                else -> {
                    prefs.dailyLimitMb = d; prefs.warnPct = p; prefs.bundleMb = b; prefs.renewalDay = r; prefs.hogMb = h
                    prefs.lastDailyWarn = ""; prefs.lastBundleWarn = ""
                    refresh(); null
                }
            }
        }
    }

    // ---- Battery --------------------------------------------------------------------

    private fun renderBattery() {
        val b = BatteryInfo.read(this)
        add(hero(R.color.bat, R.color.on_bat, R.color.bat_strong, R.color.on_bat_strong, R.drawable.ic_bolt,
            if (b?.plugged == true) "Charging" + if (b.source.isNotEmpty()) " · ${b.source}" else "" else "On battery",
            "${b?.level ?: "–"}%", null).apply {
            addView(ui.progress(b?.level ?: 0, R.color.bat_strong))
            addView(ui.row().apply {
                setPadding(0, dp(8), 0, 0)
                addView(ui.text(if (prefs.chargeAlarmOn) "Alarm at ${prefs.chargeTarget}%" else "Charge alarm off", 13f, R.color.on_bat).apply { layoutParams = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
                addView(ui.text(b?.tempC?.let { String.format(Locale.US, "%.0f °C", it) } ?: "", 13f, R.color.on_bat).apply { layoutParams = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) })
            })
        })

        section("Charge alarm")
        add(ui.card().apply {
            setPadding(0, dp(6), 0, dp(14))
            toggleRow("Ring when charged to", "Unplugging early slows battery wear", prefs.chargeAlarmOn) {
                prefs.chargeAlarmOn = !prefs.chargeAlarmOn
                if (prefs.chargeAlarmOn) {
                    Jobs.scheduleChargeJob(this@MainActivity)
                    if (BatteryInfo.read(this@MainActivity)?.plugged == true) GuardService.start(this@MainActivity, GuardService.ACTION_CHARGE_WATCH)
                }
                Tiles.refreshAll(this@MainActivity); refresh()
            }
            addView(ui.row().apply {
                setPadding(dp(16), dp(4), dp(16), 0)
                for (level in listOf(80, 85, 90, 100)) {
                    val sel = prefs.chargeTarget == level
                    addView(TextView(context).apply {
                        text = "$level%"
                        gravity = Gravity.CENTER
                        textSize = 15f
                        typeface = if (sel) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
                        setTextColor(getColor(if (sel) R.color.on_bat_strong else R.color.text_primary))
                        background = ripple(rounded(if (sel) R.color.bat_strong else R.color.card_high, 100), 100)
                        layoutParams = lp(0, dp(44), 1f).apply { marginStart = dp(4); marginEnd = dp(4) }
                        isClickable = true
                        setOnClickListener { prefs.chargeTarget = level; Tiles.refreshAll(this@MainActivity); refresh() }
                    })
                }
            })
        })
        listCard {
            toggleRow("Low battery warning", "At ${prefs.lowLevel}%", prefs.lowWarnOn) { prefs.lowWarnOn = !prefs.lowWarnOn; refresh() }
            toggleRow("Overheat warning", "Above ${prefs.hotC} °C", prefs.hotWarnOn) { prefs.hotWarnOn = !prefs.hotWarnOn; refresh() }
        }

        section("Battery health")
        val week = prefs.fullMinutes.split(':').getOrNull(1)?.toLongOrNull() ?: 0
        pairRow(
            statTile("Health", b?.health ?: "Not reported", "As reported by your phone"),
            statTile("Charge cycles", b?.cycles?.toString() ?: "—", if (b?.cycles != null) "Reported by Android" else "Not reported on this phone"),
        )
        pairRow(
            statTile("At 100% this week", if (week >= 60) "${week / 60} h ${week % 60} m" else "$week min", "Plugged in while full · less is better"),
            statTile("Voltage", b?.voltageMv?.let { String.format(Locale.US, "%.2f V", it / 1000f) } ?: "—", "Current reading"),
        )
        if (!Perms.battery.granted(this)) {
            add(ui.tonalCard(R.color.danger_container, { Perms.battery.request(this) }).apply {
                addView(ui.text("Allow unrestricted battery", 15f, R.color.danger, true))
                addView(ui.small("Samsung may put Guardian to sleep, which stops the charge alarm and warnings. Tap to allow."))
            })
        }
        note("Android only reports some health details on some phones. Guardian shows what yours provides and never guesses.")
    }

    private fun statTile(label: String, value: String, sub: String) = ui.card().apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        addView(ui.text(label, 13f, R.color.text_secondary, true))
        addView(ui.text(value, 22f, R.color.text_primary, true).marginTop(dp(6)))
        addView(ui.small(sub))
    }
}
