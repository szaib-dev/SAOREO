package com.codetic.saoreo

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings as AndroidSettings
import androidx.core.content.ContextCompat
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(coil.annotation.ExperimentalCoilApi::class)
@Composable
fun SettingsScreen(app: SaoreoApp, settings: Settings, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val screenCapture by app.screenCaptureProtection.enabled.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pendingFolder by rememberSaveable { mutableStateOf(Source.WHATSAPP) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var cacheBytes by remember { mutableLongStateOf(0L) }
    var cacheRevision by remember { mutableIntStateOf(0) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (!allowed) toast(context, "You can grant notifications in Android settings.")
    }
    fun act(block: suspend () -> Unit) {
        scope.launch {
            try { block() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { toast(context, "Couldn’t update this setting.") }
        }
    }
    fun <T> set(key: Preferences.Key<T>, value: T?) = act { app.preferences.set(key, value) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) act { app.statuses.connect(pendingFolder, uri) }
    }
    fun showOverlay() = act {
        app.preferences.set(SettingKeys.overlay, true)
        try { ContextCompat.startForegroundService(context, Intent(context, ShortcutService::class.java)) }
        catch (failure: Exception) {
            app.preferences.set(SettingKeys.overlay, false)
            throw failure
        }
    }
    val overlayPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (AndroidSettings.canDrawOverlays(context)) showOverlay()
    }
    fun hideOverlay() = act {
        app.preferences.set(SettingKeys.overlay, false)
        context.stopService(Intent(context, ShortcutService::class.java))
    }
    LaunchedEffect(cacheRevision) {
        cacheBytes = withContext(Dispatchers.IO) {
            app.cacheDir.resolve("image_cache").walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
    }

    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeading("Make it yours.", "Your library. Your preferences.")
        Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Outlined.Person, null, Modifier.size(36.dp))
                Column {
                    Text("Your offline space", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Saved on this device. Ready when you are.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        PreferenceGroup("Look & feel") {
            SettingChoice("Appearance", settings.theme, listOf("System", "Light", "Dark"), { it }) { set(SettingKeys.theme, it) }
            SettingSwitch("Haptic feedback", settings.haptic, "A little feedback when a download starts.") { set(SettingKeys.haptic, it) }
        }
        PreferenceGroup("Privacy") {
            SettingSwitch("Screen Capture Protection", screenCapture,
                "Hide SAOREO from screenshots, recordings, and recent-app previews. SAOREO will also be hidden when you share your screen.") {
                app.screenCaptureProtection.setEnabled(it)
            }
            TextButton({ confirmation = "forgetCallDevices" }) { Text("Forget verified call devices") }
        }
        PreferenceGroup("Downloads") {
            SettingChoice("Link confirmation", settings.autoShare, listOf(false, true),
                { if (it) "Instant download (skip confirmation)" else "Preview and choose quality" }) { set(SettingKeys.autoShare, it) }
            SettingChoice("Save as", settings.defaultAudio, listOf(false, true),
                { if (it) "Music · MP3" else "Video · MP4" }) { set(SettingKeys.defaultAudio, it) }
            SettingChoice("Video quality", settings.downloadQuality(false),
                Quality.entries.filter { it !in listOf(Quality.ASK, Quality.AUDIO) }, { it.label }) { set(SettingKeys.quality, it.name) }
            SettingChoice("Audio bitrate", settings.audioBitrate, listOf(128, 192, 256, 320), { "$it kbps" }) { set(SettingKeys.audioBitrate, it) }
            SettingChoice("Connection", settings.wifi, listOf(false, true),
                { if (it) "Unmetered only" else "Wi-Fi or mobile data" }) { set(SettingKeys.wifi, it) }
            SettingChoice("Simultaneous downloads", settings.concurrency, listOf(1, 2, 3), { it.toString() }) { set(SettingKeys.concurrency, it) }
            SettingSwitch("Retry temporary failures", settings.retry) { set(SettingKeys.retry, it) }
            SettingSwitch("Resume interrupted downloads", settings.resume) { set(SettingKeys.resume, it) }
        }
        PreferenceGroup("Library & storage") {
            val playlists by app.collections.playlists.collectAsStateWithLifecycle(emptyList())
            SettingChoice("Save new audio to playlist", playlists.firstOrNull { it.id == settings.defaultPlaylist },
                listOf<MusicPlaylist?>(null) + playlists, { it?.name ?: "Library only" }) { set(SettingKeys.defaultPlaylist, it?.id) }
            SettingSwitch("Organize downloads in folders", settings.folderOrganization) { set(SettingKeys.folderOrganization, it) }
            if (settings.folderOrganization) {
                val folders by app.collections.folders.collectAsStateWithLifecycle(emptyList())
                var pickFolder by remember { mutableStateOf(false) }
                ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text("Default download folder") }, supportingContent = { Text(folderLabel(folders, settings.defaultFolder)) },
                    modifier = Modifier.clickable { pickFolder = true })
                if (pickFolder) DestinationDialog(app, settings.defaultFolder, { pickFolder = false }) { set(SettingKeys.defaultFolder, it) }
                SettingSwitch("Remember last destination", settings.rememberFolder) { set(SettingKeys.rememberFolder, it) }
            }
            ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text("Thumbnail cache") }, supportingContent = { Text(sizeLabel(cacheBytes)) },
                trailingContent = { TextButton({ act {
                    app.extractor.clearCache()
                    coil.Coil.imageLoader(app).memoryCache?.clear()
                    withContext(Dispatchers.IO) { coil.Coil.imageLoader(app).diskCache?.clear() }
                    cacheRevision++
                } }) { Text("Clear") } })
            ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text("Clear download history") }, supportingContent = { Text("Keeps your saved files and music library.") },
                modifier = Modifier.clickable { confirmation = "history" })
        }
        PreferenceGroup("Device & permissions") {
            listOf(Source.WHATSAPP to settings.whatsapp, Source.BUSINESS to settings.business).forEach { (source, folder) ->
                val granted = folder != null && app.contentResolver.persistedUriPermissions.any { it.isReadPermission && it.uri == Uri.parse(folder) }
                ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text("${source.label} status folder") }, supportingContent = { Text(if (granted) "Connected" else "Choose a folder") },
                    trailingContent = { TextButton({ pendingFolder = source; picker.launch(null) }) { Text(if (granted) "Change" else "Connect") } })
            }
            SettingSwitch("Refresh statuses automatically", settings.statusRefresh) { set(SettingKeys.statusRefresh, it) }
            SettingSwitch("Completion notifications", settings.completion) { set(SettingKeys.completion, it) }
            SettingSwitch("Failure notifications", settings.failure) { set(SettingKeys.failure, it) }
            val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text("Notification permission") }, supportingContent = { Text(if (granted) "Allowed" else "Keep up with downloads in the background") },
                trailingContent = { if (!granted) TextButton({ notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Enable") } })
            val overlayGranted = AndroidSettings.canDrawOverlays(context)
            SettingSwitch("Floating download shortcut", settings.overlay && overlayGranted) { enabled ->
                if (!enabled) hideOverlay() else if (overlayGranted) showOverlay()
                else overlayPermission.launch(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
            }
            if (settings.overlay && overlayGranted) {
                SettingChoice("Shortcut size", settings.overlaySize, listOf(42, 48, 56, 64), { "$it dp" }) { set(SettingKeys.overlaySize, it) }
                SettingChoice("Shortcut opacity", settings.overlayOpacity, listOf(45, 60, 75, 90, 100), { "$it%" }) { set(SettingKeys.overlayOpacity, it) }
            }
        }
        PreferenceGroup("Advanced & about") {
            ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text("Advanced network settings") }, trailingContent = {
                Icon(if (advanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            }, modifier = Modifier.clickable { advanced = !advanced })
            if (advanced) {
                SettingChoice("Socket timeout", settings.timeout, listOf(10, 15, 25, 40, 60), { "$it seconds" }) { set(SettingKeys.timeout, it) }
                SettingChoice("Retry limit", settings.retries, (0..5).toList(), { it.toString() }) { set(SettingKeys.retries, it) }
            }
            ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text("YouTube diagnostics") }, modifier = Modifier.clickable { confirmation = "diagnostics" })
            ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text("Open-source components") }, modifier = Modifier.clickable { confirmation = "licenses" })
            val info = remember { app.packageManager.getPackageInfo(app.packageName, 0) }
            ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text("SAOREO ${info.versionName}") }, supportingContent = {
                Text("Build ${info.longVersionCode} · Android ${Build.VERSION.SDK_INT}\nyt-dlp ${Extractor.ENGINE_VERSION}")
            })
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmation == "diagnostics") {
        YoutubeDiagnosticsDialog(app, onClose = { confirmation = null })
    } else confirmation?.let { dialog ->
        val title = when (dialog) {
            "history" -> "Clear download history?"
            "forgetCallDevices" -> "Forget the verified call device?"
            else -> "Open-source components"
        }
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(title) }, text = {
            Text(when (dialog) {
                "history" -> "Removes finished history entries. Saved gallery files remain available."
                "forgetCallDevices" -> "To call again, unpair in Together and share a new secure pairing link."
                else -> "AndroidX, Compose, Coil and OkHttp (Apache 2.0)\nyoutubedl-android (GPL-3.0)\nyt-dlp (Unlicense)\nPython (PSF) · QuickJS (MIT)\nFFmpeg, bundled by youtubedl-android"
            })
        }, confirmButton = {
            TextButton(onClick = {
                confirmation = null
                if (dialog == "history") act { app.downloads.clearHistory() }
                if (dialog == "forgetCallDevices") CallIdentity(app).forgetAll()
            }) { Text(when (dialog) { "history" -> "Clear"; "forgetCallDevices" -> "Forget"; else -> "Close" }) }
        }, dismissButton = if (dialog in setOf("history", "forgetCallDevices")) {
            { TextButton(onClick = { confirmation = null }) { Text("Cancel") } }
        } else null)
    }
}

