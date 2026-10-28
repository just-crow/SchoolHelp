package com.schoolhelp.app.core

import org.junit.Assert.*
import org.junit.Test

class SolutionTest {
    @Test fun legacyAnswersKeepOrderWithoutInjectingLanguageSpecificLabels() {
        val combined = SolutionParser.parse("""{"problems":[
            {"number":"1","page":1,"answer":"A","spokenText":"First answer."},
            {"number":"1","page":2,"answer":"B","spokenText":"Second answer."}]}""")
        assertEquals(listOf(1, 2), combined.problems.map { it.page })
        assertEquals(listOf("1", "1"), combined.problems.map { it.number })
        assertEquals("1. First answer.\n\n1. Second answer.", combined.speech(false, "en"))
        assertEquals(combined.speech(false, "en"), combined.speech(false, "pl"))
        assertNull(SolutionParser.parse("""{"problems":[{"number":"1","answer":"A","spokenText":"First answer."}]}""").problems.single().page)
    }
    @Test fun modelLocalizedAnnouncementsOverrideTheAppLanguage() {
        val solution = SolutionParser.parse("""{"problems":[{"number":"1","page":1,"question":"Co to jest komórka?","answer":"Komórka.","spokenText":"Komórka jest podstawową jednostką życia.","spokenLabel":"Strona pierwsza. Zadanie pierwsze."}]}""")
        assertEquals("Strona pierwsza. Zadanie pierwsze. Komórka jest podstawową jednostką życia.", solution.speech(false, "en"))
        val spanish = solution.copy(problems = listOf(solution.problems.single().copy(spokenLabel = "Página uno. Pregunta uno.", spokenText = "Una célula.")))
        assertEquals("Página uno. Pregunta uno. Una célula.", spanish.speech(false, "pl"))
    }

    @Test fun typedLanguageOverridesPhotoLanguageInSolveAndRepairInstructions() {
        for (language in listOf("French", "Spanish", "Japanese", "Brazilian Portuguese")) {
            val prompt = SolutionParser.prompt(language, true)
            assertTrue(prompt.contains("chosen the output language: $language"))
            assertTrue(prompt.contains("Do not detect or choose an output language from the photos"))
            assertFalse(prompt.contains("Detect the language separately"))
            assertTrue(SolutionParser.rewritePrompt(language, true).contains("output language: $language"))
        }
    }

    @Test fun essayRulesPreserveArgumentEvenWithOptionalReasoningOff() {
        for (reasoning in listOf(false,true)) {
            val prompt=SolutionParser.prompt("English",reasoning,30)
            assertTrue(prompt.contains("Distinguish the task genre"))
            assertTrue(prompt.contains("finished piece itself, not an outline"))
            assertTrue(prompt.contains("Preserve them even when optional explanations are disabled"))
            assertTrue(prompt.contains("word count and paragraph count"))
            assertTrue(prompt.contains("Short factual questions, calculations and multiple-choice tasks need concise"))
            assertTrue(prompt.contains("one complete paragraph per segment"))
        }
        assertTrue(SolutionParser.rewritePrompt("English",false).contains("never turn it into a short answer, outline or summary"))
    }
    @Test fun essayParagraphsAndFullConclusionSurviveSpeechCleanup() {
        val essay="A thesis introduces the argument.\n\nThe body develops evidence and explains it.\n\nThe conclusion answers the task."
        val solution=Solution(listOf(Problem("1","",essay,"",essay,spokenLabel="Essay.")))
        assertFalse(SolutionParser.needsSpokenRewrite(solution))
        assertEquals("Essay. $essay",solution.speech(false,"en"))
        assertEquals(essay,SpeechText.clean(essay))
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
        assertEquals("7. Four.", concise.speech(false, "en"))
    }

    @Test fun explanationsUseNaturalSpokenText() {
        assertEquals("7. The answer is four. Add two and two.", SolutionParser.parse(json).speech(true, "en"))
    }

    @Test fun unreadableProblemKeepsRetakeMessage() {
        val solution = Solution(listOf(Problem("3", "", "Please retake this photo.", "", "", true)))
        assertEquals("3. Please retake this photo.", solution.speech(true, "en"))
    }

    @Test fun legacyPolishAnswerHasNoEnglishPrefix() {
        val concise = Solution(listOf(Problem("7", "", "4", "", "Cztery.")))
        assertEquals("7. Cztery.", concise.speech(false, "pl"))
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
        assertEquals("2. In the row Plant cell, under Cell wall, write Present.", solution.speech(false, "en"))
    }

    @Test fun fractionGlyphsAndSlashFractionsAreReadable() {
        assertEquals("Write one half or 1 over 2.", SpeechText.clean("Write ½ or 1/2."))
        assertEquals("1 over 2", SpeechText.clean("\\dfrac{1}{2}"))
        assertEquals("jedna druga i 3 przez 4", SpeechText.clean("½ i 3/4", "pl"))
    }

    @Test fun spokenScriptAlsoReceivesNotationCleanup() {
        val solution = Solution(listOf(Problem("1", "", "ignored", "", "**½**")))
        assertEquals("1. one half", solution.speech(false, "en"))
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
