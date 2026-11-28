package app.korvy.launcher

import app.korvy.launcher.core.AppSearch
import app.korvy.launcher.core.SmartOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class SmartOrderTest {
    private val apps = listOf("Calculator", "Calendar", "Camera", "Chrome", "Clock", "Maps", "Spotify", "Settings")
    private fun search(q: String, likely: List<String>) =
        SmartOrder.search(q, AppSearch.rank(q, apps) { it }, likely, { it }, { it })

    @Test fun emptySearchPutsLikelyAppsFirst() {
        assertEquals(listOf("Spotify", "Camera", "Calculator", "Calendar", "Chrome", "Clock", "Maps", "Settings"),
            SmartOrder.all(apps, likely = listOf("Spotify", "Camera"), key = { it }, label = { it }))
        assertEquals(apps.sorted(), SmartOrder.all(apps.shuffled(), emptyList(), { it }, { it })) // no history: A–Z
    }

    @Test fun typingKeepsTheBestMatchButBreaksTiesByLikelihood() {
        // "c": all start with C (same band); the camera the user opens every morning comes first.
        assertEquals("Camera", search("c", listOf("Camera", "Spotify")).first())
        // An exact name still beats a likely app that only starts with the letters.
        assertEquals("Clock", search("clock", listOf("Calculator")).first())
        // "s": Spotify and Settings both start with S; likely Settings first, but Maps (contains s) stays after both.
        val s = search("s", listOf("Settings", "Maps"))
        assertEquals(listOf("Settings", "Spotify"), s.take(2))
        assertEquals(search("s", emptyList()).toSet(), s.toSet())
    }
}
