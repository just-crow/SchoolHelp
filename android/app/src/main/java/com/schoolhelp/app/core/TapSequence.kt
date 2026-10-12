package com.schoolhelp.app.core

enum class PlaybackAction { REWIND, FORWARD, TOGGLE }

/** Pure timing logic. The UI schedules flush after the last tap's deadline. */
class TapSequence(val gapMs: Long = 300L) {
    init { require(gapMs in 150L..700L) }
    private var count = 0
    private var lastTap = 0L

    fun tap(nowMs: Long): List<PlaybackAction> {
        val actions = mutableListOf<PlaybackAction>()
        flush(nowMs)?.let(actions::add)
        count++
        lastTap = nowMs
        if (count == 3) {
            actions += PlaybackAction.TOGGLE
            cancel()
        }
        return actions
    }

    fun flush(nowMs: Long): PlaybackAction? {
        if (count == 0 || nowMs - lastTap < gapMs) return null
        return finish()
    }

    /** Called by a cancellable timer after a full quiet interval. */
    fun finish(): PlaybackAction? {
        if (count == 0) return null
        val action = if (count == 1) PlaybackAction.REWIND else PlaybackAction.FORWARD
        cancel()
        return action
    }

    fun cancel() { count = 0 }
}

fun seekPosition(positionMs: Long, deltaMs: Long, durationMs: Long): Long =
    (positionMs + deltaMs).coerceIn(0L, durationMs.coerceAtLeast(0L))
