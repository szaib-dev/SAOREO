package com.codetic.saoreo

import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class TogetherChatPreviewTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun incomingMessagesStackAndHideWhenChatOpens() {
        val now = SystemClock.elapsedRealtime()
        val first = TogetherChat("one", "peer", "Partner", "First incoming message", now)
        val second = first.copy(id = "two", text = "Second incoming message")
        val sent = first.copy(id = "mine", sender = "me", text = "My message")
        val old = first.copy(id = "old", text = "An old message", time = now - 10_000)
        val room = mutableStateOf(TogetherState(room = "test", member = "me", chat = listOf(old)))
        val chatOpen = mutableStateOf(false)
        compose.runOnUiThread {
            compose.activity.setContent {
                AppTheme(Settings()) {
                    TogetherChatPreviewStack(rememberCallChatPreviews(room.value, !chatOpen.value, SystemClock::elapsedRealtime))
                }
            }
        }
        compose.onNodeWithText(old.text).assertDoesNotExist()
        compose.runOnIdle { room.value = room.value.copy(chat = listOf(old, first, sent, second), incomingChat = second) }
        compose.onNodeWithText(first.text).assertExists()
        compose.onNodeWithText(second.text).assertExists()
        compose.onNodeWithText(sent.text).assertDoesNotExist()
        compose.onNodeWithText(old.text).assertDoesNotExist()
        compose.runOnIdle { chatOpen.value = true }
        compose.onNodeWithText(first.text).assertDoesNotExist()
        compose.onNodeWithText(second.text).assertDoesNotExist()
    }
}
