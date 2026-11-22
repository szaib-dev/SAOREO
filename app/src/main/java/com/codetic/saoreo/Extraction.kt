package com.codetic.saoreo

import android.content.Context
import android.system.Os
import android.os.SystemClock
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.UUID
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class Extractor(private val context: Context) {
    private val proxy by lazy { MediaProxy(onFailure = { host, reason ->
        (context.applicationContext as SaoreoApp).youtubeDiagnostics.recordHttp(JSONObject()
            .put("stage", "local_proxy").put("host", host).put("status", 502).put("reason", reason).toString())
    }) }
    private val transport by lazy { YoutubeProcess(context) }
    private val profiledProcess by lazy { YoutubeProfileProcess(context) }
    @Volatile private var initialization: List<Pair<String, Long>> = emptyList()
    private val engineFactory = lazy {
        val started = SystemClock.elapsedRealtime()
        initialization = listOf(
            "engine.zip_verification" to measureInit { installEngine() },
            "engine.python_package_init" to measureInit { YoutubeDL.init(context) },
            "engine.ffmpeg_package_init" to measureInit { FFmpeg.init(context) },
        )
        Log.i(TAG, "engine_init_ms=${SystemClock.elapsedRealtime() - started}")
        YoutubeDL
    }
    private val engine by engineFactory

    internal fun initializeLocalEncoder() {
        engineFactory.value
    }

    private inline fun measureInit(action: () -> Unit): Long {
        val start = SystemClock.elapsedRealtimeNanos()
        action()
        return SystemClock.elapsedRealtimeNanos() - start
    }

    private val executors = Executors.newFixedThreadPool(4)
    private val running = Semaphore(3)
    private val cache = MetadataCache<Preview>(
        CoroutineScope(SupervisorJob() + Dispatchers.IO), SystemClock::elapsedRealtime,
    )

    private fun installEngine() {
        val directory = File(context.noBackupFilesDir, "${YoutubeDL.baseName}/${YoutubeDL.ytdlpDirName}")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not initialize engine storage")
        val destination = File(directory, YoutubeDL.ytdlpBin)
        if (sha256(destination) == ENGINE_SHA256) return
        val temporary = File.createTempFile("engine-", ".zip", directory)
        try {
            context.resources.openRawResource(R.raw.ytdlp).use { input ->
                temporary.outputStream().use { input.copyTo(it) }
            }
            if (sha256(temporary) != ENGINE_SHA256) throw IOException("Unexpected engine checksum")
            Os.rename(temporary.absolutePath, destination.absolutePath)
        } finally { temporary.delete() }
    }

    private fun sha256(file: File): String? {
        if (!file.isFile) return null
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val size = stream.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    internal suspend fun engineVersion(): String = cancellableIo {
        engine.execute(YoutubeDLRequest(emptyList()).apply { addOption("--version") }, null, null).out.trim()
    }

    internal suspend fun preview(link: MediaLink, timeout: Int, trace: YoutubeTrace? = null): Preview {
        val key = if (trace == null) link.metadataKey()
            else "trace:${trace.mode}:${trace.client}:${link.metadataKey()}"
        cache.get(key)?.let { trace?.add("app.metadata_cache.hit"); return it }
        return cache.request(key, timeout) { fetchPreview(link, timeout, trace) }
    }

    private suspend fun fetchPreview(link: MediaLink, timeout: Int, trace: YoutubeTrace?): Preview {
        trace?.add("app.metadata_cache.miss")
        val started = SystemClock.elapsedRealtime()
        val result = withTimeout(70_000) {
            val page = if (link.source == Source.PINTEREST || link.source == Source.SNAPCHAT) {
                cancellableIo { try { directPreview(link) } catch (_: IOException) { null } }
            } else null
            page?.second?.let { return@withTimeout addDirectSizes(page.first.source, it) }
            val json = run(page?.first ?: link, timeout, trace = trace) {
                addOption("--dump-single-json")
                addOption("--skip-download")
            }
            val parseStart = SystemClock.elapsedRealtimeNanos()
            jsonPreview(json, trace, link.source).also {
                trace?.add("app.format_parsing", SystemClock.elapsedRealtimeNanos() - parseStart)
            }
        }
        Log.i(TAG, "metadata_ms=${SystemClock.elapsedRealtime() - started} source=${link.source.name}")
        return result
    }

    internal suspend fun captionInfo(link: MediaLink, timeout: Int): JSONObject = withTimeout(48_000) {
        JSONObject(run(link, timeout.coerceAtMost(12), retries = 0) {
            addOption("--dump-single-json")
            addOption("--skip-download")
            addOption("--ignore-no-formats-error")
            if (link.source == Source.YOUTUBE) addOption("--extractor-args", "youtube:skip=hls,dash")
        }.trim())
    }

    internal suspend fun transcriptAudio(link: MediaLink, folder: File, timeout: Int): File {
        run(link, timeout) {
            addOption("-f", "bestaudio[protocol=https]/bestaudio/best")
            addOption("--max-filesize", "1024M")
            addOption("--abort-on-unavailable-fragments")
            addOption("--downloader", "m3u8:native")
            addOption("-o", File(folder, "audio.%(ext)s").absolutePath)
        }
        return folder.listFiles().orEmpty().firstOrNull {
            it.isFile && it.nameWithoutExtension == "audio" && it.length() > 0 && !beginsWithErrorDocument(it)
        } ?: throw IOException("Couldn’t retrieve accessible audio.")
    }

    fun clearCache() = cache.clear()

    internal suspend fun playlistItems(link: MediaLink, timeout: Int): List<RemotePlaylistItem> {
        val json = run(link, timeout, playlist = true) {
            addOption("--flat-playlist")
            addOption("--skip-download")
            addOption("--dump-single-json")
        }
        val entries = JSONObject(json.trim()).optJSONArray("entries") ?: return emptyList()
        return (0 until minOf(entries.length(), 100)).map { index ->
            val row = entries.optJSONObject(index)
            val id = row?.optString("id")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
            val availability = row?.optString("availability")
            val title = row?.optString("title")?.take(240).orEmpty().ifBlank { "Item ${index + 1}" }
            val available = id != null && availability !in setOf("private", "needs_auth", "premium_only", "subscriber_only") &&
                title !in setOf("[Private video]", "[Deleted video]")
            RemotePlaylistItem(index, if (available) title else "$title · Unavailable",
                if (available) MediaLink("https://www.youtube.com/watch?v=$id", Source.YOUTUBE) else null)
        }
    }

    internal suspend fun downloadYoutube(
        link: MediaLink, quality: Quality, selectedHeight: Int?, directory: File, timeout: Int,
        retries: Int, resume: Boolean, bitrate: Int?, progress: (TransferProgress) -> Unit,
    ): Pair<File, String?> {
        require(link.source == Source.YOUTUBE)
        val audio = quality == Quality.AUDIO
        val output = Choice("output", selectedHeight, if (audio) "mp3" else "mp4", null,
            audioOnly = audio, requiresAudio = true)
        val result = File(directory, "result.json")
        fun title(): String? = runCatching {
            JSONObject(result.readText()).optString("title").take(240).ifBlank { null }
        }.getOrNull()
        val canonical = link.canonicalYoutubeVideo()?.let { link.copy(url = it) } ?: link
        val file = download(canonical, output, directory, timeout, retries, resume, bitrate,
            selector = youtubeSelection(quality, selectedHeight), resultInfo = result, progress = progress)
        return file to withContext(Dispatchers.IO) { title() }
    }

    internal suspend fun download(
        link: MediaLink, choice: Choice, directory: File, timeout: Int, retries: Int,
        resume: Boolean, bitrate: Int? = null, selector: String? = null, resultInfo: File? = null,
        progress: (TransferProgress) -> Unit,
    ): File {
        directory.mkdirs()
        val start = SystemClock.elapsedRealtime()
        val sawProgress = AtomicBoolean(false)
        val initialSizes = directory.listFiles().orEmpty().associate { it.name to it.length() }
        val firstWrite = object : android.os.FileObserver(
            directory, android.os.FileObserver.MODIFY or android.os.FileObserver.CLOSE_WRITE,
        ) {
            override fun onEvent(event: Int, path: String?) {
                if (path != null && !sawProgress.get() && File(directory, path).length() > (initialSizes[path] ?: 0L) &&
                    sawProgress.compareAndSet(false, true)) {
                    Log.i(TAG, "first_byte_ms=${SystemClock.elapsedRealtime() - start}")
                }
            }
        }
        val id = choice.selector.replace(Regex("[^a-zA-Z0-9+_.-]"), "")
        require(id.isNotBlank() && id == choice.selector)
        suspend fun execute(info: File?) = coroutineScope {
            val streams = linkedMapOf<String, Pair<Long, Long?>>()
            val expected = id.split('+').size
            val process = async { run(link, timeout, retries, replayInfo = info, callback = { _, _, line ->
                if (line.startsWith("[download] SAOREO ")) {
                    runCatching {
                        val payload = line.removePrefix("[download] SAOREO ").trim()
                        val fields = JSONObject(payload.replace(Regex(":NA(?=[,}])"), ":null"))
                        val format = fields.optString("format")
                        val bytes = fields.optLong("bytes").coerceAtLeast(0)
                        val done = fields.optString("status") == "finished"
                        val total = fields.optLong("total").takeIf { it > 0 } ?: bytes.takeIf { done && it > 0 }
                        streams[format] = bytes to total
                        val sum = streams.values.sumOf { it.first }
                        val manifest = resultInfo?.takeIf { it.isFile }?.let { runCatching { JSONObject(it.readText().lineSequence().last { line -> line.isNotBlank() }) }.getOrNull() }
                        val selected = manifest?.optJSONArray("formats")
                        val formatCount = selected?.length()?.takeIf { it > 0 } ?: expected
                        val knownSize = if (selected != null && selected.length() > 0) {
                            (0 until selected.length()).map { index ->
                                selected.getJSONObject(index).let { row -> row.optLong("filesize").takeIf { it > 0 } ?: row.optLong("filesize_approx").takeIf { it > 0 } }
                            }.takeIf { sizes -> sizes.all { it != null } }?.sumOf { it ?: 0 }
                        } else manifest?.optLong("filesize")?.takeIf { it > 0 } ?: manifest?.optLong("filesize_approx")?.takeIf { it > 0 }
                        val length = knownSize ?: streams.values.takeIf { it.size == formatCount && it.all { entry -> entry.second != null } }?.sumOf { it.second ?: 0 }
                        progress(TransferProgress(bytes = sum, total = length,
                            bytesPerSecond = fields.optDouble("speed").takeIf { it.isFinite() && it > 0 }?.toLong()))
                    }
                } else if (line == "SAOREO_REFRESH") {
                    streams.clear()
                    progress(TransferProgress("Refreshing media connection"))
                } else if (line.startsWith("[Merger]")) {
                    progress(TransferProgress("Merging video and audio"))
                } else if (line.startsWith("[ExtractAudio]") || line.startsWith("[VideoConvertor]")) {
                    progress(TransferProgress("Preparing output"))
                } else if (line == "SAOREO_PROCESS started") {
                    progress(TransferProgress("Processing downloaded media"))
                }
            }) {
                addOption("-f", selector ?: id)
                if (link.source == Source.YOUTUBE) addOption("--check-formats")
                if (resultInfo != null) {
                    addOption("--no-simulate")
                    addCommands(listOf("--print-to-file", """before_dl:{"title":%(title)j,"formats":%(requested_formats)j,"filesize":%(filesize)j,"filesize_approx":%(filesize_approx)j}""", resultInfo.absolutePath))
                    addOption("-S", "res,vcodec:h264,acodec:aac")
                }
                addOption("-o", File(directory, "media.%(ext)s").absolutePath)
                addOption("--newline")
                addOption("--progress")
                addOption("--progress-template", """download:[download] SAOREO {"format":%(info.format_id)j,"status":%(progress.status)j,"bytes":%(progress.downloaded_bytes)j,"total":%(progress.total_bytes)j,"speed":%(progress.speed)j}""")
                addOption("--progress-template", "postprocess:SAOREO_PROCESS %(progress.status)s")
                addOption("--progress-delta", "0.75")
                addOption(if (resume) "--continue" else "--no-continue")
                addOption("--fragment-retries", retries)
                addOption("--abort-on-unavailable-fragments")
                addOption("--retry-sleep", "http:exp=1:10")
                addOption("--retry-sleep", "fragment:exp=1:10")
                addOption("--downloader", "m3u8:native")
                addOption("--hls-prefer-native")
                addOption("--concurrent-fragments", 4)
                addOption("--http-chunk-size", "10M")
                if (choice.audioOnly && choice.extension == "mp3") {
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                    if (bitrate == null) addOption("--audio-quality", 0) else addOption("--audio-quality", "${bitrate}K")
                } else if (choice.extension == "mp4") {
                    if (selector != null) addOption("--merge-output-format", "mp4/mkv")
                    else if ('+' in id) addOption("--merge-output-format", "mp4")
                    addOption("--recode-video", "mp4")
                }
            } }
            if (resultInfo != null) {
                val started = SystemClock.elapsedRealtime()
                while (!process.isCompleted && !resultInfo.isFile) {
                    if (SystemClock.elapsedRealtime() - started >= 70_000) {
                        process.cancel()
                        throw IOException("Fetching details timed out")
                    }
                    delay(300)
                }
            }
            process.await()
        }

        firstWrite.startWatching()
        try {
            removeErrorDocuments(directory)
            completedMedia(directory, choice)?.let { return it }
            if (selector == null && choice.reusableStreams.map { it.id } == id.split('+')) {
                val info = withContext(Dispatchers.IO) { writeStreamInfo(choice) }
                try {
                    execute(info)
                    return completedMedia(directory, choice) ?: throw InvalidMediaException()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (failure: Exception) {
                    val message = failure.message.orEmpty()
                    if ("429" in message || "no space left" in message.lowercase()) throw failure
                    removeErrorDocuments(directory)
                    Log.i(TAG, "resolved_stream_failed_reextracting source=${link.source.name}")
                } finally { info.delete() }
            }
            resultInfo?.delete()
            execute(null)
            return completedMedia(directory, choice) ?: throw InvalidMediaException()
        } finally {
            removeErrorDocuments(directory)
            firstWrite.stopWatching()
        }
    }

    private suspend fun completedMedia(directory: File, choice: Choice): File? = withContext(Dispatchers.IO) {
        directory.listFiles().orEmpty().firstNotNullOfOrNull { file ->
            if (!file.isFile || file.nameWithoutExtension != "media") return@firstNotNullOfOrNull null
            val mime = mimeFor(file.extension, choice.audioOnly)
            if (file.extension != choice.extension) return@firstNotNullOfOrNull null
            try {
                verifyDownloadedMedia(file, mime ?: throw InvalidMediaException(), choice.requiresAudio)
                file
            } catch (_: InvalidMediaException) {
                if (!file.delete() && file.exists()) throw IOException("Could not remove invalid completed media")
                null
            }
        }
    }

    private fun removeErrorDocuments(directory: File) {
        directory.listFiles().orEmpty().filter {
            it.isFile && it.name.startsWith("media.") && runCatching { beginsWithErrorDocument(it) }.getOrDefault(false)
        }.forEach { it.delete() }
    }

    private fun writeStreamInfo(choice: Choice): File {
        val root = JSONObject().put("id", "saoreo-media").put("title", "SAOREO media")
        val formats = org.json.JSONArray()
        for (stream in choice.reusableStreams) {
            val format = JSONObject().put("format_id", stream.id).put("url", stream.url)
                .put("protocol", "https").put("ext", stream.extension)
                .put("vcodec", stream.videoCodec).put("acodec", stream.audioCodec)
                .put("http_headers", JSONObject(stream.headers))
            formats.put(format)
        }
        root.put("formats", formats)
        val file = File.createTempFile("resolved-stream-", ".json", context.cacheDir)
        try { file.writeText(root.toString()); return file }
        catch (failure: Exception) { file.delete(); throw failure }
    }

    private suspend fun run(
        link: MediaLink, timeout: Int, retries: Int = 1,
        callback: ((Float, Long, String) -> Unit)? = null,
        trace: YoutubeTrace? = null,
        replayInfo: File? = null,
        playlist: Boolean = false,
        options: YoutubeDLRequest.() -> Unit,
    ): String {
        val waiting = SystemClock.elapsedRealtimeNanos()
        return running.withPermit {
            trace?.add("app.concurrency_wait", SystemClock.elapsedRealtimeNanos() - waiting)
            suspendCancellableCoroutine { continuation ->
                val id = UUID.randomUUID().toString()
                val future = executors.submit {
                    try {
                        val initializeStart = SystemClock.elapsedRealtimeNanos()
                        val initialized = engineFactory.isInitialized()
                        val current = engine
                        trace?.add(if (initialized) "engine.reused" else "engine.initialized",
                            SystemClock.elapsedRealtimeNanos() - initializeStart)
                        if (!initialized) initialization.forEach { (label, nanos) -> trace?.add(label, nanos) }
                        if (continuation.isActive) {
                            val proxyStart = SystemClock.elapsedRealtimeNanos()
                            val localProxy = when {
                                link.source in setOf(Source.YOUTUBE, Source.INSTAGRAM) -> MediaProxy(trace,
                                    consistentFamily = true,
                                    onFailure = { host, reason -> (context.applicationContext as SaoreoApp).youtubeDiagnostics.recordHttp(JSONObject()
                                        .put("stage", "local_proxy").put("host", host).put("status", 502).put("reason", reason).toString()) },
                                    onRoute = { host, family -> (context.applicationContext as SaoreoApp).youtubeDiagnostics.recordHttp(JSONObject()
                                        .put("stage", "proxy_route").put("host", host).put("family", family).toString()) })
                                replayInfo != null -> MediaProxy(allowedOrigin = { streamCdnHost(link.source, it) })
                                trace != null -> MediaProxy(trace)
                                else -> null
                            }
                            val activeProxy = localProxy ?: proxy
                            trace?.add("proxy.startup", SystemClock.elapsedRealtimeNanos() - proxyStart)
                            try {
                                val clients = if (link.source == Source.YOUTUBE && replayInfo == null &&
                                    (trace == null || trace.client == YoutubeTrial.DEFAULT)) youtubeClients else listOf(null)
                                var payload: String? = null
                                for ((attempt, client) in clients.withIndex()) {
                                    if (!continuation.isActive || Thread.currentThread().isInterrupted) break
                                    val request = (if (replayInfo == null) YoutubeDLRequest(link.url)
                                        else YoutubeDLRequest(emptyList())).apply {
                                        replayInfo?.let { addOption("--load-info-json", it.absolutePath) }
                                        addOption("--ignore-config")
                                        addOption(if (playlist) "--yes-playlist" else "--no-playlist")
                                        addOption("--playlist-end", if (playlist) 100 else 1)
                                        addOption(if (trace == null) "--no-warnings" else "--verbose")
                                        addOption("--no-colors")
                                        addOption("--cache-dir", File(context.cacheDir, "extractor").absolutePath)
                                        addOption("--proxy", activeProxy.url)
                                        addOption("--socket-timeout", timeout)
                                        addOption("--retries", retries)
                                        // 403s must re-extract, rather than repeatedly retry an expired CDN URL.
                                        if (link.source == Source.YOUTUBE) addOption("--retry-sleep", "http:exp=1:4")
                                        (trace?.client?.extractorArgument ?: client)?.let { addOption("--extractor-args", it) }
                                        options()
                                    }
                                    val entered = SystemClock.elapsedRealtimeNanos()
                                    try {
                                        payload = if (trace == null || trace.mode == YoutubeTraceMode.WRAPPER) {
                                            if (link.source in setOf(Source.YOUTUBE, Source.INSTAGRAM)) transport.execute(request.buildCommand(), callback) { json ->
                                                (context.applicationContext as SaoreoApp).youtubeDiagnostics.recordHttp(json)
                                            } else current.execute(request, id, callback).also { response ->
                                                trace?.add("wrapper.return", SystemClock.elapsedRealtimeNanos() - entered)
                                                if (trace != null) response.err.lineSequence().filter(String::isNotBlank).forEach {
                                                    trace.add(YoutubeVerbose.summary(it))
                                                }
                                            }.out
                                        } else profiledProcess.execute(request.buildCommand(), trace)
                                        break
                                    } catch (failure: Exception) {
                                        trace?.add("wrapper.error", SystemClock.elapsedRealtimeNanos() - entered)
                                        if (trace != null) failure.message.orEmpty().lineSequence().filter(String::isNotBlank).forEach {
                                            trace.add(YoutubeVerbose.summary(it))
                                        }
                                        if (attempt == clients.lastIndex || !shouldRetryYoutubeClient(failure)) throw failure
                                        trace?.add("youtube.client_fallback_${attempt + 1}")
                                        callback?.invoke(0f, 0L, "SAOREO_REFRESH")
                                        Log.i(TAG, "youtube_client_fallback attempt=${attempt + 1}")
                                    }
                                }
                                if (payload == null && continuation.isActive) throw IOException("Extraction interrupted")
                                if (continuation.isActive) continuation.resume(requireNotNull(payload))
                            } finally { localProxy?.close() }
                        }
                    } catch (failure: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    }
                }
                continuation.invokeOnCancellation {
                    future.cancel(true)
                    YoutubeDL.destroyProcessById(id)
                }
            }
        }
    }

    private suspend fun <T> cancellableIo(block: () -> T): T = suspendCancellableCoroutine { continuation ->
        val future = executors.submit {
            try {
                val result = block()
                if (continuation.isActive) continuation.resume(result)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
        continuation.invokeOnCancellation { future.cancel(true) }
    }

    private fun jsonPreview(text: String, trace: YoutubeTrace?, source: Source): Preview {
        val json = mediaMetadataObject(JSONObject(text.trim()))
        val duration = json.optDouble("duration").takeIf { it.isFinite() && it > 0 }
        val formats = json.optJSONArray("formats")
        trace?.add("app.upstream_formats_${formats?.length() ?: 0}")
        val video = mutableListOf<Choice>()
        val videoExtensions = setOf("mp4", "webm", "mkv", "mov")
        val audioExtensions = setOf("m4a", "mp3", "webm", "opus", "aac")
        val audio = mutableListOf<Pair<Choice, Double>>()
        for (index in 0 until (formats?.length() ?: 0)) {
            val format = formats?.optJSONObject(index) ?: continue
            val id = format.optString("format_id")
            val ext = format.optString("ext")
            val protocol = format.optString("protocol")
            val url = format.optString("url")
            if (!id.matches(Regex("[a-zA-Z0-9_.-]+")) ||
                !url.startsWith("https://") || protocol !in setOf("https", "m3u8_native", "http_dash_segments") ||
                format.optBoolean("has_drm", false)
            ) continue
            val videoCodec = format.optString("vcodec").takeUnless { it.isBlank() || it == "null" }
            val audioCodec = format.optString("acodec").takeUnless { it.isBlank() || it == "null" }
            val hasVideo = videoCodec?.let { it != "none" } ?: (ext in videoExtensions)
            val hasAudio = audioCodec?.let { it != "none" }
                ?: (!hasVideo && ext in audioExtensions || hasVideo)
            val bitrate = format.optDouble("tbr", 0.0)
            val size = expectedSize(
                format.optLong("filesize"), format.optLong("filesize_approx"),
                bitrate, duration ?: 0.0,
            )
            val height = format.optInt("height").takeIf { it > 0 }
            val width = format.optInt("width").takeIf { it > 0 }
            val resolution = if (width != null && height != null) minOf(width, height) else height
            fun headers(value: JSONObject?): Map<String, String> = value?.keys()?.asSequence()
                ?.associateWith { value.optString(it) }.orEmpty()
            val replay = reusableStream(
                source, id, url, ext, protocol, videoCodec, audioCodec,
                headers(json.optJSONObject("http_headers")) + headers(format.optJSONObject("http_headers")),
                !format.isNull("fragments") || !format.isNull("fragment_base_url"),
                (!json.isNull("cookies") && json.optString("cookies").isNotBlank()) ||
                    (!format.isNull("cookies") && format.optString("cookies").isNotBlank()),
            )
            val choice = Choice(id, resolution, ext, size,
                audioOnly = !hasVideo, hasAudio = hasAudio, reusableStreams = listOfNotNull(replay),
                requiresAudio = audioCodec != null && audioCodec != "none")
            if (hasVideo && ext in videoExtensions) video += choice
            if (!hasVideo && hasAudio && ext in audioExtensions) {
                audio += choice to bitrate
            }
        }
        val sortedAudio = audio.sortedByDescending { it.second }.map { it.first }
        val choices = outputChoices(video, sortedAudio)
        if (choices.isEmpty()) throw IOException("No downloadable public formats")
        trace?.add("app.visible_choices_${choices.size}")
        return Preview(json.optString("title", "Untitled media").take(240),
            json.optString("thumbnail").takeIf { it.startsWith("https://") }, duration, choices)
    }

    private fun directPreview(link: MediaLink): Pair<MediaLink, Preview?>? {
        val request = Request.Builder().url(link.url).header("User-Agent", USER_AGENT).build()
        mediaHttp.newCall(request).execute().use { response ->
            val final = MediaLink.parse(response.request.url.toString()) ?: return null
            if (!response.isSuccessful || final.source != link.source) return null
            val stream = response.body?.byteStream() ?: return null
            val limit = if (link.source == Source.PINTEREST) 3_000_000 else 750_000
            val html = stream.readNBytesCompat(limit).toString(Charsets.UTF_8)
            val preview = if (link.source == Source.PINTEREST) pinImagePreview(html) else spotlightPreview(html)
            return final to preview
        }
    }

    private suspend fun addDirectSizes(source: Source, preview: Preview): Preview = coroutineScope {
        val metadataClient = mediaHttp.newBuilder().callTimeout(4, java.util.concurrent.TimeUnit.SECONDS).build()
        val choices = preview.choices.mapIndexed { index, choice ->
            async {
                val url = choice.imageUrl ?: choice.directVideoUrl
                if (index >= 4 || url == null || choice.size != null) return@async choice
                val bytes = cancellableIo {
                    try {
                        metadataClient.newCall(Request.Builder().url(url).head().build()).execute().use {
                            it.header("Content-Length")?.toLongOrNull()?.takeIf { size ->
                                size > 0 && it.isSuccessful && directAssetHost(source, it.request.url.host)
                            }
                        }
                    } catch (_: IOException) { null }
                }
                choice.copy(size = bytes?.let { ExpectedSize(it, false) })
            }
        }.awaitAll()
        preview.copy(choices = choices)
    }

    companion object {
        internal const val ENGINE_VERSION = "2026.11.20.232656"
        private const val ENGINE_SHA256 = "03f4e98b9d65d41c509235666439fb3d2f51fb0d10892a06c764ad9def770f0d"
        private const val TAG = "SAOREO"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36"
    }
}

private fun java.io.InputStream.readNBytesCompat(maximum: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (output.size() < maximum) {
        val read = read(buffer, 0, minOf(buffer.size, maximum - output.size()))
        if (read < 0) break
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private fun openGraph(html: String): Map<String, List<String>> {
    val values = LinkedHashMap<String, MutableList<String>>()
    val attributes = Regex("""([\w:-]+)\s*=\s*(['"])(.*?)\2""", RegexOption.DOT_MATCHES_ALL)
    Regex("""<meta\s+[^>]{1,4096}>""", RegexOption.IGNORE_CASE).findAll(html).forEach { tag ->
        val attrs = attributes.findAll(tag.value).associate { it.groupValues[1].lowercase() to it.groupValues[3] }
        val name = attrs["property"] ?: attrs["name"] ?: return@forEach
        val value = attrs["content"] ?: return@forEach
        values.getOrPut(name.lowercase()) { mutableListOf() }.add(
            value.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">"),
        )
    }
    return values
}

internal fun directAssetHost(source: Source, host: String): Boolean = when (source) {
    Source.INSTAGRAM -> streamCdnHost(source, host)
    Source.PINTEREST -> host == "i.pinimg.com"
    Source.SNAPCHAT -> host == "cf-st.sc-cdn.net"
    else -> false
}

internal fun spotlightPreview(html: String): Preview? {
    val graph = openGraph(html)
    if (graph["og:video:type"]?.firstOrNull() != "video/mp4") return null
    val url = (graph["og:video:secure_url"].orEmpty() + graph["og:video"].orEmpty())
        .firstOrNull { value ->
            val uri = runCatching { URI(value) }.getOrNull()
            uri?.scheme == "https" && uri.host?.let { directAssetHost(Source.SNAPCHAT, it) } == true &&
                uri.rawUserInfo == null && uri.port in setOf(-1, 443) && uri.path.startsWith("/d/")
        } ?: return null
    val width = graph["og:video:width"]?.firstOrNull()?.toIntOrNull()?.takeIf { it > 0 }
    val height = graph["og:video:height"]?.firstOrNull()?.toIntOrNull()?.takeIf { it > 0 }
    val resolution = if (width != null && height != null) minOf(width, height) else height
    val thumbnail = graph["og:image"]?.firstOrNull()?.takeIf { it.startsWith("https://") }
    val duration = graph["video:duration"]?.firstOrNull()?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
    val title = graph["og:title"]?.firstOrNull()?.take(240) ?: "Snapchat Spotlight"
    return Preview(title, thumbnail, duration,
        listOf(Choice("spotlight-mp4", resolution, "mp4", null, directVideoUrl = url)))
}

internal fun pinImagePreview(html: String): Preview? {
    val values = openGraph(html)
    val videoSnippet = Regex(
        """<script\b[^>]*\bdata-test-id\s*=\s*(['"])video-snippet\1""",
        RegexOption.IGNORE_CASE,
    ).containsMatchIn(html)
    if (videoSnippet || values.keys.any { it.startsWith("og:video") } ||
        values["og:type"].orEmpty().any { it.startsWith("video") }) return null
    val declared = values["og:image"].orEmpty()
    // Some public pages declare the original in their Pin data while Open Graph uses a
    // resized variant. Match the asset key before accepting an explicitly declared original.
    val hashes = declared.mapNotNull { published ->
        val uri = runCatching { URI(published) }.getOrNull()
        uri?.takeIf { it.host == "i.pinimg.com" }?.path?.split('/')?.drop(2)?.joinToString("/")
    }.toSet()
    val linkedOriginals = if (hashes.isEmpty()) emptyList() else Regex(
        """https://i[.]pinimg[.]com/originals/[a-zA-Z0-9/_.-]{1,256}""",
    ).findAll(html.replace("""\/""", "/")).map { it.value }
        .filter { it.substringAfter("/originals/") in hashes }.take(12).toList()
    val choices = (declared + linkedOriginals).distinct().mapNotNull { image ->
        val uri = runCatching { URI(image) }.getOrNull() ?: return@mapNotNull null
        val ext = uri.path?.substringAfterLast('.')?.lowercase()
        if (uri.scheme != "https" || uri.host != "i.pinimg.com" || uri.rawUserInfo != null ||
            uri.port !in setOf(-1, 443) || ext !in setOf("jpg", "jpeg", "png", "webp", "gif") ||
            !uri.path.startsWith("/originals/")) return@mapNotNull null
        Choice(image, values["og:image:height"]?.singleOrNull()?.toIntOrNull()?.takeIf { image in declared }, ext!!, null,
            imageUrl = image, width = values["og:image:width"]?.singleOrNull()?.toIntOrNull()?.takeIf { image in declared })
    }.take(12)
    if (choices.isEmpty()) return null
    return Preview(values["og:title"]?.firstOrNull()?.take(240) ?: "Pinterest image",
        choices.first().imageUrl, null, choices)
}
