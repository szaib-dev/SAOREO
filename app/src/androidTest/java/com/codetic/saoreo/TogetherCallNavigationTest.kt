package com.codetic.saoreo

import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Exercises the actual TogetherActivity, private room signaling and the production player. */
class TogetherCallNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<TogetherActivity>()

    @Test fun backAndStreamKeepTheSameCallAndPlayback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val key = InstrumentationRegistry.getArguments().getString("togetherKey").orEmpty()
        assumeTrue(key.isNotBlank())
        val identityName = "navigation-partner-" + java.util.UUID.randomUUID()
        lateinit var host: TogetherSession
        lateinit var partner: TogetherSession
        lateinit var partnerCall: TogetherCall
        lateinit var partnerEvents: Job
        compose.activityRule.scenario.onActivity { activity ->
            host = ViewModelProvider(activity)[TogetherSession::class.java]
            partner = TogetherSession(activity.application)
            partnerCall = TogetherCall(activity.application, partner, identity = CallIdentity(activity, identityName))
            partnerEvents = CoroutineScope(Dispatchers.Main.immediate).launch {
                partner.state.collect(partnerCall::members)
            }
            host.enter(TOGETHER_SERVER, "Navigation test", key = key)
        }
        try {
            runBlocking {
                withTimeout(30_000) { host.state.first { it.connected } }
                android.util.Log.i("SAOREO_TEST", "Navigation room created")
                withContext(Dispatchers.Main) { partner.enter(TOGETHER_SERVER, "Call partner", invite = host.inviteLink()) }
                withTimeout(30_000) { host.state.first { room -> room.members.count { it.online } == 2 } }
                withContext(Dispatchers.Main) { partnerCall.start(false) }
                withTimeout(20_000) { host.state.first { room -> room.members.any { it.id != room.member && it.call } } }
            }
            for (permission in listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA, Manifest.permission.BLUETOOTH_CONNECT)) {
                instrumentation.uiAutomation.grantRuntimePermission(compose.activity.packageName, permission)
            }
            compose.onNodeWithText("Voice").performClick()
            runBlocking {
                withTimeout(45_000) {
                    host.callController.state.first { it.connection == "Connected" }
                    partnerCall.state.first { it.connection == "Connected" }
                }
            }
            android.util.Log.i("SAOREO_TEST", "Navigation room call connected")
            val roomId = host.state.value.room
            val remoteTrack = host.callController.state.value.remoteVideo
            assertNotNull(remoteTrack)
            compose.onNodeWithContentDescription("Open full call").performClick()
            compose.onNodeWithText("Stream").performClick()
            compose.onNodeWithText("Choose what to watch").assertIsDisplayed()
            assertSame(remoteTrack, host.callController.state.value.remoteVideo)
            runBlocking {
                withContext(Dispatchers.Main) {
                    host.setMedia(TogetherMedia("", "stream",
                        "https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4",
                        "Together navigation movie", 0, false))
                }
                withTimeout(20_000) { partner.state.first { it.media != null } }
                withContext(Dispatchers.Main) { partner.status(true) }
            }
            compose.waitUntil(60_000) { host.state.value.canStartPlayback }
            runBlocking {
                withTimeout(15_000) { partner.state.first { it.canStartPlayback } }
                withContext(Dispatchers.Main) { assertTrue(partner.playback("play")) }
                withTimeout(20_000) { host.state.first { it.timeline.playing } }
            }
            val mediaId = host.state.value.media!!.id
            val originalPosition = host.state.value.timeline.position
            repeat(3) { round ->
                compose.onNodeWithContentDescription("Open full call").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithText("Stream").fetchSemanticsNodes().isNotEmpty() }
                android.util.Log.i("SAOREO_TEST", "Leaving fullscreen round ${round + 1}")
                if (round == 1) compose.onNodeWithText("Stream").performClick()
                else instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                try {
                    compose.waitUntil(10_000) {
                        compose.onAllNodesWithText("Together navigation movie").fetchSemanticsNodes().isNotEmpty()
                    }
                } catch (error: ComposeTimeoutException) {
                    compose.onRoot().printToLog("SAOREO_NAVIGATION")
                    throw error
                }
                assertEquals(roomId, host.state.value.room)
                assertEquals(mediaId, host.state.value.media?.id)
                assertTrue(host.state.value.timeline.playing)
                assertEquals("Connected", host.callController.state.value.connection)
                assertSame(remoteTrack, host.callController.state.value.remoteVideo)
            }
            assertTrue(host.state.value.timeline.target(host.serverNow()) > originalPosition)
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            runBlocking { delay(3500) }
            assertEquals("Connected", host.callController.state.value.connection)
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            assertSame(remoteTrack, host.callController.state.value.remoteVideo)
            assertTrue(host.state.value.timeline.playing)
        } catch (error: Throwable) {
            android.util.Log.e("SAOREO_TEST", "Navigation test: host=${host.state.value.error}, call=${host.callController.state.value.connection}, ready=${host.state.value.members.map { it.ready }}", error)
            throw error
        } finally {
            compose.activityRule.scenario.onActivity {
                host.callController.stop()
                partnerEvents.cancel()
                partnerCall.close()
                partner.leave()
                host.leave()
            }
            java.security.KeyStore.getInstance("AndroidKeyStore").apply {
                load(null); deleteEntry(identityName)
            }
        }
    }
}
