package com.codetic.saoreo

import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SectionExportTest {
    @Test fun embeddedEncoderProducesPlayableMp4AndMp3Selections() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "sections-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val source = directory.resolve("source.mp4")
            instrumentation.context.assets.open("playback-fixture.mp4").use { input ->
                source.outputStream().use { output -> input.copyTo(output) }
            }
            val section = MediaSection(1200, 3400)
            val video = exportSection(context, source.absolutePath, directory.resolve("clip.mp4"), section,
                Choice("video", 180, "mp4", null, requiresAudio = true), 192, false)
            val audio = exportSection(context, source.absolutePath, directory.resolve("clip.mp3"), section,
                Choice("mp3:audio", null, "mp3", null, audioOnly = true, requiresAudio = true), 192, false)
            for (file in listOf(video, audio)) {
                MediaMetadataRetriever().use { reader ->
                    reader.setDataSource(file.absolutePath)
                    assertEquals("yes", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                    val duration = checkNotNull(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong()
                    assertTrue(kotlin.math.abs(duration - section.duration) < 150L)
                }
            }
        } finally { directory.deleteRecursively() }
    }
}
