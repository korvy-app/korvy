package app.korvy.launcher.platform

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

/**
 * The one-time Pro unlock. Pro adds the extras: natural voices, Whisper speech recognition, the wake word,
 * the background slideshow and the stats overlay. The launcher itself, privacy features, search and the
 * assistant are free.
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
