package com.codetic.saoreo

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.OrientationEventListener
import android.view.Surface as DisplaySurface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.LocalActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

class TogetherActivity : ComponentActivity() {
    private val pairing get() = (application as SaoreoApp).pairing
    private var pipMode by mutableStateOf(false)
    private var currentSession: TogetherSession? = null
    private var initialInvite by mutableStateOf("")
    private var pairLink by mutableStateOf<PairLink?>(null)
    private var notificationAnswer by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        readLink(intent)
        setContent {
            val app = application as SaoreoApp
            val settings by app.preferences.flow.collectAsStateWithLifecycle(Settings())
            val session: TogetherSession = viewModel()
            LaunchedEffect(session) {
                currentSession = session
                if (initialInvite.isBlank() && pairLink == null && notificationAnswer == null && !intent.hasExtra(EXTRA_SHOW_RING)) {
                    session.restoreGame()
                }
            }
            AppTheme(settings) {
                if (pipMode) {
                    val calling by session.callController.state.collectAsState()
                    val room by session.state.collectAsState()
                    val partner = room.members.firstOrNull { it.id != room.member }
                    CameraTile(partner?.name ?: "Partner", calling.remoteVideo,
                        partner?.camera == true && partner.online, session.callController, false,
                        Modifier.fillMaxSize(), rounded = false, showLabel = false, clipVideo = true)
                } else TogetherScreen(session, initialInvite, ::finish, pairing, pairLink, { pairLink = null },
                    notificationAnswer, { notificationAnswer = null })
            }
        }
    }

    internal fun minimizeCall(): Boolean {
        if (currentSession?.callController?.state?.value?.active != true) return false
        return enterPictureInPictureMode(android.app.PictureInPictureParams.Builder()
            .setAspectRatio(android.util.Rational(9, 16)).build())
    }

    override fun onPictureInPictureModeChanged(inPictureInPictureMode: Boolean, config: Configuration) {
        super.onPictureInPictureModeChanged(inPictureInPictureMode, config)
        pipMode = inPictureInPictureMode
        currentSession?.callController?.background(inPictureInPictureMode)
    }

    override fun onUserLeaveHint() {
        if (!minimizeCall()) super.onUserLeaveHint()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readLink(intent)
    }

    override fun onResume() {
        super.onResume()
        pairing.visible(true)
    }

    override fun onPause() {
        pairing.visible(false)
        super.onPause()
    }

    private fun readLink(intent: Intent) {
        val ringing = (intent.getBooleanExtra(EXTRA_SHOW_RING, false) || intent.hasExtra(EXTRA_PAIR_ANSWER)) &&
            pairing.state.value.ring?.let { it.incoming && !it.silent } == true && togetherCallAlertsAllowed(this)
        if (ringing) { setShowWhenLocked(true); setTurnScreenOn(true) }
        val uri = intent.data?.let { data ->
            val secret = intent.getStringExtra("callSecret")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{43}")) }
            if (data.fragment == null && secret != null) data.buildUpon().fragment(secret).build() else data
        }
        pairLink = PairLink.parse(uri)
        initialInvite = uri?.takeIf {
            it.isHierarchical && (it.scheme == "saoreo" && it.host == "together" ||
                it.scheme == "https" && it.host == "szaibdev.duckdns.org" && it.port == 8765 && it.path == "/together/join")
        }?.let { if (it.scheme == "saoreo" && it.path == "/join") it.toString() else it.getQueryParameter("invite") }.orEmpty()
        notificationAnswer = intent.getStringExtra(EXTRA_PAIR_ANSWER)
    }
}

