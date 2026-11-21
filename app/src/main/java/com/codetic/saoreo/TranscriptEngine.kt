package com.codetic.saoreo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal enum class SpeechModel(val label: String, val stem: String, val size: Long, val hash: String) {
    TINY("Tiny multilingual · recommended", "tiny-q5_1", 32152673L,
        "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7"),
    BASE("Base multilingual", "base-q5_1", 59707625L,
        "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"),
}

internal data class TranscriptStatus(
    val result: Transcript? = null, val tracks: List<CaptionTrack> = emptyList(),
    val title: String? = null, val thumbnail: String? = null,
    val message: String? = null, val progress: Int? = null, val busy: Boolean = false,
    val lookupUrl: String? = null, val captionFailure: Boolean = false,
)

internal class TranscriptEngine(private val app: SaoreoApp) {
    val state = MutableStateFlow(TranscriptStatus())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val root = File(app.noBackupFilesDir, "transcripts")
    val cache = File(root, "cache")
    private val models = File(root, "models")
    private val staging = File(app.cacheDir, "speech")
    private var job: Job? = null
    private var infoLink: MediaLink? = null
    @Volatile private var process: Process? = null
    val modelRevision = MutableStateFlow(0)
    private var startingService = false

    fun reset() { if (!state.value.busy) { infoLink = null; state.value = TranscriptStatus() } }
    fun cancel() { job?.cancel(); process?.destroyForcibly() }
    fun installed(model: SpeechModel) = modelFile(model).length() == model.size
    fun storage(): Long = StatFs(app.filesDir.path).availableBytes
    private fun modelFile(model: SpeechModel) = File(models, "${model.stem}.bin")
    private fun cacheFile(key: String) = File(cache, digest(key.toByteArray(Charsets.UTF_8)) + ".json")

    private fun launch(label: String, onEnd: () -> Unit = {}, work: suspend () -> Unit) {
        if (job?.isCompleted == false) { onEnd(); return }
        state.value = state.value.copy(busy = true, message = label, progress = null)
        job = scope.launch(start = CoroutineStart.LAZY) {
            try { work() }
            catch (_: TimeoutCancellationException) {
                state.value = state.value.copy(message = "The request timed out. Try again or choose a local file.")
            } catch (_: CancellationException) { state.value = state.value.copy(message = "Cancelled") }
            catch (error: TranscriptFailure) { state.value = state.value.copy(message = error.message) }
            catch (_: Exception) { state.value = state.value.copy(message = "Couldn’t finish. Check the source, connection or available storage.") }
            finally {
                state.value = state.value.copy(busy = false, progress = null)
                startingService = false
                onEnd()
            }
        }
        job?.start()
    }

    fun discover(link: MediaLink, refresh: Boolean = false) {
        if (state.value.busy) return
        reset()
        state.value = state.value.copy(lookupUrl = link.url)
        launch("Finding captions…") {
            val key = link.metadataKey()
            if (!refresh) cached("$key/default")?.let {
                state.value = state.value.copy(result = it, title = it.title, thumbnail = it.thumbnail,
                    message = "Showing cached transcript")
                return@launch
            }
            val settings = app.preferences.flow.first()
            val info = try { app.extractor.captionInfo(link, settings.timeout) }
            catch (_: TimeoutCancellationException) {
                state.value = state.value.copy(captionFailure = true, message = "Caption check timed out. Try again or transcribe the audio.")
                return@launch
            } catch (c: CancellationException) { throw c }
            catch (_: Exception) {
                state.value = state.value.copy(captionFailure = true, message = "Couldn’t reach this video’s captions. Try again or transcribe the audio.")
                return@launch
            }
            infoLink = link
            val tracks = buildList {
                for (field in listOf("subtitles", "automatic_captions")) {
                    val group = info.optJSONObject(field) ?: continue
                    for (language in group.keys()) {
                        val variants = group.optJSONArray(language) ?: continue
                        if (language == "live_chat") continue
                        val entries = (0 until variants.length()).mapNotNull(variants::optJSONObject)
                        val files = entries.mapNotNull { entry ->
                            val format = entry.optString("ext")
                            val url = entry.optString("url")
                            if (format in setOf("vtt", "srt", "json3") && url.startsWith("https://")) CaptionFile(format, url)
                            else null
                        }.distinctBy { it.format }.sortedBy { listOf("srt", "json3", "vtt").indexOf(it.format) }
                        if (files.isEmpty()) continue
                        val label = entries.firstNotNullOfOrNull { it.optString("name").takeIf(String::isNotBlank) } ?: language
                        add(CaptionTrack("$field/$language", if (field == "subtitles") label else "$label · automatic", files))
                    }
                }
            }
            val title = info.optString("title").ifBlank { link.source.label }
            val thumb = info.optString("thumbnail").takeIf { it.startsWith("https://") }
            state.value = state.value.copy(tracks = tracks, title = title, thumbnail = thumb)
            if (tracks.isEmpty()) {
                val unavailable = link.source == Source.YOUTUBE && title.startsWith("youtube video #", ignoreCase = true)
                state.value = state.value.copy(captionFailure = true, message = if (unavailable)
                    "YouTube did not return caption information for this video."
                else "This video has no readable caption tracks.")
                return@launch
            }
            val original = info.optString("language").substringBefore('-').ifBlank {
                tracks.firstOrNull { it.key.endsWith("-orig") }?.key?.substringAfter('/')?.substringBefore('-').orEmpty()
            }
            val preferred = tracks.sortedBy { track ->
                val locale = track.key.substringAfter('/')
                val language = locale.substringBefore('-')
                val automatic = track.key.startsWith("automatic_captions/")
                when {
                    language == original && !automatic -> 0
                    locale.endsWith("-orig") -> 1
                    language == original -> 2
                    language == "en" && !automatic -> 3
                    !automatic -> 4
                    language == "en" -> 5
                    else -> 6
                }
            }
            val first = preferred.first()
            val firstLanguage = first.key.substringAfter('/').substringBefore('-')
            val alternatives = preferred.drop(1).filter {
                it.key.substringAfter('/').substringBefore('-') == firstLanguage &&
                    it.key.substringBefore('/') != first.key.substringBefore('/')
            }.take(1)
            for (track in listOf(first) + alternatives) {
                state.value = state.value.copy(captionFailure = false, message = "Loading ${track.label} captions…")
                if (fetchTrack(link, track, default = true)) break
            }
        }
    }

