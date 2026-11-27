@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.codetic.saoreo

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import android.view.LayoutInflater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

internal fun playSavedMedia(context: Context, item: Download, videoIds: List<String> = emptyList()) {
    context.startActivity(if (item.mime?.startsWith("audio/") == true) MusicActivity.open(context, item.id)
        else LibraryPlayerActivity.open(context, item.id).putStringArrayListExtra("videoIds", ArrayList(videoIds)))
}

class LibraryAudioService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private lateinit var queue: SavedMusicQueue
    private val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Main.immediate + kotlinx.coroutines.SupervisorJob())
    private val app get() = application as SaoreoApp

    override fun onCreate() {
        super.onCreate()
        player = localPlayer(this).apply { setWakeMode(C.WAKE_MODE_LOCAL) }
        queue = SavedMusicQueue(this)
        queue.restore(player)
        app.audioControl.register(player)
        var countedUri: String? = null
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) { countedUri = null }
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_POSITION_DISCONTINUITY,
                        Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED,
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_PLAYBACK_PARAMETERS_CHANGED)) queue.save(player)
                if ((events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) || events.contains(Player.EVENT_IS_PLAYING_CHANGED)) &&
                    player.isPlaying) {
                    val uri = player.currentMediaItem?.mediaId ?: return
                    if (countedUri != uri) {
                        countedUri = uri
                        scope.launch { app.dao.byUri(uri)?.let { app.collections.trackPlayed(it.id) } }
                    }
                }
            }
        })
        scope.launch {
            while (true) {
                val until = app.audioControl.sleepUntil.value
                if (until > 0L && android.os.SystemClock.elapsedRealtime() >= until) {
                    player.pause()
                    app.audioControl.sleepUntil.value = 0L
                }
                if (player.isPlaying) queue.save(player)
                delay(4000)
            }
        }
        scope.launch {
            app.dao.audio().collect { songs ->
                val available = songs.mapNotNull { it.galleryUri }.toSet()
                for (index in player.mediaItemCount - 1 downTo 0) {
                    if (player.getMediaItemAt(index).mediaId !in available) player.removeMediaItem(index)
                }
            }
        }
        val intent = MusicActivity.open(this, nowPlaying = true)
        val open = PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(open)
            .setCallback(object : MediaSession.Callback {
                override fun onPlaybackResumption(session: MediaSession, controller: MediaSession.ControllerInfo):
                    com.google.common.util.concurrent.ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    val items = (0 until player.mediaItemCount).map(player::getMediaItemAt)
                    return com.google.common.util.concurrent.Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(
                        items, player.currentMediaItemIndex.coerceAtLeast(0), player.currentPosition.coerceAtLeast(0)))
                }
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    if (controller.packageName == packageName) return super.onConnect(session, controller)
                    if (!controller.isTrusted && !session.isMediaNotificationController(controller)) {
                        return MediaSession.ConnectionResult.reject()
                    }
                    val commands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                        .remove(Player.COMMAND_SET_MEDIA_ITEM).remove(Player.COMMAND_CHANGE_MEDIA_ITEMS).build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                        .setAvailablePlayerCommands(commands).build()
                }
            }).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onDestroy() {
        queue.save(player)
        scope.cancel()
        app.audioControl.unregister(player)
        session.release()
        player.release()
        super.onDestroy()
    }
}

private fun localPlayer(context: Context): ExoPlayer = ExoPlayer.Builder(context)
    .setAudioAttributes(AudioAttributes.DEFAULT, true)
    .setHandleAudioBecomingNoisy(true)
    .setSeekBackIncrementMs(10_000)
    .setSeekForwardIncrementMs(10_000)
    .build()

