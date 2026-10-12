package com.schoolhelp.app.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schoolhelp.app.AppViewModel
import kotlinx.coroutines.delay

@Composable
fun SharingPanel(model: AppViewModel) {
    val sharing by model.sharing.state.collectAsStateWithLifecycle()
    var joinCode by remember { mutableStateOf("") }
    val context=LocalContext.current

    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Shared listening",style=MaterialTheme.typography.titleMedium)
            if(!sharing.configured) {
                Text("Sharing needs server configuration in this build. Your own answers work without it.",style=MaterialTheme.typography.bodySmall)
            } else {
                if(sharing.code.isBlank()) Button(onClick={model.sharingAction("profile")},enabled=!sharing.busy) { Text("Get my code") }
                else {
                    Text("My code: ${sharing.code.chunked(5).joinToString("-")}")
                    Row {
                        TextButton(onClick={context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("SchoolHelp code",sharing.code))}) { Text("Copy") }
                        TextButton(onClick={model.sharingAction("rotate")},enabled=!sharing.busy) { Text("Rotate code") }
                    }
                    Row {
                        Text("Share new answers",Modifier.weight(1f))
                        Switch(checked=sharing.enabled,onCheckedChange={model.sharingAction("toggle",it)},enabled=!sharing.busy)
                    }
                    Text("Connected listeners: ${sharing.listeners.size}/10",style=MaterialTheme.typography.bodySmall)
                    sharing.listeners.forEachIndexed { index, listener ->
                        TextButton(onClick={model.sharingAction("remove",member=listener.first)}) { Text("Remove listener ${index+1}") }
                    }
                }
                if(sharing.following.isBlank()) {
                    OutlinedTextField(value=joinCode,onValueChange={joinCode=it},label={Text("Owner's code")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    Button(onClick={model.joinSharing(joinCode)},enabled=joinCode.isNotBlank()&&!sharing.busy) { Text("Connect") }
                } else {
                    Text("${sharing.pending} queued · ${sharing.ready} downloaded")
                    Row {
                        Button(onClick=model::openShared) { Text("Listen") }
                        TextButton(onClick=model::leaveSharing) { Text("Disconnect") }
                    }
                }
                if(sharing.outgoing>0) Text("Pending uploads: ${sharing.outgoing}",style=MaterialTheme.typography.bodySmall)
                if(sharing.status.isNotBlank()) Text(sharing.status,style=MaterialTheme.typography.bodySmall)
                TextButton(onClick=model::syncSharing) { Text("Sync now") }
            }
        }
    }
}
