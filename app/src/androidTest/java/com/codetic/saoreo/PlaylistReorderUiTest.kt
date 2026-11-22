package com.codetic.saoreo

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistReorderUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun draggingTheFirstPlaylistDownSavesTheNewOrder() {
        val saved = listOf("First", "Second", "Third")
        var submitted: List<String>? = null
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                MaterialTheme {
                    val list = rememberLazyListState()
                    val reorder = remember(list) { PlaylistReorder(list) }
                    LazyColumn(Modifier.safeDrawingPadding(), state = list) {
                        items(reorder.draft ?: saved, key = { it }) { id ->
                            Row(Modifier.fillMaxWidth().height(64.dp).then(reorder.modifier(id)),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(id, Modifier.weight(1f))
                                PlaylistDragHandle(reorder, id, saved) { submitted = it }
                            }
                        }
                    }
                }
            }
        }
        compose.onAllNodesWithContentDescription("Drag to reorder")[0].performTouchInput {
            swipe(center, center + Offset(0f, 149.dp.toPx()), 420)
        }
        compose.runOnIdle { assertEquals(listOf("Second", "Third", "First"), submitted) }
    }
}
