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
)

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
}
