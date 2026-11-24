package app.korvy.launcher

import app.korvy.launcher.core.slideshowIndex
import org.junit.Assert.assertEquals
import org.junit.Test

class SlideshowTest {
    private val t0 = 1_800_000_000_000L - 1_800_000_000_000L % 60_000L // a whole minute

    @Test fun stepsOncePerIntervalAndWraps() {
        val first = slideshowIndex(t0, 1, 4)
        assertEquals(first, slideshowIndex(t0 + 59_999, 1, 4))
        assertEquals((first + 1) % 4, slideshowIndex(t0 + 60_000, 1, 4))
        assertEquals(first, slideshowIndex(t0 + 4 * 60_000, 1, 4)) // back to the start after 4 pictures
    }

    @Test fun advancesEvenWhenTheHomeScreenWasntVisible() {
        // Leaving for 3 minutes and coming back shows the picture 3 steps on.
        assertEquals((slideshowIndex(t0, 1, 4) + 3) % 4, slideshowIndex(t0 + 3 * 60_000 + 5_000, 1, 4))
    }

    @Test fun singlePictureOrBadIntervalStaysOnTheFirst() {
        assertEquals(0, slideshowIndex(t0, 1, 1))
        assertEquals(0, slideshowIndex(t0, 0, 4))
    }
}
