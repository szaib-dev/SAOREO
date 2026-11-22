package com.codetic.saoreo

import android.content.ContentResolver
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import org.json.JSONObject
import java.io.IOException
import java.nio.ByteBuffer

internal const val TOGETHER_FILE_CHUNK = 128 * 1024
internal const val TOGETHER_MAX_FILE = 20L * 1024 * 1024 * 1024
internal data class SharedLocalVideo(val uri: Uri, val size: Long, val mime: String, val title: String, val duration: Long)

internal suspend fun inspectSharedVideo(resolver: ContentResolver, uri: Uri): SharedLocalVideo = withContext(Dispatchers.IO) {
    require(uri.scheme == "content") { "Choose a video from the Android file picker." }
    val title = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Shared video"
    val mime = resolver.getType(uri)?.takeIf { it in setOf("video/mp4", "video/webm", "video/x-matroska") }
        ?: when (title.substringAfterLast('.').lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            else -> throw IOException("Choose an MP4, WebM or MKV video.")
        }
    val descriptor = resolver.openAssetFileDescriptor(uri, "r") ?: throw IOException("Cannot open the selected video.")
    val size = descriptor.use { asset ->
        asset.createInputStream().use { input ->
            val size = asset.length.takeIf { it > 0 } ?: (input.channel.size() - asset.startOffset)
            require(size in 1..TOGETHER_MAX_FILE) { "Choose a local video under 20 GiB." }
            // Range playback needs a seekable provider, including when the MP4 index is at its end.
            input.channel.position(asset.startOffset + size - 1)
            check(input.channel.read(ByteBuffer.allocate(1)) == 1) { "Copy this video to device storage before sharing it." }
            size
        }
    }
    val duration = MediaMetadataRetriever().use { reader ->
        val asset = resolver.openAssetFileDescriptor(uri, "r") ?: throw IOException("The video is unavailable.")
        asset.use {
            if (it.length > 0) reader.setDataSource(it.fileDescriptor, it.startOffset, it.length)
            else reader.setDataSource(it.fileDescriptor)
            require(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes") { "The file has no readable video track." }
            reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        }
    }
    SharedLocalVideo(uri, size, mime, title.take(150), duration)
}

/** Only bytes from explicitly selected files can be served; no remote paths or content URIs are accepted. */
internal class TogetherSharedFiles(private val session: TogetherSession, private val resolver: ContentResolver,
    private val scope: CoroutineScope) {
    private val sources = mutableMapOf<String, Pair<String, SharedLocalVideo>>()
    private val slots = Semaphore(8)
    private val jobs = mutableSetOf<Job>()
    fun remember(id: String, room: String, video: SharedLocalVideo) { sources[id] = room to video }
    fun local(id: String): String? = sources[id]?.takeIf { it.first == session.state.value.room }?.second?.uri?.toString()
    fun serve(request: JSONObject) {
        val id = request.optString("request")
        val owned = sources[request.optString("source")]
        val offset = request.optLong("offset", -1)
        val length = request.optInt("length", -1)
        if (!id.matches(Regex("[a-f0-9]{32}"))) return
        if (owned == null || owned.first != session.state.value.room || length !in 1..TOGETHER_FILE_CHUNK ||
            offset < 0 || offset > owned.second.size - length || !slots.tryAcquire()) {
            session.send("fileChunk", JSONObject().put("request", id).put("error", true)); return
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val payload = withContext(Dispatchers.IO) {
                    val asset = resolver.openAssetFileDescriptor(owned.second.uri, "r")
                        ?: throw IOException("The source file is unavailable.")
                    asset.use { descriptor -> descriptor.createInputStream().use { input ->
                        input.channel.position(descriptor.startOffset + offset)
                        val bytes = input.readNBytes(length)
                        if (bytes.size != length) throw IOException("The source file changed.")
                        Base64.encodeToString(bytes, Base64.NO_WRAP)
                    } }
                }
                ensureActive()
                if (session.state.value.room == owned.first)
                    session.send("fileChunk", JSONObject().put("request", id).put("data", payload))
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                if (session.state.value.room == owned.first) session.send("fileChunk", JSONObject().put("request", id).put("error", true))
            } finally { slots.release() }
        }
        jobs.add(job)
        job.invokeOnCompletion { jobs.remove(job) }
        job.start()
    }
    fun clear() { jobs.toList().forEach(Job::cancel); sources.clear() }
}
