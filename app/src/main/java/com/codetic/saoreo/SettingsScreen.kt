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

    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Section("Share behavior")
        SettingChoice("Shared links", settings.autoShare, listOf(false, true),
            { if (it) "Download instantly" else "Ask before downloading" }) { set(SettingKeys.autoShare, it) }
        Section("Download quality")
        SettingChoice("Default media type", settings.defaultAudio, listOf(false, true),
            { if (it) "Audio" else "Video" }) { set(SettingKeys.defaultAudio, it) }
        SettingChoice("Default video quality", settings.downloadQuality(false),
            Quality.entries.filter { it !in listOf(Quality.ASK, Quality.AUDIO) }, { it.label }) {
            set(SettingKeys.quality, it.name)
        }
        SettingChoice("Default audio quality", settings.audioBitrate, listOf(128, 192, 256, 320), { "$it kbps" }) {
            set(SettingKeys.audioBitrate, it)
        }
        Section("Storage and folders")
        SettingSwitch("Folder organization", settings.folderOrganization, "Browse nested folders in Downloads.") {
            set(SettingKeys.folderOrganization, it)
        }
        if (settings.folderOrganization) {
            val folders by app.collections.folders.collectAsStateWithLifecycle(emptyList())
            var pickFolder by remember { mutableStateOf(false) }
            ListItem(headlineContent = { Text("Default download destination") },
                supportingContent = { Text(folderLabel(folders, settings.defaultFolder)) },
                modifier = Modifier.clickable { pickFolder = true })
            if (pickFolder) DestinationDialog(app, settings.defaultFolder, { pickFolder = false }) {
                set(SettingKeys.defaultFolder, it)
            }
            SettingSwitch("Remember last destination", settings.rememberFolder,
                "Ask uses the last folder. Instant uses the default above.") { set(SettingKeys.rememberFolder, it) }
        }
        Section("Music and playback")
        val playlists by app.collections.playlists.collectAsStateWithLifecycle(emptyList())
        SettingChoice("Default music playlist", playlists.firstOrNull { it.id == settings.defaultPlaylist },
            listOf<MusicPlaylist?>(null) + playlists, { it?.name ?: "None" }) { set(SettingKeys.defaultPlaylist, it?.id) }
        Section("Network and advanced")
        SettingChoice("Simultaneous downloads", settings.concurrency, listOf(1, 2, 3), { it.toString() }) {
            set(SettingKeys.concurrency, it)
        }
        SettingChoice("Connection", settings.wifi, listOf(false, true),
            { if (it) "Unmetered only" else "Wi-Fi or mobile data" }) { set(SettingKeys.wifi, it) }
        SettingSwitch("Retry temporary failures", settings.retry) { set(SettingKeys.retry, it) }
        SettingSwitch("Resume interrupted downloads", settings.resume) { set(SettingKeys.resume, it) }
        ListItem(headlineContent = { Text("Advanced") }, trailingContent = {
            Icon(if (advanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
        }, modifier = Modifier.clickable { advanced = !advanced })
        if (advanced) {
            SettingChoice("Socket timeout", settings.timeout, listOf(10, 15, 25, 40, 60), { "$it seconds" }) {
                set(SettingKeys.timeout, it)
            }
            SettingChoice("Retry limit", settings.retries, (0..5).toList(), { it.toString() }) {
                set(SettingKeys.retries, it)
            }
        }

        var showDevice by rememberSaveable { mutableStateOf(false) }
        ListItem(headlineContent = { Text("Device and permissions") }, trailingContent = {
            Icon(if (showDevice) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
        }, modifier = Modifier.clickable { showDevice = !showDevice })
        if (showDevice) {
            Section("WhatsApp Status folders")
            listOf(Source.WHATSAPP to settings.whatsapp, Source.BUSINESS to settings.business).forEach { (source, folder) ->
                val granted = folder != null && app.contentResolver.persistedUriPermissions.any {
                    it.isReadPermission && it.uri == Uri.parse(folder)
                }
                ListItem(headlineContent = { Text(source.label) }, supportingContent = {
                    Text(if (granted) "Read access granted" else "Choose a status folder")
                }, trailingContent = {
                    TextButton(onClick = { pendingFolder = source; picker.launch(null) }) {
                        Text(if (granted) "Change" else "Connect")
                    }
                })
                if (folder != null) TextButton(onClick = { act { app.statuses.connect(source, null) } }) {
                    Text("Disconnect ${source.label}")
                }
            }
            SettingSwitch("Refresh statuses on return", settings.statusRefresh) { set(SettingKeys.statusRefresh, it) }

            Section("Appearance")
            SettingChoice("Theme", settings.theme, listOf("System", "Light", "Dark"), { it }) { set(SettingKeys.theme, it) }
            SettingSwitch("Haptic feedback", settings.haptic) { set(SettingKeys.haptic, it) }

            Section("Notifications")
            SettingSwitch("Show completion notifications", settings.completion) { set(SettingKeys.completion, it) }
            SettingSwitch("Show failure notifications", settings.failure) { set(SettingKeys.failure, it) }
            ListItem(headlineContent = { Text("Download progress") },
                supportingContent = { Text("Android uses a foreground notification for active transfers.") },
                trailingContent = {
                    if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                        TextButton(onClick = { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Enable") }
                })

            Section("Floating Button")
            val overlayGranted = AndroidSettings.canDrawOverlays(context)
            SettingSwitch("Floating shortcut", settings.overlay && overlayGranted,
                if (overlayGranted) "Overlay permission granted" else "Android overlay permission required") { enabled ->
                if (!enabled) hideOverlay()
                else if (overlayGranted) showOverlay()
                else overlayPermission.launch(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")))
            }
            if (settings.overlay && overlayGranted) {
                SettingChoice("Button size", settings.overlaySize, listOf(42, 48, 56, 64), { "$it dp" }) {
                    set(SettingKeys.overlaySize, it)
                }
                SettingChoice("Opacity", settings.overlayOpacity, listOf(45, 60, 75, 90, 100), { "$it%" }) {
                    set(SettingKeys.overlayOpacity, it)
                }
                TextButton(onClick = { hideOverlay() }) { Text("Hide button") }
            }

            Section("Privacy and Storage")
            ListItem(headlineContent = { Text("Thumbnail cache") }, supportingContent = { Text(sizeLabel(cacheBytes)) },
                trailingContent = {
                    TextButton(onClick = { act {
                        app.extractor.clearCache()
                        coil.Coil.imageLoader(app).memoryCache?.clear()
                        withContext(Dispatchers.IO) { coil.Coil.imageLoader(app).diskCache?.clear() }
                        cacheRevision++
                    } }) { Text("Clear") }
                })
            ListItem(headlineContent = { Text("Clear download history") },
                supportingContent = { Text("Completed and failed records are removed. Media stays in the gallery.") },
                modifier = Modifier.clickable { confirmation = "history" })
            ListItem(headlineContent = { Text("Diagnostics") }, modifier = Modifier.clickable { confirmation = "diagnostics" })
            ListItem(headlineContent = { Text("Open-source components") }, modifier = Modifier.clickable { confirmation = "licenses" })

            Section("About")
            val info = remember { app.packageManager.getPackageInfo(app.packageName, 0) }
            ListItem(headlineContent = { Text("SAOREO ${info.versionName}") },
                supportingContent = { Text("Build ${info.longVersionCode} · API ${Build.VERSION.SDK_INT}\nyt-dlp ${Extractor.ENGINE_VERSION} · Android wrapper 0.18.1") })
        }
        Spacer(Modifier.height(12.dp))
    }

    if (confirmation == "diagnostics") {
        YoutubeDiagnosticsDialog(app, onClose = { confirmation = null })
    } else confirmation?.let { dialog ->
        val title = when (dialog) {
            "history" -> "Clear download history?"
            else -> "Open-source components"
        }
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(title) }, text = {
            Text(when (dialog) {
                "history" -> "Removes finished history entries. Saved gallery files remain available."
                else -> "AndroidX, Compose, Coil and OkHttp (Apache 2.0)\nyoutubedl-android (GPL-3.0)\nyt-dlp (Unlicense)\nPython (PSF) · QuickJS (MIT)\nFFmpeg, bundled by youtubedl-android"
            })
        }, confirmButton = {
            TextButton(onClick = {
                confirmation = null
                if (dialog == "history") act { app.downloads.clearHistory() }
            }) { Text(if (dialog == "history") "Clear" else "Close") }
        }, dismissButton = if (dialog == "history") {
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
private fun Section(name: String) {
    if (name != "Downloads") HorizontalDivider(Modifier.padding(top = 9.dp))
    Text(name, modifier = Modifier.padding(top = 15.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun SettingSwitch(name: String, active: Boolean, explanation: String? = null, change: (Boolean) -> Unit) {
    ListItem(headlineContent = { Text(name) },
        supportingContent = explanation?.let { { Text(it) } },
        trailingContent = { Switch(checked = active, onCheckedChange = change) })
}

@Composable
private fun <T> SettingChoice(name: String, selection: T, choices: List<T>, label: (T) -> String, select: (T) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        ListItem(headlineContent = { Text(name) }, supportingContent = { Text(label(selection)) },
            trailingContent = { Icon(Icons.Outlined.ArrowDropDown, null) },
            modifier = Modifier.clickable { menu = true })
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            choices.forEach { option ->
                DropdownMenuItem(text = { Text(label(option)) }, onClick = { menu = false; select(option) })
            }
        }
    }
}
