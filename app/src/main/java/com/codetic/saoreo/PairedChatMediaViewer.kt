package com.codetic.saoreo

import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun ChatMediaViewer(pairing: TogetherPairing, message: PairedMessage,
    onLoaded: (File) -> Unit = {}, album: List<PairedMessage> = emptyList(), initialIndex: Int = 0,
    close: () -> Unit) {
    val pages = album.ifEmpty { listOf(message) }
    val pager = rememberPagerState(initialPage = initialIndex.coerceIn(pages.indices), pageCount = { pages.size })
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false, dismissOnClickOutside = false)) {
        HorizontalPager(pager, Modifier.fillMaxSize().background(Color.Black), beyondViewportPageCount = 1) { index ->
            ChatMediaPage(pairing, pages[index], pager.settledPage == index, pager.isScrollInProgress,
                onLoaded, close)
        }
    }
}

@Composable
private fun ChatMediaPage(pairing: TogetherPairing, activeMessage: PairedMessage, active: Boolean, scrolling: Boolean,
    onLoaded: (File) -> Unit, close: () -> Unit) {
    val item = activeMessage.attachment ?: return
    val pairingMediaContext = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val video = item.mime.startsWith("video/")
    val date = remember(activeMessage.time) {
        DateTimeFormatter.ofPattern("MMMM d, h:mm a", java.util.Locale.getDefault())
            .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(activeMessage.time))
    }
    var file by remember(item.id) { mutableStateOf(pairing.cachedAttachment(activeMessage)?.takeIf(File::isFile)) }
    var error by remember(item.id) { mutableStateOf<String?>(null) }
    var attempt by remember(item.id) { mutableIntStateOf(0) }
    var controls by remember(item.id) { mutableStateOf(true) }
    var interaction by remember(item.id) { mutableIntStateOf(0) }
    fun showControls() { controls = true; interaction++ }
    fun toggleControls() { if (controls) controls = false else showControls() }
    LaunchedEffect(item.id, attempt, active) {
        if (active && file == null) try {
            error = null
            file = pairing.openAttachment(activeMessage)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            error = attachmentFailure(failure)
        }
    }
    LaunchedEffect(file, active) { if (active) file?.let(onLoaded) }
    LaunchedEffect(controls, interaction, file) {
        if (controls && file != null && video) {
            delay(4_000)
            controls = false
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = ::toggleControls),
        contentAlignment = Alignment.Center) {
            val source = file
            val thumbnail = remember(item) { item.previewBitmap() }
            if (source != null && !video) AsyncImage(source, "Photo", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else if (source != null && active && !scrolling) {
                ChatViewerVideo(Uri.fromFile(source), controls, ::showControls, ::toggleControls)
            } else if (thumbnail != null) androidx.compose.foundation.Image(thumbnail.asImageBitmap(), if (video) "Video" else "Photo",
                Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            if (source == null && active && !scrolling) {
                if (error == null) ChatTransferStatus(pairing, item, fallback = true)
                else Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error.orEmpty(), color = Color.White)
                    TextButton({ attempt++ }) { Text("Retry") }
                }
            }
            if (active && !scrolling && (controls || source == null)) Row(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .background(Color.Black.copy(alpha = .72f)).statusBarsPadding(),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(close) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Close media", tint = Color.White) }
                Text(date, Modifier.weight(1f).padding(end = 8.dp), color = Color.White, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                IconButton({
                    if (source != null) scope.launch {
                        val label = try {
                            saveChatGallery(pairingMediaContext, item, source)
                            "Saved to gallery"
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { "Couldn’t save to gallery." }
                        android.widget.Toast.makeText(pairingMediaContext, label, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }, enabled = source != null) {
                    Icon(painterResource(R.drawable.ic_nav_downloads), "Save to gallery",
                        tint = Color.White.copy(alpha = if (source == null) .4f else 1f))
                }
                IconButton({
                    val context = pairingMediaContext
                    if (source == null) return@IconButton
                    val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".pairedmedia", source)
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType(item.mime)
                        .putExtra(android.content.Intent.EXTRA_STREAM, uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (activity is TogetherActivity) activity.launchTogetherShare(send)
                    else activity?.startActivity(android.content.Intent.createChooser(send, null))
                }, enabled = source != null) {
                    Icon(Icons.Outlined.Share, "Share", tint = Color.White.copy(alpha = if (source == null) .4f else 1f))
                }
            }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatViewerVideo(uri: Uri, controls: Boolean, interact: () -> Unit, toggleControls: () -> Unit,
    poster: android.graphics.Bitmap? = null, padNavigationBars: Boolean = true) {
    val context = LocalContext.current
    val currentInteract by rememberUpdatedState(interact)
    val currentToggle by rememberUpdatedState(toggleControls)
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            playWhenReady = false
            prepare()
        }
    }
    var playing by remember(player) { mutableStateOf(player.isPlaying) }
    var position by remember(player) { mutableLongStateOf(0L) }
    var duration by remember(player) { mutableLongStateOf(0L) }
    var frameRendered by remember(player) { mutableStateOf(false) }
    var muted by remember(player) { mutableStateOf(false) }
    var scrub by remember(player) { mutableStateOf<Float?>(null) }
    var playbackError by remember(player) { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onRenderedFirstFrame() { frameRendered = true }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { playbackError = true }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(lifecycleState, player) {
        if (lifecycleState != androidx.lifecycle.Lifecycle.State.RESUMED) player.pause()
    }
    LaunchedEffect(player, controls, playing) {
        while (controls || !playing) {
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0)
            delay(150)
        }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { PlayerView(it).apply { useController = false } },
            modifier = Modifier.fillMaxSize(), update = { it.player = player })
        if (poster != null && (!frameRendered || !playing && position == 0L)) {
            androidx.compose.foundation.Image(poster.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        Box(Modifier.fillMaxSize().pointerInput(player) {
            detectTapGestures(onTap = { currentToggle() }, onDoubleTap = {
                if (player.duration > 0) player.seekTo((player.currentPosition + 10_000).coerceAtMost(player.duration))
                currentInteract()
            })
        })
        if (playbackError) Text("Couldn’t play this video.", Modifier.align(Alignment.Center), color = Color.White)
        if ((controls || !playing) && !playbackError) Box(
            Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .52f), CircleShape)) {
            IconButton({
                if (playing) player.pause() else {
                    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                    player.play()
                }
                interact()
            }, Modifier.size(52.dp)) {
                Icon(painterResource(if (playing) R.drawable.ic_media_pause else R.drawable.ic_media_play),
                    if (playing) "Pause video" else "Play video", tint = Color.White)
            }
        }
        if ((controls || !playing) && !playbackError) {
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .then(if (padNavigationBars) Modifier.navigationBarsPadding() else Modifier)
                .padding(horizontal = 10.dp, vertical = 9.dp)
                .background(Color(0xba1a1d20), CircleShape).height(46.dp).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val fraction = scrub ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                Text(chatMediaDuration((fraction * duration).toLong()), color = Color.White,
                    style = MaterialTheme.typography.labelMedium)
                Slider(value = fraction, onValueChange = { scrub = it; interact() },
                    enabled = duration > 0, modifier = Modifier.weight(1f).height(46.dp),
                    onValueChangeFinished = {
                        scrub?.let { player.seekTo((it * duration).toLong()) }
                        scrub = null
                        interact()
                    }, thumb = { Box(Modifier.size(9.dp).background(Color.White, CircleShape)) },
                    track = {
                        Canvas(Modifier.fillMaxWidth().height(2.dp)) {
                            drawRect(Color.White.copy(alpha = .35f))
                            drawRect(Color.White, size = androidx.compose.ui.geometry.Size(size.width * fraction, size.height))
                        }
                    })
                Text(chatMediaDuration(duration), color = Color.White, style = MaterialTheme.typography.labelMedium)
                IconButton({
                    muted = !muted
                    player.volume = if (muted) 0f else 1f
                    interact()
                }, Modifier.size(40.dp)) {
                    Icon(painterResource(if (muted) R.drawable.ic_media_mute else R.drawable.ic_media_volume),
                        if (muted) "Unmute video" else "Mute video", Modifier.size(18.dp), Color.White)
                }
            }
        }
    }
}
