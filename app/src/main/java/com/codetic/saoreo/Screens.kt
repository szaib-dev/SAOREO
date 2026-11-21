package com.codetic.saoreo

import android.Manifest
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import java.util.Locale

private val dark = darkColorScheme(
    primary = Color(0xffd0b9fa), onPrimary = Color(0xff34234e), primaryContainer = Color(0xff43325e),
    onPrimaryContainer = Color(0xffebdeff), secondaryContainer = Color(0xff34303d),
    onSecondaryContainer = Color(0xffece3f5), background = Color(0xff15141b), onBackground = Color(0xffeee9f2),
    surface = Color(0xff15141b), onSurface = Color(0xffeee9f2), surfaceContainer = Color(0xff23212b),
    surfaceContainerHigh = Color(0xff292631), outline = Color(0xff9b919e), outlineVariant = Color(0xff46404b),
)
private val light = lightColorScheme(
    primary = Color(0xff624695), onPrimary = Color.White, primaryContainer = Color(0xffecdeff),
    onPrimaryContainer = Color(0xff301750), secondaryContainer = Color(0xffeee7f4),
    onSecondaryContainer = Color(0xff3d304a), background = Color(0xfffbf8fc), onBackground = Color(0xff221e28),
    surface = Color(0xfffbf8fc), onSurface = Color(0xff221e28), surfaceContainer = Color(0xfff1edf5),
    surfaceContainerHigh = Color(0xffede8f1), outline = Color(0xff7b7083), outlineVariant = Color(0xffded5e4),
)

@Composable
internal fun AppTheme(settings: Settings, content: @Composable () -> Unit) {
    val useDark = settings.theme == "Dark" || settings.theme == "System" && isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (useDark) dark else light, content = content)
}

class MainActivity : ComponentActivity() {
    private var shortcutEvents by mutableIntStateOf(0)
    private var downloadEvents by mutableIntStateOf(0)
    private var transcriptEvents by mutableIntStateOf(0)

    companion object {
        internal const val OPEN_TRANSCRIPT = "com.codetic.saoreo.OPEN_TRANSCRIPT"
        internal const val OPEN_DOWNLOADS = "com.codetic.saoreo.OPEN_DOWNLOADS"
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ShortcutService.OPEN) shortcutEvents++
        if (intent.action == OPEN_DOWNLOADS) downloadEvents++
        if (intent.action == OPEN_TRANSCRIPT) transcriptEvents++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val start = SystemClock.elapsedRealtimeNanos()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (intent.action == ShortcutService.OPEN) shortcutEvents++
        if (intent.action == OPEN_DOWNLOADS) downloadEvents++
        if (intent.action == OPEN_TRANSCRIPT) transcriptEvents++

        val app = application as SaoreoApp
        setContent {
            val settings by app.preferences.flow.collectAsStateWithLifecycle(initialValue = Settings())
            AppTheme(settings) { MainScreen(app, settings, shortcutEvents, downloadEvents, transcriptEvents) }
            val view = LocalView.current
            LaunchedEffect(Unit) {
                view.post {
                    reportFullyDrawn()
                    val elapsed = SystemClock.elapsedRealtimeNanos() - start
                app.youtubeDiagnostics.recordActivity(elapsed)
                Log.i("SAOREO", "main_visible_ms=${elapsed / 1_000_000}")
                }
            }
        }
    }
}

