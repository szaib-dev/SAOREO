package com.codetic.saoreo

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatCameraMediaTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun cameraCanSelectMultipleGalleryItemsAndCapturePhotoAndVideo() {
        val resolver = compose.activity.contentResolver
        val seeded = listOf(Color.RED, Color.BLUE).mapIndexed { index, color ->
            val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "saoreo-test-${System.nanoTime()}-$index.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SAOREO Tests")
            }))
            val image = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            try {
                image.eraseColor(color)
                checkNotNull(resolver.openOutputStream(uri)).use { check(image.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            } finally { image.recycle() }
            uri
        }
        val picked = AtomicReference<List<Uri>>(emptyList())
        val captured = mutableListOf<Uri>()
        fun waitForAction(label: String) {
            compose.waitUntil(20_000) {
                compose.onAllNodes(hasContentDescription(label) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
        }
        try {
            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent { MaterialTheme { TogetherChatCamera({}, {}, picked::set) } }
            }
            waitForAction("Take photo")
            compose.onNodeWithContentDescription("Gallery").performClick()
            val recents = compose.onAllNodesWithContentDescription("Recent photo")
            compose.waitUntil(10_000) { recents.fetchSemanticsNodes().size >= 2 }
            recents[0].performClick()
            recents[1].performClick()
            compose.onNodeWithText("2 selected").assertIsDisplayed()
            compose.onNodeWithText("Next").performClick()
            assertEquals(seeded.toSet(), picked.get().toSet())
            compose.onNodeWithContentDescription("Back to camera").performClick()

            picked.set(emptyList())
            compose.onNodeWithContentDescription("Take photo").performClick()
            compose.waitUntil(12_000) { picked.get().isNotEmpty() }
            val photo = picked.get().single().also(captured::add)
            assertEquals("image/jpeg", resolver.getType(photo))
            assertTrue(resolver.openAssetFileDescriptor(photo, "r")!!.use { it.length } > 0)

            picked.set(emptyList())
            compose.onNodeWithContentDescription("Record video").performClick()
            waitForAction("Stop recording")
            compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("0:01")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Stop recording").performClick()
            compose.waitUntil(20_000) { picked.get().isNotEmpty() }
            val clip = picked.get().single().also(captured::add)
            assertEquals("video/mp4", resolver.getType(clip))
            assertTrue(MediaMetadataRetriever().use {
                it.setDataSource(compose.activity, clip)
                it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } >= 500)

            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent { MaterialTheme { ChatViewerVideo(clip, true, {}, {}) } }
            }
            compose.onNodeWithContentDescription("Play video").assertIsDisplayed().performClick()
            waitForAction("Pause video")
            compose.onNodeWithContentDescription("Stop video").assertDoesNotExist()
            compose.onNodeWithContentDescription("Pause video").performClick()
            compose.onNodeWithContentDescription("Play video").assertIsDisplayed()
        } finally {
            compose.activityRule.scenario.onActivity { it.setContent {} }
            seeded.forEach { resolver.delete(it, null, null) }
            captured.forEach { resolver.delete(it, null, null) }
        }
    }
}
