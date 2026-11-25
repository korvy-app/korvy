package app.korvy.launcher

import app.korvy.launcher.core.HomeGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeGridTest {
    private val g = HomeGrid(cols = 4, rows = 5)

    @Test fun addFillsFirstEmptyCellAndNeverDuplicates() {
        val a = g.add("a").add("b").add("a")
        assertEquals(mapOf(0 to "a", 1 to "b"), a.cells)
    }

    @Test fun movingWithinTheGridSwapsWithTheOccupant() {
        val a = g.place("a", 0).place("b", 5)
        val moved = a.place("a", 5, from = 0)
        assertEquals(mapOf(5 to "a", 0 to "b"), moved.cells)
    }

    @Test fun droppingFromTheAppListPushesTheOccupantToTheNearestEmptyCell() {
        val a = g.place("b", 5)
        val dropped = a.place("new", 5)
        assertEquals("new", dropped.cells[5])
        assertEquals(1, dropped.cellOf("b")) // directly above (distance 1)
    }

    @Test fun droppingAnAppAlreadyOnTheGridMovesIt() {
        val a = g.place("a", 0).place("a", 7)
        assertEquals(mapOf(7 to "a"), a.cells)
    }

    @Test fun fullGridRefusesANewAppOnAnOccupiedCell() {
        var full = g
        repeat(g.size) { full = full.place("app$it", it) }
        assertEquals(full, full.place("extra", 3))
        // "Add to home" with every page full opens a new page instead of failing.
        assertEquals(2, full.add("extra").pages)
        assertEquals(g.size, full.add("extra").cellOf("extra"))
    }

    @Test fun cellAtMapsPointsAndRejectsOutside() {
        assertEquals(0, g.cellAt(1f, 1f, 400f, 500f))
        assertEquals(3, g.cellAt(399f, 1f, 400f, 500f))
        assertEquals(19, g.cellAt(399f, 499f, 400f, 500f))
        assertEquals(6, g.cellAt(250f, 150f, 400f, 500f)) // col 2, row 1
        assertNull(g.cellAt(-1f, 10f, 400f, 500f))
        assertNull(g.cellAt(10f, 500f, 400f, 500f))
    }

    @Test fun removeAndKeepOnly() {
        val a = g.add("a").add("b").add("c")
        assertEquals(setOf("a", "c"), a.remove("b").cells.values.toSet())
        assertEquals(setOf("b"), a.keepOnly(setOf("b", "zzz")).cells.values.toSet())
    }

    @Test fun serializeRoundTrips() {
        val a = HomeGrid(5, 6).place("com.x/.Main#0", 3).place("org.y/.A#10", 29)
        assertEquals(a, HomeGrid.parse(a.serialize()))
        assertEquals(HomeGrid(), HomeGrid.parse(null))
        assertEquals(HomeGrid(), HomeGrid.parse("garbage"))
    }
}
