package com.codetic.saoreo

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
private enum class CallPanelContent { OPTIONS, AUDIO, VIDEO }

@Composable
internal fun TogetherCallView(
    room: TogetherState, calling: TogetherCallState, call: TogetherCall, message: String,
    onMessage: (String) -> Unit, send: () -> Unit, chatOpen: Boolean, toggleChat: () -> Unit,
    onBack: () -> Unit, endCall: () -> Unit, toggleCamera: () -> Unit, modifier: Modifier = Modifier,
    standaloneKind: PairKind? = null,
    toggleScreenShare: () -> Unit = {},
    connectionNotice: String? = null,
    chatPreviews: List<TogetherChat> = emptyList(),
    openStream: () -> Unit = onBack,
    pairedChat: (@Composable () -> Unit)? = null,
) {
    val peer = room.members.firstOrNull { it.id != room.member }
    val localVideoOn = calling.camera || calling.sharing
    val peerVideoOn = peer?.camera == true
    val localName = if (calling.sharing) "Your screen" else "You"
    val peerName = peer?.name ?: "Partner"
    var controlsVisible by rememberSaveable(room.room) { mutableStateOf(true) }
    var previewHidden by rememberSaveable(room.room) { mutableStateOf(false) }
    var localFocused by rememberSaveable(room.room) { mutableStateOf(false) }
    var panel by remember { mutableStateOf<CallPanelContent?>(null) }
    BackHandler(enabled = panel != null) {
        panel = if (panel == CallPanelContent.AUDIO || panel == CallPanelContent.VIDEO) CallPanelContent.OPTIONS else null
    }
    var previewX by rememberSaveable(room.room) { mutableFloatStateOf(1f) }
    var previewY by rememberSaveable(room.room) { mutableFloatStateOf(0f) }
    val localMain = !chatOpen && localFocused && !previewHidden && localVideoOn && peerVideoOn
    val hasPreview = localVideoOn && !previewHidden
    val showControls = controlsVisible || chatOpen || panel != null
    var touchedAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(controlsVisible, chatOpen, panel, touchedAt, calling.mic, calling.camera, calling.sharing) {
        if (controlsVisible && !chatOpen && panel == null) { delay(4000); controlsVisible = false }
    }
    fun hideOrShowPreview() {
        previewHidden = !previewHidden
        if (previewHidden) localFocused = false
    }
    fun toggleControls() {
        panel = null
        touchedAt = SystemClock.elapsedRealtime()
        controlsVisible = !showControls
        if (chatOpen) toggleChat()
    }
    val messages = room.chat.takeLast(30)
    val scroll = rememberLazyListState()
    LaunchedEffect(chatOpen, messages.lastOrNull()?.id) {
        if (chatOpen && messages.isNotEmpty()) scroll.scrollToItem(messages.lastIndex)
    }
    Column(modifier.fillMaxSize().background(Color.Black)) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(if (chatOpen) 0.4f else 1f)) {
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
                Modifier.fillMaxSize(), rounded = false, showLabel = false, overlay = false, clipVideo = chatOpen,
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
                        .pointerInput(dragWidthPx, dragHeightPx) {
                            detectDragGestures { change, amount ->
                                change.consume()
                                if (dragWidthPx > 0f) previewX = (previewX + amount.x / dragWidthPx).coerceIn(0f, 1f)
                                if (dragHeightPx > 0f) previewY = (previewY + amount.y / dragHeightPx).coerceIn(0f, 1f)
                            }
                        }
                        .clickable(enabled = peerVideoOn && !chatOpen, interactionSource = null, indication = null, onClickLabel = "Swap video views") {
                            localFocused = !localMain
                        },
                    fill = if (localMain) !calling.remoteSharing else !calling.sharing,
                    showLabel = false,
                    clipVideo = true,
                )
            }
            TogetherChatPreviewStack(chatPreviews,
                Modifier.align(Alignment.TopCenter).zIndex(2f)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(top = if (showControls) 64.dp else 12.dp, start = 16.dp, end = 16.dp))
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
                    Column(Modifier.weight(1f)) {
                        CallHeader(standaloneKind, calling, calling.mediaWarning ?: connectionNotice, calling.connectedAtMs)
                        if (calling.retryAvailable) TextButton(call::retryConnection) { Text("Retry connection") }
                    }
                }
                val safeSides = if (chatOpen) WindowInsetsSides.Horizontal else WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                val panelHeight = (maxHeight - 94.dp).coerceAtLeast(64.dp)
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().zIndex(3f)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .88f))))
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(safeSides))
                    .padding(top = 12.dp, bottom = if (chatOpen) 8.dp else 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val panelModifier = Modifier.widthIn(max = 460.dp).fillMaxWidth()
                        .padding(horizontal = 12.dp).heightIn(max = panelHeight)
                    when (panel) {
                        CallPanelContent.OPTIONS -> TogetherCallOptionsPanel(
                            calling, previewHidden, localVideoOn, ::hideOrShowPreview,
                            showAudio = { panel = CallPanelContent.AUDIO },
                            showVideo = { panel = CallPanelContent.VIDEO },
                            openStream = { panel = null; openStream() },
                            shareScreen = { panel = null; toggleScreenShare() },
                            toggleTorch = { call.torch(!calling.torch) },
                            toggleRemote = { call.muteRemote(!calling.remoteMuted) },
                            hideControls = ::toggleControls, modifier = panelModifier,
                        )
                        CallPanelContent.AUDIO -> TogetherCallAudioPanel(
                            calling, call, close = { panel = null }, modifier = panelModifier,
                        )
                        CallPanelContent.VIDEO -> TogetherCallVideoPanel(
                            calling, call, close = { panel = CallPanelContent.OPTIONS }, modifier = panelModifier,
                        )
                        null -> Unit
                    }
                    Row(Modifier.widthIn(max = 440.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        CallAction(if (calling.mic) R.drawable.ic_mic else R.drawable.ic_mic_off,
                            if (calling.mic) "Mute microphone" else "Unmute microphone",
                            { call.microphone(!calling.mic) }, enabled = calling.active, selected = calling.active && !calling.mic)
                        if (standaloneKind != PairKind.AUDIO) CallAction(
                            if (calling.camera) R.drawable.ic_camera else R.drawable.ic_camera_off,
                            if (calling.camera) "Turn camera off" else "Turn camera on", toggleCamera, enabled = calling.active)
                        CallAction(R.drawable.ic_camera_flip, "Switch camera", {
                            touchedAt = SystemClock.elapsedRealtime()
                            call.flip()
                        }, enabled = calling.active && calling.camera)
                        CallAction(R.drawable.ic_chat, if (chatOpen) "Close call chat" else "Open call chat", {
                            panel = null
                            toggleChat()
                        }, selected = chatOpen)
                        CallAction(R.drawable.ic_call_options, "Call options", {
                            panel = if (panel == CallPanelContent.OPTIONS) null else CallPanelContent.OPTIONS
                        }, selected = panel == CallPanelContent.OPTIONS)
                        CallAction(R.drawable.ic_call_end, "End call", endCall, destructive = true)
                    }
                }
            }
        }
        if (chatOpen && pairedChat != null) Box(Modifier.fillMaxWidth().weight(0.6f).navigationBarsPadding()) {
            pairedChat()
        } else if (chatOpen) Surface(Modifier.fillMaxWidth().weight(0.6f)) {
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
        destructive -> Color(0xFFFF2E48)
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
