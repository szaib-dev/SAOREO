package com.codetic.saoreo

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Instagram can serve valid MP4 containers Android cannot read until remuxed or transcoded. */
internal suspend fun prepareInstagramVideo(context: Context, input: File, requireAudio: Boolean): File {
    try {
        withContext(Dispatchers.IO) { verifyDownloadedMedia(input, "video/mp4", requireAudio) }
        return input
    } catch (_: InvalidMediaException) { /* Keep the original until a verified replacement exists. */ }
    if (withContext(Dispatchers.IO) { !input.isFile || input.length() == 0L || beginsWithErrorDocument(input) })
        throw InvalidMediaException()
    withContext(Dispatchers.IO) { FFmpeg.init(context) }
    val executable = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
    val libraries = File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/lib")
    val output = File(input.parentFile, "instagram-normalized.mp4")
    try {
        for (transcode in listOf(false, true)) {
            output.delete()
            val codecs = if (transcode) listOf("-c:v", "libx264", "-threads", "2", "-preset", "veryfast",
                "-crf", "20", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "160k") else listOf("-c", "copy")
            val args = listOf("-nostdin", "-y", "-v", "error", "-protocol_whitelist", "file", "-i", input.absolutePath,
                "-map", "0:v:0", "-map", if (requireAudio) "0:a:0" else "0:a:0?", "-sn", "-dn") +
                codecs + listOf("-movflags", "+faststart", output.absolutePath)
            val exit = suspendCancellableCoroutine<Int> { continuation ->
                val child = AtomicReference<Process>()
                continuation.invokeOnCancellation { child.get()?.destroyForcibly() }
                thread(name = "saoreo-instagram-normalize") {
                    try {
                        if (!continuation.isActive) return@thread
                        val process = ProcessBuilder(listOf(executable.absolutePath) + args)
                            .apply { environment()["LD_LIBRARY_PATH"] = libraries.absolutePath }.redirectErrorStream(true).start()
                        child.set(process)
                        if (!continuation.isActive) process.destroyForcibly()
                        process.inputStream.use { stream ->
                            val buffer = ByteArray(4096)
                            while (stream.read(buffer) >= 0) { /* Drain without retaining media URLs. */ }
                        }
                        val code = process.waitFor()
                        if (continuation.isActive) continuation.resume(code)
                    } catch (failure: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    } finally { child.getAndSet(null)?.destroyForcibly() }
                }
            }
            if (exit != 0) continue
            try {
                withContext(Dispatchers.IO) { verifyDownloadedMedia(output, "video/mp4", requireAudio) }
            } catch (_: InvalidMediaException) { continue }
            if (!output.renameTo(input)) throw IOException("Could not stage the prepared Instagram video.")
            return input
        }
        throw InvalidMediaException()
    } finally { output.delete() }
}
