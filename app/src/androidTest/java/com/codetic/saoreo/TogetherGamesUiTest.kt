package com.codetic.saoreo

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TogetherGamesUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun aimingAndFiringPassTheChosenDirectionToTheRoom() {
        val state = JSONObject("""{"id":"ui-fixture","turnNumber":0,"turn":0,"phase":"active","board":{"round":0,"wind":1.17}}""")
        var action: String? = null
        var shot: JSONObject? = null
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                MaterialTheme {
                    GamePlay(TogetherGame.ARCHERY, state, null, 0, true, { type, value ->
                        if (type == "shoot") {
                            action = type
                            shot = value
                        }
                        true
                    }, { 0L }, Modifier.fillMaxSize().safeDrawingPadding(), {})
                }
            }
        }
        compose.onNodeWithContentDescription("Archery Duel playing field").assertIsDisplayed().performTouchInput { swipeRight() }
        compose.onNodeWithText("Release").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("shoot", action)
            assertTrue(requireNotNull(shot).getDouble("x") >= 70)
            assertTrue(requireNotNull(shot).getDouble("power") > 0)
        }
    }
}
