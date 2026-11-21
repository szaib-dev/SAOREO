package com.codetic.saoreo

import android.content.Context
import android.media.MediaMetadataRetriever
import com.yausername.ffmpeg.FFmpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class MediaSection(val start: Long, val end: Long) {
    init { require(start >= 0 && end > start && end <= 7L * 24 * 60 * 60 * 1000) }
    val duration get() = end - start
}

internal fun sectionTime(value: String): Long? {
    val raw = value.trim()
    if (!raw.matches(Regex("""(?:[0-9]{1,3}:){0,2}[0-9]{1,6}(?:\.[0-9]{1,3})?"""))) return null
    val parts = raw.split(':')
    val last = parts.last().split('.')
    val seconds = last[0].toLong()
    if (parts.size > 1 && seconds >= 60 || parts.size > 2 && parts[1].toLong() >= 60) return null
    val leading = parts.dropLast(1).fold(0L) { total, number -> total * 60 + number.toLong() }
    return ((leading * 60 + seconds) * 1000 + (last.getOrNull(1)?.padEnd(3, '0')?.toLong() ?: 0L))
        .takeIf { it <= 7L * 24 * 60 * 60 * 1000 }
}

internal fun sectionFromText(start: String, end: String, sourceDuration: Double?): MediaSection? {
    val first = sectionTime(start) ?: return null
    val last = sectionTime(end) ?: return null
    if (last <= first || sourceDuration?.let { last > (it * 1000).toLong() } == true) return null
    return MediaSection(first, last)
}

internal class SectionFailure(message: String) : IOException(message)

internal fun sectionCommand(
    input: String, output: String, section: MediaSection, audio: Boolean, bitrate: Int,
    loopback: Boolean,
): List<String> {
    fun seconds(ms: Long) = "${ms / 1000}.${(ms % 1000).toString().padStart(3, '0')}"
    val inputArgs = listOf("-nostdin", "-hide_banner", "-loglevel", "error", "-y",
        "-protocol_whitelist", if (loopback) "http,tcp" else "file",
        "-format_whitelist", if (loopback) "mov,mp4,m4a,3gp,3g2,mj2" else "mov,mp4,m4a,3gp,3g2,mj2,mp3,matroska,webm,ogg,aac",
        "-threads", "2", "-ss", seconds(section.start), "-i", input, "-t", seconds(section.duration),
        "-map_metadata", "-1", "-map_chapters", "-1", "-sn", "-dn")
    val codecs = if (audio) listOf("-map", "0:a:0", "-vn", "-c:a", "libmp3lame", "-b:a", "${bitrate}k")
    else listOf("-map", "0:v:0", "-map", "0:a:0?", "-c:v", "libx264", "-threads", "2", "-preset", "veryfast",
        "-crf", "20", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "160k", "-movflags", "+faststart")
    return inputArgs + codecs + listOf(output)
}

internal suspend fun exportSection(
    context: Context, input: String, output: File, section: MediaSection, choice: Choice,
    bitrate: Int, loopback: Boolean,
): File {
    val executable = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
    val libraries = File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/lib")
    withContext(Dispatchers.IO) { FFmpeg.init(context) }
    try {
        suspendCancellableCoroutine<Unit> { result ->
            val child = java.util.concurrent.atomic.AtomicReference<Process>()
            result.invokeOnCancellation { child.get()?.destroyForcibly() }
            thread(name = "saoreo-section") {
                try {
                    if (!result.isActive) return@thread
                    val process = ProcessBuilder(listOf(executable.absolutePath) +
                        sectionCommand(input, output.absolutePath, section, choice.audioOnly, bitrate, loopback))
                        .apply { environment()["LD_LIBRARY_PATH"] = libraries.absolutePath }.redirectErrorStream(true).start()
                    child.set(process)
                    if (!result.isActive) process.destroyForcibly()
                    process.inputStream.use { data ->
                        val buffer = ByteArray(4096)
                        while (data.read(buffer) >= 0) { /* Drain bounded process diagnostics. */ }
                    }
                    val code = process.waitFor()
                    if (result.isActive) {
                        if (code == 0) result.resume(Unit)
                        else result.resumeWithException(SectionFailure("This section could not be encoded. Check the range and available storage."))
                    }
                } catch (error: Exception) {
                    if (result.isActive) result.resumeWithException(error)
                } finally { child.getAndSet(null)?.destroyForcibly() }
            }
        }
        withContext(Dispatchers.IO) {
            val mime = if (choice.audioOnly) "audio/mpeg" else "video/mp4"
            verifyDownloadedMedia(output, mime, choice.requiresAudio)
            MediaMetadataRetriever().use { reader ->
                reader.setDataSource(output.absolutePath)
                val duration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?: throw SectionFailure("Couldn't verify the exported section.")
                if (kotlin.math.abs(duration - section.duration) > 300L) {
                    throw SectionFailure("The source ended before the requested section could be exported.")
                }
            }
        }
        return output
    } catch (failure: Exception) {
        output.delete()
        throw failure
    }
}

private val rangeHeader = Regex("""bytes ([0-9]+)-([0-9]+)/([0-9]+)""")
internal fun verifiedRange(header: String?, first: Long, last: Long, expectedTotal: Long? = null): Long? {
    val parts = header?.let(rangeHeader::matchEntire)?.groupValues ?: return null
    val total = parts[3].toLongOrNull() ?: return null
    return total.takeIf {
        parts[1].toLongOrNull() == first && parts[2].toLongOrNull() == last &&
            first >= 0 && last >= first && total > last && (expectedTotal == null || total == expectedTotal)
    }
}

