package app.korvy.launcher

import app.korvy.launcher.core.Launch
import app.korvy.launcher.core.Suggest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SuggestTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    // Monday 23 Nov 2026, 08:00
    private val mondayMorning = ZonedDateTime.of(2026, 11, 23, 8, 0, 0, 0, zone)

    private fun at(daysAgo: Long, hour: Int, minute: Int = 0) =
        mondayMorning.minusDays(daysAgo).withHour(hour).withMinute(minute).toInstant().toEpochMilli()

    @Test fun morningAppWinsInTheMorningEveningAppInTheEvening() {
        val launches = (1L..14L).flatMap { d -> listOf(Launch("transit", at(d, 8)), Launch("music", at(d, 20))) }
        assertEquals("transit", Suggest.rank(launches, mondayMorning).first())
        assertEquals("music", Suggest.rank(launches, mondayMorning.withHour(20)).first())
    }

    @Test fun sameWeekdayBeatsOtherDays() {
        // "gym" every Monday 8:00 (4 times); "news" every weekend day at 8:00 (8 times).
        val launches = (0 until 4).map { Launch("gym", at(7L * (it + 1), 8)) } +
            (0 until 4).flatMap { w -> listOf(Launch("news", at(7L * w + 1, 8)), Launch("news", at(7L * w + 2, 8))) }
        assertEquals("gym", Suggest.rank(launches, mondayMorning).first())
    }

    @Test fun timeOfDayWrapsAroundMidnight() {
        val nearMidnight = mondayMorning.withHour(23).withMinute(45)
        val launches = (1L..7L).map { Launch("reader", at(it, 0, 15)) } + (1L..7L).map { Launch("maps", at(it, 12)) }
        assertEquals("reader", Suggest.rank(launches, nearMidnight).first())
    }

    @Test fun oldLaunchesFadeAndVeryOldOnesAreIgnored() {
        val recent = Suggest.score(mondayMorning.minusDays(7), mondayMorning)
        val older = Suggest.score(mondayMorning.minusDays(28), mondayMorning)
        assertTrue(recent > older)
        assertEquals(0.0, Suggest.score(mondayMorning.minusDays(Suggest.MAX_AGE_DAYS + 1), mondayMorning), 0.0)
    }

    @Test fun excludedAppsAreNeverSuggested() {
        val launches = (1L..5L).map { Launch("secret", at(it, 8)) } + Launch("other", at(3, 15))
        assertEquals(listOf("other"), Suggest.rank(launches, mondayMorning, exclude = setOf("secret")))
    }
}
