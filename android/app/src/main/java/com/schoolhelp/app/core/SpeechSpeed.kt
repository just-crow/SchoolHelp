package com.schoolhelp.app.core

object SpeechSpeed {
    const val MIN = .25f
    const val MAX = 1.4f
    fun generation(rate: Float) = rate.coerceIn(.7f, MAX)
    fun playback(rate: Float, cloud: Boolean) = if (cloud) rate else rate / generation(rate)
}
