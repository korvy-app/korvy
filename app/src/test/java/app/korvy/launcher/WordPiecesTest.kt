package app.korvy.launcher

import app.korvy.launcher.core.WordPieces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class WordPiecesTest {
    private val wp = WordPieces.parse(File("src/main/assets/kws-pieces.tsv").readText())

    /** The model's own examples (keywords_raw.txt → keywords.txt). */
    @Test fun matchesTheModelsExamples() {
        assertEquals("▁HE LL O ▁WORLD", wp.encode("hello world"))
        assertEquals("▁HI ▁GO O G LE", wp.encode("Hi Google"))
        assertEquals("▁HE Y ▁S I RI", wp.encode("hey siri"))
        assertEquals("▁A LE X A", wp.encode("Alexa"))
        assertEquals("▁LOVE ▁AND ▁P E A CE", wp.encode("love and peace"))
    }

    @Test fun inventedNames() {
        assertEquals("▁HE Y ▁O K U VI", wp.encode("hey okuvi"))
        assertEquals("▁HE Y ▁LA UN CH ER", wp.encode("Hey, Launcher!"))
        assertEquals("▁HE Y ▁K OR V Y", wp.encode("Hey Korvy"))
        assertNull(wp.encode("123"))
    }

    @Test fun syllables() {
        assertEquals(3, WordPieces.syllables("Okuvi"))
        assertEquals(1, WordPieces.syllables("phone"))
    }
}
