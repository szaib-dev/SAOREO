package com.codetic.saoreo

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class CallControlsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun callPanelsKeepControlsAndMoveSelfViewToOptions() {
        lateinit var session: TogetherSession
        lateinit var call: TogetherCall
        compose.runOnUiThread {
            session = TogetherSession(compose.activity.application as SaoreoApp)
            call = session.callController
            compose.activity.setContent {
                AppTheme(Settings()) {
                    TogetherCallView(TogetherState(room = "controls-test"),
                        TogetherCallState(active = true, camera = true, hasTorch = true), call,
                        "", {}, {}, false, {}, {}, {}, {})
                }
            }
        }
        try {
            compose.onNodeWithContentDescription("Switch camera").assertExists()
            compose.onNodeWithContentDescription("Hide self view").assertDoesNotExist()
            compose.onNodeWithText("Share screen").assertDoesNotExist()
            compose.onNodeWithText("Stream").assertDoesNotExist()
            compose.onNodeWithContentDescription("Call options").performClick()
            compose.onNodeWithText("Stream").assertIsDisplayed()
            compose.onNodeWithText("Share screen").assertIsDisplayed()
            compose.onNodeWithText("Flashlight").assertIsDisplayed()
            compose.onNodeWithText("Hide self view").assertIsOff().performClick().assertIsOn()
            compose.onNodeWithText("Audio devices").performClick()
            compose.onNodeWithText("Output").assertIsDisplayed()
            compose.onNodeWithText("Microphone").assertIsDisplayed()
            compose.onNodeWithContentDescription("Close audio devices").performClick()
            compose.onNodeWithText("Microphone").assertDoesNotExist()
            compose.onNodeWithContentDescription("Call options").performClick()
            compose.onNodeWithText("Hide controls").performClick()
            compose.onNodeWithContentDescription("Call options").assertDoesNotExist()
            compose.onRoot().performTouchInput { click(center) }
            compose.mainClock.advanceTimeBy(4500)
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Call options").assertDoesNotExist()
            compose.onRoot().performTouchInput { click(center) }
            compose.onNodeWithContentDescription("Call options").assertExists()
        } finally { compose.runOnUiThread { call.close(); session.leave() } }
    }
}
