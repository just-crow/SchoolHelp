package com.schoolhelp.app

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.schoolhelp.app.core.PlaybackAction
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var originalSettings: com.schoolhelp.app.data.AppSettings
    @Before fun preserveSettings() {
        originalSettings = com.schoolhelp.app.data.SettingsStore(compose.activity).load()
        compose.runOnUiThread { ViewModelProvider(compose.activity)[AppViewModel::class.java].terminateSession() }
    }
    @After fun restoreSettings() {
        compose.runOnUiThread {
            ViewModelProvider(compose.activity)[AppViewModel::class.java].apply { terminateSession(); settings(originalSettings) }
        }
    }

    @Test fun playbackContinuesWhenActivityStopsAndMediaControlsStillSeek() {
        lateinit var model: AppViewModel
        compose.runOnUiThread {
            model=ViewModelProvider(compose.activity)[AppViewModel::class.java]
            model.playback.load(silentRecording())
            model.screen(Screen.LISTEN)
        }
        compose.waitUntil(5000) { model.playback.state.value.ready }
        compose.runOnUiThread { model.playback.act(PlaybackAction.TOGGLE) }
        compose.waitUntil(5000) { model.playback.state.value.playing }
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        Thread.sleep(1200)
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            model.playback.refresh()
            assertTrue(model.playback.state.value.playing)
            assertTrue(model.playback.state.value.positionMs > 500)
            model.playback.mediaController!!.transportControls.pause()
        }
        Thread.sleep(300)
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            model.playback.refresh()
            assertFalse(model.playback.state.value.playing)
        }
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
    }

    @Test fun sharedQueueRestoresAndConsumesExactlyOneItemAtATime() {
        val app=compose.activity.application as SchoolHelpApplication
        val db=com.schoolhelp.app.data.AppDatabase.get(app)
        val dao=db.records()
        val folder=File(app.filesDir,"sharing").apply { mkdirs() }
        val solution=org.json.JSONObject("""{"problems":[{"number":"1","answer":"A cell.","spokenText":"Write a cell."}]}""")
        val ids=listOf("queue-test-1","queue-test-2","queue-test-3")
        try {
            dao.clear("incoming"); dao.clear("consumed"); dao.delete("shared-current")
            ids.forEachIndexed { index,id ->
                silentRecording().copyTo(File(folder,"$id.m4a"),overwrite=true)
                dao.put(com.schoolhelp.app.data.StoredRecord(id,"incoming",org.json.JSONObject().put("solution",solution).put("speed",1.0).toString(),index.toLong()))
            }
            val repository=com.schoolhelp.app.sharing.SharingRepository(app)
            assertEquals(ids[0],repository.current()!!.id)
            repository.savePosition(4000)
            assertEquals(4000L,com.schoolhelp.app.sharing.SharingRepository(app).current()!!.position)
            assertEquals(ids[1],repository.advance(ids[0])!!.id)
            assertEquals(ids[1],repository.advance(ids[0])!!.id) // duplicate action cannot skip the next answer
            assertFalse(File(folder,"${ids[0]}.m4a").exists())
            assertEquals(ids[2],repository.advance(ids[1])!!.id)
            assertNull(repository.advance(ids[2]))
        } finally {
            dao.clear("incoming"); dao.clear("consumed"); dao.delete("shared-current")
            ids.forEach { File(folder,"$it.m4a").delete() }
        }
    }

    @Test fun sharingAudioExportsAsAacAndKeepsOriginalRecording() {
        val source=silentRecording()
        val output=File(compose.activity.cacheDir,"shared-export-test.m4a")
        output.delete()
        kotlinx.coroutines.runBlocking {
            com.schoolhelp.app.sharing.AudioExport.encode(compose.activity,source,output)
        }
        assertTrue(source.exists())
        assertTrue(output.length()>0)
        val extractor=android.media.MediaExtractor()
        try {
            extractor.setDataSource(output.absolutePath)
            assertEquals("audio/mp4a-latm",extractor.getTrackFormat(0).getString(android.media.MediaFormat.KEY_MIME))
        } finally { extractor.release(); output.delete() }
    }

    @Test fun legacySessionMigratesIntoRoomWithoutRemovingRecording() {
        val app=compose.activity.application
        val sessions=com.schoolhelp.app.data.SessionStore(app)
        val dao=com.schoolhelp.app.data.AppDatabase.get(app).records()
        val audio=silentRecording().copyTo(File(sessions.directory,"legacy-migration.wav"),overwrite=true)
        try {
            sessions.save(com.schoolhelp.app.data.SavedSession(recording=audio,positionMs=5500,listening=true))
            dao.delete("session") // Simulate the legacy JSON-only installation.
            val restored=sessions.load()
            assertEquals(audio,restored.recording)
            assertEquals(5500L,restored.positionMs)
            assertNotNull(dao.get("session"))
            assertTrue(audio.exists())
        } finally { sessions.save(com.schoolhelp.app.data.SavedSession()); audio.delete() }
    }

    @Test fun publicationSnapshotSurvivesDeletingOwnersOriginalRecording() {
        val app=compose.activity.application as SchoolHelpApplication
        val dao=com.schoolhelp.app.data.AppDatabase.get(app).records()
        val previous=dao.get("sharing-config")
        val before=dao.list("outbox").map { it.id }.toSet()
        val folder=File(app.filesDir,"sharing")
        try {
            dao.put(com.schoolhelp.app.data.StoredRecord("sharing-config","config","{\"enabled\":true}",0))
            val source=silentRecording()
            val solution=com.schoolhelp.app.core.Solution(listOf(com.schoolhelp.app.core.Problem("1","Name a gas.","Oxygen.","","Write oxygen.")))
            kotlinx.coroutines.runBlocking { app.sharing.snapshot(solution,source,1f,"en","Write oxygen.") }
            source.delete()
            val queued=dao.list("outbox").single { it.id !in before }
            val json=org.json.JSONObject(queued.payload)
            assertTrue(File(folder,json.getString("source")).length()>0)
            assertEquals("Oxygen.",json.getJSONObject("solution").getJSONArray("problems").getJSONObject(0).getString("answer"))
        } finally {
            dao.list("outbox").filter { it.id !in before }.forEach { File(folder,org.json.JSONObject(it.payload).getString("source")).delete(); dao.delete(it.id) }
            if(previous!=null) dao.put(previous) else dao.delete("sharing-config")
        }
    }

    @Test fun playbackContinuesWithScreenActuallyOff() {
        lateinit var model: AppViewModel
        compose.runOnUiThread {
            model=ViewModelProvider(compose.activity)[AppViewModel::class.java]
            model.settings(model.state.value.settings.copy(readySound=false))
            model.playback.load(silentRecording()); model.screen(Screen.LISTEN)
        }
        compose.waitUntil(5000) { model.playback.state.value.ready }
        compose.runOnUiThread { model.playback.act(PlaybackAction.TOGGLE) }
        compose.waitUntil(5000) { model.playback.state.value.playing }
        val automation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String) { android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() } }
        try {
            shell("input keyevent 223")
            Thread.sleep(1500)
            assertFalse(compose.activity.getSystemService(android.os.PowerManager::class.java).isInteractive)
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                model.playback.refresh()
                assertTrue(model.playback.state.value.playing)
                assertTrue(model.playback.state.value.positionMs>1000)
            }
        } finally { shell("input keyevent 224"); shell("wm dismiss-keyguard"); Thread.sleep(500) }
    }

    @Test fun homeAndSettingsAreUsable() {
        compose.onNodeWithText("Your questions.\nA voice to help.").assertIsDisplayed()
        screenshot("home")
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Your setup").assertIsDisplayed()
        compose.onNodeWithText("OpenRouter", useUnmergedTree = true).performClick()
        compose.onNode(hasText("OpenRouter API key", substring = true) or hasText("Replace saved API key")).assertExists()
        compose.onNodeWithText("On this phone").performClick()
        screenshot("settings")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Take a photo").assertIsDisplayed()
    }

    @Test fun multiplePhotosCanBeAddedEditedOrderedRemovedAndCleared() {
        lateinit var model: AppViewModel
        val source = File(compose.activity.cacheDir, "multi-source.png")
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val uri = android.net.Uri.fromFile(source)
        compose.runOnUiThread {
            model = ViewModelProvider(compose.activity)[AppViewModel::class.java]
            model.selectImages(listOf(uri, uri))
        }
        compose.waitUntil(5000) { model.state.value.photos.size == 2 && model.state.value.busy == null }
        val first = model.state.value.photos.first()
        val second = model.state.value.photos.last()
        assertNotEquals(first, second)
        compose.onNodeWithText("Page 1 of 2").assertExists()
        compose.onNodeWithText("Next", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("Page 2 of 2").assertExists()
        compose.runOnUiThread { model.movePage(-1) }
        assertEquals(second, model.state.value.photos.first())
        compose.runOnUiThread { model.rotateImage() }
        compose.waitUntil(5000) { model.state.value.busy == null && model.state.value.image != second }
        val rotated = model.state.value.image!!
        assertFalse(second.exists())
        assertEquals(40, android.graphics.BitmapFactory.decodeFile(rotated.path).let { image -> image.width.also { image.recycle() } })
        compose.runOnUiThread { model.selectImage(uri) }
        compose.waitUntil(5000) { model.state.value.photos.size == 3 && model.state.value.busy == null }
        val added = model.state.value.image!!
        compose.runOnUiThread { model.removeImage() }
        assertFalse(added.exists())
        assertEquals(listOf(rotated, first), model.state.value.photos)
        compose.runOnUiThread { model.selectImages(List(UiState.MAX_PHOTOS) { uri }) }
        assertEquals(2, model.state.value.photos.size)
        assertNotNull(model.state.value.error)
        compose.runOnUiThread {
            model.playback.load(silentRecording())
            model.screen(Screen.LISTEN)
        }
        compose.waitUntil(10000) { model.playback.state.value.ready }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle {
            assertEquals(Screen.HOME, model.state.value.screen)
            assertEquals(listOf(rotated, first), model.state.value.photos)
            assertTrue(rotated.exists() && first.exists())
            assertEquals(model.state.value.photos, com.schoolhelp.app.data.SessionStore(compose.activity).load().photos)
        }
        compose.runOnUiThread { model.terminateSession() }
        assertTrue(model.state.value.photos.isEmpty())
        assertFalse(first.exists())
        assertFalse(rotated.exists())
        assertTrue(source.exists())
        source.delete()
    }

    @Test fun listeningStartsPausedAndKeepsScreenAwakeUntilExit() {
        lateinit var model: AppViewModel
        var originalBrightness = 0f
        compose.runOnUiThread {
            model = ViewModelProvider(compose.activity)[AppViewModel::class.java]
            model.settings(model.state.value.settings.copy(readySound = false, provider = com.schoolhelp.app.data.AiProvider.LOCAL))
            originalBrightness = compose.activity.window.attributes.screenBrightness
            model.playback.load(silentRecording())
            model.screen(Screen.LISTEN)
        }
        compose.waitUntil(10000) { model.playback.state.value.ready }
        compose.runOnIdle {
            assertFalse(model.playback.state.value.playing)
            assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        }
        screenshot("listening")
        // TalkBack activation bypasses tap counting, as intended.
        compose.onNode(hasClickAction() and hasContentDescription("Listening mode.", substring = true))
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(5000) { model.playback.state.value.playing }
        compose.runOnUiThread { model.playback.act(PlaybackAction.TOGGLE) }
        compose.runOnIdle { assertFalse(model.playback.state.value.playing) }
        compose.runOnUiThread { model.screen(Screen.HOME) }
        compose.onNodeWithText("Take a photo").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            assertEquals(originalBrightness, compose.activity.window.attributes.screenBrightness, .001f)
        }
    }

    @Test fun physicalTripleTapTogglesAndDoubleTapOnlySeeks() {
        lateinit var model: AppViewModel
        compose.runOnUiThread {
            model = ViewModelProvider(compose.activity)[AppViewModel::class.java]
            model.settings(model.state.value.settings.copy(readySound = false, tapGapMs = 300))
            model.playback.load(silentRecording())
            model.screen(Screen.LISTEN)
        }
        compose.waitUntil(10000) { model.playback.state.value.ready }
        val button = compose.onNode(hasClickAction() and hasContentDescription("Listening mode.", substring = true))
        button.performTouchInput { repeat(3) { click(); advanceEventTime(70) } }
        compose.waitUntil(5000) { model.playback.state.value.playing }
        button.performTouchInput { repeat(3) { click(); advanceEventTime(70) } }
        compose.waitUntil(5000) { !model.playback.state.value.playing }
        button.performTouchInput { repeat(2) { click(); advanceEventTime(70) } }
        compose.waitUntil(5000) { model.playback.state.value.positionMs >= 5000 }
        compose.runOnIdle { assertFalse(model.playback.state.value.playing) }
        button.performTouchInput { click() }
        compose.waitUntil(5000) { model.playback.state.value.positionMs < 1000 && model.playback.state.value.ready }
        button.performTouchInput { down(center) }
        compose.waitUntil(3000) { model.playback.state.value.playing }
        button.performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(Screen.LISTEN, model.state.value.screen)
            assertTrue(model.playback.state.value.positionMs < 1000)
        }
        // A released restart hold must not leave the termination timer running.
        Thread.sleep(1700)
        compose.runOnIdle { assertEquals(Screen.LISTEN, model.state.value.screen) }
    }

    @Test fun holdingThreeSecondsEndsSessionAndDeletesImportedPhoto() {
        lateinit var model: AppViewModel
        val source = File(compose.activity.cacheDir, "hold-test-source.png")
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        lateinit var imported: File
        var originalBrightness = 0f
        compose.runOnUiThread {
            model = ViewModelProvider(compose.activity)[AppViewModel::class.java]
            model.settings(model.state.value.settings.copy(readySound = false))
            model.selectImage(android.net.Uri.fromFile(source))
        }
        compose.waitUntil(5000) { model.state.value.image != null && model.state.value.busy == null }
        compose.runOnUiThread {
            imported = model.state.value.image!!
            originalBrightness = compose.activity.window.attributes.screenBrightness
            model.playback.load(silentRecording())
            model.screen(Screen.LISTEN)
        }
        compose.waitUntil(10000) { model.playback.state.value.ready }
        val button = compose.onNode(hasClickAction() and hasContentDescription("Listening mode.", substring = true))
        button.performTouchInput { down(center) }
        compose.waitUntil(3000) { model.playback.state.value.playing }
        compose.runOnIdle { assertEquals(Screen.LISTEN, model.state.value.screen); assertTrue(imported.exists()) }
        compose.waitUntil(4000) { model.state.value.screen == Screen.HOME }
        compose.onRoot().performTouchInput { up() }
        compose.runOnIdle {
            assertNull(model.state.value.image)
            assertNull(model.state.value.solution)
            assertNull(model.state.value.recording)
            assertFalse(imported.exists())
            assertTrue(source.exists())
            assertFalse(model.playback.state.value.playing)
            assertEquals(0, compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            assertEquals(originalBrightness, compose.activity.window.attributes.screenBrightness, .001f)
        }
        source.delete()
        compose.onNodeWithText("Take a photo").assertIsDisplayed()
    }

    @Test fun bluetoothMediaCommandsControlSpeechAndDeactivateWhenCleared() {
        lateinit var model: AppViewModel
        compose.runOnUiThread {
            model = ViewModelProvider(compose.activity)[AppViewModel::class.java]
            model.settings(model.state.value.settings.copy(readySound = false))
            model.playback.load(silentRecording())
            model.screen(Screen.LISTEN)
        }
        compose.waitUntil(10000) { model.playback.state.value.ready }
        fun media(command: String): String {
            val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            return android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("cmd media_session $command"))
                .bufferedReader().use { it.readText() }
        }
        compose.waitUntil(10000) { media("list-sessions").contains("SchoolHelp") }
        media("dispatch next")
        compose.waitUntil(5000) { model.playback.state.value.positionMs == 5000L }
        compose.runOnIdle { assertFalse(model.playback.state.value.playing) }
        media("dispatch previous")
        compose.waitUntil(5000) { model.playback.state.value.positionMs == 0L }
        media("dispatch play-pause")
        compose.waitUntil(5000) { model.playback.state.value.playing }
        media("dispatch pause")
        compose.waitUntil(5000) { !model.playback.state.value.playing }
        media("dispatch play")
        compose.waitUntil(5000) { model.playback.state.value.playing }
        compose.runOnUiThread { model.terminateSession() }
        compose.runOnIdle { assertFalse(model.playback.state.value.playing) }
        compose.waitUntil(10000) { !media("list-sessions").contains("SchoolHelp") }
    }

    @Test fun foregroundHeadsetKeysStartPausedRecordingAndDoNotDoubleToggle() {
        lateinit var model: AppViewModel
        compose.runOnUiThread {
            model = ViewModelProvider(compose.activity)[AppViewModel::class.java]
            model.settings(model.state.value.settings.copy(readySound = false))
            model.playback.load(silentRecording())
            model.screen(Screen.LISTEN)
        }
        compose.waitUntil(10000) { model.playback.state.value.ready && compose.activity.mediaController != null && model.playback.mediaController?.sessionToken == compose.activity.mediaController?.sessionToken }
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        fun press(code: Int) {
            instrumentation.sendKeySync(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
            instrumentation.sendKeySync(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
        }
        press(android.view.KeyEvent.KEYCODE_HEADSETHOOK)
        compose.waitUntil(5000) { model.playback.state.value.playing }
        press(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        compose.waitUntil(5000) { !model.playback.state.value.playing }
        press(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
        compose.waitUntil(5000) { model.playback.state.value.positionMs >= 5000 }
        compose.runOnIdle { assertFalse(model.playback.state.value.playing) }
        press(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        compose.waitUntil(5000) { model.playback.state.value.positionMs < 1000 && model.playback.state.value.ready }
        val downTime = android.os.SystemClock.uptimeMillis()
        compose.runOnUiThread {
            model.playback.handleMediaKey(android.view.KeyEvent(downTime, downTime, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0))
            model.playback.handleMediaKey(android.view.KeyEvent(downTime, downTime + 30, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 1))
            model.playback.handleMediaKey(android.view.KeyEvent(downTime, downTime + 45, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0))
            model.playback.handleMediaKey(android.view.KeyEvent(downTime, downTime + 60, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0))
        }
        compose.waitUntil(5000) { model.playback.state.value.playing }
        compose.onNodeWithText("Earbuds: Play / pause", useUnmergedTree = true).assertExists()
        compose.runOnUiThread {
            model.terminateSession()
            assertFalse(model.playback.handleMediaKey(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)))
            assertNull(model.playback.state.value.earbudAction)
        }
    }

    @Test fun sessionRestoresPhotosJoinedTranscriptionRecordingAndPositionIntoFreshViewModel() {
        lateinit var original: AppViewModel
        val app = compose.activity.application
        val source = File(compose.activity.cacheDir, "restore-source.png")
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        compose.runOnUiThread {
            original = ViewModelProvider(compose.activity)[AppViewModel::class.java]
            original.selectImages(List(2) { android.net.Uri.fromFile(source) })
        }
        compose.waitUntil(5000) { original.state.value.photos.size == 2 && original.state.value.busy == null }
        val sessions = com.schoolhelp.app.data.SessionStore(app)
        val photos = original.state.value.photos
        compose.runOnIdle { assertEquals(photos, sessions.load().photos) }
        val recording = silentRecording().copyTo(File(sessions.directory, "restore-recording.wav"), overwrite = true)
        val solution = com.schoolhelp.app.core.Solution(listOf(com.schoolhelp.app.core.Problem(
            "6", "Question begins on page one and continues on page two.", "Oxygen.", "", "Write oxygen.", page = 1)))
        sessions.save(com.schoolhelp.app.data.SavedSession(photos, 1, solution, recording, 1.25f, 5000, true))
        val owner = androidx.lifecycle.ViewModelStore()
        lateinit var restored: AppViewModel
        compose.runOnUiThread {
            restored = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[AppViewModel::class.java]
            assertEquals(photos, restored.state.value.photos)
            assertEquals(1, restored.state.value.selectedPhoto)
            assertEquals(solution, restored.state.value.solution)
            assertEquals(recording, restored.state.value.recording)
            assertEquals(1.25f, restored.state.value.recordingSpeed)
            assertEquals(Screen.LISTEN, restored.state.value.screen)
        }
        compose.waitUntil(10000) { restored.playback.state.value.ready }
        compose.runOnUiThread {
            assertFalse(restored.playback.state.value.playing)
            assertEquals(5000L, restored.playback.state.value.positionMs)
            restored.playback.act(PlaybackAction.FORWARD)
            restored.saveSession()
            assertEquals(10000L, sessions.load().positionMs)
            restored.terminateSession()
            assertNull(sessions.load().solution)
            assertNull(sessions.load().recording)
            assertTrue(sessions.load().photos.isEmpty())
            assertFalse(recording.exists())
            assertTrue(photos.none { it.exists() })
            assertTrue(source.exists())
            owner.clear()
        }
        source.delete()
    }

    @Test fun readyChimePlaysToCompletionWithoutStartingAnswersAndCanBePreviewed() {
        lateinit var model: AppViewModel
        compose.runOnUiThread {
            model = ViewModelProvider(compose.activity)[AppViewModel::class.java]
            model.settings(model.state.value.settings.copy(readySound = true))
            model.playback.load(silentRecording())
            model.screen(Screen.LISTEN)
        }
        compose.waitUntil(10000) { model.playback.state.value.readySoundPlaying }
        compose.runOnIdle {
            assertFalse(model.playback.state.value.playing)
            assertEquals(0L, model.playback.state.value.positionMs)
        }
        compose.waitUntil(5000) { !model.playback.state.value.readySoundPlaying }
        compose.runOnUiThread { model.playback.refresh() }
        compose.runOnIdle { assertFalse(model.playback.state.value.readySoundPlaying); assertFalse(model.playback.state.value.playing) }
        compose.runOnUiThread { model.screen(Screen.SETTINGS) }
        compose.onNodeWithText("Test ready sound").performScrollTo().performClick()
        compose.waitUntil(5000) { model.playback.state.value.readySoundPlaying }
        compose.runOnUiThread { model.playback.act(PlaybackAction.TOGGLE) }
        compose.waitUntil(5000) { model.playback.state.value.playing }
        compose.runOnIdle { assertFalse(model.playback.state.value.readySoundPlaying) }
        compose.runOnUiThread {
            model.playback.pause()
            model.settings(model.state.value.settings.copy(readySound = false))
            model.screen(Screen.LISTEN)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(model.playback.state.value.readySoundPlaying); assertFalse(model.playback.state.value.playing) }
    }

    private fun silentRecording(): File {
        val samples = 16000 * 30
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(samples * 2).array()
        return File(compose.activity.cacheDir, "test-silence.wav").also { file ->
            file.outputStream().use { it.write(header); it.write(ByteArray(samples * 2)) }
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val folder = File(compose.activity.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
