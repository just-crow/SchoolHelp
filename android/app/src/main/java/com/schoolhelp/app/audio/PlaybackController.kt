@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.schoolhelp.app.audio

import android.content.Context
import android.content.Intent
import androidx.core.content.IntentCompat
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState as SystemPlaybackState
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.schoolhelp.app.core.PlaybackAction
import com.schoolhelp.app.core.seekPosition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class PlaybackState(
    val ready: Boolean = false,
    val playing: Boolean = false,
    val ended: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val earbudAction: String? = null,
    val readySoundPlaying: Boolean = false,
)

class PlaybackController(context: Context, private val onError: (String) -> Unit) {
    private val appContext = context.applicationContext
    private val mutable = MutableStateFlow(PlaybackState())
    val state = mutable.asStateFlow()
    val hasRecording get() = player.mediaItemCount > 0
    var mediaSession: androidx.media3.session.MediaSession? = null
    val mediaController: android.media.session.MediaController? get() = mediaSession?.let {
        android.media.session.MediaController(appContext, it.platformToken as android.media.session.MediaSession.Token)
    }
    private var earbudAction: String? = null
    private val pressedMediaKeys = mutableSetOf<Int>()
    private var metadataDuration = -1L
    private var chime: ExoPlayer? = null
    internal val player = ExoPlayer.Builder(context).setWakeMode(C.WAKE_MODE_LOCAL).setSeekBackIncrementMs(5000).setSeekForwardIncrementMs(5000).build().apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
        setHandleAudioBecomingNoisy(true)
        addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { refresh() }
            override fun onPlayerError(error: PlaybackException) { onError("The recording could not be played. Please generate it again.") }
        })
    }

    internal val sessionPlayer = object : androidx.media3.common.ForwardingPlayer(player) {
        override fun play() { mediaAction(KeyEvent.KEYCODE_MEDIA_PLAY) }
        override fun pause() { mediaAction(KeyEvent.KEYCODE_MEDIA_PAUSE) }
        override fun stop() { mediaAction(KeyEvent.KEYCODE_MEDIA_STOP) }
        override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
            .add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM).build()
        override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)
        override fun seekToNext() { act(PlaybackAction.FORWARD) }
        override fun seekToNextMediaItem() { act(PlaybackAction.FORWARD) }
        override fun seekToPrevious() { act(PlaybackAction.REWIND) }
        override fun seekToPreviousMediaItem() { act(PlaybackAction.REWIND) }
    }

    // Some devices send headset keys to the focused activity instead of the session.
    // Consume the entire press, but act only on its first down event.
    fun handleMediaKey(event: KeyEvent): Boolean {
        if (!hasRecording || event.keyCode !in mediaKeys) return false
        if (event.action == KeyEvent.ACTION_UP) pressedMediaKeys.remove(event.keyCode)
        else if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && pressedMediaKeys.add(event.keyCode)) mediaAction(event.keyCode)
        return true
    }

    private fun mediaAction(keyCode: Int) {
        if (!hasRecording) return
        when (keyCode) {
            KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                earbudAction = "Play / pause"
                act(PlaybackAction.TOGGLE)
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                earbudAction = "Play"
                if (!player.playWhenReady || player.playbackState == Player.STATE_ENDED) act(PlaybackAction.TOGGLE) else refresh()
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> {
                earbudAction = "Pause"
                pause()
            }
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                earbudAction = "Forward five seconds"
                act(PlaybackAction.FORWARD)
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                earbudAction = "Back five seconds"
                act(PlaybackAction.REWIND)
            }
        }
    }

    private val mediaKeys = setOf(KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP,
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND)

    fun load(file: File, speed: Float = 1f, positionMs: Long = 0) {
        stopReadySound()
        pressedMediaKeys.clear()
        earbudAction = null
        player.pause()
        player.setPlaybackSpeed(speed)
        mutable.value = PlaybackState()
        player.setMediaItem(MediaItem.Builder().setUri(android.net.Uri.fromFile(file)).setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle("SchoolHelp answers").setArtist("SchoolHelp").build()).build(), positionMs.coerceAtLeast(0))
        player.prepare()
        try { appContext.startService(android.content.Intent(appContext, ListeningService::class.java)) } catch (_: IllegalStateException) { /* Bound again when the user opens or plays the item. */ }
        refresh()
    }

    fun refresh() {
        mutable.value = PlaybackState(
            ready = player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_ENDED,
            playing = player.isPlaying,
            ended = player.playbackState == Player.STATE_ENDED,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0,
            earbudAction = earbudAction,
            readySoundPlaying = chime?.isPlaying == true,
        )
    }

    fun act(action: PlaybackAction) {
        if (!hasRecording) return
        stopReadySound()
        if (mediaSession == null) {
            try { appContext.startService(android.content.Intent(appContext, ListeningService::class.java)) } catch (_: IllegalStateException) { }
        }
        when (action) {
            PlaybackAction.TOGGLE -> if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) player.pause() else {
                if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                player.play()
            }
            PlaybackAction.REWIND -> player.seekTo(seekPosition(player.currentPosition, -5000, mutable.value.durationMs))
            PlaybackAction.FORWARD -> player.seekTo(seekPosition(player.currentPosition, 5000, mutable.value.durationMs))
        }
        refresh()
    }

    fun playReadySound() {
        if (player.playWhenReady) return
        val sound = chime ?: ExoPlayer.Builder(appContext).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SONIFICATION).build(), true)
            setHandleAudioBecomingNoisy(true)
            addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) { refresh() }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) stopReadySound()
                }
                override fun onPlayerError(error: PlaybackException) {
                    stopReadySound()
                    onError("The ready sound could not play. Check your media volume and audio output.")
                }
            })
        }.also { chime = it }
        sound.setMediaItem(MediaItem.fromUri("asset:///ready_chime.wav"))
        sound.prepare()
        sound.play()
    }

    private fun stopReadySound() { chime?.stop(); chime?.clearMediaItems(); refresh() }

    fun pause() { stopReadySound(); player.pause(); refresh() }
    fun restart() {
        if (!mutable.value.ready) return
        stopReadySound()
        player.seekTo(0)
        player.play()
        refresh()
    }
    fun clear() { stopReadySound(); pressedMediaKeys.clear(); earbudAction = null; player.stop(); player.clearMediaItems(); refresh(); appContext.stopService(android.content.Intent(appContext, ListeningService::class.java)) }
    fun release() {
        chime?.release()
        chime = null
        mediaSession?.release()
        mediaSession = null
        player.release()
    }
}
