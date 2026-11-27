package com.codetic.saoreo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

internal fun chatDraftFile(context: Context, extension: String): Pair<File, Uri> {
    val directory = File(context.cacheDir, "chat-drafts").apply { mkdirs() }
    val file = File.createTempFile("chat-", extension, directory)
    return file to FileProvider.getUriForFile(context, context.packageName + ".pairedmedia", file)
}

@Composable
internal fun ChatTransferStatus(pairing: TogetherPairing, item: ChatAttachment,
    compact: Boolean = false, fallback: Boolean = false) {
    val stats by pairing.chat.transferProgress.collectAsStateWithLifecycle()
    val done = stats[item.id]
    if (done == null && !fallback) return
    ChatTransferBadge(item.size, done, compact)
}

@Composable
internal fun ChatAlbumTransfer(pairing: TogetherPairing, items: List<PairedMessage>) {
    val stats by pairing.chat.transferProgress.collectAsStateWithLifecycle()
    val attachments = items.mapNotNull { message -> message.attachment?.let { it to message } }
    if (attachments.none { (item, _) -> item.id in stats }) return
    val total = attachments.sumOf { (item, _) -> item.size }.coerceAtLeast(1)
    val done = attachments.sumOf { (item, message) ->
        stats[item.id] ?: if (message.outgoing && message.status > 0) item.size else 0L
    }
    ChatTransferBadge(total, done, compact = false)
}

@Composable
internal fun ChatRetryUploads(pairing: TogetherPairing, messages: List<PairedMessage>, modifier: Modifier = Modifier) {
    val failed = messages.filter { it.outgoing && it.status == -1 }
    if (failed.isNotEmpty()) FilledTonalButton(
        onClick = { failed.forEach { pairing.retryMessage(it.id) } }, modifier = modifier,
    ) {
        Icon(painterResource(R.drawable.ic_chat_retry), null, Modifier.size(16.dp))
        Spacer(Modifier.width(5.dp))
        Text("Retry upload")
    }
}

