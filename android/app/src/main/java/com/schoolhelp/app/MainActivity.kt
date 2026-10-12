package com.schoolhelp.app

import android.os.Bundle
import android.media.AudioManager
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import com.schoolhelp.app.ui.SchoolHelpApp
import com.schoolhelp.app.ui.SchoolHelpTheme

class MainActivity : ComponentActivity() {
    private val model: AppViewModel by viewModels {
        object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = (application as SchoolHelpApplication).model as T
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        mediaController = model.playback.mediaController
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                while (true) {
                    mediaController = model.playback.mediaController
                    kotlinx.coroutines.delay(200)
                }
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 20)
        }
        if(intent.getBooleanExtra("shared",false)) model.openShared()
        else if(intent.getBooleanExtra("ownReady",false)) model.listenAgain()
        enableEdgeToEdge()
        setContent {
            SchoolHelpTheme {
                val state by model.state.collectAsStateWithLifecycle()
                val playback by model.playback.state.collectAsStateWithLifecycle()
                SchoolHelpApp(state, playback, model, window)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if(intent.getBooleanExtra("shared",false)) model.openShared()
        else if(intent.getBooleanExtra("ownReady",false)) model.listenAgain()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (model.state.value.screen == Screen.LISTEN && model.playback.handleMediaKey(event)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (model.state.value.screen == Screen.LISTEN && model.playback.handleMediaKey(event)) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onStart() {
        super.onStart()
        (application as SchoolHelpApplication).visible = true
        if(model.sharing.state.value.configured) model.syncSharing()
    }

    override fun onStop() {
        (application as SchoolHelpApplication).visible = false
        model.saveSession()
        super.onStop()
    }
}
