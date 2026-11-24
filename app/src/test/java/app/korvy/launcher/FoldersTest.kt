package app.korvy.launcher

import app.korvy.launcher.core.HomeGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoldersTest {
    private val g0 = HomeGrid(cells = mapOf(0 to "maps", 1 to "mail", 5 to "music"))

    @Test fun droppingOntoAnIconMakesAFolder() {
        val g = g0.place("mail", target = 0, from = 1, combine = true)
        val f = g.folder(g.cells[0])!!
        assertEquals(listOf("maps", "mail"), f.keys)
        assertEquals("Folder", f.name)
        assertNull(g.cells[1])                      // mail left its cell
        assertTrue(g.contains("mail"))
    }

    @Test fun moreAppsGoIntoTheFolder() {
        val g = g0.place("mail", 0, 1, combine = true).place("music", 0, 5, combine = true).place("camera", 0, null, combine = true)
        assertEquals(listOf("maps", "mail", "music", "camera"), g.folder(g.cells[0])!!.keys)
        assertEquals(setOf(0), g.cells.keys)
    }

    @Test fun edgeDropStillSwaps() {
        val g = g0.place("mail", target = 0, from = 1, combine = false)
        assertEquals("mail", g.cells[0]); assertEquals("maps", g.cells[1]); assertTrue(g.folders.isEmpty())
    }

    @Test fun draggingOutAndCollapsing() {
        val g = g0.place("mail", 0, 1, combine = true)
        // Drag "mail" out of the folder onto empty cell 3: the folder has one app left, so it becomes that app.
        val out = g.place("mail", target = 3)
        assertEquals("maps", out.cells[0]); assertEquals("mail", out.cells[3]); assertTrue(out.folders.isEmpty())
        // "Remove from folder" puts it in the nearest free cell.
        val g3 = g.place("music", 0, 5, combine = true).takeOutOfFolder("music")
        assertEquals(listOf("maps", "mail"), g3.folder(g3.cells[0])!!.keys)
        assertTrue(g3.cellOf("music") != null)
    }

    @Test fun foldersCanMoveButNotNest() {
        val g = g0.place("mail", 0, 1, combine = true)
        val folderKey = g.cells.getValue(0)
        val moved = g.place(folderKey, target = 7, from = 0)
        assertEquals(folderKey, moved.cells[7]); assertNull(moved.cells[0])
        val onto = g.place(folderKey, target = 5, from = 0, combine = true) // folder onto an app: swap, no nesting
        assertEquals(folderKey, onto.cells[5]); assertEquals("music", onto.cells[0])
    }

    @Test fun hiddenOrUninstalledAppsLeaveFolders() {
        val g = g0.place("mail", 0, 1, combine = true).place("music", 0, 5, combine = true)
        val kept = g.keepOnly(setOf("maps", "music"))
        assertEquals(listOf("maps", "music"), kept.folder(kept.cells[0])!!.keys)
        val one = g.keepOnly(setOf("music"))
        assertEquals("music", one.cells[0]); assertTrue(one.folders.isEmpty())
        assertFalse(g.remove(g.cells.getValue(0)).contains("maps"))
    }

    @Test fun savesAndLoads() {
        val g = g0.place("mail", 0, 1, combine = true).let { it.renameFolder(it.folders.keys.first(), "Travel | stuff") }
        val back = HomeGrid.parse(g.serialize())
        assertEquals(g, back)
        assertEquals("Travel   stuff", back.folders.values.first().name)
        assertEquals(HomeGrid(cells = mapOf(0 to "a")), HomeGrid.parse(HomeGrid(cells = mapOf(0 to "a")).serialize())) // old format
    }
}