/** Only the chosen source is served, through the app's HTTPS checks, behind a random loopback path. */
internal class SectionRangeSource private constructor(
    private val request: Request, private val source: Source, private val total: Long,
    private val etag: String?, private val progress: (Long) -> Unit,
) : AutoCloseable {
    private val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
    private val path = "/${UUID.randomUUID()}/source.mp4"
    val input = "http://127.0.0.1:${server.localPort}$path"
    @Volatile private var active: Socket? = null
    @Volatile private var transfer: Call? = null
    private val bytes = AtomicLong()
    init {
        thread(name = "saoreo-section-ranges", isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                active = socket
                try { socket.use(::serve) } catch (_: IOException) { /* FFmpeg can close a connection after a seek. */ }
                finally { active = null }
            }
        }
    }
    private fun serve(socket: Socket) {
        socket.soTimeout = 8000
        socket.sendBufferSize = 32 * 1024
        val input = socket.getInputStream().buffered()
        fun line(): String {
            val result = StringBuilder()
            repeat(4096) {
                val next = input.read()
                if (next < 0 || next == 10) return result.toString().trimEnd('\r')
                result.append(next.toChar())
            }
            throw IOException("Long request header")
        }
        val method = line().split(' ')
        if (method.size != 3 || method[1] != path || method[0] !in setOf("GET", "HEAD")) return
        var range: String? = null
        for (i in 0 until 50) {
            val header = line()
            if (header.isEmpty()) break
            if (header.startsWith("Range:", ignoreCase = true)) range = header.substringAfter(':').trim()
        }
        val match = range?.let { Regex("""bytes=([0-9]+)-([0-9]*)""").matchEntire(it) }
        if (range != null && match == null) return
        val start = match?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val end = minOf(match?.groupValues?.get(2)?.toLongOrNull() ?: total - 1, total - 1)
        if (start !in 0..end) return
        val output = socket.getOutputStream()
        val code = if (range != null) "206 Partial Content" else "200 OK"
        val contentRange = if (range != null) "Content-Range: bytes $start-$end/$total\r\n" else ""
        output.write(("HTTP/1.1 $code\r\nContent-Type: video/mp4\r\nAccept-Ranges: bytes\r\n" +
            "Content-Length: ${end - start + 1}\r\n${contentRange}Connection: close\r\n\r\n").toByteArray())
        if (method[0] == "HEAD") return
        var offset = start
        val buffer = ByteArray(16 * 1024)
        while (offset <= end && !server.isClosed) {
            val last = minOf(end, offset + 256 * 1024 - 1)
            val fetch = mediaHttp.newCall(request.newBuilder().header("Range", "bytes=$offset-$last").apply {
                if (etag != null) header("If-Range", etag)
            }.build())
            transfer = fetch
            if (server.isClosed) { fetch.cancel(); return }
            try {
                fetch.execute().use { response ->
                    if (response.code != 206 || !approvedSectionHost(source, response.request.url.host) ||
                        verifiedRange(response.header("Content-Range"), offset, last, total) == null ||
                        etag != null && response.header("ETag") != etag) throw IOException("Source range changed")
                    val body = response.body ?: throw IOException("Source returned no media")
                    body.byteStream().use { stream ->
                        var remaining = last - offset + 1
                        while (remaining > 0 && !server.isClosed) {
                            val count = stream.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                            if (count < 0) throw IOException("Truncated source range")
                            progress(bytes.addAndGet(count.toLong()))
                            output.write(buffer, 0, count)
                            remaining -= count
                        }
                    }
                }
            } finally { transfer = null }
            offset = last + 1
        }
    }
    override fun close() {
        server.close()
        runCatching { active?.close() }
        transfer?.cancel()
    }

    companion object {
        suspend fun open(source: Source, choice: Choice, progress: (Long) -> Unit): SectionRangeSource? {
            val stream = choice.reusableStreams.singleOrNull()?.takeIf { it.extension in setOf("mp4", "m4a", "mov") }
            val url = choice.directVideoUrl ?: stream?.url ?: return null
            val builder = Request.Builder().url(url).header("Accept-Encoding", "identity")
            stream?.headers?.forEach { (name, value) -> builder.header(name, value) }
            val request = builder.build()
            if (!approvedSectionHost(source, request.url.host)) return null
            return suspendCancellableCoroutine { result ->
                val call = mediaHttp.newCall(request.newBuilder().header("Range", "bytes=0-0").build())
                result.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (result.isActive) result.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val bridge = response.use {
                                if (!result.isActive || response.code != 206 ||
                                    !approvedSectionHost(source, response.request.url.host)) return@use null
                                val size = verifiedRange(response.header("Content-Range"), 0, 0)
                                    ?.takeIf { it <= 16L * 1024 * 1024 * 1024 } ?: return@use null
                                val tag = response.header("ETag")?.takeIf { it.startsWith('"') && it.endsWith('"') }
                                SectionRangeSource(request, source, size, tag, progress)
                            }
                            result.resume(bridge, onCancellation = { _, abandoned, _ -> abandoned?.close() })
                        } catch (error: IOException) {
                            if (result.isActive) result.resumeWithException(error)
                        }
                    }
                })
            }
        }
    }
}

private fun approvedSectionHost(source: Source, host: String) = streamCdnHost(source, host) || directAssetHost(source, host)
