package com.codetic.saoreo

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairedChatInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun mediaRepliesJumpToOriginalAndSelectionKeepsMessagePosition() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val databaseName = "chat-interaction-${UUID.randomUUID()}.sqlite"
        val key = ByteArray(32) { it.toByte() }
        val chat = PairedChat(app, databaseName) { _, _, _ -> key }
        val pairingPreferences = app.getSharedPreferences("together-pairing", Application.MODE_PRIVATE)
        val previousName = pairingPreferences.getString("partner", null)
        pairingPreferences.edit().putString("partner", "Zack").apply()
        val pairing = TogetherPairing(app, chat)
        pairingPreferences.edit().putString("partner", previousName).apply()
        val peer = ChatPeer(UUID.randomUUID().toString().replace("-", ""), "a".repeat(64), "b".repeat(64))
        val album = "5".repeat(32)
        val thumbnail = ByteArrayOutputStream().use { output ->
            val bitmap = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.BLUE)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)
            bitmap.recycle()
            Base64.getEncoder().encodeToString(output.toByteArray())
        }
        val video = ChatAttachment("1".repeat(32), "VID-1203.mp4", "video/mp4", 1000, "d".repeat(64),
            false, 28_000, emptyList(), thumbnail)
        val photo = video.copy(id = "2".repeat(32), name = "IMG-9346.jpg", mime = "image/jpeg", duration = 0)
        val voice = video.copy(id = "3".repeat(32), name = "recording.m4a", mime = "audio/mp4",
            voice = true, duration = 12_000, waveform = listOf(10, 45, 90, 20, 55, 15), thumbnail = null)
        val now = System.currentTimeMillis() - 10_000
        suspend fun incoming(id: String, body: String, offset: Long, attachment: ChatAttachment? = null) {
            val payload = JSONObject().put("id", id).put("text", body).putOpt("attachment", attachment?.json())
            chat.receiveLive(peer, JSONObject().put("sender", peer.partner).put("id", id).put("queued", now + offset)
                .put("envelope", encryptChatEnvelope(key, payload.toString())))
        }

        try {
            runBlocking {
                chat.activate(peer)
                incoming(video.id, "Original video caption", 0, video)
                repeat(80) { index -> incoming((100 + index).toString(16).padStart(32, '0'), "Earlier message ${index + 1}", 1L + index) }
                incoming(photo.id, "Original album caption", 90, photo.copy(album = album))
                val secondPhoto = photo.copy(id = "4".repeat(32), album = album)
                incoming(secondPhoto.id, secondPhoto.name, 91, secondPhoto)
                incoming(voice.id, voice.name, 92, voice)
                for ((body, item) in listOf("Video reply" to video, "Photo reply" to secondPhoto, "Voice reply" to voice)) {
                    assertTrue(chat.enqueue(peer, body, ChatReference(item.id, false, item.name)))
                }
                assertTrue(chat.messages.value.none { it.id == video.id })
            }
            val selection = ChatSelectionState()
            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme {
                        Column(Modifier.fillMaxSize()) {
                            PartnerChatHeader(PairState(partner = "Zack"), {}, {}, {}, {}, false, selection = selection)
                            PairedChatThread(pairing, Modifier.weight(1f), headerSelection = selection)
                        }
                    }
                }
            }
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(55)
            val middle = compose.onNodeWithText("Earlier message 63", useUnmergedTree = true)
            middle.assertIsDisplayed()
            val middleBounds = middle.fetchSemanticsNode().boundsInRoot
            middle.performTouchInput { longClick() }
            compose.onNodeWithText("1 selected").assertIsDisplayed()
            assertEquals(middleBounds, middle.fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithContentDescription("Clear selection").performClick()
            assertEquals(middleBounds, middle.fetchSemanticsNode().boundsInRoot)

            compose.onNodeWithContentDescription("Jump to latest").performClick()
            compose.onNodeWithContentDescription("Video thumbnail", useUnmergedTree = true).assertIsDisplayed()
            val renderedPhoto = compose.onNodeWithContentDescription("Photo thumbnail", useUnmergedTree = true)
                .captureToImage().toPixelMap()
            val thumbnailCenter = renderedPhoto[renderedPhoto.width / 2, renderedPhoto.height / 2]
            assertTrue(thumbnailCenter.blue > .8f && thumbnailCenter.red < .2f)
            compose.onNodeWithContentDescription("Voice note waveform", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("Video · 0:28", useUnmergedTree = true).assertIsDisplayed()
            compose.onNode(hasText("Zack") and hasText("Video · 0:28")).assertIsDisplayed()
            compose.onNode(hasText("0:12") and hasContentDescription("Voice note waveform")).assertIsDisplayed()
            compose.onNodeWithText(video.name).assertDoesNotExist()

            val reply = compose.onNodeWithText("Video reply", useUnmergedTree = true)
            val initialBounds = reply.fetchSemanticsNode().boundsInRoot
            reply.performTouchInput { longClick() }
            compose.onNodeWithText("1 selected").assertIsDisplayed()
            compose.onNodeWithContentDescription("Audio call").assertDoesNotExist()
            compose.onNode(isSelected() and hasAnyDescendant(hasText("Video reply")), useUnmergedTree = true)
                .assertIsDisplayed()
            assertEquals(initialBounds, reply.fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithContentDescription("Clear selection").performClick()
            assertEquals(initialBounds, reply.fetchSemanticsNode().boundsInRoot)

            compose.onNodeWithText("Photo", useUnmergedTree = true).performTouchInput { click() }
            compose.onNodeWithText("Original album caption").assertIsDisplayed()
            compose.onNodeWithText("Video · 0:28", useUnmergedTree = true).performTouchInput { click() }
            compose.onNodeWithText("Original video caption", useUnmergedTree = true).assertIsDisplayed()
            assertTrue(chat.messages.value.any { it.id == video.id })
        } finally {
            runBlocking { chat.activate(null) }
            app.deleteDatabase(databaseName)
        }
    }
}
