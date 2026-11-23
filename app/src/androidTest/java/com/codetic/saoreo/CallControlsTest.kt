package com.codetic.saoreo

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class CallControlsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun controlsHideAndScreenSharingLivesInOptions() {
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
            compose.onNodeWithContentDescription("Share screen").assertDoesNotExist()
            compose.onNodeWithContentDescription("Call options").performClick()
            compose.onNodeWithText("Share screen").assertExists()
            compose.onNodeWithText("Turn torch on").assertExists()
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
