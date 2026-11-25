package com.schoolhelp.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ManualSpeechSegmentsTest {
    private val essay = Problem(
        number = "1", question = "", answer = "", explanation = "",
        spokenText = "First sentence. Second sentence.\n\nFinal paragraph.",
        spokenLabel = "Question one",
    )

    @Test fun followsCustomBreaksAcrossParagraphsWithoutUsingDurations() {
        val problem = essay.copy(speechSegments = listOf(
            SpeechSegment("First sentence.", pauseSeconds = 7.0),
            SpeechSegment("Second sentence. Final paragraph."),
        ))
        assertEquals(
            listOf("Question one. First sentence.", "Second sentence. Final paragraph."),
            Solution(listOf(problem)).manualSpeechSegments(false, "en"),
        )
    }

    @Test fun incompleteSegmentsFallBackToFullParagraphs() {
        val problem = essay.copy(speechSegments = listOf(SpeechSegment("First sentence.")))
        assertEquals(
            listOf("Question one. First sentence. Second sentence.", "Final paragraph."),
            Solution(listOf(problem)).manualSpeechSegments(false, "en"),
        )
    }
}
