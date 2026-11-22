package com.codetic.saoreo

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

internal val GameGold = Color(0xFFEAD68E)
internal val GameBlue = Color(0xFF8CD8EE)
internal val GameInk = Color(0xFF161821)
private val GameSheet = Color(0xFF212430)
private val GameLine = Color(0xFF3B4050)
private val GameSlate = Color(0xFFAFB5C5)
private val GameWhite = Color(0xFFF5F4F2)

internal fun JSONObject.gameKind() = TogetherGame.entries.firstOrNull { it.wire == optString("kind") }
internal fun JSONObject.liveGame() = optString("phase") in setOf("inviting", "lobby", "active", "resolving", "paused")
internal fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else List(length()) { optString(it) }
internal fun JSONArray?.ints(): List<Int> = if (this == null) emptyList() else List(length()) { optInt(it) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TogetherGamesScreen(
    session: TogetherSession, room: TogetherState, close: () -> Unit,
    startVoice: () -> Unit, toggleCamera: () -> Unit, expandCall: () -> Unit, callAudio: () -> Unit,
) {
    val games = session.games
    val state by games.state.collectAsStateWithLifecycle()
    val remoteAim by games.remoteAim.collectAsStateWithLifecycle()
    val available by games.available.collectAsStateWithLifecycle()
    val failure by games.error.collectAsStateWithLifecycle()
    val call = session.callController
    val calling by call.state.collectAsStateWithLifecycle()
    val memberIds = state.optJSONArray("players").strings()
    val phase = state.optString("phase")
    val game = state.gameKind()
    val mySide = memberIds.indexOf(room.member)
    val players = memberIds.map { id -> room.members.firstOrNull { it.id == id } }
    var selected by rememberSaveable(room.room) { mutableStateOf<TogetherGame?>(null) }
    var oldResult by rememberSaveable(room.room) { mutableStateOf("") }
    val showMatch = game != null && phase != "abandoned" && state.optString("id") != oldResult
    val active = phase in setOf("active", "resolving", "paused")
    val context = LocalContext.current
    val settings = remember(context) { context.getSharedPreferences("together-game-ui", Context.MODE_PRIVATE) }
    var sound by remember { mutableFloatStateOf(settings.getFloat("sound", .30f)) }
    var haptics by remember { mutableStateOf(settings.getBoolean("haptics", true)) }
    var showReactions by remember { mutableStateOf(settings.getBoolean("reactions", true)) }
    var previewLocal by rememberSaveable { mutableStateOf(settings.getBoolean("self", true)) }
    var previewVideo by rememberSaveable { mutableStateOf(settings.getBoolean("video", true)) }
    var previewX by rememberSaveable(room.room) { mutableFloatStateOf(settings.getFloat("video-x", 1f)) }
    var previewY by rememberSaveable(room.room) { mutableFloatStateOf(settings.getFloat("video-y", 0f)) }
    val savedX by rememberUpdatedState(previewX)
    val savedY by rememberUpdatedState(previewY)
    DisposableEffect(settings) {
        onDispose { settings.edit().putFloat("video-x", savedX).putFloat("video-y", savedY).apply() }
    }
    var opts by rememberSaveable { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    var chat by rememberSaveable { mutableStateOf(false) }
    var reactionMenu by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf("") }
    var hud by rememberSaveable { mutableStateOf(true) }
    var notice by remember { mutableStateOf<String?>(null) }
    var reaction by remember { mutableStateOf<String?>(null) }
    var lastReaction by remember { mutableLongStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val feedback = LocalHapticFeedback.current
    val tone = remember(sound) {
        if (sound > 0f) runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, (sound * 100).roundToInt()) }.getOrNull() else null
    }
    DisposableEffect(tone) { onDispose { tone?.release() } }
    val emoji = remember { linkedMapOf("heart" to "♥", "laugh" to "😂", "clap" to "👏", "wow" to "😮", "fire" to "🔥", "kiss" to "😘") }
    LaunchedEffect(failure) { failure?.let { snackbar.showSnackbar(it); games.dismissError() } }
    LaunchedEffect(phase) {
        if (phase == "abandoned") {
            selected = null
            state.optString("notice").takeIf(String::isNotBlank)?.let { snackbar.showSnackbar(it) }
        }
    }
    LaunchedEffect(room.reaction, showReactions) {
        if (showReactions) room.reaction?.let { reaction = emoji[it.emoji]; delay(1350); reaction = null }
        else reaction = null
    }
    LaunchedEffect(state.optString("id"), state.optInt("turnNumber"), state.optString("phase")) {
        if (active && phase == "active" && game?.realTime == false) {
            notice = if (state.optInt("turn") == mySide) "Your turn" else "Partner’s turn"
            delay(1150)
            notice = null
        }
    }
    val scores = state.optJSONArray("scores").ints()
    LaunchedEffect(phase, scores) {
        if (active && state.optString("notice").isNotBlank() || phase == "finished") {
            if (haptics) feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            tone?.startTone(if (phase == "finished") ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_BEEP, 100)
        }
    }
    Scaffold(containerColor = GameInk, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ if (selected != null && !showMatch) selected = null else close() }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = GameWhite)
                }
                Text(if (showMatch) game.title else selected?.title ?: "Games", Modifier.weight(1f),
                    color = GameWhite, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                if (room.connectionSlow == true || !room.connected) Text(if (room.connected) "Slow connection" else "Reconnecting",
                    color = GameGold, style = MaterialTheme.typography.labelMedium)
                if (showMatch) IconButton({ opts = true }) { Icon(Icons.Outlined.MoreVert, "Game options", tint = GameWhite) }
            }
            when {
                !showMatch && selected == null -> GamesCatalog(Modifier.weight(1f), onOpen = { selected = it })
                !showMatch -> GameLobby(requireNotNull(selected), emptyList(), state, mySide, Modifier.weight(1f),
                    if (!available) "Games requires the updated Together server." else "Invite your connected partner.",
                    if (room.connected && available && room.members.count { it.online } == 2) "Send invitation" else null,
                    onAction = { games.command("invite", JSONObject().put("kind", selected?.wire)) })
                phase in setOf("inviting", "lobby") -> {
                    val accepted = phase == "lobby"
                    val own = state.optString("inviter") == room.member
                    val ready = state.optJSONArray("ready").strings()
                    val bothReady = memberIds.size == 2 && ready.containsAll(memberIds)
                    val action = when {
                        !accepted && own -> "Cancel invitation"
                        !accepted -> "Accept invitation"
                        bothReady -> "Start game"
                        room.member in ready -> "Mark as not ready"
                        else -> "I’m ready"
                    }
                    GameLobby(game, players, state, mySide, Modifier.weight(1f),
                        if (accepted) "Both players must be ready to start." else if (own) "Waiting for your partner." else "Your partner invited you to play.",
                        action.takeIf { room.connected },
                        onAction = {
                            when {
                                !accepted && own -> games.command("cancel")
                                !accepted -> games.command("accept")
                                bothReady -> games.command("start")
                                else -> games.command("ready", JSONObject().put("enabled", room.member !in ready))
                            }
                        },
                        secondary = if (accepted) "Close lobby" else if (!own) "Decline" else null,
                        onSecondary = {
                            if (accepted) confirm = true else { games.command("decline"); selected = null; close() }
                        })
                }
                phase == "finished" || phase == "abandoned" -> {
                    val winner = state.optInt("winner", -1)
                    val name = players.getOrNull(winner)?.name
                    GameLobby(game, players, state, mySide, Modifier.weight(1f),
                        when { winner == mySide && winner >= 0 -> "You won"; winner >= 0 -> "${name ?: "Partner"} wins"
                            phase == "abandoned" -> "Match closed"; else -> "Draw" },
                        if (room.connected) "Invite to rematch" else null,
                        onAction = { games.command("invite", JSONObject().put("kind", game.wire)) },
                        secondary = "Return to Together", onSecondary = {
                            oldResult = state.optString("id")
                            selected = null
                            close()
                        }, result = true)
                }
                else -> {
                    val turn = state.optInt("turn")
                    val readyToPlay = room.connected && phase == "active"
                    val pauseVotes = state.optJSONArray("pauseVotes").strings()
                    if (hud) GameScoreboard(game, players, scores, turn, state.optLong("deadline"),
                        phase, state.optBoolean("overtime"), reaction, session::serverNow)
                    if (pauseVotes.isNotEmpty() || phase == "paused") {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (phase == "paused") "Match paused" else "Pause requested", color = GameGold)
                            TextButton({
                                games.command(if (phase == "paused") "resume" else if (room.member in pauseVotes) "pauseCancel" else "pause")
                            }, enabled = room.connected && (phase != "paused" || room.member !in state.optJSONArray("resumeVotes").strings())) {
                                Text(if (phase == "paused") "Vote to resume" else if (room.member in pauseVotes) "Cancel request" else "Accept pause")
                            }
                        }
                    }
                    if (previewVideo && calling.active && (players.any { it?.id != room.member && it?.camera == true } ||
                            previewLocal && (calling.camera || calling.sharing))) {
                        GameVideoStrip(call, calling, players.firstOrNull { it?.id != room.member }, previewLocal,
                            previewX, previewY) { x, y -> previewX = x; previewY = y }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp)) {
                        GamePlay(game, state, remoteAim, mySide, readyToPlay,
                            games::command, session::serverNow, modifier = Modifier.fillMaxSize(),
                            toggleHud = { hud = !hud })
                        androidx.compose.animation.AnimatedVisibility(notice != null, Modifier.align(Alignment.TopCenter)) {
                            Surface(color = GameInk.copy(alpha = .92f), shape = CircleShape) {
                                Text(notice.orEmpty(), Modifier.padding(horizontal = 11.dp, vertical = 5.dp), color = GameGold,
                                    style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
            if (chat) {
                val scroll = rememberLazyListState()
                val messages = room.chat.takeLast(20)
                LaunchedEffect(messages.lastOrNull()?.id) {
                    if (messages.isNotEmpty()) scroll.scrollToItem(messages.lastIndex)
                }
                Surface(color = GameSheet, shape = RoundedCornerShape(15.dp), modifier = Modifier.padding(horizontal = 12.dp)) {
                    Column(Modifier.padding(8.dp)) {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 100.dp), state = scroll) {
                            items(messages, key = { it.id }) { entry ->
                                Text("${if (entry.sender == room.member) "You" else entry.name} · ${entry.text}",
                                    Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = GameWhite)
                            }
                        }
                        fun sendChat() {
                            if (message.isNotBlank() && session.send("chat", JSONObject().put("text", message.trim()))) message = ""
                        }
                        OutlinedTextField(message, { message = it.take(2000) }, Modifier.fillMaxWidth(), singleLine = true,
                            placeholder = { Text("Message") }, keyboardActions = KeyboardActions(onSend = { sendChat() }),
                            trailingIcon = {
                                IconButton(::sendChat, enabled = room.connected && message.isNotBlank()) {
                                    Icon(Icons.AutoMirrored.Outlined.Send, "Send message")
                                }
                            })
                    }
                }
            } else if (active && hud && room.incomingChat != null) {
                val incoming = room.incomingChat
                var visible by remember { mutableStateOf(false) }
                LaunchedEffect(incoming?.id) {
                    visible = incoming != null && session.serverNow() - incoming.time < 3000
                    delay(2500)
                    visible = false
                }
                if (visible && incoming != null) Text("${incoming.name} · ${incoming.text}",
                    Modifier.fillMaxWidth().padding(horizontal = 15.dp), color = GameSlate,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
            }
            if (hud || !active || chat) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 5.dp), horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton({
                        if (calling.active) call.microphone(!calling.mic) else startVoice()
                    }, enabled = room.connected) {
                        Icon(painterResource(if (calling.mic) R.drawable.ic_mic else R.drawable.ic_mic_off),
                            if (!calling.active) "Join voice" else if (calling.mic) "Mute microphone" else "Unmute microphone",
                            tint = if (calling.mic) GameGold else GameSlate)
                    }
                    IconButton(toggleCamera, enabled = calling.active) {
                        Icon(painterResource(if (calling.camera) R.drawable.ic_camera else R.drawable.ic_camera_off),
                            if (calling.camera) "Turn camera off" else "Turn camera on", tint = GameWhite)
                    }
                    IconButton({ chat = !chat }) {
                        Icon(painterResource(R.drawable.ic_chat), if (chat) "Close chat" else "Open chat",
                            tint = if (chat) GameGold else GameWhite)
                    }
                    Box {
                        TextButton({ reactionMenu = true }, enabled = room.connected) { Text("React", color = GameGold) }
                        DropdownMenu(reactionMenu, { reactionMenu = false }) {
                            emoji.forEach { (key, glyph) ->
                                DropdownMenuItem({ Text("$glyph  ${key.replaceFirstChar { it.uppercase() }}") }, {
                                    val now = SystemClock.elapsedRealtime()
                                    if (now - lastReaction >= 850 && session.send("reaction", JSONObject().put("emoji", key))) lastReaction = now
                                    reactionMenu = false
                                })
                            }
                        }
                    }
                    IconButton(expandCall, enabled = calling.active && room.members.any { it.id != room.member && it.call }) {
                        Icon(painterResource(R.drawable.ic_room_fullscreen), "Expand video call", tint = GameWhite)
                    }
                }
            }
        }
    }
    if (opts) ModalBottomSheet({ opts = false }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 19.dp, vertical = 7.dp)) {
            Text("Game options", style = MaterialTheme.typography.titleLarge)
            Text("Game sound", Modifier.padding(top = 12.dp))
            Slider(sound, { sound = it }, onValueChangeFinished = { settings.edit().putFloat("sound", sound).apply() })
            GameSetting("Vibration", haptics) { haptics = it; settings.edit().putBoolean("haptics", it).apply() }
            GameSetting("Show reactions", showReactions) { showReactions = it; settings.edit().putBoolean("reactions", it).apply() }
            GameSetting("Video previews", previewVideo) { previewVideo = it; settings.edit().putBoolean("video", it).apply() }
            GameSetting("Self preview", previewLocal) { previewLocal = it; settings.edit().putBoolean("self", it).apply() }
            if (calling.active) Row {
                TextButton({ opts = false; callAudio() }) { Text("Call audio devices") }
                TextButton({
                    context.getSystemService(AudioManager::class.java)
                        .adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
                }) { Text("Call volume") }
            }
            if (phase == "active") TextButton({ games.command("pause"); opts = false }, enabled = room.connected) { Text("Request pause") }
            if (phase == "paused") TextButton({ games.command("resume"); opts = false }, enabled = room.connected) { Text("Vote to resume") }
            if (phase in setOf("lobby", "active", "paused", "resolving")) TextButton({
                opts = false
                confirm = true
            }, enabled = room.connected) { Text(if (phase == "lobby") "Close lobby" else "Forfeit game", color = MaterialTheme.colorScheme.error) }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text(if (phase == "lobby") "Close this lobby?" else "Forfeit this match?") },
        text = { Text("Your Together call stays connected.") },
        confirmButton = { TextButton({ games.command("forfeit"); confirm = false }) { Text("Confirm") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Keep playing") } })
}

@Composable
private fun GameSetting(label: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Switch(value, change)
    }
}

