package com.hiddenoutlier.launcher

import android.app.Application
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    /** One-time Pro unlock (Play Billing in the play build; always unlocked in foss). Not touched at startup. */
    val pro by lazy { com.hiddenoutlier.launcher.platform.createProStore(this) }
    val widgets by lazy { com.hiddenoutlier.launcher.data.Widgets(this) }
    val voices by lazy { com.hiddenoutlier.launcher.assistant.VoiceStore(this) }
    val personality by lazy { com.hiddenoutlier.launcher.assistant.Personality(this) }
    /** Starts or stops wake-word listening to match the settings (Pro, model downloaded). Call while on screen. */
    fun syncWakeWord() {
        scope.launch {
            val s = settings.settings.first()
            val on = s.wakeWord && pro.unlocked.value && com.hiddenoutlier.launcher.assistant.NeuralVoiceCatalog.wakeWord.id in voices.installed.value
            com.hiddenoutlier.launcher.assistant.WakeWordService.sync(this@LauncherApp, on, s.wakePhrase,
                floatArrayOf(0.35f, 0.25f, 0.15f)[s.wakeSensitivity.coerceIn(0, 2)], s.wakeScreenOnly)
        }
    }

    val assistant by lazy {
        com.hiddenoutlier.launcher.assistant.AssistantController(this, scope, settingsState, apps, privacy, usage, models, personality)
    }
}
