package com.hiddenoutlier.launcher.platform

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hiddenoutlier.launcher.LauncherApp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps an activity's rotation in line with Settings → Look and feel → Screen rotation.
 * "Follow phone" respects the phone's own auto-rotate switch; landscape allows either way round.
 */
object Orientation {
    fun of(setting: Int) = when (setting) {
        1 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else -> ActivityInfo.SCREEN_ORIENTATION_USER
    }

    fun follow(activity: androidx.activity.ComponentActivity) {
        val app = activity.application as LauncherApp
        activity.requestedOrientation = of(app.settingsState.value.orientation)
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.CREATED) {
                app.settings.settings.map { it.orientation }.distinctUntilChanged().collect {
                    val want = of(it)
                    if (activity.requestedOrientation != want) activity.requestedOrientation = want
                }
            }
        }
    }
}
