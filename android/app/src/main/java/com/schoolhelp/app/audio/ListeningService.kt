@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.schoolhelp.app.audio

import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.schoolhelp.app.SchoolHelpApplication
import kotlinx.coroutines.*

class ListeningService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onCreate() {
        super.onCreate()
        val app = application as SchoolHelpApplication
        // Restore the saved recording paused when Android recreates the service.
        app.model
        val open = android.app.PendingIntent.getActivity(this, 0, Intent(this, com.schoolhelp.app.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), android.app.PendingIntent.FLAG_IMMUTABLE)
        val nextCommand=androidx.media3.session.SessionCommand("NEXT_TRANSCRIPTION", android.os.Bundle.EMPTY)
        session = MediaSession.Builder(this, app.playback.sessionPlayer).setId("SchoolHelp").setSessionActivity(open)
            .setCallback(object: MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(nextCommand).build()).build()
                }
                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, command: androidx.media3.session.SessionCommand, args: android.os.Bundle): com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult> {
                    if(command.customAction=="NEXT_TRANSCRIPTION" && app.model.state.value.sharedListening) app.model.nextShared()
                    return com.google.common.util.concurrent.Futures.immediateFuture(androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS))
                }
                override fun onPlaybackResumption(session: MediaSession, controller: MediaSession.ControllerInfo): com.google.common.util.concurrent.ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    val shared=if(app.sharing.wasSharedListening()) app.sharing.current() else null
                    val own=com.schoolhelp.app.data.SessionStore(app).load()
                    val file=if(app.sharing.wasSharedListening()) shared?.file else own.recording
                    app.playback.player.setPlaybackSpeed(shared?.speed ?: own.recordingSpeed)
                    val items=if(file==null) emptyList() else listOf(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(file)))
                    return com.google.common.util.concurrent.Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(items,0,shared?.position ?: own.positionMs))
                }
            })
            .setCustomLayout(listOf(androidx.media3.session.CommandButton.Builder(androidx.media3.session.CommandButton.ICON_NEXT)
                .setDisplayName("Next transcription").setSessionCommand(nextCommand).build()))
            .build()
        app.playback.mediaSession = session
        scope.launch {
            while (isActive) {
                app.playback.refresh()
                session?.setCustomLayout(if(app.model.state.value.sharedListening) listOf(androidx.media3.session.CommandButton.Builder(androidx.media3.session.CommandButton.ICON_NEXT)
                    .setDisplayName("Next transcription").setSessionCommand(nextCommand).build()) else emptyList())
                if (app.playback.hasRecording) app.model.saveSession()
                delay(2000)
            }
        }
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onTaskRemoved(rootIntent: Intent?) {
        (application as SchoolHelpApplication).model.saveSession()
        // A playing answer continues after its activity is dismissed.
        if (!(application as SchoolHelpApplication).playback.state.value.playing) stopSelf()
    }
    override fun onDestroy() {
        scope.cancel()
        (application as SchoolHelpApplication).model.saveSession()
        session?.release()
        (application as SchoolHelpApplication).playback.mediaSession = null
        super.onDestroy()
    }
}
