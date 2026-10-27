package com.schoolhelp.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import com.schoolhelp.app.ai.LocalProblemSolver
import com.schoolhelp.app.ai.OpenRouterSolver
import com.schoolhelp.app.ai.RemoteModel
import com.schoolhelp.app.audio.PlaybackController
import com.schoolhelp.app.audio.SpeechGenerator
import com.schoolhelp.app.audio.OpenRouterSpeech
import com.schoolhelp.app.audio.SpeechModel
import com.schoolhelp.app.core.Solution
import com.schoolhelp.app.data.AiProvider
import com.schoolhelp.app.data.AppSettings
import com.schoolhelp.app.data.ImageStore
import com.schoolhelp.app.data.ModelCatalog
import com.schoolhelp.app.data.ModelSpec
import com.schoolhelp.app.data.ModelStore
import com.schoolhelp.app.data.SettingsStore
import com.schoolhelp.app.data.SessionStore
import com.schoolhelp.app.data.SavedSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Screen { HOME, SETTINGS, LISTEN }
data class UiState(
    val screen: Screen = Screen.HOME,
    val sharedListening: Boolean = false,
    val sharedItemId: String? = null,
    val settings: AppSettings = AppSettings(),
    val photos: List<File> = emptyList(),
    val selectedPhoto: Int = 0,
    val solution: Solution? = null,
    val recording: File? = null,
    val recordingSpeed: Float = 1f,
    val savedPositionMs: Long = 0,
    val busy: String? = null,
    val error: String? = null,
    val notice: String? = null,
    val installed: Set<String> = emptySet(),
    val downloading: String? = null,
    val downloadProgress: Float = 0f,
    val downloadStatus: String = "",
    val hasKey: Boolean = false,
    val remoteModels: List<RemoteModel> = emptyList(),
    val loadingModels: Boolean = false,
    val testingKey: Boolean = false,
    val speechModels: List<SpeechModel> = emptyList(),
    val loadingSpeechModels: Boolean = false,
) {
    val image: File? get() = photos.getOrNull(selectedPhoto)
    companion object { const val MAX_PHOTOS = 10 }
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = SettingsStore(application)
    private val models = ModelStore(application)
    val images = ImageStore(application)
    private val local = LocalProblemSolver()
    private val remote = OpenRouterSolver()
    private val speech = SpeechGenerator(models)
    private val remoteSpeech = OpenRouterSpeech()
    private val database = com.schoolhelp.app.data.AppDatabase.get(application)
    private val sessions = SessionStore(application)
    private val restored = sessions.load()
    private val mutable = MutableStateFlow(UiState(settings = preferences.load(), installed = models.installedIds(), hasKey = preferences.hasApiKey(),
        photos = restored.photos, selectedPhoto = restored.selectedPhoto, solution = restored.solution,
        recording = restored.recording, recordingSpeed = restored.recordingSpeed, savedPositionMs = restored.positionMs,
        notice = if(database.records().get("generation-job") != null) {
            if(restored.solution != null) "Speech preparation was interrupted. Retry speech from your saved answers."
            else "Preparation was interrupted. Your photos are saved; solve them again when ready."
        } else null,
        screen = if (restored.listening && restored.recording != null) Screen.LISTEN else Screen.HOME))
    val state = mutable.asStateFlow()
    val playback = (application as SchoolHelpApplication).playback
    private val appScope = (application as SchoolHelpApplication).scope
    val sharing = (application as SchoolHelpApplication).sharing
    private var work: Job? = null
    private var download: Job? = null

    init {
        if (sharing.wasSharedListening()) {
            appScope.launch { openShared() }
        } else if (restored.listening) restored.recording?.let { playback.load(it, restored.recordingSpeed, restored.positionMs) }
    }

    fun saveSession() {
        val current = state.value
        if (current.sharedListening) {
            sharing.savePosition(playback.state.value.positionMs)
            return
        }
        val position = if (playback.hasRecording) playback.state.value.positionMs else current.savedPositionMs
        mutable.update { it.copy(savedPositionMs = position) }
        try {
            sessions.save(SavedSession(current.photos, current.selectedPhoto, current.solution,
                current.recording, current.recordingSpeed,
                position,
                current.screen == Screen.LISTEN))
        } catch (e: Exception) { error("Could not save this session. Free some phone storage and try again.") }
    }

    fun screen(screen: Screen) {
        if (screen != Screen.LISTEN) playback.pause()
        mutable.update { it.copy(screen = screen, error = null, notice = null) }
        saveSession()
    }

    fun settings(value: AppSettings) {
        preferences.save(value)
        mutable.update { it.copy(settings = value) }
    }

    fun saveKey(key: String): Boolean = runCatching { preferences.saveApiKey(key) }.fold(
        onSuccess = {
            mutable.update { it.copy(hasKey = preferences.hasApiKey(), notice = if (key.isBlank()) "API key removed." else "API key saved securely.", error = null) }
            true
        },
        onFailure = {
            error(if (it is IllegalArgumentException) it.message ?: "Invalid API key." else "Your key could not be saved. Please try again.")
            false
        },
    )

    fun testKey() {
        if (state.value.testingKey) return
        appScope.launch {
            mutable.update { it.copy(testingKey = true, error = null, notice = null) }
            try { remote.testKey(preferences.apiKey()); mutable.update { it.copy(notice = "OpenRouter connection is ready.") } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error(e.message ?: "Connection failed.") }
            finally { mutable.update { it.copy(testingKey = false) } }
        }
    }

    fun loadModels() {
        if (state.value.loadingModels) return
        appScope.launch {
            mutable.update { it.copy(loadingModels = true, error = null) }
            try {
                val list = remote.models()
                mutable.update { it.copy(remoteModels = list) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error("Could not load vision models. Check your connection and try again.") }
            finally { mutable.update { it.copy(loadingModels = false) } }
        }
    }

    fun install(spec: ModelSpec) {
        if (download?.isActive == true || work?.isActive == true) return
        download = appScope.launch {
            mutable.update { it.copy(downloading = spec.id, downloadProgress = 0f, downloadStatus = "Starting download", error = null) }
            try {
                models.install(spec) { progress, status -> mutable.update { it.copy(downloadProgress = progress, downloadStatus = status) } }
                mutable.update { it.copy(installed = models.installedIds(), notice = "${spec.title} is ready offline.") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error(e.message ?: "Download failed. Please try again.") }
            finally { mutable.update { it.copy(downloading = null, installed = models.installedIds()) } }
        }
    }

    fun loadSpeechModels() {
        if (state.value.loadingSpeechModels) return
        appScope.launch {
            mutable.update { it.copy(loadingSpeechModels = true, error = null) }
            try {
                val list = remoteSpeech.models()
                mutable.update { it.copy(speechModels = list) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error("Could not load speech models. Check your connection and retry.") }
            finally { mutable.update { it.copy(loadingSpeechModels = false) } }
        }
    }

    fun cancelDownload() { download?.cancel() }
    fun removeModel(spec: ModelSpec) {
        if (work?.isActive == true || download?.isActive == true) return
        appScope.launch {
            withContext(Dispatchers.IO) { models.remove(spec) }
            mutable.update { it.copy(installed = models.installedIds()) }
        }
    }

    fun selectImage(uri: Uri, temporaryCapture: File? = null) = selectImages(listOf(uri), temporaryCapture)

    fun selectImages(uris: List<Uri>, temporaryCapture: File? = null) {
        if (work?.isActive == true || uris.isEmpty()) { temporaryCapture?.delete(); return }
        if (state.value.photos.size + uris.size > UiState.MAX_PHOTOS) {
            temporaryCapture?.delete()
            error("You can add up to ${UiState.MAX_PHOTOS} photos. Remove a page or select fewer photos.")
            return
        }
        work = appScope.launch {
            val imported = mutableListOf<File>()
            mutable.update { it.copy(busy = "Preparing photos", error = null) }
            try {
                for (uri in uris) imported += images.import(uri)
                invalidateAnswer()
                mutable.update { it.copy(photos = it.photos + imported, selectedPhoto = it.photos.size) }
                imported.clear()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error(e.message ?: "Could not prepare the photos.") }
            finally {
                imported.forEach { it.delete() }
                temporaryCapture?.delete()
                saveSession()
                mutable.update { it.copy(busy = null) }
            }
        }
    }

    fun selectPage(index: Int) {
        if (work?.isActive != true && index in state.value.photos.indices) mutable.update { it.copy(selectedPhoto = index) }
        saveSession()
    }

    private fun invalidateAnswer() {
        playback.clear()
        state.value.recording?.delete()
        mutable.update { it.copy(solution = null, recording = null, savedPositionMs = 0, error = null) }
    }

    fun removeImage() {
        if (work?.isActive == true) return
        val snapshot = state.value
        val file = snapshot.image ?: return
        invalidateAnswer()
        val remaining = snapshot.photos.toMutableList().apply { removeAt(snapshot.selectedPhoto) }
        mutable.update { it.copy(photos = remaining, selectedPhoto = snapshot.selectedPhoto.coerceAtMost((remaining.size - 1).coerceAtLeast(0))) }
        file.delete()
        saveSession()
    }

    fun movePage(offset: Int) {
        if (work?.isActive == true) return
        val snapshot = state.value
        val target = snapshot.selectedPhoto + offset
        if (target !in snapshot.photos.indices) return
        invalidateAnswer()
        val reordered = snapshot.photos.toMutableList().apply { add(target, removeAt(snapshot.selectedPhoto)) }
        mutable.update { it.copy(photos = reordered, selectedPhoto = target) }
        saveSession()
    }
    fun rotateImage() { state.value.image?.let { file -> imageWork { images.rotate(file) } } }
    fun cropImage(left: Float, top: Float, right: Float, bottom: Float) {
        state.value.image?.let { file -> imageWork { images.crop(file, left, top, right, bottom) } }
    }

    private fun imageWork(block: suspend () -> File) {
        if (work?.isActive == true) return
        playback.pause()
        work = appScope.launch {
            mutable.update { it.copy(busy = "Preparing photo", error = null) }
            try {
                val image = block()
                val old = state.value.image
                invalidateAnswer()
                mutable.update { state -> state.copy(photos = state.photos.toMutableList().apply { set(state.selectedPhoto, image) }) }
                if (old != image) old?.delete()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error(e.message ?: "Could not prepare the photo.") }
            finally { saveSession(); mutable.update { it.copy(busy = null) } }
        }
    }

    fun clearImage() {
        playback.clear()
        state.value.photos.forEach { it.delete() }
        state.value.recording?.delete()
        mutable.update { it.copy(photos = emptyList(), selectedPhoto = 0, solution = null, recording = null, savedPositionMs = 0, error = null) }
        saveSession()
    }

    fun terminateSession() {
        if (state.value.sharedListening) { nextShared(); return }
        work?.cancel()
        database.records().delete("generation-job")
        clearImage()
        images.clearSessionPhotos()
        mutable.update { it.copy(screen = Screen.HOME, notice = null, recordingSpeed = 1f) }
    }

    fun solve() = solveInternal(false)
    fun retrySpeech() = solveInternal(true)

    private fun solveInternal(reuseSolution: Boolean) {
        if (work?.isActive == true) return
        val snapshot = state.value
        if(snapshot.sharedListening) { error("Return to your own recording before solving new pages."); return }
        val photos = snapshot.photos
        if (photos.isEmpty() && snapshot.settings.context.isBlank() && !(reuseSolution && snapshot.solution != null)) { error("Add a photo or enter context first."); return }
        val cachedSolution = if(reuseSolution) snapshot.solution else null
        val config = snapshot.settings
        if (config.outputLanguage.isBlank()) { error("Enter an answer language before solving."); return }
        if (snapshot.downloading != null) { error("Wait for the model download to finish first."); return }
        if (config.speechProvider == AiProvider.LOCAL && !models.installed(ModelCatalog.voice(config.language))) {
            error("Download the ${ModelCatalog.voice(config.language).title} voice in settings first."); return
        }
        if ((config.provider == AiProvider.OPENROUTER || config.speechProvider == AiProvider.OPENROUTER) && !snapshot.hasKey) {
            error("Save your OpenRouter API key in settings first."); return
        }
        if (config.speechProvider == AiProvider.OPENROUTER && (config.speechModelId.isBlank() || (config.speechVoice.isBlank() && !com.schoolhelp.app.audio.hasDefaultSpeechVoice(config.speechModelId)))) {
            error("Choose an OpenRouter speech model and voice in settings first."); return
        }
        if (cachedSolution == null && config.provider == AiProvider.LOCAL && !models.installed(ModelCatalog.vision)) {
            error("Download Gemma in settings, or connect OpenRouter."); return
        }
        playback.pause()
        database.records().put(com.schoolhelp.app.data.StoredRecord("generation-job","job","{\"stage\":\"solving\"}",System.currentTimeMillis()))
        ProcessingService.start(getApplication())
        work = appScope.launch {
            var output: File? = null
            try {
                playback.clear()
                state.value.recording?.delete()
                mutable.update { it.copy(busy = if (config.provider == AiProvider.LOCAL) "Reading and solving on your phone" else "Reading and solving with OpenRouter", error = null, solution = cachedSolution, recording = null) }
                mutable.update { it.copy(savedPositionMs = 0) }
                saveSession()
                mutable.update { it.copy(busy = if (photos.isEmpty()) "Working on your text request" else "Reading ${photos.size} page${if (photos.size == 1) "" else "s"} together") }
                val solution = cachedSolution ?: if (config.provider == AiProvider.LOCAL)
                    local.solve(models.visionFile(), photos, getApplication<Application>().cacheDir, config)
                else remote.solve(photos, preferences.apiKey(), config)
                mutable.update { it.copy(solution = solution, busy = "Turning answers into speech") }
                saveSession()
                database.records().put(com.schoolhelp.app.data.StoredRecord("generation-job","job","{\"stage\":\"speech\"}",System.currentTimeMillis()))
                ProcessingService.start(getApplication(), audio=config.speechProvider == AiProvider.LOCAL)
                val cloudSpeech = config.speechProvider == AiProvider.OPENROUTER
                output = File(sessions.directory, "answer-${java.util.UUID.randomUUID()}.${if (cloudSpeech) "mp3" else "wav"}")
                val spokenText = solution.speech(config.explanations, config.language)
                if (cloudSpeech) output = remoteSpeech.generate(spokenText, preferences.apiKey(), config, output)
                else speech.generate(spokenText, config.language, config.rate, output)
                state.value.recording?.delete()
                val playbackSpeed = if (cloudSpeech) config.rate else 1f
                try { sharing.snapshot(solution, output, playbackSpeed, config.language, spokenText) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { mutable.update { it.copy(notice="Answers are ready, but sharing could not be queued: ${e.message}") } }
                playback.load(output, playbackSpeed)
                if(!(getApplication<Application>() as SchoolHelpApplication).visible) com.schoolhelp.app.sharing.SharingNotifications.ready(getApplication(), "Your answers are ready", shared=false)
                mutable.update { it.copy(recording = output, recordingSpeed = playbackSpeed, savedPositionMs = 0, screen = Screen.LISTEN) }
            } catch (e: CancellationException) { output?.delete(); throw e }
            catch (e: OutOfMemoryError) { output?.delete(); error("This phone ran out of memory. Close other apps or use OpenRouter.") }
            catch (e: LinkageError) { output?.delete(); error("The local AI engine is not supported on this device. Try a recent 64-bit Android phone.") }
            catch (e: Exception) { output?.delete(); error(e.message ?: "Something went wrong. Please try again.") }
            finally { saveSession(); database.records().delete("generation-job"); mutable.update { it.copy(busy = null) }; ProcessingService.stop(getApplication()) }
        }
    }

    fun cancelWork() { work?.cancel() }
    fun listenAgain() {
        sharing.sharedMode(false)
        mutable.update { it.copy(sharedListening=false, sharedItemId=null) }
        val file = state.value.recording ?: return
        playback.load(file, state.value.recordingSpeed, state.value.savedPositionMs)
        screen(Screen.LISTEN)
    }
    fun sharingAction(action: String, enabled: Boolean = false, member: String = "") {
        appScope.launch { try { sharing.ownerAction(action,enabled,member) } catch(e: Exception) { error(e.message ?: "Sharing failed.") } }
    }
    fun joinSharing(code: String) {
        appScope.launch { try { sharing.join(code); sharing.sync(); openShared() } catch(e: Exception) { error(e.message ?: "Could not connect.") } }
    }
    private var sharingSync: Job? = null
    fun syncSharing() {
        if(sharingSync?.isActive==true) return
        sharingSync = appScope.launch { try { sharing.sync(); if(state.value.screen==Screen.LISTEN && state.value.sharedListening && state.value.sharedItemId==null) openShared() } catch(e: Exception) { error(e.message ?: "Sharing sync failed.") } }
    }
    fun leaveSharing() {
        appScope.launch { try {
            sharing.leave()
            if(state.value.sharedListening) { playback.clear(); mutable.update { it.copy(sharedListening=false,sharedItemId=null,screen=Screen.HOME) } }
        } catch(e: Exception) { error(e.message ?: "Could not disconnect.") } }
    }
    fun openShared() {
        if(!state.value.sharedListening) saveSession()
        val item=sharing.current()
        sharing.sharedMode(true)
        playback.pause()
        mutable.update { it.copy(sharedListening=true, sharedItemId=item?.id, screen=Screen.LISTEN) }
        if(item != null) { playback.load(item.file,item.speed,item.position) }
        else playback.clear()
    }
    fun nextShared() {
        val expected=state.value.sharedItemId
        if(expected!=null) sharing.advance(expected)
        openShared()
        if(!(getApplication<Application>() as SchoolHelpApplication).visible && state.value.settings.readySound && state.value.sharedItemId!=null) playback.playReadySound()
    }
    fun ownWorkspace() {
        sharing.sharedMode(false)
        if(state.value.sharedListening) { sharing.savePosition(playback.state.value.positionMs); playback.clear() }
        mutable.update { it.copy(sharedListening=false,sharedItemId=null,screen=Screen.HOME) }
    }
    fun error(message: String) { mutable.update { it.copy(error = message, notice = null) } }
    fun dismissMessage() { mutable.update { it.copy(error = null, notice = null) } }

    override fun onCleared() {
        saveSession()
        // Playback and active processing belong to the application/services.
        super.onCleared()
    }
}
