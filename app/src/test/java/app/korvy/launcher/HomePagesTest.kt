package app.korvy.launcher

import app.korvy.launcher.core.HomeGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomePagesTest {
    private val small = HomeGrid(cols = 2, rows = 2) // 4 cells a page keeps the tests readable

    @Test fun addingToAFullPageOpensANewOne() {
        var g = small
        listOf("a", "b", "c", "d", "e").forEach { g = g.add(it) }
        assertEquals(2, g.pages)
        assertEquals(4, g.cellOf("e")) // first cell of page 2
        assertEquals(1, g.pageOf(4))
    }

    @Test fun droppingOnTheExtraPageCreatesIt() {
        val g = small.place("a", 0).place("b", 1)
        val moved = g.place("b", 4 + 2, from = 1) // page 2, cell 2: shown only while dragging
        assertEquals(2, moved.pages)
        assertEquals(6, moved.cellOf("b"))
    }

    @Test fun emptyPagesCloseAndLaterPagesMoveUp() {
        val g = HomeGrid(2, 2, mapOf(0 to "a", 9 to "c"), pages = 3) // page 2 (cells 4-7) empty
        val c = g.compact()
        assertEquals(2, c.pages)
        assertEquals(5, c.cellOf("c")) // page 3 cell 1 → page 2 cell 1
        assertEquals(HomeGrid(2, 2), HomeGrid(2, 2, pages = 4).compact()) // all empty → one page
    }

    @Test fun nearestFreeCellStaysOnTheSamePage() {
        // Page 1 has one free cell far away; page 2 is empty. The occupant moving out stays on page 1.
        val g = HomeGrid(2, 2, mapOf(0 to "a", 1 to "b", 2 to "c"), pages = 2)
        assertEquals(3, g.nearestEmpty(0))
        val full = HomeGrid(2, 2, mapOf(0 to "a", 1 to "b", 2 to "c", 3 to "d"), pages = 2)
        assertEquals(5, full.nearestEmpty(1)) // only page 2 has room: the same spot there
    }

    @Test fun pagesAreSavedAndOldLayoutsLoadAsOnePage() {
        val g = HomeGrid(4, 5, mapOf(0 to "a", 25 to "b"), pages = 2)
        assertEquals(g, HomeGrid.parse(g.serialize()))
        assertEquals(1, HomeGrid.parse("4x5|0=a|3=b").pages)
        assertEquals("4x5|0=a", HomeGrid(4, 5, mapOf(0 to "a")).serialize()) // unchanged format for one page
    }

    @Test fun pageLimit() {
        var g = HomeGrid(1, 1)
        repeat(HomeGrid.MAX_PAGES + 2) { g = g.add("app$it") }
        assertEquals(HomeGrid.MAX_PAGES, g.pages)
        assertNull(g.cellOf("app${HomeGrid.MAX_PAGES}"))
    }
}
