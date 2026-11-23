package com.codetic.saoreo

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NowPlaying(app: SaoreoApp, player: MediaController, song: Download?, title: String, artist: String,
                        liked: Boolean, toggle: () -> Unit, like: () -> Unit, onDismiss: () -> Unit = {}) {
    val reading = rememberPlayback(player)
    val lyricMetadata by remember(song?.id) {
        song?.id?.let(app.lyrics.dao::observe) ?: kotlinx.coroutines.flow.flowOf(null)
    }.collectAsStateWithLifecycle(null)
    val displayTitle = song?.localTitle ?: lyricMetadata?.title ?: title
    val displayArtist = song?.artist ?: lyricMetadata?.artist?.takeIf(String::isNotBlank) ?: artist

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var panel by rememberSaveable { mutableStateOf<String?>(null) }
    var queue by remember(player) { mutableStateOf(emptyList<androidx.media3.common.MediaItem>()) }
    var shuffle by remember(player) { mutableStateOf(player.shuffleModeEnabled) }
    var repeat by remember(player) { mutableIntStateOf(player.repeatMode) }
    val timer by app.audioControl.sleepUntil.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var seekFeedback by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(seekFeedback) { if (seekFeedback != null) { delay(650); seekFeedback = null } }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(timer) { while (timer > 0) { now = SystemClock.elapsedRealtime(); delay(1000) } }
    DisposableEffect(player) {
        fun sync() {
            queue = (0 until player.mediaItemCount).map(player::getMediaItemAt)
            shuffle = player.shuffleModeEnabled; repeat = player.repeatMode
        }
        val listener = object : Player.Listener { override fun onEvents(player: Player, events: Player.Events) { sync() } }
        sync(); player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    val lyricsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && song != null) scope.launch {
            try {
                val lyrics = withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readNBytes(200_001)
                        require(bytes.size <= 200_000) { "Choose lyrics smaller than 200 KB." }
                        bytes.toString(Charsets.UTF_8)
                    } ?: error("Could not read lyrics.")
                }
                app.dao.editTags(song.id, song.localTitle ?: title, song.artist ?: artist, song.album, lyrics)
                message = null
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { message = failure.message ?: "Could not import lyrics." }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val artSize = minOf(maxWidth.value - 32, maxHeight.value * .40f).toInt().coerceIn(104, 280)
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 8.dp)
            .pointerInput(player) {
                detectTapGestures(onDoubleTap = { point ->
                    val forward = point.x >= size.width / 2f
                    val target = player.currentPosition + if (forward) 10_000L else -10_000L
                    player.seekTo(target.coerceIn(0L, player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE))
                    seekFeedback = if (forward) "Forward 10 seconds" else "Back 10 seconds"
                })
            },
            verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            var drag by remember { mutableFloatStateOf(0f) }
            Box(Modifier.padding(top = 6.dp).width(artSize.dp).height(
                artSize.dp
            ).pointerInput(player) {
                detectHorizontalDragGestures(onDragStart = { drag = 0f }, onDragEnd = {
                    if (drag < -100 && player.hasNextMediaItem()) player.seekToNextMediaItem()
                    else if (drag > 100) player.seekToPrevious()
                }) { change, amount -> change.consume(); drag += amount }
            }.pointerInput(player) {
                var vertical = 0f
                detectDragGesturesAfterLongPress(onDragStart = { vertical = 0f }, onDragEnd = {
                    if (vertical > 80.dp.toPx()) { player.stop(); player.clearMediaItems(); app.audioControl.sleepUntil.value = 0; onDismiss() }
                }) { change, delta -> change.consume(); vertical += delta.y }
            }) {
                Artwork(app, player.currentMediaItem?.mediaId, artSize, true,
                    Modifier.align(Alignment.BottomCenter).size(artSize.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, androidx.compose.foundation.shape.RoundedCornerShape(20.dp)),
                    androidx.compose.foundation.shape.RoundedCornerShape(20.dp), player.mediaMetadata.artworkUri?.toString() ?: song?.thumbnail)
                androidx.compose.animation.AnimatedVisibility(seekFeedback != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
                    Surface(shape = CircleShape, color = Ink.copy(alpha = .86f)) {
                        Text(seekFeedback.orEmpty(), Modifier.padding(horizontal = 18.dp, vertical = 12.dp), color = Lime)
                    }
                }
            }
            CompactSeek(reading, player)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(displayTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(displayArtist, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(like, enabled = song != null) {
                    Icon(if (liked) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                        if (liked) "Unlike song" else "Like song", tint = if (liked) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                }
            }
            reading.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton({ player.shuffleModeEnabled = !shuffle }) {
                    Icon(painterResource(R.drawable.ic_music_shuffle), if (shuffle) "Shuffle on" else "Shuffle off",
                        tint = if (shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton({ player.seekToPrevious() }, Modifier.size(48.dp)) { Icon(painterResource(R.drawable.ic_media_prev), "Previous track") }
                FilledIconButton(toggle, Modifier.size(72.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Lime, contentColor = Ink)) {
                    if (player.playbackState == Player.STATE_BUFFERING) CircularProgressIndicator(Modifier.size(28.dp), color = Ink, strokeWidth = 2.dp)
                    else Icon(painterResource(if (reading.playing) R.drawable.ic_media_pause else R.drawable.ic_media_play),
                        if (reading.playing) "Pause" else "Play", Modifier.size(32.dp))
                }
                IconButton({ player.seekToNextMediaItem() }, Modifier.size(48.dp), enabled = player.hasNextMediaItem()) {
                    Icon(painterResource(R.drawable.ic_media_next), "Next track")
                }
                IconButton({ player.repeatMode = nextRepeatMode(repeat) }) {
                    Icon(painterResource(if (repeat == Player.REPEAT_MODE_ONE) R.drawable.ic_music_repeat_one else R.drawable.ic_music_repeat),
                        when (repeat) { Player.REPEAT_MODE_ONE -> "Repeat one"; Player.REPEAT_MODE_ALL -> "Repeat all"; else -> "Repeat off" },
                        tint = if (repeat != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Surface(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(22.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ panel = "Queue" }) { Icon(painterResource(R.drawable.ic_music_queue), "Queue", Modifier.size(23.dp)) }
                    IconButton({ panel = "Lyrics" }) { Icon(painterResource(R.drawable.ic_music_lyrics), "Lyrics", Modifier.size(23.dp)) }
                    ChoiceMenu("${reading.speed}\u00d7 speed", listOf(.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f), { "${it}\u00d7" },
                        icon = R.drawable.ic_music_speed, sheetTitle = "Playback speed", active = reading.speed != 1f) {
                        player.setPlaybackSpeed(it)
                    }
                    ChoiceMenu(if (timer > 0) "Sleep timer · ${durationLabel(((timer - now).coerceAtLeast(0) / 1000))} remaining" else "Sleep timer",
                        listOf(0, 15, 30, 45, 60, 90), { if (it == 0) "Off / cancel timer" else "$it minutes" },
                        icon = R.drawable.ic_music_moon, sheetTitle = "Sleep timer", active = timer > 0) {
                        app.audioControl.sleepUntil.value = sleepDeadline(SystemClock.elapsedRealtime(), it)
                    }
                }
            }
        }
    }
    if (panel == "Lyrics") LyricsSheet(app, player, song, { panel = null },
        { lyricsPicker.launch(arrayOf("text/*", "application/octet-stream")) }, message)
    panel?.takeUnless { it == "Lyrics" }?.let { name -> ModalBottomSheet(onDismissRequest = { panel = null }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(horizontal = 24.dp).navigationBarsPadding()) {
            Text(name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 12.dp))
            when (name) {
                "Queue" -> LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(bottom = 20.dp)) {
                    itemsIndexed(queue) { index, item ->
                        ListItem(headlineContent = { Text(item.mediaMetadata.title?.toString() ?: "Audio", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { if (index == player.currentMediaItemIndex) Text("Now playing") },
                            modifier = Modifier.clickable { player.seekToDefaultPosition(index); player.prepare(); player.play() },
                            trailingContent = { Row {
                                IconButton({ player.moveMediaItem(index, index - 1) }, enabled = index > 0) { Icon(Icons.Outlined.KeyboardArrowUp, "Move earlier") }
                                IconButton({ player.moveMediaItem(index, index + 1) }, enabled = index < queue.lastIndex) { Icon(Icons.Outlined.KeyboardArrowDown, "Move later") }
                                IconButton({ player.removeMediaItem(index) }) { Icon(Icons.Outlined.Close, "Remove from queue") }
                            } })
                    }
                }

            }
        }
    } }
}

internal fun nextRepeatMode(mode: Int): Int = when (mode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
internal fun sleepDeadline(now: Long, minutes: Int): Long = if (minutes <= 0) 0 else now + minutes.coerceAtMost(180) * 60_000L

private fun audioOutput(context: Context): String {
    val manager = context.getSystemService(AudioManager::class.java)
    val active = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter {
        it.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
    }
    return active.map { if (it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) "Device speaker" else it.productName.toString() }
        .distinct().joinToString(" · ").ifBlank { "System audio output" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompactSeek(reading: Playback, player: Player, darkSurface: Boolean = false, onInteract: () -> Unit = {}) {
    var dragging by remember(player.currentMediaItem?.mediaId) { mutableStateOf<Float?>(null) }
    val duration = reading.duration.coerceAtLeast(1).toFloat()
    val value = (dragging ?: reading.position.toFloat()).coerceIn(0f, duration)
    val track = if (darkSurface) androidx.compose.ui.graphics.Color.White.copy(alpha = .25f) else MaterialTheme.colorScheme.outlineVariant
    val timeColor = if (darkSurface) androidx.compose.ui.graphics.Color.White.copy(alpha = .75f) else MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        Slider(value, { dragging = it; onInteract() }, Modifier.fillMaxWidth().height(36.dp).semantics { contentDescription = "Track position" },
            enabled = reading.duration > 0 && player.isCurrentMediaItemSeekable, valueRange = 0f..duration,
            onValueChangeFinished = { dragging?.let { player.seekTo(it.toLong()) }; dragging = null },
            thumb = { Box(Modifier.size(14.dp).background(Lime, CircleShape).border(1.dp, Ink, CircleShape)) },
            track = { Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                drawLine(track, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), size.height, StrokeCap.Round)
                drawLine(Lime, Offset(0f, size.height / 2), Offset(size.width * value / duration, size.height / 2), size.height, StrokeCap.Round)
            } })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(playbackTime(value.toLong()), style = MaterialTheme.typography.labelMedium, color = timeColor)
            Text(playbackTime(reading.duration), style = MaterialTheme.typography.labelMedium, color = timeColor)
        }
    }
}