class LibraryPlayerActivity : ComponentActivity() {
    private lateinit var app: SaoreoApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as SaoreoApp
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFFCBB9FF), background = Color.Black, surface = Color(0xFF17151E),
            )) {
                Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
                    val id = intent.getStringExtra("id")
                    val fromSession = intent.getBooleanExtra("audioSession", false)
                    var item by remember { mutableStateOf<Download?>(null) }
                    var ready by remember { mutableStateOf(fromSession) }
                    LaunchedEffect(id) {
                        if (!fromSession) {
                            item = id?.let { app.dao.find(it) }?.takeIf { safeContentUri(it.galleryUri) != null }
                            ready = true
                        }
                    }
                    when {
                        !ready -> Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        !fromSession && item == null -> PlayerMessage("This saved file is unavailable.", ::finish)
                        fromSession || item?.mime?.startsWith("audio/") == true -> AudioPlayback(item, ::finish)
                        item?.mime?.startsWith("image/") == true -> ImagePlayback(checkNotNull(item))
                        else -> VideoPlayback(app, checkNotNull(item))
                    }
                }
            }
        }
    }

    @Composable
    private fun ImagePlayback(item: Download) {
        val uri = safeContentUri(item.galleryUri) ?: return
        var failed by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(uri, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                onError = { failed = true })
            if (failed) Text("This image is unavailable.", Modifier.align(Alignment.Center))
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(alpha = .48f))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to Downloads") }
                Text(item.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { showMedia(this@LibraryPlayerActivity, uri, item.mime ?: "image/*", true) }) {
                    Icon(Icons.Outlined.Share, "Share image")
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun VideoPlayback(app: SaoreoApp, first: Download) {
        val preferences = remember { getSharedPreferences("video_positions", Context.MODE_PRIVATE) }
        var selectedId by rememberSaveable { mutableStateOf(first.id) }
        val list by produceState(listOf(first), first.id) {
            val ids = intent.getStringArrayListExtra("videoIds").orEmpty()
            value = if (ids.isEmpty()) app.dao.videos().ifEmpty { listOf(first) }
                else ids.mapNotNull { app.dao.find(it) }.filter { safeContentUri(it.galleryUri) != null }
        }
        val index = list.indexOfFirst { it.id == selectedId }
        val item = list.getOrNull(index) ?: first
        val uri = checkNotNull(safeContentUri(item.galleryUri))
        var subtitle by rememberSaveable(item.id) { mutableStateOf<String?>(null) }
        var subtitleMime by rememberSaveable(item.id) { mutableStateOf<String?>(null) }
        val player = remember { localPlayer(this) }
        var fullscreen by rememberSaveable { mutableStateOf(false) }
        var orientation by rememberSaveable { mutableIntStateOf(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) }
        LaunchedEffect(orientation) { requestedOrientation = orientation }
        LaunchedEffect(fullscreen) {
            val bars = WindowCompat.getInsetsController(window, window.decorView)
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (fullscreen) bars.hide(WindowInsetsCompat.Type.systemBars()) else bars.show(WindowInsetsCompat.Type.systemBars())
        }
        fun setFullscreen(enabled: Boolean) {
            fullscreen = enabled
            orientation = if (enabled) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        BackHandler(fullscreen) { setFullscreen(false) }
        var rotation by rememberSaveable { mutableIntStateOf(0) }
        var display by rememberSaveable { mutableStateOf("Fit") }
        var controls by remember { mutableStateOf(true) }
        var interacted by remember { mutableIntStateOf(0) }
        var feedback by remember { mutableStateOf<String?>(null) }
        var overflow by remember { mutableStateOf(false) }
        var details by remember { mutableStateOf(false) }
        var conversion by remember { mutableStateOf(false) }
        var captions by remember { mutableStateOf(false) }
        var speed by remember { mutableStateOf(false) }
        var aspect by remember { mutableStateOf(false) }
        var audioTracks by remember { mutableStateOf(false) }
        val reading = rememberPlayback(player)
        val videoGroups = reading.tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
        val unsupportedVideo = videoGroups.isNotEmpty() && videoGroups.none { group ->
            (0 until group.length).any { group.isTrackSupported(it) }
        }
        val error = reading.error ?: "This device can’t decode the video track.".takeIf { unsupportedVideo }
        LaunchedEffect(unsupportedVideo) { if (unsupportedVideo) player.pause() }
        val accessibility = LocalAccessibilityManager.current
        val awake = reading.playing
        fun savePosition() {
            val mediaId = player.currentMediaItem?.mediaId ?: return
            val position = if (player.playbackState == Player.STATE_ENDED) 0 else player.currentPosition
            preferences.edit().putLong(mediaId, position).apply()
        }
        LifecycleEventEffect(Lifecycle.Event.ON_STOP) { savePosition(); player.pause() }
        DisposableEffect(player) {
            app.audioControl.register(player)
            onDispose {
                app.audioControl.unregister(player)
                savePosition()
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                player.release()
            }
        }
        SideEffect {
            if (awake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        var loadedUri by remember { mutableStateOf<Uri?>(null) }
        LaunchedEffect(uri, subtitle, subtitleMime) {
            val localSubtitle = safeContentUri(subtitle)
            val builder = MediaItem.Builder().setUri(uri).setMediaId(uri.toString())
            if (localSubtitle != null && subtitleMime != null) {
                builder.setSubtitleConfigurations(listOf(MediaItem.SubtitleConfiguration.Builder(localSubtitle)
                    .setMimeType(subtitleMime).setLabel("Local subtitle").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()))
            }
            val keepPosition = loadedUri == uri
            val position = if (keepPosition) player.currentPosition else preferences.getLong(uri.toString(), 0L)
            val playing = if (keepPosition) player.playWhenReady else true
            loadedUri = uri
            player.setMediaItem(builder.build(), position)
            player.prepare()
            player.playWhenReady = playing
            controls = true
        }
        LaunchedEffect(controls, reading.playing, interacted, overflow, captions, speed, aspect, audioTracks, details, conversion) {
            if (controls && reading.playing && !overflow && !captions && !speed && !aspect && !audioTracks && !details && !conversion) {
                delay(accessibility?.calculateRecommendedTimeoutMillis(3200L, containsIcons = true,
                    containsText = true, containsControls = true) ?: 3200L)
                controls = false
            }
        }
        LaunchedEffect(interacted, feedback) { if (feedback != null) { delay(850); feedback = null } }
        val subtitleScope = rememberCoroutineScope()
        val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val selected = result.data?.data
            if (result.resultCode == RESULT_OK && selected?.scheme == "content") subtitleScope.launch {
                val name = withContext(Dispatchers.IO) {
                    runCatching {
                        contentResolver.query(selected, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { if (it.moveToFirst()) it.getString(0) else null }
                    }.getOrNull().orEmpty()
                }
                val mime = when (name.substringAfterLast('.').lowercase(Locale.ROOT)) {
                    "vtt" -> "text/vtt"
                    "srt" -> "application/x-subrip"
                    "ttml", "dfxp" -> "application/ttml+xml"
                    "ssa", "ass" -> "text/x-ssa"
                    else -> null
                }
                if (mime == null) toast(this@LibraryPlayerActivity, "Choose an SRT, VTT, TTML or SSA subtitle file.")
                else { subtitleMime = mime; subtitle = selected.toString() }
            }
        }

        fun move(delta: Int) {
            val target = list.getOrNull(index + delta) ?: return
            savePosition()
            rotation = 0
            selectedId = target.id
            interacted++
        }
        val tap by rememberUpdatedState<(Boolean) -> Unit> { right ->
            if (right) player.seekForward() else player.seekBack()
            feedback = if (right) "+10 sec" else "−10 sec"
            interacted++
        }
        val showControls by rememberUpdatedState<() -> Unit> { controls = !controls; interacted++ }
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).clipToBounds(), contentAlignment = Alignment.Center) {
            val swapped = rotation % 180 != 0
            val width = if (swapped) maxHeight else maxWidth
            val height = if (swapped) maxWidth else maxHeight
            AndroidView(
                factory = { LayoutInflater.from(it).inflate(R.layout.library_video_surface, android.widget.FrameLayout(it), false) as PlayerView },
                update = { view ->
                    view.player = player
                    view.resizeMode = when (display) {
                        "Fill" -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
                        "Crop" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                onRelease = { it.player = null },
                modifier = Modifier.wrapContentSize(unbounded = true).requiredSize(width, height).rotate(rotation.toFloat()),
            )
            Box(Modifier.matchParentSize().semantics {
                contentDescription = "Video playback"
                onClick(if (controls) "Hide controls" else "Show controls") { showControls(); true }
            }.pointerInput(Unit) {
                detectTapGestures(onTap = { showControls() }, onDoubleTap = { tap(it.x >= size.width / 2f) })
            }.pointerInput(list, selectedId) {
                var horizontal = 0f
                detectHorizontalDragGestures(
                    onDragStart = { horizontal = 0f },
                    onDragEnd = {
                        if (horizontal < -80.dp.toPx()) move(1)
                        else if (horizontal > 80.dp.toPx()) move(-1)
                    }
                ) { change, amount -> change.consume(); horizontal += amount }
            }.pointerInput(fullscreen) {
                if (fullscreen) {
                    var drag = 0f
                    detectVerticalDragGestures(onDragStart = { drag = 0f }, onDragEnd = {
                        if (drag > 95.dp.toPx()) setFullscreen(false)
                    }) { _, amount -> drag += amount }
                }
            })
            feedback?.let { Text(it, color = Color.White, modifier = Modifier
                .background(Color.Black.copy(alpha = .7f), MaterialTheme.shapes.medium).padding(12.dp)) }
            if (error != null) {
                Column(Modifier.background(Color.Black.copy(alpha = .88f), MaterialTheme.shapes.medium)
                    .padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error, color = Color.White)
                    if (!unsupportedVideo) TextButton(onClick = { player.prepare(); player.play() }) { Text("Retry") }
                }
            }
            androidx.compose.animation.AnimatedVisibility(controls || error != null, modifier = Modifier.matchParentSize(),
                enter = fadeIn(tween(180)), exit = fadeOut(tween(140))) {
                CompositionLocalProvider(LocalContentColor provides Color.White, LocalPickerVisibility provides { open -> speed = open }) {
                    Box(Modifier.fillMaxSize()) {
                        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .72f), Color.Transparent)))
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                            .padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { if (fullscreen) setFullscreen(false) else finish() }) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                            }
                            Text(item.title, Modifier.weight(1f).padding(horizontal = 4.dp),
                                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                            IconButton(onClick = { captions = true; interacted++ }) {
                                Icon(painterResource(R.drawable.ic_music_lyrics), "Subtitles", Modifier.size(22.dp))
                            }
                            IconButton(onClick = { overflow = true; interacted++ }) {
                                Icon(Icons.Outlined.MoreVert, "Video actions")
                            }
                        }
                        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            IconButton(onClick = { move(-1) }, enabled = index > 0, modifier = Modifier.size(48.dp)) {
                                Icon(painterResource(R.drawable.ic_media_prev), "Previous video", Modifier.size(26.dp))
                            }
                            FilledIconButton(onClick = {
                                if (player.playWhenReady && !reading.ended) player.pause()
                                else { if (reading.ended) player.seekTo(0); player.play() }
                                interacted++
                            }, enabled = error == null, modifier = Modifier.size(60.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary)) {
                                val paused = !player.playWhenReady || reading.ended
                                Icon(painterResource(if (paused) R.drawable.ic_media_play else R.drawable.ic_media_pause),
                                    if (paused) "Play" else "Pause", Modifier.size(30.dp))
                            }
                            IconButton(onClick = { move(1) }, enabled = index >= 0 && index < list.lastIndex,
                                modifier = Modifier.size(48.dp)) {
                                Icon(painterResource(R.drawable.ic_media_next), "Next video", Modifier.size(26.dp))
                            }
                        }
                        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .78f))))
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                            .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { PlaybackSeek(reading, player) { interacted++ } }
                            MediaIcon(if (fullscreen) R.drawable.ic_media_exit else R.drawable.ic_media_full,
                                if (fullscreen) "Exit fullscreen" else "Landscape fullscreen") {
                                setFullscreen(!fullscreen); interacted++
                            }
                        }
                        if (overflow) ModalBottomSheet(onDismissRequest = { overflow = false },
                            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                            Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState())
                                .navigationBarsPadding().padding(16.dp)) {
                                Text("Video actions", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(12.dp))
                                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    ChoiceMenu("${reading.speed}×", listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f), { "${it}×" }) {
                                        player.setPlaybackSpeed(it); interacted++
                                    }
                                    ChoiceMenu(display, listOf("Fit", "Fill", "Crop"), { it }) { display = it; interacted++ }
                                }
                                if (list.size > 1) {
                                    DropdownMenuItem(text = { Text("Previous video") }, enabled = index > 0, onClick = { overflow = false; move(-1) })
                                    DropdownMenuItem(text = { Text("Next video") }, enabled = index >= 0 && index < list.lastIndex,
                                        onClick = { overflow = false; move(1) })
                                }
                                DropdownMenuItem(text = { Text("Share video") }, onClick = {
                                    overflow = false
                                    showMedia(this@LibraryPlayerActivity, uri, item.mime ?: "video/mp4", true)
                                })
                                DropdownMenuItem(text = { Text("Details") }, onClick = { overflow = false; details = true })
                                DropdownMenuItem(text = { Text("Convert to MP3") }, onClick = { overflow = false; conversion = true })
                                DropdownMenuItem(text = { Text("Rotate video 90°") }, onClick = {
                                    overflow = false; rotation = (rotation + 90) % 360; interacted++
                                })
                                DropdownMenuItem(text = { Text(when (orientation) {
                                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE -> "Orientation · Landscape"
                                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT -> "Orientation · Portrait"
                                    else -> "Orientation · Auto"
                                }) }, onClick = {
                                    overflow = false
                                    orientation = when (orientation) {
                                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                        else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                    }
                                    interacted++
                                })
                                if (reading.tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO }) {
                                    DropdownMenuItem(text = { Text("Audio track") }, onClick = { overflow = false; audioTracks = true })
                                }
                                DropdownMenuItem(text = { Text("Subtitles") }, onClick = { overflow = false; captions = true })
                            }
                        }
                    }
                }
            }
        }
        if (captions) CaptionPicker(player, reading.tracks, onClose = { captions = false }) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
            runCatching { subtitlePicker.launch(localDocument("*/*")) }
                .onFailure { toast(this, "Android’s file picker is unavailable.") }
        }
        if (audioTracks) AudioTrackPicker(player, reading.tracks) { audioTracks = false }
        if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text(item.title) },
            text = { Text(listOfNotNull(item.mime, reading.duration.takeIf { it > 0 }?.let { playbackTime(it) },
                item.bytes?.let(::sizeLabel)).joinToString("  ·  ")) },
            confirmButton = { TextButton(onClick = { details = false }) { Text("Done") } })
        if (conversion) ConversionSheet(app, uri, item.title, false) { conversion = false }
        ConversionProgress(app, Modifier.fillMaxSize().safeDrawingPadding().padding(top = 56.dp), overlay = true)
    }

    companion object {
        internal fun open(context: Context, id: String) = Intent(context, LibraryPlayerActivity::class.java).putExtra("id", id)
    }
}

