package com.codetic.saoreo

import android.content.Context
import android.content.ContentValues
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.room.*
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

@Entity(tableName = "lyrics")
internal data class CachedLyrics(@PrimaryKey val song: String, val title: String, val artist: String,
    val album: String?, val duration: Long, val text: String?, val synced: Boolean,
    val checked: Long, val providerId: Long? = null)

@Dao
internal interface LyricsDao {
    @Query("SELECT * FROM lyrics WHERE song = :id") fun observe(id: String): Flow<CachedLyrics?>
    @Query("SELECT * FROM lyrics WHERE song = :id") suspend fun find(id: String): CachedLyrics?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(row: CachedLyrics)
    @Query("DELETE FROM lyrics WHERE song = :id") suspend fun remove(id: String)
    @Query("DELETE FROM lyrics WHERE text IS NULL") suspend fun clearUnavailable()
}

@Database(entities = [CachedLyrics::class], version = 1, exportSchema = false)
internal abstract class LyricsDb : RoomDatabase() { abstract fun lyrics(): LyricsDao }

private const val LYRICS_MATCHING_VERSION = 3

/** Audio-library observation keeps download and playlist execution independent of lyric requests. */
internal class LyricsRepository(private val app: SaoreoApp) {
    private val database = Room.databaseBuilder(app, LyricsDb::class.java, "music-lyrics.db").build()
    val dao = database.lyrics()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private val requestGate = Mutex()
    private val policy = app.getSharedPreferences("lyrics-provider", Context.MODE_PRIVATE)

    fun start() {
        scope.launch {
            if (policy.getInt("matchingVersion", 0) < LYRICS_MATCHING_VERSION) {
                dao.clearUnavailable()
                policy.edit().putInt("matchingVersion", LYRICS_MATCHING_VERSION).apply()
            }
            val observed = mutableSetOf<String>()
            app.dao.audio().collect { songs ->
                for (song in songs) if (song.galleryUri != null && observed.add(song.id)) enqueue(song.id)
            }
        }
    }

