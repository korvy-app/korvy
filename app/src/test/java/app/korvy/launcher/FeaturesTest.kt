package app.korvy.launcher

import app.korvy.launcher.core.Feature
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeaturesTest {
    @Test fun wakeWordIsOffUntilSwitchedOn() {
        assertFalse(Feature.WAKE_WORD.default)
        assertFalse(Feature.WAKE_WORD.on(emptySet()))
        assertTrue(Feature.WAKE_WORD.on(setOf("wake_word")))
    }
}
