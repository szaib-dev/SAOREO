package com.codetic.saoreo

import android.content.Context
import android.os.SystemClock
import android.view.LayoutInflater
import android.widget.FrameLayout
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
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import kotlinx.coroutines.delay

internal class TogetherPlayback(
    private val roomState: () -> TogetherState,
    private val serverClock: () -> Long,
    private val sendStatus: (Boolean, Boolean) -> Boolean,
    private val elapsedClock: () -> Long = SystemClock::elapsedRealtime,
) {
    constructor(session: TogetherSession) : this({ session.state.value }, session::serverNow, session::status)
    var position by mutableLongStateOf(0)
    var duration by mutableLongStateOf(0)
    var ready by mutableStateOf(false)
    var buffering by mutableStateOf(false)
    var ended by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var live by mutableStateOf(false)
    var qualityLimit by mutableStateOf<Int?>(null)
    var reload by mutableIntStateOf(0)
    var youtube = false
    var actualPlaying = false
    private var lastPlayCommand = 0L
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
        val now = elapsedClock()
        val changed = lastApplied != room.timeline.playing
        val serverNow = serverClock()
        val target = room.timeline.target(serverNow)
        val restart = ended && room.timeline.position == 0L && target < duration
        val drift = (force || now - lastCorrection >= 3500) &&
            room.timeline.needsSeek(position, serverNow, if (room.timeline.playing) 2200 else 650)
        if (!live && !buffering && (restart || drift)) {
            seek?.invoke(if (duration > 0) target.coerceAtMost(duration) else target)
            lastCorrection = now
        }
        if (changed || force || retryTogetherPlay(room.timeline.playing, actualPlaying, buffering, now - lastPlayCommand)) {
            play?.invoke(room.timeline.playing)
            lastPlayCommand = now
        }
        lastApplied = room.timeline.playing
    }
    fun report() {
        val status = togetherPlayerStatus(ready, buffering, youtube, roomState().wanted)
        if (!roomState().connected) lastStatus = null
        else if (status != lastStatus && sendStatus(status.first, status.second)) lastStatus = status
    }
    fun reset() {
        actualPlaying = false; live = false; lastPlayCommand = 0; lastCorrection = 0
        ready = false; buffering = false; ended = false; error = null
        position = 0; duration = 0; lastApplied = null; lastStatus = null
    }
}

