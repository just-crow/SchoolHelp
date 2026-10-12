package com.schoolhelp.app.sharing

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit
import com.schoolhelp.app.SchoolHelpApplication

class SharingSyncWorker(context: Context, params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val app=applicationContext as SchoolHelpApplication
        val previous=app.sharing.state.value.ready
        return try {
            app.sharing.sync()
            if(app.sharing.state.value.ready>previous && !app.visible) SharingNotifications.ready(applicationContext, silent=app.playback.state.value.playing)
            Result.success()
        } catch(e: kotlinx.coroutines.CancellationException) { throw e }
        catch(e: Exception) { Result.retry() }
    }
    companion object {
        private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        fun enqueue(context: Context, expedited: Boolean = false) {
            val builder=OneTimeWorkRequestBuilder<SharingSyncWorker>().setConstraints(constraints()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS)
            if(expedited && android.os.Build.VERSION.SDK_INT>=31) builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            WorkManager.getInstance(context).enqueueUniqueWork("sharing-sync",ExistingWorkPolicy.APPEND_OR_REPLACE,builder.build())
        }
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("sharing-periodic",ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SharingSyncWorker>(15,TimeUnit.MINUTES).setConstraints(constraints()).build())
        }
    }
}
