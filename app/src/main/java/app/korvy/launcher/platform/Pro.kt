package app.korvy.launcher.platform

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * The one-time Pro unlock (Play build). Pro: the on-device AI model, teaching the assistant (notebook, phrases,
 * nicknames), the personality editor, natural voices, Whisper speech recognition, hide-and-lock, the background
 * slideshow and the stats overlay. Free: the launcher, search, folders, widgets, hiding apps and the assistant's
 * quick commands (apps, alarms, timers, calls, texts, weather...). A 7-day trial is available (see [ProAccess]).
 *
 * - Play build: $4.99 one-time purchase through Play Billing (the only Google code, and only in `play`).
 * - F-Droid / GitHub build, and any copy not installed from the Play Store: everything unlocked.
 * Nothing about the purchase is sent anywhere except Google Play's own purchase flow.
 */
interface ProStore {
    val unlocked: StateFlow<Boolean>
    /** The local price ("$4.99"), once Play has said; null until then or when buying isn't possible. */
    val price: StateFlow<String?>
    /** False in builds where there's nothing to buy (F-Droid, sideloaded). */
    val canBuy: Boolean
    /** Checks purchases again (also restores a purchase on a new phone). Cheap; safe to call often. */
    fun refresh()
    fun buy(activity: Activity)
}


/**
 * Pro as the app sees it: bought, or within the free trial. Wraps the store ([ProStore]) so every existing
 * "is Pro unlocked?" check also honours the trial. The trial start time is kept on the phone only.
 */
class ProAccess(
    private val store: ProStore, context: android.content.Context, scope: kotlinx.coroutines.CoroutineScope,
    /** Developer options → "Test Pro locks": behave as if nothing was bought. */
    private val testLocked: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false),
) : ProStore by store {
    private val prefs = context.getSharedPreferences("pro_trial", android.content.Context.MODE_PRIVATE)
    private val trialStart = kotlinx.coroutines.flow.MutableStateFlow(prefs.getLong("started", 0L))
    /** When Korvy was first installed on this phone; kept by Android even when the app's data is cleared. */
    private val installedAt = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime }.getOrDefault(0L)
    /** Ticks once a minute so the trial ends on time without a restart. */
    private val clock = kotlinx.coroutines.flow.flow { while (true) { emit(System.currentTimeMillis()); kotlinx.coroutines.delay(60_000) } }

    /** True if bought (or the build has nothing to buy). */
    val purchased: StateFlow<Boolean> = kotlinx.coroutines.flow.combine(store.unlocked, testLocked) { b, t -> b && !t }
        .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, store.unlocked.value && !testLocked.value)

    override val unlocked: StateFlow<Boolean> =
        kotlinx.coroutines.flow.combine(purchased, trialStart, clock) { bought, start, _ -> bought || app.korvy.launcher.core.Trial.active(start, System.currentTimeMillis()) } // the clock only re-checks; read the time now (its value can be a minute old)
            .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly,
                purchased.value || app.korvy.launcher.core.Trial.active(trialStart.value, System.currentTimeMillis()))

    fun canStartTrial() = (store.canBuy || testLocked.value) && app.korvy.launcher.core.Trial.canStart(trialStart.value, installedAt, System.currentTimeMillis())

    /** Developer options: forget the trial so it can be tried again. */
    fun resetTrial() { prefs.edit().remove("started").apply(); trialStart.value = 0L }
    fun trialDaysLeft() = app.korvy.launcher.core.Trial.daysLeft(trialStart.value, System.currentTimeMillis())
    fun startTrial() {
        if (!canStartTrial()) return
        val now = System.currentTimeMillis()
        prefs.edit().putLong("started", now).apply(); trialStart.value = now
    }
}