    fun select(track: CaptionTrack) {
        val link = infoLink ?: return
        launch("Loading ${track.label} captions…") { fetchTrack(link, track, default = false) }
    }

    private suspend fun fetchTrack(link: MediaLink, track: CaptionTrack, default: Boolean): Boolean {
        val key = "${link.metadataKey()}/${track.key}"
        val result = cached(key) ?: run {
            var lines = emptyList<TranscriptLine>()
            for (file in track.files) {
                try {
                    val output = withTimeout(9_000) { requestBytes(file.url, 16 * 1024 * 1024) }
                    lines = withContext(Dispatchers.Default) {
                        val body = output.toString(Charsets.UTF_8)
                        if (file.format == "json3") parseJsonCaptions(body) else parseTranscript(body)
                    }
                    if (lines.isNotEmpty()) break
                } catch (_: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IOException) {
                    // An expired or unavailable format can still have a usable alternative.
                } catch (_: org.json.JSONException) {
                    // Some endpoints answer a caption request with a non-caption payload.
                }
            }
            if (lines.isEmpty()) {
                state.value = state.value.copy(captionFailure = true,
                    message = "The ${track.label} track couldn’t be loaded. Try another language or transcribe the audio.")
                return false
            }
            Transcript(state.value.title ?: link.source.label, lines, state.value.thumbnail).also { save(key, it) }
        }
        if (default) save("${link.metadataKey()}/default", result)
        state.value = state.value.copy(result = result, message = track.label, captionFailure = false)
        return true
    }

    fun import(uri: Uri) {
        if (uri.scheme != "content" || state.value.busy) return
        reset()
        launch("Opening transcript…") {
            val result = withContext(Dispatchers.IO) {
                val name = name(uri)
                val extension = name.substringAfterLast('.').lowercase()
                if (extension !in setOf("txt", "vtt", "srt")) throw TranscriptFailure("Choose a TXT, SRT or VTT file.")
                val raw = app.contentResolver.openInputStream(uri)?.use { boundedRead(it, 16 * 1024 * 1024) }
                    ?: throw TranscriptFailure("This file is unavailable.")
                val lines = parseTranscript(raw.toString(Charsets.UTF_8), extension == "txt")
                if (lines.isEmpty()) throw TranscriptFailure("This file has no readable transcript.")
                Transcript(name.substringBeforeLast('.'), lines)
            }
            state.value = state.value.copy(result = result, title = result.title, message = null)
        }
    }

    fun clearCache() = launch("Clearing transcripts…") {
        withContext(Dispatchers.IO) { cache.deleteRecursively() }
        state.value = state.value.copy(message = "Cached transcripts cleared")
    }

    fun deleteModel(model: SpeechModel) = launch("Removing model…") {
        withContext(Dispatchers.IO) { modelFile(model).delete() }
        modelRevision.value++
        state.value = state.value.copy(message = "Model removed")
    }

