package app.korvy.launcher

import android.app.Application
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import app.korvy.launcher.data.AppRepository
import app.korvy.launcher.data.Backgrounds
import app.korvy.launcher.data.HiddenStore
import app.korvy.launcher.data.SettingsStore
import app.korvy.launcher.data.UsageStore
import app.korvy.launcher.assistant.AssistantController
import app.korvy.launcher.assistant.ModelStore
import app.korvy.launcher.assistant.NeuralVoiceCatalog
import app.korvy.launcher.assistant.Personality
import app.korvy.launcher.assistant.VoiceStore
import app.korvy.launcher.assistant.WakeWordService
import app.korvy.launcher.data.Settings
import app.korvy.launcher.data.Widgets

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
        settings.settings.stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, Settings(tutorialDone = true))
    }
    val models by lazy { ModelStore(this) }
    /** One-time Pro unlock (Play Billing in the play build; always unlocked in foss). Not touched at startup. */
    val pro by lazy { app.korvy.launcher.platform.createProStore(this) }
    val widgets by lazy { Widgets(this) }
    val voices by lazy { VoiceStore(this) }
    val personality by lazy { Personality(this) }
    /** Starts or stops wake-word listening to match the settings (Pro, model downloaded). Call while on screen. */
    fun syncWakeWord() {
        scope.launch {
            val s = settings.settings.first()
            // Behind a feature flag until it works reliably (Developer options).
            val on = app.korvy.launcher.core.Feature.WAKE_WORD.on(s.flagsOn, s.flagsOff) && s.wakeWord && pro.unlocked.value && NeuralVoiceCatalog.wakeWord.id in voices.installed.value
            WakeWordService.sync(this@LauncherApp, on, s.wakePhrase,
                floatArrayOf(0.35f, 0.25f, 0.15f)[s.wakeSensitivity.coerceIn(0, 2)], s.wakeScreenOnly)
        }
    }

    val assistant by lazy {
        AssistantController(this, scope, settingsState, apps, privacy, usage, models, personality)
    }
}
