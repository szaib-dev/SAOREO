package com.codetic.saoreo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal class SongSelection {
    var ids by mutableStateOf(emptySet<String>())
    var confirm by mutableStateOf(false)
    var adding by mutableStateOf(false)
    var creating by mutableStateOf(false)
    var deleting by mutableStateOf(false)

    fun toggle(id: String) {
        if (!deleting) ids = if (id in ids) ids - id else ids + id
    }
}

@Composable
internal fun SongSelectionBar(selection: SongSelection) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("${selection.ids.size} selected", Modifier.weight(1f))
        TextButton({ selection.adding = true }, enabled = !selection.deleting) { Text("Add to playlist") }
        TextButton({ selection.confirm = true }, enabled = !selection.deleting) { Text("Delete") }
        TextButton({ selection.ids = emptySet() }, enabled = !selection.deleting) { Text("Cancel") }
    }
}

@Composable
internal fun SongDeletionDialog(app: SaoreoApp, selection: SongSelection) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    BackHandler(selection.ids.isNotEmpty() && !selection.deleting) { selection.ids = emptySet() }
    val playlists by app.collections.playlists.collectAsState(initial = emptyList())
    fun addToPlaylist(id: String) {
        selection.deleting = true
        scope.launch {
            try {
                for (songId in selection.ids) app.dao.find(songId)?.let { app.collections.addSong(id, it) }
                selection.ids = emptySet()
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { toast(context, "Couldn’t add the selected songs.") }
            finally { selection.deleting = false }
        }
    }
    if (selection.adding) AlertDialog(
        onDismissRequest = { selection.adding = false },
        title = { Text("Add to playlist") },
        text = {
            androidx.compose.foundation.lazy.LazyColumn {
                item { TextButton({ selection.adding = false; selection.creating = true }) { Text("Create playlist") } }
                items(playlists.size) { index ->
                    val playlist = playlists[index]
                    TextButton({ selection.adding = false; addToPlaylist(playlist.id) }) { Text(playlist.name) }
                }
            }
        },
        confirmButton = { TextButton({ selection.adding = false }) { Text("Cancel") } },
    )
    if (selection.creating) NameDialog("New playlist", close = { selection.creating = false }) { name ->
        val playlist = app.collections.newPlaylist(name)
        selection.creating = false
        addToPlaylist(playlist.id)
    }
    if (selection.confirm) AlertDialog(
        onDismissRequest = { selection.confirm = false },
        title = { Text("Permanently delete ${selection.ids.size} selected songs?") },
        text = { Text("This removes songs from all playlists and deletes their downloaded audio and lyrics. Shared files are kept.") },
        confirmButton = {
            TextButton({
                selection.confirm = false
                selection.deleting = true
                scope.launch {
                    var failures = 0
                    try {
                        for (id in selection.ids) {
                            try {
                                app.downloads.deleteSong(id)
                                selection.ids -= id
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (_: Exception) {
                                failures++
                            }
                        }
                        if (failures > 0) toast(context, "Couldn’t delete $failures selected songs.")
                    } finally {
                        selection.deleting = false
                    }
                }
            }) { Text("Delete permanently") }
        },
        dismissButton = { TextButton({ selection.confirm = false }) { Text("Cancel") } },
    )
}