@Composable
private fun YoutubeDiagnosticsDialog(app: SaoreoApp, onClose: () -> Unit) {
    val context = LocalContext.current
    val mode by app.youtubeDiagnostics.mode.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("YouTube diagnostics") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Choose a capture mode, then analyze a public YouTube link. Copy the report here after the run. Reports stay in memory until SAOREO exits.")
                SettingChoice("Capture mode", mode, YoutubeTraceMode.entries, { it.label }, app.youtubeDiagnostics::select)
                TextButton(onClick = {
                    app.extractor.clearCache()
                    toast(context, "Extraction cache cleared")
                }) { Text("Clear extraction cache") }
                TextButton(onClick = {
                    app.youtubeDiagnostics.clear()
                    toast(context, "Reports cleared")
                }) { Text("Clear reports") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val report = app.youtubeDiagnostics.export()
                if (report.isBlank()) toast(context, "No YouTube captures yet")
                else {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("SAOREO performance report", report))
                    toast(context, "Report copied")
                }
            }) { Text("Copy report") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

@Composable
private fun PreferenceGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Eyebrow(title, Modifier.padding(start = 6.dp, top = 10.dp))
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), content = content)
        }
    }
}

@Composable
private fun SettingSwitch(name: String, active: Boolean, explanation: String? = null, change: (Boolean) -> Unit) {
    ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text(name) },
        supportingContent = explanation?.let { { Text(it) } },
        trailingContent = { Switch(checked = active, onCheckedChange = change) })
}

@Composable
private fun <T> SettingChoice(name: String, selection: T, choices: List<T>, label: (T) -> String, select: (T) -> Unit) {
    ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text(name) },
        trailingContent = { Box(Modifier.widthIn(max = 170.dp)) { ChoiceMenu(label(selection), choices, label, select) } })
}