@Composable
private fun GamesCatalog(modifier: Modifier, onOpen: (TogetherGame) -> Unit) {
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Play something together", color = GameSlate, style = MaterialTheme.typography.titleMedium) }
        item {
            val featured = TogetherGame.POOL
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(GameSheet).clickable { onOpen(featured) }) {
                GameArtwork(featured, Modifier.fillMaxWidth().height(156.dp))
                Text(featured.title, Modifier.padding(start = 14.dp, top = 10.dp), color = GameWhite, style = MaterialTheme.typography.titleLarge)
                Text("Turn-based · ${featured.description}", Modifier.padding(start = 14.dp, bottom = 12.dp), color = GameSlate,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        items(TogetherGame.entries.filterNot { it == TogetherGame.POOL }, key = { it.wire }) { game ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpen(game) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GameArtwork(game, Modifier.size(98.dp, 63.dp).clip(RoundedCornerShape(11.dp)))
                Column {
                    Text(game.title, color = GameWhite, style = MaterialTheme.typography.titleMedium)
                    Text(if (game.realTime) "Real time" else "Turn-based", color = GameGold, style = MaterialTheme.typography.labelSmall)
                    Text(game.description, color = GameSlate, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun GameLobby(
    game: TogetherGame, players: List<TogetherMember?>, state: JSONObject, mySide: Int, modifier: Modifier,
    status: String, action: String?, onAction: () -> Unit, secondary: String? = null, onSecondary: () -> Unit = {},
    result: Boolean = false,
) {
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { GameArtwork(game, Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(18.dp))) }
        item {
            Text(status, color = if (result) GameGold else GameWhite, style = MaterialTheme.typography.headlineSmall)
            if (result) Text(state.optString("notice"), color = GameSlate)
        }
        if (players.isNotEmpty()) item {
            val ready = state.optJSONArray("ready").strings()
            val scores = state.optJSONArray("scores").ints()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                players.forEachIndexed { i, member ->
                    Column(Modifier.weight(1f).background(GameSheet, RoundedCornerShape(12.dp)).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        GameAvatar(member?.name, i, false)
                        Text(member?.name ?: if (i == mySide) "You" else "Partner", color = GameWhite, maxLines = 1)
                        Text(if (result) "${scores.getOrElse(i) { 0 }} ${scoreLabel(game)}" else
                            if (member?.id in ready) "Ready" else "Waiting", color = GameGold,
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        if (!result) item { Text(game.rules, color = GameSlate, style = MaterialTheme.typography.bodyMedium) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                action?.let { Button(onAction) { Text(it) } }
                secondary?.let { TextButton(onSecondary) { Text(it) } }
            }
        }
    }
}

private fun scoreLabel(game: TogetherGame) = when (game) {
    TogetherGame.POOL -> "balls"
    TogetherGame.GOLF -> "strokes"
    TogetherGame.ARCHERY -> "points"
    TogetherGame.TANK -> "HP"
    TogetherGame.SOCCER -> "goals"
}

@Composable
private fun GameAvatar(name: String?, index: Int, active: Boolean) {
    val tint = if (index == 0) GameGold else GameBlue
    val color by animateColorAsState(tint.copy(alpha = if (active) .34f else .15f), label = "Active player")
    Box(Modifier.size(32.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
        Text(name.orEmpty().trim().take(1).uppercase().ifBlank { "${index + 1}" }, color = tint,
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun GameScoreboard(game: TogetherGame, players: List<TogetherMember?>, scores: List<Int>, turn: Int,
                           deadline: Long, phase: String, overtime: Boolean, reaction: String?, now: () -> Long) {
    var seconds by remember(deadline) { mutableLongStateOf(0) }
    LaunchedEffect(deadline) {
        while (deadline > 0) {
            val remaining = (deadline - now()).coerceAtLeast(0)
            seconds = (remaining + 999) / 1000
            delay((remaining % 1_000L).coerceAtLeast(80L))
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (0..1).forEach { i ->
            if (i == 1) Column(Modifier.width(46.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (phase == "paused") "Ⅱ" else if (phase == "resolving") "•••" else durationLabel(seconds),
                    color = if (seconds <= 10 && phase == "active") GameGold else GameWhite, fontWeight = FontWeight.Bold)
                if (overtime) Text("OT", color = GameGold, style = MaterialTheme.typography.labelSmall)
            }
            val name = players.getOrNull(i)?.name
            val selected = turn == i && !game.realTime && phase == "active"
            val outline by animateColorAsState(if (selected) GameGold else GameLine, label = "Turn outline")
            Row(Modifier.weight(1f).border(1.dp, outline, RoundedCornerShape(12.dp)).padding(6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                GameAvatar(name, i, selected)
                Column(Modifier.weight(1f)) {
                    Text(name ?: "Player ${i + 1}", color = GameWhite, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium)
                    Text("${scores.getOrElse(i) { 0 }} ${scoreLabel(game)}", color = GameSlate, style = MaterialTheme.typography.labelSmall)
                }
                if (i == 0 && reaction != null) Text(reaction, color = GameGold, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun GameVideoStrip(call: TogetherCall, calling: TogetherCallState, peer: TogetherMember?, local: Boolean,
                           x: Float, y: Float, onMove: (Float, Float) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(74.dp).padding(horizontal = 12.dp)) {
        val width = minOf(maxWidth, 154.dp)
        val density = LocalDensity.current
        val rangeX = with(density) { (maxWidth - width).toPx().coerceAtLeast(1f) }
        val rangeY = with(density) { 12.dp.toPx() }
        val current by rememberUpdatedState(onMove)
        val px by rememberUpdatedState(x)
        val py by rememberUpdatedState(y)
        Row(Modifier.offset { IntOffset((rangeX * x).roundToInt(), (rangeY * y).roundToInt()) }
            .width(width).height(62.dp).pointerInput(rangeX, rangeY) {
                detectDragGestures { event, delta ->
                    event.consume()
                    current((px + delta.x / rangeX).coerceIn(0f, 1f), (py + delta.y / rangeY).coerceIn(0f, 1f))
                }
            }, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (peer?.camera == true) CameraTile(peer.name, calling.remoteVideo, peer.online, call, false,
                Modifier.weight(1f).fillMaxHeight(), showLabel = false)
            if (local && (calling.camera || calling.sharing)) CameraTile("You", calling.localVideo, true, call, !calling.sharing,
                Modifier.weight(1f).fillMaxHeight(), showLabel = false)
        }
    }
}
