package app.korvy.launcher

import app.korvy.launcher.core.HomeGrid
import app.korvy.launcher.core.WidgetPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeWidgetsTest {
    // 4 × 5 grid: rows of 4 cells (row 0 = cells 0-3, row 1 = 4-7, …), 20 cells a page.
    private val g = HomeGrid(4, 5)

    @Test fun aWidgetTakesWholeRowsAndAppsMoveOut() {
        val withApps = g.place("a", 0).place("b", 5).place("c", 12)
        val w = withApps.placeWidget(7, page = 0, row = 0, rows = 2) // rows 0-1: cells 0-7
        assertEquals(WidgetPlace(0, 0, 2), w.widgets[7])
        assertTrue((0..7).none { it in w.cells })                    // nothing under the widget
        assertTrue(listOf("a", "b", "c").all { w.cellOf(it) != null }) // nobody lost
        assertEquals(12, w.cellOf("c"))                             // untouched
        assertEquals(7, w.widgetAt(5)); assertNull(w.widgetAt(8))
    }

    @Test fun appsCantBeDroppedOnAWidget() {
        val w = g.placeWidget(7, 0, 0, 2)
        assertEquals(w, w.place("a", 6))              // row 1 is the widget
        assertEquals(8, w.add("a").cellOf("a"))       // first free cell is just below it
    }

    @Test fun movingToAnotherPageAndRowsThatDontFit() {
        val w = g.placeWidget(7, 0, 0, 2).placeWidget(7, page = 1, row = 4, rows = 2) // row 4 + 2 > 5: moved up to row 3
        assertEquals(WidgetPlace(1, 3, 2), w.widgets[7])
        assertEquals(2, w.pages)
        assertEquals(1, w.compact().pages.let { if (w.cells.isEmpty()) 1 else it }) // page 1 now empty: page 2 moves up
        assertEquals(WidgetPlace(0, 3, 2), w.compact().widgets[7])
    }

    @Test fun twoWidgetsSwapWhenTheyFit() {
        val w = g.placeWidget(1, 0, 0, 2).placeWidget(2, 0, 3, 2)
        val swapped = w.placeWidget(1, 0, 3, 2) // drop widget 1 on widget 2
        assertEquals(WidgetPlace(0, 3, 2), swapped.widgets[1])
        assertEquals(WidgetPlace(0, 0, 2), swapped.widgets[2])
    }

    @Test fun addingFindsFreeRowsOrANewPage() {
        var full = g
        repeat(20) { full = full.place("app$it", it) }
        val w = full.addWidget(9, rows = 2)
        assertEquals(WidgetPlace(1, 0, 2), w.widgets[9])
        assertEquals(2, w.pages)
    }

    @Test fun widgetsAreSaved() {
        val w = g.place("a", 19).placeWidget(5, 0, 1, 3).placeWidget(6, 0, 4, 1)
        assertEquals(w, HomeGrid.parse(w.serialize()))
        assertEquals(emptyMap<Int, WidgetPlace>(), HomeGrid.parse("4x5|0=a").widgets) // old layouts: no widgets
    }

    @Test fun resizeKeepsThePlace() {
        val w = g.placeWidget(5, 0, 3, 1).resizeWidget(5, 3)
        assertEquals(WidgetPlace(0, 2, 3), w.widgets[5]) // grew upward to fit
    }
}
