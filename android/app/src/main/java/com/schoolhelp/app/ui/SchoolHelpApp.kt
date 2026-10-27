package com.schoolhelp.app.ui

import android.graphics.BitmapFactory
import android.os.SystemClock
import android.view.Window
import android.view.WindowManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.schoolhelp.app.AppViewModel
import com.schoolhelp.app.Screen
import com.schoolhelp.app.UiState
import com.schoolhelp.app.audio.PlaybackState
import com.schoolhelp.app.core.PlaybackAction
import com.schoolhelp.app.core.TapSequence
import com.schoolhelp.app.data.AiProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@Composable
fun SchoolHelpApp(state: UiState, playback: PlaybackState, model: AppViewModel, window: Window) {
    val sharing by model.sharing.state.collectAsStateWithLifecycle()
    LaunchedEffect(sharing.following) {
        if (sharing.following.isNotBlank()) while (true) {
            model.syncSharing()
            delay(15000)
        }
    }
    if (state.screen == Screen.LISTEN) {
        ListeningScreen(state, playback, model, window)
        return
    }
    BackHandler(enabled = state.screen != Screen.HOME) { model.screen(Screen.HOME) }
    Surface(Modifier.fillMaxSize(), color = Paper) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state.screen == Screen.SETTINGS) IconButton(onClick = { model.screen(Screen.HOME) }) {
                    Icon(Icons.Outlined.ArrowBack, "Back")
                } else Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Forest), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.GraphicEq, null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Text(if (state.screen == Screen.SETTINGS) "Your setup" else "SchoolHelp", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 10.dp).weight(1f))
                if (state.screen == Screen.HOME) IconButton(onClick = { model.screen(Screen.SETTINGS) }, enabled = state.busy == null) {
                    Icon(Icons.Outlined.Tune, "Settings")
                }
            }
            HorizontalDivider(color = Line)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                MessageBanner(state, model)
                if (state.screen == Screen.SETTINGS) SettingsScreen(state, model)
                else {
                    if(state.sharedListening) {
                        TextButton(onClick=model::openShared) { Text("Return to shared listening") }
                        TextButton(onClick=model::ownWorkspace) { Text("Open my own workspace") }
                    } else HomeScreen(state, model)
                    SharingPanel(model)
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun MessageBanner(state: UiState, model: AppViewModel) {
    val message = state.error ?: state.notice ?: return
    Surface(color = if (state.error != null) Color(0xFFF5E4DD) else Sage, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
            IconButton(onClick = model::dismissMessage) { Icon(Icons.Outlined.Close, "Dismiss message", modifier = Modifier.size(18.dp)) }
        }
    }
}

