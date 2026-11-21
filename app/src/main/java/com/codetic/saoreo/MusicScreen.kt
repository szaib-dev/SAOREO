package com.codetic.saoreo

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
private fun Artwork(app: SaoreoApp, uri: String?, size: Int, artwork: Boolean) {
    val art by produceState<android.graphics.Bitmap?>(null, uri, artwork, size) {
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
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(if (size > 80) 22.dp else 8.dp))
        .background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_music_note), null, Modifier.size((size / 2).dp),
            tint = MaterialTheme.colorScheme.onSecondaryContainer)
        if (art != null) AsyncImage(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
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
    var playing by remember(player) { mutableStateOf(player.isPlaying) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                song = player.currentMediaItem
                playing = player.isPlaying
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    val track = song ?: return
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
        color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.clickable(onClick = onOpen).padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Artwork(app, track.mediaId, 38, true)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(track.mediaMetadata.title?.toString() ?: "Audio", maxLines = 1, overflow = TextOverflow.Ellipsis)
                track.mediaMetadata.artist?.let { Text(it.toString(), maxLines = 1, style = MaterialTheme.typography.bodySmall) }
            }
            IconButton({
                if (player.playWhenReady) player.pause()
                else { player.prepare(); player.play() }
            }) {
                Icon(painterResource(if (playing) R.drawable.ic_media_pause else R.drawable.ic_media_play),
                    if (playing) "Pause" else "Play")
            }
        }
    }
}

