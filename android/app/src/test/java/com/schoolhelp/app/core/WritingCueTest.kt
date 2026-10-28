package com.schoolhelp.app.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.abs

class WritingCueTest {
    @Test fun ticksAreQuietRepeatedAndSpeedCompensatedWithoutExtendingThePause() {
        for(speed in listOf(.25f,.5f,1f,1.4f)) {
            fun peak(start:Double,end:Double)=((start*speed*WritingCue.RATE).toInt() until (end*speed*WritingCue.RATE).toInt()).maxOf {abs(WritingCue.sample(it,speed).toInt())}
            assertTrue(peak(.15,.24) in 100..1200)
            assertTrue(peak(5.15,5.24) in 100..1200)
            assertEquals(0,peak(1.0,2.0))
            val file=File.createTempFile("writing-cue", ".wav")
            try {WritingCue.write(file,6.0,speed);assertEquals(6.0*speed,(file.length()-44)/2.0/WritingCue.RATE,1.0/WritingCue.RATE)}
            finally {file.delete()}
        }
    }
}
