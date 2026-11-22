package com.codetic.saoreo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.security.MessageDigest

class TogetherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val initialInvite = intent.data?.takeIf {
            it.scheme == "saoreo" && it.host == "together" ||
                it.scheme == "https" && it.host == "szaibdev.duckdns.org" && it.port == 8765 && it.path == "/together/join"
        }?.getQueryParameter("invite").orEmpty()
        setContent {
            val app = application as SaoreoApp
            val settings by app.preferences.flow.collectAsStateWithLifecycle(Settings())
            val session: TogetherSession = viewModel()
            AppTheme(settings) { TogetherScreen(session, initialInvite, ::finish) }
        }
    }
}

private val reactions = linkedMapOf("heart" to "\u2764\ufe0f", "laugh" to "\ud83d\ude02", "wow" to "\ud83d\ude2e", "clap" to "\ud83d\udc4f", "fire" to "\ud83d\udd25", "kiss" to "\ud83d\ude18")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TogetherScreen(session: TogetherSession, initialInvite: String = "", finish: () -> Unit) {
    val room by session.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current
    val call = remember(session) { TogetherCall(context.applicationContext, session) }
    val calling by call.state.collectAsStateWithLifecycle()
    val playback = remember(session) { TogetherPlayback(session) }
    var showLeave by remember { mutableStateOf(false) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var callView by rememberSaveable { mutableStateOf(false) }
    var callChat by rememberSaveable { mutableStateOf(false) }
    var partnerLeft by rememberSaveable { mutableStateOf(false) }
    var partnerSeen by rememberSaveable { mutableStateOf(false) }
    var partnerCallSeen by rememberSaveable { mutableStateOf(false) }
    val peer = room.members.firstOrNull { it.id != room.member }
    LaunchedEffect(room.room, room.connected, peer?.online, peer?.call) {
        if (room.room.isEmpty()) {
            partnerLeft = false
            partnerSeen = false
            partnerCallSeen = false
            callView = false
            fullscreen = false
        } else if (room.connected) {
            val online = peer?.online == true
            if (partnerSeen && !online || partnerCallSeen && peer?.call != true && calling.active) {
                partnerLeft = true
                if (room.wanted) session.playback("pause")
            }
            if (online && !partnerSeen || peer?.call == true && !partnerCallSeen) partnerLeft = false
            partnerSeen = online
            partnerCallSeen = peer?.call == true
        }
    }
    var browseYouTube by rememberSaveable { mutableStateOf(false) }
    var showNetflix by remember { mutableStateOf(false) }
    val activityContext = context as? ComponentActivity
    DisposableEffect(fullscreen, activityContext) {
        val window = activityContext?.window
        val insets = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
        if (fullscreen) {
            insets?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (activityContext != null) activityContext.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            insets?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (fullscreen) {
                insets?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                if (activityContext != null) activityContext.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }
    var showMedia by remember { mutableStateOf(false) }
    var showOutput by remember { mutableStateOf(false) }
    var showRoomOptions by remember { mutableStateOf(false) }
    var showPrivateSource by remember { mutableStateOf(false) }
    var queueNew by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf("Chat") }
    var message by rememberSaveable { mutableStateOf("") }
    var localSource by rememberSaveable(room.media?.id) { mutableStateOf<String?>(null) }
    var choosingMatch by remember { mutableStateOf(false) }
    var loadingFile by remember { mutableStateOf(false) }
    var uploadFraction by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(initialInvite) {
        if (initialInvite.isNotBlank() && room.room.isEmpty()) {
            val name = session.preferences.getString("name", null)?.takeIf { it.isNotBlank() } ?: "Guest"
            session.enter("https://szaibdev.duckdns.org:8765", name, invite = initialInvite)
        }
    }
    var pendingCall by remember { mutableStateOf("voice") }
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val keyboard = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(room.chat.lastOrNull()?.id, keyboard, tab) {
        if (tab == "Chat" && (room.chat.isNotEmpty() || keyboard)) {
            delay(150); val count = listState.layoutInfo.totalItemsCount
            if (count > 0) listState.animateScrollToItem(count - 1)
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val camera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!mic) session.error("Microphone permission is needed to join the call")
        else if (pendingCall == "camera") { if (camera) call.camera(true) else session.error("Allow camera access to turn video on") }
        else if (pendingCall == "unmute") call.microphone(true)
        else call.start(pendingCall == "video" && camera)
    }
    fun startCall(video: Boolean) {
        pendingCall = if (video) "video" else "voice"
        permissions.launch(if (video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA, Manifest.permission.BLUETOOTH_CONNECT)
            else arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT))
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            loadingFile = true
            try {
                if (!choosingMatch) {
                    val title = withContext(Dispatchers.IO) {
                        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Shared video"
                    }
                    var lastPercent = -1
                    val id = session.shareVideo(context.contentResolver, uri) { sent, total ->
                        if (total > 0) {
                            val percent = (100 * sent / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                uploadFraction = percent / 100f
                            }
                        }
                    }
                    check(session.setMedia(TogetherMedia("", "upload", id, title, 0, false), queueNew)) {
                        "Reconnect to the room and choose the video again."
                    }
                    return@launch
                }
                val info = withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    val digest = MessageDigest.getInstance("SHA-256")
                    context.contentResolver.openInputStream(uri)!!.use { stream ->
                        val chunk = ByteArray(256 * 1024)
                        while (true) { val count = stream.read(chunk); if (count < 0) break; digest.update(chunk, 0, count) }
                    }
                    val hash = digest.digest().joinToString("") { "%02x".format(it) }
                    val title = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else "Local video"
                    } ?: "Local video"
                    hash to title
                }
                if (info.first != room.media?.source) session.error("These files do not match. Choose the exact same video on both phones.")
                else localSource = uri.toString()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { session.error(e.message ?: "Unable to share this video.") }
            finally { loadingFile = false; uploadFraction = null }
        }
    }
    LaunchedEffect(room.media?.id) {
        if (room.media?.kind == "local") localSource = session.preferences.getString("local:${room.media?.source}", null)
    }
    LaunchedEffect(room.revision) { call.members(room) }
    LaunchedEffect(room.error, calling.error) {
        val error = room.error ?: calling.error
        if (error != null) { snackbar.showSnackbar(error); session.clearError() }
    }
    DisposableEffect(lifecycle, call) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                if (session.state.value.connected) { session.playback("pause"); session.status(false) }
                playback.remote { playback.play?.invoke(false) }; playback.lastStatus = null
                call.stop()
            }
            if (event == Lifecycle.Event.ON_START) { playback.lastStatus = null; playback.report(); call.refreshOutput() }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); call.close() }
    }
    BackHandler(room.room.isNotEmpty() && !keyboard) {
        when {
            callView && callChat -> callChat = false
            callView -> callView = false
            fullscreen -> fullscreen = false
            else -> showLeave = true
        }
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        if (!fullscreen && !callView) Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ if (room.room.isNotEmpty()) showLeave = true else finish() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text("Together", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (room.room.isNotEmpty()) Text(if (room.connected) "Private room \u00b7 ${room.members.count { it.online }}/2 here" else "Reconnecting\u2026",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (room.room.isNotEmpty()) {
                IconButton({
                    val invite = session.inviteLink()
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, invite), "Invite your partner"))
                }) { Icon(Icons.Outlined.Share, "Invite partner") }
                IconButton({ showRoomOptions = true }) { Icon(Icons.Outlined.MoreVert, "Room settings") }
            }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            if (room.room.isEmpty()) TogetherLobby(session, initialInvite)
            else Column(Modifier.fillMaxSize()) {
                val media = room.media
                val canControl = room.canStartPlayback && playback.ready
                if (media != null) {
                    val stage = when {
                        callView -> Modifier.fillMaxWidth().height(if (keyboard) 1.dp else 110.dp)
                        fullscreen -> Modifier.fillMaxWidth().weight(1f)
                        keyboard -> Modifier.fillMaxWidth().height(112.dp)
                        else -> Modifier.fillMaxWidth().height(210.dp)
                    }
                    Row(stage, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(if (fullscreen) 12.dp else 20.dp))) {
                            TogetherVideo(context, room, session, playback, localSource, Modifier.fillMaxSize())
                        }
                        if (fullscreen && calling.active) Column(Modifier.width(150.dp).fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CameraTile(peer?.name ?: "Partner", calling.remoteVideo, peer?.camera == true && peer.online,
                                call, false, Modifier.fillMaxWidth().weight(1f))
                            CameraTile("You", calling.localVideo, calling.camera, call, true, Modifier.fillMaxWidth().weight(1f))
                        }
                    }
                    if (!callView && (!keyboard || fullscreen)) {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) { Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                            if (!fullscreen) {
                                Text(media.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                playback.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                                if (media.kind == "local" && localSource == null) {
                                    TextButton({ choosingMatch = true; picker.launch(arrayOf("video/*")) }) { Text("Choose matching video") }
                                }
                            }
                            TogetherSeekBar(playback.position, playback.duration, canControl && !playback.live) {
                                session.playback("seek", it)
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                IconButton({ session.playback("seek", (playback.position - 10000).coerceAtLeast(0)) }, enabled = canControl && !playback.live) {
                                    Icon(painterResource(R.drawable.ic_back_ten), "Back 10 seconds")
                                }
                                FilledIconButton({ session.playback(if (room.wanted) "pause" else "play") },
                                    enabled = room.connected && (room.wanted || canControl),
                                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Lime, contentColor = Ink)) {
                                    Icon(painterResource(if (room.wanted) R.drawable.ic_media_pause else R.drawable.ic_media_play),
                                        if (room.wanted) "Pause both" else "Play both")
                                }
                                IconButton({ session.playback("seek", playback.position + 10000) }, enabled = canControl && !playback.live) {
                                    Icon(painterResource(R.drawable.ic_forward_ten), "Forward 10 seconds")
                                }
                                Spacer(Modifier.weight(1f))
                                if (calling.active && !fullscreen) IconButton({ callView = true }) {
                                    Icon(painterResource(R.drawable.ic_camera), "Open full call")
                                }
                                if (!fullscreen) IconButton({ queueNew = false; showMedia = true }) { Icon(Icons.Outlined.Add, "Change video") }
                                IconButton({ fullscreen = !fullscreen }) {
                                    Icon(painterResource(if (fullscreen) R.drawable.ic_room_exit_fullscreen else R.drawable.ic_room_fullscreen),
                                        if (fullscreen) "Exit fullscreen" else "Fullscreen")
                                }
                            }
                            val status = when {
                                !room.connected -> "Reconnecting"
                                room.members.count { it.online } < 2 -> "Waiting for your partner"
                                room.members.any { !it.ready } -> "Waiting for both players"
                                room.wait && room.members.any { it.buffering } -> "Waiting for buffering"
                                room.timeline.playing -> "Playing together"
                                else -> "Both ready"
                            }
                            if (!fullscreen || !canControl) Text(status, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } }
                    }
                } else if (!callView) {
                    Button({ queueNew = false; showMedia = true }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Icon(Icons.Outlined.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Choose what to watch")
                    }
                }
                if (callView) TogetherCallView(
                    room, calling, call, message, { message = it.take(2000) },
                    { if (message.isNotBlank() && session.send("chat", JSONObject().put("text", message.trim()))) message = "" },
                    callChat, { callChat = !callChat }, { callView = false; callChat = false },
                    { call.stop(); callView = false; callChat = false },
                    {
                        if (calling.camera) call.camera(false)
                        else {
                            pendingCall = "camera"
                            permissions.launch(arrayOf(Manifest.permission.CAMERA))
                        }
                    }, Modifier.weight(1f),
                )
                if (!fullscreen && !callView) LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState,
                    contentPadding = PaddingValues(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            AnimatedVisibility(calling.active && !keyboard) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val peer = room.members.firstOrNull { it.id != room.member }
                                    CameraTile(peer?.name ?: "Your partner", calling.remoteVideo, peer?.camera == true && peer.online,
                                        call, false, Modifier.weight(1f).aspectRatio(0.85f))
                                    CameraTile("You", calling.localVideo, calling.camera, call, true, Modifier.weight(1f).aspectRatio(0.85f))
                                }
                            }
                            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceEvenly) {
                                    if (!calling.active) {
                                        TextButton({ startCall(false) }, enabled = room.connected) { Icon(painterResource(R.drawable.ic_call), null); Spacer(Modifier.width(5.dp)); Text("Voice") }
                                        TextButton({ startCall(true) }, enabled = room.connected) { Icon(painterResource(R.drawable.ic_camera), null); Spacer(Modifier.width(5.dp)); Text("Video") }
                                    } else {
                                        IconButton({ call.microphone(!calling.mic) }) { Icon(painterResource(if (calling.mic) R.drawable.ic_mic else R.drawable.ic_mic_off), if (calling.mic) "Mute microphone" else "Unmute microphone", tint = if (calling.mic) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error) }
                                        IconButton({
                                            if (calling.camera) call.camera(false)
                                            else { pendingCall = "camera"; permissions.launch(arrayOf(Manifest.permission.CAMERA)) }
                                        }) { Icon(painterResource(if (calling.camera) R.drawable.ic_camera else R.drawable.ic_camera_off), if (calling.camera) "Turn camera off" else "Turn camera on") }
                                        IconButton({ call.flip() }, enabled = calling.camera) { Icon(Icons.Outlined.Refresh, "Switch camera") }
                                        IconButton({ showOutput = true }) { Icon(painterResource(R.drawable.ic_speaker), "Audio output") }
                                        FilledTonalIconButton({ call.stop() }, colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                                            Icon(painterResource(R.drawable.ic_call_end), "End call")
                                        }
                                    }
                                }
                            }
                            if (calling.active) TextButton({ callView = true }) { Text("Full call") }
                            if (calling.active) Text("${calling.connection} \u00b7 ${calling.output} \u00b7 foreground call", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                reactions.forEach { (key, glyph) -> TextButton({ session.send("reaction", JSONObject().put("emoji", key)) }, enabled = room.connected,
                                    contentPadding = PaddingValues(0.dp), modifier = Modifier.size(44.dp)) { Text(glyph, fontSize = 23.sp) } }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FilterChip(tab == "Chat", { tab = "Chat" }, { Text("Chat") }, leadingIcon = { Icon(painterResource(R.drawable.ic_chat), null, Modifier.size(17.dp)) })
                                Spacer(Modifier.width(8.dp))
                                FilterChip(tab == "Queue", { tab = "Queue" }, { Text("Queue \u00b7 ${room.queue.size}") })
                                Spacer(Modifier.weight(1f))
                                if (tab == "Queue") IconButton({ queueNew = true; showMedia = true }) { Icon(Icons.Outlined.Add, "Add to shared queue") }
                            }
                        }
                    }
                    if (tab == "Chat") {
                        if (room.chat.isEmpty()) item { Text("Say hello. This conversation lasts only for this room.", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(room.chat.takeLast(30), key = { it.id }) { item ->
                            val mine = item.sender == room.member
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                                Surface(shape = RoundedCornerShape(18.dp), color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier.widthIn(max = 320.dp)) {
                                    Column(Modifier.padding(horizontal = 13.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(if (mine) "You" else item.name, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                        Text(item.text, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    } else {
                        if (room.queue.isEmpty()) item { Text("Pick what comes next.", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall) }
                        items(room.queue, key = { it.id }) { item ->
                            ListItem(headlineContent = { Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text(item.kind.uppercase()) },
                                leadingContent = { IconButton({ session.send("queueSelect", JSONObject().put("id", item.id)) }) { Icon(Icons.Outlined.PlayArrow, "Watch ${item.title}") } },
                                trailingContent = { IconButton({ session.send("queueRemove", JSONObject().put("id", item.id)) }) { Icon(Icons.Outlined.Close, "Remove from queue") } })
                        }
                    }
                }
                if (loadingFile) {
                    val fraction = uploadFraction
                    val bar = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    if (fraction == null) LinearProgressIndicator(bar)
                    else LinearProgressIndicator(progress = { fraction }, modifier = bar)
                    Text("Sharing video with your room", Modifier.padding(12.dp), style = MaterialTheme.typography.labelSmall)
                }
                if (!fullscreen && !callView && tab == "Chat") OutlinedTextField(
                    value = message,
                    onValueChange = { message = it.take(2000) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Message") },
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 3,
                    trailingIcon = {
                        IconButton({
                            if (session.send("chat", JSONObject().put("text", message.trim()))) message = ""
                        }, enabled = room.connected && message.isNotBlank()) {
                            Icon(Icons.AutoMirrored.Outlined.Send, "Send message")
                        }
                    },
                )
            }
            if (partnerLeft) Surface(Modifier.fillMaxSize(), color = Color.White, contentColor = Ink) {
                Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Icon(painterResource(R.drawable.ic_together), null, Modifier.size(52.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("Your partner left", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("You can wait here or leave the room.", Modifier.padding(vertical = 12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton({ partnerLeft = false; call.stop(); session.leave() }) { Text("Quit", color = Ink) }
                        Button({ partnerLeft = false }, colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink)) { Text("Wait") }
                    }
                }
            }
            var visibleReaction by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(room.reaction) { room.reaction?.let { visibleReaction = reactions[it.emoji]; delay(1700); visibleReaction = null } }
            AnimatedVisibility(visibleReaction != null, Modifier.align(Alignment.Center), enter = fadeIn() + slideInVertically { it }, exit = fadeOut(tween(400)) + slideOutVertically { -it }) {
                Text(visibleReaction.orEmpty(), fontSize = 84.sp)
            }
        }
    }
    if (showLeave) AlertDialog(onDismissRequest = { showLeave = false }, title = { Text("Leave your room?") }, text = { Text("Playback pauses for your partner and your call ends.") },
        confirmButton = { TextButton({ call.stop(); session.leave(); showLeave = false }) { Text("Leave room") } }, dismissButton = { TextButton({ showLeave = false }) { Text("Stay") } })
    if (showMedia) MediaChooser(
        onDismiss = { showMedia = false },
        onYouTube = { showMedia = false; browseYouTube = true },
        onNetflix = { showMedia = false; showNetflix = true },
        onPickFile = { choosingMatch = false; showMedia = false; picker.launch(arrayOf("video/mp4")) },
        onChoose = { item -> session.setMedia(item, queueNew); showMedia = false },
    )
    if (browseYouTube) TogetherYouTubeBrowser(onDismiss = { browseYouTube = false }) { id, live ->
        val queued = session.setMedia(TogetherMedia("", "youtube", id, "YouTube video", 0, live), queueNew)
        if (queued) browseYouTube = false
        else {
            browseYouTube = false
            session.error("Reconnect to your room before choosing a video.")
        }
        queued
    }
    if (showNetflix) AlertDialog(onDismissRequest = { showNetflix = false }, title = { Text("Netflix") },
        text = { Text("Netflix playback can be controlled from the Netflix app. Shared playback controls are unavailable for Netflix.") },
        confirmButton = { TextButton({
            showNetflix = false
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.netflix.com/browse"))) }
                .onFailure { session.error("Netflix could not be opened.") }
        }) { Text("Open Netflix") } },
        dismissButton = { TextButton({ showNetflix = false }) { Text("Back") } })
    if (showOutput) ModalBottomSheet({ showOutput = false }) {
        Text("Audio output", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
        call.devices().forEach { device -> TextButton({ call.output(device); showOutput = false }, Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { Text(TogetherCall.outputName(device)) } }
        Spacer(Modifier.navigationBarsPadding().height(16.dp))
    }
    if (showRoomOptions) ModalBottomSheet({ showRoomOptions = false }) {
        Text("Your room", Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.titleLarge)
        ListItem(headlineContent = { Text("Wait when a player buffers") }, supportingContent = { Text("Keep the moment together") },
            trailingContent = { Switch(room.wait, { session.send("wait", JSONObject().put("enabled", it)) }) })
        if (room.media?.kind == "stream") TextButton({ showRoomOptions = false; showPrivateSource = true }, Modifier.padding(horizontal = 16.dp)) { Text("Set this phone's private stream URL") }
        if (room.members.firstOrNull { it.id == room.member }?.ordinal == 0) TextButton({ session.send("resetPartner"); showRoomOptions = false }, Modifier.padding(horizontal = 16.dp)) { Text("Reset partner slot and invitation") }
        Text("Calls stop when you leave this screen. Netflix app control is not supported. Live IPTV timing depends on the provider.", Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.navigationBarsPadding().height(16.dp))
    }
    if (showPrivateSource) {
        var url by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showPrivateSource = false }, title = { Text("Private stream source") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Use your own authorized URL for the same program. This URL stays on this phone.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(url, { url = it }, label = { Text("HTTPS stream URL") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            }
        }, confirmButton = { TextButton({ if (Uri.parse(url).scheme == "https") { localSource = url; showPrivateSource = false } else session.error("Use an HTTPS stream URL") }) { Text("Use source") } },
            dismissButton = { TextButton({ showPrivateSource = false }) { Text("Cancel") } })
    }
}

@Composable
private fun TogetherLobby(session: TogetherSession, invitation: String) {
    val state by session.state.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf(session.preferences.getString("name", "").orEmpty()) }
    var server by rememberSaveable { mutableStateOf(session.preferences.getString("server", "https://szaibdev.duckdns.org:8765").orEmpty()) }
    var key by rememberSaveable { mutableStateOf(session.preferences.getString("access", "").orEmpty()) }
    var invite by rememberSaveable { mutableStateOf(invitation) }
    var joining by rememberSaveable { mutableStateOf(invitation.isNotEmpty()) }
    var settings by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(painterResource(R.drawable.ic_together), null, Modifier.size(42.dp))
                Column { Text("A little closer.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Your video. Your people. One moment.", style = MaterialTheme.typography.bodySmall) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!joining, { joining = false }, { Text("Create a room") })
            FilterChip(joining, { joining = true }, { Text("Join your partner") })
        }
        OutlinedTextField(name, { name = it.take(32) }, Modifier.fillMaxWidth(), label = { Text("Your name") }, singleLine = true, shape = RoundedCornerShape(18.dp))
        if (joining) OutlinedTextField(invite, { invite = it }, Modifier.fillMaxWidth(), label = { Text("Invitation code or message") }, maxLines = 4, shape = RoundedCornerShape(18.dp), trailingIcon = {
            IconButton({ invite = clipboard.getText()?.text.orEmpty() }) { Icon(Icons.Outlined.ContentPaste, "Paste invitation") }
        }) else OutlinedTextField(key, { key = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Private server access key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(18.dp), supportingText = { Text("Only the room creator needs this key.") })
        TextButton({ settings = !settings }) { Icon(Icons.Outlined.Settings, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Server connection") }
        AnimatedVisibility(settings) {
            OutlinedTextField(server, { server = it }, Modifier.fillMaxWidth(), label = { Text("HTTPS server address") }, singleLine = true,
                shape = RoundedCornerShape(18.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        }
        Button({
            if (!joining) session.preferences.edit().putString("access", key).apply()
            session.enter(server, name, if (joining) null else key, if (joining) invite else null)
        }, enabled = !state.connecting && name.isNotBlank() && (if (joining) invite.isNotBlank() else key.isNotBlank()),
            modifier = Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink)) {
            if (state.connecting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Ink)
            else { Icon(painterResource(R.drawable.ic_together), null); Spacer(Modifier.width(8.dp)); Text(if (joining) "Join the moment" else "Create private room") }
        }
        Text("Two people. Independent playback. Optional voice or video call. Camera and microphone stay off until you choose to join the call.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaChooser(onDismiss: () -> Unit, onYouTube: () -> Unit, onNetflix: () -> Unit,
    onPickFile: () -> Unit, onChoose: (TogetherMedia) -> Unit) {
    var showStream by rememberSaveable { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var live by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Choose a source", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (!showStream) {
                SourceOption("YouTube", "Browse and tap a video", onYouTube)
                SourceOption("Netflix", "Open Netflix separately", onNetflix)
                SourceOption("Local video", "Share an MP4 under 100 MiB with your room", onPickFile)
                TextButton({ showStream = true }) { Text("Direct stream or IPTV") }
                return@Column
            }
            TextButton({ showStream = false }) { Text("All sources") }
            Text("YouTube or a public HTTPS video/HLS/DASH link. Each phone streams independently.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(input, { input = it; error = null }, Modifier.fillMaxWidth(), label = { Text("Video link") }, maxLines = 3, shape = RoundedCornerShape(18.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(title, { title = it.take(150) }, Modifier.fillMaxWidth(), label = { Text("Title (optional)") }, singleLine = true, shape = RoundedCornerShape(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(live, { live = it }); Text("Live stream", style = MaterialTheme.typography.bodyMedium) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button({
                val youtube = TogetherSources.youtube(input)
                val uri = runCatching { java.net.URI(input.trim()) }.getOrNull()
                if (youtube != null) onChoose(TogetherMedia("", "youtube", youtube, title.ifBlank { "YouTube video" }, 0, live))
                else if (uri?.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && !uri.host.endsWith("netflix.com")) {
                    onChoose(TogetherMedia("", "stream", input.trim(), title.ifBlank { "Shared stream" }, 0, live))
                } else error = "Use a YouTube link or a direct HTTPS media URL. Netflix playback cannot be controlled here."
            }, Modifier.fillMaxWidth(), enabled = input.isNotBlank()) { Icon(Icons.Outlined.PlayArrow, null); Text("Select stream") }
            OutlinedButton(onPickFile, Modifier.fillMaxWidth()) { Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Choose a local video") }
            Text("For local videos, both people choose the exact same file. Private IPTV credentials can be entered separately on each phone after loading the program.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun CameraTile(name: String, track: VideoTrack?, enabled: Boolean, call: TogetherCall, mirror: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val renderer = remember(call) { SurfaceViewRenderer(context).apply {
        init(call.egl.eglBaseContext, null); setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT); setMirror(mirror)
        setEnableHardwareScaler(true); setZOrderMediaOverlay(true)
    } }
    DisposableEffect(renderer, track, enabled) {
        if (enabled) runCatching { track?.addSink(renderer) }
        onDispose { if (enabled) runCatching { track?.removeSink(renderer) } }
    }
    DisposableEffect(renderer) { onDispose { renderer.release() } }
    Box(modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        if (enabled && track != null) AndroidView(factory = { renderer }, modifier = Modifier.fillMaxSize())
        else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(painterResource(R.drawable.ic_camera_off), null, Modifier.size(25.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Camera off", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(Modifier.align(Alignment.BottomStart).padding(7.dp), color = Ink.copy(alpha = .75f), shape = RoundedCornerShape(8.dp)) {
            Text(name, Modifier.padding(horizontal = 7.dp, vertical = 3.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
    }
}
