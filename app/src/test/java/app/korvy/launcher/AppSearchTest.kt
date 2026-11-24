package app.korvy.launcher

import app.korvy.launcher.core.AppSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSearchTest {
    private val apps = listOf("Google Maps", "Messages", "Maps Offline", "Camera", "Calculator", "Café Finder", "Piano Notes", "Chrome", "Spotify")

    @Test fun prefixBeatsWordStartBeatsContains() {
        assertEquals(listOf("Maps Offline", "Google Maps"), AppSearch.rank("maps", apps) { it }.take(2))
    }

    @Test fun initialsFindMultiWordNames() {
        assertEquals("Google Maps", AppSearch.rank("gm", apps) { it }.first())
        assertEquals("Piano Notes", AppSearch.rank("pn", apps) { it }.first())
    }

    @Test fun accentsAndCaseDontMatter() {
        assertEquals("Café Finder", AppSearch.rank("CAFE", apps) { it }.first())
    }

    @Test fun lettersInOrderAsLastResort() {
        assertEquals(listOf("Calculator"), AppSearch.rank("clcltr", apps) { it })
    }

    @Test fun noMatchGivesNothing() {
        assertTrue(AppSearch.rank("zzz", apps) { it }.isEmpty())
    }

    @Test fun exactNameOnlyForRevealingHiddenApps() {
        assertTrue(AppSearch.isExact("piano notes", "Piano Notes"))
        assertFalse(AppSearch.isExact("piano", "Piano Notes"))
        assertFalse(AppSearch.isExact("  ", "Piano Notes"))
    }

    @Test fun typosStillFindTheApp() {
        assertEquals("Chrome", AppSearch.rank("chorme", apps) { it }.first())        // swapped letters
        assertEquals("Calculator", AppSearch.rank("calcualtor", apps) { it }.first()) // swap in a long name
        assertEquals("Spotify", AppSearch.rank("spotfy", apps) { it }.first())       // missing letter
        assertEquals("Spotify", AppSearch.rank("spptify", apps) { it }.first())      // wrong letter
        assertEquals("Messages", AppSearch.rank("mesages", apps) { it }.first())     // partly typed with a typo
        assertEquals("Piano Notes", AppSearch.rank("notse", apps) { it }.first())    // typo in a later word
    }

    @Test fun shortQueriesDontMatchByTypo() {
        assertTrue(AppSearch.rank("xam", apps) { it }.isEmpty()) // 3 letters: no typo allowance
    }

    @Test fun realMatchesOutrankTypoMatches() {
        val ranked = AppSearch.rank("camer", listOf("Camera", "Gamer Hub")) { it }
        assertEquals("Camera", ranked.first())
    }

    @Test fun editDistanceCountsSwapsAsOne() {
        assertEquals(1, AppSearch.editDistance("chorme", "chrome"))
        assertEquals(2, AppSearch.editDistance("kitten", "sitting".take(6)))
        assertEquals(0, AppSearch.editDistance("same", "same"))
    }
}
