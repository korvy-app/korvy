package app.korvy.launcher

import app.korvy.launcher.data.SearchEngine
import app.korvy.launcher.data.Settings
import app.korvy.launcher.platform.OnlineSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionsTest {
    @Test fun turningOnOnlySuggestionsLeavesEverythingElseOff() {
        // What SettingsStore.enableSuggestionsOnly writes when Online features were off.
        val s = Settings(online = true, sourcesOff = OnlineSource.entries.filter { !it.optIn }.map { it.name }.toSet(),
            sourcesOn = setOf(OnlineSource.SUGGESTIONS.name))
        assertTrue(s.allows(OnlineSource.SUGGESTIONS))
        OnlineSource.entries.filter { it != OnlineSource.SUGGESTIONS }.forEach { assertFalse(it.name, s.allows(it)) }
    }

    @Test fun suggestionsComeFromTheChosenEngine() {
        assertTrue(SearchEngine.BRAVE.suggestions.startsWith("https://search.brave.com/"))
        assertTrue(SearchEngine.STARTPAGE.suggestions.startsWith("https://www.startpage.com/"))
        assertEquals(SearchEngine.DUCKDUCKGO.suggestions, SearchEngine.MOJEEK.suggestions) // Mojeek has none
        assertEquals("DuckDuckGo", SearchEngine.MOJEEK.suggestionsBy)
        // Every suggestion address is on the source's host list (Net refuses anything else).
        SearchEngine.entries.forEach { e ->
            val host = java.net.URL(e.suggestions).host
            assertTrue(host, OnlineSource.SUGGESTIONS.hosts.any { host == it || host.endsWith(".$it") })
        }
    }
}