@Composable
private fun HomeScreen(state: UiState, model: AppViewModel) {
    val image = state.image
    val context = LocalContext.current
    var capturePath by rememberSaveable { mutableStateOf<String?>(null) }
    var crop by remember { mutableStateOf(false) }
    val capture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        capturePath?.let { path ->
            val file = File(path)
            if (success) model.selectImage(FileProvider.getUriForFile(context, "${context.packageName}.files", file), file)
            else file.delete()
        }
        capturePath = null
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(UiState.MAX_PHOTOS)) { uris -> model.selectImages(uris) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Eyebrow("A LITTLE CLARITY, OUT LOUD")
        Text("Your questions.\nA voice to help.", style = MaterialTheme.typography.displayMedium)
        Text("Photograph a problem. Hear the answer,\none step at a time.", color = Muted, style = MaterialTheme.typography.bodyLarge)
    }
    ProviderPill(state)
    OutlinedTextField(value = state.settings.outputLanguage,
        onValueChange = { model.settings(state.settings.copy(outputLanguage = it.take(60))) },
        label = { Text("Answer and speech language") }, placeholder = { Text("Enter a language") },
        supportingText = { Text("All answers and spoken labels use this language. No automatic detection. Changes apply when you solve again.") },
        singleLine = true, enabled = state.busy == null, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = state.settings.context,
        onValueChange = { model.settings(state.settings.copy(context = it.take(8000))) },
        label = { Text("Context for the AI (optional)") },
        placeholder = { Text("Add background, definitions, or instructions for these questions…") },
        supportingText = { Text("Used with your photos when solving. Saved until you clear it. Not read aloud as a separate note.") },
        minLines = 3, maxLines = 8, enabled = state.busy == null, modifier = Modifier.fillMaxWidth())
    if (state.settings.context.isNotEmpty()) TextButton(onClick = { model.settings(state.settings.copy(context = "")) }, enabled = state.busy == null) { Text("Clear context") }
    if (image == null) {
        Surface(shape = RoundedCornerShape(24.dp), color = Sage, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Box(Modifier.size(96.dp).border(1.dp, Forest.copy(alpha = .2f), RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.DocumentScanner, null, tint = Forest, modifier = Modifier.size(48.dp))
                }
                Text("Start with a clear photo", style = MaterialTheme.typography.titleMedium)
                Text("Keep the page flat and make sure all\nthe numbers and symbols are visible.", color = Muted,
                    style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Button(onClick = {
                    try {
                        val file = model.images.captureFile()
                        capturePath = file.path
                        capture.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", file))
                    } catch (e: Exception) { model.error("No camera app is available. Import a photo instead.") }
                }, enabled = state.busy == null, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Outlined.PhotoCamera, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp)); Text("Take a photo")
                }
                TextButton(onClick = { picker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = state.busy == null) {
                    Icon(Icons.Outlined.PhotoLibrary, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp)); Text("Import from photos")
                }
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { model.selectPage(state.selectedPhoto - 1) }, enabled = state.busy == null && state.selectedPhoto > 0) { Text("Previous") }
                Text("Page ${state.selectedPhoto + 1} of ${state.photos.size}", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { model.selectPage(state.selectedPhoto + 1) }, enabled = state.busy == null && state.selectedPhoto < state.photos.lastIndex) { Text("Next") }
            }
            Surface(shape = RoundedCornerShape(20.dp), color = Sage) {
                Photo(image, Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 340.dp).padding(12.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = model::rotateImage, enabled = state.busy == null) { Icon(Icons.Outlined.RotateRight, null); Spacer(Modifier.width(6.dp)); Text("Rotate") }
                TextButton(onClick = { crop = true }, enabled = state.busy == null) { Icon(Icons.Outlined.Crop, null); Spacer(Modifier.width(6.dp)); Text("Crop") }
                TextButton(onClick = model::removeImage, enabled = state.busy == null) { Icon(Icons.Outlined.Close, null); Spacer(Modifier.width(6.dp)); Text("Remove") }
            }
            if (state.photos.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { model.movePage(-1) }, enabled = state.busy == null && state.selectedPhoto > 0) { Text("Move earlier") }
                TextButton(onClick = { model.movePage(1) }, enabled = state.busy == null && state.selectedPhoto < state.photos.lastIndex) { Text("Move later") }
                TextButton(onClick = model::clearImage, enabled = state.busy == null) { Text("Clear all") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = state.busy == null && state.photos.size < UiState.MAX_PHOTOS, modifier = Modifier.weight(1f)) { Text("Add photos") }
                OutlinedButton(onClick = {
                    try {
                        val file = model.images.captureFile()
                        capturePath = file.path
                        capture.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", file))
                    } catch (e: Exception) { model.error("No camera app is available. Import a photo instead.") }
                }, enabled = state.busy == null && state.photos.size < UiState.MAX_PHOTOS, modifier = Modifier.weight(1f)) { Text("Take another") }
            }
            Text("Up to ${UiState.MAX_PHOTOS} photos. Pages are read together in this order, including questions that continue on the next page. Your session is saved on this phone until you clear it.", color = Muted, style = MaterialTheme.typography.bodySmall)
            if (state.settings.provider == AiProvider.OPENROUTER) Text("These photos will be sent to OpenRouter and your selected AI provider.", color = Muted, style = MaterialTheme.typography.bodySmall)
            if (state.settings.speechProvider == AiProvider.OPENROUTER) Text("Answer text will be sent to OpenRouter for speech.", color = Muted, style = MaterialTheme.typography.bodySmall)
            Button(onClick = model::solve, enabled = state.busy == null && state.downloading == null,
                modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Outlined.AutoAwesome, null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text("Solve & prepare speech")
            }
        }
    }
    state.busy?.let { status ->
        Surface(color = Sage, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Forest, trackColor = Line)
                Text(status, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Text("You can cancel while the answer is being prepared.", style = MaterialTheme.typography.bodySmall, color = Muted)
                TextButton(onClick = model::cancelWork) { Text("Cancel") }
            }
        }
    }
    if (state.solution != null && state.busy == null) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Eyebrow("YOUR ANSWERS")
            state.solution.problems.forEach { problem ->
                Column(Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(16.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${problem.page?.let { "Page $it · " }.orEmpty()}${if (problem.unreadable) "Retake needed" else "Question"} ${problem.number}", style = MaterialTheme.typography.labelMedium, color = Forest)
                    if (problem.question.isNotBlank()) Text(problem.question, style = MaterialTheme.typography.bodyMedium, color = Muted)
                    Text(problem.answer, style = MaterialTheme.typography.titleMedium)
                    if (problem.explanation.isNotBlank()) Text(problem.explanation, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if(state.recording == null) OutlinedButton(onClick=model::retrySpeech) { Text("Retry speech from saved answers") }
            if (state.recording != null) OutlinedButton(onClick = model::listenAgain, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Outlined.Headphones, null); Spacer(Modifier.width(10.dp)); Text("Open listening mode")
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        HorizontalDivider(color = Line)
        Eyebrow("LESS LOOKING. MORE LISTENING.")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            GestureHint("1 tap", "−5 seconds")
            GestureHint("2 taps", "+5 seconds")
            GestureHint("3 taps", "Play / pause")
        }
        Text("In listening mode, tap anywhere. Hold 1.5 seconds to restart; keep holding to 3 seconds to end and clear all photos. Your screen stays awake, even when paused.", style = MaterialTheme.typography.bodySmall, color = Muted)
    }
    if (crop && image != null) CropDialog(image, { crop = false }) { l, t, r, b -> model.cropImage(l, t, r, b); crop = false }
}

@Composable
private fun ProviderPill(state: UiState) {
    val configured = state.settings.provider == AiProvider.OPENROUTER && state.hasKey && state.settings.modelId.isNotBlank() ||
        state.settings.provider == AiProvider.LOCAL && "gemma-e2b" in state.installed
    Row(Modifier.clip(CircleShape).background(Sage).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(if (configured) Forest else Color(0xFFB78642), CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(if (state.settings.provider == AiProvider.LOCAL) "On your phone · ${if (configured) "offline AI" else "setup needed"}"
            else "OpenRouter · ${if (configured) "connected" else "setup needed"}", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun Eyebrow(text: String) { Text(text, style = MaterialTheme.typography.labelMedium, color = Forest, letterSpacing = 1.3.sp) }

@Composable
private fun GestureHint(title: String, detail: String, dark: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = if (dark) Cream else Ink)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = if (dark) Cream.copy(alpha = .5f) else Muted)
    }
}

@Composable
internal fun Photo(file: File, modifier: Modifier = Modifier) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, file.path) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }
    }
    bitmap?.let { Image(it, "Selected problem photo", modifier, contentScale = ContentScale.Fit) }
}

