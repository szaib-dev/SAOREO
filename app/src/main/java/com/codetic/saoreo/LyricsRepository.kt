package com.codetic.saoreo

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
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
}

@Database(entities = [CachedLyrics::class], version = 1, exportSchema = false)
internal abstract class LyricsDb : RoomDatabase() { abstract fun lyrics(): LyricsDao }

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
        if (cached?.text != null || cached != null && System.currentTimeMillis() - cached.checked < TimeUnit.DAYS.toMillis(7)) return@withContext
        val query = readMetadata(song)
        if (!song.lyrics.isNullOrBlank()) return@withContext
        if (query.artist.isBlank() || query.duration <= 0) {
            dao.save(CachedLyrics(id, query.title, query.artist, query.album, query.duration, null, false, System.currentTimeMillis()))
            return@withContext
        }
        val exact = request("get", query, true)
        val candidates = if (exact is JSONObject) listOf(exact) else {
            val results = request("search", query, false) as? JSONArray
            (0 until (results?.length() ?: 0)).mapNotNull { results?.optJSONObject(it) }
        }
        val matches = candidates.filter { row -> lyricsMatch(query, row.optString("trackName"),
            row.optString("artistName"), row.optString("albumName"), row.optDouble("duration")) }
        val match = matches.sortedByDescending { !it.optString("syncedLyrics").let { s -> s.isBlank() || s == "null" } }.firstOrNull()
        val synced = match?.optString("syncedLyrics")?.takeUnless { it.isBlank() || it == "null" }
            ?.takeIf { parseLyrics(it).any { line -> line.time != null } }
        val text = synced ?: match?.optString("plainLyrics")?.takeUnless { it.isBlank() || it == "null" }
        dao.save(CachedLyrics(id, query.title, query.artist, query.album, query.duration, text,
            synced != null, System.currentTimeMillis(), match?.optLong("id")))
    }

    private fun readMetadata(song: Download): LyricsMetadata {
        var title = song.localTitle ?: song.title
        var artist = song.artist
        var album = song.album
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
            .addQueryParameter("track_name", query.title).addQueryParameter("artist_name", query.artist)
        query.album?.let { url.addQueryParameter("album_name", it) }
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
