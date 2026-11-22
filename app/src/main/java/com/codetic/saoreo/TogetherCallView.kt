package com.codetic.saoreo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

@Composable
internal fun TogetherCallView(
    room: TogetherState,
    calling: TogetherCallState,
    call: TogetherCall,
    message: String,
    onMessage: (String) -> Unit,
    send: () -> Unit,
    chatOpen: Boolean,
    toggleChat: () -> Unit,
    onBack: () -> Unit,
    endCall: () -> Unit,
    toggleCamera: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val peer = room.members.firstOrNull { it.id != room.member }
    val messages = room.chat.takeLast(30)
    val scroll = rememberLazyListState()
    LaunchedEffect(chatOpen, messages.lastOrNull()?.id) {
        if (chatOpen && messages.isNotEmpty()) scroll.scrollToItem(messages.lastIndex)
    }
    Column(modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to watch room") }
            Column(Modifier.weight(1f)) {
                Text(peer?.name ?: "Call", style = MaterialTheme.typography.titleMedium)
                Text(calling.connection, style = MaterialTheme.typography.labelSmall)
            }
            IconButton(toggleChat) {
                Icon(painterResource(R.drawable.ic_chat), if (chatOpen) "Close call chat" else "Open call chat")
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            CameraTile(peer?.name ?: "Partner", calling.remoteVideo, peer?.camera == true && peer.online,
                call, false, Modifier.fillMaxSize())
            CameraTile("You", calling.localVideo, calling.camera, call, true,
                Modifier.padding(8.dp).align(Alignment.TopEnd).width(94.dp).fillMaxHeight(0.42f))
        }
        if (chatOpen) {
            LazyColumn(Modifier.fillMaxWidth().weight(0.8f), state = scroll, verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                    IconButton(send, enabled = room.connected && message.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Outlined.Send, "Send message")
                    }
                })
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            IconButton({ call.microphone(!calling.mic) }) {
                Icon(painterResource(if (calling.mic) R.drawable.ic_mic else R.drawable.ic_mic_off),
                    if (calling.mic) "Mute microphone" else "Unmute microphone")
            }
            IconButton(toggleCamera) {
                Icon(painterResource(if (calling.camera) R.drawable.ic_camera else R.drawable.ic_camera_off),
                    if (calling.camera) "Turn camera off" else "Turn camera on")
            }
            FilledTonalIconButton(endCall, colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Icon(painterResource(R.drawable.ic_call_end), "End call")
            }
        }
    }
}
