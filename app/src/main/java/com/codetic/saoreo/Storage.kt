package com.codetic.saoreo

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import coil.ImageLoader
import coil.ImageLoaderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

private val Context.settingsData by preferencesDataStore("saoreo_settings")

data class Settings(
    val quality: Quality = Quality.ASK,
    val theme: String = "Dark",
    val wifi: Boolean = false,
    val autoShare: Boolean = false,
    val folderOrganization: Boolean = false,
    val defaultAudio: Boolean = false,
    val audioBitrate: Int = 192,
    val defaultFolder: String? = null,
    val lastFolder: String? = null,
    val rememberFolder: Boolean = false,
    val defaultPlaylist: String? = null,
    val retry: Boolean = true,
    val resume: Boolean = true,
    val concurrency: Int = 2,
    val timeout: Int = 15,
    val retries: Int = 2,
    val completion: Boolean = true,
    val failure: Boolean = true,
    val statusRefresh: Boolean = true,
    val haptic: Boolean = true,
    val overlay: Boolean = false,
    val overlaySize: Int = 48,
    val overlayOpacity: Int = 90,
    val whatsapp: String? = null,
    val business: String? = null,
)

object SettingKeys {
    val quality = stringPreferencesKey("quality")
    val theme = stringPreferencesKey("theme")
    val wifi = booleanPreferencesKey("wifi")
    val autoShare = booleanPreferencesKey("autoShare")
    val folderOrganization = booleanPreferencesKey("folderOrganization")
    val defaultAudio = booleanPreferencesKey("defaultAudio")
    val audioBitrate = intPreferencesKey("audioBitrate")
    val defaultFolder = stringPreferencesKey("defaultFolder")
    val lastFolder = stringPreferencesKey("lastFolder")
    val rememberFolder = booleanPreferencesKey("rememberFolder")
    val defaultPlaylist = stringPreferencesKey("defaultPlaylist")
    val retry = booleanPreferencesKey("retry")
    val resume = booleanPreferencesKey("resume")
    val concurrency = intPreferencesKey("concurrency")
    val timeout = intPreferencesKey("timeout")
    val retries = intPreferencesKey("retries")
    val completion = booleanPreferencesKey("completion")
    val failure = booleanPreferencesKey("failure")
    val statusRefresh = booleanPreferencesKey("statusRefresh")
    val haptic = booleanPreferencesKey("haptic")
    val overlay = booleanPreferencesKey("overlay")
    val overlaySize = intPreferencesKey("overlaySize")
    val overlayOpacity = intPreferencesKey("overlayOpacity")
    val whatsapp = stringPreferencesKey("whatsapp")
    val business = stringPreferencesKey("business")
}

class PreferencesStore(private val context: Context) {
    val flow: Flow<Settings> = context.settingsData.data.map { p ->
        with(SettingKeys) {
            Settings(
                quality = p[quality]?.let { name -> Quality.entries.firstOrNull { it.name == name } } ?: Quality.ASK,
                theme = p[theme] ?: "Dark",
                wifi = p[wifi] ?: false, autoShare = p[autoShare] ?: false,
                folderOrganization = p[folderOrganization] ?: false,
                defaultAudio = p[defaultAudio] ?: (p[quality] == Quality.AUDIO.name),
                audioBitrate = p[audioBitrate]?.takeIf { it in setOf(128, 192, 256, 320) } ?: 192,
                defaultFolder = p[defaultFolder], lastFolder = p[lastFolder], rememberFolder = p[rememberFolder] ?: false,
                defaultPlaylist = p[defaultPlaylist],
                retry = p[retry] ?: true, resume = p[resume] ?: true,
                concurrency = (p[concurrency] ?: 2).coerceIn(1, 3),
                timeout = (p[timeout] ?: 15).coerceIn(10, 60),
                retries = (p[retries] ?: 2).coerceIn(0, 5),
                completion = p[completion] ?: true, failure = p[failure] ?: true,
                statusRefresh = p[statusRefresh] ?: true, haptic = p[haptic] ?: true,
                overlay = p[overlay] ?: false,
                overlaySize = (p[overlaySize] ?: 48).coerceIn(42, 64),
                overlayOpacity = (p[overlayOpacity] ?: 90).coerceIn(45, 100),
                whatsapp = p[whatsapp], business = p[business],
            )
        }
    }

