package com.codetic.saoreo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidMediaIntegrityTest {
    @Test fun instagramRepairRebuildsUnreadableContainerWithoutAcceptingHtml() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File.createTempFile("saoreo-instagram", ".mp4", instrumentation.targetContext.cacheDir)
        try {
            instrumentation.context.assets.open("integrity-sample.mp4").use { input -> file.outputStream().use(input::copyTo) }
            file.appendBytes(byteArrayOf(0, 0, 0, 0))
            assertTrue(!hasCompleteMp4Boxes(file))
            prepareInstagramVideo(instrumentation.targetContext, file, requireAudio = true)
            verifyDownloadedMedia(file, "video/mp4", requireAudio = true)
            assertTrue(hasCompleteMp4Boxes(file))
            file.writeText("<html>Login required</html>")
            var rejected = false
            try { prepareInstagramVideo(instrumentation.targetContext, file, false) }
            catch (_: InvalidMediaException) { rejected = true }
            assertTrue(rejected)
        } finally { file.delete() }
    }

    @Test fun requiresReadableAudioAndVideoSamples() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File.createTempFile("saoreo-integrity", ".mp4", instrumentation.targetContext.cacheDir)
        try {
            instrumentation.context.assets.open("integrity-sample.mp4").use { input ->
                file.outputStream().use(input::copyTo)
            }
            verifyDownloadedMedia(file, "video/mp4", requireAudio = true)
            file.writeText("<html><body>Upstream error</body></html>")
            assertThrows(InvalidMediaException::class.java) {
                verifyDownloadedMedia(file, "video/mp4", requireAudio = true)
            }
        } finally { file.delete() }
    }
}
