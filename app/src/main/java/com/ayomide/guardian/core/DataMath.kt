package com.ayomide.guardian.core

import java.time.LocalDate
import java.util.Locale

object DataMath {

    const val MB = 1024L * 1024L

    /** First day of the current bundle period for a monthly renewal day (1–31). */
    fun periodStart(renewalDay: Int, today: LocalDate): LocalDate {
        fun dayIn(month: LocalDate) = month.withDayOfMonth(renewalDay.coerceIn(1, month.lengthOfMonth()))
        val thisMonth = dayIn(today.withDayOfMonth(1))
        return if (!today.isBefore(thisMonth)) thisMonth else dayIn(today.withDayOfMonth(1).minusMonths(1))
    }

    /** 0 = fine, 1 = past the warning percentage, 2 = at or over the limit. */
    fun level(usedBytes: Long, limitBytes: Long, warnPct: Int): Int = when {
        limitBytes <= 0 -> 0
        usedBytes >= limitBytes -> 2
        usedBytes * 100 >= limitBytes * warnPct.coerceIn(1, 100) -> 1
        else -> 0
    }

    fun format(bytes: Long): String = when {
        bytes >= 1024 * MB -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * MB))
        bytes >= MB -> "${bytes / MB} MB"
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    fun percent(used: Long, limit: Long): Int =
        if (limit <= 0) 0 else ((used * 100) / limit).toInt().coerceIn(0, 100)
}
