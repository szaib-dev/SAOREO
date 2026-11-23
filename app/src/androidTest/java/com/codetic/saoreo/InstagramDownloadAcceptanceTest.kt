package com.codetic.saoreo

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InstagramDownloadAcceptanceTest {
    @Test fun publicReelCompletesThroughDownloadsAndSavesToMediaStore(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("instagram_url")
        assumeTrue("Pass instagram_url for the live acceptance test", url != null)
        val link = requireNotNull(MediaLink.parse(requireNotNull(url)))
        assertEquals(Source.INSTAGRAM, link.source)
        val app = ApplicationProvider.getApplicationContext<SaoreoApp>()
        ActivityScenario.launch(MainActivity::class.java).use {
            val settings = Settings(quality = Quality.BEST, retry = false, resume = false)
            val id = app.downloads.enqueue(link, settings, null, null)
            try {
                val result = withTimeout(150_000) {
                    var row = app.dao.find(id)
                    while (row?.active != false) {
                        delay(350)
                        row = app.dao.find(id)
                    }
                    requireNotNull(row)
                }
                assertEquals(result.error, JobState.COMPLETE.name, result.state)
                assertTrue((result.bytes ?: 0L) > 0)
                val saved = Uri.parse(requireNotNull(result.galleryUri))
                assertTrue(isReadable(app, saved))
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(app, saved, null)
                    val tracks = (0 until extractor.trackCount).map { index ->
                        extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
                    }
                    assertTrue(tracks.any { it.startsWith("video/") })
                    if (args.getString("instagram_expect_audio") == "true") {
                        assertTrue(tracks.any { it.startsWith("audio/") })
                    }
                } finally {
                    extractor.release()
                }
                android.util.Log.i("SAOREO_ACCEPTANCE", "Instagram saved playable MP4 bytes=${result.bytes}")
            } finally {
                app.dao.find(id)?.let { row ->
                    if (row.active) app.downloads.cancel(row)
                    app.downloads.remove(row, true)
                }
            }
        }
    }
}
