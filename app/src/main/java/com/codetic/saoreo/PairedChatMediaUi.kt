package com.codetic.saoreo

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlin.math.sqrt
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

internal class PairedMediaProvider : FileProvider()

internal class ChatRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var start = 0L
    private val levels = ArrayList<Int>()
    val elapsed get() = (SystemClock.elapsedRealtime() - start).coerceAtLeast(0)

    fun start() {
        check(recorder == null)
        val output = File.createTempFile("voice-", ".m4a", context.cacheDir)
        val input = MediaRecorder(context)
        try {
            input.setAudioSource(MediaRecorder.AudioSource.MIC)
            input.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            input.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            input.setAudioChannels(1)
            input.setAudioSamplingRate(44100)
            input.setAudioEncodingBitRate(64000)
            input.setOutputFile(output.absolutePath)
            input.prepare()
            input.start()
        } catch (failure: Exception) {
            input.release()
            output.delete()
            throw failure
        }
        recorder = input
        file = output
        start = SystemClock.elapsedRealtime()
        levels.clear()
    }

    fun liveLevels(): List<Int> = levels.takeLast(160)

    fun sample() {
        val amplitude = recorder?.maxAmplitude ?: return
        levels.add(amplitude.coerceIn(0, 32767))
    }

    fun stop(): RecordedNote? {
        val input = recorder ?: return null
        val output = checkNotNull(file)
        recorder = null
        file = null
        try {
            input.stop()
        } catch (_: RuntimeException) {
            output.delete()
            return null
        } finally {
            input.release()
        }
        val duration = runCatching {
            MediaMetadataRetriever().use {
                it.setDataSource(output.absolutePath)
                it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            }
        }.getOrNull() ?: 0L
        if (duration < 250) {
            output.delete()
            return null
        }
        val bins = minOf(24, levels.size)
        val waveform = List(bins) { index ->
            val from = index * levels.size / bins
            val to = (index + 1) * levels.size / bins
            val meanSquare = levels.subList(from, to).map { it.toDouble() * it }.average()
            // Compress the measured dynamic range so quieter sections remain visible.
            (sqrt(sqrt(meanSquare) / 32767.0) * 100).roundToInt()
        }
        return RecordedNote(output, duration, waveform)
    }

    fun cancel() {
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null
        file?.delete()
        file = null
    }
}

internal data class RecordedNote(val file: File, val duration: Long, val waveform: List<Int>)

internal fun chatMediaDuration(milliseconds: Long): String =
    "%d:%02d".format(milliseconds / 60_000, milliseconds / 1000 % 60)

