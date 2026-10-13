package com.ayomide.guardian.screens

import android.text.InputType
import com.ayomide.guardian.core.Pin
import com.ayomide.guardian.data.Prefs
import com.ayomide.guardian.ui.Ui

/** Set or change the stop PIN. Changing requires the current PIN. */
object PinDialog {
    fun show(ui: Ui, onDone: () -> Unit = {}) {
        val prefs = Prefs(ui.ctx)
        val type = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val current = ui.input("Current PIN", "", type)
        val first = ui.input("New PIN (4–8 digits)", "", type)
        val second = ui.input("Repeat new PIN", "", type)
        val form = ui.column().apply {
            addView(ui.small("This PIN stops the alarm. Pick one a thief can't guess, and don't forget it."))
            if (prefs.hasPin) addView(current)
            addView(first); addView(second)
        }
        ui.form(if (prefs.hasPin) "Change stop PIN" else "Set a stop PIN", form) {
            val a = first.text.toString()
            when {
                prefs.hasPin && !prefs.checkPin(current.text.toString()) -> "Current PIN is wrong."
                !Pin.isValid(a) -> "Use 4 to 8 digits."
                a != second.text.toString() -> "The two PINs don't match."
                else -> {
                    prefs.setPin(a); onDone(); null
                }
            }
        }
    }
}