@Composable
internal fun MediaIcon(resource: Int, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(painterResource(resource), label, tint = if (enabled) Lime else Color.White.copy(alpha = .3f))
    }
}

@Composable
private fun AudioTrackPicker(player: Player, tracks: Tracks, close: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text("Audio track") }, text = {
        Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
            TextButton(onClick = {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO).build()
                close()
            }) { Text("Automatic") }
            var number = 0
            for (group in tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }) {
                for (i in 0 until group.length) {
                    if (!group.isTrackSupported(i)) continue
                    number++
                    val format = group.getTrackFormat(i)
                    TextButton(onClick = {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i)).build()
                        close()
                    }) { Text((format.label ?: format.language ?: "Track $number") + if (group.isTrackSelected(i)) " ✓" else "") }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = close) { Text("Done") } })
}

private fun safeContentUri(value: String?): Uri? = value?.let(Uri::parse)?.takeIf { it.scheme == "content" }

internal data class Playback(
    val playing: Boolean = false, val position: Long = 0, val duration: Long = 0, val speed: Float = 1f,
    val tracks: Tracks = Tracks.EMPTY, val error: String? = null, val ended: Boolean = false,
)

@Composable
internal fun rememberPlayback(player: Player): Playback {
    var state by remember(player) { mutableStateOf(Playback()) }
    DisposableEffect(player) {
        fun update() {
            state = Playback(player.isPlaying, player.currentPosition, player.duration.coerceAtLeast(0),
                player.playbackParameters.speed, player.currentTracks, player.playerError?.let(::playbackError),
                player.playbackState == Player.STATE_ENDED)
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { update() }
        }
        player.addListener(listener)
        update()
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (true) {
            state = state.copy(position = player.currentPosition, duration = player.duration.coerceAtLeast(0))
            delay(250)
        }
    }
    return state
}

