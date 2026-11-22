package com.hiddenoutlier.launcher

import android.app.Application
import com.hiddenoutlier.launcher.data.AppRepository
import com.hiddenoutlier.launcher.data.Backgrounds
import com.hiddenoutlier.launcher.data.HiddenStore
import com.hiddenoutlier.launcher.data.SettingsStore
import com.hiddenoutlier.launcher.data.UsageStore

/** Holds the app-wide stores. No analytics, crash reporting or network code is initialised here or anywhere. */
class LauncherApp : Application() {
    val apps by lazy { AppRepository(this) }
    val usage by lazy { UsageStore(this) }
    val privacy by lazy { HiddenStore(this) }
    val settings by lazy { SettingsStore(this) }
    val backgrounds by lazy { Backgrounds(this) }
}
