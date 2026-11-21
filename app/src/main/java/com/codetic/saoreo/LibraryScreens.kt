package com.codetic.saoreo

import android.net.Uri
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DownloadsScreen(app: SaoreoApp, settings: Settings, modifier: Modifier = Modifier,
                    search: Boolean = false, jobsOnly: Boolean = false, importAction: Int = 0, paste: () -> Unit = {}) {
    val context = LocalContext.current
    val downloads by app.dao.observe().collectAsStateWithLifecycle(initialValue = emptyList())
    val transferProgress by app.downloadNotices.progress.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("All") }
    var query by rememberSaveable { mutableStateOf("") }
    var moveFolder by remember { mutableStateOf<DownloadFolder?>(null) }
    var confirmation by remember { mutableStateOf<Download?>(null) }
    val folders by app.collections.folders.collectAsStateWithLifecycle(emptyList())
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    var addFolder by rememberSaveable { mutableStateOf(false) }
    var renameFolder by remember { mutableStateOf<DownloadFolder?>(null) }
    var move by remember { mutableStateOf<Download?>(null) }
    var renameFile by remember { mutableStateOf<Download?>(null) }
    val scope = rememberCoroutineScope()
    var convertUri by rememberSaveable { mutableStateOf<String?>(null) }
    var convertTitle by rememberSaveable { mutableStateOf<String?>(null) }
    var imported by rememberSaveable { mutableStateOf(false) }
    fun convert(uri: Uri, title: String?, import: Boolean) {
        imported = import
        convertTitle = title
        convertUri = uri.toString()
    }
    val matchesSearch: (Download) -> Boolean = { !search || it.title.contains(query.trim(), ignoreCase = true) }
    val active = downloads.filter { it.active || it.state == "CONVERTING" }.filter(matchesSearch)
    val saved = downloads.filter { it.state == JobState.COMPLETE.name && matchesSearch(it) }
        .filter { !settings.folderOrganization || folder == null || it.folder == folder }
        .filter {
            when (filter) {
                "Videos" -> it.mime?.startsWith("video/") == true
                "Audio" -> it.mime?.startsWith("audio/") == true
                "Images" -> it.mime?.startsWith("image/") == true
                else -> true
            }
        }
    val failures = downloads.filter { it.state in setOf("FAILED", "CANCELLED") && matchesSearch(it) }
    val history = if (jobsOnly) failures else saved
    val list = active + history
    fun act(block: suspend () -> Unit) {
        scope.launch {
            try { block() } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { toast(context, "Couldn’t update this download.") }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) act {
            if (app.contentResolver.getType(uri)?.startsWith("audio/") == true) app.collections.importAudio(uri)
            else app.statuses.save(app.statuses.fromUri(uri), Source.SHARED)
        }
    }
    var handledImport by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(importAction) {
        if (importAction != 0 && importAction != handledImport) {
            handledImport = importAction
            picker.launch(arrayOf("audio/*", "video/*", "image/*"))
        }
    }
    Column(modifier) {
        if (search) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            singleLine = true, label = { Text(if (folder != null && settings.folderOrganization && !jobsOnly)
                "Search this folder" else "Search downloads") })
        if (!jobsOnly) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All", "Videos", "Audio", "Images").forEach { type ->
                FilterChip(type == filter, onClick = { filter = type }, label = { Text(type) })
            }
        }
        if (settings.folderOrganization && !jobsOnly) {
            val current = folders.firstOrNull { it.id == folder }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (current != null) IconButton({ folder = current.parent.takeIf(String::isNotEmpty) }) {
                    Icon(Icons.Outlined.KeyboardArrowUp, "Up one level")
                }
                Text(if (folder == null) "All folders" else folderLabel(folders, folder), Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton({ addFolder = true }) { Icon(painterResource(R.drawable.ic_folder_new), "New folder") }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                folders.filter { it.parent == folder.orEmpty() }.forEach { child ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AssistChip({ folder = child.id }, label = { Text(child.name, maxLines = 1) },
                            leadingIcon = { Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp)) })
                        var menu by remember(child.id) { mutableStateOf(false) }
                        Box {
                            IconButton({ menu = true }) { Icon(Icons.Outlined.MoreVert, "Folder actions for ${child.name}") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ Text("Rename") }, { menu = false; renameFolder = child })
                                DropdownMenuItem({ Text("Move") }, { menu = false; moveFolder = child })
                            }
                        }
                    }
                }
            }
        }
        if (list.isEmpty()) {
            EmptyState(if (jobsOnly) "No download activity" else if (search && query.isNotBlank())
                "No matching media" else "Nothing saved here")
            if (!jobsOnly && (!search || query.isBlank())) Row(Modifier.padding(horizontal = 12.dp)) {
                TextButton(paste) { Text("Paste link") }
                TextButton({ picker.launch(arrayOf("audio/*", "video/*", "image/*")) }) { Text("Import media") }
            }
        } else LazyColumn(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (active.isNotEmpty()) item { Text("In progress", Modifier.padding(6.dp), style = MaterialTheme.typography.titleSmall) }
            items(list, key = { it.id }) { item ->
                if (item.id == history.firstOrNull()?.id) {
                    Text(if (jobsOnly) "Recent attempts" else "Saved media", Modifier.padding(6.dp),
                        style = MaterialTheme.typography.titleSmall)
                }
                var menu by remember { mutableStateOf(false) }
                val source = Source.valueOf(item.source)
                val uri = item.galleryUri?.let(Uri::parse)
                val available by produceState(initialValue = true, key1 = uri) {
                    value = uri == null || withContext(Dispatchers.IO) { isReadable(app, uri) }
                }
                val mime = item.mime ?: "application/octet-stream"
                val video = mime.startsWith("video/")
                val audio = mime.startsWith("audio/")
                val canOpen = item.state == JobState.COMPLETE.name && uri != null && available
                fun open() {
                    if (video || audio || mime.startsWith("image/")) playSavedMedia(context, item)
                    else uri?.let { showMedia(context, it, mime, false) }
                }
                val transfer = transferProgress[item.id]
                val stateLabel = if (transfer != null && item.active) {
                    listOfNotNull(transfer.stage, transfer.bytes.takeIf { it > 0 }?.let(::sizeLabel),
                        transfer.percent.takeIf { it >= 0 }?.let { "$it%" }).joinToString(" · ")
                } else when (item.state) {
                    "QUEUED" -> if (!hasDownloadNetwork(context, settings.wifi)) "Waiting for connection"
                        else if (item.error == null) "Waiting to start" else "Waiting to retry"
                    "RESOLVING" -> "Retrieving media"
                    "DOWNLOADING" -> if (item.progress < 0) "Downloading" else "Downloading · ${item.progress}%"
                    "COMPLETE" -> if (available) "Saved" else "File unavailable"
                    "CANCELLED" -> "Cancelled"
                    "CONVERTING" -> "Converting to MP3"
                    else -> item.error?.substringBefore('\n') ?: "Download failed"
                }
                val dimensions by produceState<String?>(null, uri, available) {
                    if (canOpen && (video || mime.startsWith("image/"))) value = withContext(Dispatchers.IO) {
                        runCatching { savedDimensions(app, checkNotNull(uri), video) }.getOrNull()
                    }
                }
                val duration by produceState<String?>(null, uri, available) {
                    if (canOpen && (video || audio)) value = withContext(Dispatchers.IO) {
                        runCatching {
                            android.media.MediaMetadataRetriever().use { reader ->
                                reader.setDataSource(app, uri)
                                reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                                    ?.toLongOrNull()?.let(::playbackTime)
                            }
                        }.getOrNull()
                    }
                }
                Surface(shape = RoundedCornerShape(10.dp), color = if (item.active) MaterialTheme.colorScheme.surfaceContainer
                    else MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            val isImage = item.mime?.startsWith("image/") == true
                            val model = if (canOpen) {
                                if (isImage) uri else if (video) videoThumb(context, uri) else null
                            } else item.thumbnail
                            Row(Modifier.weight(1f).then(if (canOpen) Modifier.clickable(
                                onClickLabel = if (video || audio) "Play" else "Open", onClick = ::open,
                            ) else Modifier), horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Thumb(model, Modifier.size(48.dp).clip(RoundedCornerShape(9.dp)))
                                Column(Modifier.weight(1f)) {
                                    Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    val known = item.bytes?.let(::sizeLabel)
                                    val previewInfo = item.detail?.split(" · ")
                                    val estimate = previewInfo?.lastOrNull()?.takeIf { it != "Size unavailable" }?.let {
                                        if (it.startsWith("~")) "Est. ${it.removePrefix("~")}" else "$it expected"
                                    }
                                    Text(listOfNotNull(source.label, dimensions ?: previewInfo?.firstOrNull()?.takeIf {
                                        it.endsWith("p") || " × " in it
                                    }, duration, known ?: estimate?.takeIf { item.active }).joinToString(" · "),
                                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (!canOpen || item.active) Text(stateLabel, style = MaterialTheme.typography.labelMedium,
                                        color = if (item.state == "FAILED" || !available) MaterialTheme.colorScheme.error
                                            else MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Download actions") }
                                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                    fun execute(action: () -> Unit): () -> Unit = { menu = false; action() }
                                    if (item.active) DropdownMenuItem(text = { Text("Cancel") },
                                        onClick = execute { act { app.downloads.cancel(item) } })
                                    else {
                                        if (uri != null && available) {
                                            DropdownMenuItem(text = { Text(if (video || audio) "Play" else "Open") }, onClick = execute(::open))
                                            if (video) DropdownMenuItem(text = { Text("Convert to MP3") },
                                                onClick = execute { convert(uri, item.title, false) })
                                            if (video || audio) DropdownMenuItem(text = { Text("Open with") },
                                                onClick = execute { showMedia(context, uri, mime, false, chooseApp = true) })
                                            DropdownMenuItem(text = { Text("Share") }, onClick = execute { showMedia(context, uri, mime, true) })
                                            if (source != Source.IMPORTED) {
                                                if (settings.folderOrganization)
                                                    DropdownMenuItem(text = { Text("Rename file") }, onClick = execute { renameFile = item })
                                                DropdownMenuItem(text = { Text("Delete file") }, onClick = execute { confirmation = item })
                                            }
                                        }
                                        if (item.state in setOf("FAILED", "CANCELLED") && MediaLink.parse(item.url) != null) {
                                            if (item.state == "FAILED") DropdownMenuItem(text = { Text("Copy diagnostic report") },
                                                onClick = execute {
                                                    val info = context.packageManager.getPackageInfo(context.packageName, 0)
                                                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                                                        ClipData.newPlainText("SAOREO diagnostic",
                                                            "SAOREO ${info.versionName} · Android ${android.os.Build.VERSION.SDK_INT} · yt-dlp ${Extractor.ENGINE_VERSION}\n" +
                                                                (item.error ?: "Transfer failed")))
                                                })
                                            DropdownMenuItem(text = { Text("Retry") },
                                                onClick = execute { act { app.downloads.retry(item, settings) } })
                                        }
                                        if (settings.folderOrganization)
                                            DropdownMenuItem(text = { Text("Move to folder") }, onClick = execute { move = item })
                                        DropdownMenuItem(text = { Text("Remove from history") },
                                            onClick = execute { act { app.downloads.remove(item, false) } })
                                    }
                                }
                            }
                        }
                        if (item.active && item.state != "QUEUED") {
                            if (item.progress < 0) LinearProgressIndicator(Modifier.fillMaxWidth())
                            else LinearProgressIndicator(progress = { item.progress / 100f }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
    if (settings.folderOrganization) {
        if (addFolder) NameDialog("New folder", close = { addFolder = false }, save = { app.collections.newFolder(folder, it) })
        renameFolder?.let { item -> NameDialog("Rename folder", item.name, { renameFolder = null }) {
            app.collections.renameFolder(item.id, it)
        } }
        renameFile?.let { item -> NameDialog("Rename file", item.title, { renameFile = null }) {
            app.collections.renameFile(item, it)
        } }
        moveFolder?.let { item ->
            DestinationDialog(app, item.parent.takeIf(String::isNotEmpty), { moveFolder = null },
                select = { target -> act { app.collections.moveFolder(item, target) } }, excludedBranch = item.id)
        }
        move?.let { item -> DestinationDialog(app, item.folder, { move = null }) { target -> act { app.collections.move(item, target) } } }
    }
    convertUri?.let { value ->
        ConversionSheet(app, Uri.parse(value), convertTitle, imported) { convertUri = null }
    }
    confirmation?.let { item ->
        AlertDialog(onDismissRequest = { confirmation = null },
            title = { Text("Delete this file?") }, text = { Text(item.title) },
            confirmButton = { TextButton(onClick = { confirmation = null; act { app.downloads.remove(item, true) } }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } })
    }
}

@Composable
internal fun ThumbnailForDownload(item: Download, modifier: Modifier) {
    val context = LocalContext.current
    val uri = item.galleryUri?.let(Uri::parse)
    val model = when {
        uri == null -> item.thumbnail
        item.mime?.startsWith("image/") == true -> uri
        item.mime?.startsWith("video/") == true -> videoThumb(context, uri)
        else -> null
    }
    Thumb(model, modifier.clip(RoundedCornerShape(8.dp)))
}

@Composable
fun EmptyState(title: String, description: String? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 26.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

internal fun videoThumb(context: android.content.Context, uri: Uri) =
    ImageRequest.Builder(context).data(uri).decoderFactory(VideoFrameDecoder.Factory())
        .videoFrameMillis(0).build()

private fun savedDimensions(context: android.content.Context, uri: Uri, video: Boolean): String? {
    val (width, height) = if (video) android.media.MediaMetadataRetriever().use { reader ->
        reader.setDataSource(context, uri)
        val w = reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
        val h = reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
        val rotation = reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
        if (rotation == 90 || rotation == 270) h to w else w to h
    } else {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        bounds.outWidth to bounds.outHeight
    }
    return if (width > 0 && height > 0) "$width × $height" else null
}