@Composable
internal fun MusicLibrary(app: SaoreoApp, selected: String?, launchPlayer: Boolean, close: () -> Unit,
                          controller: MediaController? = null, embedded: Boolean = false) {
    val context = LocalContext.current
    val standalone = if (embedded) null else rememberMusicController(app)
    val connectionError = standalone?.second ?: false
    val loadedSongs by app.dao.audio().collectAsStateWithLifecycle(initialValue = null)
    val songs = loadedSongs.orEmpty()
    val playlists by app.collections.playlists.collectAsStateWithLifecycle(initialValue = emptyList())
    val membership by app.collections.dao.tracks().collectAsStateWithLifecycle(initialValue = emptyList())
    val states by app.collections.dao.songStates().collectAsStateWithLifecycle(initialValue = emptyList())
    val tags = remember { mutableStateMapOf<String, SongTags>() }
    LaunchedEffect(songs) {
        for (song in songs) {
            val uri = song.galleryUri ?: continue
            if (uri !in tags) tags[uri] = songTags(app, uri)
        }
    }
    var section by rememberSaveable { mutableStateOf("Songs") }
    var collection by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var full by rememberSaveable { mutableStateOf(launchPlayer || selected != null) }
    var consumed by rememberSaveable { mutableStateOf(false) }
    var form by remember { mutableStateOf<String?>(null) }
    var deletePlaylist by remember { mutableStateOf<MusicPlaylist?>(null) }
    var songOptions by remember { mutableStateOf<Download?>(null) }
    var removeSong by remember { mutableStateOf<Download?>(null) }
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val player = controller ?: standalone?.first
    val stateBySong = states.associateBy { it.song }
    val currentQueue = remember(player) { mutableStateOf(emptyList<MediaItem>()) }
    var currentUri by remember { mutableStateOf<String?>(null) }
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    if (player != null) DisposableEffect(player) {
        fun sync() {
            currentUri = player.currentMediaItem?.mediaId
            currentQueue.value = (0 until player.mediaItemCount).map(player::getMediaItemAt)
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
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) perform {
            importing = true
            try { app.collections.importAudio(uri); toast(app, "Added to Music") }
            finally { importing = false }
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
    val matching = songs.filter { song ->
        val tag = tags[song.galleryUri]
        query.isBlank() || listOf(song.title, tag?.title, tag?.artist, tag?.album).filterNotNull().any { it.contains(query, true) }
    }
    val visible = when {
        playlist != null -> membership.filter { it.playlist == playlist.id }.mapNotNull { ref -> matching.firstOrNull { it.id == ref.song } }
        albumOrArtist && collection != null -> matching.filter { group(it) == collection }
        section == "Liked" -> matching.filter { stateBySong[it.id]?.liked == true }
        section == "Recent" -> matching.filter { (stateBySong[it.id]?.played ?: 0) > 0 }.sortedByDescending { stateBySong[it.id]?.played }
        else -> matching
    }
    val current = songs.firstOrNull { it.galleryUri == currentUri }
    val trackTitle = current?.let { tags[it.galleryUri]?.title ?: it.title }
        ?: player?.mediaMetadata?.title?.toString() ?: "Audio"
    val trackSubtitle = current?.let { tags[it.galleryUri]?.artist }
        ?: player?.mediaMetadata?.artist?.toString() ?: "Unknown artist"

    BackHandler(full || collection != null) {
        if (full) full = false else collection = null
    }
    Column(Modifier.fillMaxSize().then(if (embedded) Modifier else Modifier.safeDrawingPadding()).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (!embedded || full || collection != null) IconButton({ if (full) full = false else if (collection != null) collection = null else close() }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
            }
            Text(if (full) "Now playing" else playlist?.name ?: collection ?: if (embedded) "Library" else "Music", Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!full) IconButton({ import.launch(arrayOf("audio/*")) }, enabled = !importing) {
                Icon(Icons.Outlined.Add, "Import audio")
            }
        }
        if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (connectionError) Text("Android could not connect to audio playback.", color = MaterialTheme.colorScheme.error)
        if (full && player != null && currentUri != null) {
            var showQueue by rememberSaveable { mutableStateOf(false) }
            val timer by app.audioControl.sleepUntil.collectAsStateWithLifecycle()
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BoxWithConstraints { Artwork(app, currentUri, (maxWidth.value.toInt() - 24).coerceIn(110, 280), true) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(trackTitle, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(trackSubtitle, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (current != null) {
                        val liked = stateBySong[current.id]?.liked == true
                        IconButton({ perform { app.collections.toggleLike(current.id) } }) {
                            Icon(if (liked) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                                if (liked) "Unlike" else "Like",
                                tint = if (liked) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                        }
                    }
                }
                reading.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                PlaybackSeek(reading, player)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ player.shuffleModeEnabled = !shuffle }) {
                        Icon(painterResource(R.drawable.ic_music_shuffle), if (shuffle) "Disable shuffle" else "Enable shuffle",
                            tint = if (shuffle) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                    }
                    IconButton({ player.seekToPrevious() }) { Icon(painterResource(R.drawable.ic_media_prev), "Previous track") }
                    FilledIconButton(::togglePlayback,
                        Modifier.size(62.dp)) { Icon(painterResource(if (reading.playing) R.drawable.ic_media_pause else R.drawable.ic_media_play),
                        if (reading.playing) "Pause" else "Play", Modifier.size(32.dp)) }
                    IconButton({ player.seekToNextMediaItem() }, enabled = player.hasNextMediaItem()) {
                        Icon(painterResource(R.drawable.ic_media_next), "Next track")
                    }
                    IconButton({ player.repeatMode = when (repeat) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    } }) {
                        val label = when (repeat) { Player.REPEAT_MODE_ONE -> "Repeat one"; Player.REPEAT_MODE_ALL -> "Repeat all"; else -> "Repeat off" }
                        Icon(painterResource(if (repeat == Player.REPEAT_MODE_ONE) R.drawable.ic_music_repeat_one else R.drawable.ic_music_repeat), label,
                            tint = if (repeat != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    if (current != null) IconButton({ songOptions = current }) { Icon(Icons.Outlined.MoreVert, "Track options") }
                    TextButton({ showQueue = !showQueue }) { Text("Queue") }
                    ChoiceMenu(if (timer > 0) "Sleep timer on" else "Sleep timer", listOf(0, 15, 30, 45, 60),
                        { if (it == 0) "Off" else "$it minutes" }) {
                        app.audioControl.sleepUntil.value = if (it == 0) 0 else SystemClock.elapsedRealtime() + it * 60_000
                    }
                }
                if (showQueue) currentQueue.value.forEachIndexed { index, item ->
                    ListItem(headlineContent = { Text(item.mediaMetadata.title?.toString() ?: "Audio", maxLines = 1,
                        overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.clickable { player.seekToDefaultPosition(index); player.prepare(); player.play() },
                        trailingContent = {
                            Row {
                                IconButton({ player.moveMediaItem(index, index - 1) }, enabled = index > 0) {
                                    Icon(Icons.Outlined.KeyboardArrowUp, "Move earlier")
                                }
                                IconButton({ player.removeMediaItem(index) }) { Icon(Icons.Outlined.Close, "Remove from queue") }
                            }
                        })
                }
            }
        } else if (full && !connectionError && (player == null || selected != null && !consumed)) {
            if (player != null && loadedSongs != null) Text("This audio is no longer in the library.") else LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search your music") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Songs", "Playlists", "Artists", "Albums", "Liked", "Recent").forEach { tab ->
                    FilterChip(section == tab, { section = tab; collection = null }, label = { Text(tab) })
                }
            }
            LazyColumn(Modifier.weight(1f)) {
                when {
                    section == "Playlists" && playlist == null -> {
                        item { TextButton({ form = "" }) { Icon(Icons.Outlined.Add, null); Text("New playlist") } }
                        items(playlists.filter { it.name.contains(query, true) }, key = { it.id }) { list ->
                            ListItem(headlineContent = { Text(list.name) }, supportingContent = {
                                Text("${membership.count { it.playlist == list.id }} tracks")
                            }, leadingContent = { Icon(painterResource(R.drawable.ic_music_note), null) },
                                modifier = Modifier.clickable { collection = list.id })
                        }
                    }
                    albumOrArtist && collection == null -> items(matching.groupBy(::group).toSortedMap().entries.toList()) { (name, group) ->
                        ListItem(headlineContent = { Text(name) }, supportingContent = { Text("${group.size} tracks") },
                            leadingContent = { if (section == "Artists") Icon(Icons.Outlined.Person, null) else Icon(painterResource(R.drawable.ic_music_album), null) },
                            modifier = Modifier.clickable { collection = name })
                    }
                    else -> {
                        if (playlist != null) item {
                            Row {
                                TextButton({ form = playlist.name }) { Text("Rename") }
                                TextButton({ deletePlaylist = playlist }) { Text("Delete") }
                            }
                        }
                        if (visible.isEmpty()) item {
                            EmptyState("No songs here", if (section == "Songs") "Download audio, convert a video, or import an audio file." else null)
                        }
                        items(visible, key = { it.id }) { song ->
                            val metadata = tags[song.galleryUri]
                            ListItem(
                                headlineContent = { Text(metadata?.title ?: song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(listOfNotNull(metadata?.artist ?: "Unknown artist",
                                    metadata?.duration?.takeIf { it > 0 }?.let(::playbackTime)).joinToString(" · "),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                modifier = Modifier.clickable(enabled = player != null) { play(visible, song) },
                                leadingContent = { Artwork(app, song.galleryUri, 44, true) },
                                trailingContent = { IconButton({ songOptions = song }) { Icon(Icons.Outlined.MoreVert, "Track options") } },
                            )
                        }
                    }
                }
            }
            if (!embedded && player != null && currentUri != null) Surface(color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(14.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth().clickable { full = true }.padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Artwork(app, currentUri, 44, true)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(trackTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(trackSubtitle, maxLines = 1, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(::togglePlayback) {
                        Icon(painterResource(if (reading.playing) R.drawable.ic_media_pause else R.drawable.ic_media_play),
                            if (reading.playing) "Pause" else "Play")
                    }
                }
            }
        }
    }
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
