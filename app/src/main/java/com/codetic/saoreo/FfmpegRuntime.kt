package com.codetic.saoreo

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import com.yausername.ffmpeg.FFmpeg
import java.io.File
import java.io.IOException

private const val RUNTIME = "ffmpeg-runtime-v1"

/** The upstream FFmpeg archive expects these SONAMEs but does not package them. */
internal object FfmpegRuntime {
    @Synchronized
    fun initialize(context: Context) {
        FFmpeg.init(context)
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "x86_64" }
            ?: throw IOException("No FFmpeg runtime for this device.")
        val directory = supportLibraries(context)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not prepare FFmpeg libraries.")
        for (name in listOf("libandroid-posix-semaphore.so", "libandroid-support.so", "libexpat.so.1", "libcrypto.so.3")) {
            val library = File(directory, name)
            if (library.isFile && library.length() > 0) continue
            val target = AtomicFile(library)
            val output = target.startWrite()
            try {
                context.assets.open("$RUNTIME/$abi/$name").use { it.copyTo(output) }
                target.finishWrite(output)
            } catch (failure: Throwable) {
                target.failWrite(output)
                throw failure
            }
        }
    }

    fun supportLibraries(context: Context) = File(context.noBackupFilesDir, RUNTIME)
}
