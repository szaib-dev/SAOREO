package com.codetic.saoreo

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private const val PreviewDurationMs = 5_000L

@Composable
internal fun rememberCallChatPreviews(room: TogetherState, enabled: Boolean, serverNow: () -> Long): List<TogetherChat> {
    var previews by remember(room.room) { mutableStateOf(emptyList<TogetherChat>()) }
    LaunchedEffect(room.chat, room.incomingChat?.id, enabled, room.room) {
        val incomingId = room.incomingChat?.id
        val latest = if (enabled && incomingId != null) {
            val index = room.chat.indexOfLast { it.id == incomingId }
            room.chat.take(index + 1).filter { it.sender != room.member }.takeLast(3)
        } else emptyList()
        while (true) {
            val now = serverNow()
            previews = latest.filter { (now - it.time).coerceAtLeast(0) < PreviewDurationMs }
            val oldest = previews.minOfOrNull { it.time } ?: break
            delay(PreviewDurationMs - (now - oldest).coerceAtLeast(0))
        }
    }
    return if (enabled) previews else emptyList()
}

@Composable
internal fun TogetherChatPreviewStack(messages: List<TogetherChat>, modifier: Modifier = Modifier) {
    Column(modifier.widthIn(max = 420.dp).animateContentSize(),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (message in messages) key(message.id) {
            Surface(
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                shape = RoundedCornerShape(16.dp),
                color = Color(0xCC211E2A),
                contentColor = Color.White,
                border = BorderStroke(1.dp, Color.White.copy(alpha = .16f)),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text(message.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(message.text, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
