package app.korvy.launcher.platform

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** F-Droid / GitHub build: everything is unlocked and there is no store code at all. */
fun createProStore(@Suppress("UNUSED_PARAMETER") context: Context): ProStore = object : ProStore {
    override val unlocked: StateFlow<Boolean> = MutableStateFlow(true)
    override val price: StateFlow<String?> = MutableStateFlow(null)
    override val canBuy = false
    override fun refresh() {}
    override fun buy(activity: Activity) {}
}
