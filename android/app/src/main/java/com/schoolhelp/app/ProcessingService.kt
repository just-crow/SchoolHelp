package com.schoolhelp.app

import android.app.*
import android.content.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** Keeps work explicitly started by the user alive while the screen is locked. */
class ProcessingService : Service() {
    private var lock: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "cancel") {
            (application as SchoolHelpApplication).model.cancelWork()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("processing", "Preparing answers", NotificationManager.IMPORTANCE_LOW))
        val cancel = PendingIntent.getService(this, 2, Intent(this, ProcessingService::class.java).setAction("cancel"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "processing")
            .setSmallIcon(com.schoolhelp.app.R.drawable.ic_launcher).setContentTitle("Preparing your answers")
            .setContentText("Reading pages and generating speech").setOngoing(true).setContentIntent(open)
            .addAction(0, "Cancel", cancel).build()
        val type = if (Build.VERSION.SDK_INT >= 35 && intent?.getBooleanExtra("audio",false)==true) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        // Core 1.17 ServiceCompat masks flags to Android 14 types, dropping mediaProcessing to zero.
        if (Build.VERSION.SDK_INT >= 35) startForeground(102,notification,type)
        else androidx.core.app.ServiceCompat.startForeground(this,102,notification,type)
        if (lock == null) lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SchoolHelp:processing").apply { acquire(30 * 60 * 1000L) }
        return START_NOT_STICKY
        } catch (e: Exception) {
            stopSelf()
            CrashDiagnostics.record(this,"processing-service",e)
            val model=(application as SchoolHelpApplication).model
            model.error("Android could not keep preparation running. Keep SchoolHelp open and retry. Your answers are saved.")
            model.cancelWork()
            return START_NOT_STICKY
        }
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
        val model=(application as SchoolHelpApplication).model
        model.cancelWork()
        model.error("Android stopped background preparation after its time limit. Reopen the app and retry from your saved answers.")
    }
    override fun onDestroy() {
        runCatching {lock?.let {if(it.isHeld)it.release()}}.onFailure {CrashDiagnostics.record(this,"processing-wake-lock-release",it)}
        lock=null
        super.onDestroy()
    }
    companion object {
        fun start(context: Context, audio: Boolean = false) { ContextCompat.startForegroundService(context, Intent(context, ProcessingService::class.java).putExtra("audio",audio)) }
        fun stop(context: Context) { context.stopService(Intent(context, ProcessingService::class.java)) }
    }
}
