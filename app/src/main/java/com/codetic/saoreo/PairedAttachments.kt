package com.codetic.saoreo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.nimbusds.jose.crypto.DirectDecrypter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.FileOutputStream
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.security.MessageDigest
import java.util.Base64

private const val PART_BYTES = 48 * 1024
private const val MAX_BYTES = PART_BYTES * 512L
private val attachmentId = Regex("[a-f0-9]{32}")
private val digestId = Regex("[a-f0-9]{64}")
private val mimeType = Regex("[a-z0-9.+-]+/[a-z0-9.+-]+")

internal data class ChatAttachment(
    val id: String, val name: String, val mime: String, val size: Long, val hash: String,
    val voice: Boolean, val duration: Long, val waveform: List<Int>, val thumbnail: String? = null, val album: String? = null,
) {
    val parts get() = ((size + PART_BYTES - 1) / PART_BYTES).toInt()
    private val extension get() = name.substringAfterLast('.', "bin").takeIf { it.matches(Regex("[A-Za-z0-9]{1,12}")) } ?: "bin"
    val storedName get() = "media.$extension"

    fun json() = JSONObject().put("id", id).put("name", name).put("mime", mime).put("size", size)
        .put("hash", hash).put("voice", voice).put("duration", duration).put("waveform", org.json.JSONArray(waveform))
        .putOpt("thumbnail", thumbnail).putOpt("album", album)

    fun previewBitmap(): Bitmap? = thumbnail?.let {
        val bytes = runCatching { Base64.getDecoder().decode(it) }.getOrNull() ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outMimeType == "image/jpeg" && bounds.outWidth in 1..320 && bounds.outHeight in 1..320)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size) else null
    }

    companion object {
        fun parse(data: JSONObject?): ChatAttachment? {
            data ?: return null
            val id = data.optString("id").takeIf(attachmentId::matches) ?: return null
            val name = data.optString("name").takeIf { it == safeAttachmentName(it) } ?: return null
            val mime = data.optString("mime").takeIf { it.length <= 100 && mimeType.matches(it) } ?: return null
            val size = data.optLong("size").takeIf { it in 1..MAX_BYTES } ?: return null
            val hash = data.optString("hash").takeIf(digestId::matches) ?: return null
            val voice = data.optBoolean("voice")
            val duration = data.optLong("duration").coerceIn(0, 24L * 60 * 60 * 1000)
            val levels = data.optJSONArray("waveform")
            val waveform = List(minOf(levels?.length() ?: 0, 32)) { levels!!.optInt(it).coerceIn(0, 100) }
            if (voice && (!mime.startsWith("audio/") || duration == 0L)) return null
            val thumbnail = data.optString("thumbnail").takeIf { it.length in 4..6800 && it.all { char ->
                char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in "+/="
            } }
            return ChatAttachment(id, name, mime, size, hash, voice, duration, waveform, thumbnail,
                data.optString("album").takeIf(attachmentId::matches))
        }
    }
}

private fun safeAttachmentName(value: String): String =
    value.filter { it != '/' && it != '\\' && !it.isISOControl() }.trim().take(140)
        .takeIf { it.isNotEmpty() && it != "." && it != ".." } ?: "Attachment"

internal data class AttachmentRequest(val method: String, val id: String, val part: Int = 0,
    val total: Int = 0, val envelope: String? = null)

internal class PairedAttachments(private val context: Context) {
    private val root = File(context.filesDir, "paired-media")
    private val downloads = Mutex()
    private val mutableProgress = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Long>>(emptyMap())
    val progress: kotlinx.coroutines.flow.StateFlow<Map<String, Long>> = mutableProgress
    private fun report(id: String, bytes: Long?) {
        synchronized(mutableProgress) {
            mutableProgress.value = if (bytes == null) mutableProgress.value - id else mutableProgress.value + (id to bytes)
        }
    }
    private val cache = File(context.cacheDir, "paired-media")

    private fun folder(peer: ChatPeer, author: String, id: String): File {
        require(attachmentId.matches(peer.conversation) && digestId.matches(author) && attachmentId.matches(id))
        return File(File(File(root, peer.conversation), author), id)
    }

