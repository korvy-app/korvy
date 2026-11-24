package app.korvy.launcher

import app.korvy.launcher.core.Speech
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTest {
    @Test fun placeNamesDontRepeat() {
        assertEquals("San Diego, California", Speech.placeName("San Diego", "California"))
        assertEquals("New York", Speech.placeName("New York", "New York"))
        assertEquals("Mexico City", Speech.placeName("Mexico City", "Mexico City"))
        assertEquals("Luxembourg", Speech.placeName("Luxembourg", "Luxembourg District"))
        assertEquals("Paris", Speech.placeName("Paris", ""))
    }

    @Test fun speaksDetailsButNotSourcesOrSymbols() {
        assertEquals(
            "Clear, 64 degrees in San Diego. High 70 degrees, low 55 degrees. Chance of rain 10 percent.",
            Speech.forSpeech("Clear, 64°F in San Diego.", listOf("High 70°F · low 55°F", "Chance of rain 10%", "Source: Open-Meteo")),
        )
        assertEquals("Top headlines: Storm hits coast. Markets rise.",
            Speech.forSpeech("Top headlines:", listOf("• Storm hits coast", "• Markets rise", "Source: NPR, BBC")))
    }
}
