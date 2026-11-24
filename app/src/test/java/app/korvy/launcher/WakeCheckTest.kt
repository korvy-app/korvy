package app.korvy.launcher

import app.korvy.launcher.core.WakeCheck
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Transcripts are real Whisper tiny.en output for macOS voices (scripts/kws-bench), 2026-11-23. */
class WakeCheckTest {
    @Test fun acceptsHowWhisperWritesKorvy() {
        listOf("Hey Corvie!", "Hey Corvie.", "Hey, Corvie.", "Hey Korvy", "hey corvy", "Heycorvie").forEach {
            assertTrue(it, WakeCheck.matches(it, "Hey Korvy"))
        }
    }

    @Test fun rejectsNearMisses() {
        listOf("Hey Curvie.", "Hey curvy.", "Hey, Crevy.", "Hey Covey!", "Hey Kirby!", "Hey, Corey.", "Hey Cori.",
            "Hey, Korea.", "Hageria.", "Hey Groovy!", "Hey Coffee!", "Hicofi", "Hey Carvett!", "Hey Kobe.", "HIKORI").forEach {
            assertFalse(it, WakeCheck.matches(it, "Hey Korvy"))
        }
    }

    @Test fun worksForOtherPhrases() {
        assertTrue(WakeCheck.matches("Okay, launcher.", "Hey Launcher"))
        assertFalse(WakeCheck.matches("Hey there", "Hey Launcher"))
    }

    @Test fun looseSpellingsDropTheR() {
        assertTrue("hey covie" in WakeCheck.looseSpellings(listOf("hey korvie", "hey corvie")))
    }
}