class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val start = SystemClock.elapsedRealtimeNanos()
        super.onCreate(savedInstanceState)
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        val app = application as SaoreoApp
        val mediaType = intent.takeIf { it.action == Intent.ACTION_SEND }?.type.orEmpty()
        val text = if (mediaType == "text/plain")
            runCatching { intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.take(16_385)?.toString() }.getOrNull()
            else null
        val uri = if (mediaType.startsWith("image/") || mediaType.startsWith("video/"))
            runCatching { intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) }
                .getOrNull()?.takeIf { it.scheme == "content" }
            else null
        val link = text?.let(MediaLink::fromText)
        val playlist = text?.let(::supportedPlaylist)
        lifecycleScope.launch {
            try {
                val settings = app.preferences.flow.first()
                if (uri == null && settings.autoShare) {
                    if (link == null) toast(this@ShareActivity, if (playlist != null)
                        "Use Ask before downloading to choose playlist items." else "Share a supported media link.")
                    else {
                        app.downloads.enqueue(link, settings.copy(quality = settings.downloadQuality()), null, null,
                            DownloadOptions(settings.destination(instant = true),
                                settings.defaultPlaylist.takeIf { settings.defaultAudio }, bitrate = settings.audioBitrate))
                        toast(this@ShareActivity, "Downloading…")
                    }
                    finish()
                } else showSheet(app, settings, text, uri, start)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                toast(this@ShareActivity, (error as? SectionFailure)?.message ?: "Couldn’t queue the download. Open SAOREO and try again.")
                finish()
            }
        }
    }

    private fun showSheet(app: SaoreoApp, settings: Settings, text: String?, uri: Uri?, start: Long) {
        setFinishOnTouchOutside(true)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setContent {
            AppTheme(settings) {
                Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 3.dp) {
                    Column(
                        Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Mark()
                            Text("Save to SAOREO", Modifier.weight(1f).padding(start = 10.dp),
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            IconButton(onClick = ::finish) { Icon(Icons.Outlined.Close, "Close") }
                        }
                        if (uri != null) SharedFile(app, uri, ::finish)
                        else if (text != null && (MediaLink.fromText(text) != null || supportedPlaylist(text) != null))
                            LinkPanel(app, settings, text, share = true, close = ::finish)
                        else Text("Share a supported media link or a photo or video.")
                    }
                }
            }
            val view = LocalView.current
            LaunchedEffect(Unit) { view.post {
                reportFullyDrawn()
                val elapsed = SystemClock.elapsedRealtimeNanos() - start
                app.youtubeDiagnostics.recordActivity(elapsed)
                Log.i("SAOREO", "share_visible_ms=${elapsed / 1_000_000}")
            } }
        }
    }
}

