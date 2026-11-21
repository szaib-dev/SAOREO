@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.codetic.saoreo

import android.content.ComponentName
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

@RunWith(AndroidJUnit4::class)
class LibraryPlaybackConversionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as SaoreoApp

    @Test fun mediaStorePlaybackCanSeekAndUseAlternateSpeed() = runBlocking {
        withVideo { input, _ ->
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
                lateinit var player: ExoPlayer
                val ready = CountDownLatch(1)
                scenario.onActivity {
                    player = ExoPlayer.Builder(it).build()
                    player.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_READY) ready.countDown()
                        }
                    })
                    player.setMediaItem(MediaItem.fromUri(input.uri))
                    player.prepare()
                }
                try {
                    assertTrue("MediaStore video did not become ready", ready.await(20, TimeUnit.SECONDS))
                    scenario.onActivity {
                        assertTrue(player.isCurrentMediaItemSeekable)
                        assertTrue(player.duration in 4_900L..5_200L)
                        player.seekTo(2_200)
                        player.setPlaybackSpeed(1.5f)
                        assertEquals(1.5f, player.playbackParameters.speed, 0.01f)
                        assertEquals(2_200L, player.currentPosition)
                    }
                } finally { scenario.onActivity { player.release() } }
            }
        }
    }

    @Test fun realMp3IsPublishedWhileUiIsStoppedAndOriginalIsPreserved() = runBlocking {
        withVideo { input, original ->
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
                scenario.onActivity { assertTrue(app.converter.start(input, 192)) }
                scenario.moveToState(Lifecycle.State.CREATED)
                val status = terminal()
                val output = checkNotNull(status.output) { status.stage }
                try {
                    MediaMetadataRetriever().use { reader ->
                        reader.setDataSource(app, output)
                        assertEquals("audio/mpeg", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE))
                        val duration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                        val bitrate = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)!!.toInt()
                        assertTrue(duration in 4_850..5_250)
                        assertTrue(bitrate in 185_000..200_000)
                    }
                    val saved = app.dao.observe().first().single { it.galleryUri == output.toString() }
                    assertEquals(JobState.COMPLETE.name, saved.state)
                    assertEquals("audio/mpeg", saved.mime)
                    assertArrayEquals(original, app.contentResolver.openInputStream(input.uri)!!.use { it.readBytes() })
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    checkBackgroundAudio(scenario, output)
                } finally { removeOutput(output) }
            }
        }
    }

    @Test fun cancellationAndInsufficientSpaceDoNotPublishOrModifySource() = runBlocking {
        withVideo { input, original ->
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
                scenario.onActivity { assertTrue(app.converter.start(input.copy(bytes = Long.MAX_VALUE), 320)) }
                val storage = terminal()
                assertNull(storage.output)
                assertTrue(storage.stage.contains("storage", true))
                scenario.onActivity {
                    assertTrue(app.converter.start(input, 320))
                    app.converter.cancel()
                }
                val cancelled = terminal()
                assertNull(cancelled.output)
                assertEquals("Conversion cancelled", cancelled.stage)
                assertTrue(app.converter.directory.list().isNullOrEmpty())
                assertArrayEquals(original, app.contentResolver.openInputStream(input.uri)!!.use { it.readBytes() })
            }
        }
    }

    private suspend fun checkBackgroundAudio(scenario: ActivityScenario<MainActivity>, audio: Uri) {
        val token = SessionToken(app, ComponentName(app, LibraryAudioService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        val controller = future.get(20, TimeUnit.SECONDS)
        try {
            scenario.onActivity {
                controller.setMediaItem(MediaItem.Builder().setUri(audio).setMediaId(audio.toString()).build())
                controller.prepare()
                controller.play()
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            var position = 0L
            withTimeout(20_000) {
                while (position < 500) {
                    instrumentation.runOnMainSync { position = controller.currentPosition }
                    delay(100)
                }
            }
            instrumentation.runOnMainSync { controller.stop(); controller.clearMediaItems() }
        } finally {
            instrumentation.runOnMainSync { MediaController.releaseFuture(future) }
        }
    }

    private suspend fun withVideo(block: suspend (ConversionInput, ByteArray) -> Unit) {
        val title = "SAOREO test ${UUID.randomUUID()}"
        val file = File.createTempFile("source", ".mp4", app.cacheDir)
        val bytes = instrumentation.context.assets.open("playback-fixture.mp4").use { it.readBytes() }
        file.writeBytes(bytes)
        var source: Uri? = null
        try {
            source = publishMedia(app, file, title, "video/mp4") {}
            block(inspectVideo(app, source, title, imported = true), bytes)
        } finally {
            source?.let { app.contentResolver.delete(it, null, null) }
            app.dao.observe().first().filter { it.source == Source.CONVERTED.name && it.title == title }
                .forEach { app.dao.remove(it.id) }
            file.delete()
        }
    }

    private suspend fun terminal(): ConversionStatus = withTimeout(60_000) {
        app.converter.status.first { it?.active == false }!!
    }

    private suspend fun removeOutput(output: Uri) {
        app.contentResolver.delete(output, null, null)
        app.dao.observe().first().find { it.galleryUri == output.toString() }?.let { app.dao.remove(it.id) }
    }
}