private fun playbackError(error: PlaybackException): String = when (error.errorCode) {
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "The file is missing or was moved."
    PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "Android denied access to this file."
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "This device can’t decode this media."
    else -> "This file couldn’t be played. It may be damaged or unavailable."
}

internal fun playbackTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else durationLabel(seconds)
}

@Composable
internal fun PlaybackSeek(reading: Playback, player: Player, onInteract: () -> Unit = {}) {
    val seeking = player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) && player.isCurrentMediaItemSeekable
    MediaTimeline(reading.position, reading.duration, seeking, onInteract = onInteract, onSeek = player::seekTo)
}

@Composable
private fun PlayToggle(reading: Playback, player: Player, onInteract: () -> Unit = {}) {
    TextButton(onClick = {
        if (player.playWhenReady && !reading.ended) player.pause()
        else {
            if (reading.ended) player.seekTo(0)
            if (reading.error != null) player.prepare()
            player.play()
        }
        onInteract()
    }) {
        if (player.playWhenReady && !reading.ended && reading.error == null) Text("Pause") else Icon(Icons.Outlined.PlayArrow, "Play")
    }
}

@Composable
private fun CaptionPicker(player: Player, tracks: Tracks, onClose: () -> Unit, onImport: () -> Unit) {
    AlertDialog(onDismissRequest = onClose, title = { Text("Subtitles") }, text = {
        Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
            TextButton(onClick = {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                onClose()
            }) { Text("Off") }
            tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.forEach { group ->
                for (index in 0 until group.length) {
                    if (!group.isTrackSupported(index)) continue
                    val format = group.getTrackFormat(index)
                    TextButton(onClick = {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)).build()
                        onClose()
                    }) { Text((format.label ?: format.language ?: "Subtitle ${index + 1}") +
                        if (group.isTrackSelected(index)) " ✓" else "") }
                }
            }
            TextButton(onClick = { onClose(); onImport() }) { Text("Choose local subtitle file") }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("Done") } })
}

