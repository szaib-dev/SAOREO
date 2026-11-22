package com.codetic.saoreo

import android.content.ComponentName
import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PlayerSessionTest {
    private val app = ApplicationProvider.getApplicationContext<SaoreoApp>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun realAudioQueueSeekingSpeedAndRestoration() = runBlocking {
        val file = File(app.cacheDir, "session-fixture.wav")
        val bytes = 44100 * 2 * 4
        val buffer = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(44100).putInt(88200).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(bytes)
        repeat(bytes / 2) { buffer.putShort((kotlin.math.sin(it * 2 * Math.PI * 440 / 44100) * 3000).toInt().toShort()) }
        file.writeBytes(buffer.array())
        var uri: Uri? = null
        var controller: MediaController? = null
        try {
            uri = publishMedia(app, file, "SAOREO playback test", "audio/wav") {}
            val imported = app.collections.importAudio(requireNotNull(uri))
            assertEquals("IMPORTED", imported.origin)
            assertEquals(imported.id, app.collections.importAudio(requireNotNull(uri)).id)
            assertFalse(app.dao.observe().first().any { it.id == imported.id })
            lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
            instrumentation.runOnMainSync {
                future = MediaController.Builder(app, SessionToken(app, ComponentName(app, LibraryAudioService::class.java))).buildAsync()
            }
            val player = future.get(30, TimeUnit.SECONDS)
            controller = player
            instrumentation.runOnMainSync {
                player.setMediaItems(listOf(localAudioItem(uri.toString(), "First"),
                    localAudioItem(uri.toString() + "#second", "Second")))
                player.prepare(); player.play()
            }
            withTimeout(20_000) { while (true) {
                var playing = false
                instrumentation.runOnMainSync { playing = player.isPlaying && player.currentPosition > 100 }
                if (playing) break
                delay(100)
            } }
            instrumentation.runOnMainSync {
                player.pause(); player.seekTo(1200); player.setPlaybackSpeed(1.5f)
                player.shuffleModeEnabled = true; player.repeatMode = Player.REPEAT_MODE_ALL
                SavedMusicQueue(app, "queue-instrumentation-test").save(player)
                assertEquals(1.5f, player.playbackParameters.speed, .001f)
                player.seekForward(); assertTrue(player.currentPosition >= 1200)
                player.seekBack(); assertTrue(player.currentPosition >= 0)
                player.shuffleModeEnabled = false
                player.seekToNextMediaItem(); assertEquals(1, player.currentMediaItemIndex)
                player.seekToPreviousMediaItem(); assertEquals(0, player.currentMediaItemIndex)
                val restored = androidx.media3.exoplayer.ExoPlayer.Builder(app).build()
                try {
                    SavedMusicQueue(app, "queue-instrumentation-test").restore(restored)
                    assertEquals(2, restored.mediaItemCount)
                    assertEquals(1.5f, restored.playbackParameters.speed, .001f)
                    assertEquals(Player.REPEAT_MODE_ALL, restored.repeatMode)
                    assertTrue(restored.shuffleModeEnabled)
                } finally { restored.release() }
                app.audioControl.sleepUntil.value = SystemClockCompat.now() + 300
                player.play()
            }
            delay(5000)
            instrumentation.runOnMainSync { assertFalse(player.playWhenReady); assertEquals(0L, app.audioControl.sleepUntil.value) }
            app.dao.remove(imported.id)
        } finally {
            instrumentation.runOnMainSync { controller?.pause(); controller?.clearMediaItems(); controller?.release() }
            uri?.let { app.contentResolver.delete(it, null, null) }
            file.delete()
        }
    }
}

private object SystemClockCompat { fun now() = android.os.SystemClock.elapsedRealtime() }
