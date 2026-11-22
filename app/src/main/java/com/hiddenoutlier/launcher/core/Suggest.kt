package com.hiddenoutlier.launcher.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow

/** One app launch recorded on the device (never leaves it). [key] identifies the app (see AppEntry.key). */
data class Launch(val key: String, val epochMillis: Long)

/**
 * Suggests apps for "now" from the user's own launch history. Plain scoring, no language model:
 * a launch counts more when it happened near the same time of day, on the same kind of day
 * (same weekday > weekday/weekend match > other), and recently (older launches fade out).
 */
object Suggest {
    /** Spread of the time-of-day bell curve, in hours. */
    const val HOUR_SIGMA = 1.5
    /** A launch's weight halves every this many days. */
    const val HALF_LIFE_DAYS = 21.0
    /** Launches older than this are ignored (and pruned from storage). */
    const val MAX_AGE_DAYS = 120L

    fun score(launch: ZonedDateTime, now: ZonedDateTime): Double {
        val ageDays = (now.toEpochSecond() - launch.toEpochSecond()) / 86_400.0
        if (ageDays < 0 || ageDays > MAX_AGE_DAYS) return 0.0
        val minutesNow = now.hour * 60 + now.minute
        val minutesThen = launch.hour * 60 + launch.minute
        val diff = abs(minutesNow - minutesThen)
        val hours = min(diff, 1440 - diff) / 60.0 // circular: 23:30 is close to 00:30
        val timeWeight = exp(-(hours * hours) / (2 * HOUR_SIGMA * HOUR_SIGMA))
        val dayWeight = when {
            launch.dayOfWeek == now.dayOfWeek -> 1.0
            isWeekend(launch.dayOfWeek) == isWeekend(now.dayOfWeek) -> 0.6
            else -> 0.25
        }
        val decay = 0.5.pow(ageDays / HALF_LIFE_DAYS)
        // A small time-independent share so frequently used apps still rank when history is thin.
        return decay * (timeWeight * dayWeight + 0.1)
    }

    fun rank(
        launches: List<Launch>,
        now: ZonedDateTime,
        exclude: Set<String> = emptySet(),
        limit: Int = 4,
        zone: ZoneId = now.zone,
    ): List<String> {
        val totals = HashMap<String, Double>()
        for (l in launches) {
            if (l.key in exclude) continue
            val s = score(ZonedDateTime.ofInstant(Instant.ofEpochMilli(l.epochMillis), zone), now)
            if (s > 0) totals[l.key] = (totals[l.key] ?: 0.0) + s
        }
        return totals.entries.sortedByDescending { it.value }.take(limit).map { it.key }
    }

    private fun isWeekend(d: DayOfWeek) = d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY
}
