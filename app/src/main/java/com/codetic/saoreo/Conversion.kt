package com.codetic.saoreo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

internal val mp3Rates = listOf(128, 192, 256, 320)

internal fun estimatedMp3Bytes(durationMs: Long, rate: Int): Long? =
    if (durationMs > 0 && rate in mp3Rates && durationMs <= Long.MAX_VALUE / rate)
        durationMs * rate / 8 else null

internal data class ConversionInput(
    val uri: Uri, val filename: String, val title: String, val durationMs: Long,
    val bytes: Long?, val imported: Boolean = false,
)

internal suspend fun inspectVideo(
    context: Context, uri: Uri, title: String?, imported: Boolean, audioInput: Boolean = false,
): ConversionInput = withContext(Dispatchers.IO) {
    if (uri.scheme != "content") throw IOException("Choose a local video file.")
    val resolver = context.contentResolver
    var size: Long? = null
    var name = if (audioInput) "Audio" else "Video"
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameColumn >= 0) name = cursor.getString(nameColumn) ?: name
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn).takeIf { it >= 0 }
            }
        }
    if (!audioInput && imported && resolver.getType(uri) != "video/mp4" && !name.endsWith(".mp4", true)) {
        throw IOException("Select an MP4 video.")
    }
    if (!isReadable(context, uri)) throw FileNotFoundException()
    // Some document providers supply streams without seek or duration metadata.
    // The local engine checks the actual audio and video tracks after staging.
    val (duration, embeddedTitle) = runCatching {
        MediaMetadataRetriever().use { reader ->
            reader.setDataSource(context, uri)
            (reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) to
                reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
        }
    }.getOrDefault(0L to null)
    ConversionInput(uri, name, (title ?: embeddedTitle ?: name.substringBeforeLast('.')).take(256), duration, size, imported)
}

internal data class ConversionStatus(
    val title: String, val stage: String, val started: Long,
    val percent: Int? = null, val active: Boolean = true, val output: Uri? = null,
    val ended: Long? = null,
)

internal class ConversionState(private val app: SaoreoApp) {
    val status = MutableStateFlow<ConversionStatus?>(null)
    val directory = File(app.cacheDir, "audio-conversions")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val recover = scope.async {
        val abandoned = app.dao.interruptedConversions()
        for (item in abandoned) {
            item.galleryUri?.let { runCatching { app.contentResolver.delete(Uri.parse(it), null, null) } }
            app.dao.update(item.copy(state = JobState.FAILED.name, galleryUri = null, error = "Conversion interrupted. Start it again."))
        }
        directory.deleteRecursively()
    }

    fun start(request: ConversionInput, rate: Int, videoOutput: Boolean = false): Boolean {
        if (status.value?.active == true || rate !in mp3Rates) return false
        val initial = ConversionStatus(request.title, "Preparing video", SystemClock.elapsedRealtime())
        status.value = initial
        return runCatching {
            ContextCompat.startForegroundService(app, Intent(app, ConversionService::class.java)
                .setAction(ConversionService.START).setData(request.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra("title", request.title).putExtra("duration", request.durationMs)
                .putExtra("size", request.bytes ?: -1).putExtra("rate", rate)
                .putExtra("imported", request.imported).putExtra("videoOutput", videoOutput))
            true
        }.getOrElse {
            status.value = null
            false
        }
    }

    fun cancel() {
        app.startService(Intent(app, ConversionService::class.java).setAction(ConversionService.CANCEL))
    }
}

internal fun mp3Arguments(input: String, output: String, rate: Int): List<String> {
    require(rate in mp3Rates)
    return listOf(
        "-nostdin", "-hide_banner", "-loglevel", "error", "-xerror", "-nostats", "-progress", "pipe:1",
        "-max_alloc", "67108864", "-filter_threads", "1", "-protocol_whitelist", "file", "-format_whitelist", "mov,matroska,webm", "-threads", "1",
        "-i", input, "-map", "0:a:0", "-vn", "-sn", "-dn", "-map_metadata", "-1",
        "-map_chapters", "-1", "-ac", "2", "-ar", "44100", "-c:a", "libmp3lame",
        "-b:a", "${rate}k", "-f", "mp3", "-n", output,
    )
}

class ConversionService : Service() {
    private val app get() = application as SaoreoApp
    private val state get() = app.converter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var encoder: Process? = null
    @Volatile private var deadline = false
    private val notifications get() = getSystemService(NotificationManager::class.java)
    private var latestNotification = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?) = null

