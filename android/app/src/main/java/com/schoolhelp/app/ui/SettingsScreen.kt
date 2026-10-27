package com.schoolhelp.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.schoolhelp.app.AppViewModel
import com.schoolhelp.app.UiState
import com.schoolhelp.app.data.AiProvider
import com.schoolhelp.app.data.ModelCatalog
import com.schoolhelp.app.data.ModelSpec

@Composable
internal fun SettingsScreen(state: UiState, model: AppViewModel) {
    var key by remember { mutableStateOf("") }
    var showModels by rememberSaveable { mutableStateOf(false) }
    var showSpeechModels by rememberSaveable { mutableStateOf(false) }
    var showVoices by rememberSaveable { mutableStateOf(false) }
    var remove by remember { mutableStateOf<ModelSpec?>(null) }
    val settings = state.settings
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow("MAKE IT YOURS")
        Text("A quieter way\nto work things out.", style = MaterialTheme.typography.headlineLarge)
        Text("Choose where answers come from and how they sound.", style = MaterialTheme.typography.bodyMedium, color = Muted)
    }
    SettingsSection("Answer engine") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(selected = settings.provider == AiProvider.LOCAL, onClick = { model.settings(settings.copy(provider = AiProvider.LOCAL)) },
                label = { Text("On this phone") }, leadingIcon = { Icon(Icons.Outlined.Smartphone, null, Modifier.size(18.dp)) })
            FilterChip(selected = settings.provider == AiProvider.OPENROUTER, onClick = { model.settings(settings.copy(provider = AiProvider.OPENROUTER)) },
                label = { Text("OpenRouter") }, leadingIcon = { Icon(Icons.Outlined.CloudQueue, null, Modifier.size(18.dp)) })
        }
        Text(if (settings.provider == AiProvider.LOCAL) "Photo solving stays on your device. Download Gemma below to solve offline."
            else "Photos are sent to OpenRouter and your chosen provider. Your own API key and credits are used.",
            style = MaterialTheme.typography.bodyMedium, color = Muted)
        if (settings.provider == AiProvider.OPENROUTER || settings.speechProvider == AiProvider.OPENROUTER) {
            OutlinedTextField(value = key, onValueChange = { key = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                label = { Text(if (state.hasKey) "Replace saved API key" else "OpenRouter API key") },
                supportingText = { Text(if (state.hasKey) "A key is saved in Android's secure storage." else "Your key is encrypted with Android Keystore.") },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (model.saveKey(key)) key = "" }, enabled = key.isNotBlank()) { Text("Save key") }
                OutlinedButton(onClick = model::testKey, enabled = state.hasKey && !state.testingKey) { Text(if (state.testingKey) "Testing…" else "Test connection") }
            }
            if (state.hasKey) TextButton(onClick = { model.saveKey("") }) { Text("Remove saved key") }
        }
        if (settings.provider == AiProvider.OPENROUTER) {
            OutlinedButton(onClick = { showModels = true; if (state.remoteModels.isEmpty()) model.loadModels() }, modifier = Modifier.fillMaxWidth()) {
                Text(settings.modelId.ifBlank { "Choose a vision model" }, modifier = Modifier.weight(1f), maxLines = 2)
                Icon(Icons.Outlined.ExpandMore, null)
            }
        }
    }
    SettingsSection("Speech engine") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(selected = settings.speechProvider == AiProvider.LOCAL,
                onClick = { model.settings(settings.copy(speechProvider = AiProvider.LOCAL)) }, label = { Text("Offline voices") })
            FilterChip(selected = settings.speechProvider == AiProvider.OPENROUTER,
                onClick = {
                    model.settings(settings.copy(speechProvider = AiProvider.OPENROUTER))
                    if (state.speechModels.isEmpty()) model.loadSpeechModels()
                }, label = { Text("OpenRouter speech") })
        }
        Text(if (settings.speechProvider == AiProvider.LOCAL) "Answers become speech on your device. Download the matching English or Polish voice below."
            else "Answer text is sent to OpenRouter for speech using your saved API key and credits. An offline voice download is not needed.",
            style = MaterialTheme.typography.bodyMedium, color = Muted)
        if (settings.speechProvider == AiProvider.OPENROUTER) {
            val speechModel = state.speechModels.find { it.id == settings.speechModelId }
            OutlinedButton(onClick = { showSpeechModels = true; if (state.speechModels.isEmpty()) model.loadSpeechModels() }, modifier = Modifier.fillMaxWidth()) {
                Text(speechModel?.name ?: settings.speechModelId.ifBlank { "Choose a speech model" }, Modifier.weight(1f), maxLines = 2)
                Icon(Icons.Outlined.ExpandMore, null)
            }
            OutlinedButton(onClick = { showVoices = true; if (state.speechModels.isEmpty()) model.loadSpeechModels() },
                enabled = settings.speechModelId.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text(settings.speechVoice.ifBlank { if (settings.speechModelId.startsWith("fish-audio/")) "Provider default voice" else "Choose a speech voice" }, Modifier.weight(1f), maxLines = 2)
                Icon(Icons.Outlined.ExpandMore, null)
            }
            if (state.loadingSpeechModels) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Choose a voice that supports your answer language. Speaking speed is applied during playback for OpenRouter speech.",
                style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
    SettingsSection("Voice & answers") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(selected = settings.language == "en", onClick = { model.settings(settings.copy(language = "en")) }, label = { Text("English") })
            FilterChip(selected = settings.language == "pl", onClick = { model.settings(settings.copy(language = "pl")) }, label = { Text("Polski") })
        }
        SettingSwitch("Include reasoning", "Add brief justification or working, as you would on a test.", settings.explanations) {
            model.settings(settings.copy(explanations = it))
        }
        Text("Speaking speed · ${"%.1f".format(settings.rate)}×", style = MaterialTheme.typography.titleMedium)
        Slider(settings.rate, onValueChange = { model.settings(settings.copy(rate = it)) }, valueRange = .7f..1.4f, steps = 6,
            modifier = Modifier.fillMaxWidth())
        Text("Voice and explanation changes apply to the next generated recording.", style = MaterialTheme.typography.bodySmall, color = Muted)
    }
    SettingsSection("Listening controls") {
        Text("Earbud media controls: play/pause controls speech, next track skips forward 5 seconds, and previous track rewinds 5 seconds. Assign gestures in the Xiaomi Earbuds app. The earbuds decide which gesture sends each command.",
            style = MaterialTheme.typography.bodyMedium, color = Muted)
        SettingSwitch("Ready sound", "A subtle chime when the full-screen button is ready.", settings.readySound) {
            model.settings(settings.copy(readySound = it))
        }
        TextButton(onClick = model.playback::playReadySound) { Text("Test ready sound") }
        Text("Uses your media volume and connected headphones. Turn up media volume if you cannot hear the test.", style = MaterialTheme.typography.bodySmall, color = Muted)
        Text("Time between taps · ${settings.tapGapMs} ms", style = MaterialTheme.typography.titleMedium)
        Slider(settings.tapGapMs.toFloat(), { model.settings(settings.copy(tapGapMs = it.toLong())) }, valueRange = 150f..700f, steps = 10)
        Text("Increase this if your double or triple taps are being missed. Hold anywhere for 1.5 seconds to restart audio; keep holding to 3 seconds to end the session and clear the photo.", style = MaterialTheme.typography.bodySmall, color = Muted)
    }
    SettingsSection("Offline models") {
        Text("Download once, use without internet. Gemma needs about 2 GB of download storage plus working memory; actual performance depends on your phone.",
            style = MaterialTheme.typography.bodyMedium, color = Muted)
        ModelCatalog.all.forEach { spec ->
            val installed = spec.id in state.installed
            val downloading = state.downloading == spec.id
            Column(Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(16.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(spec.title, style = MaterialTheme.typography.titleMedium)
                        Text(spec.description, style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                    if (installed) Icon(Icons.Outlined.CheckCircle, "Installed", tint = Forest, modifier = Modifier.size(22.dp))
                }
                if (downloading) {
                    LinearProgressIndicator(progress = { state.downloadProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text("${state.downloadStatus} · ${(state.downloadProgress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = model::cancelDownload) { Text("Cancel download") }
                } else if (installed) TextButton(onClick = { remove = spec }, enabled = state.downloading == null && state.busy == null) { Text("Delete model") }
                else OutlinedButton(onClick = { model.install(spec) }, enabled = state.downloading == null && state.busy == null) {
                    Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Download")
                }
            }
        }
    }
    Text("SchoolHelp 0.1 · Built for a little more understanding.\nAI answers can contain mistakes; check important results.", style = MaterialTheme.typography.bodySmall, color = Muted)
    if (showModels) {
        var query by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showModels = false }, title = { Text("Choose a vision model") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text("Search models") }, singleLine = true)
                if (state.loadingModels) LinearProgressIndicator(Modifier.fillMaxWidth())
                else if (state.remoteModels.isEmpty()) {
                    Text("No models loaded. Check your connection and retry.")
                    TextButton(onClick = model::loadModels) { Text("Retry") }
                }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(state.remoteModels.filter { it.name.contains(query, true) || it.id.contains(query, true) }, key = { it.id }) { item ->
                        TextButton(onClick = { model.settings(settings.copy(modelId = item.id)); showModels = false }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(item.name, style = MaterialTheme.typography.bodyMedium)
                                Text(item.id, style = MaterialTheme.typography.bodySmall, color = Muted)
                            }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { showModels = false }) { Text("Done") } })
    }
    if (showSpeechModels) {
        var query by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showSpeechModels = false }, title = { Text("Choose a speech model") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text("Search speech models") }, singleLine = true)
                if (state.loadingSpeechModels) LinearProgressIndicator(Modifier.fillMaxWidth())
                else if (state.speechModels.isEmpty()) Text("No speech models loaded. Check your connection and retry.")
                TextButton(onClick = model::loadSpeechModels, enabled = !state.loadingSpeechModels) { Text("Refresh models") }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(state.speechModels.filter { it.name.contains(query, true) || it.id.contains(query, true) }, key = { it.id }) { item ->
                        TextButton(onClick = {
                            model.settings(settings.copy(speechModelId = item.id,
                                speechVoice = settings.speechVoice.takeIf { it in item.voices } ?: item.voices.firstOrNull().orEmpty()))
                            showSpeechModels = false
                        }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(item.name, style = MaterialTheme.typography.bodyMedium)
                                Text(item.id, style = MaterialTheme.typography.bodySmall, color = Muted)
                            }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { showSpeechModels = false }) { Text("Done") } })
    }
    if (showVoices) {
        var query by remember { mutableStateOf("") }
        val voices = state.speechModels.find { it.id == settings.speechModelId }?.voices.orEmpty()
        AlertDialog(onDismissRequest = { showVoices = false }, title = { Text("Choose a speech voice") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (settings.speechModelId.startsWith("fish-audio/")) {
                    Text("Fish Audio does not publish a preset voice list through OpenRouter. Use its default voice, or enter a Fish Audio reference voice ID. OpenAI voice names such as alloy do not apply.")
                    TextButton(onClick = { model.settings(settings.copy(speechVoice = "")); showVoices = false }) { Text("Use provider default voice") }
                    OutlinedTextField(settings.speechVoice, { model.settings(settings.copy(speechVoice = it.trim())) },
                        label = { Text("Fish Audio voice ID (optional)") }, singleLine = true)
                } else if (state.loadingSpeechModels) LinearProgressIndicator(Modifier.fillMaxWidth())
                else if (voices.isEmpty()) {
                    Text("This model has no voice list available. Enter a voice ID from its OpenRouter model page.")
                    OutlinedTextField(settings.speechVoice, { model.settings(settings.copy(speechVoice = it.trim())) },
                        label = { Text("Voice ID") }, singleLine = true)
                    TextButton(onClick = model::loadSpeechModels) { Text("Refresh voices") }
                } else {
                    OutlinedTextField(query, { query = it }, label = { Text("Search voices") }, singleLine = true)
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(voices.filter { it.contains(query, true) }) { voice ->
                            TextButton(onClick = { model.settings(settings.copy(speechVoice = voice)); showVoices = false }, modifier = Modifier.fillMaxWidth()) {
                                Text(voice, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { showVoices = false }) { Text("Done") } })
    }
    remove?.let { spec ->
        AlertDialog(onDismissRequest = { remove = null }, title = { Text("Delete ${spec.title}?") },
            text = { Text("You can download it again whenever you need it.") },
            confirmButton = { TextButton(onClick = { model.removeModel(spec); remove = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { remove = null }) { Text("Cancel") } })
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(color = Line)
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        content()
    }
}

@Composable
private fun SettingSwitch(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Switch(checked, onChange, modifier = Modifier.semantics { contentDescription = title })
    }
}
