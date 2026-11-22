package com.codetic.saoreo

import android.content.ContentValues
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

@Entity(tableName = "folders", indices = [Index(value = ["parent", "name"], unique = true)])
internal data class DownloadFolder(@PrimaryKey val id: String, val parent: String, val name: String)

@Entity(tableName = "playlists")
internal data class MusicPlaylist(@PrimaryKey val id: String, val name: String)

@Entity(tableName = "playlist_tracks", primaryKeys = ["playlist", "song"],
    foreignKeys = [
        ForeignKey(entity = MusicPlaylist::class, parentColumns = ["id"], childColumns = ["playlist"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Download::class, parentColumns = ["id"], childColumns = ["song"], onDelete = ForeignKey.CASCADE),
    ], indices = [Index("song")])
internal data class PlaylistTrack(val playlist: String, val song: String, val added: Long = System.currentTimeMillis())

@Entity(tableName = "song_state", foreignKeys = [
    ForeignKey(entity = Download::class, parentColumns = ["id"], childColumns = ["song"], onDelete = ForeignKey.CASCADE),
])
internal data class SongState(@PrimaryKey val song: String, val liked: Boolean = false, val played: Long = 0,
    @ColumnInfo(defaultValue = "0") val playCount: Int = 0)

@Dao
internal interface CollectionDao {
    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE") fun folders(): Flow<List<DownloadFolder>>
    @Query("SELECT * FROM folders WHERE id = :id") suspend fun folder(id: String): DownloadFolder?
    @Insert suspend fun insert(folder: DownloadFolder)
    @Query("UPDATE folders SET name = :name WHERE id = :id") suspend fun renameFolder(id: String, name: String)
    @Query("UPDATE folders SET parent = :parent WHERE id = :id") suspend fun moveFolder(id: String, parent: String)
    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE") fun playlists(): Flow<List<MusicPlaylist>>
    @Query("SELECT * FROM playlists WHERE id = :id") suspend fun playlist(id: String): MusicPlaylist?
    @Insert suspend fun insert(playlist: MusicPlaylist)
    @Query("UPDATE playlists SET name = :name WHERE id = :id") suspend fun renamePlaylist(id: String, name: String)
    @Query("DELETE FROM playlists WHERE id = :id") suspend fun deletePlaylist(id: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun add(track: PlaylistTrack)
    @Query("DELETE FROM playlist_tracks WHERE playlist = :playlist AND song = :song") suspend fun remove(playlist: String, song: String)
    @Query("SELECT * FROM playlist_tracks ORDER BY added") fun tracks(): Flow<List<PlaylistTrack>>
    @Query("SELECT * FROM song_state") fun songStates(): Flow<List<SongState>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun init(state: SongState)
    @Query("UPDATE song_state SET liked = NOT liked WHERE song = :song") suspend fun toggleLike(song: String)
    @Query("UPDATE song_state SET played = :now, playCount = playCount + 1 WHERE song = :song") suspend fun played(song: String, now: Long)
}

internal class Collections(private val app: SaoreoApp) {
    val dao get() = app.database.collections()
    val folders get() = dao.folders()
    val playlists get() = dao.playlists()

    suspend fun newFolder(parent: String?, title: String): DownloadFolder {
        if (parent != null && dao.folder(parent) == null) throw IOException("Parent folder is unavailable.")
        val folder = DownloadFolder(UUID.randomUUID().toString(), parent.orEmpty(), collectionName(title))
        dao.insert(folder)
        return folder
    }
    suspend fun renameFolder(id: String, title: String) = dao.renameFolder(id, collectionName(title))
    suspend fun moveFolder(item: DownloadFolder, parent: String?) = app.database.withTransaction {
        val visited = mutableSetOf(item.id)
        var next = parent
        while (next != null) {
            if (!visited.add(next)) throw IOException("Choose a destination outside this folder.")
            val ancestor = dao.folder(next) ?: throw IOException("Destination unavailable.")
            next = ancestor.parent.takeIf(String::isNotEmpty)
        }
        dao.moveFolder(item.id, parent.orEmpty())
    }
    suspend fun move(item: Download, folder: String?) {
        if (folder != null && dao.folder(folder) == null) throw IOException("Destination folder is unavailable.")
        app.dao.move(item.id, folder)
    }
    suspend fun renameFile(item: Download, name: String) = withContext(Dispatchers.IO) {
        require(item.source != Source.IMPORTED.name)
        val uri = Uri.parse(item.galleryUri ?: throw IOException("No saved file."))
        require(uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY)
        val current = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: throw IOException("File is unavailable.")
        val extension = current.substringAfterLast('.', "")
        val title = safeName(collectionName(name))
        val proposed = if (extension.isBlank()) title else "$title.$extension"
        val renamed = app.contentResolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, proposed)
        }, null, null)
        if (renamed != 1) throw IOException("Android could not rename the file.")
        val actual = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: proposed
        app.dao.rename(item.id, actual.substringBeforeLast('.', actual))
    }
    suspend fun newPlaylist(name: String) = MusicPlaylist(UUID.randomUUID().toString(), collectionName(name)).also {
        dao.insert(it)
    }
    suspend fun renamePlaylist(id: String, name: String) = dao.renamePlaylist(id, collectionName(name))
    suspend fun deletePlaylist(id: String) = dao.deletePlaylist(id)
    suspend fun addSong(playlist: String, song: Download) {
        require(song.mime?.startsWith("audio/") == true && song.state == JobState.COMPLETE.name)
        dao.add(PlaylistTrack(playlist, song.id))
    }
    suspend fun toggleLike(song: String) {
        dao.init(SongState(song))
        dao.toggleLike(song)
    }
    suspend fun trackPlayed(song: String) {
        dao.init(SongState(song))
        dao.played(song, System.currentTimeMillis())
    }

    suspend fun removeSong(song: Download) {
        require(song.mime?.startsWith("audio/") == true && song.state == JobState.COMPLETE.name)
        app.dao.remove(song.id)
        if (song.source == Source.IMPORTED.name && song.galleryUri != null) withContext(Dispatchers.IO) {
            runCatching { app.contentResolver.releasePersistableUriPermission(Uri.parse(song.galleryUri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    suspend fun importAudio(uri: Uri): Download = importMedia(uri, false)

    suspend fun importVideo(uri: Uri): Download = importMedia(uri, true)

    private suspend fun importMedia(uri: Uri, video: Boolean): Download = withContext(Dispatchers.IO) {
        require(uri.scheme == "content")
        val resolver = app.contentResolver
        val original = resolver.getType(uri)
        val display = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) to it.getLong(1).takeIf { size -> size > 0 } else null }
        val extension = display?.first?.substringAfterLast('.')?.lowercase()
        val kind = if (video) "video/" else "audio/"
        val mime = original?.takeIf { it.startsWith(kind) }
            ?: extension?.let { mimeFor(it, audio = !video) }?.takeIf { it.startsWith(kind) }
            ?: throw IOException("Choose a supported ${if (video) "video" else "audio"} file.")
        MediaMetadataRetriever().use {
            it.setDataSource(app, uri)
            if (if (video) it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes" else
                it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != "yes" ||
                it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes") {
                throw IOException("This file does not contain supported audio.")
            }
        }
        if (uri.authority != MediaStore.AUTHORITY) resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        app.database.runInTransactionAudio(app.dao, uri.toString(), mime, display)
    }
}

private suspend fun DownloadDb.runInTransactionAudio(
    dao: DownloadDao, uri: String, mime: String, display: Pair<String?, Long?>?,
): Download = withTransaction {
    dao.byUri(uri) ?: Download(UUID.randomUUID().toString(), uri, Source.IMPORTED.name,
        display?.first?.substringBeforeLast('.') ?: "Imported audio", state = JobState.COMPLETE.name,
        progress = 100, galleryUri = uri, mime = mime, bytes = display?.second).also { dao.insert(it) }
}

internal fun collectionName(value: String): String = value.trim().also {
    require(it.length in 1..80 && it.none { c -> c == '/' || c == '\\' || c.isISOControl() } && it !in setOf(".", ".."))
}

internal fun folderLabel(folders: List<DownloadFolder>, id: String?): String {
    if (id == null) return "Standard location"
    val byId = folders.associateBy { it.id }
    val labels = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    var current = id
    while (current != null && seen.add(current)) {
        val node = byId[current] ?: return "Unavailable folder"
        labels.add(node.name)
        current = node.parent.takeIf(String::isNotEmpty)
    }
    return labels.asReversed().joinToString(" / ")
}

internal data class DownloadOptions(
    val folder: String? = null,
    val playlist: String? = null,
    val section: MediaSection? = null,
    val bitrate: Int? = null,
)

internal fun Settings.destination(instant: Boolean = false): String? = when {
    !folderOrganization -> null
    !instant && rememberFolder && lastFolder != null -> lastFolder.takeIf(String::isNotEmpty)
    else -> defaultFolder
}

internal fun Settings.downloadQuality(audio: Boolean = defaultAudio): Quality =
    if (audio) Quality.AUDIO else quality.takeUnless { it in listOf(Quality.ASK, Quality.AUDIO) } ?: Quality.BEST

internal fun preferredDownload(choices: List<Choice>, quality: Quality): Choice? =
    selectChoice(choices, quality, "mp4") ?: choices
        .filter { !it.audioOnly && it.imageUrl == null && it.height != null }
        .minWithOrNull(compareBy<Choice> { it.height }.thenByDescending { it.hasAudio })
        .takeUnless { quality == Quality.AUDIO }
