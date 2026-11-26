package app.korvy.launcher

import app.korvy.launcher.core.ScreenContext
import app.korvy.launcher.core.ScreenText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTextTest {
    @Test fun compactsWhatAnAppShows() {
        val t = ScreenText.compact(listOf("  Blue Bottle Coffee ", "•", "", "Open until 6 PM", "Directions", "Directions",
            "300 Webster St, Oakland", "★", "x"))
        assertEquals("Blue Bottle Coffee\nOpen until 6 PM\nDirections\n300 Webster St, Oakland", t)
    }

    @Test fun capsTheLengthAtALineEnd() {
        val lines = (1..200).map { "Line number $it of a long article" }
        val t = ScreenText.compact(lines, max = 100)
        assertTrue(t.length <= 100)
        assertTrue(t.endsWith("article")) // whole lines only
        assertEquals(300, ScreenText.compact(listOf("x".repeat(500)), max = 300).length) // one huge text: cut
    }

    @Test fun knowsWhichRequestsAreAboutTheScreen() {
        listOf("text this to Jen", "summarize this", "what does this page say", "add that to my calendar",
            "translate it into Spanish", "envíale esto a Jen", "¿qué dice aquí?", "resume la página",
            "Resuma o que está na minha tela", "O que é importante aqui?", "Résume ce qui est à l'écran",
            "Qu'est-ce qui est important ici ?", "Fasse zusammen, was auf meinem Bildschirm steht", "Was ist hier wichtig?").forEach {
            assertTrue(it, ScreenText.refersToScreen(it))
        }
        listOf("set a timer for 5 minutes", "call mom", "what's the weather in Paris", "open camera",
            "pon una alarma a las 7").forEach { assertFalse(it, ScreenText.refersToScreen(it)) }
    }

    @Test fun promptSaysWhereTheTextIsFrom() {
        assertTrue(ScreenText.forPrompt(ScreenContext("Maps", "300 Webster St")).contains("screen in Maps"))
        assertTrue(ScreenText.forPrompt(ScreenContext("Chrome", "hello", selected = true)).contains("selected in Chrome"))
    }
}
