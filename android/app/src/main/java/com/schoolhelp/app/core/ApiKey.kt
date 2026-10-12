package com.schoolhelp.app.core

object ApiKey {
    fun normalize(value: String): String {
        // Clipboard transfers can add invisible padding, including a trailing NUL.
        val key = value.trim { it.isWhitespace() || it == '\u0000' || it == '\uFEFF' || it == '\u200B' }
        require(key.isNotEmpty()) { "Enter an OpenRouter API key first." }
        require(key.all { it.code in 33..126 }) {
            "The API key contains a space or hidden character. Copy the key again from OpenRouter and save it."
        }
        return key
    }
}
