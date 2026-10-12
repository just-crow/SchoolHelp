package com.schoolhelp.app

import com.schoolhelp.app.core.SpeechText
import com.schoolhelp.app.core.Solution
import com.schoolhelp.app.core.Problem
import org.junit.Assert.assertEquals
import org.junit.Test

/** Run on Android's ICU implementation, not only desktop Java's regex engine. */
class SpeechTextAndroidTest {
    @Test fun ordinaryTextAndFractionsDoNotCrashOnAndroid() {
        assertEquals("Plain spoken answer.", SpeechText.clean("Plain spoken answer."))
        for (command in listOf("frac", "dfrac", "tfrac")) {
            assertEquals("1 over 2", SpeechText.clean("\\$command{1}{2}"))
        }
        assertEquals("square root of 9", SpeechText.clean("\\sqrt{9}"))
        assertEquals("Write one half or 1 over 2.", SpeechText.clean("Write ½ or 1/2."))
        assertEquals("jedna druga i 3 przez 4", SpeechText.clean("½ i 3/4", "pl"))
    }

    @Test fun nestedFractionsKeepTheirGrouping() {
        assertEquals("a fraction with numerator 1 over 2 and denominator 3 end of fraction",
            SpeechText.clean("\\frac{\\frac{1}{2}}{3}"))
        val solution = Solution(listOf(Problem("1", "", "", "", "**\\frac{1}{2}**")))
        assertEquals("Question 1. 1 over 2", solution.speech(false, "en"))
    }
}
