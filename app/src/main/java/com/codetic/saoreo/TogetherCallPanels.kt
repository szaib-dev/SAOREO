package com.codetic.saoreo

import android.media.AudioDeviceInfo
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private val PanelInk = Color(0xFF161720)
private val PanelText = Color(0xFFF4F1FF)
private val PanelMuted = Color(0xFFCFCBDD)
private val PanelEdge = Color(0x38DAD4F2)
private val PanelAccent = Color(0xFFCFBAFF)

@Composable
internal fun TogetherCallOptionsPanel(
    calling: TogetherCallState,
    previewHidden: Boolean,
    hasSelfVideo: Boolean,
    togglePreview: () -> Unit,
    showAudio: () -> Unit,
    openStream: () -> Unit,
    shareScreen: () -> Unit,
    toggleTorch: () -> Unit,
    hideControls: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CallPanel(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OptionTile("Hide self view", R.drawable.ic_call_hide_preview, Modifier.weight(1f),
                hasSelfVideo, checked = previewHidden, onClick = togglePreview)
            OptionTile("Audio devices", R.drawable.ic_call_audio_devices, Modifier.weight(1f),
                calling.active, accent = true, onClick = showAudio)
            OptionTile("Stream", R.drawable.ic_call_stream, Modifier.weight(1f),
                calling.active, onClick = openStream)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OptionTile(if (calling.sharing) "Stop sharing" else "Share screen", R.drawable.ic_call_share_screen,
                Modifier.weight(1f), calling.active, accent = calling.sharing, onClick = shareScreen)
            OptionTile("Flashlight", R.drawable.ic_call_torch, Modifier.weight(1f),
                calling.active && calling.hasTorch, checked = calling.torch, onClick = toggleTorch)
        }
        TextButton(hideControls, modifier = Modifier.align(Alignment.CenterHorizontally),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
            Text("Hide controls", color = PanelMuted, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
internal fun TogetherCallAudioPanel(
    calling: TogetherCallState,
    call: TogetherCall,
    close: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val outputs = remember(call, calling.audioDeviceRevision) { call.devices() }
    val inputs = remember(call, calling.audioDeviceRevision) { call.inputs() }
    CallPanel(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF292A37)) {
                Icon(painterResource(R.drawable.ic_call_audio_devices), null,
                    Modifier.padding(10.dp).size(23.dp), tint = PanelAccent)
            }
            Column(Modifier.weight(1f)) {
                Text("Audio devices", color = PanelText, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium)
                Text("Choose input and output for the call", color = PanelMuted,
                    style = MaterialTheme.typography.bodySmall)
            }
            IconButton(close) { Icon(Icons.Outlined.Close, "Close audio devices", tint = PanelText) }
        }
        AudioRouteGrid("Output", R.drawable.ic_speaker, outputs, calling.outputDeviceId, false, call::output)
        HorizontalDivider(color = PanelEdge)
        AudioRouteGrid("Microphone", R.drawable.ic_mic, inputs, calling.inputDeviceId, true, call::input)
    }
}

@Composable
private fun CallPanel(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Surface(modifier, shape = shape, color = PanelInk, border = BorderStroke(1.dp, PanelEdge)) {
        Column(Modifier.background(Brush.verticalGradient(listOf(Color(0xFF252632), PanelInk)))
            .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp), content = {
                Box(Modifier.align(Alignment.CenterHorizontally).size(width = 30.dp, height = 4.dp)
                    .clip(CircleShape).background(PanelMuted.copy(alpha = .75f)))
                content()
            })
    }
}