private val reactions = linkedMapOf("heart" to "\u2764\ufe0f", "laugh" to "\ud83d\ude02", "wow" to "\ud83d\ude2e", "clap" to "\ud83d\udc4f", "fire" to "\ud83d\udd25", "kiss" to "\ud83d\ude18")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TogetherScreen(session: TogetherSession, initialInvite: String, finish: () -> Unit,
    pairing: TogetherPairing, pairLink: PairLink?, closePairLink: () -> Unit,
    notificationAnswer: String?, clearNotificationAnswer: () -> Unit) {
    val room by session.state.collectAsStateWithLifecycle()
    val paired by pairing.state.collectAsStateWithLifecycle()
    var pastedPairLink by remember { mutableStateOf<PairLink?>(null) }
    val activePairLink = pairLink ?: pastedPairLink
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current
    val call = session.callController
    val calling by call.state.collectAsStateWithLifecycle()
    val gameState by session.games.state.collectAsStateWithLifecycle()
    var showGames by rememberSaveable(room.room) { mutableStateOf(false) }
    fun browseWithGame() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }
    val playback = remember(session) { TogetherPlayback(session) }
    var showLeave by remember { mutableStateOf(false) }
    var fullMovie by rememberSaveable { mutableStateOf(false) }
    val standalone = room.pairKind == PairKind.AUDIO || room.pairKind == PairKind.VIDEO
    val callView = standalone || room.callFullscreen && calling.active
    val fullscreen = fullMovie && !callView && room.media != null
    var expandedCamera by remember(fullscreen, callView) { mutableStateOf<String?>(null) }
    var movieControls by remember(fullscreen, room.media?.id) { mutableStateOf(false) }
    var movieControlsTouched by remember { mutableLongStateOf(0) }
    LaunchedEffect(fullscreen, movieControls, movieControlsTouched) {
        if (fullscreen && movieControls) {
            delay(3500)
            movieControls = false
        }
    }

    var callChat by rememberSaveable { mutableStateOf(false) }
    var partnerLeft by rememberSaveable(room.room) { mutableStateOf(false) }
    var partnerSeen by rememberSaveable(room.room) { mutableStateOf(false) }
    val peer = room.members.firstOrNull { it.id != room.member }
    val connectionNotice = when {
        room.room.isEmpty() -> null
        !room.connected -> "Reconnecting to Together…"
        room.connectionSlow == true && room.memberConnections[peer?.id] == true -> "Both connections are slow"
        room.connectionSlow == true -> "Your connection is slow"
        peer != null && !peer.online -> "Partner reconnecting…"
        room.memberConnections[peer?.id] == true -> "Partner’s connection is slow"
        else -> null
    }
    fun openFullCall() {
        if (calling.active && peer?.call == true) session.fullCall(true)
        else if (paired.partner != null) pairing.ring(PairKind.VIDEO)
        else session.error("Have your partner join the Watch call before opening it fullscreen.")
    }

    fun endRoom() { call.stop(); session.leave() }
    LaunchedEffect(paired.launch?.id) {
        val launch = paired.launch ?: return@LaunchedEffect
        if (room.room != launch.ticket.optString("room")) call.stop()
        session.enterPair(launch)
        pairing.joined(launch)
    }
    LaunchedEffect(notificationAnswer, paired.ring?.id) {
        val id = notificationAnswer ?: return@LaunchedEffect
        if (paired.ring?.let { it.incoming && it.id == id } == true) {
            pairing.answer(id, true)
            clearNotificationAnswer()
        } else if (paired.connected) clearNotificationAnswer()
    }
    LaunchedEffect(room.room, room.connected, peer?.id, peer?.online, peer?.call) {
        if (room.room.isEmpty()) {
            partnerLeft = false
            partnerSeen = false
            fullMovie = false
        } else if (room.connected) {
            val online = peer?.online == true
            if (partnerSeen && peer == null) {
                if (standalone) {
                    endRoom()
                    session.error("Call ended")
                    return@LaunchedEffect
                } else {
                    partnerLeft = true
                    if (room.wanted) session.playback("pause")
                }
            }
            if (online) partnerLeft = false
            if (peer != null) partnerSeen = partnerSeen || online
        }
    }
    var browseYouTube by rememberSaveable { mutableStateOf(false) }
    var showNetflix by remember { mutableStateOf(false) }
    TogetherWindow(fullscreen, callView) { fullMovie = false }
    var showMedia by remember { mutableStateOf(false) }
    var showOutput by remember { mutableStateOf(false) }
    var showRoomOptions by remember { mutableStateOf(false) }
    var showPrivateSource by remember { mutableStateOf(false) }
    var queueNew by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf("Chat") }
    var message by rememberSaveable { mutableStateOf("") }
    var showReactions by remember { mutableStateOf(false) }
    fun sendMessage() {
        if (message.isNotBlank() && session.send("chat", JSONObject().put("text", message.trim()))) message = ""
    }
    var localSource by rememberSaveable(room.media?.id) { mutableStateOf<String?>(null) }
    var loadingFile by remember { mutableStateOf(false) }
    LaunchedEffect(initialInvite) {
        if (initialInvite.isNotBlank() && room.room.isEmpty()) {
            val name = session.preferences.getString("name", null)?.takeIf { it.isNotBlank() } ?: "Guest"
            session.enter(TOGETHER_SERVER, name, invite = initialInvite)
        }
    }
    var pendingCall by remember { mutableStateOf("voice") }
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val keyboard = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val configuration = LocalConfiguration.current
    var visibleMessage by remember(room.room) { mutableStateOf<TogetherChat?>(null) }
    val showChatBanner = fullscreen || (!callView && tab == "Queue" && room.media != null)
    LaunchedEffect(room.incomingChat?.id, showChatBanner) {
        visibleMessage = null
        val incoming = room.incomingChat ?: return@LaunchedEffect
        val remaining = 5_000L - (session.serverNow() - incoming.time).coerceAtLeast(0)
        if (remaining > 0 && showChatBanner) {
            visibleMessage = incoming
            delay(remaining)
            visibleMessage = null
        }
    }
    LaunchedEffect(room.chat.lastOrNull()?.id, keyboard, tab) {
        if (tab == "Chat" && (room.chat.isNotEmpty() || keyboard)) {
            delay(150); val count = listState.layoutInfo.totalItemsCount
            if (count > 0) listState.animateScrollToItem(count - 1)
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val camera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!mic) {
            if (standalone) endRoom()
            session.error("Microphone permission is needed to join the call")
        }
        else if (pendingCall == "camera") {
            if (camera) { TogetherActiveCallService.updateCamera(context, call); call.camera(true) }
            else session.error("Allow camera access to turn video on")
        }
        else if (pendingCall == "unmute") call.microphone(true)
        else if (!TogetherActiveCallService.begin(context, call, camera, pendingCall == "video" && camera))
            session.error("Open Together to start the call.")
    }
    fun startCall(video: Boolean) {
        pendingCall = if (video) "video" else "voice"
        permissions.launch(if (video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA, Manifest.permission.BLUETOOTH_CONNECT)
            else arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT))
    }
    var startedStandalone by rememberSaveable(room.room) { mutableStateOf(false) }
    LaunchedEffect(room.room, standalone, room.connected) {
        if ((standalone || room.pairKind == PairKind.WATCH) && room.connected && !startedStandalone && !calling.active) {
            startedStandalone = true
            startCall(room.pairKind == PairKind.VIDEO)
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            loadingFile = true
            try {
                val item = session.shareLocalVideo(context.contentResolver, uri)
                check(session.setMedia(item, queueNew)) { "Reconnect to the room and choose the video again." }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { session.error(e.message ?: "Unable to share this video.") }
            finally { loadingFile = false }
        }
    }
    val projection = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null &&
            !TogetherActiveCallService.project(context, call, data)) session.error("Join the call before sharing your screen.")
    }
    fun toggleScreenShare() {
        if (calling.sharing) call.stopScreenShare()
        else if (calling.active) {
            val manager = context.getSystemService(android.media.projection.MediaProjectionManager::class.java)
            projection.launch(manager.createScreenCaptureIntent())
        }
    }
    LaunchedEffect(room.error, calling.error) {
        val error = room.error ?: calling.error
        if (error != null) { snackbar.showSnackbar(error); session.clearError() }
    }
    LaunchedEffect(paired.error) {
        paired.error?.let { snackbar.showSnackbar(it); pairing.error(null) }
    }
    DisposableEffect(lifecycle, call) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                call.background(true)
                if (session.state.value.connected && session.state.value.media != null) {
                    session.playback("pause")
                }
            }
            if (event == Lifecycle.Event.ON_START) { call.background(false); playback.lastStatus = null; playback.report(); call.refreshOutput() }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    BackHandler(room.room.isNotEmpty() && !keyboard) {
        when {
            showGames -> showGames = false
            gameState.liveGame() && !callView && !fullscreen -> browseWithGame()
            callView && callChat -> callChat = false
            gameState.liveGame() && standalone -> browseWithGame()
            calling.active -> (context as? TogetherActivity)?.minimizeCall()
            callView -> session.fullCall(false)
            fullscreen -> fullMovie = false
            else -> showLeave = true
        }
    }
    LaunchedEffect(callView) { if (!callView) callChat = false }
    val incoming = paired.ring?.takeIf { it.incoming && it.kind != PairKind.WATCH }
    var expiredIncoming by remember(incoming?.id) { mutableStateOf(false) }
    LaunchedEffect(incoming?.id) {
        if (incoming != null) {
            delay((incoming.until - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            expiredIncoming = true
        }
    }
    val callActivity = LocalActivity.current
    LaunchedEffect(incoming?.id, expiredIncoming, callActivity) {
        if (incoming == null || expiredIncoming) {
            callActivity?.setShowWhenLocked(false)
            callActivity?.setTurnScreenOn(false)
        }
    }
    if (incoming != null && !expiredIncoming && activePairLink == null) {
        TogetherIncomingCall(incoming, paired.partner ?: "Your partner") { pairing.answer(incoming.id, it) }
        return
    }
    Scaffold(
        contentWindowInsets = if (fullscreen || callView) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (!fullscreen && !callView) Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton({
                    if (gameState.liveGame()) browseWithGame() else if (room.room.isNotEmpty()) showLeave = true else finish()
                }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    connectionNotice?.let {
                        Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (room.room.isNotEmpty()) {
                    IconButton({ showGames = true }) {
                        BadgedBox(badge = { if (gameState.liveGame()) Badge() }) {
                            Icon(painterResource(R.drawable.ic_games_controller),
                                if (gameState.liveGame()) "Return to game" else "Games")
                        }
                    }
                    IconButton({ showRoomOptions = true }) {
                        Icon(painterResource(R.drawable.ic_ui_settings), "Room settings", Modifier.size(20.dp))
                    }
                    if (room.connected && peer == null && paired.ring == null) IconButton({
                        val watchRoom = session.watchRoomFor(pairing.server)
                        if (paired.partner != null && watchRoom != null) pairing.ring(PairKind.WATCH, watchRoom)
                        else shareTogetherLink(context, session.inviteLink(), "Invite your partner")
                    }) { Icon(Icons.Outlined.Share, "Share room") }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            if (room.room.isEmpty()) TogetherLobby(session, initialInvite, pairing, paired) { pastedPairLink = it }
            else Column(Modifier.fillMaxSize()) {
                val media = room.media
                val canControl = room.canStartPlayback && playback.ready
                val atEnd = playback.ended || playback.duration > 0 &&
                    playback.position >= playback.duration - 150 && room.timeline.position >= playback.duration - 150
                if (media != null) {
                    val stage = when {
                        callView -> Modifier.fillMaxWidth().height(0.dp)
                        fullscreen -> Modifier.fillMaxWidth().weight(1f)
                        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && !fullscreen -> Modifier.fillMaxWidth().height(100.dp)
                        else -> Modifier.fillMaxWidth().height(((configuration.screenWidthDp - 32).dp * 9 / 16).coerceAtMost(230.dp))
                    }.then(if (fullscreen || callView) Modifier else Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp)))
                    val showMovieCameras = fullscreen && calling.active &&
                        (peer?.camera == true || calling.camera || calling.sharing)
                    Row(stage.background(Color.Black).then(if (showMovieCameras) Modifier
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(horizontal = 10.dp, vertical = 8.dp) else Modifier),
                        horizontalArrangement = Arrangement.spacedBy(if (showMovieCameras) 10.dp else 0.dp)) {
                        if (showMovieCameras) {
                            val panelWidth = (configuration.screenWidthDp.dp * .22f).coerceIn(112.dp, 182.dp)
                            val railShape = RoundedCornerShape(18.dp)
                            Column(Modifier.width(panelWidth).fillMaxHeight()
                                .background(Color(0xFF17151E), railShape).border(1.dp, Color(0xFF373141), railShape)
                                .padding(7.dp),
                                verticalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterVertically)) {
                                fun preview(id: String): Modifier = Modifier.fillMaxWidth()
                                    .weight(if (expandedCamera == id) 1.45f else 1f)
                                    .border(1.dp, if (expandedCamera == id) Lavender else Color(0xFF625672), RoundedCornerShape(20.dp))
                                    .pointerInput(id) {
                                        detectTapGestures(onDoubleTap = { expandedCamera = if (expandedCamera == id) null else id })
                                    }
                                CameraTile(peer?.name ?: "Partner", calling.remoteVideo, peer?.camera == true && peer.online,
                                    call, false, preview(peer?.id ?: "partner"), clipVideo = true)
                                CameraTile(if (calling.sharing) "Your screen" else "You", calling.localVideo,
                                    calling.camera || calling.sharing, call, !calling.sharing, preview("self"), clipVideo = true)
                            }
                        }
                        val movieShape = RoundedCornerShape(16.dp)
                        Box(Modifier.weight(1f).fillMaxHeight()
                            .then(if (showMovieCameras) Modifier.clip(movieShape).border(1.dp, Color(0xFF373141), movieShape) else Modifier)
                            .background(Color.Black).clipToBounds()) {
                            TogetherVideo(context, room, session, playback, localSource, Modifier.fillMaxSize())
                            if (!callView && media.kind == "youtube") {
                                if (!room.wanted && playback.position < 1_500) {
                                    coil.compose.AsyncImage(
                                        model = "https://i.ytimg.com/vi/${media.source}/hqdefault.jpg",
                                        contentDescription = "YouTube preview",
                                        modifier = Modifier.matchParentSize().background(Color.Black),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                    )
                                    Text("YouTube", Modifier.align(Alignment.BottomStart).padding(10.dp)
                                        .background(Color.Black.copy(alpha = .75f), RoundedCornerShape(6.dp)).padding(6.dp),
                                        color = Color.White, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            if (!callView) {
                                TogetherVideoGestures(playback, session,
                                    canControl && !playback.live && (fullscreen || media.kind != "youtube"),
                                    Modifier.matchParentSize(), onTap = {
                                        expandedCamera = null
                                        if (fullscreen) movieControls = !movieControls
                                    })
                            }
                            visibleMessage?.takeIf { showChatBanner && !callChat && !partnerLeft }?.let { incoming ->
                                Surface(
                                    Modifier.align(Alignment.TopCenter)
                                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                        .widthIn(max = 440.dp).semantics { liveRegion = LiveRegionMode.Polite },
                                    color = Color.Black.copy(alpha = .84f), contentColor = Color.White,
                                    shape = RoundedCornerShape(14.dp),
                                ) {
                                    Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                        Text(incoming.name, style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(incoming.text, style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            if (fullscreen) {
                                connectionNotice?.let { notice ->
                                    Surface(Modifier.align(Alignment.TopStart)
                                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Start))
                                        .padding(12.dp), color = Color.Black.copy(alpha = .75f), contentColor = Color.White,
                                        shape = RoundedCornerShape(8.dp)) {
                                        Text(notice, Modifier.padding(horizontal = 10.dp, vertical = 7.dp), style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                                androidx.compose.animation.AnimatedVisibility(movieControls, Modifier.align(Alignment.BottomCenter),
                                    enter = fadeIn(), exit = fadeOut()) {
                                Column(
                                    Modifier.fillMaxWidth()
                                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .88f))))
                                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                                        .padding(start = 16.dp, end = 12.dp, top = 18.dp),
                                ) {
                                    CompositionLocalProvider(LocalContentColor provides Color.White) {
                                        TogetherPlayerStatus(room, playback, session, canControl)
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        TogetherPlayButton(room, session, canControl, atEnd) {
                                            movieControlsTouched = SystemClock.elapsedRealtime()
                                        }
                                            if (playback.duration > 0 && !playback.live) {
                                            MediaTimeline(playback.position, playback.duration, canControl, Modifier.weight(1f),
                                                onInteract = { movieControlsTouched = SystemClock.elapsedRealtime() }) {
                                                    session.playback("seek", it)
                                                }
                                            } else Spacer(Modifier.weight(1f))
                                            if (media.kind == "stream") TogetherQuality(playback)
                                            if (calling.active || paired.partner != null) IconButton(::openFullCall) {
                                                Icon(painterResource(R.drawable.ic_camera), "Switch both to video call", Modifier.size(21.dp))
                                            }
                                            IconButton({ fullMovie = false }) {
                                                Icon(painterResource(R.drawable.ic_room_exit_fullscreen), "Exit fullscreen", Modifier.size(22.dp))
                                            }
                                        }
                                    }
                                }
                                }
                            }
                        }
                    }
                    if (!callView && !fullscreen && !keyboard) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(media.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                if (media.kind == "stream") TogetherQuality(playback)
                                IconButton({ queueNew = false; showMedia = true }) {
                                    Icon(Icons.Outlined.Add, "Change video", Modifier.size(20.dp))
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TogetherPlayButton(room, session, canControl, atEnd)
                                if (playback.duration > 0 && !playback.live) {
                                    MediaTimeline(playback.position, playback.duration, canControl, Modifier.weight(1f)) {
                                        session.playback("seek", it)
                                    }
                                } else Spacer(Modifier.weight(1f))
                                IconButton({ fullMovie = true }) {
                                    Icon(painterResource(R.drawable.ic_room_fullscreen), "Fullscreen", Modifier.size(22.dp))
                                }
                            }
                            TogetherPlayerStatus(room, playback, session, canControl)
                        }
                    }
                } else if (!callView) {
                    Button({ queueNew = false; showMedia = true }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Icon(Icons.Outlined.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Choose what to watch")
                    }
                }
                if (!fullscreen && !callView && !keyboard) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (calling.active) calling.connection else "Watch call", Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!calling.active) {
                            TextButton({ startCall(false) }, enabled = room.connected) {
                                Icon(painterResource(R.drawable.ic_call), null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp)); Text("Voice")
                            }
                            TextButton({ startCall(true) }, enabled = room.connected) {
                                Icon(painterResource(R.drawable.ic_camera), null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp)); Text("Video")
                            }
                        } else {
                            IconButton({ call.microphone(!calling.mic) }) {
                                Icon(painterResource(if (calling.mic) R.drawable.ic_mic else R.drawable.ic_mic_off),
                                    if (calling.mic) "Mute microphone" else "Unmute microphone", Modifier.size(20.dp))
                            }
                            IconButton({
                                if (calling.camera) call.camera(false)
                                else { pendingCall = "camera"; permissions.launch(arrayOf(Manifest.permission.CAMERA)) }
                            }) {
                                Icon(painterResource(if (calling.camera) R.drawable.ic_camera else R.drawable.ic_camera_off),
                                    if (calling.camera) "Turn camera off" else "Turn camera on", Modifier.size(20.dp))
                            }
                            IconButton(::openFullCall) {
                                Icon(painterResource(R.drawable.ic_room_fullscreen), "Open full call", Modifier.size(20.dp))
                            }
                            IconButton({ call.stop() }) {
                                Icon(painterResource(R.drawable.ic_call_end), "End call", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                if (!fullscreen && !callView) {
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(tab == "Chat", { tab = "Chat" }, { Text("Chat") })
                        FilterChip(tab == "Queue", { tab = "Queue" }, { Text(if (room.queue.isEmpty()) "Queue" else "Queue · ${room.queue.size}") })
                        Spacer(Modifier.weight(1f))
                        if (tab == "Queue") IconButton({ queueNew = true; showMedia = true }) {
                            Icon(Icons.Outlined.Add, "Add to shared queue", Modifier.size(20.dp))
                        }
                    }
                }
                if (!fullscreen && !callView) LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState,
                    contentPadding = PaddingValues(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (tab == "Chat") {
                        if (room.chat.isEmpty()) item { Text("No messages yet.", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
                        if (calling.active && (peer != null || calling.camera || calling.sharing)) item(key = "call-cameras") {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val tile = Modifier.weight(1f).height(172.dp)
                                CameraTile(peer?.name ?: "Partner", calling.remoteVideo, peer?.camera == true && peer.online,
                                    call, false, tile, clipVideo = true)
                                CameraTile(if (calling.sharing) "Your screen" else "You", calling.localVideo,
                                    calling.camera || calling.sharing, call, !calling.sharing, tile, clipVideo = true)
                            }
                        }
                        if (room.queue.isEmpty()) item { Text("Pick what comes next.", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall) }
                        items(room.queue, key = { it.id }) { item ->
                            ListItem(headlineContent = { Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text(item.kind.uppercase()) },
                                leadingContent = { IconButton({ session.send("queueSelect", JSONObject().put("id", item.id)) }) { Icon(Icons.Outlined.PlayArrow, "Watch ${item.title}") } },
                                trailingContent = { IconButton({ session.send("queueRemove", JSONObject().put("id", item.id)) }) { Icon(Icons.Outlined.Close, "Remove from queue") } })
                        }
                    }
                }
                if (loadingFile) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    Text("Connecting your shared video", Modifier.padding(12.dp), style = MaterialTheme.typography.labelSmall)
                }
                if (!fullscreen && !callView && tab == "Chat") Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        IconButton({ showReactions = true }, enabled = room.connected) {
                            Icon(Icons.Outlined.FavoriteBorder, "Reactions", Modifier.size(20.dp))
                        }
                        DropdownMenu(showReactions, { showReactions = false }) {
                            reactions.entries.chunked(3).forEach { group ->
                                Row(Modifier.padding(horizontal = 4.dp)) {
                                    group.forEach { (key, glyph) ->
                                        IconButton({
                                            session.send("reaction", JSONObject().put("emoji", key))
                                            showReactions = false
                                        }, modifier = Modifier.semantics { contentDescription = key }) {
                                            Text(glyph, fontSize = 23.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    TextField(message, { message = it.take(2000) }, Modifier.weight(1f),
                        placeholder = { Text("Message") }, shape = RoundedCornerShape(12.dp), maxLines = 2,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        ),
                        trailingIcon = {
                            IconButton(::sendMessage, enabled = room.connected && message.isNotBlank()) {
                                Icon(Icons.AutoMirrored.Outlined.Send, "Send message", Modifier.size(20.dp))
                            }
                        },
                    )
                }
            }
            if (callView) TogetherCallView(
                room, calling, call, message, { message = it.take(2000) },
                ::sendMessage,
                callChat, { callChat = !callChat }, { if (calling.active) (context as? TogetherActivity)?.minimizeCall() else session.fullCall(false); callChat = false },
                { if (standalone) endRoom() else { call.stop(); session.fullCall(false) }; callChat = false },
                {
                    if (calling.camera) call.camera(false)
                    else {
                        pendingCall = "camera"
                        permissions.launch(arrayOf(Manifest.permission.CAMERA))
                    }
                }, Modifier.fillMaxSize(), standaloneKind = room.pairKind.takeIf { standalone },
                toggleScreenShare = ::toggleScreenShare, showAudio = { showOutput = true },
                connectionNotice = connectionNotice,
            )
            if (callView) IconButton({ showGames = true }, Modifier.align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End))
                .padding(top = 4.dp, end = 8.dp)) {
                Icon(painterResource(R.drawable.ic_games_controller), "Open Games", tint = Color.White)
            }
            if (partnerLeft) Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Icon(painterResource(R.drawable.ic_nav_together), null, Modifier.size(52.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("Your partner left", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("You can wait here or leave the room.", Modifier.padding(vertical = 12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton({ partnerLeft = false; call.stop(); session.leave() }) { Text("Quit") }
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
    TogetherPairDialogs(pairing, paired, activePairLink, { pastedPairLink = null; closePairLink() }, session)
    if (showLeave) AlertDialog(onDismissRequest = { showLeave = false }, title = { Text("Leave your room?") }, text = { Text("Playback pauses for your partner and your call ends.") },
        confirmButton = { TextButton({ call.stop(); session.leave(); showLeave = false }) { Text("Leave room") } }, dismissButton = { TextButton({ showLeave = false }) { Text("Stay") } })
    if (showMedia) MediaChooser(
        onDismiss = { showMedia = false },
        onYouTube = { showMedia = false; browseYouTube = true },
        onNetflix = { showMedia = false; showNetflix = true },
        onPickFile = { showMedia = false; picker.launch(arrayOf("video/mp4", "video/webm", "video/x-matroska")) },
        onChoose = { item -> session.setMedia(item, queueNew); showMedia = false },
    )
    val gameKind = gameState.gameKind()
    if (!showGames && gameKind != null && gameState.optString("phase") == "inviting" &&
        gameState.optString("inviter") != room.member) {
        AlertDialog(onDismissRequest = {},
            title = { Text("Play ${gameKind.title}?") },
            text = { Text("${peer?.name ?: "Your partner"} invited you to a game.") },
            confirmButton = { TextButton({
                if (session.games.command("accept")) showGames = true
            }, enabled = room.connected) { Text("Accept") } },
            dismissButton = { TextButton({ session.games.command("decline") }, enabled = room.connected) { Text("Decline") } })
    }
    if (showGames && room.room.isNotEmpty()) TogetherGamesScreen(
        session, room, close = { showGames = false }, startVoice = { startCall(false) },
        toggleCamera = {
            if (calling.camera) call.camera(false)
            else { pendingCall = "camera"; permissions.launch(arrayOf(Manifest.permission.CAMERA)) }
        },
        expandCall = { showGames = false; if (!callView) openFullCall() },
        callAudio = { showOutput = true },
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
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            ListItem(headlineContent = { Text("Audio output") }, supportingContent = { Text(calling.output) })
            TextButton({ call.output(null) }, Modifier.padding(horizontal = 16.dp)) { Text("Automatic output") }
            call.devices().forEach { device -> TextButton({ call.output(device) }, Modifier.padding(horizontal = 16.dp)) { Text(TogetherCall.outputName(device)) } }
            HorizontalDivider()
            ListItem(headlineContent = { Text("Microphone") }, supportingContent = { Text(calling.input) })
            TextButton({ call.input(null) }, Modifier.padding(horizontal = 16.dp)) { Text("Automatic microphone") }
            call.inputs().forEach { device -> TextButton({ call.input(device) }, Modifier.padding(horizontal = 16.dp)) { Text(TogetherCall.outputName(device)) } }
        }
        Spacer(Modifier.navigationBarsPadding().height(16.dp))
    }
    if (showRoomOptions) ModalBottomSheet({ showRoomOptions = false }) {
        Text("Your room", Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.titleLarge)
        ListItem(headlineContent = { Text("Wait when a player buffers") }, supportingContent = { Text("Keep the moment together") },
            trailingContent = { Switch(room.wait, { session.send("wait", JSONObject().put("enabled", it)) }) })
        if (calling.active) TextButton({ showRoomOptions = false; showOutput = true }, Modifier.padding(horizontal = 16.dp)) { Text("Audio output") }
        if (calling.camera) TextButton({ call.flip(); showRoomOptions = false }, Modifier.padding(horizontal = 16.dp)) { Text("Switch camera") }
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
private fun TogetherWindow(fullscreen: Boolean, callView: Boolean, exitFullscreen: () -> Unit) {
    val activity = LocalActivity.current ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val exit by rememberUpdatedState(exitFullscreen)
    val immersive = fullscreen || callView
    DisposableEffect(activity) {
        val originalOrientation = activity.requestedOrientation
        onDispose { activity.requestedOrientation = originalOrientation }
    }
    SideEffect {
        val orientation = when {
            fullscreen -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            callView -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        if (activity.requestedOrientation != orientation) activity.requestedOrientation = orientation
    }
    DisposableEffect(activity, immersive) {
        val window = activity.window
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        val originalBehavior = insets.systemBarsBehavior
        if (immersive) {
            insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (immersive) {
                insets.show(WindowInsetsCompat.Type.systemBars())
                insets.systemBarsBehavior = originalBehavior
            }
        }
    }
    DisposableEffect(activity, lifecycle, fullscreen) {
        if (!fullscreen) return@DisposableEffect onDispose { }
        val rotation = activity.display?.rotation
        val unrotated = rotation == null || rotation == DisplaySurface.ROTATION_0 || rotation == DisplaySurface.ROTATION_180
        val portraitDisplay = activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val naturalPortrait = unrotated == portraitDisplay
        var heldInLandscape = false
        val sensor = object : OrientationEventListener(activity) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val angle = (orientation + if (naturalPortrait) 0 else 90) % 180
                // Enter fullscreen even if the phone is still upright. Exit after a physical turn back.
                if (angle in 65..115) heldInLandscape = true
                if (heldInLandscape && (angle <= 25 || angle >= 155)) exit()
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> sensor.enable()
                Lifecycle.Event.ON_PAUSE -> sensor.disable()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            sensor.disable()
        }
    }
}

@Composable
private fun TogetherQuality(playback: TogetherPlayback) {
    var show by remember { mutableStateOf(false) }
    Box {
        TextButton({ show = true }) { Text(playback.qualityLimit?.let { "${it}p" } ?: "Auto") }
        DropdownMenu(show, { show = false }) {
            listOf(null, 1080, 720, 480, 360).forEach { height ->
                DropdownMenuItem(text = { Text(height?.let { "Up to ${it}p" } ?: "Auto quality") },
                    onClick = { playback.qualityLimit = height; show = false })
            }
        }
    }
}

@Composable
private fun TogetherPlayButton(room: TogetherState, session: TogetherSession, canControl: Boolean, ended: Boolean,
    onInteract: () -> Unit = {},
) {
    val label = when { room.wanted -> "Pause both"; ended -> "Replay both"; else -> "Play both" }
    IconButton({
        onInteract()
        if (room.wanted) session.playback("pause")
        else if (!ended || session.playback("seek", 0)) session.playback("play")
    }, enabled = room.connected && (room.wanted || canControl)) {
        Icon(painterResource(if (room.wanted) R.drawable.ic_media_pause else R.drawable.ic_media_play),
            label, Modifier.size(24.dp))
    }
}

@Composable
private fun TogetherVideoGestures(playback: TogetherPlayback, session: TogetherSession, canSeek: Boolean, modifier: Modifier, onTap: () -> Unit) {
    var feedback by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    val tap by rememberUpdatedState(onTap)
    fun skip(seconds: Long): Boolean {
        val target = (playback.position + seconds * 1000).coerceAtLeast(0).let {
            if (playback.duration > 0) it.coerceAtMost(playback.duration) else it
        }
        if (!session.playback("seek", target)) return false
        feedback = seconds to SystemClock.elapsedRealtime()
        return true
    }
    LaunchedEffect(feedback) { if (feedback != null) { delay(700); feedback = null } }
    Box(modifier.semantics {
        contentDescription = "Shared video"
        if (canSeek) customActions = listOf(CustomAccessibilityAction("Back 10 seconds") { skip(-10) },
            CustomAccessibilityAction("Forward 10 seconds") { skip(10) })
    }.pointerInput(session, playback, canSeek) {
        detectTapGestures(
            onDoubleTap = if (canSeek) ({ point -> skip(if (point.x < size.width / 2f) -10 else 10) }) else null,
            onTap = { tap() },
        )
    }) {
        feedback?.let { (seconds) ->
            val forward = seconds > 0
            Text(if (forward) "+10s" else "−10s", Modifier.align(if (forward) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(horizontal = 24.dp).background(Color.Black.copy(alpha = .65f), RoundedCornerShape(16.dp)).padding(10.dp),
                color = Color.White, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun TogetherPlayerStatus(room: TogetherState, playback: TogetherPlayback, session: TogetherSession, canControl: Boolean) {
    val error = playback.error
    if (error != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton({ session.status(false); playback.reload++ }) { Text("Retry player") }
        }
    } else if (!canControl || (room.wanted && !room.timeline.playing)) {
        val status = when {
            !room.connected -> "Reconnecting"
            room.members.count { it.online } < 2 -> "Waiting for your partner"
            !playback.ready -> "Loading video…"
            room.members.any { !it.ready } -> "Waiting for your partner’s player"
            room.wanted && !room.timeline.playing -> "Buffering…"
            room.timeline.playing -> "Playing together"
            else -> "Both ready"
        }
        Text(status, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun TogetherLobby(session: TogetherSession, invitation: String, pairing: TogetherPairing, paired: PairState,
    openPairLink: (PairLink) -> Unit,
) {
    val state by session.state.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf(session.preferences.getString("name", "").orEmpty()) }
    var server by rememberSaveable { mutableStateOf(session.preferences.getString("server", TOGETHER_SERVER).orEmpty()) }
    var key by rememberSaveable { mutableStateOf(session.preferences.getString("access", "").orEmpty()) }
    var invite by rememberSaveable(invitation) { mutableStateOf(invitation) }
    var joining by rememberSaveable(invitation) { mutableStateOf(invitation.isNotEmpty()) }
    var manualRoom by rememberSaveable(invitation) { mutableStateOf(invitation.isNotEmpty()) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var creatingLink by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val manualJoin = manualRoom && joining
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!manualRoom && paired.partner != null) {
            PairedTogetherActions(pairing, paired)
            TextButton({ manualRoom = true }) { Text("Use a one-time watch room") }
            return@Column
        }
        if (!manualRoom) {
            Text("Pair a device", style = MaterialTheme.typography.headlineSmall)
            Text("Share a link once. When your partner accepts, you can call or watch together from this screen.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!joining, { joining = false }, { Text("Create a room") })
            FilterChip(joining, { joining = true }, { Text("Join your partner") })
        }
        OutlinedTextField(name, { name = it.take(32) }, Modifier.fillMaxWidth(), label = { Text("Your name") },
            singleLine = true, shape = RoundedCornerShape(18.dp))
        if (manualJoin) OutlinedTextField(invite, { invite = it }, Modifier.fillMaxWidth(),
            label = { Text("Invitation code or message") }, maxLines = 4, shape = RoundedCornerShape(18.dp),
            trailingIcon = { IconButton({ invite = clipboard.getText()?.text.orEmpty() }) { Icon(Icons.Outlined.ContentPaste, "Paste invitation") } })
        else OutlinedTextField(key, { key = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Private server access key") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(18.dp))
        TextButton({ settings = !settings }) {
            Icon(painterResource(R.drawable.ic_ui_settings), null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Server connection")
        }
        AnimatedVisibility(settings) {
            OutlinedTextField(server, { server = it }, Modifier.fillMaxWidth(), label = { Text("HTTPS server address") },
                singleLine = true, shape = RoundedCornerShape(18.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        }
        Button({
            if (manualRoom) {
                if (!joining) session.preferences.edit().putString("access", key).apply()
                session.enter(server, name, if (joining) null else key, if (joining) invite else null)
            } else {
                creatingLink = true
                scope.launch {
                    try {
                        val link = pairing.invite(server, name, key)
                        session.preferences.edit().putString("server", server).putString("name", name).putString("access", key).apply()
                        shareTogetherLink(context, link, "Share pairing link")
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (error: Exception) { pairing.error(error.message ?: "Couldn’t create a pairing link.") }
                    finally { creatingLink = false }
                }
            }
        }, enabled = !state.connecting && !creatingLink && name.isNotBlank() &&
            (if (manualJoin) invite.isNotBlank() else key.isNotBlank()),
            modifier = Modifier.fillMaxWidth().height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink)) {
            if (state.connecting || creatingLink) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Ink)
            else Text(if (!manualRoom) "Create pairing link" else if (joining) "Join private room" else "Create private room")
        }
        if (!manualRoom) TextButton({
            val link = PairLink.parse(clipboard.getText()?.text?.trim()?.let(android.net.Uri::parse))
            if (link?.callSecret != null) openPairLink(link)
            else pairing.error("Copy the secure pairing link your partner sent first.")
        }) { Text("Paste a pairing link") }
        TextButton({ manualRoom = !manualRoom }) { Text(if (manualRoom) "Back to pairing" else "Use a one-time watch room") }
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
                SourceOption("Local video", "Stream a video from this phone. Your partner needs no copy.", onPickFile)
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
            Text("Local videos stream from the source phone while it stays in the room. Private IPTV credentials can be entered separately on each phone after loading the program.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun CameraTile(name: String, track: VideoTrack?, enabled: Boolean, call: TogetherCall, mirror: Boolean, modifier: Modifier,
    fill: Boolean = false, rounded: Boolean = true, showLabel: Boolean = true, overlay: Boolean = true,
    clipVideo: Boolean = false,
    chrome: @Composable BoxScope.() -> Unit = {},
) {
    val context = LocalContext.current
    Box(modifier.clip(RoundedCornerShape(if (rounded) 20.dp else 0.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        if (enabled && track != null) {
            if (clipVideo) {
                val renderer = remember(call) { RoundedCameraView(context, call.egl.eglBaseContext) }
                DisposableEffect(renderer, track) {
                    runCatching { track.addSink(renderer) }
                    onDispose { runCatching { track.removeSink(renderer) } }
                }
                AndroidView(factory = { renderer }, update = { it.configure(fill, mirror) },
                    onRelease = { it.release() }, modifier = Modifier.fillMaxSize())
            } else {
                val renderer = remember(call, fill, overlay) { SurfaceViewRenderer(context).apply {
                    init(call.egl.eglBaseContext, null)
                    setScalingType(if (fill) RendererCommon.ScalingType.SCALE_ASPECT_FILL else RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    setEnableHardwareScaler(true)
                    setZOrderMediaOverlay(overlay)
                } }
                DisposableEffect(renderer, track) {
                    runCatching { track.addSink(renderer) }
                    onDispose { runCatching { track.removeSink(renderer) } }
                }
                AndroidView(factory = { renderer }, update = { it.setMirror(mirror) },
                    onRelease = { it.release() }, modifier = Modifier.fillMaxSize())
            }
        }
        else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(painterResource(R.drawable.ic_camera_off), null, Modifier.size(25.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Camera off", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (showLabel) Surface(Modifier.align(Alignment.BottomStart).padding(7.dp), color = Ink.copy(alpha = .75f), shape = RoundedCornerShape(8.dp)) {
            Text(name, Modifier.padding(horizontal = 7.dp, vertical = 3.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
        chrome()
    }
}