    fun startService(action: String, model: SpeechModel, source: String? = null, local: Uri? = null, language: String = "auto") {
        if (state.value.busy || startingService) return
        startingService = true
        state.value = state.value.copy(busy = true, message = "Preparing…", progress = null)
        try {
            ContextCompat.startForegroundService(app, Intent(app, TranscriptService::class.java).setAction(action)
                .setData(local).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra("model", model.name).putExtra("url", source).putExtra("language", language))
        } catch (_: Exception) {
            startingService = false
            state.value = state.value.copy(busy = false, message = "Android could not start background processing.")
        }
    }

    fun serviceUnavailable() {
        startingService = false
        state.value = state.value.copy(busy = false, message = "Android could not start background processing.")
    }

    fun model(model: SpeechModel, onEnd: () -> Unit) = launch("Downloading model…", onEnd) {
        withContext(Dispatchers.IO) {
            models.mkdirs()
            if (storage() < model.size + 8_000_000) throw TranscriptFailure("Free up storage before downloading this model.")
            val target = modelFile(model)
            val partial = File(models, "${model.stem}.part")
            try {
                downloadModel(model, partial)
                checkModel(model, partial)
                android.system.Os.rename(partial.path, target.path)
            } finally { partial.delete() }
        }
        modelRevision.value++
        state.value = state.value.copy(message = "Model ready")
    }

