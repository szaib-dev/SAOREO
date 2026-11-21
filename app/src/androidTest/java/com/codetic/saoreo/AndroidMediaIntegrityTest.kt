package com.codetic.saoreo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidMediaIntegrityTest {
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