@Composable
internal fun ChatWaveform(levels: List<Int>, color: Color, modifier: Modifier = Modifier,
    fraction: Float = 1f, compact: Boolean = true) {
    Canvas(modifier.height(if (compact) 17.dp else 24.dp)) {
        if (levels.isEmpty()) {
            drawLine(color.copy(alpha = .34f), Offset(0f, center.y), Offset(size.width, center.y), 1.dp.toPx())
            drawLine(color, Offset(0f, center.y), Offset(size.width * fraction, center.y), 1.dp.toPx())
        } else {
            val interval = size.width / levels.size
            levels.forEachIndexed { index, value ->
                val x = interval * (index + .5f)
                val amplitude = size.height * (.045f + value * .004f)
                val tint = color.copy(alpha = if (x <= size.width * fraction) color.alpha else color.alpha * .38f)
                drawLine(tint, Offset(x, center.y - amplitude), Offset(x, center.y + amplitude),
                    strokeWidth = (if (compact) 1.25.dp else 2.dp).toPx(), cap = StrokeCap.Round)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun ChatAttachmentBubble(pairing: TogetherPairing, message: PairedMessage, compact: Boolean = false,
    interactive: Boolean = true, gallery: List<PairedMessage> = emptyList(), onSelect: () -> Unit = {},
    mediaFooter: (@Composable () -> Unit)? = null) {
    val item = message.attachment ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var local by remember(item.id) { mutableStateOf(pairing.cachedAttachment(message)) }
    var viewing by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(item.id, message.outgoing) {
        if (message.outgoing && item.isVisualMedia && local == null)
            local = runCatching { pairing.openAttachment(message) }.getOrNull()
    }
    var opening by remember(item.id) { mutableStateOf(false) }
    var error by remember(item.id) { mutableStateOf<String?>(null) }
    var player by remember(item.id) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember(item.id) { mutableStateOf(false) }
    var position by remember(item.id) { mutableFloatStateOf(0f) }
    var scrubPosition by remember(item.id) { mutableStateOf<Float?>(null) }
    LaunchedEffect(playing, player) {
        while (playing) {
            position = runCatching { player?.currentPosition?.toFloat() ?: 0f }.getOrDefault(0f)
            kotlinx.coroutines.delay(90)
        }
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(lifecycleState) {
        if (lifecycleState != androidx.lifecycle.Lifecycle.State.RESUMED && playing) {
            player?.pause()
            playing = false
        }
    }
    LaunchedEffect(playing, message.id) {
        if (playing && !message.outgoing) pairing.markVoicePlayed(message)
    }
    DisposableEffect(item.id) { onDispose { player?.release() } }
    fun open(file: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".pairedmedia", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
    fun play(file: File) {
        val existing = player
        if (existing != null && playing) {
            existing.pause()
            playing = false
        } else if (existing != null) {
            if (position >= item.duration) { existing.seekTo(0); position = 0f }
            existing.start()
            playing = true
        } else {
            val audio = MediaPlayer()
            try {
                audio.setDataSource(file.absolutePath)
                audio.setOnPreparedListener {
                    if (player == it) {
                        opening = false
                        if (lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED) {
                            it.start()
                            playing = true
                        }
                    }
                }
                audio.setOnCompletionListener { playing = false; position = item.duration.toFloat() }
                audio.setOnErrorListener { _, _, _ ->
                    error = "Couldn’t play this voice note"; opening = false; playing = false
                    player?.release()
                    player = null
                    true
                }
                player = audio
                opening = true
                audio.prepareAsync()
            } catch (failure: Exception) {
                audio.release()
                player = null
                throw failure
            }
        }
    }

    val preview = remember(item) { item.previewBitmap() }
    val localImage = local?.takeIf { item.mime.startsWith("image/") && it.isFile }
    val visual = item.isVisualMedia
    val video = item.mime.startsWith("video/")
    fun openMedia() {
        if (opening) return
        if (visual) {
            viewing = true
            return
        }
        scope.launch {
            opening = true
            error = null
            try {
                open(local?.takeIf(File::isFile) ?: pairing.openAttachment(message).also { local = it })
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { error = attachmentFailure(failure) }
            finally { opening = false }
        }
    }

    if (visual) {
        val ratio = (preview?.let { it.width.toFloat() / it.height } ?: if (video) 1.6f else .82f)
            .coerceIn(.58f, 1.85f)
        val height = (218f / ratio).coerceIn(112f, 248f)
        val width = (height * ratio).coerceAtMost(218f)
        Box(Modifier.width(width.dp).height(height.dp).background(Color(0xff191b1e))
            .combinedClickable(enabled = interactive, role = Role.Button, onClickLabel = if (video) "Open video" else "Open image",
                onClick = ::openMedia, onLongClick = onSelect), contentAlignment = Alignment.Center) {
            ChatTilePreview(pairing, message, Modifier.fillMaxSize(), local)
            if (video || localImage == null && preview == null) {
                Box(Modifier.size(40.dp).background(Color.Black.copy(alpha = .62f), CircleShape),
                    contentAlignment = Alignment.Center) {
                    Icon(painterResource(if (video) R.drawable.ic_media_play else R.drawable.ic_profile_image),
                        if (video) "Play video" else "Open image", Modifier.size(19.dp), Color.White)
                }
            }
            ChatTransferStatus(pairing, item)
            if (interactive) ChatRetryUploads(pairing, listOf(message))
            if (mediaFooter != null || video && item.duration > 0) {
                Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .74f))))
                    .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (video && item.duration > 0) Text(chatMediaDuration(item.duration), color = Color.White,
                        style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.weight(1f))
                    mediaFooter?.invoke()
                }
            }
        }
        if (viewing) ChatMediaViewer(pairing, message, onLoaded = { local = pairing.cachedAttachment(message) },
            album = gallery, initialIndex = gallery.indexOfFirst { it.id == message.id && it.outgoing == message.outgoing }.coerceAtLeast(0)) { viewing = false }
        return
    }
    val voiceRow = if (compact) Modifier.width(180.dp) else Modifier.widthIn(min = 206.dp)
    Row(if (item.voice) voiceRow else Modifier, verticalAlignment = Alignment.CenterVertically) {
        if (item.voice) {
            IconButton(onClick = {
                scope.launch {
                    opening = true
                    error = null
                    try {
                        val file = local?.takeIf(File::isFile) ?: pairing.openAttachment(message).also { local = it }
                        opening = false
                        play(file)
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) {
                        opening = false
                        error = attachmentFailure(failure)
                    }
                }
            }, modifier = if (compact) Modifier.size(36.dp) else Modifier, enabled = !opening && interactive) {
                val glyph = painterResource(if (playing) R.drawable.ic_media_pause else R.drawable.ic_media_play)
                val label = if (playing) "Pause voice note" else "Play voice note"
                if (compact) {
                    val foreground = LocalContentColor.current
                    val lightControl = foreground.luminance() > .43f
                    Box(Modifier.size(26.dp).background(foreground.copy(alpha = if (lightControl) .14f else .88f),
                        CircleShape), contentAlignment = Alignment.Center) {
                        Icon(glyph, label, Modifier.size(13.dp), tint = Color.White)
                    }
                } else Icon(glyph, label)
            }
            val color = LocalContentColor.current
            val displayedPosition = scrubPosition ?: position
            val fraction = (displayedPosition / item.duration.coerceAtLeast(1)).coerceIn(0f, 1f)
            Slider(fraction, { scrubPosition = it * item.duration }, Modifier.weight(1f).height(30.dp)
                .semantics { contentDescription = "Voice note playback position" },
                enabled = interactive && player != null && !opening && item.duration > 0,
                onValueChangeFinished = {
                    val target = scrubPosition
                    scrubPosition = null
                    if (target != null) {
                        position = target
                        runCatching { player?.seekTo(target.toLong(), MediaPlayer.SEEK_CLOSEST) }
                    }
                }, thumb = { Box(Modifier.size(0.dp)) }, track = {
                    ChatWaveform(item.waveform, color, Modifier.fillMaxWidth(), fraction, compact)
                })
            val shownTime = if (playing || displayedPosition > 0f || scrubPosition != null)
                displayedPosition.toLong() else item.duration
            Text(chatMediaDuration(shownTime),
                Modifier.padding(horizontal = if (compact) 5.dp else 8.dp),
                style = if (compact) MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, letterSpacing = 0.sp)
                    else MaterialTheme.typography.labelSmall)
        } else {
            Column {
                Text(item.name, maxLines = 2)
                TextButton(onClick = ::openMedia, enabled = !opening, shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text(if (opening) "Opening…" else "Open attachment", color = LocalContentColor.current)
                }
            }
        }
    }
    if (opening && item.voice) LinearProgressIndicator(if (compact) Modifier.width(180.dp) else Modifier.fillMaxWidth())
    if (interactive) ChatRetryUploads(pairing, listOf(message))
    error?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
}

internal val ChatAttachment.isVisualMedia: Boolean
    get() = mime.startsWith("image/") || mime.startsWith("video/")

internal fun attachmentFailure(error: Exception): String = when {
    error is ChatHttpError && error.code == 410 -> "Attachment expired before download. Ask your partner to resend it."
    error is android.content.ActivityNotFoundException -> "No app can open this file type."
    else -> "Couldn’t open the attachment. Tap to retry."
}

