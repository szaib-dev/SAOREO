package com.codetic.saoreo

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.room.withTransaction
import android.os.SystemClock
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DownloadQueue(private val app: SaoreoApp) {
    private val manager: WorkManager get() = WorkManager.getInstance(app)
    private val slots = Mutex()
    private val enqueueLock = Mutex()
    private var count = 0

    suspend fun <T> withSlot(limit: Int, block: suspend () -> T): T {
        while (!slots.withLock { if (count < limit) { count++; true } else false }) delay(350)
        try { return block() }
        finally { withContext(NonCancellable) { slots.withLock { count-- } } }
    }

    suspend fun enqueue(link: MediaLink, settings: Settings, preview: Preview?, choice: Choice?): String =
        enqueue(link, settings, preview, choice, DownloadOptions())

    internal suspend fun enqueue(link: MediaLink, settings: Settings, preview: Preview?, choice: Choice?, options: DownloadOptions): String = enqueueLock.withLock {
        if (options.folder != null && app.collections.dao.folder(options.folder) == null)
            throw SectionFailure("Choose an available download folder in Settings.")
        if (android.os.Environment.getExternalStorageState() != android.os.Environment.MEDIA_MOUNTED)
            throw SectionFailure("Storage is unavailable. Reconnect storage before downloading.")

        val started = SystemClock.elapsedRealtime()
        val item = Download(
            id = UUID.randomUUID().toString(), url = link.url, source = link.source.name,
            title = preview?.title ?: link.source.label, thumbnail = preview?.thumbnail,
            choice = choice?.id, quality = settings.quality.name,
            detail = choice?.description(), folder = options.folder, targetPlaylist = options.playlist,
            clipStart = options.section?.start, clipEnd = options.section?.end, audioBitrate = options.bitrate,
        )
        val existing = app.dao.activeFor(item.source).firstOrNull {
            MediaLink.parse(it.url)?.metadataKey() == link.metadataKey() && it.choice == item.choice &&
                it.quality == item.quality && it.folder == item.folder && it.targetPlaylist == item.targetPlaylist &&
                it.clipStart == item.clipStart && it.clipEnd == item.clipEnd && it.audioBitrate == item.audioBitrate
        }
        if (existing != null) return@withLock existing.id
        withContext(NonCancellable) {
            app.dao.insert(item)
            schedule(item, settings)
        }
        Log.i("SAOREO", "job_accept_ms=${SystemClock.elapsedRealtime() - started}")
        item.id
    }

    suspend fun retry(item: Download, settings: Settings) = app.publications.withLock {
        val latest = app.dao.find(item.id) ?: return@withLock
        if (latest.state !in setOf(JobState.FAILED.name, JobState.CANCELLED.name)) return@withLock
        val reset = latest.copy(state = JobState.QUEUED.name, error = null, progress = -1)
        if (app.dao.update(reset) == 1) schedule(reset, settings)
    }

    private suspend fun schedule(item: Download, settings: Settings) {
        val request = OneTimeWorkRequestBuilder<TransferWorker>()
            .setInputData(workDataOf("id" to item.id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(
                if (settings.wifi) NetworkType.UNMETERED else NetworkType.CONNECTED,
            ).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 12 + (0L..8L).random(), TimeUnit.SECONDS)
            .build()
        if (!hasDownloadNetwork(app, settings.wifi)) {
            app.downloadNotices.waiting(item.id, item.title, if (settings.wifi) "Waiting for an unmetered connection" else "Waiting for connection")
        }
        try {
            withContext(NonCancellable + Dispatchers.IO) {
                manager.enqueueUniqueWork(item.id, ExistingWorkPolicy.REPLACE, request).result.get()
            }
        } catch (error: Exception) {
            withContext(NonCancellable) {
                app.dao.update(item.copy(state = JobState.FAILED.name, error = "Android could not queue the download."))
            }
            throw error
        }
    }

    suspend fun cancel(item: Download) = app.publications.withLock {
        val latest = app.dao.find(item.id) ?: return@withLock
        if (!latest.active) return@withLock
        app.dao.update(latest.copy(state = JobState.CANCELLED.name, error = "Cancelled"))
        withContext(Dispatchers.IO) { manager.cancelUniqueWork(item.id).result.get() }
        app.downloadNotices.end(item.id)
    }

    suspend fun remove(item: Download, delete: Boolean) {
        val uri = item.galleryUri?.let(Uri::parse)
        if (delete && uri != null && item.source != Source.IMPORTED.name) withContext(Dispatchers.IO) {
            if (app.contentResolver.delete(uri, null, null) <= 0 && isReadable(app, uri)) {
                throw IOException("Failed to delete media")
            }
        }
        if (!delete && item.state == JobState.COMPLETE.name && item.mime?.startsWith("audio/") == true)
            app.dao.hide(item.id)
        else app.dao.remove(item.id)
        withContext(Dispatchers.IO) { workspace(item.id).deleteRecursively() }
    }

    suspend fun clearHistory() = app.publications.withLock {
        val removed = app.dao.observe().first().filterNot { it.active }
        app.database.withTransaction {
            app.dao.hideAudioHistory()
            app.dao.clear()
        }
        withContext(Dispatchers.IO) { removed.forEach { workspace(it.id).deleteRecursively() } }
    }

    fun workspace(id: String): File = File(app.filesDir, "transfers/${UUID.fromString(id)}")
}

class TransferWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val app = context.applicationContext as SaoreoApp
    private val notices get() = app.downloadNotices
    private val idInDb get() = inputData.getString("id")

    override suspend fun doWork(): Result {
        val key = idInDb ?: return Result.failure()
        val item = app.dao.find(key) ?: return Result.failure()
        if (!item.active) return Result.success()
        val settings = app.preferences.flow.first()
        notices.channels()
        return try {
            app.downloads.withSlot(settings.concurrency) { transfer(key, settings) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            val latest = app.dao.find(key) ?: return Result.failure()
            if (!latest.active) return Result.success()
            val message = if (error is QualityUnavailable) "No format matches your quality preference."
                else if (error is ForegroundUnavailable) "Android could not start a background transfer."
                else if (error is SectionFailure) error.message.orEmpty() else mediaFailure(error.message)
            val stage = notices.progress.value[key]?.stage ?: "Preparing"
            val report = transferFailureReport(Source.valueOf(item.source), stage, error)
            val temporary = listOf("timed out", "timeout", "connection reset", "unreachable", "502", "503", "504", "network is down", "unable to resolve host", "socket closed")
                .any { error.message.orEmpty().contains(it, ignoreCase = true) }
            if (settings.retry && temporary && runAttemptCount < settings.retries) {
                updateActive(latest.copy(state = JobState.QUEUED.name, error = message))
                Result.retry()
            } else {
                updateActive(latest.copy(state = JobState.FAILED.name, error = "$message\n$report"))
                if (settings.failure) notices.finished(latest, message)
                Result.failure()
            }
        } finally {
            notices.end(key)
            withContext(NonCancellable) {
                val next = app.dao.find(key)
                if (next?.active == true && (!hasDownloadNetwork(app, settings.wifi) || next.state == JobState.QUEUED.name)) {
                    notices.waiting(key, next.title, if (!hasDownloadNetwork(app, settings.wifi)) "Waiting for connection" else "Waiting to retry")
                }
            }
        }
    }

    private suspend fun transfer(key: String, settings: Settings): Result = coroutineScope {
        val item = app.dao.find(key) ?: return@coroutineScope Result.failure()
        if (!item.active) return@coroutineScope Result.success()
        val link = MediaLink.parse(item.url) ?: run {
            updateActive(item.copy(state = JobState.FAILED.name, error = "Unsupported link"))
            return@coroutineScope Result.failure()
        }
        val trace = app.youtubeDiagnostics.open(link.source, "worker")
        updateActive(item.copy(state = JobState.RESOLVING.name, error = null, progress = -1))
        val foregroundAt = SystemClock.elapsedRealtimeNanos()
        try {
            val preparing = TransferProgress("Fetching details")
            setForeground(ForegroundInfo(notices.id(key), notices.ongoing(key, item.title, preparing),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
            notices.active(key, item.title, preparing)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: IllegalStateException) { throw ForegroundUnavailable() }
        trace?.add("worker.foreground_setup_returned", SystemClock.elapsedRealtimeNanos() - foregroundAt)
        if (link.source == Source.YOUTUBE && item.clipStart == null) {
            return@coroutineScope transferYoutube(item, link, settings)
        }
        val preview = try {
            app.extractor.preview(link, settings.timeout, trace)
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw IOException("Metadata timed out")
        }
        val requested = Quality.valueOf(item.quality)
        val choice = if (item.choice == null) preferredDownload(preview.choices, requested)
            else preview.choices.firstOrNull { it.id == item.choice }
                ?: if (requested == Quality.AUDIO || item.detail.orEmpty().startsWith("Audio ·")) {
                    preview.choices.firstOrNull { it.audioOnly && it.selector == item.choice }
                        ?: selectChoice(preview.choices, Quality.AUDIO, "mp4")
                } else null
        choice ?: throw QualityUnavailable()
        val section = item.clipStart?.let { start -> item.clipEnd?.let { MediaSection(start, it) } }
        if (section != null && (choice.imageUrl != null || preview.duration?.let { section.end > it * 1000 } == true))
            throw SectionFailure("The selected range is outside this media's duration.")
        val bitrate = item.audioBitrate ?: 192
        val updating = item.copy(title = preview.title, thumbnail = preview.thumbnail, progress = -1,
            state = JobState.DOWNLOADING.name, detail = choice.description(), error = null)
        updateActive(updating)
        val workspace = app.downloads.workspace(key)
        if (!settings.resume) withContext(Dispatchers.IO) { workspace.deleteRecursively() }
        val progressValue = java.util.concurrent.atomic.AtomicReference(TransferProgress())
        val updater = launch {
            var last: TransferProgress? = null
            while (true) {
                val value = if (progressValue.get().stage == "Exporting section" || hasDownloadNetwork(app, settings.wifi)) progressValue.get()
                    else progressValue.get().copy(stage = "Waiting for connection", bytesPerSecond = null)
                if (value != last) {
                    app.dao.progress(key, value.percent)
                    notices.update(key, preview.title, value)
                    last = value
                }
                delay(900)
            }
        }
        suspend fun downloadSource(): File {
            val directUrl = choice.imageUrl ?: choice.directVideoUrl
            return if (directUrl != null) downloadDirect(
                directUrl, link.source, choice.extension, workspace,
                if (choice.imageUrl != null) MAX_IMAGE else MAX_DIRECT_VIDEO, settings.resume, progressValue::set,
            ).also {
                val mime = mimeFor(it.extension, choice.audioOnly) ?: throw InvalidMediaException()
                try { withContext(Dispatchers.IO) { verifyDownloadedMedia(it, mime, choice.requiresAudio) } }
                catch (failure: InvalidMediaException) { it.delete(); throw failure }
            } else if (link.source == Source.YOUTUBE) {
                app.extractor.downloadYoutube(link, if (choice.audioOnly) Quality.AUDIO else requested.takeUnless {
                    it == Quality.AUDIO
                } ?: Quality.BEST, choice.height, workspace, settings.timeout,
                    if (settings.retry) settings.retries else 0, settings.resume, item.audioBitrate, progressValue::set).first
            } else {
                val originalAudio = choice.reusableStreams.singleOrNull()?.extension
                    ?.takeIf { section != null && choice.audioOnly && it in setOf("m4a", "webm", "ogg", "opus", "aac") }
                val inputChoice = if (originalAudio == null) choice else choice.copy(extension = originalAudio)
                app.extractor.download(link, inputChoice, workspace, settings.timeout,
                    if (settings.retry) settings.retries else 0, settings.resume,
                    bitrate = if (section == null) item.audioBitrate else null, progress = progressValue::set)
            }
        }
        val file = try {
            if (section == null) downloadSource() else {
                withContext(Dispatchers.IO) { workspace.mkdirs() }
                val output = workspace.resolve(if (choice.audioOnly) "section.mp3" else "section.mp4")
                val ranged = try {
                    SectionRangeSource.open(link.source, choice) { bytes ->
                        progressValue.set(TransferProgress("Fetching section", bytes = bytes))
                    }?.use { source ->
                        exportSection(app, source.input, output, section, choice, bitrate, loopback = true)
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: IOException) { null }
                if (ranged != null) ranged else {
                    val input = downloadSource()
                    progressValue.set(TransferProgress("Exporting section"))
                    notices.update(key, preview.title, progressValue.get())
                    exportSection(app, input.absolutePath, output, section, choice, bitrate, loopback = false)
                }
            }
        } finally { updater.cancel() }
        saveTransfer(updating, file, choice.audioOnly, settings)
    }

    private suspend fun transferYoutube(item: Download, link: MediaLink, settings: Settings): Result = coroutineScope {
        val workspace = app.downloads.workspace(item.id)
        if (!settings.resume) withContext(Dispatchers.IO) { workspace.deleteRecursively() }
        val selectedHeight = Regex("""^(\d{2,4})p""").find(item.detail.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
        val requested = if (item.choice?.startsWith("mp3:") == true) Quality.AUDIO else Quality.valueOf(item.quality)
        val latest = java.util.concurrent.atomic.AtomicReference(TransferProgress("Fetching details"))
        val updater = launch {
            var previous: TransferProgress? = null
            while (true) {
                val value = latest.get()
                if (value != previous) {
                    if (value.stage != "Fetching details") {
                        val row = app.dao.find(item.id)
                        if (row?.state == JobState.RESOLVING.name)
                            updateActive(row.copy(state = JobState.DOWNLOADING.name))
                        app.dao.progress(item.id, value.percent)
                    }
                    notices.update(item.id, item.title, value)
                    previous = value
                }
                delay(650)
            }
        }
        val (file, title) = try {
            withTimeoutOrNull(6L * 60 * 60 * 1000) {
                app.extractor.downloadYoutube(link, requested, selectedHeight, workspace, settings.timeout,
                    if (settings.retry) settings.retries else 0, settings.resume, item.audioBitrate, latest::set)
            } ?: throw IOException("Transfer timed out")
        } finally { updater.cancel() }
        saveTransfer(item.copy(title = title ?: item.title), file, requested == Quality.AUDIO, settings)
    }

    private suspend fun saveTransfer(item: Download, file: File, audio: Boolean, settings: Settings): Result {
        val key = item.id
        notices.update(key, item.title, TransferProgress("Saving"))
        val mime = mimeFor(file.extension, audio) ?: throw IOException("Invalid completed format")
        app.publications.withLock {
            if (app.dao.find(key)?.active != true) return Result.success()
            withContext(NonCancellable + Dispatchers.IO) {
                publishMedia(app, file, item.title, mime) { uri ->
                    app.database.withTransaction {
                        val current = app.dao.find(key) ?: throw IOException("Download record unavailable")
                        app.dao.update(current.copy(title = item.title, thumbnail = item.thumbnail, detail = item.detail,
                            state = JobState.COMPLETE.name, progress = 100, error = null,
                            galleryUri = uri.toString(), mime = mime, bytes = file.length()))
                        val playlist = current.targetPlaylist
                        if (audio && playlist != null && app.collections.dao.playlist(playlist) != null)
                            app.collections.dao.add(PlaylistTrack(playlist, key))
                    }
                }
                app.downloads.workspace(key).deleteRecursively()
            }
        }
        if (settings.completion) app.dao.find(key)?.let { notices.finished(it) }
        return Result.success()
    }

    private suspend fun updateActive(item: Download) = app.publications.withLock {
        if (app.dao.find(item.id)?.active != true) throw CancellationException("Download cancelled")
        app.dao.update(item)
    }

    private suspend fun downloadDirect(
        url: String, source: Source, extension: String, folder: File, maximum: Long, resume: Boolean,
        progress: (TransferProgress) -> Unit,
    ): File = suspendCancellableCoroutine { continuation ->
        val started = SystemClock.elapsedRealtime()
        folder.mkdirs()
        val file = File(folder, "direct.$extension")
        val partial = File(folder, "direct.$extension.part")
        val validator = File(folder, "direct.$extension.etag")
        val tag = if (resume && partial.length() > 0) runCatching { validator.readText() }.getOrNull()
            ?.takeIf { it.firstOrNull()?.code == 34 && it.lastOrNull()?.code == 34 && it.length <= 256 } else null
        val offset = if (tag != null) partial.length() else 0L
        val request = Request.Builder().url(url).header("Accept-Encoding", "identity").apply {
            if (tag != null) { header("Range", "bytes=$offset-"); header("If-Range", tag) }
        }.build()
        val call = mediaHttp.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                try {
                    response.use {
                        if (response.code == 416) { partial.delete(); validator.delete(); throw IOException("Connection reset; source range changed") }
                        if (response.code !in setOf(200, 206) || !directAssetHost(source, response.request.url.host)) {
                            throw IOException("Media HTTP ${response.code}")
                        }
                        if (isDocumentContentType(response.header("Content-Type"))) {
                            throw IOException("Unexpected document response")
                        }
                        val body = response.body ?: throw IOException("Empty source")
                        val etag = response.header("ETag")?.takeIf { it.firstOrNull()?.code == 34 && it.lastOrNull()?.code == 34 && it.length <= 256 }
                        val range = response.header("Content-Range")?.let { Regex("""bytes (\d+)-(\d+)/(\d+)""").matchEntire(it) }
                        val start = if (response.code == 206) {
                            if (offset <= 0 || etag != tag || range?.groupValues?.get(1)?.toLongOrNull() != offset) {
                                partial.delete(); validator.delete()
                                throw IOException("Connection reset; source range changed")
                            }
                            offset
                        } else 0L
                        val total = if (start > 0) range?.groupValues?.get(3)?.toLongOrNull() else body.contentLength().takeIf { it >= 0 }
                        if (total != null && (total > maximum || total < start)) throw IOException("Invalid media size")
                        if (start > 0 && range?.groupValues?.get(2)?.toLongOrNull() != total?.minus(1)) {
                            partial.delete(); validator.delete()
                            throw IOException("Connection reset; source range changed")
                        }
                        if (start == 0L) {
                            partial.delete()
                            validator.delete()
                        }
                        if (resume && etag != null) validator.writeText(etag)
                        try {
                            var count = start
                            body.byteStream().use { input ->
                                java.io.FileOutputStream(partial, start != 0L).use { output ->
                                    val buffer = ByteArray(32 * 1024)
                                    while (true) {
                                        val read = input.read(buffer)
                                        if (read < 0) break
                                        if (count == 0L) Log.i("SAOREO", "first_byte_ms=${SystemClock.elapsedRealtime() - started}")
                                        count += read
                                        if (count > maximum) throw IOException("Media exceeds size limit")
                                        output.write(buffer, 0, read)
                                        val elapsed = SystemClock.elapsedRealtime() - started
                                        progress(TransferProgress(bytes = count, total = total,
                                            bytesPerSecond = if (elapsed >= 500) ((count - start) * 1000 / elapsed) else null))
                                    }
                                }
                            }
                            if (total != null && count != total) throw IOException("Connection reset; incomplete media")
                            if (count == 0L || beginsWithErrorDocument(partial)) {
                                partial.delete(); validator.delete()
                                throw InvalidMediaException()
                            }
                            if (!partial.renameTo(file)) throw IOException("Unable to stage completed media")
                            validator.delete()
                            if (continuation.isActive) continuation.resume(file)
                        } finally {
                            if (!resume || etag == null || partial.length() > maximum) {
                                partial.delete(); validator.delete()
                            }
                        }
                    }
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        })
    }

    private class QualityUnavailable : IOException()
    private class ForegroundUnavailable : IOException()
    companion object {
        private const val MAX_IMAGE = 100L * 1024 * 1024
        private const val MAX_DIRECT_VIDEO = 1L * 1024 * 1024 * 1024
    }
}
