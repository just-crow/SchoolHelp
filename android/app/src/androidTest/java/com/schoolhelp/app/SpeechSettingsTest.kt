package com.schoolhelp.app

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import com.schoolhelp.app.audio.SpeechModel
import com.schoolhelp.app.data.AiProvider
import com.schoolhelp.app.data.SettingsStore
import com.schoolhelp.app.ui.SchoolHelpTheme
import com.schoolhelp.app.ui.SettingsScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class SpeechSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun speechModelAndVoiceCanBeChosenIndependentlyAndPersisted() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val preferences = SettingsStore(application)
        val original = preferences.load()
        val store = ViewModelStore()
        val owner = object : ViewModelStoreOwner { override val viewModelStore = store }
        lateinit var model: AppViewModel
        try {
            compose.runOnUiThread {
                model = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(application))[AppViewModel::class.java]
                model.settings(original.copy(provider = AiProvider.LOCAL, speechProvider = AiProvider.OPENROUTER,
                    speechModelId = "", speechVoice = ""))
            }
            compose.setContent {
                val state by model.state.collectAsState()
                SchoolHelpTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        SettingsScreen(state.copy(speechModels = listOf(SpeechModel("test/tts", "Test speech", listOf("voice-a", "voice-b")))), model)
                    }
                }
            }
            compose.onNodeWithText("Choose a speech model").performScrollTo().performClick()
            compose.onNodeWithText("Test speech").performClick()
            compose.onNodeWithText("voice-a").performScrollTo().performClick()
            compose.onNodeWithText("voice-b").performClick()
            val screenshot = File(application.getExternalFilesDir(null), "screenshots/speech-settings.png")
            screenshot.parentFile?.mkdirs()
            screenshot.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
            compose.runOnIdle {
                val saved = preferences.load()
                assertEquals(AiProvider.LOCAL, saved.provider)
                assertEquals(AiProvider.OPENROUTER, saved.speechProvider)
                assertEquals("test/tts", saved.speechModelId)
                assertEquals("voice-b", saved.speechVoice)
            }
            compose.onNodeWithText("Offline voices").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(AiProvider.LOCAL, preferences.load().speechProvider) }
        } finally {
            compose.runOnUiThread { preferences.save(original); store.clear() }
        }
    }
}
