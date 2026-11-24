package app.korvy.launcher

import app.korvy.launcher.core.SentenceSplitter
import org.junit.Assert.assertEquals
import org.junit.Test

class SentenceSplitterTest {
    @Test fun returnsEachFinishedSentenceOnceAsTextStreamsIn() {
        val s = SentenceSplitter()
        assertEquals(emptyList<String>(), s.feed("Paris is the capital"))
        assertEquals(emptyList<String>(), s.feed("Paris is the capital of France."))      // not sure it ended yet
        assertEquals(listOf("Paris is the capital of France."), s.feed("Paris is the capital of France. It is"))
        assertEquals(emptyList<String>(), s.feed("Paris is the capital of France. It is big"))
        assertEquals(listOf("It is big!"), s.finish("Paris is the capital of France. It is big!"))
    }

    @Test fun doesNotSplitOnAbbreviationsOrDecimals() {
        val s = SentenceSplitter()
        assertEquals(listOf("Dr. Smith paid 3.50 dollars."), s.feed("Dr. Smith paid 3.50 dollars. Then"))
        assertEquals(listOf("Then left."), s.finish("Dr. Smith paid 3.50 dollars. Then left."))
    }

    @Test fun finishReturnsAnUnterminatedTail() {
        assertEquals(listOf("Timer set for 5 minutes"), SentenceSplitter().finish("Timer set for 5 minutes"))
    }
}
