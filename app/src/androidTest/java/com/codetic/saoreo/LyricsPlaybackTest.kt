package com.codetic.saoreo

import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class LyricsPlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun lyricTapsSeekRealPlaybackAndPreservePause() {
        val activity = compose.activity
        val app = activity.application as SaoreoApp
        val file = File(app.cacheDir, "lyrics-playback-test.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("playback-fixture.mp4").use { input ->
            file.outputStream().use(input::copyTo)
        }
        lateinit var player: ExoPlayer
        val text = (0..30).joinToString("\n") { index -> "[00:${index.toString().padStart(2, '0')}.00]Playback lyric $index" }
        val song = Download("lyrics-ui-test", "local-test", "IMPORTED", "Playback test", lyrics = text)
        compose.runOnUiThread {
            player = ExoPlayer.Builder(app).build()
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.prepare()
            activity.setContent { AppTheme(Settings()) { LyricsSheet(app, player, song, {}, {}, null) } }
        }
        try {
            compose.waitUntil(15000) { compose.runOnIdle { player.playbackState == Player.STATE_READY } }
            compose.onNodeWithText("Playback lyric 1").performClick()
            compose.runOnIdle { assertEquals(1000L, player.currentPosition); assertFalse(player.playWhenReady) }
            compose.onNodeWithContentDescription("Play", useUnmergedTree = true).performClick()
            compose.waitUntil(10000) { compose.runOnIdle { player.isPlaying } }
            compose.onNodeWithText("Playback lyric 2").performClick()
            compose.runOnIdle { assertTrue(player.playWhenReady); assertTrue(player.currentPosition in 2000L..3000L) }
            compose.onNodeWithContentDescription("Pause", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Playback lyric 0").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(0L, player.currentPosition); assertFalse(player.playWhenReady) }
            compose.onNodeWithContentDescription("Expand lyrics").performClick()
            compose.onNodeWithContentDescription("Collapse lyrics").assertExists()
            compose.onNode(hasScrollAction()).performTouchInput { swipeUp() }
            compose.onNodeWithText("Resume Live").assertExists().performClick()
            compose.onNodeWithText("Resume Live").assertDoesNotExist()
            compose.runOnIdle { player.setPlaybackSpeed(1.5f); player.repeatMode = Player.REPEAT_MODE_ONE }
            compose.onNodeWithText("Playback lyric 1").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(1000L, player.currentPosition); assertEquals(1.5f, player.playbackParameters.speed) }
        } finally {
            compose.runOnUiThread { player.release() }
            file.delete()
        }
    }
}
