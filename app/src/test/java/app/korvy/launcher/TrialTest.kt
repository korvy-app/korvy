package app.korvy.launcher

import app.korvy.launcher.core.Trial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrialTest {
    private val day = 24L * 60 * 60 * 1000
    private val t0 = 1_800_000_000_000L

    @Test fun sevenDaysThenOver() {
        assertFalse(Trial.active(0, t0))                    // never started
        assertTrue(Trial.canStart(0)); assertFalse(Trial.canStart(t0))
        assertTrue(Trial.active(t0, t0))
        assertEquals(7, Trial.daysLeft(t0, t0))
        assertEquals(7, Trial.daysLeft(t0, t0 + day / 2))   // rounds up
        assertEquals(1, Trial.daysLeft(t0, t0 + 6 * day + 1))
        assertFalse(Trial.active(t0, t0 + 7 * day))
        assertEquals(0, Trial.daysLeft(t0, t0 + 8 * day))
        assertFalse(Trial.active(t0, t0 - 1))               // clock set back before the start: not active
    }
}
