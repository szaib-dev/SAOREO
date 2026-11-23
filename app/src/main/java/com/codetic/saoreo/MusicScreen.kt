package com.codetic.saoreo

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MusicActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as SaoreoApp
        val selected = intent.getStringExtra("song")
        val nowPlaying = intent.getBooleanExtra("playing", false)
        setContent {
            val settings by app.preferences.flow.collectAsStateWithLifecycle(Settings())
            AppTheme(settings) { Surface { MusicLibrary(app, selected, nowPlaying, ::finish) } }
        }
    }
    companion object {
        internal fun open(context: Context, song: String? = null, nowPlaying: Boolean = false) =
            Intent(context, MusicActivity::class.java).putExtra("song", song).putExtra("playing", nowPlaying)
    }
}

private data class SongTags(val title: String?, val artist: String?, val album: String?, val duration: Long)

private suspend fun songTags(app: SaoreoApp, uri: String): SongTags = withContext(Dispatchers.IO) {
    runCatching {
        MediaMetadataRetriever().use { reader ->
            reader.setDataSource(app, Uri.parse(uri))
            SongTags(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf(String::isNotBlank),
                reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf(String::isNotBlank),
                reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf(String::isNotBlank),
                reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0)
        }
    }.getOrDefault(SongTags(null, null, null, 0))
}

@Composable
internal fun Artwork(app: SaoreoApp, uri: String?, size: Int, artwork: Boolean,
                     modifier: Modifier = Modifier.size(size.dp),
                     shape: Shape = RoundedCornerShape(if (size > 80) 26.dp else 14.dp),
                     coverUrl: String? = null) {
    val art by produceState<android.graphics.Bitmap?>(null, uri, artwork, size) {
        value = null
        value = if (uri == null || !artwork) null else withContext(Dispatchers.IO) {
            runCatching {
                MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(app, Uri.parse(uri))
                    val encoded = retriever.embeddedPicture?.takeIf { it.size <= 2_000_000 } ?: return@use null
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
                    if (bounds.outWidth !in 1..8192 || bounds.outHeight !in 1..8192) return@use null
                    val options = android.graphics.BitmapFactory.Options()
                    val target = size * 3
                    while (maxOf(bounds.outWidth, bounds.outHeight) / (options.inSampleSize.coerceAtLeast(1) * 2) >= target)
                        options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
                    android.graphics.BitmapFactory.decodeByteArray(encoded, 0, encoded.size, options)
                }
            }.getOrNull()
        }
    }
    Box(modifier.clip(shape), contentAlignment = Alignment.Center) {
        RecordArt(Modifier.fillMaxSize(), uri?.hashCode() ?: coverUrl?.hashCode() ?: 0, shape)
        val cover = art ?: coverUrl
        if (cover != null) AsyncImage(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

private fun musicItem(song: Download, tags: SongTags?): MediaItem =
    localAudioItem(requireNotNull(song.galleryUri), tags?.title ?: song.title, tags?.artist, tags?.album)

@Composable
internal fun rememberMusicController(app: SaoreoApp): Pair<MediaController?, Boolean> {
    var player by remember { mutableStateOf<MediaController?>(null) }
    var failed by remember { mutableStateOf(false) }
    DisposableEffect(app) {
        val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, LibraryAudioService::class.java))).buildAsync()
        future.addListener({
            runCatching { future.get() }.onSuccess { player = it }.onFailure { failed = true }
        }, ContextCompat.getMainExecutor(app))
        onDispose { MediaController.releaseFuture(future) }
    }
    return player to failed
}

