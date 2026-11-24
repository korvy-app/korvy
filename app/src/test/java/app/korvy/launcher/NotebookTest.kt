package app.korvy.launcher

import app.korvy.launcher.core.Lesson
import app.korvy.launcher.core.Notebook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookTest {
    @Test fun recognisesLessons() {
        assertEquals(Lesson.Note("I like temperatures in Celsius"), Lesson.parse("Remember that I like temperatures in Celsius."))
        assertEquals(Lesson.NewPhrase("goodnight", listOf("set a timer for 8 hours", "play rain sounds")),
            Lesson.parse("When I say goodnight, set a timer for 8 hours and play rain sounds"))
        assertEquals(Lesson.NewPhrase("heading home", listOf("text Jen on my way", "directions to home")),
            Lesson.parse("when I say \"heading home\" text Jen on my way, then directions to home"))
        assertEquals(Lesson.Alias("Jen", "Jennifer Lopez"), Lesson.parse("When I say Jen I mean Jennifer Lopez"))
        assertEquals(Lesson.Alias("the bird app", "X"), Lesson.parse("whenever I say the bird app, I mean X"))
        assertEquals(Lesson.Forget("goodnight"), Lesson.parse("forget goodnight"))
        assertEquals(Lesson.Show, Lesson.parse("What do you remember?"))
        assertNull(Lesson.parse("what's the weather"))
    }

    @Test fun multiWordTriggers() {
        assertEquals(Lesson.NewPhrase("good night", listOf("set a timer for 8 hours")), Lesson.parse("when I say good night set a timer for 8 hours"))
        assertEquals(Lesson.NewPhrase("lights out", listOf("play white noise")), Lesson.parse("when I say lights out, play white noise"))
    }

    @Test fun keepsRockAndRollTogether() {
        // "and" only splits before a new command word.
        assertEquals(Lesson.NewPhrase("party", listOf("play rock and roll")), Lesson.parse("when I say party play rock and roll"))
    }

    @Test fun markdownRoundTrips() {
        val nb = Notebook().addNote("I like Celsius").addPhrase("goodnight", listOf("timer 8 hours", "play rain sounds"))
        val back = Notebook.parse(nb.toMarkdown())
        assertEquals(nb, back)
        // Hand-written files work too.
        val hand = Notebook.parse("## Notes\n- Vegetarian\n## Phrases\n- morning -> weather; news\n")
        assertEquals(listOf("Vegetarian"), hand.notes)
        assertEquals(listOf("weather", "news"), hand.phraseFor("Morning!")?.steps)
    }

    @Test fun phrasesMatchLoosely() {
        val nb = Notebook().addPhrase("Good night", listOf("timer 8 hours"))
        assertEquals("Good night", nb.phraseFor("good night.")?.trigger)
        assertEquals("Good night", nb.phraseFor("Good night please")?.trigger)
        assertNull(nb.phraseFor("good night moon"))
    }

    @Test fun onlyRelevantNotesWithinBudget() {
        var nb = Notebook()
        repeat(40) { nb = nb.addNote("Filler note number $it about gardening and birds") }
        nb = nb.addNote("I like temperatures in Celsius")
        val picked = nb.notesFor("what's the temperature outside", budget = 120)
        assertEquals("I like temperatures in Celsius.", picked.first())
        assertTrue(picked.sumOf { it.length + 2 } <= 120)
    }

    @Test fun forgetAndTidy() {
        val nb = Notebook().addNote("I like Celsius").addNote("i like celsius!").addPhrase("goodnight", listOf("x")).addNote("Vegetarian")
        assertEquals(2, nb.notes.size) // duplicate ignored
        val (after, n) = nb.forget("celsius")
        assertEquals(1, n); assertEquals(listOf("Vegetarian."), after.notes)
        assertEquals(0, nb.forget("goodnight").first.phrases.size)
    }

    @Test fun rememberWhenISayIsAPhrase() {
        assertEquals(Lesson.NewPhrase("Do the thing", listOf("send a message to Lukas that says \"Turn off the lights\"")),
            Lesson.parse("Remember when I say \"Do the thing\" send a message to Lukas that says \"Turn off the lights\""))
        assertEquals(Lesson.NewPhrase("lights out", listOf("play white noise")), Lesson.parse("remember that when I say lights out, play white noise"))
        // A note stays a note.
        assertEquals(Lesson.Note("I like tea"), Lesson.parse("remember I like tea"))
    }

    @Test fun repairsPhrasesSavedAsNotes() {
        // Exactly what 0.11.3 saved for the owner's request.
        val nb = Notebook().addNote("when I say \"Do the thing\" send a message to Lukas that says \"Turn off the lights\"").addNote("I like tea")
        val fixed = nb.repaired()
        assertEquals(listOf("I like tea."), fixed.notes)
        assertEquals(listOf("send a message to Lukas that says \"Turn off the lights\""), fixed.phraseFor("do the thing")?.steps)
        // Notes written by "when I say X I mean Y" (not a phrase) are left alone.
        val alias = Notebook().addNote("When I say “boss”, I mean my manager")
        assertEquals(alias, alias.repaired())
    }
}
