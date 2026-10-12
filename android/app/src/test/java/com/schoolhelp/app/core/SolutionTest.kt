package com.schoolhelp.app.core

import org.junit.Assert.*
import org.junit.Test

class SolutionTest {
    @Test fun parsedStartingPagesPreserveQuestionNumbersAndAnnouncePageInBothLanguages() {
        val combined = SolutionParser.parse("""{"problems":[
            {"number":"1","page":1,"answer":"A","spokenText":"First answer."},
            {"number":"1","page":2,"answer":"B","spokenText":"Second answer."}]}""")
        assertEquals(listOf(1, 2), combined.problems.map { it.page })
        assertEquals(listOf("1", "1"), combined.problems.map { it.number })
        assertEquals("Page 1. Question 1. First answer.\n\nPage 2. Question 1. Second answer.", combined.speech(false, "en"))
        assertTrue(combined.speech(false, "pl").contains("Strona 2. Zadanie 1."))
        assertNull(SolutionParser.parse("""{"problems":[{"number":"1","answer":"A","spokenText":"First answer."}]}""").problems.single().page)
    }
    private val json = """{"problems":[{"number":"7","question":"2 + 2","answer":"4","explanation":"Add two and two.","spokenText":"The answer is four. Add two and two.","unreadable":false}]}"""

    @Test fun parsesNumberedProblemFromFencedOutput() {
        val result = SolutionParser.parse("```json\n$json\n```")
        assertEquals("7", result.problems.single().number)
        assertEquals("4", result.problems.single().answer)
    }

    @Test fun removesThinkingBeforeParsingAnswer() {
        val result = SolutionParser.parse("<think>{This is not JSON}</think>\n$json")
        assertEquals("4", result.problems.single().answer)
    }

    @Test fun answerOnlyOmitsExplanation() {
        val concise = Solution(listOf(Problem("7", "", "4", "Add two and two.", "Four.")))
        assertEquals("Question 7. Four.", concise.speech(false, "en"))
    }

    @Test fun explanationsUseNaturalSpokenText() {
        assertEquals("Question 7. The answer is four. Add two and two.", SolutionParser.parse(json).speech(true, "en"))
    }

    @Test fun unreadableProblemKeepsRetakeMessage() {
        val solution = Solution(listOf(Problem("3", "", "Please retake this photo.", "", "", true)))
        assertEquals("Question 3. Please retake this photo.", solution.speech(true, "en"))
    }

    @Test fun polishQuestionLabelIsLocalized() {
        val concise = Solution(listOf(Problem("7", "", "4", "", "Cztery.")))
        assertEquals("Zadanie 7. Cztery.", concise.speech(false, "pl"))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsTruncatedOutput() {
        SolutionParser.parse("{\"problems\":[")
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsEmptyProblemList() {
        SolutionParser.parse("{\"problems\":[]}")
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsMissingAnswers() {
        SolutionParser.parse("{\"problems\":[{\"number\":\"1\"}]}")
    }

    @Test fun commonNotationIsReadableWhenSpokenTextIsMissing() {
        assertEquals("x squared equals 4", SpeechText.clean("**x² = 4**"))
        assertEquals("1 over 2", SpeechText.clean("\\frac{1}{2}"))
        assertEquals("square root of 9", SpeechText.clean("\\sqrt{9}"))
    }

    @Test fun tablePlacementInstructionsAreSpokenEvenWhenReasoningIsDisabled() {
        val solution = Solution(listOf(Problem("2", "Complete the table", "Present", "", "In the row Plant cell, under Cell wall, write Present.")))
        assertEquals("Question 2. In the row Plant cell, under Cell wall, write Present.", solution.speech(false, "en"))
    }

    @Test fun fractionGlyphsAndSlashFractionsAreReadable() {
        assertEquals("Write one half or 1 over 2.", SpeechText.clean("Write ½ or 1/2."))
        assertEquals("1 over 2", SpeechText.clean("\\dfrac{1}{2}"))
        assertEquals("jedna druga i 3 przez 4", SpeechText.clean("½ i 3/4", "pl"))
    }

    @Test fun spokenScriptAlsoReceivesNotationCleanup() {
        val solution = Solution(listOf(Problem("1", "", "ignored", "", "**½**")))
        assertEquals("Question 1. one half", solution.speech(false, "en"))
    }

    @Test fun nestedFractionsKeepNumeratorAndDenominatorGrouping() {
        assertEquals("a fraction with numerator 1 over 2 and denominator 3 end of fraction", SpeechText.clean("\\frac{\\frac{1}{2}}{3}"))
    }

    @Test fun formattingMustBeRewrittenBeforeSpeechGeneration() {
        for (script in listOf("", "\\frac{1}{2}", "**Answer**", "x² = 4", "| Plant | Present |", "- First entry")) {
            val solution = Solution(listOf(Problem("1", "", "answer", "", script)))
            assertTrue(SolutionParser.needsSpokenRewrite(solution))
            assertNotNull(runCatching { SolutionParser.requireListeningText(solution) }.exceptionOrNull())
        }
        val plain = Solution(listOf(Problem("1", "", "answer", "", "Write one half. Put one above the fraction bar and two below it.")))
        assertFalse(SolutionParser.needsSpokenRewrite(plain))
        assertEquals(plain, SolutionParser.requireListeningText(plain))
    }
}
