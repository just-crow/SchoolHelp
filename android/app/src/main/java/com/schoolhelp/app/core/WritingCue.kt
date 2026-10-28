package com.schoolhelp.app.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Quiet ticks are part of the recording: manual playback pause silences them too. */
object WritingCue {
    const val RATE=8000
    const val VOLUME=.035
    fun sample(frame:Int,speed:Float):Short {
        val realTime=frame.toDouble()/RATE/speed
        if(realTime<.15)return 0
        val phase=(realTime-.15)%5.0
        if(phase>=.09)return 0
        val envelope=sin(Math.PI*phase/.09).pow(2)
        return (sin(2*Math.PI*660*frame/RATE)*envelope*VOLUME*32767).roundToInt().toShort()
    }
    fun write(file:File,seconds:Double,speed:Float,checkCancelled:()->Unit={}) {
        require(seconds>0&&seconds<=600&&speed>0&&speed<=4)
        val frames=(seconds*speed*RATE).roundToInt().coerceAtLeast(1)
        val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36+frames*2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(RATE).putInt(RATE*2).putShort(2).putShort(16).put("data".toByteArray()).putInt(frames*2).array()
        file.outputStream().buffered().use {output->
            output.write(header)
            val buffer=ByteArray(4096)
            var frame=0
            while(frame<frames){
                checkCancelled()
                val count=minOf(buffer.size/2,frames-frame)
                repeat(count){i->val value=sample(frame+i,speed).toInt();buffer[i*2]=value.toByte();buffer[i*2+1]=(value shr 8).toByte()}
                output.write(buffer,0,count*2);frame+=count
            }
        }
    }
}
