package com.codetic.saoreo

import android.content.Intent
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal enum class MediaAutoDownload(val label: String) {
    Never("Never"), Unmetered("Unmetered networks"), Always("Any connection")
}

@Composable
internal fun PairedNotificationsPage(pairing: TogetherPairing, back: () -> Unit) {
    val context = LocalContext.current
    val id = pairing.conversationId
    var muted by remember(id) { mutableStateOf(id?.let { PairedChatNotifications.muted(context, it) } == true) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        PairedPageHeader("Notifications", back)
        ListItem(headlineContent = { Text("Mute messages") }, trailingContent = {
            Switch(muted, { enabled ->
                id?.let { PairedChatNotifications.mute(context, it, enabled) }
                if (enabled) PairedChatNotifications.clear(context)
                muted = enabled
            }, enabled = id != null)
        })
        TextButton({
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }, Modifier.padding(horizontal = 12.dp)) { Text("Android notification settings") }
    }
}

@Composable
internal fun PairedStoragePage(pairing: TogetherPairing, back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sizes by remember { mutableStateOf(Triple(0L, 0L, 0L)) }
    var auto by remember { mutableStateOf(pairing.mediaDownload()) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var instruction by remember { mutableStateOf<String?>(null) }
    var operation by remember { mutableStateOf<Boolean?>(null) }

    suspend fun updateSize() { sizes = pairing.storageSize() }
    LaunchedEffect(pairing.conversationId) { updateSize() }
    fun backup(uri: android.net.Uri?, restore: Boolean) {
        val key = password.toCharArray()
        password = ""
        if (uri == null) { key.fill('\u0000'); return }
        scope.launch {
            busy = true
            instruction = null
            try {
                pairing.chatBackup(uri, key, restore)
                instruction = if (restore) "Backup restored" else "Encrypted backup saved"
                updateSize()
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                instruction = if (restore) "Couldn’t restore this backup. Check the file and password."
                    else "Couldn’t write a complete backup."
            } finally {
                key.fill('\u0000')
                busy = false
            }
        }
    }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { backup(it, false) }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { backup(it, true) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).navigationBarsPadding()
        .verticalScroll(rememberScrollState())) {
        PairedPageHeader("Storage and backup", back)
        ListItem(headlineContent = { Text("Conversation database") }, trailingContent = { Text(Formatter.formatFileSize(context, sizes.third)) })
        ListItem(headlineContent = { Text("Encrypted media") }, trailingContent = { Text(Formatter.formatFileSize(context, sizes.first)) })
        ListItem(headlineContent = { Text("Media preview cache") }, trailingContent = { Text(Formatter.formatFileSize(context, sizes.second)) })
        TextButton({
            scope.launch {
                pairing.chat.clearMediaCache()
                updateSize()
                instruction = "Preview cache cleared"
            }
        }, Modifier.padding(start = 12.dp), enabled = !busy) { Text("Clear preview cache") }
        HorizontalDivider(Modifier.padding(16.dp))
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("Auto-download media", style = MaterialTheme.typography.titleMedium)
            for (policy in MediaAutoDownload.entries) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(auto == policy, { auto = policy; pairing.mediaDownload(policy) })
                Text(policy.label)
            }
        }
        HorizontalDivider(Modifier.padding(16.dp))
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Encrypted backup", style = MaterialTheme.typography.titleMedium)
            Text("Includes this conversation, call history and locally downloaded attachments.")
            Button({ operation = false }, enabled = !busy && pairing.conversationId != null) { Text("Create backup") }
            OutlinedButton({ operation = true }, enabled = !busy && pairing.conversationId != null) { Text("Restore backup") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            instruction?.let { Text(it) }
        }
    }
    operation?.let { restoring ->
        AlertDialog(onDismissRequest = { operation = null; password = "" },
            title = { Text(if (restoring) "Restore encrypted backup" else "Create encrypted backup") },
            text = {
                Column {
                    if (restoring) Text("The backup is merged into the current paired conversation. Use your own backup.")
                    OutlinedTextField(password, { password = it.take(128) }, label = { Text("Password, at least 12 characters") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation())
                }
            },
            confirmButton = {
                TextButton({
                    operation = null
                    if (restoring) restore.launch(arrayOf("*/*")) else create.launch("SAOREO-Together.saoreobak")
                }, enabled = password.length >= 12) { Text("Continue") }
            }, dismissButton = { TextButton({ operation = null; password = "" }) { Text("Cancel") } })
    }
}
