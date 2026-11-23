package com.hiddenoutlier.launcher

import com.hiddenoutlier.launcher.core.Endpointer
import com.hiddenoutlier.launcher.core.Endpointer.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class EndpointerTest {
    private fun noise(amp: Float) = FloatArray(1600) { ((it * 7919) % 200 - 100) / 100f * amp }
    private fun voice(amp: Float) = FloatArray(1600) { (sin(it * 0.2) * amp).toFloat() }

    @Test fun stopsAfterTheSpeakerPauses() {
        val ep = Endpointer()
        repeat(5) { assertEquals(Result.CONTINUE, ep.feed(noise(0.002f))) } // quiet room
        repeat(15) { assertEquals(Result.CONTINUE, ep.feed(voice(0.2f))) }  // 1.5 s of speech
        var r = Result.CONTINUE; var frames = 0
        while (r == Result.CONTINUE && frames < 50) { r = ep.feed(noise(0.002f)); frames++ }
        assertEquals(Result.DONE, r)
        assertTrue("stops ~0.9 s after speech, took ${frames * 100} ms", frames in 8..11)
    }

    @Test fun givesUpWhenNobodySpeaks() {
        val ep = Endpointer()
        var r = Result.CONTINUE; var frames = 0
        while (r == Result.CONTINUE && frames < 100) { r = ep.feed(noise(0.004f)); frames++ }
        assertEquals(Result.NO_SPEECH, r)
        assertEquals(60, frames)
    }

    @Test fun loudRoomNeedsLouderSpeech() {
        val ep = Endpointer()
        repeat(10) { ep.feed(noise(0.05f)) } // steady fan/traffic noise is not speech
        assertTrue(!ep.heard)
    }

    @Test fun dropsWhisperNonSpeechMarks() {
        assertEquals("Call Mom.", Endpointer.clean(" [BLANK_AUDIO] Call Mom. (music) "))
        assertEquals("", Endpointer.clean("[BLANK_AUDIO]"))
    }
}
