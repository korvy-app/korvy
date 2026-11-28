package app.korvy.launcher

import app.korvy.launcher.core.GridSize
import app.korvy.launcher.core.HomeGrid
import app.korvy.launcher.core.WidgetPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GridSizeTest {
    @Test fun autoFitsTheScreen() {
        assertEquals(3 to 4, GridSize.auto(320, 640))   // small phone
        assertEquals(4 to 5, GridSize.auto(393, 851))   // Pixel 4a (today's 4 × 5)
        assertEquals(4 to 5, GridSize.auto(360, 800))   // common Android phone
        assertEquals(5 to 6, GridSize.auto(448, 998))   // XL phone (e.g. a "Pro XL")
        assertEquals(8 to 8, GridSize.auto(800, 1280))  // tablet
        assertEquals(5 to 6, GridSize.choose(5, 0, 393, 851).let { it.first to 6 }.let { GridSize.choose(5, 6, 393, 851) })
        assertEquals(4 to 5, GridSize.choose(0, 0, 393, 851))
    }

    @Test fun appsKeepTheirPlaceWhenTheyStillFit() {
        // 4 × 5 → 5 × 6: every cell still exists.
        val g = HomeGrid(4, 5).place("a", 0).place("b", 3).place("c", 19).place("d", 20 + 5)
        val big = g.resized(5, 6)
        assertEquals(0, big.cellOf("a"))                 // row 0 col 0
        assertEquals(3, big.cellOf("b"))                 // row 0 col 3
        assertEquals(4 * 5 + 3, big.cellOf("c"))         // row 4 col 3
        assertEquals(30 + 1 * 5 + 1, big.cellOf("d"))    // page 2, row 1 col 1
    }

    @Test fun appsThatNoLongerFitMoveNearbyAndNobodyIsLost() {
        var g = HomeGrid(5, 6)
        repeat(30) { g = g.place("app$it", it) }       // a full 5 × 6 page
        val small = g.resized(4, 5)                      // 20 cells a page
        assertEquals(30, small.cells.size)
        assertTrue((0 until 30).all { small.cellOf("app$it") != null })
        assertEquals(2, small.pages)                     // the overflow opened page 2
        assertEquals(0, small.cellOf("app0"))
    }

    @Test fun widgetsShrinkToFit() {
        val g = HomeGrid(4, 8).placeWidget(7, 0, 4, 4)  // rows 4-7 on a tall tablet grid
        val w = g.resized(4, 5).widgets[7]!!
        assertEquals(0, w.page); assertTrue(w.row + w.rows <= 5)
    }
}