@Composable
private fun AudioPlayback(item: Download?, close: () -> Unit) {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var failed by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, LibraryAudioService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            runCatching { future.get() }.onSuccess { controller = it }.onFailure { failed = true }
        }, androidx.core.content.ContextCompat.getMainExecutor(context))
        onDispose { MediaController.releaseFuture(future) }
    }
    val player = controller
    if (player == null) {
        if (failed) PlayerMessage("Couldn’t connect to audio playback.", close)
        else Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    LaunchedEffect(player, item?.id) {
        val uri = safeContentUri(item?.galleryUri)
        if (item != null && uri != null && player.currentMediaItem?.mediaId != uri.toString()) {
            player.setMediaItem(MediaItem.Builder().setUri(uri).setMediaId(uri.toString())
                .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).build()).build())
            player.prepare()
            player.play()
        }
    }
    val reading = rememberPlayback(player)
    var uri by remember(player) { mutableStateOf(safeContentUri(player.currentMediaItem?.mediaId)) }
    var title by remember(player) { mutableStateOf(player.mediaMetadata.title?.toString() ?: item?.title ?: "Audio") }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                uri = safeContentUri(player.currentMediaItem?.mediaId)
                title = player.mediaMetadata.title?.toString() ?: item?.title ?: "Audio"
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    Column(Modifier.fillMaxSize()
        .background(Brush.verticalGradient(listOf(Color(0xFF28203D), Color.Black)))
        .safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
        IconButton(onClick = close) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to Downloads") }
        Spacer(Modifier.height(34.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium)
        reading.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
        Spacer(Modifier.height(24.dp))
        PlaybackSeek(reading, player)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { PlayToggle(reading, player) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(enabled = uri != null, onClick = { uri?.let { showMedia(context, it, item?.mime ?: "audio/mpeg", true) } }) {
                Icon(Icons.Outlined.Share, null)
                Spacer(Modifier.width(8.dp))
                Text("Share")
            }
            TextButton(enabled = uri != null, onClick = { uri?.let { showMedia(context, it, item?.mime ?: "audio/mpeg", false, chooseApp = true) } }) {
                Text("Open with")
            }
        }
    }
}

@Composable
private fun PlayerMessage(message: String, close: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        Column(Modifier.safeDrawingPadding().padding(16.dp)) {
            IconButton(onClick = close) { Icon(Icons.Outlined.Close, "Close player") }
            Text(message, Modifier.padding(12.dp))
        }
    }
}
