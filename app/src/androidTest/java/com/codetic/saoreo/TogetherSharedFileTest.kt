package com.codetic.saoreo

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TogetherSharedFileTest {
    @Test fun selectedFileIsSeekableAndMetadataComesFromTheActualVideo() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "transcript-clipboard").apply { mkdirs() }
        val file = File(directory, "together-source-test.mp4")
        try {
            instrumentation.context.assets.open("integrity-sample.mp4").use { input -> file.outputStream().use(input::copyTo) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.transcripts", file)
            val result = inspectSharedVideo(context.contentResolver, uri)
            assertEquals(file.length(), result.size)
            assertEquals(uri, result.uri)
            assertEquals("video/mp4", result.mime)
            assertTrue(result.duration > 0)
            context.contentResolver.openAssetFileDescriptor(uri, "r")!!.use { asset ->
                asset.createInputStream().use { input ->
                    val offset = result.size - 100
                    input.channel.position(asset.startOffset + offset)
                    val range = input.readNBytes(100)
                    assertArrayEquals(file.readBytes().takeLast(100).toByteArray(), range)
                }
            }
            file.writeText("<html>This is not a video</html>")
            var rejected = false
            try { inspectSharedVideo(context.contentResolver, uri) } catch (_: Exception) { rejected = true }
            assertTrue(rejected)
        } finally { file.delete() }
    }
}
