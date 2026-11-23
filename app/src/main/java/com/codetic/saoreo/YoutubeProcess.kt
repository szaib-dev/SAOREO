package com.codetic.saoreo

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.concurrent.FutureTask
import kotlin.concurrent.thread

/** Same packaged Python, QuickJS and FFmpeg as the Android wrapper, with observable HTTP stages. */
internal class YoutubeProcess(private val context: Context) {
    fun execute(arguments: List<String>, callback: ((Float, Long, String) -> Unit)?, evidence: (String) -> Unit): String {
        val bin = File(context.applicationInfo.nativeLibraryDir)
        val packages = File(context.noBackupFilesDir, "youtubedl-android/packages")
        val python = File(packages, "python/usr")
        val script = File.createTempFile("yt-transport-", ".py", context.cacheDir)
        try {
            context.resources.openRawResource(R.raw.yt_transport).use { input -> script.outputStream().use(input::copyTo) }
            val command = listOf(File(bin, "libpython.so").absolutePath, script.absolutePath,
                File(context.noBackupFilesDir, "youtubedl-android/yt-dlp/yt-dlp").absolutePath,
                "--js-runtimes", "quickjs:${File(bin, "libqjs.so").absolutePath}",
                "--ffmpeg-location", File(bin, "libffmpeg.so").absolutePath) + arguments
            val builder = ProcessBuilder(command)
            builder.environment().apply {
                this["LD_LIBRARY_PATH"] = (listOf(bin.absolutePath, FfmpegRuntime.supportLibraries(context).absolutePath) + listOf("python", "ffmpeg", "aria2c")
                    .map { File(packages, "$it/usr/lib").absolutePath }).joinToString(":")
                this["SSL_CERT_FILE"] = File(python, "etc/tls/cert.pem").absolutePath
                this["PYTHONHOME"] = python.absolutePath
                this["HOME"] = python.absolutePath
                this["TMPDIR"] = context.cacheDir.absolutePath
                this["PATH"] = System.getenv("PATH").orEmpty() + ":" + bin.absolutePath
            }
            val process = builder.start()
            try {
                val output = FutureTask {
                    val text = StringBuilder()
                    process.inputStream.bufferedReader().useLines { lines -> lines.forEach {
                        if (it.startsWith("[download]") || it.startsWith("[Merger]") || it.startsWith("[ExtractAudio]") ||
                            it.startsWith("[VideoConvertor]") || it.startsWith("SAOREO_PROCESS")) callback?.invoke(0f, 0L, it)
                        else if (text.length < 12_000_000) text.appendLine(it)
                    } }
                    text.toString()
                }
                val errors = FutureTask {
                    val tail = StringBuilder()
                    process.errorStream.bufferedReader().useLines { lines -> lines.forEach {
                        if (it.startsWith("SAOREO_HTTP ")) evidence(it.removePrefix("SAOREO_HTTP "))
                        else {
                            tail.appendLine(it)
                            if (tail.length > 16_000) tail.delete(0, tail.length - 12_000)
                        }
                    } }
                    tail.toString()
                }
                thread(isDaemon = true, name = "yt-output") { output.run() }
                thread(isDaemon = true, name = "yt-network") { errors.run() }
                val exit = process.waitFor()
                val error = errors.get()
                if (exit != 0) throw IOException(error.ifBlank { "YouTube process exited with $exit" })
                return output.get()
            } finally {
                if (process.isAlive) {
                    val interrupted = Thread.interrupted()
                    try {
                        process.destroy()
                        if (!process.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)) process.destroyForcibly()
                    } finally { if (interrupted) Thread.currentThread().interrupt() }
                }
            }
        } finally { script.delete() }
    }
}
