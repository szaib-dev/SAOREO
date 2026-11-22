package com.codetic.saoreo

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AppUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<SaoreoApp>()

    @Test fun compactInputAndLibraryClassificationInBothThemes() {
        val item = Download(UUID.randomUUID().toString(), "content://test/imported", Source.IMPORTED.name,
            "Imported UI fixture", state = "COMPLETE", mime = "audio/wav")
        runBlocking { app.dao.insert(item) }
        try {
            for (theme in listOf("Dark", "Light")) {
                runBlocking { app.preferences.set(SettingKeys.theme, theme) }
                compose.onNode(hasText("Downloads") and hasClickAction()).performClick()
                compose.onNodeWithText("Paste a video or music link...").assertIsDisplayed()
                compose.onNodeWithContentDescription("Paste link").assertIsDisplayed()
                compose.onNodeWithContentDescription("Download link").assertIsDisplayed()
                compose.onNodeWithText("Imported UI fixture").assertDoesNotExist()
                compose.onNodeWithText("New download").assertDoesNotExist()
                screenshot("downloads-${theme.lowercase()}")
                compose.onNodeWithText("Paste a video or music link...").performTextInput("https://example.com/unsupported")
                compose.onNodeWithContentDescription("Download link").performClick()
                compose.onNodeWithText("Enter a supported video or music URL.").assertIsDisplayed()
                compose.onNode(hasText("Music") and hasClickAction()).performClick()
                compose.onNodeWithText("Imported UI fixture").assertExists()
                screenshot("music-${theme.lowercase()}")
            }
        } finally {
            runBlocking { app.dao.remove(item.id); app.preferences.set(SettingKeys.theme, "Dark") }
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val target = File(app.externalCacheDir, "test-screenshots/$name.png")
        target.parentFile?.mkdirs()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
