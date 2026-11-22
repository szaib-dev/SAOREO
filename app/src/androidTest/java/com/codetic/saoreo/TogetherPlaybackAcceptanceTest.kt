package com.codetic.saoreo

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Media3 decoding under the production synchronization driver, with remote controls. */
@RunWith(AndroidJUnit4::class)
class TogetherPlaybackAcceptanceTest {
    @Test fun remoteCommandsControlRealVideoPlayback() = runBlocking<Unit> {
        val key = InstrumentationRegistry.getArguments().getString("togetherKey").orEmpty()
        assumeTrue(key.isNotBlank())
        val app = ApplicationProvider.getApplicationContext<SaoreoApp>()
        val activity = ActivityScenario.launch(TogetherActivity::class.java)
        lateinit var a: TogetherSession
        lateinit var b: TogetherSession
        lateinit var playback: TogetherPlayback
        withContext(Dispatchers.Main) { a = TogetherSession(app); b = TogetherSession(app); playback = TogetherPlayback(a) }
        try {
            withContext(Dispatchers.Main) { a.enter("https://szaibdev.duckdns.org:8765", "Video test A", key = key) }
            withTimeout(30000) { a.state.first { it.connected } }
            withContext(Dispatchers.Main) { b.enter("https://szaibdev.duckdns.org:8765", "Video test B", invite = a.state.value.invite) }
            withTimeout(30000) { b.state.first { it.connected } }
            activity.onActivity { host -> host.setContent {
                val room by a.state.collectAsState()
                AppTheme(Settings()) { Surface { Column { room.media?.let {
                    TogetherVideo(LocalContext.current, room, a, playback, null, Modifier.fillMaxWidth().aspectRatio(16f/9f))
                    Text(if (room.timeline.playing) "Playing together" else "Paused together")
                } } } }
            } }
            withContext(Dispatchers.Main) { a.setMedia(TogetherMedia("", "stream", "https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4", "Big Buck Bunny - playback acceptance", 0, false)) }
            withTimeout(30000) { a.state.first { it.media != null }; b.state.first { it.media != null } }
            withContext(Dispatchers.Main) { b.status(true) }
            withTimeout(45000) { while (true) {
                if (withContext(Dispatchers.Main) { playback.ready }) break
                delay(100)
            } }
            withContext(Dispatchers.Main) { b.playback("play") }
            withTimeout(20000) { while (true) {
                if (withContext(Dispatchers.Main) { playback.position > 1200 }) break
                delay(100)
            } }
            withContext(Dispatchers.Main) { b.playback("pause") }
            withTimeout(10000) { a.state.first { !it.wanted } }
            delay(2000)
            val position = withContext(Dispatchers.Main) { playback.position }
            delay(1200)
            withContext(Dispatchers.Main) { assertEquals(position.toDouble(), playback.position.toDouble(), 200.0); b.playback("seek", 10000) }
            withTimeout(15000) { while (true) {
                if (withContext(Dispatchers.Main) { kotlin.math.abs(playback.position - 10000) < 1200 }) break
                delay(100)
            } }
            withContext(Dispatchers.Main) { assertNull(playback.error) }
            android.util.Log.i("SAOREO_TEST", "PASS actual Media3 playback, partner pause, shared seek")
        } finally {
            withContext(Dispatchers.Main) { a.leave(); b.leave() }
            activity.close()
        }
    }
}
