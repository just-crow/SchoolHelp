package com.schoolhelp.app.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

data class SpeechSegment(val text: String, val writingText: String = "", val pauseSeconds: Double? = null)
data class WritingPart(val text: String, val pauseSeconds: Double)

object WritingPauses {
    fun instruction(wpm: Int) = if (wpm <= 0) "" else """
        Writing pauses are enabled at $wpm words per minute. For every problem return speechSegments: an ordered array of {"text":"spoken instructions and answer", "writingText":"only the text the listener must write", "pauseSeconds":null}.
        Each segment must contain plain spoken words in the selected output language. Together its text fields must cover the entire spokenText exactly once; do not put page/question labels in segments. Normally use one segment per short question, followed by a writing pause. For essays, normally use one complete paragraph per segment and include the full paragraph in writingText; preserve the full essay in order, without shortening it or adding repeated Write instructions. User context may override pause grouping. For tables or multi-step answers you may split into manageable entries.
        The app calculates a silent pause from writingText and the writing speed. Do not include instructions, explanations or labels in writingText unless they must be copied. Use an empty writingText for a segment requiring no writing.
        Follow the user's context about pauses and grouping, such as pausing after each row or after the entire question. Set pauseSeconds to an explicit number of real seconds only when requested by the context, including zero for no pause; otherwise use null. Maximum 600 seconds per pause. Do not speak pause announcements. Return at most 100 speech segments in total. Preserve speechSegments when rewriting the answer.
    """.trimIndent()

    fun seconds(text: String, wpm: Int, override: Double?): Double {
        if (override != null && override.isFinite()) return override.coerceIn(0.0, 600.0)
        val count = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*").findAll(text).count()
        return ceil(count * 60.0 / wpm.coerceIn(1, 300)).coerceAtMost(600.0)
    }
    fun plan(solution: Solution, wpm: Int, explanations: Boolean, language: String): List<WritingPart> {
        val result = solution.problems.flatMap { problem ->
            val segments = problem.speechSegments.ifEmpty {
                listOf(SpeechSegment(problem.spokenText.ifBlank { problem.answer + if(explanations) " " + problem.explanation else "" }, if(problem.unreadable) "" else problem.answer))
            }
            val label = problem.spokenLabel.trim().trimEnd('.', ' ').ifBlank { problem.number }
            segments.mapIndexed { index, segment ->
                require(segment.text.isNotBlank()) { "The model returned an empty speech segment. Solve again." }
                require(!SpeechText.hasVisualNotation(segment.text)) { "A speech segment contains visual notation. Solve again." }
                WritingPart((if(index == 0) "$label. " else "") + SpeechText.clean(segment.text, language), seconds(segment.writingText,wpm,segment.pauseSeconds))
            }
        }
        require(result.size in 1..100) { "Too many speech parts. Ask for fewer pauses in context and solve again." }
        require(result.sumOf { it.pauseSeconds } <= 7200) { "Writing pauses exceed two hours. Increase writing speed or request shorter pauses in context." }
        return result
    }
    fun json(segments: List<SpeechSegment>) = JSONArray(segments.map {
        JSONObject().put("text",it.text).put("writingText",it.writingText).put("pauseSeconds",it.pauseSeconds ?: JSONObject.NULL)
    })
}
