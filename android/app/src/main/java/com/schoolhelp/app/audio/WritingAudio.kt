package com.schoolhelp.app.audio

import android.content.Context
import com.schoolhelp.app.core.WritingPart
import com.schoolhelp.app.sharing.AudioExport
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.io.File

object WritingAudio {
    suspend fun generate(context: Context, parts: List<WritingPart>, speed: Float, output: File, cueSounds: Boolean = true,
        generateClip: suspend (String,File,Int,Int) -> File): File {
        val directory = File(output.parentFile,"writing-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            val clips = parts.mapIndexed { index, part ->
                coroutineContext.ensureActive()
                generateClip(part.text,File(directory,"part-$index.mp3"),index+1,parts.size) to part.pauseSeconds
            }
            AudioExport.combine(context,clips,speed,output,cueSounds)
            return output
        } finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }
}
