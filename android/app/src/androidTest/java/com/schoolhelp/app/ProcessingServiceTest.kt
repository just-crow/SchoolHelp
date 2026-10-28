package com.schoolhelp.app

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProcessingServiceTest {
    @Test fun cloudAndLocalPreparationPromoteToForegroundWithoutInvalidType() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val app=context.applicationContext as SchoolHelpApplication
        if(Build.VERSION.SDK_INT>=33)instrumentation.uiAutomation.grantRuntimePermission(context.packageName,android.Manifest.permission.POST_NOTIFICATIONS)
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        withTimeout(15000) {while(!app.visible)delay(50)}
        val manager=context.getSystemService(ActivityManager::class.java)
        fun running()=manager.getRunningServices(100).firstOrNull {it.service.className==ProcessingService::class.java.name}
        for(local in listOf(false,true)) {
            context.stopService(Intent(context,ProcessingService::class.java))
            withTimeout(10000) {while(running()!=null)delay(50)}
            try {
                instrumentation.runOnMainSync {ProcessingService.start(context,audio=local)}
                withTimeout(10000) {while(running()?.foreground!=true)delay(50)}
                assertTrue("Foreground promotion failed for local=$local",running()?.foreground==true)
                // Give asynchronous platform validation time to fail the test if the service is invalid.
                delay(300)
                assertTrue(running()?.foreground==true)
            } finally {
                context.stopService(Intent(context,ProcessingService::class.java))
                withTimeout(10000) {while(running()!=null)delay(50)}
            }
        }
    }
}