    suspend fun <T> set(key: Preferences.Key<T>, value: T?) {
        context.settingsData.edit { if (value == null) it.remove(key) else it[key] = value }
    }
}

enum class JobState { QUEUED, RESOLVING, DOWNLOADING, PAUSED, COMPLETE, FAILED, CANCELLED }

enum class MediaOrigin { DOWNLOADED, IMPORTED, CONVERTED, STATUS }

internal fun legacyOrigin(source: String): String = when (source) {
    "IMPORTED", "SHARED" -> MediaOrigin.IMPORTED.name
    "CONVERTED" -> MediaOrigin.CONVERTED.name
    "WHATSAPP", "BUSINESS" -> MediaOrigin.STATUS.name
    else -> MediaOrigin.DOWNLOADED.name
}

@Entity(tableName = "downloads")
data class Download(
    @PrimaryKey val id: String,
    val url: String,
    val source: String,
    val title: String,
    val thumbnail: String? = null,
    val choice: String? = null,
    val quality: String = Quality.BEST.name,
    val state: String = JobState.QUEUED.name,
    val progress: Int = -1,
    val galleryUri: String? = null,
    val mime: String? = null,
    val bytes: Long? = null,
    val detail: String? = null,
    val error: String? = null,
    val hash: String? = null,
    val created: Long = System.currentTimeMillis(),
    val folder: String? = null,
    val targetPlaylist: String? = null,
    val clipStart: Long? = null,
    val clipEnd: Long? = null,
    val audioBitrate: Int? = null,
    @ColumnInfo(defaultValue = "0") val hiddenFromHistory: Boolean = false,
    @ColumnInfo(defaultValue = "'DOWNLOADED'") val origin: String = legacyOrigin(source),
    val localTitle: String? = null,
    val artist: String? = null,
    val album: String? = null,
    @ColumnInfo(defaultValue = "0") val duration: Long = 0,
    val lyrics: String? = null,
) {
    val active: Boolean get() = state in setOf("QUEUED", "RESOLVING", "DOWNLOADING")
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads WHERE hiddenFromHistory = 0 AND origin = 'DOWNLOADED' ORDER BY created DESC")
    fun observe(): Flow<List<Download>>
    @Query("SELECT * FROM downloads WHERE state = 'COMPLETE' AND mime LIKE 'video/%' ORDER BY created DESC")
    fun videoLibrary(): Flow<List<Download>>
    @Query("UPDATE downloads SET localTitle = :title, artist = :artist, album = :album, lyrics = :lyrics WHERE id = :id")
    suspend fun editTags(id: String, title: String, artist: String?, album: String?, lyrics: String?)
    @Query("UPDATE downloads SET duration = :duration WHERE id = :id AND duration = 0")
    suspend fun setDuration(id: String, duration: Long)
    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun find(id: String): Download?
    @Query("SELECT * FROM downloads WHERE state IN ('QUEUED','RESOLVING','DOWNLOADING') AND source = :source")
    suspend fun activeFor(source: String): List<Download>
    @Query("SELECT EXISTS(SELECT 1 FROM downloads WHERE galleryUri = :uri AND id != :id)")
    suspend fun hasOtherFileReference(uri: String, id: String): Boolean
    @Query("SELECT * FROM downloads WHERE galleryUri = :uri AND state = 'COMPLETE' LIMIT 1") suspend fun byUri(uri: String): Download?
    @Query("SELECT * FROM downloads WHERE state = 'COMPLETE' AND mime LIKE 'audio/%' ORDER BY created DESC")
    fun audio(): Flow<List<Download>>
    @Query("SELECT * FROM downloads WHERE url = :url AND state = 'COMPLETE' AND clipStart IS NULL AND mime LIKE 'video/%' ORDER BY created DESC LIMIT 1")
    suspend fun previewFile(url: String): Download?
    @Query("UPDATE downloads SET folder = :folder WHERE id = :id") suspend fun move(id: String, folder: String?)
    @Query("UPDATE downloads SET title = :title WHERE id = :id") suspend fun rename(id: String, title: String)
    @Query("SELECT * FROM downloads WHERE source = 'CONVERTED' AND state = 'CONVERTING'")
    suspend fun interruptedConversions(): List<Download>
    @Query("SELECT * FROM downloads WHERE state = 'COMPLETE' AND mime LIKE 'video/%' AND galleryUri IS NOT NULL ORDER BY created DESC")
    suspend fun videos(): List<Download>
    @Query("SELECT * FROM downloads WHERE hash = :hash AND state = 'COMPLETE'")
    suspend fun saved(hash: String): List<Download>
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: Download)
    @Update
    suspend fun update(item: Download): Int
    @Query("UPDATE downloads SET progress = :progress WHERE id = :id AND state = 'DOWNLOADING'")
    suspend fun progress(id: String, progress: Int)
    @Query("DELETE FROM downloads WHERE id = :id AND state NOT IN ('QUEUED','RESOLVING','DOWNLOADING','CONVERTING')")
    suspend fun remove(id: String)
    @Query("UPDATE downloads SET hiddenFromHistory = 1 WHERE id = :id") suspend fun hide(id: String)
    @Query("UPDATE downloads SET hiddenFromHistory = 1 WHERE origin = 'DOWNLOADED' AND state = 'COMPLETE' AND mime LIKE 'audio/%'")
    suspend fun hideAudioHistory()
    @Query("DELETE FROM downloads WHERE hiddenFromHistory = 0 AND origin = 'DOWNLOADED' AND state NOT IN ('QUEUED','RESOLVING','DOWNLOADING','PAUSED','CONVERTING')")
    suspend fun clear()
}

