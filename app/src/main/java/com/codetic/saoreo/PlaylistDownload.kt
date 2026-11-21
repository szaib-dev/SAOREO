package com.codetic.saoreo

import androidx.compose.foundation.layout.*
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

internal data class RemotePlaylistItem(val key: Int, val title: String, val link: MediaLink?)

@Composable
internal fun PlaylistDownloadDialog(app: SaoreoApp, settings: Settings, link: MediaLink, close: () -> Unit,
                                    finishShare: (() -> Unit)?) {
    var items by remember { mutableStateOf<List<RemotePlaylistItem>?>(null) }
    var selected by remember { mutableStateOf(setOf<Int>()) }
    var audio by rememberSaveable { mutableStateOf(settings.defaultAudio) }
    var quality by rememberSaveable { mutableStateOf(settings.downloadQuality(false)) }
    var bitrate by rememberSaveable { mutableIntStateOf(settings.audioBitrate) }
    var folder by rememberSaveable { mutableStateOf(settings.destination()) }
    var playlist by rememberSaveable { mutableStateOf(settings.defaultPlaylist) }
    var busy by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var queueReport by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(link, attempt) {
        error = null
        try { items = app.extractor.playlistItems(link, settings.timeout) }
        catch (cancel: CancellationException) {
            if (cancel is kotlinx.coroutines.TimeoutCancellationException) error = "The playlist took too long to load."
            else throw cancel
        } catch (failure: Exception) { error = mediaFailure(failure.message) }
    }
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text("Download playlist items") }, text = {
        Column(Modifier.heightIn(max = 515.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val result = items
            if (result == null) {
                if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else { Text(error.orEmpty()); TextButton({ attempt++ }) { Text("Retry") } }
            } else if (result.isEmpty()) Text("No accessible items were returned.")
            else {
                Text("Showing up to 100 items. Download progress and item failures appear in Downloads.",
                    style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton({ selected = result.filter { it.link != null }.map { it.key }.toSet() }) { Text("Select all") }
                    TextButton({ selected = emptySet() }) { Text("Clear") }
                }
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(result, key = { it.key }) { item ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(item.key in selected,
                                { checked -> selected = if (checked) selected + item.key else selected - item.key },
                                enabled = item.link != null && !busy)
                            Text(item.title, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                }
                if (queueReport == null) {
                    ChoiceMenu(if (audio) "MP3 audio" else "MP4 video", listOf(false, true),
                        { if (it) "MP3 audio" else "MP4 video" }) { audio = it }
                    if (!audio) ChoiceMenu(quality.label, Quality.entries.filter { it !in listOf(Quality.ASK, Quality.AUDIO) },
                        { it.label }) { quality = it }
                    DestinationAndPlaylist(app, settings, audio, folder, playlist, bitrate,
                        { folder = it }, { playlist = it }, { bitrate = it })
                }
                queueReport?.let { Text(it) }
            }
        }
    }, confirmButton = {
        TextButton(enabled = !busy && (selected.isNotEmpty() || queueReport != null), onClick = {
            if (queueReport != null) { close(); finishShare?.invoke(); return@TextButton }
            scope.launch {
                busy = true
                val failures = mutableListOf<String>()
                val targets = items.orEmpty().filter { it.key in selected }
                val request = settings.copy(quality = if (audio) Quality.AUDIO else quality)
                for (item in targets) {
                    val media = item.link ?: continue
                    try {
                        app.downloads.enqueue(media, request, Preview(item.title, null, null, emptyList()), null,
                            DownloadOptions(folder.takeIf { settings.folderOrganization }, playlist.takeIf { audio }, bitrate = bitrate))
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { failures.add(item.title) }
                }
                val queued = targets.size - failures.size
                queueReport = "$queued queued." + if (failures.isEmpty()) "" else
                    " Couldn’t queue ${failures.size}: ${failures.take(4).joinToString()}."
                if (queued > 0 && settings.folderOrganization && settings.rememberFolder)
                    runCatching { app.preferences.set(SettingKeys.lastFolder, folder.orEmpty()) }
                busy = false
            }
        }) { Text(if (queueReport != null) "Done" else if (busy) "Queuing…" else "Download ${selected.size}") }
    }, dismissButton = { if (queueReport == null) TextButton(close, enabled = !busy) { Text("Cancel") } })
}
