package com.schoolhelp.app

import android.app.Application
import com.schoolhelp.app.audio.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class SchoolHelpApplication : Application() {
    var visible = false
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val playback by lazy { PlaybackController(this) { message -> model.error(message) } }
    val sharing by lazy { com.schoolhelp.app.sharing.SharingRepository(this) }
    val model by lazy { AppViewModel(this) }
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.FIREBASE_APP_ID.isNotBlank()) {
            val options=com.google.firebase.FirebaseOptions.Builder().setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setApiKey(BuildConfig.FIREBASE_API_KEY).setProjectId(BuildConfig.FIREBASE_PROJECT_ID).setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID).build()
            if(com.google.firebase.FirebaseApp.getApps(this).isEmpty()) com.google.firebase.FirebaseApp.initializeApp(this,options)
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token.addOnSuccessListener { sharing.storePushToken(it) }
        }
        com.schoolhelp.app.sharing.SharingSyncWorker.schedule(this)
    }
}
