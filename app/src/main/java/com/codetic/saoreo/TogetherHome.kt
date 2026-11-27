package com.codetic.saoreo

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

@Composable
internal fun TogetherHome(
    partner: String?, online: Boolean, photo: File?, photoRevision: Int,
    lastMessage: PairedMessage?, recentCalls: List<PairedCallEntry>, canCall: Boolean,
    back: () -> Unit, openSearch: () -> Unit, openChat: () -> Unit, openCalls: () -> Unit,
    openActivities: () -> Unit, openProfile: () -> Unit, openPairing: () -> Unit,
    audioCall: () -> Unit, videoCall: () -> Unit, modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier.fillMaxSize(), color = colors.background, contentColor = colors.onBackground) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", Modifier.size(24.dp)) }
                Text("Together", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.headlineSmall)
                IconButton(openSearch, enabled = partner != null) {
                    Icon(painterResource(R.drawable.ic_ui_search), "Search conversation", Modifier.size(25.dp))
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                val compact = maxHeight < 510.dp
                Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp).padding(top = if (compact) 8.dp else 19.dp, bottom = if (compact) 10.dp else 24.dp)) {
                    if (partner == null) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(Modifier.size(88.dp), color = colors.primary.copy(alpha = .14f).compositeOver(colors.background),
                                contentColor = colors.primary, shape = CircleShape) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(painterResource(R.drawable.ic_together_message), null, Modifier.size(34.dp))
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            Text("Connect with a partner", style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(14.dp))
                            OutlinedButton(openPairing) { Text("Pair a device") }
                        }
                    } else {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Column(Modifier.clip(RoundedCornerShape(24.dp)).clickable(
                                onClickLabel = "Open partner information", role = Role.Button, onClick = openProfile
                            ).padding(horizontal = 16.dp, vertical = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                if (photo?.isFile == true) {
                                    PartnerAvatar(partner, if (compact) 68 else 88, picture = photo, revision = photoRevision)
                                } else {
                                    Surface(Modifier.size(if (compact) 68.dp else 88.dp), shape = CircleShape,
                                        color = colors.primary.copy(alpha = .14f).compositeOver(colors.background),
                                        contentColor = colors.primary) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(partner.firstOrNull()?.uppercase().orEmpty(), style = MaterialTheme.typography.displaySmall)
                                        }
                                    }
                                }
                                Spacer(Modifier.height(if (compact) 5.dp else 10.dp))
                                Text(partner, style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                    Box(Modifier.size(7.dp).background(if (online) colors.primary else colors.onSurfaceVariant, CircleShape))
                                    Text(if (online) "Online" else "Offline", color = colors.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            Spacer(Modifier.height(if (compact) 10.dp else 19.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                                HomeCallButton("Audio call", R.drawable.ic_call, canCall, false, compact, audioCall)
                                HomeCallButton("Video call", R.drawable.ic_camera, canCall, true, compact, videoCall)
                            }
                        }

                        Spacer(Modifier.height(if (compact) 9.dp else 18.dp))
                        HomeDetailsRow(R.drawable.ic_together_message, "Last message", openChat, compact, message = lastMessage)
                        HorizontalDivider(color = colors.outlineVariant)
                        Spacer(Modifier.height(if (compact) 12.dp else 21.dp))
                        Text("Recent calls", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (recentCalls.isEmpty()) {
                            Text("No calls yet", Modifier.padding(vertical = 16.dp),
                                color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        } else {
                            recentCalls.take(2).forEach { call ->
                                HomeDetailsRow(if (call.kind == PairKind.VIDEO) R.drawable.ic_camera else R.drawable.ic_call,
                                    call.kind.title, openCalls, compact, call = call)
                                HorizontalDivider(color = colors.outlineVariant.copy(alpha = .65f))
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = colors.outlineVariant.copy(alpha = .72f))
            Row(Modifier.fillMaxWidth().background(colors.surfaceContainerLow).navigationBarsPadding()
                .padding(horizontal = 6.dp, vertical = 4.dp)) {
                HomeNavItem("Home", true, {}, Modifier.weight(1f)) { tint ->
                    Icon(painterResource(R.drawable.ic_nav_home), null, Modifier.size(23.dp), tint)
                }
                HomeNavItem("Chat", false, openChat, Modifier.weight(1f)) { tint ->
                    Icon(painterResource(R.drawable.ic_together_message), null, Modifier.size(23.dp), tint)
                }
                HomeNavItem("Calls", false, openCalls, Modifier.weight(1f)) { tint ->
                    Icon(painterResource(R.drawable.ic_call), null, Modifier.size(23.dp), tint)
                }
                HomeNavItem("Settings", false, openActivities, Modifier.weight(1f)) { tint ->
                    Icon(painterResource(R.drawable.ic_profile_settings), null, Modifier.size(22.dp), tint)
                }
            }
        }
    }
}

@Composable
private fun HomeNavItem(label: String, selected: Boolean, open: () -> Unit, modifier: Modifier,
    icon: @Composable (Color) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tint = if (selected) colors.primary else colors.onSurfaceVariant
    Column(modifier.selectable(selected = selected, role = Role.Tab, onClick = open)
        .heightIn(min = 60.dp).padding(top = 6.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        icon(if (selected) colors.onSurface else tint)
        Text(label, color = tint, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        Spacer(Modifier.padding(top = 2.dp).size(37.dp, 3.dp)
            .background(if (selected) colors.primary else Color.Transparent, CircleShape))
    }
}

@Composable
private fun HomeCallButton(label: String, @DrawableRes icon: Int, enabled: Boolean, accent: Boolean, compact: Boolean, action: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.widthIn(min = 84.dp).clip(RoundedCornerShape(18.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = action).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 7.dp)) {
        Surface(Modifier.size(if (compact) 52.dp else 64.dp), shape = CircleShape,
            color = if (accent) colors.primary.copy(alpha = .13f).compositeOver(colors.background) else colors.surfaceContainer,
            contentColor = if (accent) colors.primary else colors.onSurface) {
            Box(contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), null, Modifier.size(26.dp),
                    tint = LocalContentColor.current.copy(alpha = if (enabled) 1f else .45f))
            }
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun HomeDetailsRow(@DrawableRes icon: Int, title: String, open: () -> Unit, compact: Boolean,
    message: PairedMessage? = null, call: PairedCallEntry? = null) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = open)
        .heightIn(min = if (compact) 52.dp else 65.dp).padding(vertical = if (compact) 5.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(Modifier.size(40.dp), shape = CircleShape,
            color = if (call == null) Color.Transparent else colors.surfaceContainer,
            contentColor = if (call?.kind == PairKind.VIDEO) colors.primary else colors.onSurfaceVariant) {
            Box(contentAlignment = Alignment.Center) { Icon(painterResource(icon), null, Modifier.size(22.dp)) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = if (call == null) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleSmall,
                color = if (call == null) colors.onSurfaceVariant else colors.onSurface)
            if (call != null) {
                val missed = call.outcome == "missed"
                val status = when (call.outcome) {
                    "completed" -> "Completed"
                    "connected" -> "In progress"
                    "accepted" -> "Accepted"
                    else -> call.outcome.replaceFirstChar(Char::titlecase)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (call.incoming) "↘" else "↗", color = if (missed) colors.error else colors.primary,
                        style = MaterialTheme.typography.bodySmall)
                    Text(listOfNotNull(status, homeTime(call.time),
                        call.duration.takeIf { it > 0L }?.let(::chatMediaDuration)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            } else if (message == null) {
                Text("No messages yet", style = MaterialTheme.typography.bodyMedium)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(message.previewLabel(), Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium)
                    Text(homeTime(message.time), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(22.dp), colors.onSurfaceVariant)
    }
}

private fun homeTime(time: Long): String {
    val day = Calendar.getInstance().apply { timeInMillis = time }
    val now = Calendar.getInstance()
    fun isSameDay(other: Calendar) = day.get(Calendar.YEAR) == other.get(Calendar.YEAR) &&
        day.get(Calendar.DAY_OF_YEAR) == other.get(Calendar.DAY_OF_YEAR)
    val clock = pairedChatTime(time)
    if (isSameDay(now)) return "Today, $clock"
    now.add(Calendar.DAY_OF_YEAR, -1)
    if (isSameDay(now)) return "Yesterday, $clock"
    return "${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(time))}, $clock"
}
