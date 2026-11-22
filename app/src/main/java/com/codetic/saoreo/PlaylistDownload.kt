package com.codetic.saoreo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.net.URI
import androidx.compose.ui.draw.clip

internal fun supportedPlaylist(text: String): MediaLink? {
    if (text.length > 16_384) return null
    return Regex("""https?://[^\s<>"\u0000-\u001f]+""", RegexOption.IGNORE_CASE).findAll(text).firstNotNullOfOrNull {
        val uri = runCatching { URI(it.value.trimEnd('.', ',', ')', ']', '}')) }.getOrNull() ?: return@firstNotNullOfOrNull null
        if (uri.rawUserInfo != null || uri.port !in setOf(-1, 443) ||
            uri.host?.lowercase() !in setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be"))
            return@firstNotNullOfOrNull null
        val id = uri.rawQuery.orEmpty().split('&').firstOrNull { part -> part.startsWith("list=") }?.substringAfter('=')
        if (id == null || !id.matches(Regex("[A-Za-z0-9_-]{10,128}"))) return@firstNotNullOfOrNull null
        MediaLink("https://www.youtube.com/playlist?list=$id", Source.YOUTUBE)
    }
}

@Composable
internal fun PlaylistDownloadDialog(app: SaoreoApp, settings: Settings, link: MediaLink?, close: () -> Unit,
                                    finishShare: (() -> Unit)?, spotifyUrl: String? = null) {
    var remote by remember(link, spotifyUrl) { mutableStateOf<RemotePlaylist?>(null) }
    var selected by rememberSaveable { mutableStateOf(setOf<Int>()) }
    var audio by rememberSaveable { mutableStateOf(true) }
    var quality by rememberSaveable { mutableStateOf(settings.downloadQuality(false)) }
    var bitrate by rememberSaveable { mutableIntStateOf(settings.audioBitrate) }
    var folder by rememberSaveable { mutableStateOf(settings.destination()) }
    var playlist by rememberSaveable { mutableStateOf<String?>(null) }
    var createdPlaylist by rememberSaveable { mutableStateOf<String?>(null) }
    var createPlaylist by rememberSaveable { mutableStateOf(true) }
    var name by rememberSaveable { mutableStateOf("") }
    val orderStart = rememberSaveable { System.currentTimeMillis() }
    var queueJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var completed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(link, spotifyUrl, attempt) {
        error = null
        try {
            remote = if (spotifyUrl != null) loadSpotifyPlaylist(spotifyUrl)
                else app.extractor.playlistDetails(requireNotNull(link), settings.timeout)
            if (name.isBlank()) name = safeName(remote!!.title).take(80)
        } catch (cancel: CancellationException) {
            if (cancel is kotlinx.coroutines.TimeoutCancellationException) error = "The playlist took too long to load."
            else throw cancel
        } catch (failure: Exception) { error = failure.message ?: "Could not load this playlist." }
    }
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text("Make it your mix") }, text = {
        Column(Modifier.heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val result = remote
            if (result == null) {
                if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else { Text(error.orEmpty()); TextButton({ attempt++ }) { Text("Retry") } }
            } else {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PlaylistArtwork(result.thumbnail, result.title.hashCode(), 76)
                    Column(Modifier.weight(1f)) {
                        Text(result.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                        Text("${result.items.size} tracks · ${selected.size} selected", style = MaterialTheme.typography.bodySmall)
                        TextButton({ context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(result.sourceUrl))) }) { Text(if (spotifyUrl != null) "Open in Spotify" else "Open on YouTube") }
                    }
                }
                result.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (result.items.isEmpty()) Text("No accessible tracks were returned.")
                Row {
                    TextButton({ selected = result.items.filter { it.selectable }.map { it.key }.toSet() }, enabled = !busy && !completed) { Text("Select all") }
                    TextButton({ selected = emptySet() }, enabled = !busy && !completed) { Text("Clear") }
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(result.items, key = { it.key }) { item ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(item.key in selected, { checked -> selected = if (checked) selected + item.key else selected - item.key },
                                enabled = item.selectable && !busy && !completed)
                            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                Text(item.title, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text(if (!item.selectable) "Unavailable" else item.artist, style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                if (!completed) {
                    TextButton({ advanced = !advanced }, enabled = !busy) { Text(if (audio) "MP3 audio · Download options" else "MP4 video · Download options") }
                    if (audio) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(createPlaylist, { createPlaylist = it }, enabled = !busy)
                        Text("Create a playlist in Music")
                    }
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }, confirmButton = {
        TextButton(enabled = !busy && (completed || selected.isNotEmpty() && (!audio || !createPlaylist || name.isNotBlank())), onClick = {
            if (completed) { close(); finishShare?.invoke(); return@TextButton }
            queueJob = scope.launch {
                busy = true; error = null
                try {
                    val result = requireNotNull(remote)
                    if (audio && createPlaylist && createdPlaylist == null)
                        createdPlaylist = app.collections.newPlaylist(name, result.thumbnail, result.sourceUrl).id
                    val destinationPlaylist = if (createPlaylist) createdPlaylist else playlist
                    val targets = result.items.filter { it.key in selected && it.selectable }
                    val failures = mutableListOf<RemotePlaylistItem>()
                    var queued = 0
                    for ((index, item) in targets.withIndex()) {
                        status = "${if (spotifyUrl != null) "Matching and queuing" else "Queuing"} ${index + 1} of ${targets.size}: ${item.title}"
                        try {
                            val media = item.link ?: app.extractor.matchSong(item, settings.timeout)
                            app.downloads.enqueue(media, settings.copy(quality = if (audio) Quality.AUDIO else quality),
                                Preview(item.title, item.thumbnail, item.duration, emptyList()), null,
                                DownloadOptions(folder.takeIf { settings.folderOrganization }, destinationPlaylist.takeIf { audio }, bitrate = bitrate,
                                    musicTitle = item.title.takeIf { spotifyUrl != null },
                                    artist = item.artist.takeIf { spotifyUrl != null }, album = name.takeIf { audio },
                                    playlistPosition = (orderStart + item.key).takeIf { audio && destinationPlaylist != null }))
                            selected = selected - item.key
                            queued++
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { failures.add(item) }
                    }
                    selected = failures.map { it.key }.toSet()
                    completed = failures.isEmpty()
                    status = "$queued queued. Download progress is in Downloads." + if (failures.isEmpty()) "" else
                        " ${failures.size} could not be matched or queued. Retry the remaining selection."
                    if (queued > 0 && settings.folderOrganization && settings.rememberFolder)
                        app.preferences.set(SettingKeys.lastFolder, folder.orEmpty())
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { error = failure.message ?: "Could not create the playlist." }
                finally { busy = false }
            }
        }) { Text(if (completed) "Done" else if (busy) "Preparing…" else "Download ${selected.size}") }
    }, dismissButton = { if (!completed) TextButton({
        if (busy) { queueJob?.cancel(); status = "Queuing stopped. Existing downloads will continue. Remaining tracks stay selected." }
        else close()
    }) { Text(if (busy) "Stop queuing" else "Cancel") } })
    if (advanced) AlertDialog(onDismissRequest = { advanced = false }, title = { Text("Download options") }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (spotifyUrl == null) ChoiceMenu(if (audio) "MP3 · Save to Music" else "MP4 · Save videos", listOf(false, true),
                { if (it) "MP3 · Save to Music" else "MP4 · Save videos" }) { audio = it }
            if (!audio) ChoiceMenu(quality.label, Quality.entries.filter { it !in listOf(Quality.ASK, Quality.AUDIO) },
                { it.label }) { quality = it }
            if (audio && createPlaylist) OutlinedTextField(name, { name = it.take(80) },
                singleLine = true, label = { Text("Playlist name") })
            DestinationAndPlaylist(app, settings, audio, folder, if (createPlaylist) null else playlist, bitrate,
                { folder = it }, { playlist = it; createPlaylist = false }, { bitrate = it })
        }
    }, confirmButton = { TextButton({ advanced = false }) { Text("Done") } })
}

@Composable
internal fun PlaylistArtwork(url: String?, seed: Int, size: Int) {
    Box(Modifier.size(size.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))) {
        RecordArt(Modifier.fillMaxSize(), seed)
        if (url != null) coil.compose.AsyncImage(url, null, Modifier.fillMaxSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    }
}