@Database(entities = [Download::class, DownloadFolder::class, MusicPlaylist::class, PlaylistTrack::class, SongState::class], version = 5)
abstract class DownloadDb : RoomDatabase() {
    abstract fun downloads(): DownloadDao
    internal abstract fun collections(): CollectionDao
}

internal val collectionsMigration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        for (column in listOf("folder TEXT", "targetPlaylist TEXT", "clipStart INTEGER", "clipEnd INTEGER", "audioBitrate INTEGER", "hiddenFromHistory INTEGER NOT NULL DEFAULT 0")) {
            db.execSQL("ALTER TABLE downloads ADD COLUMN $column")
        }
        db.execSQL("CREATE TABLE IF NOT EXISTS folders (id TEXT NOT NULL PRIMARY KEY, parent TEXT NOT NULL, name TEXT NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_folders_parent_name ON folders (parent, name)")
        db.execSQL("CREATE TABLE IF NOT EXISTS playlists (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS playlist_tracks (playlist TEXT NOT NULL, song TEXT NOT NULL, added INTEGER NOT NULL, PRIMARY KEY (playlist, song), FOREIGN KEY (playlist) REFERENCES playlists (id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY (song) REFERENCES downloads (id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_playlist_tracks_song ON playlist_tracks (song)")
        db.execSQL("CREATE TABLE IF NOT EXISTS song_state (song TEXT NOT NULL PRIMARY KEY, liked INTEGER NOT NULL, played INTEGER NOT NULL, FOREIGN KEY (song) REFERENCES downloads (id) ON UPDATE NO ACTION ON DELETE CASCADE)")
    }
}

internal val libraryMigration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE downloads ADD COLUMN origin TEXT NOT NULL DEFAULT 'DOWNLOADED'")
        db.execSQL("UPDATE downloads SET origin = CASE WHEN source IN ('IMPORTED','SHARED') THEN 'IMPORTED' WHEN source = 'CONVERTED' THEN 'CONVERTED' WHEN source IN ('WHATSAPP','BUSINESS') THEN 'STATUS' ELSE 'DOWNLOADED' END")
        for (column in listOf("localTitle TEXT", "artist TEXT", "album TEXT", "duration INTEGER NOT NULL DEFAULT 0", "lyrics TEXT"))
            db.execSQL("ALTER TABLE downloads ADD COLUMN $column")
        db.execSQL("ALTER TABLE song_state ADD COLUMN playCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE song_state SET playCount = 1 WHERE played > 0")
    }
}

internal val playlistArtworkMigration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE playlists ADD COLUMN thumbnail TEXT")
        db.execSQL("ALTER TABLE playlists ADD COLUMN sourceUrl TEXT")
    }
}

