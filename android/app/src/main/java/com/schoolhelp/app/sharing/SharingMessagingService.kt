package com.schoolhelp.app.sharing



import android.app.*

import android.content.*

import androidx.core.app.NotificationCompat

import com.google.firebase.messaging.FirebaseMessagingService

import com.google.firebase.messaging.RemoteMessage

import com.schoolhelp.app.*



class SharingMessagingService: FirebaseMessagingService() {

    override fun onNewToken(token: String) { (application as SchoolHelpApplication).sharing.storePushToken(token) }

    override fun onMessageReceived(message: RemoteMessage) {

        // No answer content or credentials are carried in push payloads.

        SharingNotifications.ready(this, "New shared transcription. Downloading audio…", silent=true)

        SharingSyncWorker.enqueue(this, expedited=true)

    }

}

object SharingNotifications {

    fun ready(context: Context, text: String = "Your transcription is ready to listen", silent: Boolean = false, shared: Boolean = true) {

        val manager=context.getSystemService(NotificationManager::class.java)

        val settings=com.schoolhelp.app.data.SettingsStore(context).load()

        val channelId=if(settings.readySound && !silent) "shared-ready-sound" else "shared-ready-silent"

        val channel=NotificationChannel(channelId, "Shared answers ready" + if(settings.readySound && !silent) "" else " (silent)", NotificationManager.IMPORTANCE_DEFAULT)

        channel.setSound(if(settings.readySound && !silent) android.net.Uri.parse("android.resource://${context.packageName}/${R.raw.ready_chime}") else null,

            android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION).build())

        manager.createNotificationChannel(channel)

        val open=PendingIntent.getActivity(context,if(shared) 3 else 4,Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("shared",shared).putExtra("ownReady",!shared),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        if(android.os.Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) return

        manager.notify(103,NotificationCompat.Builder(context,channelId).setSmallIcon(R.drawable.ic_launcher)

            .setContentTitle("SchoolHelp").setContentText(text).setContentIntent(open).setAutoCancel(true).build())

    }

}