@Composable
private fun ChatTransferBadge(size: Long, done: Long?, compact: Boolean) {
    val context = LocalContext.current
    val fraction = ((done ?: 0L).toFloat() / size).coerceIn(0f, 1f)
    val accent = MaterialTheme.colorScheme.primary
    val total = Formatter.formatShortFileSize(context, size)
    Surface(shape = RoundedCornerShape(18.dp), color = Color(0xe017191c), contentColor = Color.White) {
        Column(Modifier.padding(horizontal = if (compact) 7.dp else 13.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(if (compact) 30.dp else 46.dp), contentAlignment = Alignment.Center) {
                if (done == null) CircularProgressIndicator(Modifier.fillMaxSize(), color = accent, strokeWidth = 3.dp)
                else CircularProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxSize(),
                    color = accent, strokeWidth = 3.dp, trackColor = Color.White.copy(alpha = .2f))
                if (done != null) Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
            }
            if (!compact) Text(if (done == null) total else "${Formatter.formatShortFileSize(context, done)} / $total",
                Modifier.padding(top = 5.dp), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatAttachmentDraftView(items: List<Uri>, caption: String, changeCaption: (String) -> Unit,
    submitting: Boolean, sendError: String?,
    add: () -> Unit, remove: (Uri) -> Unit, replace: (Uri, Uri) -> Unit, close: () -> Unit, send: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var index by remember { mutableIntStateOf(0) }
    val current = items[index.coerceIn(items.indices)]
    val mime = remember(current) { context.contentResolver.getType(current).orEmpty() }
    val video = mime.startsWith("video/")
    val photo = mime.startsWith("image/")
    var editing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var editingFile by remember { mutableStateOf(false) }
    val busy = editingFile || submitting
    val duration by produceState(0L, current, video) {
        value = if (video) withContext(Dispatchers.IO) {
            runCatching { MediaMetadataRetriever().use {
                it.setDataSource(context, current)
                it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } }.getOrDefault(0L)
        } else 0L
    }
    val poster by produceState<Bitmap?>(null, current, video) {
        value = if (video) withContext(Dispatchers.IO) {
            runCatching { MediaMetadataRetriever().use {
                it.setDataSource(context, current)
                it.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 640)
            } }.getOrNull()
        } else null
    }
    val frames by produceState(emptyList<Bitmap>(), current, duration, editing) {
        value = if (editing && video && duration > 0) withContext(Dispatchers.IO) {
            runCatching { MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context, current)
                (0..7).mapNotNull { index ->
                    retriever.getScaledFrameAtTime(duration * 1000 * index / 8,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 144, 96)
                }
            } }.getOrDefault(emptyList())
        } else emptyList()
    }
    val fileSize by produceState<Long?>(null, current) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.query(current, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
                    ?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
            }.getOrNull()
        }
    }
    var range by remember(current, duration) { mutableStateOf(0f..duration.toFloat()) }
    fun applyEdit() {
        if (busy) return
        editingFile = true
        error = null
        scope.launch {
            try {
                val output = withContext(Dispatchers.IO) {
                    val (file, uri) = chatDraftFile(context, if (video) ".mp4" else ".jpg")
                    try {
                        if (video) trimChatVideo(context, current, file, range.start.toLong(), range.endInclusive.toLong())
                        else {
                            val source = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, current)) { decoder, info, _ ->
                                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                                val scale = minOf(1f, 2600f / maxOf(info.size.width, info.size.height))
                                decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1),
                                    (info.size.height * scale).toInt().coerceAtLeast(1))
                            }
                            val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height,
                                Matrix().apply { postRotate(90f) }, true)
                            try { file.outputStream().use { check(rotated.compress(Bitmap.CompressFormat.JPEG, 93, it)) } }
                            finally { rotated.recycle(); if (source !== rotated) source.recycle() }
                        }
                    } catch (failure: Exception) { file.delete(); throw failure }
                    uri
                }
                replace(current, output)
                editing = false
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { error = "Couldn’t edit this attachment." }
            finally { editingFile = false }
        }
    }
    Dialog(onDismissRequest = { if (!busy) close() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xff101214), contentColor = Color.White) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    DraftTool("Back", enabled = !busy, action = close) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null) }
                    Text("${index.coerceIn(items.indices) + 1} of ${items.size}", Modifier.weight(1f).padding(start = 10.dp),
                        style = MaterialTheme.typography.titleSmall)
                    if (photo) DraftTool("Rotate photo", enabled = !busy, action = ::applyEdit) {
                        Icon(painterResource(R.drawable.ic_camera_flip), null, Modifier.size(21.dp))
                    }
                    if (video && duration > 0) TextButton({ editing = !editing }, enabled = !busy) { Text("Trim") }
                }
                Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
                    if (photo) AsyncImage(current, "Attachment preview", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else if (video) ChatViewerVideo(current, controls = true, interact = {}, toggleControls = {},
                        poster = poster, padNavigationBars = false)
                    else Text("Attachment")
                }
                if (editing && video && duration > 0) Column(Modifier.padding(horizontal = 13.dp, vertical = 8.dp)) {
                    if (frames.isNotEmpty()) Row(Modifier.fillMaxWidth().height(47.dp)
                        .clip(RoundedCornerShape(8.dp)).border(1.dp, Color.White.copy(alpha = .72f), RoundedCornerShape(8.dp))
                        .clickable { editing = !editing }) {
                        frames.forEach { frame -> androidx.compose.foundation.Image(frame.asImageBitmap(), null,
                            Modifier.weight(1f).fillMaxHeight(), contentScale = ContentScale.Crop) }
                    }
                    if (editing) {
                        RangeSlider(range, { range = it }, valueRange = 0f..duration.toFloat())
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${chatMediaDuration(range.start.toLong())} – ${chatMediaDuration(range.endInclusive.toLong())}", Modifier.weight(1f))
                            TextButton(::applyEdit, enabled = !busy && range.endInclusive - range.start >= 500) { Text("Apply trim") }
                        }
                    }
                }
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    itemsIndexed(items) { position, uri ->
                        val shape = RoundedCornerShape(10.dp)
                        val selected = current == uri
                        Box(Modifier.size(49.dp).clip(shape).background(Color(0xff25282a))
                            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
                            .clickable(enabled = !busy) { index = position; editing = false }, contentAlignment = Alignment.Center) {
                            if (context.contentResolver.getType(uri)?.startsWith("image/") == true)
                                AsyncImage(uri, "Attachment ${position + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            else if (selected && poster != null) androidx.compose.foundation.Image(poster!!.asImageBitmap(),
                                "Selected video", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            else Icon(painterResource(R.drawable.ic_camera), "Video ${position + 1}", Modifier.size(19.dp))
                        }
                    }
                    item { DraftTool("Add media", enabled = !busy, action = add) { Icon(Icons.Outlined.Add, null) } }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                (sendError ?: error)?.let { Text(it, Modifier.padding(horizontal = 14.dp), color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(caption, { changeCaption(it.take(2000)) }, Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    placeholder = { Text("Add a caption") }, shape = RoundedCornerShape(24.dp), maxLines = 3, enabled = !busy)
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val details = listOfNotNull(duration.takeIf { it > 0 }?.let(::chatMediaDuration),
                        fileSize?.let { Formatter.formatShortFileSize(context, it) })
                    Text(details.joinToString(" · "), Modifier.weight(1f), color = Color(0xffbec3c5),
                        style = MaterialTheme.typography.labelMedium)
                    DraftTool("Remove attachment", enabled = !busy, action = { if (items.size == 1) close() else remove(current) }) {
                        Icon(Icons.Outlined.Delete, null)
                    }
                    Button(send, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Outlined.Send, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (submitting) "Preparing…" else "Send")
                    }
                }
            }
        }
    }
}

