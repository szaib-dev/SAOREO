package com.codetic.saoreo

import android.Manifest
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.VideoSink
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in: two native peers, real room server, forced TURN path and received camera frames. */
@RunWith(AndroidJUnit4::class)
class TogetherAcceptanceTest {
    @Test fun privateRoomSynchronizationChatAndNativeVideoCall() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val key = args.getString("togetherKey").orEmpty()
        assumeTrue("Provide private test deployment access through instrumentation arguments", key.isNotBlank())
        val app = ApplicationProvider.getApplicationContext<SaoreoApp>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (permission in listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA, Manifest.permission.BLUETOOTH_CONNECT)) {
            instrumentation.uiAutomation.grantRuntimePermission(app.packageName, permission)
        }
        val activity = ActivityScenario.launch(TogetherActivity::class.java)
        lateinit var a: TogetherSession
        lateinit var b: TogetherSession
        lateinit var ca: TogetherCall
        lateinit var cb: TogetherCall
        val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var sessionsReady = false
        var callsReady = false
        var metrics: Job? = null
        var left: Job? = null
        var right: Job? = null
        val aKey = "acceptance-call-a-${java.util.UUID.randomUUID()}"
        val bKey = "acceptance-call-b-${java.util.UUID.randomUUID()}"
        try {
            withContext(Dispatchers.Main) {
                a = TogetherSession(app); b = TogetherSession(app); sessionsReady = true
                a.enter("https://szaibdev.duckdns.org:8765", "Android test A", key = key)
            }
            withTimeout(25000) { a.state.first { it.connected } }; android.util.Log.i("SAOREO_TEST", "Creator connected")
            withContext(Dispatchers.Main) { b.enter("https://szaibdev.duckdns.org:8765", "Android test B", invite = a.inviteLink()) }
            withTimeout(25000) { a.state.first { it.members.count { m -> m.online } == 2 }; b.state.first { it.connected } }
            android.util.Log.i("SAOREO_TEST", "Both connected")
            if (args.getString("callOnly") != "true") {
            withContext(Dispatchers.Main) { a.setMedia(TogetherMedia("", "youtube", "aqz-KE-bpKQ", "Android acceptance", 0, false)) }
            withTimeout(10000) { a.state.first { it.media != null }; b.state.first { it.media != null } }; android.util.Log.i("SAOREO_TEST", "Media acknowledged on both")
            withContext(Dispatchers.Main) { a.status(true); b.status(true) }
            withTimeout(10000) {
                a.state.first { it.canStartPlayback }
                b.state.first { it.canStartPlayback }
            }
            android.util.Log.i("SAOREO_TEST", "Both ready")
            withContext(Dispatchers.Main) { b.playback("play") }
            withTimeout(10000) { a.state.first { it.timeline.playing } }; android.util.Log.i("SAOREO_TEST", "Mutual play passed")
            withContext(Dispatchers.Main) { a.playback("seek", 45000); a.playback("pause") }
            val paused = withTimeout(10000) { b.state.first { !it.wanted && it.timeline.position >= 45000 } }
            assertFalse(paused.timeline.playing); android.util.Log.i("SAOREO_TEST", "Mutual seek/pause passed")
            }
            withContext(Dispatchers.Main) { a.send("chat", JSONObject().put("text", "Android partner message")) }
            withTimeout(10000) { b.state.first { it.chat.any { m -> m.text == "Android partner message" } } }
            android.util.Log.i("SAOREO_TEST", "Chat passed")
            withContext(Dispatchers.Main) {
                ca = TogetherCall(app, a, forceRelay = args.getString("directCall") != "true", identity = CallIdentity(app, aKey))
                cb = TogetherCall(app, b, forceRelay = args.getString("directCall") != "true", identity = CallIdentity(app, bKey))
                callsReady = true
                left = main.launch { a.state.collect { ca.members(it) } }
                right = main.launch { b.state.collect { cb.members(it) } }
                cb.start(false); ca.start(true)
                metrics = main.launch {
                    while (isActive) {
                        delay(5000)
                        ca.diagnostics { android.util.Log.i("SAOREO_TEST", "A: $it") }
                        cb.diagnostics { android.util.Log.i("SAOREO_TEST", "B: $it") }
                    }
                }
            }
            withTimeout(45000) { ca.state.first { it.connection == "Connected" }; cb.state.first { it.connection == "Connected" } }
            android.util.Log.i("SAOREO_TEST", "Native ICE connected on both")
            val track = withTimeout(10000) { cb.state.first { it.remoteVideo != null }.remoteVideo!! }
            val frames = AtomicInteger()
            val sink = VideoSink { frames.incrementAndGet() }
            track.addSink(sink)
            try {
                withTimeout(15000) { while (frames.get() < 5) delay(100) }
                if (args.getString("exerciseRecovery") == "true") {
                    val count = frames.get()
                    withContext(Dispatchers.Main) { ca.retryConnection() }
                    withTimeout(45_000) {
                        ca.state.first { it.connection == "Connected" }
                        cb.state.first { it.connection == "Connected" }
                        while (frames.get() < count + 30) delay(100)
                    }
                    assertSame(track, cb.state.value.remoteVideo)
                    android.util.Log.i("SAOREO_TEST", "PASS ICE restart retained the remote track and received new frames")
                }
                if (args.getString("exerciseOutage") == "true") {
                    fun airplane(enabled: Boolean) {
                        val mode = if (enabled) "enable" else "disable"
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(
                            instrumentation.uiAutomation.executeShellCommand("cmd connectivity airplane-mode $mode")).use { it.readBytes() }
                    }
                    val count = frames.get()
                    airplane(true)
                    try {
                        withTimeout(30_000) { ca.state.first { it.connection == "Reconnecting…" } }
                        delay(4_000)
                    } finally { airplane(false) }
                    withTimeout(60_000) {
                        ca.state.first { it.connection == "Connected" }
                        cb.state.first { it.connection == "Connected" }
                        while (frames.get() < count + 60) delay(100)
                    }
                    assertSame(track, cb.state.value.remoteVideo)
                    android.util.Log.i("SAOREO_TEST", "PASS temporary airplane-mode outage recovered on the existing remote track")
                }
                val watchMs = args.getString("holdCallMs")?.toLongOrNull()?.coerceIn(0, 180_000) ?: 0
                if (watchMs > 0) {
                    val count = frames.get()
                    delay(watchMs)
                    assertEquals("Connected", ca.state.value.connection)
                    assertEquals("Connected", cb.state.value.connection)
                    assertTrue("Continuous video must still be decoding", frames.get() > count)
                }
            } finally { track.removeSink(sink) }
            assertTrue("Native camera frames must arrive over forced TURN", frames.get() >= 5)
            withContext(Dispatchers.Main) {
                ca.microphone(false)
                assertFalse(ca.state.value.mic)
                ca.camera(false)
                assertFalse(ca.state.value.camera)
                a.send("reaction", JSONObject().put("emoji", "heart"))
            }
            withTimeout(10000) { b.state.first { it.reaction?.emoji == "heart" } }
        } finally {
            if (sessionsReady) android.util.Log.i("SAOREO_TEST", "Session diagnostics A=${a.state.value.error}, B=${b.state.value.error}, ready=${a.state.value.members.map { it.ready }}")
            if (callsReady) android.util.Log.i("SAOREO_TEST", "Call diagnostics A=${ca.state.value.connection}/${ca.state.value.error}, B=${cb.state.value.connection}/${cb.state.value.error}")
            withContext(Dispatchers.Main) {
                metrics?.cancel(); left?.cancel(); right?.cancel()
                if (callsReady) { ca.close(); cb.close() }
                if (sessionsReady) { a.leave(); b.leave() }
            }
            main.cancel(); activity.close()
            java.security.KeyStore.getInstance("AndroidKeyStore").apply {
                load(null); deleteEntry(aKey); deleteEntry(bKey)
            }
        }
    }
}
