package com.hiddenoutlier.launcher

import android.app.Application
import kotlinx.coroutines.flow.stateIn
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

    /** App-wide scope and settings, shared by the home screen and the power-button assistant panel. */
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    val settingsState by lazy {
        settings.settings.stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, com.hiddenoutlier.launcher.data.Settings(tutorialDone = true))
    }
    val models by lazy { com.hiddenoutlier.launcher.assistant.ModelStore(this) }
    val voices by lazy { com.hiddenoutlier.launcher.assistant.VoiceStore(this) }
    val personality by lazy { com.hiddenoutlier.launcher.assistant.Personality(this) }
    val assistant by lazy {
        com.hiddenoutlier.launcher.assistant.AssistantController(this, scope, settingsState, apps, privacy, usage, models, personality)
    }
}
