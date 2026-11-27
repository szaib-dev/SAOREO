package com.codetic.saoreo

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.semantics.onClick
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun shareTogetherLink(context: Context, link: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
    context.startActivity(Intent.createChooser(send, title))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PairedTogetherActions(pairing: TogetherPairing, state: PairState, showHeader: Boolean = true) {
    val partner = state.partner ?: return
    var confirmUnpair by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val availability = Intent(context, TogetherAvailabilityService::class.java)
    fun startAvailability() {
        runCatching { ContextCompat.startForegroundService(context, availability) }
            .onSuccess { pairing.setAvailabilityPreference(true) }
            .onFailure { pairing.error("Couldn’t start background calls.") }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startAvailability() else pairing.error("Allow notifications to receive calls when Together is closed.")
    }
    if (showHeader) {
    Text(partner, style = MaterialTheme.typography.headlineSmall)
    Text(when {
        !state.connected -> "Connecting to Together…"
        state.online -> "Available"
        else -> "Offline"
    }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Background calls and invites", style = MaterialTheme.typography.titleSmall)
            Text("Ring when Together is closed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = state.availableInBackground, onCheckedChange = { enabled ->
            if (!enabled) { pairing.setAvailabilityPreference(false); context.stopService(availability) }
            else if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) startAvailability()
            else notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }, modifier = Modifier.semantics { contentDescription = "Background calls and invites" })
    }
    val notificationsManager = context.getSystemService(android.app.NotificationManager::class.java)
    if (state.availableInBackground && android.os.Build.VERSION.SDK_INT >= 34 && !notificationsManager.canUseFullScreenIntent()) {
        TextButton({
            context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                android.net.Uri.parse("package:${context.packageName}")))
        }) { Text("Allow fullscreen incoming calls") }
    }
    TextButton({ confirmUnpair = true }) { Text("Unpair device") }
    if (confirmUnpair) AlertDialog(onDismissRequest = { confirmUnpair = false }, title = { Text("Unpair $partner?") },
        text = { Text("You’ll need a new pairing link to call or watch together again.") },
        confirmButton = {
            TextButton({
                confirmUnpair = false
                scope.launch {
                    try { pairing.unpair(); pairing.setAvailabilityPreference(false); context.stopService(availability) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { pairing.error(error.message ?: "Couldn’t unpair these devices.") }
                }
            }) { Text("Unpair") }
        }, dismissButton = { TextButton({ confirmUnpair = false }) { Text("Cancel") } })
}

