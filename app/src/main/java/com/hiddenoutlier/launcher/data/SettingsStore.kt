package com.hiddenoutlier.launcher.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")

/** When the assistant speaks its replies. */
enum class SpeakMode(val title: String) { ALWAYS("Always"), AFTER_VOICE("When I speak to it"), OFF("Never") }

/** Web search engines. All open the results in the user's browser; nothing passes through this app. */
enum class SearchEngine(val title: String, val url: String) {
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q="),
    BRAVE("Brave Search", "https://search.brave.com/search?q="),
    STARTPAGE("Startpage", "https://www.startpage.com/do/search?q="),
    MOJEEK("Mojeek", "https://www.mojeek.com/search?q="),
}

data class Settings(
    val tutorialDone: Boolean = false,
    /** null = use the device-tier default. */
    val animations: Boolean? = null,
    val engine: SearchEngine = SearchEngine.DUCKDUCKGO,
    val suggestions: Boolean = true,
    val revealHiddenByName: Boolean = true,
    val slideshow: Boolean = false,
    val slideshowMinutes: Int = 15,
    /** The user tapped "Not now" on the home-screen card that offers to become the default home app. */
    val homePromptDismissed: Boolean = false,
    // Assistant (off by default)
    val assistant: Boolean = false,
    val modelId: String? = null,
    // Online features: master switch off by default; each source has its own switch (on once the master is on).
    val online: Boolean = false,
    val sourcesOff: Set<String> = emptySet(),
    /** Opt-in sources (OnlineSource.optIn) the user turned on. */
    val sourcesOn: Set<String> = emptySet(),
    val homePlace: String = "",
    val fahrenheit: Boolean = java.util.Locale.getDefault().country in setOf("US", "LR", "MM"),
    val modelsOnMobileData: Boolean = false,
    // Voice
    val speak: SpeakMode = SpeakMode.AFTER_VOICE,
    val ttsEngine: String? = null,
    val ttsVoice: String? = null,
    val ttsRate: Float = 1f,
    val ttsPitch: Float = 1f,
    /** Start listening right away when the assistant is opened with the power button. */
    val listenOnAssist: Boolean = true,
    /** Show matching contacts in search (only when the contacts permission is granted). */
    val contactsInSearch: Boolean = true,
) {
    fun allows(source: com.hiddenoutlier.launcher.platform.OnlineSource) =
        online && if (source.optIn) source.name in sourcesOn else source.name !in sourcesOff
}


