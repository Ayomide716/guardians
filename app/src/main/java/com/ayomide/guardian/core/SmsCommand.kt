package com.ayomide.guardian.core

import java.security.MessageDigest

/** "Find my phone" text commands. */
object SmsCommand {

    const val MIN_SECRET = 4

    fun matches(body: String?, secret: String?): Boolean {
        val s = secret?.trim().orEmpty()
        if (s.length < MIN_SECRET) return false
        return body?.trim()?.equals(s, ignoreCase = true) == true
    }

    /** Digits only, Nigerian +234/00234/234… turned into the local 0… form. */
    fun normalize(number: String?): String {
        val d = number.orEmpty().filter { it.isDigit() }
        return when {
            d.startsWith("00234") -> "0" + d.substring(5).trimStart('0')
            d.startsWith("234") && d.length == 13 -> "0" + d.substring(3)
            else -> d
        }
    }

    fun isTrusted(sender: String?, trusted: List<String>): Boolean {
        val list = trusted.map { normalize(it) }.filter { it.isNotEmpty() }
        if (list.isEmpty()) return true
        val n = normalize(sender)
        return n.isNotEmpty() && n in list
    }

    fun reply(batteryPct: Int?, lat: Double?, lng: Double?, accuracyM: Float?): String {
        val sb = StringBuilder("Guardian: your phone is ringing.")
        if (batteryPct != null) sb.append(" Battery ").append(batteryPct).append("%.")
        if (lat != null && lng != null) {
            sb.append(" Location: https://maps.google.com/?q=")
                .append(String.format(java.util.Locale.US, "%.5f,%.5f", lat, lng))
            if (accuracyM != null) sb.append(" (±").append(accuracyM.toInt()).append(" m)")
        } else {
            sb.append(" Location unavailable.")
        }
        return sb.toString()
    }
}

object Pin {
    fun isValid(pin: String) = pin.length in 4..8 && pin.all { it.isDigit() }

    fun hash(pin: String, salt: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + ":" + pin).toByteArray())
            .joinToString("") { "%02x".format(it) }
}
