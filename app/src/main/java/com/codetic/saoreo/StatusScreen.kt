@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.codetic.saoreo

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

private enum class StatusOperation(val title: String, val message: String) {
    HIDE("Hide from SAOREO?", "The source and any saved copy stay on your device."),
    SAVED("Delete the saved copy?", "The status cache file stays on your device."),
    SOURCE("Delete this local cache file?", "This permanently deletes the selected file from the status folder on this device."),
}

@Composable
fun StatusScreen(app: SaoreoApp, settings: Settings, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by rememberSaveable { mutableStateOf(Source.WHATSAPP) }
    var filter by rememberSaveable { mutableStateOf("All") }
    var revision by remember { mutableIntStateOf(0) }
    var scanned by remember(source) { mutableStateOf(emptyList<LocalMedia>()) }
    var imports by remember { mutableStateOf(mapOf<Source, List<LocalMedia>>()) }
    var selected by remember(source) { mutableStateOf(setOf<Uri>()) }
    var viewer by remember { mutableStateOf<Pair<List<LocalMedia>, Int>?>(null) }
    var confirm by remember { mutableStateOf<Pair<LocalMedia, StatusOperation>?>(null) }
    var menu by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember(source) { mutableStateOf<String?>(null) }
    var pickerSource by rememberSaveable { mutableStateOf(Source.WHATSAPP) }
    var requestedWrite by rememberSaveable { mutableStateOf(false) }
    val folder = if (source == Source.WHATSAPP) settings.whatsapp else settings.business
    val history by app.dao.observe().collectAsStateWithLifecycle(emptyList())
    val saved = remember(history, source) { history.filter { it.source == source.name && it.galleryUri != null }.associateBy { it.url } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.data?.let { uri ->
            val write = requestedWrite && ((result.data?.flags ?: 0) and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0
            scope.launch {
                try {
                    app.statuses.connect(pickerSource, uri, write)
                    revision++
                } catch (_: SecurityException) { error = "Folder access was denied. Import files or choose another folder." }
                catch (_: Exception) { error = "Couldn’t open this folder." }
            }
        }
    }
    fun pickFolder(write: Boolean) {
        pickerSource = source
        requestedWrite = write
        picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0).apply {
            folder?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(it)) }
        })
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val target = pickerSource
        scope.launch {
            val files = uris.distinct().mapNotNull { uri ->
                val item = try { app.statuses.fromUri(uri) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { null }
                item?.also { app.statuses.rememberImport(target, uri) }
            }
            if (files.isNotEmpty()) imports = imports + (target to (imports[target].orEmpty() + files).distinctBy { it.uri })
            else if (uris.isNotEmpty()) toast(context, "No supported status media selected.")
        }
    }
    fun importFiles() { pickerSource = source; importer.launch(arrayOf("image/*", "video/*")) }
    fun save(items: List<LocalMedia>) {
        if (saving) return
        val target = source
        scope.launch {
            saving = true
            var count = 0
            var failed = 0
            try {
                items.forEach { media ->
                    try { if (app.statuses.save(media, target).second) count++ }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { failed++ }
                }
                toast(context, when {
                    failed > 0 -> "Saved $count. Couldn’t save $failed."
                    count == 0 -> "Already saved"
                    else -> "Saved to gallery"
                })
                selected = emptySet()
            } finally { saving = false }
        }
    }
    LaunchedEffect(folder, source, revision) {
        scanning = true
        error = null
        try {
            val stored = app.statuses.imports(source)
            imports = imports + (source to (stored + imports[source].orEmpty()).distinctBy { it.uri })
            scanned = app.statuses.list(folder)
        }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { scanned = emptyList(); error = "Folder access expired. Reconnect or import files." }
        finally { scanning = false }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (settings.statusRefresh && folder != null) revision++ }
    val filtered = remember(scanned, imports, source, filter, revision) {
        (scanned + imports[source].orEmpty()).distinctBy { it.uri }.filter {
            !app.statuses.hidden(source, it.uri) && (filter == "All" || (filter == "Videos") == it.video)
        }
    }
    Column(modifier) {
        PageHeading("Keep the moment.", "Save photos and videos from your local status folders.", Modifier.padding(horizontal = 24.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            ChoiceMenu(source.label, listOf(Source.WHATSAPP, Source.BUSINESS), Source::label) { source = it }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { revision++ }, enabled = !scanning) { Icon(Icons.Outlined.Refresh, "Refresh statuses") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Status options") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Import status files") }, onClick = { menu = false; importFiles() })
                    DropdownMenuItem(text = { Text(if (folder == null) "Connect status folder" else "Change status folder") },
                        onClick = { menu = false; pickFolder(false) })
                    if (folder != null) DropdownMenuItem(text = { Text("Request cache deletion access") },
                        onClick = { menu = false; pickFolder(true) })
                    DropdownMenuItem(text = { Text("Show hidden statuses") }, onClick = {
                        menu = false; app.statuses.showHidden(); revision++
                    })
                }
            }
        }
        if (scanning || saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, Modifier.padding(horizontal = 15.dp), color = MaterialTheme.colorScheme.error) }
        if (folder == null && filtered.isEmpty() && !scanning) {
            EmptyState("Connect ${source.label} statuses", "Choose an accessible status folder. If Android hides the folder, import photos or videos you can access.")
            Row(Modifier.padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { pickFolder(false) }) { Text("Choose folder") }
                OutlinedButton(onClick = ::importFiles) { Text("Import files") }
            }
        } else {
            Row(Modifier.padding(horizontal = 15.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("All", "Photos", "Videos").forEach { label ->
                    FilterChip(filter == label, { filter = label; selected = emptySet() }, { Text(label) },
                        shape = RoundedCornerShape(24.dp), colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary))
                }
            }
            if (selected.isNotEmpty()) TextButton(onClick = { save(filtered.filter { it.uri in selected }) }, enabled = !saving,
                modifier = Modifier.padding(start = 12.dp)) { Text("Save selected · ${selected.size}") }
            if (filtered.isEmpty() && !scanning) EmptyState("No accessible statuses", "Open a status in ${source.label}, then refresh. Cached files may expire.")
            LazyVerticalGrid(GridCells.Adaptive(98.dp), contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(filtered, key = { _, media -> media.uri.toString() }) { index, media ->
                    val marked = media.uri in selected
                    fun toggle() { selected = if (marked) selected - media.uri else selected + media.uri }
                    Column(Modifier.clip(RoundedCornerShape(15.dp)).combinedClickable(
                        onClick = { if (selected.isEmpty()) viewer = filtered to index else toggle() },
                        onLongClick = ::toggle, onLongClickLabel = "Select status").padding(5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(76.dp).background(if (marked) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.primaryContainer, CircleShape).padding(2.dp).clip(CircleShape)) {
                            Thumb(if (media.video) videoThumb(context, media.uri) else media.uri, Modifier.matchParentSize())
                            if (media.video) Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_media_play), "Video",
                                Modifier.align(Alignment.Center), tint = Color.White)
                        }
                        Text(if (marked) "Selected" else if (saved[media.uri.toString()] != null) "Saved"
                            else if (media.video) "Video status" else "Photo status", style = MaterialTheme.typography.labelMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (media.modified > 0) Text(statusTime(media.modified), style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    viewer?.let { (files, index) ->
        StatusStory(files, index, source, saved, saving, onSave = { save(listOf(it)) },
            canDelete = { app.statuses.canDelete(folder, it.uri) }, onOperation = { media, operation -> confirm = media to operation },
            dialogOpen = confirm != null, onClose = { viewer = null })
    }
    confirm?.let { (media, operation) ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text(operation.title) }, text = { Text(operation.message) },
            confirmButton = { TextButton(onClick = {
                confirm = null
                scope.launch {
                    try {
                        when (operation) {
                            StatusOperation.HIDE -> app.statuses.hide(source, media.uri)
                            StatusOperation.SAVED -> saved[media.uri.toString()]?.let { app.downloads.remove(it, true) }
                            StatusOperation.SOURCE -> app.statuses.deleteSource(folder, media)
                        }
                        if (operation != StatusOperation.SAVED) viewer = null
                        revision++
                        toast(context, if (operation == StatusOperation.HIDE) "Status hidden" else "File deleted")
                    } catch (_: Exception) { toast(context, "Android couldn’t delete this file with the current access.") }
                }
            }) { Text(if (operation == StatusOperation.HIDE) "Hide" else "Delete") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } })
    }
}

private fun statusTime(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))

