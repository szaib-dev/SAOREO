@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.codetic.saoreo

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun NameDialog(title: String, initial: String = "", close: () -> Unit, save: suspend (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf(initial) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(value, { value = it.take(80); error = null }, label = { Text("Name") },
                singleLine = true, isError = error != null)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, dismissButton = { TextButton(close, enabled = !busy) { Text("Cancel") } }, confirmButton = {
        TextButton(enabled = value.isNotBlank() && !busy, onClick = {
            scope.launch {
                busy = true
                try { save(collectionName(value)); close() }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { error = "Use a valid, available name."; busy = false }
            }
        }) { Text("Save") }
    })
}

@Composable
internal fun DestinationDialog(app: SaoreoApp, initial: String?, close: () -> Unit,
                               excludedBranch: String? = null, select: (String?) -> Unit) {
    val folders by app.collections.folders.collectAsStateWithLifecycle(initialValue = emptyList())
    var parent by rememberSaveable { mutableStateOf(initial) }
    var create by rememberSaveable { mutableStateOf(false) }
    val current = folders.firstOrNull { it.id == parent }
    AlertDialog(onDismissRequest = close, title = { Text("Download destination") }, text = {
        Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
            Text(folderLabel(folders, parent), style = MaterialTheme.typography.titleSmall)
            Text("Folders organize your SAOREO library. Android stores media in its usual locations.",
                style = MaterialTheme.typography.bodySmall)
            if (parent != null) TextButton({ parent = current?.parent?.takeIf(String::isNotEmpty) }) { Text("Up one level") }
            folders.filter { it.parent == parent.orEmpty() && it.id != excludedBranch }.forEach { folder ->
                TextButton({ parent = folder.id }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.FolderOpen, null)
                    Spacer(Modifier.width(8.dp))
                    Text(folder.name)
                }
            }
            TextButton({ create = true }, enabled = parent == null || current != null) {
                Icon(painterResource(R.drawable.ic_folder_new), null)
                Spacer(Modifier.width(8.dp))
                Text("New folder")
            }
        }
    }, confirmButton = {
        TextButton({ select(parent); close() }, enabled = parent == null || current != null) {
            Text(if (parent == null) "Use standard location" else "Use this folder")
        }
    }, dismissButton = { TextButton(close) { Text("Cancel") } })
    if (create) NameDialog("New folder", close = { create = false }, save = { parent = app.collections.newFolder(parent, it).id })
}

@Composable
internal fun DestinationAndPlaylist(
    app: SaoreoApp, settings: Settings, audio: Boolean, folder: String?, playlist: String?, bitrate: Int,
    onFolder: (String?) -> Unit, onPlaylist: (String?) -> Unit, onBitrate: (Int) -> Unit,
) {
    var picker by rememberSaveable { mutableStateOf(false) }
    if (settings.folderOrganization) {
        val folders by app.collections.folders.collectAsStateWithLifecycle(initialValue = emptyList())
        OutlinedButton({ picker = true }, Modifier.fillMaxWidth()) { Text(folderLabel(folders, folder)) }
    }
    if (audio) {
        val playlists by app.collections.playlists.collectAsStateWithLifecycle(initialValue = emptyList())
        ChoiceMenu("$bitrate kbps", listOf(128, 192, 256, 320), { "$it kbps" }, onBitrate)
        if (playlists.isNotEmpty()) ChoiceMenu(playlists.firstOrNull { it.id == playlist }?.name ?: "Playlist: None",
            listOf<MusicPlaylist?>(null) + playlists, { it?.name ?: "None" }) { onPlaylist(it?.id) }
    }
    if (picker) DestinationDialog(app, folder, { picker = false }, select = onFolder)
}

@Composable
internal fun SectionEditor(app: SaoreoApp, link: MediaLink, section: MediaSection?, close: () -> Unit) {
    if (section == null) return
    val cached by produceState<Download?>(null, link.url) { value = app.dao.previewFile(link.url) }
    val uri = cached?.galleryUri?.let(Uri::parse)?.takeIf { it.scheme == "content" }
    val context = LocalContext.current
    if (uri == null) {
        AlertDialog(onDismissRequest = close, title = { Text("Section preview") },
            text = { Text("Preview requires a saved copy of this media in SAOREO.") },
            confirmButton = { TextButton(close) { Text("Close") } })
        return
    }
    val player = remember(uri, section) {
        ExoPlayer.Builder(context).setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true).build().apply {
            setMediaItem(MediaItem.Builder().setUri(uri).setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(section.start).setEndPositionMs(section.end).build()).build())
            prepare()
        }
    }
    DisposableEffect(player) {
        app.audioControl.register(player)
        onDispose {
            app.audioControl.unregister(player)
            player.release()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }
    val reading = rememberPlayback(player)
    AlertDialog(onDismissRequest = close, title = { Text("Section preview") }, text = {
        Column {
            AndroidView({ PlayerView(it).apply { this.player = player; useController = true } },
                Modifier.fillMaxWidth().height(170.dp))
            reading.error?.let { Text(it) }
        }
    }, confirmButton = { TextButton(close) { Text("Done") } })
}
