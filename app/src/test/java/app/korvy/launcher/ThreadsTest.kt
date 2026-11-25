package app.korvy.launcher

import app.korvy.launcher.core.Threads
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadsTest {
    @Test fun phones() {
        // Pixel 4a (Snapdragon 730G): 6 × A55 (492), 2 × A76 (1024).
        assertEquals(2 to 8, Threads.forModel(listOf(492, 492, 492, 492, 492, 492, 1024, 1024), 8))
        // Tensor G4-style: 4 small, 3 big, 1 prime.
        assertEquals(4 to 8, Threads.forModel(listOf(255, 255, 255, 255, 840, 840, 840, 1024), 8))
        // All cores alike (4 × A53).
        assertEquals(4 to 4, Threads.forModel(listOf(1024, 1024, 1024, 1024), 4))
        // Unreadable: a safe middle.
        assertEquals(4 to 8, Threads.forModel(emptyList(), 8))
    }
}
