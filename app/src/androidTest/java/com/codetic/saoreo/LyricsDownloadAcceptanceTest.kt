package com.codetic.saoreo

import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class LyricsDownloadAcceptanceTest {
    @Test fun automaticLyricsFollowCompletedYouTubePlaylistAudio() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("lyrics_live") == "true")
        val app = ApplicationProvider.getApplicationContext<SaoreoApp>()
        val playlist = app.collections.newPlaylist("Lyrics acceptance")
        val ids = mutableListOf<String>()
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                // Same queue and destination options used by the playlist download screen.
                for ((index, video) in listOf("dQw4w9WgXc", "fJ9rUzIMcZQ").withIndex()) {
                    val link = requireNotNull(MediaLink.parse("https://www.youtube.com/watch?v=$video"))
                    ids += app.downloads.enqueue(link, Settings(quality = Quality.AUDIO), null, null,
                        DownloadOptions(playlist = playlist.id, playlistPosition = System.currentTimeMillis() + index))
                }
                for (id in ids) {
                    val row = withTimeout(10 * 60_000) {
                        var value = app.dao.find(id)
                        while (value?.active != false) { delay(500); value = app.dao.find(id) }
                        requireNotNull(value)
                    }
                    assertEquals(row.error, JobState.COMPLETE.name, row.state)
                    assertTrue(row.bytes!! > 0)
                    val lyrics = withTimeout(90000) {
                        var value = app.lyrics.dao.find(id)
                        while (value == null) { delay(500); value = app.lyrics.dao.find(id) }
                        value
                    }
                    android.util.Log.i("SAOREO_LYRICS_TEST", "track=$id artist=${lyrics.artist} cached=${lyrics.text != null} synced=${lyrics.synced}")
                    if (id == ids.first()) assertTrue("Known song should have synchronized lyrics", lyrics.synced)
                    val before = app.lyrics.dao.find(id)
                    app.lyrics.fetch(id)
                    assertEquals("Cached result remains identical", before, app.lyrics.dao.find(id))
                }
            } finally {
                for (id in ids) app.dao.find(id)?.let { row ->
                    if (row.active) app.downloads.cancel(row)
                    app.downloads.remove(row, true)
                }
                app.collections.deletePlaylist(playlist.id)
            }
        }
    }
}
