package app.korvy.launcher.core

/**
 * The free Pro trial: [DAYS] days from when the user starts it. Kept on the phone only (no account, no server),
 * so it's honour-based: reinstalling resets it, which is fine for an open-source app.
 */
object Trial {
    const val DAYS = 7
    /** The trial can only be started this many days after installing (clearing app data can't restart it later). */
    const val START_WITHIN_DAYS = 30
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** True while the trial runs. [startedAt] 0 = never started. */
    fun active(startedAt: Long, now: Long): Boolean = startedAt > 0 && now >= startedAt && now < startedAt + DAYS * DAY_MS

    /** Whole days left, rounded up (6.2 days → 7); 0 when over or not started. */
    fun daysLeft(startedAt: Long, now: Long): Int =
        if (!active(startedAt, now)) 0 else ((startedAt + DAYS * DAY_MS - now + DAY_MS - 1) / DAY_MS).toInt()

    /** Not used yet, and within [START_WITHIN_DAYS] of [installedAt] (the install date survives clearing app data). */
    fun canStart(startedAt: Long, installedAt: Long, now: Long): Boolean =
        startedAt == 0L && now >= installedAt && now - installedAt < START_WITHIN_DAYS * DAY_MS
}
