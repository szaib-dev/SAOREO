package com.codetic.saoreo

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChatVoiceGestureTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun draggingUpLocksAndDraggingRightDiscardsWithoutSending() {
        val stage = mutableStateOf(VoiceStage.Idle)
        var discards = 0
        var sends = 0
        fun discard() { discards++; stage.value = VoiceStage.Idle }
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                MaterialTheme {
                    Box(Modifier.fillMaxSize()) {
                        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(end = 36.dp, bottom = 75.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                if (stage.value != VoiceStage.Idle) ChatRecordingBar(stage.value, 3_000,
                                    List(100) { it * 200 }, ::discard, Modifier.fillMaxWidth())
                            }
                            ChatVoiceControls(stage.value, enabled = true,
                                press = { stage.value = VoiceStage.Held; true },
                                release = { sends++ },
                                cancelHold = { if (stage.value == VoiceStage.Held) discard() },
                                lock = { stage.value = VoiceStage.Locked },
                                accessibleStart = { stage.value = VoiceStage.Locked },
                                send = { sends++ })
                        }
                    }
                }
            }
        }
        val mic = compose.onNodeWithContentDescription("Record voice note")
        mic.performTouchInput {
            down(center)
            moveBy(Offset(0f, -80.dp.toPx()))
            up()
        }
        compose.onNodeWithContentDescription("Recording locked").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send voice note").assertIsDisplayed()
        val waveform = compose.onNodeWithContentDescription("Recording waveform").fetchSemanticsNode().boundsInRoot
        assertTrue(waveform.width > waveform.height * 4)
        compose.onNodeWithContentDescription("Stop recording").assertDoesNotExist()
        compose.onNodeWithContentDescription("Delete recording").performClick()
        mic.performTouchInput {
            down(center)
            moveBy(Offset(30.dp.toPx(), 0f))
            up()
        }
        mic.assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(2, discards)
            assertEquals(0, sends)
        }
    }
}
