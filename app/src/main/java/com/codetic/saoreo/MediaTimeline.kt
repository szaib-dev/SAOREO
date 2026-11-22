package com.codetic.saoreo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaTimeline(position: Long, duration: Long, enabled: Boolean, modifier: Modifier = Modifier.fillMaxWidth(),
    onInteract: () -> Unit = {}, onSeek: (Long) -> Unit) {
    var dragged by remember { mutableStateOf<Float?>(null) }
    val progress = dragged ?: if (duration > 0) (position.toDouble() / duration).toFloat().coerceIn(0f, 1f) else 0f
    val foreground = LocalContentColor.current
    val track = foreground.copy(alpha = .23f)
    val active = if (enabled) Lime else foreground.copy(alpha = .62f)
    Column(modifier) {
        Slider(progress, { dragged = it; onInteract() }, Modifier.fillMaxWidth().height(30.dp)
            .semantics { contentDescription = "Playback position" },
            enabled = enabled && duration > 0,
            onValueChangeFinished = {
                val target = dragged
                dragged = null
                if (enabled && target != null) onSeek((target.toDouble() * duration).toLong())
                onInteract()
            },
            thumb = { Box(Modifier.size(if (dragged != null) 12.dp else 8.dp).background(active, CircleShape)) },
            track = {
                Canvas(Modifier.fillMaxWidth().height(3.dp)) {
                    val start = Offset(0f, center.y)
                    drawLine(track, start, Offset(size.width, center.y), size.height, StrokeCap.Round)
                    if (progress > 0) drawLine(active, start, Offset(size.width * progress, center.y), size.height, StrokeCap.Round)
                }
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(playbackTime(dragged?.let { (it.toDouble() * duration).toLong() } ?: position),
                style = MaterialTheme.typography.labelSmall, color = foreground.copy(alpha = .82f))
            Text(if (duration > 0) playbackTime(duration) else "–:––",
                style = MaterialTheme.typography.labelSmall, color = foreground.copy(alpha = .82f))
        }
    }
}