@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun TogetherVideo(context: Context, room: TogetherState, session: TogetherSession, playback: TogetherPlayback,
    localSource: String?, modifier: Modifier = Modifier, onIptvSetup: () -> Unit = {}) {
    val media = room.media ?: return
    val lifecycle = LocalLifecycleOwner.current
    val catalog = session.iptv
    val isIptv = media.kind == "iptv"
    var consent by remember(media.id, catalog.allowHttp) { mutableStateOf(false) }
    var declined by remember(media.id) { mutableStateOf(false) }
    var program by remember(media.id) { mutableStateOf<IptvProgram?>(null) }
    var finding by remember(media.id) { mutableStateOf(isIptv) }
    LaunchedEffect(media.id, catalog.entries, catalog.allowHttp, playback.reload) {
        if (!isIptv) return@LaunchedEffect
        finding = true
        try {
            program = catalog.resolve(media.source)
            if (program?.url?.startsWith("http://") == true && !catalog.allowHttp && !declined) consent = true
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: IptvHttpConsentRequired) {
            if (!declined) consent = true
        } catch (_: Exception) {
            program = null
        } finally {
            finding = false
        }
    }
    if (consent) IptvHttpWarning(onCancel = { declined = true; consent = false },
        onAllow = { declined = false; catalog.allowHttp = true; consent = false })
    val iptvSource = program?.url?.takeIf { !it.startsWith("http://") || catalog.allowHttp }
    key(media.id, localSource, playback.reload, iptvSource, if (isIptv) catalog.allowHttp else false) {
        DisposableEffect(Unit) {
            playback.reset()
            playback.youtube = media.kind == "youtube"
            onDispose { playback.seek = null; playback.play = null; playback.reset() }
        }
        if (media.kind == "youtube") {
            LaunchedEffect(playback.ready, playback.error) {
                if (!playback.ready && playback.error == null) {
                    delay(18_000)
                    if (!playback.ready) playback.error = "YouTube took too long to load."
                }
            }
            val view = remember { YouTubePlayerView(context).apply { enableAutomaticInitialization = false } }
            DisposableEffect(view) {
                var active = true
                var cued = false
                var cuePosition = 0L
                lifecycle.lifecycle.addObserver(view)
                view.initialize(object : AbstractYouTubePlayerListener() {
                    override fun onReady(player: YouTubePlayer) {
                        if (!active) { return }
                        var started = false
                        playback.seek = {
                            if (started) {
                                if (it < playback.duration - 150) playback.ended = false
                                player.seekTo(it / 1000f)
                            }
                        }
                        playback.play = { shouldPlay ->
                            if (shouldPlay && !started) {
                                started = true
                                val target = session.state.value.timeline.target(session.serverNow())
                                if (cued && kotlin.math.abs(target - cuePosition) < 650) player.play()
                                else player.loadVideo(media.source, target / 1000f)
                            } else if (shouldPlay) player.play()
                            else if (started) player.pause()
                        }
                        playback.live = media.live
                        cuePosition = session.state.value.timeline.target(session.serverNow())
                        player.cueVideo(media.source, cuePosition / 1000f)
                    }
                    override fun onVideoId(youTubePlayer: YouTubePlayer, videoId: String) {
                        if (active && videoId == media.source) {
                            playback.ready = true
                            playback.report()
                        }
                    }
                    override fun onStateChange(player: YouTubePlayer, state: PlayerConstants.PlayerState) {
                        if (!active) { return }
                        when (state) {
                            PlayerConstants.PlayerState.VIDEO_CUED -> {
                                cued = true
                                playback.actualPlaying = false
                                playback.ended = false
                                playback.ready = true
                                playback.buffering = false
                                playback.error = null
                                playback.report()
                            }
                            PlayerConstants.PlayerState.BUFFERING -> playback.buffering = true
                            PlayerConstants.PlayerState.PLAYING -> {
                                playback.actualPlaying = true
                                playback.ended = false
                                playback.ready = true
                                playback.buffering = false
                                playback.error = null
                                playback.report()
                            }
                            PlayerConstants.PlayerState.PAUSED -> { playback.actualPlaying = false; playback.buffering = false; playback.report() }
                            PlayerConstants.PlayerState.ENDED -> {
                                playback.actualPlaying = false
                                playback.ended = true
                                session.mediaFinished(media.id)
                            }
                            else -> Unit
                        }
                    }
                    override fun onCurrentSecond(player: YouTubePlayer, second: Float) {
                        if (!active) { return }
                        val newPosition = (second * 1000).toLong()
                        playback.position = newPosition
                    }
                    override fun onVideoDuration(player: YouTubePlayer, duration: Float) {
                        if (!active) { return }
                        playback.duration = (duration * 1000).toLong()
                    }
                    override fun onError(player: YouTubePlayer, error: PlayerConstants.PlayerError) {
                        if (!active) { return }
                        playback.ready = false; playback.error = "YouTube could not play this video ($error). It may restrict embedding or require access in YouTube."; playback.report()
                    }
                }, IFramePlayerOptions.Builder(context).controls(0).fullscreen(0).build())
                onDispose { active = false; lifecycle.lifecycle.removeObserver(view); view.release() }
            }
            AndroidView(factory = { view.apply {
                // The embedded player still renders YouTube's attribution and status.
                // Only room commands may interact with its transport.
                setOnTouchListener { _, _ -> true }
                descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            } }, modifier = modifier.background(Color.Black))
        } else {
            val source = if (isIptv) iptvSource else localSource ?: (if (media.kind == "relay") session.localSharedSource(media.source) else null) ?: when (media.kind) {
                "relay" -> session.sharedFileUrl(media.source)
                "upload" -> session.uploadUrl(media.source)
                "stream" -> media.source
                else -> null
            }
            if (source != null) {
                val player = remember {
                    ExoPlayer.Builder(context).apply {
                        if (isIptv) {
                            setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                                androidx.media3.datasource.DataSource.Factory {
                                    if (source.startsWith("content://")) {
                                        IptvManifestDataSource(context, source, catalog.allowHttp)
                                    } else IptvDataSource(catalog.allowHttp)
                                }
                            ))
                        } else if (media.kind in setOf("upload", "relay")) {
                            setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                                androidx.media3.datasource.DefaultDataSource.Factory(context, session.uploadDataSource())
                            ))
                            if (source.startsWith("https://")) {
                                setLoadControl(DefaultLoadControl.Builder()
                                    .setBufferDurationsMsForStreaming(10_000, 30_000, 500, 1_500).build())
                            }
                        }
                    }.build().apply {
                        setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, false)
                    }
                }
                DisposableEffect(player) {
                    playback.seek = {
                        if (it < playback.duration - 150) playback.ended = false
                        player.seekTo(it)
                        playback.position = player.currentPosition.coerceAtLeast(0)
                    }
                    playback.play = { player.playWhenReady = it }
                    player.addListener(object : Player.Listener {
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            playback.actualPlaying = isPlaying
                            if (isPlaying) playback.ended = false
                        }
                        override fun onPlaybackStateChanged(state: Int) {
                            val ended = state == Player.STATE_ENDED
                            if (ended) playback.ended = true
                            playback.ready = state == Player.STATE_READY || ended || state == Player.STATE_BUFFERING && playback.ready
                            playback.buffering = state == Player.STATE_BUFFERING
                            playback.live = player.isCurrentMediaItemLive || (isIptv && media.live && program?.hls != true)
                            if (!playback.buffering) playback.report()
                            if (ended) session.mediaFinished(media.id)
                        }
                        override fun onPlayerError(error: PlaybackException) {
                            playback.ready = false
                            if (isIptv) {
                                val needsConsent = generateSequence<Throwable>(error) { it.cause }
                                    .any { it is IptvHttpConsentRequired }
                                if (needsConsent && !declined) consent = true
                                playback.error = if (needsConsent) "This IPTV stream requires HTTP approval."
                                    else "IPTV playback failed. Check your access and the provider's connection limit."
                            } else playback.error = "Playback failed: ${error.errorCodeName}. Check your source and access."
                            playback.report()
                        }
                    })
                    val item = MediaItem.Builder().setUri(source).apply {
                        if (isIptv && program?.hls == true) setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
                    }.build()
                    player.setMediaItem(item)
                    player.prepare()
                    onDispose { player.release() }
                }
                LaunchedEffect(player, playback.qualityLimit) {
                    val height = playback.qualityLimit
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setMaxVideoSize(Int.MAX_VALUE, height ?: Int.MAX_VALUE).build()
                }
                LaunchedEffect(player) {
                    while (true) {
                        playback.position = player.currentPosition.coerceAtLeast(0)
                        playback.duration = player.duration.coerceAtLeast(0)
                        delay(250)
                    }
                }
                AndroidView(
                    factory = { viewContext ->
                        LayoutInflater.from(viewContext).inflate(R.layout.library_video_surface, FrameLayout(viewContext), false) as PlayerView
                    },
                    update = { it.player = player },
                    onRelease = { it.player = null },
                    modifier = modifier.background(Color.Black))
            } else if (isIptv) Surface(modifier) {
                androidx.compose.foundation.layout.Column(Modifier.padding(20.dp)) {
                    Text(if (finding) "Connecting to your IPTV catalog…"
                        else if (program != null || declined) "This IPTV stream requires HTTP approval."
                        else "Add the same playlist or provider on this phone to play this program.")
                    if (!finding) androidx.compose.material3.TextButton({
                        if (program != null || declined) consent = true else onIptvSetup()
                    }) { Text(if (program != null || declined) "Review HTTP access" else "Set up IPTV") }
                }
            } else Surface(modifier) { Text("Ask the source phone to select and share this video again.", Modifier.padding(24.dp)) }
        }
    }
    LaunchedEffect(room.connected, playback.ready, media.id, playback.reload) {
        playback.report()
    }
    LaunchedEffect(room.revision, room.connected, playback.ready, playback.reload) {
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
