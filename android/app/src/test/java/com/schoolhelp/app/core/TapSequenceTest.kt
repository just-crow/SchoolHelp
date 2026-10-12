package com.schoolhelp.app.core

import org.junit.Assert.*
import org.junit.Test

class TapSequenceTest {
    @Test fun singleTapWaitsForTheWholeWindow() {
        val taps = TapSequence(300)
        assertTrue(taps.tap(1000).isEmpty())
        assertNull(taps.flush(1299))
        assertEquals(PlaybackAction.REWIND, taps.flush(1300))
        assertNull(taps.flush(2000))
    }

    @Test fun doubleTapDoesNotAlsoRewind() {
        val taps = TapSequence()
        assertTrue(taps.tap(0).isEmpty())
        assertTrue(taps.tap(150).isEmpty())
        assertNull(taps.flush(300))
        assertEquals(PlaybackAction.FORWARD, taps.flush(450))
        assertNull(taps.flush(800))
    }

    @Test fun tripleTapTogglesWithoutEitherSkip() {
        val taps = TapSequence()
        assertTrue(taps.tap(0).isEmpty())
        assertTrue(taps.tap(120).isEmpty())
        assertEquals(listOf(PlaybackAction.TOGGLE), taps.tap(240))
        assertNull(taps.flush(1000))
    }

    @Test fun separateSlowTapsAreSeparateRewinds() {
        val taps = TapSequence()
        taps.tap(0)
        assertEquals(listOf(PlaybackAction.REWIND), taps.tap(400))
        assertEquals(PlaybackAction.REWIND, taps.flush(700))
    }

    @Test fun holdOrCancelledGestureDiscardsPendingTaps() {
        val taps = TapSequence()
        taps.tap(0); taps.tap(100); taps.cancel()
        assertNull(taps.flush(500))
        assertTrue(taps.tap(600).isEmpty())
        assertEquals(PlaybackAction.REWIND, taps.flush(900))
    }

    @Test fun fourthTapStartsANewSequence() {
        val taps = TapSequence()
        taps.tap(0); taps.tap(80)
        assertEquals(listOf(PlaybackAction.TOGGLE), taps.tap(160))
        assertTrue(taps.tap(240).isEmpty())
        assertEquals(PlaybackAction.REWIND, taps.flush(540))
    }

    @Test fun tapAtDeadlineStartsNewSequence() {
        val taps = TapSequence()
        taps.tap(0)
        assertEquals(listOf(PlaybackAction.REWIND), taps.tap(300))
        assertEquals(PlaybackAction.REWIND, taps.flush(600))
    }

    @Test fun configurableGapAccommodatesSlowerTaps() {
        val taps = TapSequence(600)
        taps.tap(0); taps.tap(500)
        assertEquals(listOf(PlaybackAction.TOGGLE), taps.tap(1000))
    }

    @Test fun seekClampsToRecordingBoundaries() {
        assertEquals(0L, seekPosition(2000, -5000, 9000))
        assertEquals(9000L, seekPosition(7000, 5000, 9000))
        assertEquals(5000L, seekPosition(0, 5000, 9000))
        assertEquals(0L, seekPosition(0, 5000, -1))
    }

    @Test fun deadlineCallbackDoesNotRepeatTheAction() {
        val taps = TapSequence()
        taps.tap(100)
        assertEquals(PlaybackAction.REWIND, taps.finish())
        assertNull(taps.finish())
        taps.tap(200); taps.tap(300); taps.tap(400)
        assertNull(taps.finish())
    }
}