internal val playlistOrderMigration = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE playlists ADD COLUMN sortPosition INTEGER")
    }
}

class SaoreoApp : Application(), ImageLoaderFactory {
    internal lateinit var screenCaptureProtection: ScreenCaptureProtection
        private set
    val preferences by lazy { PreferencesStore(this) }
    internal val pairing by lazy { TogetherPairing(this) }
    internal val database by lazy { Room.databaseBuilder(this, DownloadDb::class.java, "downloads.db")
        .addMigrations(collectionsMigration, libraryMigration, playlistArtworkMigration, playlistOrderMigration).build() }
    val dao get() = database.downloads()
    internal val collections by lazy { Collections(this) }
    internal val lyrics by lazy { LyricsRepository(this) }
    internal val audioControl = AudioControl()
    internal val youtubeDiagnostics = YoutubeDiagnostics(this)
    val extractor by lazy { Extractor(this) }
    val downloads by lazy { DownloadQueue(this) }
    internal val downloadNotices by lazy { DownloadNotifications(this) }
    val statuses by lazy { StatusStore(this) }
    val publications = Mutex()
    internal val converter by lazy { ConversionState(this) }
    internal val transcripts by lazy { TranscriptEngine(this) }

    override fun onCreate() {
        super.onCreate()
        screenCaptureProtection = ScreenCaptureProtection(this)
        lyrics.start()
        TogetherMessagingService.register(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) {
                if (pairing.resumeAvailabilityAtBoot && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    runCatching { androidx.core.content.ContextCompat.startForegroundService(this@SaoreoApp,
                        android.content.Intent(this@SaoreoApp, TogetherAvailabilityService::class.java)) }
                }
            }
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) = Unit
            override fun onActivityResumed(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivityStopped(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })
        converter.recover
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(mediaHttp).crossfade(true).build()
}

fun mimeFor(extension: String, audio: Boolean = false): String? = when (extension.lowercase()) {
    "mp4" -> if (audio) "audio/mp4" else "video/mp4"
    "m4a" -> "audio/mp4"
    "mp3" -> "audio/mpeg"
    "webm" -> if (audio) "audio/webm" else "video/webm"
    "mkv" -> "video/x-matroska"
    "mov" -> "video/quicktime"
    "opus" -> "audio/ogg"
    "aac" -> "audio/aac"
    "flac" -> "audio/flac"
    "wav" -> "audio/wav"
    "ogg", "oga" -> "audio/ogg"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "heic" -> "image/heic"
    "avif" -> "image/avif"
    else -> null
}

fun validateMedia(file: File, mime: String) {
    if (!file.isFile || file.length() <= 0) throw IOException("Media is empty")
    if (mime.startsWith("image/")) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outMimeType != mime) {
            throw IOException("Image format could not be verified")
        }
    } else {
        MediaMetadataRetriever().use { reader ->
            reader.setDataSource(file.absolutePath)
            val key = if (mime.startsWith("audio/")) MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO
                else MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO
            if (reader.extractMetadata(key) != "yes") throw IOException("Media track missing")
        }
    }
}

suspend fun publishMedia(
    context: Context, file: File, title: String, mime: String, onPublished: suspend (Uri) -> Unit,
): Uri = withContext(Dispatchers.IO) {
    validateMedia(file, mime)
    val (collection, directory) = when (mime.substringBefore('/')) {
        "image" -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_PICTURES
        "audio" -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MUSIC
        else -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MOVIES
    }
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "${safeName(title)}.${file.extension}")
        put(MediaStore.MediaColumns.MIME_TYPE, mime)
        put(MediaStore.MediaColumns.RELATIVE_PATH, "$directory/SAOREO")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore insert failed")
    try {
        resolver.openOutputStream(uri)?.use { output ->
            file.inputStream().use { it.copyTo(output) }
        } ?: throw IOException("MediaStore output unavailable")
        if (resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) != 1) {
            throw IOException("MediaStore publish failed")
        }
        onPublished(uri)
        uri
    } catch (failure: Exception) {
        runCatching { resolver.delete(uri, null, null) }
        throw failure
    }
}

fun isReadable(context: Context, uri: Uri): Boolean = runCatching {
    context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
}.getOrDefault(false)
