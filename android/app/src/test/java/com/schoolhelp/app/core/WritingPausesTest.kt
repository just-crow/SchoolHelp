package com.schoolhelp.app.core

import org.junit.Assert.*
import org.junit.Test

class WritingPausesTest {
    @Test fun timeCountsOnlyWhatMustBeWrittenAndHonorsOverrides() {
        assertEquals(30.0,WritingPauses.seconds("one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen",30,null),.001)
        assertEquals(12.5,WritingPauses.seconds("many many words",30,12.5),.001)
        assertEquals(0.0,WritingPauses.seconds("words",30,0.0),.001)
        assertEquals(0.0,WritingPauses.seconds("",30,null),.001)
        assertEquals(600.0,WritingPauses.seconds("word",30,900.0),.001)
    }
    @Test fun segmentsKeepOrderAndLabelsWithoutCountingInstructions() {
        val json="""{"problems":[{"number":"1","answer":"A B","spokenText":"Write A then B.","spokenLabel":"Question one.","speechSegments":[{"text":"Write A.","writingText":"A","pauseSeconds":null},{"text":"Write B.","writingText":"B","pauseSeconds":9}]}]}"""
        val solution=SolutionParser.parse(json)
        val restored=SolutionParser.parse("""{"problems":[{"number":"1","answer":"A B","spokenText":"Write A then B.","spokenLabel":"Question one.","speechSegments":${WritingPauses.json(solution.problems[0].speechSegments)}}]}""")
        val plan=WritingPauses.plan(restored,30,false,"en")
        assertEquals(listOf("Question one. Write A.","Write B."),plan.map { it.text })
        assertEquals(listOf(2.0,9.0),plan.map { it.pauseSeconds })
    }
    @Test fun olderAnswersGetOnePauseAfterEachAnswer() {
        val solution=Solution(listOf(Problem("1","","one two three","","Write one two three.")))
        assertEquals(6.0,WritingPauses.plan(solution,30,false,"en").single().pauseSeconds,.001)
    }
}