    @OptIn(DelicateCoroutinesApi::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            job?.cancel()
            encoder?.destroyForcibly()
            if (job == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (job != null) return START_NOT_STICKY
        val uri = intent?.data
        val rate = intent?.getIntExtra("rate", 192) ?: 192
        if (intent?.action != START || uri?.scheme != "content" || rate !in mp3Rates) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val title = intent.getStringExtra("title").orEmpty().ifBlank { "Video" }
        val initial = ConversionStatus(title, "Preparing video", SystemClock.elapsedRealtime())
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Audio conversions", NotificationManager.IMPORTANCE_LOW))
        try {
            val notice = notification(initial)
            if (Build.VERSION.SDK_INT >= 35) {
                try {
                    startForeground(NOTICE, notice, type)
                } catch (unsupported: IllegalArgumentException) {
                    // Some platform builds only accept dataSync for local file processing.
                    startForeground(NOTICE, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                }
            } else startForeground(NOTICE, notice, type)
        } catch (failure: Exception) {
            android.util.Log.w("SAOREO-Conversion", "Foreground promotion failed", failure)
            state.status.value = initial.copy(active = false, stage = "Android couldn't start conversion. Keep SAOREO open and try again.", ended = SystemClock.elapsedRealtime())
            stopSelf(startId)
            return START_NOT_STICKY
        }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${packageName}:conversion")
            .apply { setReferenceCounted(false); acquire(TimeUnit.HOURS.toMillis(6)) }
        // Even an immediate cancel must run the cleanup and publish its final state.
        job = scope.launch(start = CoroutineStart.ATOMIC) {
            val size = intent.getLongExtra("size", -1).takeIf { it >= 0 }
            convert(uri, rate, initial, intent.getLongExtra("duration", 0), size, intent.getBooleanExtra("imported", false), intent.getBooleanExtra("videoOutput", false))
        }
        return START_NOT_STICKY
    }

    private suspend fun convert(uri: Uri, rate: Int, initial: ConversionStatus, duration: Long, size: Long?, imported: Boolean, videoOutput: Boolean) {
        val extension = if (videoOutput) "mp4" else "mp3"
        val mime = if (videoOutput) "video/mp4" else "audio/mpeg"
        val location = if (videoOutput) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC
        val format = extension.uppercase(java.util.Locale.ROOT)
        var record = Download(UUID.randomUUID().toString(), "", Source.CONVERTED.name, initial.title,
            state = "CONVERTING", mime = mime, detail = if (videoOutput) "Still image video" else "$rate kbps")
        var inserted = false
        val folder = File(state.directory, record.id)
        var completed: Uri? = null
        var stage = "Conversion interrupted."
        fun progress(label: String, percent: Int? = null) {
            val status = initial.copy(stage = label, percent = percent)
            state.status.value = status
            val now = SystemClock.elapsedRealtime()
            if (now - latestNotification >= 950) {
                latestNotification = now
                notifications.notify(NOTICE, notification(status))
            }
        }
        try {
            state.recover.await()
            app.dao.insert(record)
            inserted = true
            if (!folder.mkdirs()) throw IOException("Couldn’t create temporary storage.")
            val input = File(folder, "input")
            val output = File(folder, "output.$extension")
            val reserve = 8L * 1024 * 1024
            val outputEstimate = estimatedMp3Bytes(duration, rate) ?: 0L
            if (size != null && StatFs(folder.path).availableBytes - reserve - outputEstimate < size) {
                throw IOException("Free up storage for the video and its converted audio.")
            }
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.createInputStream().use { source ->
                    input.outputStream().buffered().use { sink ->
                        var last = 0L
                        copyConversionData(source, sink) { bytes ->
                            val now = SystemClock.elapsedRealtime()
                            if (now - last >= 300) {
                                progress("Reading input", size?.takeIf { it > 0 }
                                    ?.let { (100.0 * bytes / it).toInt().coerceIn(0, 99) })
                                last = now
                            }
                        }
                    }
                }
            } ?: throw FileNotFoundException()
            val actualDuration = if (videoOutput || imported || duration <= 0) {
                try {
                    MediaMetadataRetriever().use { reader ->
                        reader.setDataSource(input.path)
                        if (!videoOutput && reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") {
                            throw IOException("The selected file does not contain a video track.")
                        }
                        if (reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != "yes") {
                            throw IOException("This file has no audio track.")
                        }
                        if (!videoOutput && imported && reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) != "video/mp4") {
                            throw IOException("The selected file is not an MP4 video.")
                        }
                        reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: duration
                    }
                } catch (_: RuntimeException) { throw IOException("This video is corrupt or uses an unsupported format.") }
            } else duration
            currentCoroutineContext().ensureActive()
            progress("Encoding $format", 0)
            if (videoOutput) app.extractor.initializeLocalEncoder()
            val executable = File(applicationInfo.nativeLibraryDir, if (videoOutput) "libffmpeg.so" else "libsaoreomp3.so")
            if (!executable.canExecute()) throw IOException("The offline encoder is unavailable on this device.")
            val arguments = if (videoOutput) listOf(
                "-nostdin", "-hide_banner", "-loglevel", "error", "-progress", "pipe:1",
                "-protocol_whitelist", "file", "-format_whitelist", "mp3", "-i", input.path,
                "-f", "lavfi", "-i", "color=c=0x19191e:s=640x360:r=10",
                "-map", "1:v:0", "-map", "0:a:0", "-c:v", "libx264", "-preset", "ultrafast",
                "-threads", "2", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "160k",
                "-shortest", "-movflags", "+faststart", "-n", output.path,
            ) else mp3Arguments(input.path, output.path, rate)
            val process = ProcessBuilder(listOf(executable.path) + arguments).apply {
                if (videoOutput) {
                    val packages = File(noBackupFilesDir, "youtubedl-android/packages")
                    environment()["LD_LIBRARY_PATH"] = listOf("python", "ffmpeg")
                        .joinToString(":") { File(packages, "$it/usr/lib").absolutePath }
                }
            }.redirectErrorStream(true).directory(folder).start()
            encoder = process
            try {
                currentCoroutineContext().ensureActive()
                var storageFailure = false
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        currentCoroutineContext().ensureActive()
                        if (line.contains("No space left", ignoreCase = true)) storageFailure = true
                        val micros = line.removePrefix("out_time_us=").takeIf { line.startsWith("out_time_us=") }?.toLongOrNull()
                        if (micros != null) progress("Encoding $format", actualDuration.takeIf { it > 0 }
                            ?.let { (micros / 10.0 / it).toInt().coerceIn(0, 99) })
                    }
                }
                currentCoroutineContext().ensureActive()
                if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
                    if (storageFailure) throw IOException("There is not enough storage to save the $format.")
                    throw IOException("Couldn’t encode this file. Check that the audio track is playable.")
                }
            } finally {
                process.destroyForcibly()
                encoder = null
            }
            input.delete()
            verifyDownloadedMedia(output, mime, requireAudio = true)
            progress("Saving $format", 99)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "${safeName(initial.title)}.$extension")
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.TITLE, initial.title)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$location/SAOREO/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = if (videoOutput) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            // MediaStore assigns a unique path when the requested name already exists.
            val pending = withContext(NonCancellable) {
                val entry = contentResolver.insert(collection, values) ?: throw IOException("Couldn’t create the $format in $location/SAOREO.")
                record = record.copy(galleryUri = entry.toString())
                app.dao.update(record)
                entry
            }
            contentResolver.openOutputStream(pending)?.buffered()?.use { sink ->
                output.inputStream().use { source -> copyConversionData(source, sink) }
            } ?: throw IOException("Couldn’t open the $format for writing.")
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                if (contentResolver.update(pending, ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }, null, null) != 1) throw IOException("Couldn’t publish the MP3.")
                app.dao.update(record.copy(state = JobState.COMPLETE.name, bytes = output.length(), progress = 100))
                completed = pending
                stage = "Saved to $location/SAOREO"
            }
        } catch (_: CancellationException) {
            stage = if (deadline) "Android’s background time limit was reached." else "Conversion cancelled"
        } catch (_: SecurityException) {
            stage = "Access expired. Choose the video again."
        } catch (_: FileNotFoundException) {
            stage = "The video could not be opened. It may have moved or been deleted."
        } catch (failure: IOException) {
            val message = failure.message.orEmpty()
            stage = if (message.contains("ENOSPC") || message.contains("No space left"))
                "There is not enough storage to save the $format."
            else if (message.startsWith("/") || message.contains(folder.path))
                "Couldn’t read or save the conversion. Check available storage."
            else message.ifBlank { "Couldn’t read or save the media file." }
        } catch (_: Exception) {
            stage = "Conversion failed. Check that the video is accessible and playable."
        } finally {
            withContext(NonCancellable) {
                if (completed == null) {
                    record.galleryUri?.let { runCatching { contentResolver.delete(Uri.parse(it), null, null) } }
                    if (inserted) runCatching {
                        app.dao.update(record.copy(state = if (stage == "Conversion cancelled") JobState.CANCELLED.name else JobState.FAILED.name,
                            galleryUri = null, error = stage))
                    }
                }
                folder.deleteRecursively()
                val terminal = initial.copy(stage = stage, active = false, output = completed,
                    ended = SystemClock.elapsedRealtime())
                withContext(Dispatchers.Main) {
                    job = null
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    if (stage != "Conversion cancelled" && !deadline) notifications.notify(RESULT, notification(terminal))
                    stopSelf()
                    state.status.value = terminal
                }
            }
        }
    }

    private fun notification(status: ConversionStatus): Notification {
        val open = Intent(this, MainActivity::class.java).setAction(MainActivity.OPEN_DOWNLOADS)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val cancel = Intent(this, ConversionService::class.java).setAction(CANCEL)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_app_notification)
            .setContentTitle(status.title).setContentText(status.stage)
            .setContentIntent(PendingIntent.getActivity(this, 0, open, flags))
            .setOnlyAlertOnce(true).setAutoCancel(!status.active).setOngoing(status.active).apply {
                if (status.active) {
                    setUsesChronometer(true)
                    setWhen(System.currentTimeMillis() - (SystemClock.elapsedRealtime() - status.started))
                    setProgress(100, status.percent ?: 0, status.percent == null)
                    addAction(Notification.Action.Builder(null, "Cancel",
                        PendingIntent.getService(this@ConversionService, 0, cancel, flags)).build())
                }
            }.build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        deadline = true
        job?.cancel()
        encoder?.destroyForcibly()
        stopSelf()
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        encoder?.destroyForcibly()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        internal const val START = "com.codetic.saoreo.CONVERT"
        internal const val CANCEL = "com.codetic.saoreo.CONVERT_CANCEL"
        private const val CHANNEL = "offline_conversion"
        private const val NOTICE = 7121
        private const val RESULT = 7122
    }
}

private suspend fun copyConversionData(
    source: InputStream, sink: OutputStream, onProgress: (Long) -> Unit = {},
) {
    val buffer = ByteArray(256 * 1024)
    var bytes = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = source.read(buffer)
        if (count < 0) return
        sink.write(buffer, 0, count)
        bytes += count
        onProgress(bytes)
    }
}
