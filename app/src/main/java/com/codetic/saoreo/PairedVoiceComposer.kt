package com.codetic.saoreo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal enum class VoiceStage { Idle, Held, Locked, Ready }

@Composable
internal fun ChatVoiceControls(
    stage: VoiceStage, enabled: Boolean, press: () -> Boolean,
    release: () -> Unit, cancelHold: () -> Unit, lock: () -> Unit, accessibleStart: () -> Unit,
    send: () -> Unit,
) {
    val currentPress by rememberUpdatedState(press)
    val currentRelease by rememberUpdatedState(release)
    val currentCancel by rememberUpdatedState(cancelHold)
    val currentLock by rememberUpdatedState(lock)
    val colors = MaterialTheme.colorScheme
    var discardDirection by remember { mutableIntStateOf(0) }
    var dropFrom by remember { mutableStateOf(Offset.Zero) }
    val drop = remember { Animatable(0f) }
    LaunchedEffect(discardDirection) {
        if (discardDirection != 0) {
            drop.snapTo(0f)
            drop.animateTo(1f, tween(390))
            discardDirection = 0
        }
    }
    if (stage == VoiceStage.Idle || stage == VoiceStage.Held) {
        val lockTravel = 72.dp
        val cancelLeft = 98.dp
        val cancelRight = 27.dp
        val density = LocalDensity.current
        var drag by remember { mutableStateOf(Offset.Zero) }
        val draggingUp = -drag.y > abs(drag.x) && drag.y < 0
        val draggingSideways = abs(drag.x) > -drag.y && with(density) { abs(drag.x) > 9.dp.toPx() }
        val direction = when {
            discardDirection != 0 -> discardDirection
            draggingSideways && drag.x > 0 -> 1
            draggingSideways -> -1
            else -> 0
        }
        val targetX = if (direction < 0) -cancelLeft else 3.dp
        val targetY = if (direction < 0) 0.dp else -48.dp
        // The touch target stays in the composer. Only the artwork moves above it.
        Box(
            Modifier.size(44.dp).semantics {
                contentDescription = "Record voice note"
                role = Role.Button
                if (enabled) onClick(label = "Start locked recording") { accessibleStart(); true }
            }.pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val lockDistance = lockTravel.toPx()
                val leftDistance = cancelLeft.toPx()
                val rightDistance = cancelRight.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    if (discardDirection != 0 || !currentPress()) return@awaitEachGesture
                    try {
                        while (true) {
                            val move = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (move.isConsumed) break
                            val offset = move.position - down.position
                            drag = offset
                            if (-offset.y >= lockDistance && -offset.y > abs(offset.x)) {
                                move.consume()
                                currentLock()
                                break
                            }
                            if ((offset.x <= -leftDistance || offset.x >= rightDistance) && abs(offset.x) >= -offset.y) {
                                val side = if (offset.x < 0) -1 else 1
                                dropFrom = Offset(if (side < 0) -leftDistance * .78f else rightDistance * .5f, 0f)
                                discardDirection = side
                                move.consume()
                                break
                            }
                            if (!move.pressed) {
                                move.consume()
                                currentRelease()
                                break
                            }
                            move.consume()
                        }
                    } finally {
                        drag = Offset.Zero
                        currentCancel()
                    }
                }
            }.alpha(if (enabled) 1f else .45f),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(
                visible = stage == VoiceStage.Held && direction == 0,
                modifier = Modifier.absoluteOffset(y = -lockTravel - 7.dp),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                val progress = with(density) { (-drag.y / lockTravel.toPx()).coerceIn(0f, 1f) }
                Column(Modifier.shadow(5.dp, RoundedCornerShape(22.dp))
                    .background(colors.surface, RoundedCornerShape(22.dp))
                    .border(1.dp, colors.primary.copy(alpha = .22f + progress * .78f), RoundedCornerShape(22.dp))
                    .width(42.dp).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(painterResource(R.drawable.ic_chat_lock), "Slide up to lock recording",
                        Modifier.size(17.dp), tint = if (draggingUp) colors.primary else colors.onSurface)
                    Icon(Icons.Outlined.KeyboardArrowUp, null, Modifier.size(17.dp), tint = colors.primary)
                }
            }
            if (direction != 0) {
                val lid = if (discardDirection != 0) (1f - (drop.value - .64f) / .36f).coerceIn(0f, 1f)
                else with(density) { (abs(drag.x) / 34.dp.toPx()).coerceIn(.25f, 1f) }
                Box(Modifier.absoluteOffset(x = targetX, y = targetY).size(43.dp)
                    .shadow(4.dp, CircleShape).background(colors.errorContainer, CircleShape),
                    contentAlignment = Alignment.Center) {
                    ChatDiscardBin(lid, colors.onErrorContainer)
                }
            }
            val disappearing = discardDirection != 0
            val dropFraction = if (disappearing) (drop.value / .74f).coerceIn(0f, 1f) else 0f
            Box(
                Modifier.absoluteOffset {
                    val dx = if (direction < 0) drag.x.coerceIn(-cancelLeft.toPx() * .78f, 0f)
                        else drag.x.coerceIn(0f, 12.dp.toPx())
                    val dy = if (direction == 0) drag.y.coerceIn(-lockTravel.toPx(), 0f) else 0f
                    val x = if (disappearing) dropFrom.x + (targetX.toPx() - dropFrom.x) * dropFraction else dx
                    val y = if (disappearing) dropFrom.y + (targetY.toPx() - dropFrom.y) * dropFraction else dy
                    IntOffset(x.roundToInt(), y.roundToInt())
                }.size(44.dp).alpha(1f - dropFraction),
                contentAlignment = Alignment.Center,
            ) {
                val holding = stage == VoiceStage.Held || disappearing
                val scale = 1f - .65f * dropFraction
                Box(Modifier.size(44.dp * scale).shadow(if (holding) 5.dp else 0.dp, CircleShape)
                    .background(if (holding) colors.primaryContainer else colors.primary, CircleShape),
                    contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_mic), null, Modifier.size(18.dp * scale),
                        tint = if (holding) colors.onPrimaryContainer else colors.onPrimary)
                }
            }
        }
    } else {
        FilledIconButton(send, modifier = Modifier.size(44.dp)) {
            Icon(Icons.AutoMirrored.Outlined.Send, "Send voice note", Modifier.size(17.dp))
        }
    }
}