@Composable
private fun SharedFile(app: SaoreoApp, uri: Uri, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    Text("Save the photo or video you shared to your gallery.")
    Button(onClick = {
        busy = true
        scope.launch {
            try {
                val (_, new) = app.statuses.save(app.statuses.fromUri(uri), Source.SHARED)
                toast(app, if (new) "Saved to gallery" else "Already saved")
                close()
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { message = "Couldn’t read or save the shared file." }
            finally { busy = false }
        }
    }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Saving…" else "Save") }
    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

private enum class Tab(val label: String, val icon: ImageVector?) {
    HOME("Home", Icons.Outlined.Home),
    DOWNLOADS("Downloads", Icons.Outlined.Download),
    MUSIC("Music", null),
    STATUS("Status", Icons.Outlined.PhotoLibrary),
    TOOLS("Tools", Icons.Outlined.Build),
}

@Composable
private fun MainScreen(app: SaoreoApp, settings: Settings, shortcutEvents: Int, downloadEvents: Int, transcriptEvents: Int) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    var input by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    var libraryAction by remember { mutableIntStateOf(0) }
    var jobsOnly by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val stateHolder = rememberSaveableStateHolder()
    val records by app.dao.observe().collectAsStateWithLifecycle(emptyList())
    val transfers by app.downloadNotices.progress.collectAsStateWithLifecycle()
    val player = rememberMusicController(app).first
    LaunchedEffect(shortcutEvents) { if (shortcutEvents > 0) { tab = Tab.HOME; page = null } }
    LaunchedEffect(transcriptEvents) { if (transcriptEvents > 0) { tab = Tab.TOOLS; page = "Transcript" } }
    LaunchedEffect(downloadEvents) { if (downloadEvents > 0) { tab = Tab.DOWNLOADS; page = null; jobsOnly = true } }
    BackHandler(page != null || search || jobsOnly && tab == Tab.DOWNLOADS) {
        if (page != null) page = null else if (search) search = false else jobsOnly = false
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 56.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (page != null) IconButton({ page = null }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                    else if (tab == Tab.HOME) {
                        Mark()
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(page ?: if (tab == Tab.HOME) "SAOREO" else tab.label, Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (page == null && tab == Tab.DOWNLOADS) {
                        IconButton({ search = !search }) { Icon(Icons.Outlined.Search, "Search downloads") }
                        Box {
                            IconButton({ overflow = true }) { Icon(Icons.Outlined.MoreVert, "Download options") }
                            DropdownMenu(overflow, { overflow = false }) {
                                DropdownMenuItem({ Text(if (jobsOnly) "Saved media" else "Download activity") }, {
                                    overflow = false
                                    jobsOnly = !jobsOnly
                                })
                                DropdownMenuItem({ Text("Import media") }, { overflow = false; libraryAction++ })
                            }
                        }
                    }
                    if (page != "Settings") IconButton({ page = "Settings" }) {
                        Icon(Icons.Outlined.Settings, "Settings")
                    }
                }
            }
        },
        bottomBar = {
            if (page == null) Column {
                MusicMiniPlayer(app, player, onOpen = { context.startActivity(MusicActivity.open(context, nowPlaying = true)) })
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    BoxWithConstraints(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)) {
                        val labelsFit = maxWidth >= (336 * LocalDensity.current.fontScale).dp
                        Row(Modifier.fillMaxWidth().selectableGroup().heightIn(min = 60.dp)) {
                            Tab.entries.forEach { item ->
                                val selected = tab == item
                                val accent by animateColorAsState(if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent, label = "Tab selection")
                                Column(Modifier.weight(1f).heightIn(min = 60.dp)
                                    .selectable(selected, role = Role.Tab, onClick = {
                                        tab = item
                                        search = false
                                        jobsOnly = false
                                    }).semantics { if (!labelsFit) contentDescription = item.label },
                                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    Box(Modifier.size(44.dp, 28.dp).background(accent, RoundedCornerShape(16.dp)),
                                        contentAlignment = Alignment.Center) {
                                        val tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        val icon = item.icon
                                        if (icon == null) Icon(painterResource(R.drawable.ic_music_note), null, Modifier.size(21.dp), tint)
                                        else Icon(icon, null, Modifier.size(21.dp), tint)
                                    }
                                    if (labelsFit) Text(item.label, Modifier.padding(top = 3.dp), maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner).imePadding()) {
            val modifier = Modifier.fillMaxSize()
            when (page) {
                "Settings" -> SettingsScreen(app, settings, modifier)
                "Transcript" -> stateHolder.SaveableStateProvider("transcript") { TranscriptScreen(app, modifier) }
                "Convert to MP3" -> ConversionTools(app, modifier)
                else -> when (tab) {
                    Tab.HOME -> Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Download from a link", style = MaterialTheme.typography.titleMedium)
                        stateHolder.SaveableStateProvider("home-link") {
                            LinkPanel(app, settings, input, onInput = { input = it })
                        }
                        val active = records.filter { it.active }.take(3)
                        if (active.isNotEmpty()) {
                            ListItem(headlineContent = { Text("Download activity") },
                                modifier = Modifier.clickable { tab = Tab.DOWNLOADS; jobsOnly = true })
                            active.forEach { item ->
                                val transfer = transfers[item.id]
                                ListItem(headlineContent = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = { Text(listOfNotNull(transfer?.stage ?: "Queued",
                                        transfer?.bytes?.takeIf { it > 0 }?.let(::sizeLabel)).joinToString(" · ")) },
                                    modifier = Modifier.clickable { tab = Tab.DOWNLOADS; jobsOnly = true })
                            }
                        }
                        val recent = records.filter { it.state == JobState.COMPLETE.name && it.galleryUri != null }.take(3)
                        if (recent.isNotEmpty()) {
                            Text("Recently saved", style = MaterialTheme.typography.titleMedium)
                            recent.forEach { item ->
                                ListItem(headlineContent = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = { Text(Source.valueOf(item.source).label) },
                                    leadingContent = { ThumbnailForDownload(item, Modifier.size(42.dp)) },
                                    modifier = Modifier.clickable { playSavedMedia(context, item) })
                            }
                        }
                    }
                    Tab.DOWNLOADS -> stateHolder.SaveableStateProvider("downloads") {
                        DownloadsScreen(app, settings, modifier, search, jobsOnly, libraryAction) { tab = Tab.HOME }
                    }
                    Tab.MUSIC -> stateHolder.SaveableStateProvider("music") {
                        MusicLibrary(app, null, false, {}, controller = player, embedded = true)
                    }
                    Tab.STATUS -> stateHolder.SaveableStateProvider("statuses") { StatusScreen(app, settings, modifier) }
                    Tab.TOOLS -> Column(modifier.padding(horizontal = 12.dp).verticalScroll(rememberScrollState())) {
                        ListItem(headlineContent = { Text("Transcript") },
                            supportingContent = { Text("Read, copy, and export captions") },
                            leadingContent = { Icon(Icons.AutoMirrored.Outlined.List, null) },
                            trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) },
                            modifier = Modifier.clickable { page = "Transcript" })
                        ListItem(headlineContent = { Text("MP4-to-MP3 conversion") },
                            supportingContent = { Text("Save audio from a video on your device") },
                            leadingContent = { Icon(painterResource(R.drawable.ic_music_note), null) },
                            trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) },
                            modifier = Modifier.clickable { page = "Convert to MP3" })
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversionTools(app: SaoreoApp, modifier: Modifier) {
    var file by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> file = uri?.toString() }
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button({ picker.launch(arrayOf("video/mp4")) }) { Text("Choose MP4") }
        ConversionProgress(app)
    }
    file?.let { ConversionSheet(app, Uri.parse(it), null, true) { file = null } }
}

@Composable
private fun Mark() {
    Image(painterResource(R.drawable.mark), null, Modifier.size(37.dp)
        .background(Color(0xff302743), RoundedCornerShape(12.dp)))
}

@Composable
private fun LinkPanel(
    app: SaoreoApp, settings: Settings, initial: String, share: Boolean = false,
    onInput: (String) -> Unit = {}, close: () -> Unit = {},
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val link = remember(initial) { MediaLink.fromText(initial) }
    var revision by rememberSaveable { mutableIntStateOf(0) }
    var preview by remember(link) { mutableStateOf<Preview?>(null) }
    var selection by remember(link) { mutableStateOf<Choice?>(null) }
    var selectedImages by remember(link) { mutableStateOf(setOf<String>()) }
    var loading by remember(link) { mutableStateOf(false) }
    var accepting by remember { mutableStateOf(false) }
    var error by remember(link) { mutableStateOf<String?>(null) }
    var actionError by remember(link) { mutableStateOf<String?>(null) }
    var audioPreset by rememberSaveable(link?.url) { mutableStateOf<Boolean?>(null) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val playlistLink = remember(initial) { supportedPlaylist(initial) }
    var playlistOpen by rememberSaveable(initial) { mutableStateOf(false) }
    var folder by rememberSaveable(link?.url, settings.destination()) { mutableStateOf(settings.destination()) }
    var playlist by rememberSaveable(link?.url, settings.defaultPlaylist) { mutableStateOf(settings.defaultPlaylist) }
    var bitrate by rememberSaveable(link?.url, settings.audioBitrate) { mutableIntStateOf(settings.audioBitrate) }
    var rangeOn by rememberSaveable(link?.url) { mutableStateOf(false) }
    var rangeStart by rememberSaveable(link?.url) { mutableStateOf("00:00") }
    var rangeEnd by rememberSaveable(link?.url) { mutableStateOf("") }
    var rangePreview by rememberSaveable(link?.url) { mutableStateOf(false) }
    val section = if (rangeOn) sectionFromText(rangeStart, rangeEnd, preview?.duration) else null
    val requestedQuality = settings.downloadQuality(audioPreset ?: settings.defaultAudio)

    fun setPreset(audio: Boolean) {
        audioPreset = audio
        val quality = if (audio) Quality.AUDIO
            else settings.quality.takeUnless { it == Quality.ASK || it == Quality.AUDIO } ?: Quality.BEST
        selection = preview?.let { preferredDownload(it.choices, quality) }
    }

    suspend fun accept() {
        val target = link ?: return
        if (accepting || rangeOn && section == null) return
        val metadata = preview
        val choices = if (selection == null && (target.source == Source.YOUTUBE || audioPreset != null && metadata == null)) listOf(null)
            else if (selection?.imageUrl != null && selectedImages.isNotEmpty())
                metadata?.choices.orEmpty().filter { it.id in selectedImages }
            else listOfNotNull(selection)
        if (choices.isEmpty()) return
        accepting = true
        actionError = null
        var accepted = 0
        try {
            for (choice in choices) {
                val audio = choice?.audioOnly ?: (requestedQuality == Quality.AUDIO)
                app.downloads.enqueue(target, settings.copy(quality = requestedQuality), metadata, choice,
                    DownloadOptions(folder.takeIf { settings.folderOrganization }, playlist.takeIf { audio }, section, bitrate))
                accepted++
            }
            if (settings.folderOrganization && settings.rememberFolder)
                app.preferences.set(SettingKeys.lastFolder, folder.orEmpty())
            if (settings.haptic) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            toast(context, "Downloading...")
            if (share) close()
            else if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                val asked = app.getSharedPreferences("download_ui", Context.MODE_PRIVATE)
                if (!asked.getBoolean("asked_notifications", false)) {
                    asked.edit().putBoolean("asked_notifications", true).apply()
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) {
            actionError = if (accepted == 0 && failure is SectionFailure) failure.message else if (accepted > 0) "$accepted queued. Android could not queue the remaining downloads."
                else "Android could not queue the download. Try again."
        } finally { accepting = false }
    }

    LaunchedEffect(link, revision, settings.timeout) {
        if (link == null) return@LaunchedEffect
        val trace = app.youtubeDiagnostics.open(link.source, if (share) "share" else "home")
        val timingStart = SystemClock.elapsedRealtimeNanos()
        val debounceAt = SystemClock.elapsedRealtimeNanos()
        if (!share && revision == 0) kotlinx.coroutines.delay(260)
        trace?.add("ui.debounce", SystemClock.elapsedRealtimeNanos() - debounceAt)
        loading = true
        error = null
        try {
            val result = app.extractor.preview(link, settings.timeout, trace)
            val delivered = SystemClock.elapsedRealtimeNanos()
            trace?.add("ui.result_received", delivered - timingStart)
            preview = result
            val preferred = settings.downloadQuality(audioPreset ?: settings.defaultAudio)
            selection = preferredDownload(result.choices, preferred)
                ?: result.choices.firstOrNull().takeIf { preferred != Quality.AUDIO && settings.quality == Quality.ASK && audioPreset == null }
            selectedImages = setOfNotNull(selection?.takeIf { it.imageUrl != null }?.id)
            trace?.add("ui.state_applied", SystemClock.elapsedRealtimeNanos() - delivered)
        } catch (_: TimeoutCancellationException) {
            error = "The source took too long to respond."
            trace?.add("ui.timeout")
        } catch (cancel: CancellationException) {
            trace?.add("ui.cancelled")
            throw cancel
        } catch (failure: Exception) {
            error = mediaFailure(failure.message)
            trace?.add("ui.error")
        } finally {
            loading = false
            trace?.add("ui.finished", SystemClock.elapsedRealtimeNanos() - timingStart)
        }
        if (trace != null) withFrameNanos { trace.add("ui.next_frame") }
    }
    LaunchedEffect(settings.quality, settings.defaultAudio) {
        if (audioPreset == null && preview != null) {
            selection = preview?.let { preferredDownload(it.choices, requestedQuality) }
        }
    }


    if (!share) {
        OutlinedTextField(
            value = initial, onValueChange = {
                val value = it.take(16_385)
                onInput(value)
            }, singleLine = true,
            label = { Text("Media link") }, placeholder = { Text("Paste a link to get started") },
            leadingIcon = { Icon(Icons.Outlined.Link, null) },
            trailingIcon = {
                if (initial.isNotBlank()) IconButton(onClick = { onInput("") }) { Icon(Icons.Outlined.Close, "Clear link") }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); if (!loading) revision++ }),
            shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                if (text.isNullOrBlank()) toast(context, "Clipboard is empty")
                else {
                    val value = text.take(16_385)
                    onInput(value)
                    focus.clearFocus()
                }
            }, Modifier.weight(1f)) {
                Icon(Icons.Outlined.ContentPaste, null, Modifier.size(17.dp))
                Spacer(Modifier.width(7.dp))
                Text("Paste")
            }
        }
    }
    if (link != null) {
        val result = preview
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Thumb(result?.thumbnail, Modifier.size(54.dp).clip(RoundedCornerShape(8.dp)))
            Column(Modifier.weight(1f)) {
                Text(result?.title ?: link.source.label, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall)
                Text(listOfNotNull(link.source.label.takeIf { result != null },
                    result?.duration?.toLong()?.let(::durationLabel),
                    "Fetching details".takeIf { loading }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val audio = selection?.audioOnly ?: (requestedQuality == Quality.AUDIO)
        if (result?.choices?.any { it.imageUrl != null } != true) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!audio, onClick = { setPreset(false) }, label = { Text("MP4") },
                    enabled = result == null || result.choices.any { !it.audioOnly })
                FilterChip(audio, onClick = { setPreset(true) }, label = { Text("MP3") },
                    enabled = result == null || result.choices.any { it.audioOnly })
            }
        }
        if (result != null) {
            if (result.choices.count { it.imageUrl != null } > 1) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    result.choices.filter { it.imageUrl != null }.forEachIndexed { index, option ->
                        FilterChip(option.id in selectedImages, onClick = {
                            val next = if (option.id in selectedImages) selectedImages - option.id else selectedImages + option.id
                            if (next.isNotEmpty()) { selectedImages = next; selection = option }
                        }, label = { Text("${index + 1}") })
                    }
                }
            }
            val formatChoices = result.choices.filter { it.imageUrl != null || it.audioOnly == audio }
            if (formatChoices.size > 1 || selection == null && formatChoices.isNotEmpty()) {
                ChoiceMenu(selection?.description() ?: "Quality", formatChoices, { it.description() }) {
                    selection = it
                    if (it.imageUrl != null) selectedImages = setOf(it.id)
                }
            } else selection?.let { Text(it.description(), style = MaterialTheme.typography.bodySmall) }
        }
        if (result?.choices?.any { it.imageUrl != null } != true) {
            FilterChip(rangeOn, { rangeOn = !rangeOn }, label = { Text("Download section") })
            if (rangeOn) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(rangeStart, { rangeStart = it.take(16) }, Modifier.weight(1f),
                        label = { Text("Start") }, placeholder = { Text("01:20") }, singleLine = true)
                    OutlinedTextField(rangeEnd, { rangeEnd = it.take(16) }, Modifier.weight(1f),
                        label = { Text("End") }, placeholder = { Text("02:05") }, singleLine = true)
                }
                Text(section?.let { "${playbackTime(it.duration)} selected" }
                    ?: "Enter a valid start and end in MM:SS or HH:MM:SS.",
                    style = MaterialTheme.typography.bodySmall)
                Text("If the source cannot serve a range, SAOREO downloads the full item before trimming on this device.",
                    style = MaterialTheme.typography.bodySmall)
                TextButton({ rangePreview = true }, enabled = section != null) { Text("Preview saved media") }
                if (rangePreview) SectionEditor(app, link, section) { rangePreview = false }
            }
        }
        DestinationAndPlaylist(app, settings, audio, folder, playlist, bitrate,
            { folder = it }, { playlist = it }, { bitrate = it })
        Button(
            onClick = { focus.clearFocus(); scope.launch { accept() } },
            enabled = !accepting && (!rangeOn || section != null && selection?.imageUrl == null) &&
                (selection != null || link.source == Source.YOUTUBE && !rangeOn || result == null && audioPreset != null),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(when {
                accepting -> "Queuing…"
                selectedImages.size > 1 -> "Save ${selectedImages.size} images"
                selection?.imageUrl != null -> "Save image"
                else -> "Download"
            })
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { revision++ }) { Text("Retry preview") }
        }
        actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    } else if (!share && playlistLink == null) {
        if (initial.isNotBlank()) Text("Enter a supported media link.", color = MaterialTheme.colorScheme.error)
        else Text("YouTube, Instagram, TikTok, X, Snapchat and Pinterest",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (playlistLink != null) {
        OutlinedButton({ playlistOpen = true }, Modifier.fillMaxWidth()) { Text("Choose playlist items") }
        if (playlistOpen) PlaylistDownloadDialog(app, settings, playlistLink, { playlistOpen = false }, close.takeIf { share })
    }

}

@Composable
fun Thumb(model: Any?, modifier: Modifier = Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.PermMedia, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (model != null) AsyncImage(model, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
fun <T> ChoiceMenu(label: String, options: List<T>, text: (T) -> String, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(label, maxLines = 2)
            Icon(Icons.Outlined.ArrowDropDown, null)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(text(option)) }, onClick = { expanded = false; onSelect(option) })
            }
        }
    }
}

fun toast(context: Context, message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

fun durationLabel(seconds: Long): String =
    String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)

fun showMedia(context: Context, uri: Uri, mime: String, share: Boolean, chooseApp: Boolean = false) {
    val intent = if (share) Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
        else Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    intent.clipData = android.content.ClipData.newUri(context.contentResolver, "SAOREO media", uri)
    runCatching {
        context.startActivity(if (share || chooseApp) Intent.createChooser(intent, if (share) "Share media" else "Open with") else intent)
    }.onFailure { toast(context, "This file could not be opened.") }
}
