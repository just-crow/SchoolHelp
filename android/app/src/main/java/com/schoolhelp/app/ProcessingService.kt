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
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("processing", "Preparing answers", NotificationManager.IMPORTANCE_LOW))
        val cancel = PendingIntent.getService(this, 2, Intent(this, ProcessingService::class.java).setAction("cancel"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "processing")
            .setSmallIcon(com.schoolhelp.app.R.drawable.ic_launcher).setContentTitle("Preparing your answers")
            .setContentText("Reading pages and generating speech").setOngoing(true).setContentIntent(open)
            .addAction(0, "Cancel", cancel).build()
        val type = if (Build.VERSION.SDK_INT >= 35 && intent?.getBooleanExtra("audio",false)==true) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        androidx.core.app.ServiceCompat.startForeground(this,102,notification,type)
        if (lock == null) lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SchoolHelp:processing").apply { acquire(30 * 60 * 1000L) }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        (application as SchoolHelpApplication).model.cancelWork()
        stopSelf()
    }
    override fun onDestroy() { lock?.let { if (it.isHeld) it.release() }; super.onDestroy() }
    companion object {
        fun start(context: Context, audio: Boolean = false) { ContextCompat.startForegroundService(context, Intent(context, ProcessingService::class.java).putExtra("audio",audio)) }
        fun stop(context: Context) { context.stopService(Intent(context, ProcessingService::class.java)) }
    }
}