@Composable
internal fun PreparePairedAvailability(pairing: TogetherPairing, state: PairState) {
    val partner = state.partner ?: return
    val context = LocalContext.current
    fun startAvailability() {
        runCatching { ContextCompat.startForegroundService(context, Intent(context, TogetherAvailabilityService::class.java)) }
            .onSuccess { pairing.setAvailabilityPreference(true) }
            .onFailure { pairing.error("Couldn’t start background calls.") }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startAvailability()
    }
    LaunchedEffect(partner, state.availableInBackground) {
        if (!state.availableInBackground && pairing.resumeAvailabilityAtBoot &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) startAvailability()
        val preferences = context.getSharedPreferences("together-background-setup", Context.MODE_PRIVATE)
        if (!preferences.getBoolean("offered-v2", false)) {
            preferences.edit().putBoolean("offered-v2", true).apply()
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                startAvailability()
            } else notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
internal fun TogetherPairDialogs(pairing: TogetherPairing, state: PairState, link: PairLink?, closeLink: () -> Unit,
    session: TogetherSession) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    if (link != null) {
        var inviter by remember(link) { mutableStateOf<String?>(null) }
        var error by remember(link) { mutableStateOf<String?>(null) }
        var accepting by remember(link) { mutableStateOf(false) }
        var name by rememberSaveable(link.code) { mutableStateOf(session.preferences.getString("name", "").orEmpty()) }
        LaunchedEffect(link) {
            try { inviter = pairing.preview(link) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Couldn’t open this pairing link." }
        }
        AlertDialog(onDismissRequest = { if (!accepting) closeLink() }, title = { Text("Pair devices") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when {
                        error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                        inviter == null -> CircularProgressIndicator(Modifier.size(24.dp))
                        else -> Text("${inviter.orEmpty()} wants to pair with you for calls and watch invites.")
                    }
                    if (inviter != null) OutlinedTextField(name, { name = it.take(32) }, singleLine = true,
                        label = { Text("Your name") }, enabled = !accepting)
                }
            }, confirmButton = {
                Button({
                    accepting = true
                    scope.launch {
                        try {
                            pairing.accept(link, name)
                            session.preferences.edit().putString("name", name.trim()).apply()
                            closeLink()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message ?: "Couldn’t accept pairing." }
                        finally { accepting = false }
                    }
                }, enabled = inviter != null && name.isNotBlank() && !accepting) { Text("Accept pairing") }
            }, dismissButton = { TextButton(closeLink, enabled = !accepting) { Text("Cancel") } })
        return
    }

    val ring = state.ring?.takeIf { it.kind == PairKind.WATCH }
    var expired by remember(ring?.id) { mutableStateOf(false) }
    LaunchedEffect(ring?.id, ring?.until) {
        if (ring != null) {
            delay((ring.until - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            expired = true
        }
    }
    val incoming = ring?.takeIf { it.incoming && !expired && state.visible }
    DisposableEffect(incoming?.id) {
        incoming?.let { pairing.acknowledgeIncomingAlert(it.id) }
        val ringtone = incoming?.takeIf { !it.silent && togetherCallAlertsAllowed(context) }?.let {
            runCatching {
                RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
                    ?.apply { isLooping = true; play() }
            }.getOrNull()
        }
        onDispose { ringtone?.stop() }
    }
    if (ring == null || expired) return
    val partner = state.partner ?: "Your partner"
    if (ring.incoming) {
        AlertDialog(onDismissRequest = { pairing.answer(ring.id, false) }, title = { Text(ring.kind.title) },
            text = { Text(if (ring.kind == PairKind.WATCH) "$partner invited you to watch together." else "$partner is calling you.") },
            confirmButton = { Button({ pairing.answer(ring.id, true) }) { Text("Accept") } },
            dismissButton = { TextButton({ pairing.answer(ring.id, false) }) { Text("Decline") } })
    } else {
        AlertDialog(onDismissRequest = { pairing.cancel(ring.id) }, title = { Text(if (ring.silent) "Calling $partner silently…" else if (ring.delivered) "Ringing $partner…" else "Calling $partner…") },
            text = { Text(ring.kind.title) }, confirmButton = { TextButton({ pairing.cancel(ring.id) }) { Text("Cancel request") } })
    }
}

@Composable
internal fun TogetherIncomingCall(ring: PairRing, partner: String, alertActive: () -> Unit = {}, message: (() -> Unit)? = null, photo: java.io.File? = null, photoRevision: Int = 0, answer: (Boolean) -> Unit) {
    val context = LocalContext.current
    val activity = androidx.activity.compose.LocalActivity.current
    DisposableEffect(ring.id) {
        alertActive()
        val alert = !ring.silent && togetherCallAlertsAllowed(context)
        activity?.setShowWhenLocked(alert)
        activity?.setTurnScreenOn(alert)
        val ringtone = if (alert) runCatching {
            RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
                ?.apply { isLooping = true; play() }
        }.getOrNull() else null
        onDispose {
            ringtone?.stop()
            activity?.setShowWhenLocked(false)
            activity?.setTurnScreenOn(false)
        }
    }
    androidx.activity.compose.BackHandler { answer(false) }
    val callIcon = if (ring.kind == PairKind.VIDEO) R.drawable.ic_camera else R.drawable.ic_call
    Surface(Modifier.fillMaxSize(), color = Color(0xff111214), contentColor = Color(0xfff7f7f7)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(42.dp))
            Icon(painterResource(callIcon), null, Modifier.size(25.dp))
            Spacer(Modifier.height(10.dp))
            Text("Incoming ${ring.kind.title.lowercase()}", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            PartnerAvatar(partner, 110, picture = photo, revision = photoRevision)
            Spacer(Modifier.height(18.dp))
            Text(partner, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(2f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                if (message != null) InvitationAction("Message", R.drawable.ic_chat, Color(0xff252629), Color.White, message)
                SlideToAnswer(callIcon) { answer(true) }
                InvitationAction("Decline", R.drawable.ic_call_end, Color(0xffe93641), Color.White) { answer(false) }
            }
        }
    }
}

@Composable
private fun InvitationAction(label: String, icon: Int, background: Color, foreground: Color, action: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledIconButton(onClick = action, modifier = Modifier.size(58.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = background, contentColor = foreground)) {
            Icon(painterResource(icon), label, Modifier.size(25.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White)
    }
}

@Composable
internal fun TogetherOutgoingCall(kind: PairKind, partner: String, ringing: Boolean, silent: Boolean,
    call: TogetherCall, state: TogetherCallState, muted: Boolean, toggleMic: () -> Unit, speaker: Boolean,
    toggleSpeaker: () -> Unit, minimize: () -> Unit, cancel: () -> Unit,
    photo: java.io.File? = null, photoRevision: Int = 0) {
    val context = LocalContext.current
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsState()
    val cameraAvailable = kind == PairKind.VIDEO && lifecycle == androidx.lifecycle.Lifecycle.State.RESUMED
    var cameraGranted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val requestCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { cameraGranted = it }
    LaunchedEffect(kind) { if (kind == PairKind.VIDEO && !cameraGranted) requestCamera.launch(Manifest.permission.CAMERA) }
    DisposableEffect(call, cameraAvailable, cameraGranted) {
        if (cameraAvailable && cameraGranted) call.previewBeforeCall(true)
        onDispose { if (cameraAvailable && cameraGranted) call.previewBeforeCall(false) }
    }
    androidx.activity.compose.BackHandler { minimize() }
    val icon = if (kind == PairKind.VIDEO) R.drawable.ic_camera else R.drawable.ic_call
    Surface(Modifier.fillMaxSize(), color = chatCharcoal, contentColor = Color.White) {
        Box(Modifier.fillMaxSize()) {
            if (cameraAvailable && state.camera) CameraTile("Your camera", state.localVideo, true, call,
                mirror = true, modifier = Modifier.fillMaxSize(), fill = true, overlay = false, rounded = false, showLabel = false)
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 18.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(minimize, Modifier.align(Alignment.Start)) {
                    Icon(androidx.compose.material.icons.Icons.AutoMirrored.Outlined.ArrowBack, "Back to chat")
                }
                Icon(painterResource(icon), null, Modifier.size(27.dp))
                Spacer(Modifier.height(9.dp))
                Text(if (ringing) "Ringing $partner…" else "Calling $partner…", style = MaterialTheme.typography.titleLarge)
                Text(if (silent) "${kind.title} · silent" else kind.title, color = Color(0xffc6c6ca))
                Spacer(Modifier.weight(1f))
                if (!state.camera) PartnerAvatar(partner, 110, picture = photo, revision = photoRevision)
                Spacer(Modifier.weight(2f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    if (kind == PairKind.VIDEO) InvitationAction("Switch camera", R.drawable.ic_camera_flip, Color(0xff252629), Color.White, call::flip)
                    else InvitationAction(if (speaker) "Speaker on" else "Speaker", R.drawable.ic_speaker, Color(0xff252629), Color.White, toggleSpeaker)
                    InvitationAction(if (muted) "Unmute" else "Mute", if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic, Color(0xff252629), Color.White, toggleMic)
                    InvitationAction("Cancel", R.drawable.ic_call_end, Color(0xffe93641), Color.White, cancel)
                }
            }
        }
    }
}

@Composable
private fun SlideToAnswer(icon: Int, answer: () -> Unit) {
    val latestAnswer by rememberUpdatedState(answer)
    var drag by remember { mutableFloatStateOf(0f) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(Modifier.size(56.dp).pointerInput(Unit) {
            val threshold = 44.dp.toPx()
            detectVerticalDragGestures(onDragCancel = { drag = 0f }, onDragEnd = {
                val accepted = drag <= -threshold
                drag = 0f
                if (accepted) latestAnswer()
            }) { change, amount -> change.consume(); drag = (drag + amount).coerceIn(-90.dp.toPx(), 0f) }
        }.semantics { onClick(label = "Answer call") { latestAnswer(); true } },
            color = Color(0xffc9ee65), shape = androidx.compose.foundation.shape.CircleShape) {
            Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(0, (drag * .25f).toInt()) },
                contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), null, tint = Color(0xff131909))
            }
        }
        Text("Swipe up to answer", Modifier.padding(top = 6.dp), color = Color.White,
            style = MaterialTheme.typography.labelSmall)
    }
}