@Composable
private fun CropDialog(file: File, dismiss: () -> Unit, apply: (Float, Float, Float, Float) -> Unit) {
    var left by remember { mutableFloatStateOf(0f) }; var top by remember { mutableFloatStateOf(0f) }
    var right by remember { mutableFloatStateOf(1f) }; var bottom by remember { mutableFloatStateOf(1f) }
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, file.path) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Focus on the problem") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            bitmap?.let { image ->
                Box(Modifier.fillMaxWidth().aspectRatio(image.width.toFloat() / image.height)) {
                    Image(image, null, Modifier.fillMaxSize())
                    Canvas(Modifier.fillMaxSize()) {
                        val veil = Color.Black.copy(alpha = .5f)
                        drawRect(veil, size = Size(size.width, size.height * top))
                        drawRect(veil, Offset(0f, size.height * bottom), Size(size.width, size.height * (1 - bottom)))
                        drawRect(veil, Offset(0f, size.height * top), Size(size.width * left, size.height * (bottom - top)))
                        drawRect(veil, Offset(size.width * right, size.height * top), Size(size.width * (1 - right), size.height * (bottom - top)))
                        drawRect(Color.White, Offset(size.width * left, size.height * top), Size(size.width * (right - left), size.height * (bottom - top)), style = Stroke(2.dp.toPx()))
                    }
                }
            }
            Text("Adjust the four edges to keep the question and its symbols.", style = MaterialTheme.typography.bodySmall)
            Text("Left", style = MaterialTheme.typography.labelMedium); Slider(left, { left = it.coerceAtMost(right - .1f) }, valueRange = 0f..0.9f)
            Text("Right", style = MaterialTheme.typography.labelMedium); Slider(right, { right = it.coerceAtLeast(left + .1f) }, valueRange = .1f..1f)
            Text("Top", style = MaterialTheme.typography.labelMedium); Slider(top, { top = it.coerceAtMost(bottom - .1f) }, valueRange = 0f..0.9f)
            Text("Bottom", style = MaterialTheme.typography.labelMedium); Slider(bottom, { bottom = it.coerceAtLeast(top + .1f) }, valueRange = .1f..1f)
        }
    }, confirmButton = { TextButton(onClick = { apply(left, top, right, bottom) }) { Text("Apply crop") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
private fun ListeningScreen(state: UiState, playback: PlaybackState, model: AppViewModel, window: Window) {
    val scope = rememberCoroutineScope()
    val taps = remember(state.settings.tapGapMs) { TapSequence(state.settings.tapGapMs) }
    var pending by remember { mutableStateOf<Job?>(null) }
    var feedback by remember { mutableStateOf("Ready when you are") }
    var sounded by rememberSaveable(state.sharedItemId ?: state.recording?.path) { mutableStateOf(false) }
    val currentPlayback by rememberUpdatedState(playback)
    val sharedQueue by model.sharing.state.collectAsStateWithLifecycle()
    val waiting = state.sharedListening && state.sharedItemId == null
    LaunchedEffect(state.sharedListening, state.sharedItemId, sharedQueue.ready) {
        if(state.sharedListening && state.sharedItemId==null && sharedQueue.ready>0) model.openShared()
    }
    val perform: (PlaybackAction) -> Unit = { action ->
        model.playback.act(action)
        feedback = when (action) {
            PlaybackAction.REWIND -> "Back 5 seconds"
            PlaybackAction.FORWARD -> "Forward 5 seconds"
            PlaybackAction.TOGGLE -> if (currentPlayback.playing) "Paused" else "Playing"
        }
    }
    BackHandler { model.screen(Screen.HOME) }
    DisposableEffect(window) {
        val oldBrightness = window.attributes.screenBrightness
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = .08f }
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            pending?.cancel(); taps.cancel()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply { screenBrightness = oldBrightness }
            insets.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    LaunchedEffect(playback.ready) {
        if (playback.ready && !sounded) {
            sounded = true
            if (state.settings.readySound) {
                model.playback.playReadySound()
            }
        }
    }
    LaunchedEffect(Unit) { while (true) { model.playback.refresh(); delay(200) } }
        BoxWithConstraints(Modifier.fillMaxSize().background(Night)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "Listening mode. ${if (playback.playing) "Playing" else "Paused"}. One tap rewinds five seconds. Two taps skip forward. Three taps play or pause. Hold one and a half seconds to restart. Keep holding to three seconds to finish this item."
                onClick(label = "Play or pause") { perform(PlaybackAction.TOGGLE); true }
                customActions = listOf(
                    CustomAccessibilityAction("Rewind five seconds") { perform(PlaybackAction.REWIND); true },
                    CustomAccessibilityAction("Forward five seconds") { perform(PlaybackAction.FORWARD); true },
                    CustomAccessibilityAction("Restart recording") { model.playback.restart(); true },
                    CustomAccessibilityAction("End session and clear photo") { model.terminateSession(); true },
                )
            }
            .pointerInput(taps) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    pending?.cancel()
                    // A Boolean result distinguishes release/cancellation from timeout.
                    val released = withTimeoutOrNull(1500L) {
                        waitForUpOrCancellation()?.also { it.consume() } != null
                    }
                    if (released == null) {
                        taps.cancel()
                        model.playback.restart()
                        feedback = "Playing from the beginning"
                        val ended = withTimeoutOrNull(1500L) {
                            waitForUpOrCancellation()?.also { it.consume() } != null
                        }
                        if (ended == null) model.terminateSession()
                    } else if (released) {
                        taps.tap(SystemClock.uptimeMillis()).forEach(perform)
                        pending = scope.launch {
                            delay(taps.gapMs)
                            taps.finish()?.let(perform)
                        }
                    } else taps.cancel()
                }
            }) {
            val compact = maxHeight < 580.dp
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(if (compact) 20.dp else 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { model.screen(Screen.HOME) }) {
                        Text("Back", color = Cream)
                    }
                    Text("LISTENING MODE", color = Cream.copy(alpha = .5f), style = MaterialTheme.typography.labelMedium, letterSpacing = 2.sp)
                    Icon(Icons.Outlined.Headphones, null, tint = Cream.copy(alpha = .5f), modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(if (compact) 64.dp else 116.dp).border(1.dp, Cream.copy(alpha = .15f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(if (playback.playing) Icons.Outlined.GraphicEq else Icons.Outlined.PlayArrow, null, tint = Cream, modifier = Modifier.size(48.dp))
                }
                Spacer(Modifier.height(if (compact) 12.dp else 32.dp))
                Text(if (waiting) "Waiting for an answer" else if (!playback.ready) "Getting ready…" else if (playback.ended) "All caught up." else if (playback.playing) "Just listen." else "Take your time.",
                    fontFamily = Editorial, fontSize = if (compact) 28.sp else 38.sp, color = Cream)
                Spacer(Modifier.height(12.dp))
                Text(if (waiting) "New answers will appear here when they are ready." else if (playback.ended) "Triple tap to listen again" else if (playback.playing) "Triple tap anywhere to pause" else "Triple tap anywhere to play",
                    style = MaterialTheme.typography.bodyMedium, color = Cream.copy(alpha = .5f))
                Spacer(Modifier.height(if (compact) 12.dp else 40.dp))
                if (waiting) {
                    Text(sharedQueue.status, color = Cream.copy(alpha = .65f), style = MaterialTheme.typography.bodySmall)
                    state.error?.let { Text(it, color = Cream, style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = model::syncSharing) { Text("Check for answers", color = Cream) }
                } else {
                LinearProgressIndicator(progress = { if (playback.durationMs > 0) (playback.positionMs.toFloat() / playback.durationMs).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth(), color = Color(0xFFB7CAA7), trackColor = Cream.copy(alpha = .1f))
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(time(playback.positionMs), color = Cream.copy(alpha = .5f), style = MaterialTheme.typography.bodySmall)
                    Text(time(playback.durationMs), color = Cream.copy(alpha = .5f), style = MaterialTheme.typography.bodySmall)
                }
                }
                if (!compact) Text(feedback, color = Cream.copy(alpha = .65f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 20.dp))
                playback.earbudAction?.let { action ->
                    Text("Earbuds: $action", color = Cream.copy(alpha = .65f), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
                }
                Spacer(Modifier.weight(1f))
                if (!waiting) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    GestureHint("1 tap", "−5 seconds", true); GestureHint("2 taps", "+5 seconds", true); GestureHint("3 taps", "Play / pause", true)
                }
                HorizontalDivider(Modifier.padding(vertical = if (compact) 12.dp else 24.dp), color = Cream.copy(alpha = .1f))
                Text(if (waiting) "Use Back to return to sharing settings" else "HOLD 1.5 SECONDS TO RESTART · 3 SECONDS TO END", color = Cream.copy(alpha = .4f), style = MaterialTheme.typography.bodySmall,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
}

private fun time(ms: Long): String = String.format(Locale.ROOT, "%d:%02d", ms / 60000, (ms / 1000) % 60)
