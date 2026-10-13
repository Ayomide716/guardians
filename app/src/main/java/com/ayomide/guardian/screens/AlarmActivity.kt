package com.ayomide.guardian.screens

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.ayomide.guardian.R
import com.ayomide.guardian.data.EventLog
import com.ayomide.guardian.data.Prefs
import com.ayomide.guardian.system.GuardService
import com.ayomide.guardian.ui.Ui
import com.ayomide.guardian.ui.dp
import com.ayomide.guardian.ui.ripple
import com.ayomide.guardian.ui.rounded

/** PIN pad that stops the alarm or disarms. Shows over the lock screen. */
class AlarmActivity : Activity() {

    private lateinit var prefs: Prefs
    private val entered = StringBuilder()
    private val dots = ArrayList<View>()
    private lateinit var message: TextView
    private var wrong = 0

    private val alarming get() = GuardService.phase == GuardService.Phase.TRIGGERED || GuardService.phase == GuardService.Phase.SOUNDING || GuardService.ringing

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        prefs = Prefs(this)
        val ui = Ui(this)
        val fg = getColor(R.color.alarm_fg)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(getColor(if (alarming) R.color.alarm_bg else R.color.bg))
            setPadding(dp(28), dp(40), dp(28), dp(24))
        }
        val onAlarm = alarming
        val textColor = if (onAlarm) fg else getColor(R.color.text_primary)

        root.addView(ImageView(this).apply {
            setImageResource(if (onAlarm) R.drawable.ic_volume else R.drawable.ic_lock)
            imageTintList = ColorStateList.valueOf(getColor(if (onAlarm) R.color.alarm_bg else R.color.on_accent))
            background = rounded(if (onAlarm) R.color.alarm_fg else R.color.accent, 100)
            setPadding(dp(22), dp(22), dp(22), dp(22))
            layoutParams = LinearLayout.LayoutParams(dp(88), dp(88))
        })
        root.addView(TextView(this).apply {
            text = if (onAlarm) "ALARM" else "Disarm"
            textSize = 36f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(textColor)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        })
        message = TextView(this).apply {
            text = when {
                GuardService.ringing && GuardService.phase == GuardService.Phase.IDLE -> "Find my phone is ringing.\nEnter your PIN to stop it."
                onAlarm -> "This phone was taken without the PIN.\nEnter the PIN to stop the alarm."
                else -> "Enter your PIN to turn off the ${GuardService.modeName(prefs.alarmMode).lowercase()}."
            }
            textSize = 16f
            setTextColor(textColor)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(20))
        }
        root.addView(message)

        val dotRow = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(24)) }
        repeat(8) {
            val d = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginStart = dp(7); marginEnd = dp(7) }
                visibility = View.GONE
            }
            dots += d; dotRow.addView(d)
        }
        root.addView(dotRow)

        val keyBg = if (onAlarm) R.color.alarm_key else R.color.card
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "OK", "0", "⌫")
        for (r in 0 until 4) {
            root.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                for (c in 0 until 3) {
                    val k = keys[r * 3 + c]
                    addView(TextView(context).apply {
                        text = k
                        textSize = 26f
                        gravity = Gravity.CENTER
                        setTextColor(textColor)
                        layoutParams = LinearLayout.LayoutParams(0, dp(68), 1f).apply { setMargins(dp(7), dp(7), dp(7), dp(7)) }
                        if (k.isNotEmpty()) {
                            background = ripple(if (k == "⌫") null else rounded(keyBg, 100), 100)
                            contentDescription = when (k) { "⌫" -> "Delete"; "OK" -> "Confirm PIN"; else -> k }
                            if (k == "OK") textSize = 18f
                            isClickable = true
                            setOnClickListener { press(k) }
                        }
                    })
                }
            })
        }
        if (!onAlarm) root.addView(ui.flatButton("Cancel") { finish() }.apply { setTextColor(textColor) })

        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(dp(28), dp(40) + b.top, dp(28), dp(24) + b.bottom)
            }
            insets
        }
        setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        updateDots()
    }

    private fun press(k: String) {
        val confirm = k == "OK"
        if (k == "⌫") {
            if (entered.isNotEmpty()) entered.setLength(entered.length - 1)
        } else if (!confirm && entered.length < 8) {
            entered.append(k)
        }
        updateDots()
        if (entered.length >= 4 && prefs.checkPin(entered.toString())) {
            GuardService.start(this, GuardService.ACTION_STOP_RING)
            if (GuardService.phase != GuardService.Phase.IDLE || prefs.armed) GuardService.start(this, GuardService.ACTION_DISARM)
            finish()
        } else if (confirm || entered.length == 8) {
            if (entered.isEmpty()) return
            wrong++
            EventLog.add(this, "Wrong PIN entered")
            message.text = "Wrong PIN ($wrong). Try again."
            entered.setLength(0)
            updateDots()
        }
    }

    private fun updateDots() {
        val fill = getColor(if (alarming) R.color.alarm_fg else R.color.accent)
        for ((i, d) in dots.withIndex()) {
            d.visibility = if (i < maxOf(4, entered.length)) View.VISIBLE else View.GONE
            d.background = if (i < entered.length) android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(fill)
            } else android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL; setStroke(dp(2), fill)
            }
        }
    }

    @Deprecated("Back is disabled while the alarm is going off")
    override fun onBackPressed() {
        if (!alarming) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}