@Composable
private fun ChatDiscardBin(open: Float, tint: Color) {
    Canvas(Modifier.size(24.dp)) {
        val unit = size.width / 24f
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun edge(x: Float, y: Float, endX: Float, endY: Float) {
            drawLine(tint, point(x, y), point(endX, endY), strokeWidth = 1.7f * unit, cap = StrokeCap.Round)
        }
        edge(6f, 9f, 7.5f, 21f)
        edge(7.5f, 21f, 16.5f, 21f)
        edge(16.5f, 21f, 18f, 9f)
        rotate(-33f * open, point(4f, 8f)) {
            edge(4f, 8f, 20f, 8f)
            edge(9f, 5.5f, 15f, 5.5f)
        }
    }
}

@Composable
internal fun ChatRecordingBar(
    stage: VoiceStage, elapsed: Long, levels: List<Int>, discard: () -> Unit, modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = colors.surfaceVariant) {
        Row(Modifier.heightIn(min = 44.dp).padding(start = 3.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(discard, Modifier.size(40.dp)) {
                Icon(Icons.Outlined.Delete, "Delete recording", Modifier.size(17.dp), tint = colors.error)
            }
            if (stage != VoiceStage.Ready) {
                Box(Modifier.size(6.dp).background(colors.error, CircleShape))
                Spacer(Modifier.width(6.dp))
            }
            Text(chatMediaDuration(elapsed), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
            if (stage == VoiceStage.Ready) {
                Text("Voice note ready", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.labelMedium)
            } else {
                Canvas(Modifier.weight(1f).padding(horizontal = 8.dp).height(22.dp)
                    .semantics { contentDescription = "Recording waveform" }) {
                    val count = (size.width / 3.2.dp.toPx()).roundToInt().coerceAtLeast(1)
                    val interval = size.width / count
                    repeat(count) { index ->
                        val level = levels.getOrNull(levels.size - count + index) ?: 0
                        val x = interval * (index + .5f)
                        val amplitude = size.height * (.055f + .42f * sqrt(level / 32767f))
                        drawLine(colors.primary, Offset(x, center.y - amplitude), Offset(x, center.y + amplitude),
                            strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
                    }
                }
                if (stage == VoiceStage.Locked) Icon(painterResource(R.drawable.ic_chat_lock), "Recording locked",
                    Modifier.size(16.dp), tint = colors.primary)
            }
        }
    }
}
