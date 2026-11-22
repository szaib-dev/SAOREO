package com.codetic.saoreo

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import kotlinx.coroutines.delay

internal class TogetherPlayback(private val session: TogetherSession) {
    var position by mutableLongStateOf(0)
    var duration by mutableLongStateOf(0)
    var ready by mutableStateOf(false)
    var buffering by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var live by mutableStateOf(false)
    var youtube = false
    var seek: ((Long) -> Unit)? = null
    var play: ((Boolean) -> Unit)? = null
    var lastApplied: Boolean? = null
    var lastStatus: Pair<Boolean, Boolean>? = null
    private var lastCorrection = 0L

    fun remote(block: () -> Unit) = block()

    fun apply(room: TogetherState, force: Boolean = false) {
        if (!room.connected || !ready) {
            remote { play?.invoke(false) }; lastApplied = false; return
        }
        val now = SystemClock.elapsedRealtime()
        val changed = lastApplied != room.timeline.playing
        if (!live && !buffering && (force || now - lastCorrection >= 3500) &&
            room.timeline.needsSeek(position, session.serverNow(), if (room.timeline.playing) 2200 else 650)) {
            seek?.invoke(room.timeline.target(session.serverNow()).let { if (duration > 0) it.coerceAtMost(duration) else it })
            lastCorrection = now
        }
        if (changed || force) play?.invoke(room.timeline.playing)
        lastApplied = room.timeline.playing
    }
    fun report() {
        val status = ready to (buffering && !youtube && session.state.value.wanted)
        if (!session.state.value.connected) lastStatus = null
        else if (status != lastStatus && session.status(ready, buffering)) lastStatus = status
    }
    fun reset() { ready = false; buffering = false; error = null; position = 0; duration = 0; lastApplied = null; lastStatus = null }
}

@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun TogetherVideo(context: Context, room: TogetherState, session: TogetherSession, playback: TogetherPlayback,
    localSource: String?, modifier: Modifier = Modifier) {
    val media = room.media ?: return
    val lifecycle = LocalLifecycleOwner.current
    key(media.id, localSource) {
        DisposableEffect(Unit) {
            playback.reset()
            playback.youtube = media.kind == "youtube"
            onDispose { playback.seek = null; playback.play = null; playback.reset() }
        }
        if (media.kind == "youtube") {
            val view = remember { YouTubePlayerView(context).apply { enableAutomaticInitialization = false } }
            DisposableEffect(view) {
                lifecycle.lifecycle.addObserver(view)
                view.initialize(object : AbstractYouTubePlayerListener() {
                    override fun onReady(player: YouTubePlayer) {
                        var started = false
                        playback.seek = { if (started) player.seekTo(it / 1000f) }
                        playback.play = { shouldPlay ->
                            if (shouldPlay && !started) {
                                started = true
                                player.loadVideo(media.source, session.state.value.timeline.target(session.serverNow()) / 1000f)
                            } else if (shouldPlay) player.play()
                            else if (started) player.pause()
                        }
                        playback.live = media.live
                        playback.remote { player.cueVideo(media.source, room.timeline.target(session.serverNow()) / 1000f) }
                    }
                    override fun onVideoId(youTubePlayer: YouTubePlayer, videoId: String) {
                        if (videoId == media.source) {
                            playback.ready = true
                            playback.report()
                        }
                    }
                    override fun onStateChange(player: YouTubePlayer, state: PlayerConstants.PlayerState) {
                        when (state) {
                            PlayerConstants.PlayerState.VIDEO_CUED -> { playback.ready = true; playback.buffering = false; playback.report() }
                            PlayerConstants.PlayerState.BUFFERING -> playback.buffering = true
                            PlayerConstants.PlayerState.PLAYING -> {
                                if (!session.state.value.timeline.playing) {
                                    playback.remote { player.pause() }
                                }
                                playback.ready = true
                                playback.buffering = false
                                playback.report()
                            }
                            PlayerConstants.PlayerState.PAUSED -> { playback.buffering = false; playback.report() }
                            PlayerConstants.PlayerState.ENDED -> session.playback("pause")
                            else -> Unit
                        }
                    }
                    override fun onCurrentSecond(player: YouTubePlayer, second: Float) {
                        val newPosition = (second * 1000).toLong()
                        playback.position = newPosition
                    }
                    override fun onVideoDuration(player: YouTubePlayer, duration: Float) { playback.duration = (duration * 1000).toLong() }
                    override fun onError(player: YouTubePlayer, error: PlayerConstants.PlayerError) {
                        playback.ready = false; playback.error = "YouTube could not play this video ($error). It may restrict embedding or require access in YouTube."; playback.report()
                    }
                }, IFramePlayerOptions.Builder(context).controls(0).fullscreen(0).build())
                onDispose { lifecycle.lifecycle.removeObserver(view); view.release() }
            }
            AndroidView(factory = { view }, modifier = modifier.background(Color.Black).heightIn(min = 200.dp))
        } else {
            val source = localSource ?: when (media.kind) {
                "upload" -> session.uploadUrl(media.source)
                "stream" -> media.source
                else -> null
            }
            if (source != null) {
                val player = remember {
                    ExoPlayer.Builder(context).apply {
                        if (media.kind == "upload") setMediaSourceFactory(
                            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(session.uploadDataSource())
                        )
                    }.build().apply {
                        setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, false)
                    }
                }
                DisposableEffect(player) {
                    playback.seek = { player.seekTo(it) }
                    playback.play = { player.playWhenReady = it }
                    player.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            playback.ready = state == Player.STATE_READY || state == Player.STATE_BUFFERING && playback.ready
                            playback.buffering = state == Player.STATE_BUFFERING
                            playback.live = player.isCurrentMediaItemLive
                            if (!playback.buffering) playback.report()
                            if (state == Player.STATE_ENDED) session.playback("pause")
                        }
                        override fun onPlayerError(error: PlaybackException) {
                            playback.ready = false; playback.error = "Playback failed: ${error.errorCodeName}. Check your source and access."; playback.report()
                        }
                    })
                    player.setMediaItem(MediaItem.fromUri(source)); player.prepare()
                    onDispose { player.release() }
                }
                LaunchedEffect(player) {
                    while (true) {
                        playback.position = player.currentPosition.coerceAtLeast(0)
                        playback.duration = player.duration.coerceAtLeast(0)
                        delay(250)
                    }
                }
                AndroidView(factory = { PlayerView(context).apply { this.player = player; useController = false } },
                    modifier = modifier.background(Color.Black))
            } else Surface(modifier) { Text("Ask your partner to reselect the file using Share local MP4.", Modifier.padding(24.dp)) }
        }
    }
    LaunchedEffect(room.connected, playback.ready, media.id) {
        playback.report()
    }
    LaunchedEffect(room.revision, room.connected, playback.ready) {
        playback.apply(room)
    }
    LaunchedEffect(media.id, playback.buffering) {
        if (playback.buffering) {
            delay(2400)
            playback.report()
        }
    }
    LaunchedEffect(media.id) {
        while (true) {
            playback.apply(session.state.value)
            delay(1200)
        }
    }
}