@Composable
private fun OptionTile(
    label: String, @DrawableRes icon: Int, modifier: Modifier, enabled: Boolean,
    checked: Boolean? = null, accent: Boolean = false, onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(17.dp)
    val action = if (checked == null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        else Modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = { onClick() })
    Surface(modifier.height(98.dp).clip(shape).then(action), shape = shape,
        color = if (accent) Color(0xFF383044) else Color(0xFF242530),
        border = BorderStroke(1.dp, if (accent) PanelAccent.copy(alpha = .55f) else PanelEdge)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 5.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(icon), null, Modifier.size(25.dp),
                tint = PanelAccent.copy(alpha = if (enabled) 1f else .48f))
            Spacer(Modifier.height(4.dp))
            Text(label, color = PanelText.copy(alpha = if (enabled) 1f else .56f),
                style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2)
            if (checked != null) Box(Modifier.height(27.dp), contentAlignment = Alignment.Center) {
                Switch(checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.scale(.67f),
                    colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF8060AB),
                        checkedThumbColor = Color.White, uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color(0xFF444651), uncheckedBorderColor = Color.Transparent))
            }
        }
    }
}

private data class AudioRoute(
    val device: AudioDeviceInfo?, val label: String, @DrawableRes val icon: Int, val available: Boolean = true,
)

private fun audioRoutes(devices: List<AudioDeviceInfo>, microphone: Boolean): List<AudioRoute> {
    val phoneType = if (microphone) AudioDeviceInfo.TYPE_BUILTIN_MIC else AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    val bluetooth = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER)
    val wired = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE)
    val phone = devices.firstOrNull { it.type == phoneType }
    val wireless = devices.firstOrNull { it.type in bluetooth }
    val cable = devices.firstOrNull { it.type in wired }
    val shown = listOfNotNull(phone?.id, wireless?.id, cable?.id)
    return buildList {
        add(AudioRoute(phone, if (microphone) "Phone mic" else "Phone speaker", R.drawable.ic_call_phone, phone != null))
        add(AudioRoute(wireless, if (microphone) "Bluetooth mic" else "Bluetooth", R.drawable.ic_call_bluetooth, wireless != null))
        add(AudioRoute(cable, if (microphone) "Wired headset mic" else "Wired headset", R.drawable.ic_call_audio_devices, cable != null))
        devices.filter { it.id !in shown }.forEach { device ->
            val icon = when (device.type) {
                in bluetooth -> R.drawable.ic_call_bluetooth
                in wired -> R.drawable.ic_call_audio_devices
                else -> R.drawable.ic_call_phone
            }
            add(AudioRoute(device, TogetherCall.outputName(device), icon))
        }
        add(AudioRoute(null, if (microphone) "Automatic mic" else "Automatic output",
            if (microphone) R.drawable.ic_mic else R.drawable.ic_speaker))
    }
}

@Composable
private fun AudioRouteGrid(
    title: String, @DrawableRes icon: Int, devices: List<AudioDeviceInfo>, selectedId: Int?,
    microphone: Boolean, select: (AudioDeviceInfo?) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Icon(painterResource(icon), null, Modifier.size(18.dp), tint = PanelAccent)
        Text(title, color = PanelText, style = MaterialTheme.typography.titleSmall)
    }
    audioRoutes(devices, microphone).chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            row.forEach { route ->
                val selected = route.available && route.device?.id == selectedId
                val shape = RoundedCornerShape(14.dp)
                Surface(Modifier.weight(1f).height(74.dp).clip(shape).selectable(
                    selected, enabled = route.available, role = Role.RadioButton, onClick = { select(route.device) }),
                    shape = shape, color = if (selected) Color(0xFF393847) else Color(0xFF252631),
                    border = BorderStroke(1.dp, if (selected) PanelAccent.copy(alpha = .65f) else PanelEdge)) {
                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().padding(horizontal = 3.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(painterResource(route.icon), null, Modifier.size(22.dp),
                                tint = PanelAccent.copy(alpha = if (route.available) 1f else .46f))
                            Spacer(Modifier.height(3.dp))
                            Text(route.label, color = PanelText.copy(alpha = if (route.available) 1f else .5f),
                                style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2)
                        }
                        if (selected) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(15.dp)
                            .clip(CircleShape).background(PanelAccent), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Check, null, Modifier.size(11.dp), tint = PanelInk)
                        }
                    }
                }
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}
