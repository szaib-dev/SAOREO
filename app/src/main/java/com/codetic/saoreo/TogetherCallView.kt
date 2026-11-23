package com.codetic.saoreo

import android.os.SystemClock
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import java.util.Locale

private val CallAccent = Color(0xFFC2B1FF)
private val CallGlass = Color(0xBA211E2A)
private val CallGlassBorder = Color(0x35E4DCFA)

@Composable
internal fun TogetherCallView(
    room: TogetherState, calling: TogetherCallState, call: TogetherCall, message: String,
    onMessage: (String) -> Unit, send: () -> Unit, chatOpen: Boolean, toggleChat: () -> Unit,
    onBack: () -> Unit, endCall: () -> Unit, toggleCamera: () -> Unit, modifier: Modifier = Modifier,
    standaloneKind: PairKind? = null,
    toggleScreenShare: () -> Unit = {}, showAudio: () -> Unit = {},
    connectionNotice: String? = null,
) {
    val peer = room.members.firstOrNull { it.id != room.member }
    val localVideoOn = calling.camera || calling.sharing
    val peerVideoOn = peer?.camera == true && peer.online
    val localName = if (calling.sharing) "Your screen" else "You"
    val peerName = peer?.name ?: "Partner"
    var controlsVisible by rememberSaveable(room.room) { mutableStateOf(true) }
    var previewHidden by rememberSaveable(room.room) { mutableStateOf(false) }
    var localFocused by rememberSaveable(room.room) { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var previewX by rememberSaveable(room.room) { mutableFloatStateOf(1f) }
    var previewY by rememberSaveable(room.room) { mutableFloatStateOf(0f) }
    var connectedAt by rememberSaveable(room.room) { mutableLongStateOf(0L) }
    LaunchedEffect(calling.active, calling.connection) {
        if (!calling.active) connectedAt = 0L
        else if (calling.connection == "Connected" && connectedAt == 0L) connectedAt = SystemClock.elapsedRealtime()
    }
    val localMain = localFocused && !previewHidden && localVideoOn && peerVideoOn
    val hasPreview = localVideoOn && !previewHidden
    val showControls = controlsVisible || chatOpen
    fun hideOrShowPreview() {
        previewHidden = !previewHidden
        if (previewHidden) localFocused = false
    }
    fun toggleControls() {
        menuOpen = false
        controlsVisible = !showControls
        if (chatOpen) toggleChat()
    }
    val messages = room.chat.takeLast(30)
    val scroll = rememberLazyListState()
    LaunchedEffect(chatOpen, messages.lastOrNull()?.id) {
        if (chatOpen && messages.isNotEmpty()) scroll.scrollToItem(messages.lastIndex)
    }
    Column(modifier.fillMaxSize().background(Color.Black)) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val insetSides = WindowInsets.safeDrawing.asPaddingValues()
            val layoutDirection = LocalLayoutDirection.current
            val left = insetSides.calculateLeftPadding(layoutDirection) + 12.dp
            val right = insetSides.calculateRightPadding(layoutDirection) + 12.dp
            val top = insetSides.calculateTopPadding() + if (showControls) 76.dp else 12.dp
            val bottom = if (chatOpen) 68.dp else insetSides.calculateBottomPadding() + if (showControls) 84.dp else 12.dp
            val previewWidth = minOf(if (chatOpen) 78.dp else 108.dp, (maxWidth - left - right).coerceAtLeast(1.dp))
            val previewHeight = minOf(if (chatOpen) 100.dp else 156.dp, (maxHeight - top - bottom).coerceAtLeast(1.dp))
            val dragWidth = (maxWidth - left - right - previewWidth).coerceAtLeast(0.dp)
            val dragHeight = (maxHeight - top - bottom - previewHeight).coerceAtLeast(0.dp)
            val density = LocalDensity.current
            val dragWidthPx = with(density) { dragWidth.toPx() }
            val dragHeightPx = with(density) { dragHeight.toPx() }
            if (!localMain && standaloneKind == PairKind.AUDIO && !peerVideoOn) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Surface(Modifier.size(86.dp), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Box(contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_call), null, Modifier.size(35.dp)) }
                    }
                    Text(peer?.name ?: "Partner", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                }
            } else CameraTile(
                if (localMain) localName else peerName,
                if (localMain) calling.localVideo else calling.remoteVideo,
                if (localMain) localVideoOn else peerVideoOn, call, localMain && !calling.sharing,
                Modifier.fillMaxSize(), rounded = false, showLabel = false, overlay = false,
            )
            if (calling.remoteBackground) Text("$peerName is using other apps",
                Modifier.align(Alignment.Center).background(CallGlass, RoundedCornerShape(12.dp)).padding(12.dp),
                color = Color.White)
            Box(Modifier.matchParentSize().clickable(
                interactionSource = null, indication = null,
                onClickLabel = if (showControls) "Hide call controls" else "Show call controls",
                onClick = ::toggleControls,
            ))
            if (hasPreview && previewHeight >= 48.dp) {
                CameraTile(
                    if (localMain) peerName else localName,
                    if (localMain) calling.remoteVideo else calling.localVideo,
                    if (localMain) peerVideoOn else localVideoOn, call, !localMain && !calling.sharing,
                    Modifier.absoluteOffset(x = left + dragWidth * previewX, y = top + dragHeight * previewY)
                        .size(previewWidth, previewHeight)
                        .zIndex(1f)
                        .border(1.dp, CallAccent.copy(alpha = if (showControls) .85f else .38f), RoundedCornerShape(20.dp))
                        .pointerInput(dragWidthPx, dragHeightPx) {
                            detectDragGestures { change, amount ->
                                change.consume()
                                if (dragWidthPx > 0f) previewX = (previewX + amount.x / dragWidthPx).coerceIn(0f, 1f)
                                if (dragHeightPx > 0f) previewY = (previewY + amount.y / dragHeightPx).coerceIn(0f, 1f)
                            }
                        }
                        .clickable(enabled = peerVideoOn, interactionSource = null, indication = null, onClickLabel = "Swap video views") {
                            localFocused = !localMain
                        },
                    fill = !calling.sharing && !localMain,
                    showLabel = false,
                    clipVideo = true,
                ) {
                    if (showControls) {
                        Box(Modifier.align(Alignment.TopCenter)
                            .clip(RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp))
                            .background(CallGlass).padding(horizontal = 9.dp, vertical = 2.dp)) {
                            Icon(painterResource(R.drawable.ic_call_options), null, Modifier.size(16.dp), tint = Color.White)
                        }
                        if (previewHeight >= 78.dp) Box(Modifier.align(Alignment.BottomEnd).padding(5.dp)) {
                            CallAction(R.drawable.ic_call_hide_preview, "Hide self view", ::hideOrShowPreview, size = 44)
                        }
                    }
                }
            }
            if (showControls) {
                Row(Modifier.fillMaxWidth().align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .78f), Color.Transparent)))
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (standaloneKind == null) Surface(
                        onClick = onBack, shape = CircleShape, color = CallGlass, contentColor = Color.White,
                        border = BorderStroke(1.dp, CallGlassBorder), modifier = Modifier.size(48.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to watch room")
                        }
                    }
                    CallHeader(standaloneKind, calling, connectionNotice, connectedAt)
                }
                val safeSides = if (chatOpen) WindowInsetsSides.Horizontal else WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .88f))))
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(safeSides))
                    .padding(top = 22.dp, bottom = if (chatOpen) 8.dp else 16.dp),
                    contentAlignment = Alignment.Center) {
                    Row(Modifier.widthIn(max = 440.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        CallAction(if (calling.mic) R.drawable.ic_mic else R.drawable.ic_mic_off,
                            if (calling.mic) "Mute microphone" else "Unmute microphone",
                            { call.microphone(!calling.mic) }, enabled = calling.active, selected = calling.active && !calling.mic)
                        if (standaloneKind != PairKind.AUDIO) CallAction(
                            if (calling.camera) R.drawable.ic_camera else R.drawable.ic_camera_off,
                            if (calling.camera) "Turn camera off" else "Turn camera on", toggleCamera, enabled = calling.active)
                        CallAction(R.drawable.ic_call_share_screen, if (calling.sharing) "Stop sharing" else "Share screen",
                            toggleScreenShare, enabled = calling.active, selected = calling.sharing)
                        CallAction(R.drawable.ic_chat, if (chatOpen) "Close call chat" else "Open call chat", toggleChat, selected = chatOpen)
                        Box {
                            CallAction(R.drawable.ic_call_options, "Call options", { menuOpen = !menuOpen }, selected = menuOpen)
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false },
                                shape = RoundedCornerShape(21.dp), containerColor = Color(0xFF24202F),
                                border = BorderStroke(1.dp, CallGlassBorder)) {
                                if (localVideoOn) {
                                    CallMenuItem(if (previewHidden) "Show self view" else "Hide self view",
                                        if (previewHidden) "See your camera preview" else "Keep your camera on for your partner",
                                        R.drawable.ic_call_hide_preview) { menuOpen = false; hideOrShowPreview() }
                                    if (!previewHidden) CallMenuItem("Swap views",
                                        if (localMain) "Focus on your partner" else "Make yourself the main view",
                                        R.drawable.ic_call_swap_views) { menuOpen = false; localFocused = !localMain }
                                }
                                CallMenuItem("Audio devices", "Speaker, Bluetooth and microphone",
                                    R.drawable.ic_call_audio_devices, enabled = calling.active) { menuOpen = false; showAudio() }
                                if (calling.camera) CallMenuItem("Switch camera", "Front or rear camera",
                                    R.drawable.ic_camera_flip) { menuOpen = false; call.flip() }
                                CallMenuItem("Hide controls", "Tap the video to bring them back",
                                    R.drawable.ic_room_fullscreen, onClick = ::toggleControls)
                            }
                        }
                        CallAction(R.drawable.ic_call_end, "End call", endCall, destructive = true)
                    }
                }
            }
        }
        if (chatOpen) Surface(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                LazyColumn(Modifier.fillMaxWidth().weight(1f), state = scroll, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(messages, key = { it.id }) { entry ->
                        Surface(shape = RoundedCornerShape(14.dp), color = if (entry.sender == room.member)
                            MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Text("${if (entry.sender == room.member) "You" else entry.name} · ${entry.text}",
                                Modifier.padding(9.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                OutlinedTextField(message, onMessage, Modifier.fillMaxWidth(), placeholder = { Text("Message") },
                    shape = RoundedCornerShape(20.dp), maxLines = 2, trailingIcon = {
                        IconButton(send, enabled = room.connected && message.isNotBlank()) { Icon(Icons.AutoMirrored.Outlined.Send, "Send message") }
                    })
            }
        }
    }
}

@Composable
private fun CallHeader(kind: PairKind?, calling: TogetherCallState, connectionNotice: String?, connectedAt: Long) {
    var seconds by remember(connectedAt) { mutableLongStateOf(0L) }
    LaunchedEffect(connectedAt) {
        if (connectedAt <= 0L) return@LaunchedEffect
        while (true) {
            val elapsed = (SystemClock.elapsedRealtime() - connectedAt).coerceAtLeast(0L)
            seconds = elapsed / 1_000
            delay(1_000 - elapsed % 1_000)
        }
    }
    val connected = calling.connection == "Connected"
    val status = connectionNotice ?: when {
        kind != null && !calling.active -> "Connecting…"
        connected && connectedAt > 0 -> "Connected · " + String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60)
        else -> calling.connection
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(if (kind == PairKind.AUDIO) "Audio call" else "Video call", color = Color.White,
            style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(7.dp).background(
                if (connected && connectionNotice == null) Color(0xFF9BE477) else Color(0xFFF3BE78), CircleShape))
            Text(status, color = Color(0xFFE2DDEA), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun CallAction(
    @DrawableRes icon: Int, description: String, onClick: () -> Unit,
    enabled: Boolean = true, selected: Boolean = false, destructive: Boolean = false, size: Int = 48,
) {
    val color = when {
        destructive -> Color(0xFFE82742)
        selected -> Color(0xFF473765)
        else -> CallGlass
    }
    val border = when {
        destructive -> Color(0xFFF87180)
        selected -> CallAccent
        else -> CallGlassBorder
    }
    Surface(onClick = onClick, enabled = enabled, modifier = Modifier.size(size.dp), shape = CircleShape,
        color = color, contentColor = if (enabled) Color.White else Color.White.copy(alpha = .38f),
        border = BorderStroke(1.dp, border)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), description, Modifier.size(if (size < 48) 20.dp else 23.dp))
        }
    }
}

@Composable
private fun CallMenuItem(
    title: String, description: String, @DrawableRes icon: Int, enabled: Boolean = true, onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFCAC0D9).copy(alpha = if (enabled) 1f else .45f))
            }
        },
        onClick = onClick, enabled = enabled,
        leadingIcon = { Icon(painterResource(icon), null, Modifier.size(21.dp)) },
        colors = MenuDefaults.itemColors(textColor = Color.White, leadingIconColor = CallAccent,
            disabledTextColor = Color.White.copy(alpha = .45f), disabledLeadingIconColor = CallAccent.copy(alpha = .45f)),
    )
}
