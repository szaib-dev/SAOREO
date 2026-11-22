package com.codetic.saoreo

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer

/** Opt-in real-network acceptance. Failing transfers fail the test rather than being swallowed. */
@RunWith(AndroidJUnit4::class)
class YoutubeAcceptanceTest {
    @Test fun downloadDecodeAndPlay() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("saoreo_url")
        assumeTrue("Pass saoreo_url for the real-network test", url != null)
        val app = ApplicationProvider.getApplicationContext<SaoreoApp>()
        val link = requireNotNull(MediaLink.parse(requireNotNull(url)))
        val audio = args.getString("saoreo_audio", "false").toBoolean()
        val directory = File(app.cacheDir, "youtube-acceptance-${java.util.UUID.randomUUID()}-${if (audio) "audio" else "video"}").apply { mkdirs() }
        val start = android.os.SystemClock.elapsedRealtime()
        try {
            assertEquals(Extractor.ENGINE_VERSION, app.extractor.engineVersion())
            val (file, title) = withTimeout(12 * 60_000) {
                app.extractor.downloadYoutube(link, if (audio) Quality.AUDIO else Quality.P360, if (audio) null else 360,
                    directory, 15, 1, true, 128) { progress ->
                    if (progress.bytes > 0) android.util.Log.i("SAOREO_ACCEPTANCE", "bytes=${progress.bytes} total=${progress.total} speed=${progress.bytesPerSecond}")
                }
            }
            assertFalse(beginsWithErrorDocument(file))
            val extractor = MediaExtractor()
            val tracks = mutableSetOf<String>()
            try {
                extractor.setDataSource(file.absolutePath)
                for (index in 0 until extractor.trackCount) {
                    val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
                    extractor.selectTrack(index)
                    assertTrue("Track $mime has media samples", extractor.readSampleData(ByteBuffer.allocate(4 * 1024 * 1024), 0) > 0)
                    extractor.unselectTrack(index)
                    tracks += mime.substringBefore('/')
                }
            } finally { extractor.release() }
            assertTrue("Audio track required", "audio" in tracks)
            if (!audio) assertTrue("Video track required", "video" in tracks)
            val played = CompletableDeferred<Unit>()
            lateinit var player: ExoPlayer
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(app).build()
                player.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) played.complete(Unit) }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) { played.completeExceptionally(error) }
                })
                player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
                player.prepare(); player.play()
            }
            try { withTimeout(30_000) { played.await() } } finally { instrumentation.runOnMainSync { player.release() } }
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "VERIFIED title=$title audio=$audio bytes=${file.length()} tracks=$tracks elapsed_ms=${android.os.SystemClock.elapsedRealtime() - start}\n")
            })
        } finally {
            File(app.noBackupFilesDir, "youtube-acceptance-report.txt").writeText(app.youtubeDiagnostics.export())
        }
    }
}