@Composable
internal fun MusicMiniPlayer(app: SaoreoApp, player: MediaController?, onOpen: () -> Unit) {
    if (player == null) return
    var song by remember(player) { mutableStateOf(player.currentMediaItem) }
    var lastSong by remember(player) { mutableStateOf(player.currentMediaItem) }
    val reading = rememberPlayback(player)
    var drag by remember(player) { mutableFloatStateOf(0f) }
    var dismissing by remember(player) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var motion by remember(player) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun clear() {
        player.stop()
        player.clearMediaItems()
        app.audioControl.sleepUntil.value = 0
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                val next = player.currentMediaItem
                if (next != null && next != song) {
                    motion?.cancel()
                    drag = 0f
                    dismissing = false
                }
                song = next
                if (song != null) lastSong = song
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    AnimatedVisibility(song != null, enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { it },
        exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { it }) {
        val track = lastSong
        val trackUri = track?.mediaId
        val cover by key(trackUri) {
            produceState(track?.mediaMetadata?.artworkUri?.toString(), app, trackUri) {
                if (value == null && trackUri != null) value = app.dao.byUri(trackUri)?.thumbnail
            }
        }
        Box(Modifier.pointerInput(player) {
            fun settle(dismiss: Boolean) {
                if (dismissing) return
                dismissing = dismiss
                motion?.cancel()
                val target = if (dismiss) maxOf(size.height.toFloat(), drag) else 0f
                val trackOnRelease = song
                motion = scope.launch {
                    Animatable(drag).animateTo(target, tween(160)) { drag = value }
                    if (dismiss && song == trackOnRelease) clear()
                }
            }
            detectVerticalDragGestures(onDragStart = { if (!dismissing) motion?.cancel() },
                onDragCancel = { settle(false) }, onDragEnd = { settle(drag >= 42.dp.toPx()) }) { change, amount ->
                if (!dismissing) {
                    change.consume()
                    drag = (drag + amount).coerceAtLeast(0f)
                }
            }
        }.semantics {
            customActions = listOf(CustomAccessibilityAction("Clear mini-player") { clear(); true })
        }) {
        Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .offset { IntOffset(0, drag.roundToInt()) },
            color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(20.dp)) {
            Column {
                Row(Modifier.clickable(onClick = onOpen).padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(app, trackUri, 36, true, shape = CircleShape, coverUrl = cover)
                    Column(Modifier.weight(1f).padding(start = 10.dp, end = 2.dp)) {
                        Text(track?.mediaMetadata?.title?.toString() ?: "Audio", maxLines = 1,
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PlayingBars(reading.playing)
                            track?.mediaMetadata?.artist?.toString()?.takeIf { it.isNotBlank() }?.let {
                                Text(it, maxLines = 1, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    IconButton({ player.seekToPrevious() }) { Icon(painterResource(R.drawable.ic_media_prev), "Previous track", Modifier.size(22.dp)) }
                    FilledIconButton({
                        if (player.playWhenReady) player.pause() else {
                            if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
                            player.prepare(); player.play()
                        }
                    }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Lime, contentColor = Ink)) {
                        Icon(painterResource(if (reading.playing) R.drawable.ic_media_pause else R.drawable.ic_media_play),
                            if (reading.playing) "Pause" else "Play", Modifier.size(22.dp))
                    }
                    IconButton({ if (player.hasNextMediaItem()) player.seekToNextMediaItem() else player.seekToDefaultPosition(0) }) {
                        Icon(painterResource(R.drawable.ic_media_next), "Next track", Modifier.size(22.dp))
                    }
                }
                if (reading.duration > 0) LinearProgressIndicator(
                    progress = { (reading.position.toFloat() / reading.duration).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(2.dp), color = Lime, trackColor = Color.Transparent)
            }
        }
        }
    }
}

@Composable
internal fun MusicLibrary(app: SaoreoApp, selected: String?, launchPlayer: Boolean, close: () -> Unit,
                          controller: MediaController? = null, embedded: Boolean = false, initialSection: String = "Songs",
                          onDownload: (() -> Unit)? = null) {
    val context = LocalContext.current
    val standalone = if (embedded) null else rememberMusicController(app)
    val connectionError = standalone?.second ?: false
    val loadedSongs by app.dao.audio().collectAsStateWithLifecycle(initialValue = null)
    val songs = loadedSongs.orEmpty()
    val playlists by app.collections.playlists.collectAsStateWithLifecycle(initialValue = emptyList())
    val membership by app.collections.dao.tracks().collectAsStateWithLifecycle(initialValue = emptyList())
    val states by app.collections.dao.songStates().collectAsStateWithLifecycle(initialValue = emptyList())
    val tags = remember { mutableStateMapOf<String, SongTags>() }
    val embeddedTags = remember { mutableMapOf<String, SongTags>() }
    LaunchedEffect(songs) {
        for (song in songs) {
            val uri = song.galleryUri ?: continue
            val original = embeddedTags.getOrPut(uri) { songTags(app, uri) }
            tags[uri] = original.copy(title = song.localTitle ?: original.title,
                artist = song.artist ?: original.artist, album = song.album ?: original.album)
            if (song.duration == 0L && original.duration > 0) app.dao.setDuration(song.id, original.duration)
        }
    }
    var section by rememberSaveable { mutableStateOf(initialSection) }
    var collection by rememberSaveable { mutableStateOf<String?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var full by rememberSaveable { mutableStateOf(launchPlayer || selected != null) }
    var consumed by rememberSaveable { mutableStateOf(false) }
    var form by remember { mutableStateOf<String?>(null) }
    var deletePlaylist by remember { mutableStateOf<MusicPlaylist?>(null) }
    var songOptions by remember { mutableStateOf<Download?>(null) }
    var removeSong by remember { mutableStateOf<Download?>(null) }
    var remoteImport by rememberSaveable { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Download?>(null) }
    var sort by rememberSaveable { mutableStateOf("Date added") }
    var addingTracks by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val player = controller ?: standalone?.first
    val stateBySong = states.associateBy { it.song }
    val currentQueue = remember(player) { mutableStateOf(emptyList<MediaItem>()) }
    var playbackRequested by remember(player) { mutableStateOf(false) }
    var currentUri by remember { mutableStateOf<String?>(null) }
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    if (player != null) DisposableEffect(player) {
        fun sync() {
            currentUri = player.currentMediaItem?.mediaId
            currentQueue.value = (0 until player.mediaItemCount).map(player::getMediaItemAt)
            playbackRequested = player.playWhenReady && player.playbackState in setOf(Player.STATE_BUFFERING, Player.STATE_READY)
            shuffle = player.shuffleModeEnabled
            repeat = player.repeatMode
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { sync() }
        }
        sync()
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    val reading = if (player != null) rememberPlayback(player) else Playback()
    fun perform(block: suspend () -> Unit) = scope.launch {
        error = null
        try { block() } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { error = "Couldn’t update the music library. Check file access and try again." }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) perform {
            importing = true
            var added = 0
            try {
                for (uri in uris.distinct()) {
                    try {
                        val song = app.collections.importAudio(uri)
                        if (section == "Playlists" && collection != null) app.collections.addSong(requireNotNull(collection), song)
                        added++
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { error = "Some files could not be imported. Check file access and try again." }
                }
                toast(app, "$added song${if (added == 1) "" else "s"} added to Music")
            } finally { importing = false }
        }
    }
    fun togglePlayback() {
        if (player == null) return
        if (player.isPlaying || player.playWhenReady && player.playbackState == Player.STATE_BUFFERING) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
            player.prepare()
            player.play()
        }
    }
    fun play(list: List<Download>, song: Download) {
        if (player == null) return
        val index = list.indexOf(song)
        if (index < 0) return
        val sameQueue = player.mediaItemCount == list.size &&
            list.indices.all { player.getMediaItemAt(it).mediaId == list[it].galleryUri }
        val currentSong = player.currentMediaItem?.mediaId == song.galleryUri
        if (!sameQueue) player.setMediaItems(list.map { musicItem(it, tags[it.galleryUri]) }, index,
            if (currentSong) player.currentPosition else 0)
        else if (!currentSong || player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition(index)
        player.prepare()
        player.play()
    }
    LaunchedEffect(player, songs, selected) {
        val target = songs.firstOrNull { it.id == selected }
        if (!consumed && player != null && target != null) { consumed = true; play(songs, target) }
    }
    val albumOrArtist = section == "Artists" || section == "Albums"
    fun group(song: Download): String {
        val metadata = tags[song.galleryUri]
        return if (section == "Artists") metadata?.artist ?: "Unknown artist" else metadata?.album ?: "Unknown album"
    }
    val playlist = playlists.firstOrNull { it.id == collection && section == "Playlists" }
    val musicListState = rememberLazyListState()
    val reorder = remember(musicListState) { PlaylistReorder(musicListState) }
    val savedPlaylistOrder = playlists.map { it.id }
    val playlistById = playlists.associateBy { it.id }
    val shownPlaylistOrder = ((reorder.draft ?: emptyList()) + savedPlaylistOrder).distinct()
    LaunchedEffect(savedPlaylistOrder, reorder.dragging) { reorder.acceptSaved(savedPlaylistOrder) }
    val reorderEdge = with(LocalDensity.current) { 56.dp.toPx() }
    if (!full && section == "Playlists" && playlist == null) PlaylistDragScroll(musicListState, reorder, reorderEdge)
    fun savePlaylistOrder(order: List<String>) {
        perform {
            try { app.collections.reorderPlaylists(order) }
            catch (error: Exception) { reorder.discard(order); throw error }
        }
    }
    val playlistSongs = if (playlist == null) emptyList() else {
        val byId = songs.associateBy { it.id }
        membership.filter { it.playlist == playlist.id }.mapNotNull { byId[it.song] }
    }
    var playlistOptions by remember(playlist?.id) { mutableStateOf(false) }
    val matching = songs.filter { song ->
        val tag = tags[song.galleryUri]
        query.isBlank() || listOf(song.title, tag?.title, tag?.artist, tag?.album).filterNotNull().any { it.contains(query, true) }
    }
    val visible = when {
        playlist != null -> if (query.isBlank()) playlistSongs else playlistSongs.filter { it in matching }
        albumOrArtist && collection != null -> matching.filter { group(it) == collection }
        section == "Liked" -> matching.filter { stateBySong[it.id]?.liked == true }
        section == "Recent" -> matching.filter { (stateBySong[it.id]?.played ?: 0) > 0 }.sortedByDescending { stateBySong[it.id]?.played }
        section == "Most played" -> matching.filter { (stateBySong[it.id]?.playCount ?: 0) > 0 }.sortedByDescending { stateBySong[it.id]?.playCount }
        else -> matching
    }.let { rows -> if (section in listOf("Recent", "Most played")) rows else when (sort) {
        "Title" -> rows.sortedBy { (tags[it.galleryUri]?.title ?: it.title).lowercase() }
        "Artist" -> rows.sortedBy { tags[it.galleryUri]?.artist.orEmpty().lowercase() }
        "Duration" -> rows.sortedByDescending { tags[it.galleryUri]?.duration ?: it.duration }
        else -> if (playlist != null) rows else rows.sortedByDescending { it.created }
    } }
    val current = songs.firstOrNull { it.galleryUri == currentUri }
    val queueIds = currentQueue.value.map { it.mediaId }
    val ownsPlaylistPlayback = playlist != null && queueIds.isNotEmpty() && currentUri in queueIds &&
        (queueIds == visible.map { it.galleryUri } || queueIds == playlistSongs.map { it.galleryUri })
    val playlistPlaying = ownsPlaylistPlayback && playbackRequested
    val trackTitle = current?.let { tags[it.galleryUri]?.title ?: it.title }
        ?: player?.mediaMetadata?.title?.toString() ?: "Audio"
    val trackSubtitle = current?.let { tags[it.galleryUri]?.artist }
        ?: player?.mediaMetadata?.artist?.toString() ?: "Unknown artist"

    BackHandler(full || collection != null) {
        if (full) full = false else collection = null
    }
    val showPlaylistCover = playlist != null && !full
    Box(Modifier.fillMaxSize().background(if (full) Brush.verticalGradient(listOf(
        MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.background), endY = 1300f)
        else Brush.verticalGradient(listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.background)))) {
    if (showPlaylistCover && playlist != null) {
        val cover = playlistSongs.firstOrNull()
        val base = MaterialTheme.colorScheme.background
        val extraTop = if (embedded) 0.dp else WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
        Box(Modifier.fillMaxWidth().height(330.dp + extraTop)) {
            Artwork(app, cover?.galleryUri.takeIf { playlist.thumbnail == null }, 360, true,
                modifier = Modifier.matchParentSize(), shape = RoundedCornerShape(0.dp),
                coverUrl = playlist.thumbnail ?: cover?.thumbnail)
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(
                Color(0xBD0D0B14), Color(0x480D0B14), Color(0x9D0D0B14), base,
            ))))
        }
    }
    Column(Modifier.fillMaxSize().animateContentSize()
        .then(if (embedded) Modifier else Modifier.safeDrawingPadding()).padding(horizontal = 24.dp)) {
        CompositionLocalProvider(LocalContentColor provides if (showPlaylistCover) Color.White else LocalContentColor.current) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (!embedded || full || collection != null) IconButton({ if (full) full = false else if (collection != null) collection = null else close() }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
            }
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                if (!embedded || full || collection != null) Text(if (full) "Now playing" else if (playlist != null) "Playlist" else collection ?: "Music",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!full && playlist == null) Text("${songs.size} songs", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!full) IconButton({ searching = !searching; if (!searching) query = "" }) { Icon(painterResource(R.drawable.ic_ui_search), "Search music") }
            if (!full) ChoiceMenu(sort, listOf("Date added", "Title", "Duration", "Artist"), { it }) { sort = it }
            if (!full && playlist == null) IconButton({ import.launch(arrayOf("audio/*")) }, enabled = !importing) {
                Icon(Icons.Outlined.Add, "Import audio")
            }
            if (!full && playlist != null) Box {
                IconButton({ playlistOptions = true }) { Icon(Icons.Outlined.MoreVert, "Playlist options") }
                DropdownMenu(playlistOptions, { playlistOptions = false }) {
                    DropdownMenuItem(text = { Text("Rename playlist") }, onClick = {
                        playlistOptions = false
                        form = playlist.name
                    })
                    playlist.sourceUrl?.let { url ->
                        DropdownMenuItem(text = { Text("Open original playlist") }, onClick = {
                            playlistOptions = false
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        })
                    }
                    DropdownMenuItem(text = { Text("Delete playlist") }, onClick = {
                        playlistOptions = false
                        deletePlaylist = playlist
                    })
                }
            }
        }
        }
        if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (connectionError) Text("Android could not connect to audio playback.", color = MaterialTheme.colorScheme.error)
        if (full && player != null && currentUri != null) {
            NowPlaying(app, player, current, trackTitle, trackSubtitle, current?.let { stateBySong[it.id]?.liked } == true,
                ::togglePlayback, { current?.let { perform { app.collections.toggleLike(it.id) } } }, onDismiss = { full = false; if (!embedded) close() })
        } else if (full && !connectionError && (player == null || selected != null && !consumed)) {
            if (player != null && loadedSongs != null) Text("This audio is no longer in the library.") else LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            AnimatedVisibility(searching) {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    placeholder = { Text("Search songs, artists, albums") }, shape = RoundedCornerShape(18.dp),
                    leadingIcon = { Icon(painterResource(R.drawable.ic_ui_search), null) }, singleLine = true)
            }
            if (playlist == null) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Songs", "Playlists", "Artists", "Albums", "Liked", "Recent", "Most played").forEach { tab ->
                    FilterChip(section == tab, { section = tab; collection = null }, label = { Text(tab) },
                        shape = RoundedCornerShape(24.dp),
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Lime,
                            selectedLabelColor = Ink))
                }
            }
            LazyColumn(Modifier.weight(1f), state = musicListState, contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    section == "Playlists" && playlist == null -> {
                        item {
                            OutlinedButton({ form = "" }, Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("Create a playlist")
                            }
                        }
                        item { OutlinedButton({ remoteImport = true }, Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.Link, null); Spacer(Modifier.width(8.dp)); Text("Import YouTube / Spotify playlist")
                        } }
                        if (playlists.isEmpty()) item {
                            EmptyState("Every mood needs a playlist.", "Create one, then add songs from your library.")
                        }
                        items(shownPlaylistOrder.mapNotNull(playlistById::get).filter { it.name.contains(query, true) }, key = { it.id }) { list ->
                            var actions by remember(list.id) { mutableStateOf(false) }
                            val canReorder = query.isBlank() && playlists.size > 1
                            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer,
                                modifier = Modifier.fillMaxWidth().then(reorder.modifier(list.id))
                                    .combinedClickable(onClick = { collection = list.id }, onLongClick = { actions = true },
                                        onLongClickLabel = "Playlist actions")
                                    .semantics {
                                        if (canReorder) customActions = listOfNotNull(
                                            if (shownPlaylistOrder.firstOrNull() != list.id) CustomAccessibilityAction("Move up") {
                                                reorder.move(list.id, -1, savedPlaylistOrder)?.let(::savePlaylistOrder) != null
                                            } else null,
                                            if (shownPlaylistOrder.lastOrNull() != list.id) CustomAccessibilityAction("Move down") {
                                                reorder.move(list.id, 1, savedPlaylistOrder)?.let(::savePlaylistOrder) != null
                                            } else null,
                                        )
                                    }) {
                                Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    PlaylistArtwork(list.thumbnail, list.id.hashCode(), 70)
                                    Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(list.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                        Text("${membership.count { ref -> ref.playlist == list.id && songs.any { it.id == ref.song } }} tracks", style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Box {
                                        if (canReorder) PlaylistDragHandle(reorder, list.id, savedPlaylistOrder, ::savePlaylistOrder)
                                        DropdownMenu(actions, onDismissRequest = { actions = false }) {
                                            DropdownMenuItem(text = { Text("Delete playlist") }, onClick = {
                                                actions = false
                                                deletePlaylist = list
                                            })
                                        }
                                    }
                                }
                            }
                        }
                    }
                    albumOrArtist && collection == null -> items(matching.groupBy(::group).toSortedMap().entries.toList()) { (name, group) ->
                        ListItem(headlineContent = { Text(name) }, supportingContent = { Text("${group.size} tracks") },
                            leadingContent = { if (section == "Artists") Icon(Icons.Outlined.Person, null) else Icon(painterResource(R.drawable.ic_music_album), null) },
                            modifier = Modifier.clickable { collection = name })
                    }
                    else -> {
                        if (playlist != null) item {
                            Column(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.fillMaxWidth().heightIn(min = 206.dp)
                                    .padding(horizontal = 12.dp, vertical = 14.dp),
                                    verticalArrangement = Arrangement.Bottom) {
                                    Text(playlist.name, style = MaterialTheme.typography.headlineMedium,
                                        color = Color.White, fontWeight = FontWeight.SemiBold,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("${playlistSongs.size} ${if (playlistSongs.size == 1) "track" else "tracks"}",
                                        color = Color(0xFFE0DDE6), style = MaterialTheme.typography.labelMedium)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Button({
                                        if (playlistPlaying) player?.pause()
                                        else if (ownsPlaylistPlayback) togglePlayback()
                                        else visible.firstOrNull()?.let { player?.shuffleModeEnabled = false; play(visible, it) }
                                    }, enabled = (ownsPlaylistPlayback || visible.isNotEmpty()) && player != null) {
                                        Icon(painterResource(if (playlistPlaying) R.drawable.ic_media_pause else R.drawable.ic_media_play),
                                            null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(if (playlistPlaying) "Pause" else "Play")
                                    }
                                    OutlinedButton({ addingTracks = true }) { Icon(Icons.Outlined.Add, null); Text("Add songs") }
                                }
                            }
                        } else if (visible.isNotEmpty()) item {
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (section == "Liked") "Liked songs" else section, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                TextButton({ player?.shuffleModeEnabled = true; play(visible, visible.random()) }, enabled = player != null) {
                                    Icon(painterResource(R.drawable.ic_music_shuffle), null, Modifier.size(18.dp)); Text(" Shuffle")
                                }
                            }
                        }
                        if (visible.isEmpty()) item {
                            EmptyState(if (query.isNotBlank()) "No matches" else if (playlist != null) "Make it your mix." else "Your next favorite belongs here.",
                                if (query.isNotBlank()) "Try another title, artist, or album." else "Add songs from your device or save audio from a link.")
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                TextButton({ import.launch(arrayOf("audio/*")) }) { Text("Import songs") }
                                if (onDownload != null) TextButton(onDownload) { Text("Download audio") }
                            }
                        }
                        items(visible, key = { it.id }) { song ->
                            val metadata = tags[song.galleryUri]
                            ListItem(
                                headlineContent = { Text(metadata?.title ?: song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(listOfNotNull(metadata?.artist ?: song.artist ?: "Unknown artist",
                                    metadata?.duration?.takeIf { playlist == null && it > 0 }?.let(::playbackTime)).joinToString(" · "),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                modifier = Modifier.clip(RoundedCornerShape(18.dp)).clickable(enabled = player != null) { play(visible, song) },
                                colors = ListItemDefaults.colors(containerColor = if (currentUri == song.galleryUri)
                                    MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent),
                                leadingContent = { Artwork(app, song.galleryUri, 40, true, shape = CircleShape, coverUrl = song.thumbnail) },
                                trailingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (playlist != null) {
                                            val duration = metadata?.duration?.takeIf { it > 0 } ?: song.duration
                                            if (duration > 0) Text(playbackTime(duration), style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        IconButton({ songOptions = song }) { Icon(Icons.Outlined.MoreVert, "Track options") }
                                    }
                                },
                            )
                        }
                    }
                }
            }
            if (!embedded) MusicMiniPlayer(app, player) { full = true }
        }
    }
    }
    editing?.let { song ->
        EditTrackDialog(song, tags[song.galleryUri]?.title ?: song.title, tags[song.galleryUri]?.artist, tags[song.galleryUri]?.album,
            close = { editing = null }) { title, artist, album ->
            app.dao.editTags(song.id, title, artist, album, song.lyrics)
            if (player != null) for (index in 0 until player.mediaItemCount) {
                val item = player.getMediaItemAt(index)
                if (item.mediaId == song.galleryUri) player.replaceMediaItem(index, item.buildUpon().setMediaMetadata(
                    item.mediaMetadata.buildUpon().setTitle(title).setArtist(artist).setAlbumTitle(album).build()).build())
            }
        }
    }
    if (remoteImport) AlertDialog(onDismissRequest = { remoteImport = false }, title = { Text("Import a playlist") },
        text = { val settings by app.preferences.flow.collectAsStateWithLifecycle(Settings())
            LinkInput(app, settings, onQueued = { remoteImport = false }) },
        confirmButton = { TextButton({ remoteImport = false }) { Text("Close") } })
    if (addingTracks && playlist != null) AlertDialog(onDismissRequest = { addingTracks = false },
        title = { Text("Add songs to ${playlist.name}") }, text = {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                if (songs.isEmpty()) item { Text("Import songs into your library first.") }
                items(songs, key = { it.id }) { song ->
                    val included = membership.any { it.playlist == playlist.id && it.song == song.id }
                    ListItem(headlineContent = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Checkbox(included, { checked -> perform {
                            if (checked) app.collections.addSong(playlist.id, song) else app.collections.dao.remove(playlist.id, song.id)
                        } }) })
                }
            }
        }, confirmButton = { TextButton({ addingTracks = false }) { Text("Done") } })
    form?.let { value -> NameDialog(if (value.isEmpty()) "New playlist" else "Rename playlist", value, { form = null }) {
        if (playlist != null && value.isNotEmpty()) app.collections.renamePlaylist(playlist.id, it) else app.collections.newPlaylist(it)
    } }
    deletePlaylist?.let { list -> AlertDialog(onDismissRequest = { deletePlaylist = null }, title = { Text("Delete playlist?") },
        text = { Text("Songs will stay in your music library.") },
        confirmButton = { TextButton({ perform { app.collections.deletePlaylist(list.id); collection = null }; deletePlaylist = null }) { Text("Delete") } },
        dismissButton = { TextButton({ deletePlaylist = null }) { Text("Cancel") } }) }
    removeSong?.let { song ->
        AlertDialog(onDismissRequest = { removeSong = null }, title = { Text("Remove from Music?") },
            text = { Text("This removes the track from Music and its playlists. The file stays where it is.") },
            confirmButton = { TextButton({
                removeSong = null
                perform {
                    if (player != null) {
                        for (index in (player.mediaItemCount - 1) downTo 0) {
                            if (player.getMediaItemAt(index).mediaId == song.galleryUri) player.removeMediaItem(index)
                        }
                    }
                    app.collections.removeSong(song)
                }
            }) { Text("Remove") } },
            dismissButton = { TextButton({ removeSong = null }) { Text("Cancel") } })
    }
    songOptions?.let { song ->
        AlertDialog(onDismissRequest = { songOptions = null }, title = { Text(song.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    TextButton({ editing = song; songOptions = null }) { Text("Edit track details") }
                    TextButton({ perform { app.collections.toggleLike(song.id) }; songOptions = null }) {
                        Text(if (stateBySong[song.id]?.liked == true) "Unlike" else "Like")
                    }
                    TextButton({ player?.addMediaItem(musicItem(song, tags[song.galleryUri])); songOptions = null },
                        enabled = player != null) { Text("Add to queue") }
                    playlists.forEach { list ->
                        val exists = membership.any { it.playlist == list.id && it.song == song.id }
                        TextButton({
                            perform {
                                if (exists) app.collections.dao.remove(list.id, song.id) else app.collections.addSong(list.id, song)
                            }
                        }) { Text(if (exists) "Remove from ${list.name}" else "Add to ${list.name}") }
                    }
                    if (song.source == Source.IMPORTED.name || song.hiddenFromHistory)
                        TextButton({ songOptions = null; removeSong = song }) { Text("Remove from Music") }
                    song.galleryUri?.let { uri ->
                        TextButton({ showMedia(context, Uri.parse(uri), song.mime ?: "audio/*", true) }) { Text("Share") }
                        TextButton({ showMedia(context, Uri.parse(uri), song.mime ?: "audio/*", false, chooseApp = true) }) { Text("Open with") }
                    }
                }
            }, confirmButton = { TextButton({ songOptions = null }) { Text("Done") } })
    }
}
