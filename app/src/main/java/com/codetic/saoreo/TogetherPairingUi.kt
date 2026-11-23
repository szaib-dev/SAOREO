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
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
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
internal fun PairedTogetherActions(pairing: TogetherPairing, state: PairState) {
    val partner = state.partner ?: return
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmUnpair by rememberSaveable { mutableStateOf(false) }
    val kind = PairKind.entries[tab]
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
    Text(partner, style = MaterialTheme.typography.headlineSmall)
    Text(when {
        !state.connected -> "Connecting to Together…"
        state.online -> "Available"
        else -> "Offline"
    }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    PrimaryTabRow(selectedTabIndex = tab) {
        listOf("Streaming", "Audio call", "Video call").forEachIndexed { index, title ->
            Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
        }
    }
    Button({ pairing.ring(kind) }, enabled = state.connected && state.ring == null && state.requesting == null, modifier = Modifier.fillMaxWidth()) {
        val icon = when (kind) { PairKind.WATCH -> R.drawable.ic_media_play; PairKind.AUDIO -> R.drawable.ic_call; PairKind.VIDEO -> R.drawable.ic_camera }
        Icon(painterResource(icon), null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (state.requesting != null) "Calling..." else when (kind) { PairKind.WATCH -> "Send watch invite"; PairKind.AUDIO -> "Start audio call"; PairKind.VIDEO -> "Start video call" })
    }
    if (kind != PairKind.WATCH) OutlinedButton({ pairing.ring(kind, silent = true) },
        enabled = state.connected && state.ring == null && state.requesting == null, modifier = Modifier.fillMaxWidth()) {
        Text("Call silently")
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

    val ring = state.ring?.takeUnless { it.incoming && it.kind != PairKind.WATCH }
    var expired by remember(ring?.id) { mutableStateOf(false) }
    LaunchedEffect(ring?.id, ring?.until) {
        if (ring != null) {
            delay((ring.until - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            expired = true
        }
    }
    val incoming = ring?.takeIf { it.incoming && !expired && state.visible }
    DisposableEffect(incoming?.id) {
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
internal fun TogetherIncomingCall(ring: PairRing, partner: String, answer: (Boolean) -> Unit) {
    val context = LocalContext.current
    val activity = androidx.activity.compose.LocalActivity.current
    DisposableEffect(ring.id) {
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
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            Surface(Modifier.size(96.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Box(contentAlignment = Alignment.Center) {
                    Text(partner.take(1).uppercase(), style = MaterialTheme.typography.displaySmall)
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(partner, style = MaterialTheme.typography.headlineLarge)
            Text("Incoming ${ring.kind.title.lowercase()}", Modifier.padding(top = 6.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton({ answer(false) }, Modifier.weight(1f).height(52.dp)) { Text("Decline") }
                Button({ answer(true) }, Modifier.weight(1f).height(52.dp)) { Text("Answer") }
            }
        }
    }
}