class SettingsStore(private val context: Context) {
    private object K {
        val tutorialDone = booleanPreferencesKey("tutorial_done")
        val animations = stringPreferencesKey("animations") // "on" / "off" / absent = default
        val engine = stringPreferencesKey("engine")
        val suggestions = booleanPreferencesKey("suggestions")
        val reveal = booleanPreferencesKey("reveal_hidden_by_name")
        val slideshow = booleanPreferencesKey("slideshow")
        val slideshowMinutes = intPreferencesKey("slideshow_minutes")
        val homePromptDismissed = booleanPreferencesKey("home_prompt_dismissed")
        val homeGrid = stringPreferencesKey("home_grid")
        val assistant = booleanPreferencesKey("assistant")
        val modelId = stringPreferencesKey("model_id")
        val online = booleanPreferencesKey("online")
        val sourcesOff = androidx.datastore.preferences.core.stringSetPreferencesKey("sources_off")
        val sourcesOn = androidx.datastore.preferences.core.stringSetPreferencesKey("sources_on")
        val homePlace = stringPreferencesKey("home_place")
        val fahrenheit = booleanPreferencesKey("fahrenheit")
        val modelsOnMobileData = booleanPreferencesKey("models_on_mobile_data")
        val speak = stringPreferencesKey("speak")
        val ttsEngine = stringPreferencesKey("tts_engine")
        val ttsVoice = stringPreferencesKey("tts_voice")
        val ttsRate = androidx.datastore.preferences.core.floatPreferencesKey("tts_rate")
        val ttsPitch = androidx.datastore.preferences.core.floatPreferencesKey("tts_pitch")
        val listenOnAssist = booleanPreferencesKey("listen_on_assist")
        val contactsInSearch = booleanPreferencesKey("contacts_in_search")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            tutorialDone = p[K.tutorialDone] ?: false,
            animations = when (p[K.animations]) { "on" -> true; "off" -> false; else -> null },
            engine = p[K.engine]?.let { runCatching { SearchEngine.valueOf(it) }.getOrNull() } ?: SearchEngine.DUCKDUCKGO,
            suggestions = p[K.suggestions] ?: true,
            revealHiddenByName = p[K.reveal] ?: true,
            slideshow = p[K.slideshow] ?: false,
            slideshowMinutes = p[K.slideshowMinutes] ?: 15,
            homePromptDismissed = p[K.homePromptDismissed] ?: false,
            assistant = p[K.assistant] ?: false,
            modelId = p[K.modelId],
            online = p[K.online] ?: false,
            sourcesOff = p[K.sourcesOff] ?: emptySet(),
            sourcesOn = p[K.sourcesOn] ?: emptySet(),
            homePlace = p[K.homePlace] ?: "",
            fahrenheit = p[K.fahrenheit] ?: Settings().fahrenheit,
            modelsOnMobileData = p[K.modelsOnMobileData] ?: false,
            speak = p[K.speak]?.let { runCatching { SpeakMode.valueOf(it) }.getOrNull() } ?: SpeakMode.AFTER_VOICE,
            ttsEngine = p[K.ttsEngine],
            ttsVoice = p[K.ttsVoice],
            ttsRate = p[K.ttsRate] ?: 1f,
            ttsPitch = p[K.ttsPitch] ?: 1f,
            listenOnAssist = p[K.listenOnAssist] ?: true,
            contactsInSearch = p[K.contactsInSearch] ?: true,
        )
    }

    /** Apps placed on the home screen. Hidden apps are removed from it, so no hidden app key is stored here. */
    val homeGrid: Flow<com.hiddenoutlier.launcher.core.HomeGrid> = context.dataStore.data.map { com.hiddenoutlier.launcher.core.HomeGrid.parse(it[K.homeGrid]) }
    suspend fun setHomeGrid(g: com.hiddenoutlier.launcher.core.HomeGrid) = set { it[K.homeGrid] = g.serialize() }

    private suspend fun set(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }

    suspend fun setTutorialDone(v: Boolean) = set { it[K.tutorialDone] = v }
    suspend fun setAnimations(v: Boolean) = set { it[K.animations] = if (v) "on" else "off" }
    suspend fun setEngine(v: SearchEngine) = set { it[K.engine] = v.name }
    suspend fun setSuggestions(v: Boolean) = set { it[K.suggestions] = v }
    suspend fun setRevealHiddenByName(v: Boolean) = set { it[K.reveal] = v }
    suspend fun setSlideshow(v: Boolean) = set { it[K.slideshow] = v }
    suspend fun setSlideshowMinutes(v: Int) = set { it[K.slideshowMinutes] = v }
    suspend fun setHomePromptDismissed(v: Boolean) = set { it[K.homePromptDismissed] = v }
    suspend fun setAssistant(v: Boolean) = set { it[K.assistant] = v }
    suspend fun setModelId(v: String?) = set { if (v == null) it.remove(K.modelId) else it[K.modelId] = v }
    suspend fun setOnline(v: Boolean) = set { it[K.online] = v }
    suspend fun setSource(source: com.hiddenoutlier.launcher.platform.OnlineSource, on: Boolean) = set {
        if (source.optIn) it[K.sourcesOn] = (it[K.sourcesOn] ?: emptySet()).let { s -> if (on) s + source.name else s - source.name }
        else it[K.sourcesOff] = (it[K.sourcesOff] ?: emptySet()).let { s -> if (on) s - source.name else s + source.name }
    }
    suspend fun setHomePlace(v: String) = set { it[K.homePlace] = v.trim() }
    suspend fun setFahrenheit(v: Boolean) = set { it[K.fahrenheit] = v }
    suspend fun setModelsOnMobileData(v: Boolean) = set { it[K.modelsOnMobileData] = v }
    suspend fun setSpeak(v: SpeakMode) = set { it[K.speak] = v.name }
    suspend fun setTtsEngine(v: String?) = set { if (v == null) it.remove(K.ttsEngine) else it[K.ttsEngine] = v; it.remove(K.ttsVoice) }
    suspend fun setTtsVoice(v: String?) = set { if (v == null) it.remove(K.ttsVoice) else it[K.ttsVoice] = v }
    suspend fun setTtsRate(v: Float) = set { it[K.ttsRate] = v }
    suspend fun setTtsPitch(v: Float) = set { it[K.ttsPitch] = v }
    suspend fun setListenOnAssist(v: Boolean) = set { it[K.listenOnAssist] = v }
    suspend fun setContactsInSearch(v: Boolean) = set { it[K.contactsInSearch] = v }
}