@Composable
private fun DraftTool(label: String, enabled: Boolean, action: () -> Unit, icon: @Composable () -> Unit) {
    IconButton(action, Modifier.size(44.dp).background(Color(0xff25282a), CircleShape)
        .semantics { contentDescription = label }, enabled = enabled, content = icon)
}

private fun trimChatVideo(context: Context, input: Uri, output: File, start: Long, end: Long) {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(context, input, null)
        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            val tracks = mutableMapOf<Int, Int>()
            var capacity = 1024 * 1024
            var videoTrack = -1
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (!mime.startsWith("audio/") && !mime.startsWith("video/")) continue
                if (mime.startsWith("video/")) {
                    videoTrack = index
                    if (format.containsKey(MediaFormat.KEY_ROTATION)) muxer.setOrientationHint(format.getInteger(MediaFormat.KEY_ROTATION))
                }
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) capacity = maxOf(capacity, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                tracks[index] = muxer.addTrack(format)
            }
            check(videoTrack >= 0)
            extractor.selectTrack(videoTrack)
            extractor.seekTo(start * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val anchor = extractor.sampleTime
            check(anchor >= 0 && anchor < end * 1000)
            for (index in tracks.keys) if (index != videoTrack) extractor.selectTrack(index)
            extractor.seekTo(anchor, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val buffer = ByteBuffer.allocate(capacity.coerceAtMost(24 * 1024 * 1024))
            val info = MediaCodec.BufferInfo()
            muxer.start()
            started = true
            while (extractor.sampleTrackIndex >= 0) {
                val time = extractor.sampleTime
                val track = tracks.getValue(extractor.sampleTrackIndex)
                if (time in anchor until end * 1000) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    info.set(0, size, time - anchor, if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(track, buffer, info)
                }
                extractor.advance()
            }
        } finally {
            try { if (started) muxer.stop() } finally { muxer.release() }
        }
    } finally { extractor.release() }
}

@Composable
internal fun ChatTilePreview(pairing: TogetherPairing, message: PairedMessage, modifier: Modifier, supplied: File? = null,
    loadOriginal: Boolean = true, description: String? = message.attachment?.name) {
    val item = message.attachment ?: return
    val video = item.mime.startsWith("video/")
    val local by produceState<File?>(null, message.id, supplied) {
        value = supplied ?: pairing.cachedAttachment(message)
        if (value == null && loadOriginal && message.outgoing && item.isVisualMedia) {
            try { value = pairing.openAttachment(message) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { }
        }
    }
    val frame by produceState<Bitmap?>(null, local, video) {
        value = if (video && local != null) withContext(Dispatchers.IO) {
            runCatching {
                MediaMetadataRetriever().use {
                    it.setDataSource(local!!.path)
                    it.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 640)
                }
            }.getOrNull()
        } else null
    }
    val thumbnail = remember(item) { item.previewBitmap() }
    val image = frame ?: thumbnail
    if (!video && local != null) AsyncImage(local, description, modifier, contentScale = ContentScale.Crop)
    else if (image != null) androidx.compose.foundation.Image(image.asImageBitmap(), description, modifier, contentScale = ContentScale.Crop)
}
