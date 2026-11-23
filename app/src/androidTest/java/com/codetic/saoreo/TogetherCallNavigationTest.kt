package com.codetic.saoreo

import android.Manifest
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.core.content.FileProvider
import androidx.media3.datasource.DataSpec
import java.io.File
import java.nio.ByteBuffer
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

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun backAndStreamKeepTheSameCallAndPlayback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val key = InstrumentationRegistry.getArguments().getString("togetherKey").orEmpty()
        assumeTrue(key.isNotBlank())
        val identityName = "navigation-partner-" + java.util.UUID.randomUUID()
        lateinit var host: TogetherSession
        lateinit var partner: TogetherSession
        lateinit var partnerCall: TogetherCall
        lateinit var partnerEvents: Job
        var localVideo: File? = null
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
            compose.onNodeWithContentDescription("Open Games").assertDoesNotExist()
            compose.onNodeWithText("Stream").assertDoesNotExist()
            compose.onNodeWithContentDescription("Call options").performClick()
            compose.onNodeWithText("Stream").performClick()
            compose.onNodeWithText("Choose what to watch").assertIsDisplayed()
            assertSame(remoteTrack, host.callController.state.value.remoteVideo)
            val context = instrumentation.targetContext
            val fixture = File(File(context.cacheDir, "transcript-clipboard").apply { mkdirs() },
                "Together navigation movie.mp4")
            localVideo = fixture
            runBlocking {
                withContext(Dispatchers.IO) { makePlaybackFixture(instrumentation, fixture) }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.transcripts", fixture)
                val local = host.shareLocalVideo(context.contentResolver, uri)
                withContext(Dispatchers.Main) {
                    assertTrue(host.setMedia(local.copy(title = "Together navigation movie")))
                }
                val received = withTimeout(20_000) { partner.state.first { it.media != null }.media!! }
                withContext(Dispatchers.IO) {
                    val range = partner.uploadDataSource().createDataSource()
                    try {
                        val expected = fixture.inputStream().use { it.readNBytes(128) }
                        range.open(DataSpec.Builder().setUri(partner.sharedFileUrl(received.source)).setLength(128).build())
                        val actual = ByteArray(expected.size)
                        var total = 0
                        while (total < actual.size) {
                            val read = range.read(actual, total, actual.size - total)
                            check(read > 0) { "Shared video returned an incomplete range" }
                            total += read
                        }
                        assertArrayEquals(expected, actual)
                    } finally { range.close() }
                }
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
                compose.onNodeWithContentDescription("Call options").assertIsDisplayed()
                android.util.Log.i("SAOREO_TEST", "Leaving fullscreen round ${round + 1}")
                if (round == 1) {
                    compose.onNodeWithContentDescription("Call options").performClick()
                    compose.onNodeWithText("Stream").performClick()
                } else instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
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
            val activity = compose.activity
            instrumentation.runOnMainSync {
                activity.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NO_USER_ACTION))
            }
            runBlocking {
                withTimeout(15_000) {
                    while (withContext(Dispatchers.Main) { activity.hasWindowFocus() && !activity.isInPictureInPictureMode }) {
                        delay(100)
                    }
                }
                delay(3500)
            }
            assertEquals("Connected", host.callController.state.value.connection)
            shell(instrumentation, "input keyevent 4")
            runBlocking {
                withTimeout(15_000) {
                    while (withContext(Dispatchers.Main) { !activity.hasWindowFocus() || activity.isInPictureInPictureMode }) {
                        delay(100)
                    }
                }
            }
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
            localVideo?.delete()
            java.security.KeyStore.getInstance("AndroidKeyStore").apply {
                load(null); deleteEntry(identityName)
            }
        }
    }

    private fun shell(instrumentation: android.app.Instrumentation, command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).use { it.readBytes() }
    }

    private fun makePlaybackFixture(instrumentation: android.app.Instrumentation, output: File) {
        val extractor = MediaExtractor()
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            instrumentation.context.assets.openFd("playback-fixture.mp4").use { input ->
                extractor.setDataSource(input.fileDescriptor, input.startOffset, input.length)
                val tracks = (0 until extractor.trackCount).map { index ->
                    val format = extractor.getTrackFormat(index)
                    extractor.selectTrack(index)
                    muxer.addTrack(format) to format.getLong(MediaFormat.KEY_DURATION)
                }
                val durationUs = tracks.maxOf { it.second }
                val data = ByteBuffer.allocate(512 * 1024)
                val info = MediaCodec.BufferInfo()
                muxer.start()
                repeat(16) { pass ->
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    while (extractor.sampleTrackIndex >= 0) {
                        val track = tracks[extractor.sampleTrackIndex].first
                        val length = extractor.readSampleData(data, 0)
                        if (length < 0) break
                        info.set(0, length, pass * durationUs + extractor.sampleTime,
                            extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC)
                        muxer.writeSampleData(track, data, info)
                        extractor.advance()
                    }
                }
                muxer.stop()
            }
        } finally {
            extractor.release()
            muxer.release()
        }
    }
}