@Composable
private fun StatusStory(
    files: List<LocalMedia>, initial: Int, source: Source, saved: Map<String, Download>, saving: Boolean,
    onSave: (LocalMedia) -> Unit, canDelete: (LocalMedia) -> Boolean, onOperation: (LocalMedia, StatusOperation) -> Unit,
    dialogOpen: Boolean, onClose: () -> Unit,
) {
    val context = LocalContext.current
    var page by rememberSaveable { mutableIntStateOf(initial) }
    var hold by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var foreground by remember { mutableStateOf(true) }
    var muted by rememberSaveable { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    val media = files[page]
    var damaged by remember(media.uri) { mutableStateOf(false) }
    var loaded by remember(media.uri) { mutableStateOf(media.video) }
    var fraction by remember(media.uri) { mutableFloatStateOf(0f) }
    val player = remember(media.uri) {
        if (media.video) ExoPlayer.Builder(context).setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true).build().apply { setMediaItem(MediaItem.fromUri(media.uri)); prepare() }
        else null
    }
    fun advance(delta: Int) {
        val target = page + delta
        if (target > files.lastIndex) onClose()
        else if (target >= 0) { page = target; paused = false; hold = false }
    }
    val next by rememberUpdatedState<(Int) -> Unit>(::advance)
    val close by rememberUpdatedState(onClose)
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { foreground = false }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { foreground = true }
    DisposableEffect(player) {
        onDispose { player?.release() }
    }
    val frozen = hold || paused || menu || dialogOpen || !foreground || damaged || !loaded
    LaunchedEffect(player, frozen, muted) { if (player != null) { player.volume = if (muted) 0f else 1f; player.playWhenReady = !frozen } }
    LaunchedEffect(media.uri, frozen) {
        if (frozen) return@LaunchedEffect
        var last = android.os.SystemClock.elapsedRealtime()
        while (true) {
            delay(60)
            if (player != null) {
                if (player.playerError != null) { damaged = true; break }
                if (player.duration > 0) fraction = (player.currentPosition.toDouble() / player.duration).toFloat().coerceIn(0f, 1f)
                if (player.playbackState == Player.STATE_ENDED) { next(1); break }
            } else {
                val now = android.os.SystemClock.elapsedRealtime()
                fraction = (fraction + (now - last) / 5500f).coerceAtMost(1f)
                last = now
                if (fraction >= 1f) { next(1); break }
            }
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            val bars = window?.let { WindowCompat.getInsetsController(it, view) }
            bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars?.hide(WindowInsetsCompat.Type.systemBars())
            onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
        }
        Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
            Box(Modifier.fillMaxSize()) {
                if (player != null) AndroidView(factory = { PlayerView(it).apply { useController = false } },
                    update = { it.player = player }, onRelease = { it.player = null }, modifier = Modifier.fillMaxSize())
                else AsyncImage(media.uri, "Photo status", Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                    onSuccess = { loaded = true }, onError = { damaged = true })
                Box(Modifier.matchParentSize().pointerInput(media.uri) {
                    detectTapGestures(onTap = { next(if (it.x >= size.width / 2f) 1 else -1) },
                        onPress = { hold = true; try { tryAwaitRelease() } finally { hold = false } })
                }.pointerInput(media.uri) {
                    var x = 0f
                    var y = 0f
                    detectDragGestures(onDragStart = { x = 0f; y = 0f; hold = true },
                        onDragCancel = { hold = false }, onDragEnd = {
                            hold = false
                            val threshold = 72.dp.toPx()
                            if (y > threshold && y > abs(x)) close()
                            else if (abs(x) > threshold && abs(x) > abs(y)) next(if (x < 0) 1 else -1)
                        }) { change, amount -> change.consume(); x += amount.x; y += amount.y }
                })
                if (damaged) Text("This status is unavailable.", Modifier.align(Alignment.Center))
                else if (!loaded) CircularProgressIndicator(Modifier.align(Alignment.Center))
                Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .86f), Color.Transparent)))
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(horizontal = 12.dp, vertical = 6.dp)) {
                    val first = (page / 12) * 12
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        for (i in first until minOf(files.size, first + 12)) {
                            val fill = when { i < page -> 1f; i == page -> fraction; else -> 0f }
                            Box(Modifier.weight(1f).height(2.dp).clip(CircleShape).background(Color.White.copy(alpha = .35f))) {
                                Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(Color.White))
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close status", tint = Color.White) }
                        Column(Modifier.weight(1f)) {
                            Text(if (media.video) "Video status" else "Photo status", style = MaterialTheme.typography.titleSmall)
                            Text(source.label + if (media.modified > 0) " · " + statusTime(media.modified) else "",
                                style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Status actions", tint = Color.White) }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("Hide from SAOREO") }, onClick = { menu = false; onOperation(media, StatusOperation.HIDE) })
                                val record = saved[media.uri.toString()]
                                record?.galleryUri?.let { url ->
                                    DropdownMenuItem(text = { Text("Open saved copy") }, onClick = { menu = false; playSavedMedia(context, record) })
                                    DropdownMenuItem(text = { Text("Share saved copy") }, onClick = { menu = false; showMedia(context, Uri.parse(url), media.mime, true) })
                                    DropdownMenuItem(text = { Text("Delete saved copy") }, onClick = { menu = false; onOperation(media, StatusOperation.SAVED) })
                                }
                                if (canDelete(media)) DropdownMenuItem(text = { Text("Delete local cache file") },
                                    onClick = { menu = false; onOperation(media, StatusOperation.SOURCE) })
                            }
                        }
                    }
                }
                Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .76f))))
                    .safeDrawingPadding().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    MediaIcon(R.drawable.ic_media_prev, "Previous status", page > 0) { next(-1) }
                    MediaIcon(if (paused) R.drawable.ic_media_play else R.drawable.ic_media_pause, if (paused) "Play" else "Pause") { paused = !paused }
                    if (player != null) MediaIcon(if (muted) R.drawable.ic_media_mute else R.drawable.ic_media_volume,
                        if (muted) "Unmute status" else "Mute status") { muted = !muted }
                    Button(onClick = { onSave(media) }, enabled = !saving && !damaged) {
                        Text(if (saved[media.uri.toString()] != null) "Saved" else "Save")
                    }
                    MediaIcon(R.drawable.ic_media_next, "Next status") { next(1) }
                }
            }
        }
    }
}
