package com.schoolhelp.app.core

import org.junit.Assert.*
import org.junit.Test

class SpeechSpeedTest {
    @Test fun quarterSpeedIsAppliedOnceForBothProviders() {
        for (rate in listOf(.25f,.3f,.5f,.7f,1f,1.4f)) {
            val synthesis = SpeechSpeed.generation(rate)
            assertTrue(synthesis >= .7f)
            assertEquals(rate, synthesis * SpeechSpeed.playback(rate, false), .0001f)
            assertEquals(rate, SpeechSpeed.playback(rate, true), .0001f)
        }
    }
}