    fun transcribe(model: SpeechModel, link: MediaLink?, uri: Uri?, language: String, onEnd: () -> Unit) =
        launch("Preparing audio…", onEnd) {
            val key = link?.let { "${it.metadataKey()}/whisper/${model.name}/$language" }
            val cached = key?.let { cached(it) }
            val result = cached ?: withContext(Dispatchers.IO) {
                checkModel(model, modelFile(model))
                staging.deleteRecursively()
                staging.mkdirs()
                try {
                    val source = if (link != null) {
                        update("Retrieving audio…")
                        withTimeout(25 * 60_000L) { app.extractor.transcriptAudio(link, staging, app.preferences.flow.first().timeout) }
                    } else {
                        require(uri?.scheme == "content")
                        update("Reading local media…")
                        val local = File(staging, "local-source")
                        app.contentResolver.openInputStream(requireNotNull(uri))?.use { input ->
                            local.outputStream().use { output ->
                                val buffer = ByteArray(256 * 1024)
                                var bytes = 0L
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    bytes += n
                                    if (bytes > 1024L * 1024 * 1024) throw TranscriptFailure("Choose a local recording under 1 GB.")
                                    output.write(buffer, 0, n)
                                }
                            }
                        } ?: throw TranscriptFailure("This media file is unavailable.")
                        local
                    }
                    if (source.length() > 1024L * 1024 * 1024) throw TranscriptFailure("The audio source exceeds 1 GB.")
                    val localKey = if (link == null) "local/${hashFile(source)}/${model.name}/$language" else null
                    if (localKey != null) cached(localKey)?.let { return@withContext it }
                    update("Preparing speech audio…")
                    native("libsaoreomp3.so", listOf("-nostdin", "-v", "error", "-xerror", "-max_alloc", "67108864",
                        "-protocol_whitelist", "file", "-format_whitelist", "mov,matroska,webm,wav,mp3,ogg,flac,aac",
                        "-threads", "1", "-i", source.path, "-map", "0:a:0", "-vn", "-sn", "-dn",
                        "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", "-f", "segment", "-segment_time", "480",
                        "-segment_format", "wav", "-reset_timestamps", "1", File(staging, "chunk-%05d.wav").path)) {}
                    source.delete()
                    val chunks = staging.listFiles().orEmpty().filter { it.name.startsWith("chunk-") }.sortedBy { it.name }
                    if (chunks.isEmpty()) throw TranscriptFailure("No readable audio track was found.")
                    val lines = mutableListOf<TranscriptLine>()
                    var elapsed = 0L
                    for ((index, chunk) in chunks.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        val output = File(staging, "segment")
                        val srt = File(staging, "segment.srt")
                        srt.delete()
                        update("Transcribing audio…", index * 100 / chunks.size)
                        native("libsaoreowhisper.so", listOf("-m", modelFile(model).path, "-f", chunk.path,
                            "-t", "2", "-ng", "-l", language, "-osrt", "-of", output.path, "-pp")) { row ->
                            val part = Regex("progress\\s*=\\s*(\\d+)%").find(row)?.groupValues?.get(1)?.toIntOrNull()
                            if (part != null) update("Transcribing audio…", ((index * 100 + part) / chunks.size).coerceIn(0, 99))
                        }
                        if (srt.isFile) lines += parseTranscript(srt.readText(), offset = elapsed)
                        elapsed += wavDuration(chunk)
                        chunk.delete()
                    }
                    if (lines.isEmpty()) throw TranscriptFailure("No speech was recognized.")
                    val title = if (link == infoLink) state.value.title else null
                    Transcript(title ?: uri?.let { name(it).substringBeforeLast('.') } ?: "${link?.source?.label} transcript",
                        lines, if (link == infoLink) state.value.thumbnail else null).also {
                        if (localKey != null) save(localKey, it)
                    }
                } finally { staging.deleteRecursively() }
            }
            if (key != null && cached == null) save(key, result)
            state.value = state.value.copy(result = result, title = result.title,
                message = if (cached == null) "Generated on this device" else "Showing cached transcript")
        }

    private fun wavDuration(file: File): Long {
        java.io.RandomAccessFile(file, "r").use { input ->
            input.seek(12)
            while (input.filePointer + 8 <= input.length()) {
                val tag = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
                val size = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
                if (tag == "data") return size * 1000 / 32000
                if (size > input.length() - input.filePointer) break
                input.seek(input.filePointer + size + (size % 2))
            }
        }
        return 480_000L
    }

    private suspend fun native(binary: String, args: List<String>, line: (String) -> Unit) {
        val executable = File(app.applicationInfo.nativeLibraryDir, binary)
        if (!executable.canExecute()) throw TranscriptFailure("The local speech engine is unavailable on this device.")
        val running = ProcessBuilder(listOf(executable.path) + args).redirectErrorStream(true).start()
        process = running
        try {
            currentCoroutineContext().ensureActive()
            running.inputStream.bufferedReader().use { reader ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    line(reader.readLine() ?: break)
                }
            }
            currentCoroutineContext().ensureActive()
            if (running.waitFor() != 0) throw TranscriptFailure("Couldn’t process this audio. Check its format and available memory or storage.")
        } finally {
            running.destroyForcibly()
            process = null
        }
    }

    private fun update(label: String, percent: Int? = null) {
        state.value = state.value.copy(message = label, progress = percent)
    }

    private fun checkModel(model: SpeechModel, file: File) {
        if (file.length() != model.size) throw TranscriptFailure("Download a verified model first.")
        if (hashFile(file) != model.hash) throw TranscriptFailure("Model verification failed. Delete and download it again.")
    }

    private fun hashFile(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                hash.update(buffer, 0, n)
            }
        }
        return hash.digest().hex()
    }

    private suspend fun downloadModel(model: SpeechModel, file: File) =
        fetch("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-${model.stem}.bin?download=true") { stream ->
            file.outputStream().use { sink ->
                val buffer = ByteArray(128 * 1024)
                var count = 0L
                var last = -1
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    count += n
                    if (count > model.size) throw IOException("Model exceeds expected size")
                    sink.write(buffer, 0, n)
                    val percent = (count * 100 / model.size).toInt().coerceAtMost(99)
                    if (percent != last) { update("Downloading model…", percent); last = percent }
                }
            }
        }

    private suspend fun requestBytes(url: String, limit: Int) = fetch(url) { boundedRead(it, limit) }

    private suspend fun <T> fetch(url: String, read: (InputStream) -> T): T = suspendCancellableCoroutine { continuation ->
        val call = mediaHttp.newCall(Request.Builder().url(url).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                try {
                    val result = response.use {
                        if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                        it.body?.byteStream()?.use(read) ?: throw IOException("Missing response")
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }

    private suspend fun cached(key: String) = withContext(Dispatchers.IO) {
        runCatching { Transcript.decode(cacheFile(key).readText()) }.getOrNull()
    }
    private suspend fun save(key: String, transcript: Transcript) = withContext(Dispatchers.IO) {
        runCatching {
            cache.mkdirs()
            val records = cache.listFiles().orEmpty().filter { it.extension == "json" }.sortedByDescending { it.lastModified() }
            if (records.size >= 40) records.drop(39).forEach(File::delete)
            val temp = File.createTempFile("transcript", ".tmp", cache)
            try {
                temp.writeText(transcript.encode(), Charsets.UTF_8)
                android.system.Os.rename(temp.path, cacheFile(key).path)
            } finally { temp.delete() }
        }
    }

    fun name(uri: Uri) = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Local media"

    private class TranscriptFailure(message: String) : IOException(message)
    private fun digest(input: ByteArray) = MessageDigest.getInstance("SHA-256").digest(input).hex()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun boundedRead(input: InputStream, limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return output.toByteArray()
            if (n > limit - output.size()) throw IOException("Transcript exceeds the supported size.")
            output.write(buffer, 0, n)
        }
    }
}
