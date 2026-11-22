package com.codetic.saoreo

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun EditTrackDialog(song: Download, initialTitle: String, initialArtist: String?, initialAlbum: String?,
                             close: () -> Unit, save: suspend (String, String?, String?) -> Unit) {
    var title by rememberSaveable(song.id) { mutableStateOf(initialTitle) }
    var artist by rememberSaveable(song.id) { mutableStateOf(initialArtist.orEmpty()) }
    var album by rememberSaveable(song.id) { mutableStateOf(initialAlbum.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text("Track details") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Changes are saved in your SAOREO library.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(title, { title = it.take(240) }, label = { Text("Title") }, singleLine = true)
            OutlinedTextField(artist, { artist = it.take(240) }, label = { Text("Artist") }, singleLine = true)
            OutlinedTextField(album, { album = it.take(240) }, label = { Text("Album") }, singleLine = true)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton({
        scope.launch {
            busy = true
            try { save(title.trim(), artist.trim().ifBlank { null }, album.trim().ifBlank { null }); close() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { error = "Could not save track details." }
            finally { busy = false }
        }
    }, enabled = title.isNotBlank() && !busy) { Text("Save") } },
        dismissButton = { TextButton(close, enabled = !busy) { Text("Cancel") } })
}