    suspend fun stage(peer: ChatPeer, key: ByteArray, id: String, uri: Uri, voice: Boolean,
        duration: Long, waveform: List<Int>): ChatAttachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        require(uri.scheme in setOf("content", "file"))
        val name = if (voice) "Voice note.m4a" else resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME),
            null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Attachment"
        val mime = if (voice) "audio/mp4" else resolver.getType(uri)
            ?.lowercase(java.util.Locale.ROOT)?.takeIf { it.length <= 100 && mimeType.matches(it) }
                ?: "application/octet-stream"
        val dir = folder(peer, peer.self, id)
        check(dir.mkdirs())
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            resolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(PART_BYTES)
                var part = 0
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.readNBytes(buffer, 0, buffer.size)
                    if (count == 0) break
                    bytes += count
                    if (bytes > MAX_BYTES) throw IOException("Attachments must be 24 MB or smaller")
                    digest.update(buffer, 0, count)
                    val envelope = encryptPart(key, id, part, buffer.copyOf(count))
                    savePart(dir, part++, envelope)
                }
            } ?: throw IOException("Couldn’t open the attachment")
            if (bytes == 0L) throw IOException("The attachment is empty")
            val media = mediaPreview(uri, mime)
            ChatAttachment(id, safeAttachmentName(name), mime, bytes, digest.digest().hex(), voice,
                if (voice) duration else media.second, waveform, media.first)
        } catch (failure: Throwable) {
            dir.deleteRecursively()
            throw failure
        }
    }

    private fun mediaPreview(uri: Uri, mime: String): Pair<String?, Long> {
        return runCatching {
            when {
                mime.startsWith("video/") -> MediaMetadataRetriever().use {
                    it.setDataSource(context, uri)
                    val duration = it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val preview = runCatching { it.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 320) }.getOrNull()
                    preview?.let(::encodePreview) to duration
                }
                mime.startsWith("image/") && uri.scheme == "content" -> {
                    val preview = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                        val (width, height) = info.size.let { it.width to it.height }
                        val scale = minOf(1f, 320f / maxOf(width, height))
                        decoder.setTargetSize((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                    encodePreview(preview) to 0L
                }
                else -> null to 0L
            }
        }.getOrDefault(null to 0L)
    }

    private fun encodePreview(preview: Bitmap): String? {
        try {
            val target = ByteArrayOutputStream(4096)
            for (quality in listOf(64, 46, 30, 18, 9)) {
                target.reset()
                if (preview.compress(Bitmap.CompressFormat.JPEG, quality, target) && target.size() <= 5100) {
                    return Base64.getEncoder().encodeToString(target.toByteArray())
                }
            }
            return null
        } finally {
            preview.recycle()
        }
    }

    suspend fun upload(peer: ChatPeer, item: ChatAttachment, transfer: suspend (AttachmentRequest) -> String) {
        val dir = folder(peer, peer.self, item.id)
        val stored = transfer(AttachmentRequest("HEAD", item.id)).toIntOrNull()
            ?.takeIf { it in 0..item.parts } ?: throw IOException("Invalid attachment upload status")
        report(item.id, minOf(item.size, stored.toLong() * PART_BYTES))
        try {
        for (part in stored until item.parts) {
            val envelope = withContext(Dispatchers.IO) { File(dir, part.toString()).readText(Charsets.US_ASCII) }
            transfer(AttachmentRequest("PUT", item.id, part, item.parts, envelope))
            report(item.id, minOf(item.size, (part + 1L) * PART_BYTES))
        }
        } finally { report(item.id, null) }
    }

    fun contains(peer: ChatPeer, author: String, item: ChatAttachment): Boolean {
        val dir = folder(peer, author, item.id)
        return (author == peer.self || File(dir, "verified").isFile) &&
            (0 until item.parts).all { File(dir, it.toString()).isFile }
    }

    fun exportMedia(peer: ChatPeer, author: String, item: ChatAttachment, key: ByteArray, output: OutputStream) {
        val dir = folder(peer, author, item.id)
        val decrypter = DirectDecrypter(key)
        for (part in 0 until item.parts) {
            val envelope = File(dir, part.toString()).readText(Charsets.US_ASCII)
            output.write(decryptAttachmentPart(decrypter, item, part, envelope))
        }
    }

    fun restoreMedia(peer: ChatPeer, author: String, item: ChatAttachment, key: ByteArray,
        source: InputStream, work: File): Pair<File, File> {
        val destination = folder(peer, author, item.id)
        val staged = File(File(work, author), item.id).apply { check(mkdirs()) }
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in 0 until item.parts) {
            val count = minOf(PART_BYTES.toLong(), item.size - part.toLong() * PART_BYTES).toInt()
            val bytes = source.readNBytes(count)
            check(bytes.size == count) { "Truncated media in backup" }
            digest.update(bytes)
            savePart(staged, part, encryptPart(key, item.id, part, bytes))
        }
        check(digest.digest().hex() == item.hash) { "Media did not match the backup" }
        File(staged, "verified").createNewFile()
        return staged to destination
    }

    private fun directorySize(directory: File) = if (directory.isDirectory) directory.walkTopDown()
        .filter(File::isFile).sumOf(File::length) else 0L

    fun size(peer: ChatPeer): Pair<Long, Long> = directorySize(File(root, peer.conversation)) to directorySize(cache)

    suspend fun clearCache() = downloads.withLock { withContext(Dispatchers.IO) { cache.deleteRecursively() } }

    fun cached(peer: ChatPeer, author: String, item: ChatAttachment): File =
        File(File(cache, folder(peer, author, item.id).relativeTo(root).path), item.storedName)

    suspend fun discardOutgoing(peer: ChatPeer, id: String) = withContext(Dispatchers.IO) {
        folder(peer, peer.self, id).deleteRecursively()
    }

    suspend fun open(peer: ChatPeer, author: String, item: ChatAttachment, key: ByteArray,
        transfer: suspend (AttachmentRequest) -> String): File = downloads.withLock {
        withContext(Dispatchers.IO) {
            val dir = folder(peer, author, item.id).apply { mkdirs() }
            val viewDir = File(cache, dir.relativeTo(root).path).apply { mkdirs() }
            val final = File(viewDir, item.storedName)
            val verified = File(dir, "verified")
            val legacy = File(dir, item.storedName)
            if (!verified.isFile || !final.isFile) {
                if (author != peer.self) report(item.id, 0)
                val partial = File(viewDir, "opening")
                val digest = MessageDigest.getInstance("SHA-256")
                val decrypter = DirectDecrypter(key)
                try {
                    legacy.takeIf(File::isFile)?.inputStream().use { old ->
                        FileOutputStream(partial).use { output ->
                            for (part in 0 until item.parts) {
                                coroutineContext.ensureActive()
                                val stored = File(dir, part.toString())
                                val bytes = if (old != null) {
                                    old.readNBytes(PART_BYTES).also {
                                        if (author != peer.self && !stored.isFile)
                                            savePart(dir, part, encryptPart(key, item.id, part, it))
                                    }
                                } else {
                                    val envelope = if (stored.isFile) stored.readText(Charsets.US_ASCII)
                                        else if (author != peer.self) transfer(AttachmentRequest("GET", item.id, part))
                                        else throw IOException("Attachment staging file is missing")
                                    decryptAttachmentPart(decrypter, item, part, envelope).also {
                                        if (!stored.isFile) savePart(dir, part, envelope)
                                    }
                                }
                                digest.update(bytes)
                                output.write(bytes)
                                if (author != peer.self) report(item.id, minOf(item.size, (part + 1L) * PART_BYTES))
                            }
                        }
                    }
                    if (partial.length() != item.size || digest.digest().hex() != item.hash) {
                        if (author != peer.self && !verified.isFile) {
                            for (part in 0 until item.parts) File(dir, part.toString()).delete()
                        }
                        throw IOException("Attachment integrity check failed")
                    }
                    if (author != peer.self && !verified.isFile) File(dir, "ack-pending").createNewFile()
                    verified.createNewFile()
                    if (!partial.renameTo(final)) throw IOException("Couldn’t open the attachment")
                    legacy.delete()
                    File(dir, "receiving").delete()
                } finally {
                    partial.delete()
                    if (author != peer.self) report(item.id, null)
                }
            }
            if (author != peer.self && File(dir, "ack-pending").isFile) acknowledge(item.id, dir, transfer)
            final
        }
    }

    suspend fun acknowledgeDownloads(peer: ChatPeer, transfer: suspend (AttachmentRequest) -> String) =
        withContext(Dispatchers.IO) {
            val inbox = File(File(root, peer.conversation), peer.partner)
            inbox.listFiles()?.filter { attachmentId.matches(it.name) && File(it, "ack-pending").isFile &&
                File(it, "verified").isFile }?.take(12)?.forEach { acknowledge(it.name, it, transfer) }
        }

    private fun savePart(dir: File, part: Int, envelope: String) {
        val pending = File(dir, "$part.part")
        FileOutputStream(pending).use { stream ->
            stream.write(envelope.toByteArray(Charsets.US_ASCII))
            stream.fd.sync()
        }
        if (!pending.renameTo(File(dir, part.toString()))) throw IOException("Couldn’t store encrypted attachment")
    }

    private fun encryptPart(key: ByteArray, id: String, part: Int, bytes: ByteArray): String {
        val clear = JSONObject().put("id", id).put("part", part)
            .put("bytes", Base64.getEncoder().encodeToString(bytes))
        return encryptChatEnvelope(key, clear.toString())
    }

    private suspend fun acknowledge(id: String, dir: File, transfer: suspend (AttachmentRequest) -> String) {
        try {
            transfer(AttachmentRequest("DELETE", id))
            File(dir, "ack-pending").delete()
        } catch (error: ChatHttpError) {
            if (error.code == 410) File(dir, "ack-pending").delete()
        } catch (_: IOException) {
            // The durable marker is retried during conversation synchronization.
        }
    }
}

internal fun decryptAttachmentPart(decrypter: DirectDecrypter, item: ChatAttachment, part: Int, envelope: String): ByteArray {
    return try {
        val clear = JSONObject(decryptChatEnvelope(decrypter, envelope))
        require(clear.getString("id") == item.id && clear.getInt("part") == part)
        Base64.getDecoder().decode(clear.getString("bytes")).also {
            require(it.size.toLong() == minOf(PART_BYTES.toLong(), item.size - part.toLong() * PART_BYTES))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        throw IOException("Attachment integrity check failed", error)
    }
}

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

