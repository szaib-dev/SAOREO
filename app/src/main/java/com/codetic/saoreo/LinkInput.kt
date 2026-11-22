package com.codetic.saoreo

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LinkInput(app: SaoreoApp, settings: Settings, modifier: Modifier = Modifier, onEditing: (Boolean) -> Unit = {}, onQueued: () -> Unit = {}) {
    var text by rememberSaveable { mutableStateOf("") }
    var confirming by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var hadKeyboard by remember { mutableStateOf(false) }
    LaunchedEffect(keyboard) {
        if (keyboard) hadKeyboard = true else if (hadKeyboard) {
            hadKeyboard = false; focus.clearFocus(); onEditing(false)
        }
    }
    val link = remember(text) { MediaLink.fromText(text.trim()) }
    val playlist = remember(text) { supportedPlaylist(text.trim()) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun accepted() {
        text = ""; confirming = false; onQueued()
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !app.getSharedPreferences("download_ui", Context.MODE_PRIVATE).getBoolean("asked_notifications", false)) {
            app.getSharedPreferences("download_ui", Context.MODE_PRIVATE).edit().putBoolean("asked_notifications", true).apply()
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    fun submit() {
        if (busy) return
        focus.clearFocus()
        if (link == null && playlist == null) { error = "Enter a supported video or music URL."; return }
        error = null
        if (!settings.autoShare || playlist != null) { confirming = true; return }
        val target = link ?: return
        scope.launch {
            busy = true
            try {
                app.downloads.enqueue(target, settings.copy(quality = settings.downloadQuality()), null, null,
                    DownloadOptions(settings.destination(instant = true), settings.defaultPlaylist.takeIf { settings.defaultAudio }, bitrate = settings.audioBitrate))
                accepted()
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { error = failure.message ?: "Could not queue the download." }
            finally { busy = false }
        }
    }
    Column(modifier) {
        OutlinedTextField(text, { text = it.take(16_384); error = null }, Modifier.fillMaxWidth().onFocusChanged { onEditing(it.isFocused) },
            placeholder = { Text("Paste a video or music link...", maxLines = 1) },
            singleLine = true, shape = RoundedCornerShape(24.dp), isError = error != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { submit() }),
            trailingIcon = {
                Row(Modifier.padding(end = 4.dp)) {
                    IconButton({
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        val value = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                        if (value.isBlank()) error = "Copy a link first." else { text = value.take(16_384); error = null }
                    }, enabled = !busy) { Icon(Icons.Outlined.ContentPaste, "Paste link") }
                    FilledIconButton({ submit() }, enabled = !busy, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Lime, contentColor = Ink)) {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Outlined.Download, "Download link")
                    }
                }
            })
        if (error != null) Text(requireNotNull(error), Modifier.padding(start = 12.dp, top = 4.dp),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        else if (link != null || playlist != null) Text(if (playlist != null) "YouTube playlist" else requireNotNull(link).source.label,
            Modifier.padding(start = 12.dp, top = 4.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (confirming) ModalBottomSheet(onDismissRequest = { confirming = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()
            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Ready to save", style = MaterialTheme.typography.headlineSmall)
            LinkPanel(app, settings, text, share = true, close = { confirming = false },
                onQueued = { accepted() }, requirePreview = true)
        }
    }
}
