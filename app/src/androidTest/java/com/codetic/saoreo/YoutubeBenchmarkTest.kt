package com.codetic.saoreo

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class YoutubeBenchmarkTest {
    private val app = ApplicationProvider.getApplicationContext<SaoreoApp>()

    @Test fun benchmark() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val link = args.getString("saoreo_benchmark_url")?.let(MediaLink::parse)
        assumeTrue("Set saoreo_benchmark_url to an authorized public YouTube link", link?.source == Source.YOUTUBE)
        requireNotNull(link)
        val mode = YoutubeTraceMode.valueOf(args.getString("saoreo_mode", "PROFILE"))
        require(mode != YoutubeTraceMode.OFF)
        val trial = YoutubeTrial.valueOf(args.getString("saoreo_trial", "DEFAULT"))
        val count = args.getString("saoreo_runs", "1").toInt().coerceIn(1, 6)
        val bypass = args.getString("saoreo_bypass_app_cache", "true").toBoolean()
        repeat(count) { index ->
            if (bypass || index == 0) app.extractor.clearCache()
            val trace = YoutubeTrace(index + 1, mode, trial)
            val start = SystemClock.elapsedRealtimeNanos()
            try {
                val preview = app.extractor.preview(link, 15, trace)
                val formats = preview.choices.map { it.id }.sorted().joinToString("\n")
                val digest = MessageDigest.getInstance("SHA-256").digest(formats.toByteArray())
                    .joinToString("") { "%02x".format(it) }
                trace.add("benchmark.choices_${preview.choices.size}.fingerprint_$digest")
                trace.add("benchmark.result_success", SystemClock.elapsedRealtimeNanos() - start)
                if (args.getString("saoreo_download", "false").toBoolean() && index == count - 1) {
                    verifyDownload(link, preview, trace)
                }
            } catch (_: TimeoutCancellationException) {
                trace.add("benchmark.result_timeout", SystemClock.elapsedRealtimeNanos() - start)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                trace.add("benchmark.result_error", SystemClock.elapsedRealtimeNanos() - start)
            } finally {
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString("stream", "\n${trace.report()}\n")
                })
            }
        }
    }

    private suspend fun verifyDownload(link: MediaLink, preview: Preview, trace: YoutubeTrace) {
        val choice = selectChoice(preview.choices, Quality.BEST, "mp4")
            ?: error("No downloadable video")
        val directory = File(app.cacheDir, "benchmark-${UUID.randomUUID()}")
        val start = SystemClock.elapsedRealtimeNanos()
        try {
            val file = withTimeout(180_000) {
                app.extractor.download(link, choice, directory, 15, 1, false) {}
            }
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                val sample = ByteBuffer.allocate(4 * 1024 * 1024)
                val tracks = (0 until extractor.trackCount).mapNotNull { index ->
                    val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    extractor.selectTrack(index)
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    sample.clear()
                    val hasSample = extractor.readSampleData(sample, 0) > 0
                    extractor.unselectTrack(index)
                    mime?.substringBefore('/')?.takeIf { hasSample }
                }
                val valid = "audio" in tracks && "video" in tracks
                trace.add(if (valid) "download.audio_video_samples_verified" else "download.track_check_failed",
                    SystemClock.elapsedRealtimeNanos() - start)
            } finally { extractor.release() }
        } catch (_: TimeoutCancellationException) {
            trace.add("download.timeout", SystemClock.elapsedRealtimeNanos() - start)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            trace.add("download.failed", SystemClock.elapsedRealtimeNanos() - start)
        } finally { directory.deleteRecursively() }
    }
}