    fun enqueue(id: String) {
        val request = OneTimeWorkRequestBuilder<LyricsWorker>()
            .setInputData(workDataOf("song" to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(app).enqueueUniqueWork("lyrics-$id", ExistingWorkPolicy.KEEP, request)
    }

    suspend fun fetch(id: String): Unit = withContext(Dispatchers.IO) {
        val song = app.dao.find(id) ?: return@withContext
        if (song.state != JobState.COMPLETE.name || song.galleryUri == null || !song.mime.orEmpty().startsWith("audio/")) return@withContext
        val cached = dao.find(id)
        if (cached?.text != null || cached != null && policy.getInt("matchingVersion", 0) >= LYRICS_MATCHING_VERSION &&
            System.currentTimeMillis() - cached.checked < TimeUnit.HOURS.toMillis(6)) return@withContext
        val query = readMetadata(song)
        if (!song.lyrics.isNullOrBlank()) return@withContext
        if (normalizedMusicName(query.title).isEmpty() || query.duration <= 0) {
            dao.save(CachedLyrics(id, query.title, query.artist, query.album, query.duration, null, false, System.currentTimeMillis()))
            return@withContext
        }
        fun match(row: JSONObject): CachedLyrics? {
            val artist = row.optString("artistName")
            val album = row.optString("albumName")
            if (!lyricsMatch(query, row.optString("trackName"), artist, album, row.optDouble("duration"))) return null
            val synced = row.optString("syncedLyrics").takeIf { parseLyrics(it).any { line -> line.time != null } }
            val text = synced ?: row.optString("plainLyrics").takeUnless { it.isBlank() || it == "null" } ?: return null
            return CachedLyrics(id, query.title, artist, album, query.duration, text, synced != null,
                System.currentTimeMillis(), row.optLong("id").takeIf { it > 0 })
        }
        val exact = if (query.artist.isNotBlank() && query.duration <= 3_600_000)
            (request("get", query, true) as? JSONObject)?.let(::match) else null
        val result = exact ?: run {
            val results = request("search", query.copy(artist = lyricsPrimaryArtist(query.artist)), false) as? JSONArray
            val matches = (0 until (results?.length() ?: 0)).mapNotNull { results?.optJSONObject(it)?.let(::match) }
            if (query.artist.isBlank() && matches.map { normalizedMusicName(it.artist) }.distinct().size > 1) null
            else matches.firstOrNull { it.synced } ?: matches.firstOrNull()
        }
        app.publications.withLock {
            if (app.dao.find(id) == null) return@withLock
            if (result?.text != null && song.source == Source.YOUTUBE.name) {
                runCatching { saveSidecar(Uri.parse(song.galleryUri), result.text) }
                    .onFailure { Log.w("Lyrics", "Could not save the lyrics file for ${song.id}", it) }
            }
            dao.save(result ?: CachedLyrics(id, query.title, query.artist, query.album, query.duration, null, false, System.currentTimeMillis()))
        }
    }

    fun ownedSidecar(audio: Uri): Uri? {
        if (audio.authority != MediaStore.AUTHORITY) return null
        val resolver = app.contentResolver
        val columns = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH)
        val (name, directory) = resolver.query(audio, columns, null, null, null)?.use {
            if (!it.moveToFirst()) null else it.getString(0) to it.getString(1)
        } ?: return null
        val files = MediaStore.Files.getContentUri(MediaStore.getVolumeName(audio))
        return resolver.query(files, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
            arrayOf("${name.substringBeforeLast('.')}.lrc", directory, app.packageName), null)?.use {
            if (it.moveToFirst()) Uri.withAppendedPath(files, it.getLong(0).toString()) else null
        }
    }

    private fun saveSidecar(audio: Uri, text: String) {
        if (audio.authority != MediaStore.AUTHORITY) return
        val resolver = app.contentResolver
        val columns = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH)
        val (name, directory) = resolver.query(audio, columns, null, null, null)?.use {
            if (!it.moveToFirst()) null else it.getString(0) to it.getString(1)
        } ?: return
        val files = MediaStore.Files.getContentUri(MediaStore.getVolumeName(audio))
        val sidecarName = "${name.substringBeforeLast('.')}.lrc"
        if (resolver.query(files, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf(sidecarName, directory), null)?.use { it.moveToFirst() } == true) return
        val sidecar = resolver.insert(files, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, sidecarName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/lrc")
            put(MediaStore.MediaColumns.RELATIVE_PATH, directory)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: throw IOException("Could not create lyrics file")
        try {
            resolver.openOutputStream(sidecar)?.bufferedWriter()?.use { it.write(text) }
                ?: throw IOException("Could not write lyrics file")
            if (resolver.update(sidecar, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) != 1)
                throw IOException("Could not publish lyrics file")
        } catch (error: Exception) {
            runCatching { resolver.delete(sidecar, null, null) }
            throw error
        }
    }

    private fun readMetadata(song: Download): LyricsMetadata {
        var title = song.localTitle ?: song.title
        var artist = song.artist
        // A download's album field can contain the destination playlist name.
        // Only the file's embedded album is suitable for provider lookup.
        var album: String? = null
        var duration = song.duration
        runCatching {
            MediaMetadataRetriever().use { reader ->
                reader.setDataSource(app, Uri.parse(song.galleryUri))
                title = song.localTitle ?: reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf(String::isNotBlank) ?: title
                artist = artist ?: reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                album = album ?: reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                duration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: duration
            }
        }
        return identifyLyricsMetadata(title, artist, album, duration)
    }

    private suspend fun request(path: String, query: LyricsMetadata, exact: Boolean): Any? = requestGate.withLock {
        val wait = policy.getLong("retryAfter", 0) - System.currentTimeMillis()
        if (wait > 0) throw IOException("Lyrics provider is cooling down")
        delay(800)
        val url = "https://lrclib.net/api/$path".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", query.title)
        if (query.artist.isNotBlank()) url.addQueryParameter("artist_name", query.artist)
        if (exact) query.album?.let { url.addQueryParameter("album_name", it) }
        if (exact) url.addQueryParameter("duration", (query.duration / 1000.0).toString())
        val call = http.newCall(Request.Builder().url(url.build())
            .header("User-Agent", "SAOREO/2.10.1 (https://github.com/szaib-dev/SAOREO)").build())
        // runInterruptible cancels the blocking transport when WorkManager stops the worker.
        runInterruptible {
            call.execute().use { response ->
                if (response.code == 404) return@runInterruptible null
                if (response.code == 429) {
                    val seconds = response.header("Retry-After")?.toLongOrNull()?.coerceAtLeast(1) ?: 60
                    policy.edit().putLong("retryAfter", System.currentTimeMillis() + seconds * 1000 + (0..1000).random()).apply()
                    throw IOException("Lyrics request rate limited")
                }
                if (!response.isSuccessful) throw IOException("Lyrics HTTP ${response.code}")
                val body = response.body?.byteStream()?.readNBytes(1_000_001) ?: throw IOException("Empty lyrics response")
                if (body.size > 1_000_000) throw IOException("Lyrics response too large")
                val text = body.toString(Charsets.UTF_8)
                if (exact) JSONObject(text) else JSONArray(text)
            }
        }
    }
}

class LyricsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("song") ?: return Result.failure()
        return try {
            (applicationContext as SaoreoApp).lyrics.fetch(id)
            Result.success()
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { if (runAttemptCount < 3) Result.retry() else Result.failure() }
    }
}
