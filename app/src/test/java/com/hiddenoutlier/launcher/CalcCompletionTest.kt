package com.hiddenoutlier.launcher

import com.hiddenoutlier.launcher.core.Calc
import com.hiddenoutlier.launcher.core.Completion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalcCompletionTest {
    @Test fun percentages() {
        assertEquals("18% of 64 is 11.52.", Calc.answer("what is 18 percent of 64"))
        assertEquals("18% of 64 is 11.52.", Calc.answer("what's 18% of 64?"))
        assertEquals("A 20% tip on $42.50 is $8.50 (total $51.00).", Calc.answer("20% tip on 42.50"))
    }

    @Test fun expressions() {
        assertEquals("12*7 = 84", Calc.answer("what is 12*7"))
        assertEquals("12 x 7 = 84", Calc.answer("12 x 7"))
        assertEquals("(2+3)^2 / 4 = 6.25", Calc.answer("calculate (2+3)^2 / 4"))
        assertEquals("10 / 3 = 3.333333", Calc.answer("10 / 3"))
        assertEquals("1,200 - 5% = 1199.95", Calc.answer("1,200 - 5%"))
    }

    @Test fun notMaths() {
        assertNull(Calc.answer("what is the capital of France"))
        assertNull(Calc.answer("open camera"))
        assertNull(Calc.answer("timer 5 minutes"))
        assertNull(Calc.answer("2024"))
    }

    @Test fun completionOnlyWhenTheTopNameStartsWithTheQuery() {
        assertEquals("tify", Completion.suffix("spo", listOf("Spotify", "Sports")))
        assertEquals("tify", Completion.suffix("SPO", listOf("Spotify")))
        assertNull(Completion.suffix("maps", listOf("Google Maps")))   // matched mid-name: no ghost text
        assertNull(Completion.suffix("chrome", listOf("Chrome")))       // nothing left to add
        assertNull(Completion.suffix("", listOf("Chrome")))
    }
}
