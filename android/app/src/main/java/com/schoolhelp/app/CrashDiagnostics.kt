package com.schoolhelp.app

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Local diagnostics only: never stores task text, API keys, or exception messages. */
object CrashDiagnostics {
    private val stage=AtomicReference("starting")
    @Volatile private var installed=false
    @Volatile private var appContext:Context?=null
    fun stage(value:String) {
        stage.set(value.take(100))
        runCatching {appContext?.getSharedPreferences("diagnostics",Context.MODE_PRIVATE)?.edit()?.putString("last-stage",stage.get())?.apply()}
    }
    @Synchronized fun install(context:Context) {
        if(installed)return
        installed=true
        appContext=context.applicationContext
        val previous=Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            record(context,"uncaught",error,thread.name)
            if(previous!=null) previous.uncaughtException(thread,error)
            else {android.os.Process.killProcess(android.os.Process.myPid());kotlin.system.exitProcess(10)}
        }
    }
    fun safeTrace(error:Throwable):String = buildString {
        var cause:Throwable?=error
        repeat(6) {
            val current=cause ?: return@buildString
            appendLine(current.javaClass.name)
            current.stackTrace.take(30).forEach { appendLine("  at $it") }
            cause=current.cause.takeIf { it !== current }
        }
    }.take(20000)
    fun record(context:Context,event:String,error:Throwable,thread:String=Thread.currentThread().name) {
        runCatching {
            val report="SchoolHelp ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n"+
                "Time: ${java.util.Date()}\nDevice: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n"+
                "Stage: ${stage.get()}\nEvent: $event\nThread: $thread\n"+safeTrace(error)
            val directory=File(context.filesDir,"diagnostics").apply {mkdirs()}
            File(directory,if(event=="uncaught") "last-crash.txt" else "last-failure.txt").writeText(report)
        }
    }
    fun report(context:Context):String = buildString {
        appendLine("Current SchoolHelp ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, SDK ${Build.VERSION.SDK_INT}")
        appendLine("Current stage: ${stage.get()}")
        appendLine("Last saved stage: ${context.getSharedPreferences("diagnostics",Context.MODE_PRIVATE).getString("last-stage","unknown")}")
        for(name in listOf("last-crash.txt","last-failure.txt")) {
            val file=File(context.filesDir,"diagnostics/$name")
            if(file.isFile)appendLine("\n"+runCatching {file.readText().take(24000)}.getOrDefault("Report unreadable"))
        }
        if(Build.VERSION.SDK_INT>=30) runCatching {
            val manager=context.getSystemService(ActivityManager::class.java)
            manager.getHistoricalProcessExitReasons(context.packageName,0,10).forEach {
                appendLine("Previous process exit: reason=${it.reason}, status=${it.status}, time=${java.util.Date(it.timestamp)}, PSS=${it.pss} KB")
            }
        }
        appendLine("No question text, photos, API keys or exception messages are included. Reports stay on this device unless you copy them.")
    }
}
