package com.codetic.saoreo

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.sin

@Composable
internal fun SourceMark(source: Source, modifier: Modifier = Modifier.size(18.dp)) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.semantics { contentDescription = source.label }) {
        val w = size.width
        val h = size.height
        if (source == Source.YOUTUBE) {
            drawRoundRect(Color(0xffff0033), Offset(0f, h * .17f), Size(w, h * .66f), CornerRadius(h * .2f))
            drawPath(Path().apply { moveTo(w * .42f, h * .33f); lineTo(w * .42f, h * .67f); lineTo(w * .7f, h * .5f); close() }, Color.White)
        } else if (source == Source.INSTAGRAM) {
            drawRoundRect(Color(0xffd94382), Offset(w * .08f, h * .08f), Size(w * .84f, h * .84f), CornerRadius(w * .25f), style = Stroke(w * .09f))
            drawCircle(Color(0xffd94382), w * .2f, style = Stroke(w * .09f))
            drawCircle(Color(0xffd94382), w * .06f, Offset(w * .76f, h * .25f))
        } else {
            drawRoundRect(neutral.copy(alpha = .16f), cornerRadius = CornerRadius(w * .25f))
            drawPath(Path().apply { moveTo(w * .38f, h * .25f); lineTo(w * .38f, h * .75f); lineTo(w * .75f, h * .5f); close() }, neutral)
        }
    }
}

@Composable
internal fun PlayingBars(playing: Boolean, modifier: Modifier = Modifier.size(20.dp, 14.dp)) {
    val phase = if (playing) {
        val transition = rememberInfiniteTransition(label = "Playing indicator")
        val value by transition.animateFloat(0f, 6.283f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "Equalizer bars")
        value
    } else 0f
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.semantics { contentDescription = if (playing) "Playing" else "Paused" }) {
        for (i in 0..3) {
            val height = if (playing) size.height * (.25f + .75f * ((sin(phase + i * 1.7f) + 1) / 2)) else size.height * .25f
            drawRoundRect(color, Offset(size.width * i / 4, (size.height - height) / 2), Size(size.width * .14f, height), CornerRadius(2.dp.toPx()))
        }
    }
}
