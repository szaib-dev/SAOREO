package com.codetic.saoreo

import android.app.Activity.RESULT_OK
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TranscriptScreen(app: SaoreoApp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val engine = app.transcripts
    val status by engine.state.collectAsStateWithLifecycle()
    val revision by engine.modelRevision.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf("") }
    var ai by rememberSaveable { mutableStateOf(false) }
    var timestamps by rememberSaveable { mutableStateOf(true) }
    var model by rememberSaveable { mutableStateOf(SpeechModel.TINY) }
    var language by rememberSaveable { mutableStateOf("auto") }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var actionSheet by remember { mutableStateOf<String?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var tools by remember { mutableStateOf(false) }
    var confirmModel by remember { mutableStateOf(false) }
    var removeModel by remember { mutableStateOf(false) }
    var inputUri by rememberSaveable { mutableStateOf<String?>(null) }
    var inputName by rememberSaveable { mutableStateOf<String?>(null) }
    var exportPending by rememberSaveable { mutableStateOf<String?>(null) }
    val installed = remember(model, revision) { engine.installed(model) }
    val link = remember(url) { transcriptLink(url) }
    LaunchedEffect(link, ai) {
        val target = link ?: return@LaunchedEffect
        if (ai || target.source != Source.YOUTUBE) return@LaunchedEffect
        delay(480)
        if (engine.state.value.lookupUrl != target.url && !engine.state.value.busy) engine.discover(target)
    }
    val scope = rememberCoroutineScope()
    val clipboard = remember { context.getSystemService(ClipboardManager::class.java) }
    val openText = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) it.data?.data?.let(engine::import)
    }
    val openMedia = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) it.data?.data?.takeIf { uri -> uri.scheme == "content" }?.let { uri ->
            inputUri = uri.toString()
            scope.launch { inputName = withContext(Dispatchers.IO) { runCatching { engine.name(uri) }.getOrDefault("Local media") } }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val name = exportPending
        exportPending = null
        if (name != null && name.matches(Regex("transcript-[0-9]+\\.txt"))) scope.launch {
            val success = withContext(Dispatchers.IO) {
                val staged = File(app.cacheDir, "transcript-export/$name")
                try {
                    if (uri?.scheme != "content") return@withContext false
                    app.contentResolver.openOutputStream(uri, "wt")?.use { target ->
                        staged.inputStream().use { it.copyTo(target) }
                    } ?: throw IOException("File unavailable")
                    true
                } catch (_: IOException) { false }
                catch (_: SecurityException) { false }
                finally { staged.delete() }
            }
            if (uri != null) toast(context, if (success) "Transcript exported" else "Couldn’t write the transcript.")
        }
    }
    fun prepareExport(transcript: Transcript, showTimes: Boolean = timestamps) {
        if (exportPending != null) return
        scope.launch {
            val staged = withContext(Dispatchers.IO) {
                runCatching {
                    val directory = File(app.cacheDir, "transcript-export").apply { mkdirs() }
                    directory.listFiles().orEmpty().filter { it.lastModified() < System.currentTimeMillis() - 86_400_000L }
                        .forEach(File::delete)
                    File.createTempFile("transcript-", ".txt", directory).apply {
                        writeText(transcript.text(showTimes), Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            if (staged == null) toast(context, "Couldn’t prepare the transcript for export.")
            else {
                exportPending = staged.name
                runCatching { export.launch("${safeName(transcript.title)}.txt") }.onFailure {
                    exportPending = null
                    withContext(Dispatchers.IO) { staged.delete() }
                    toast(context, "Android’s file picker is unavailable.")
                }
            }
        }
    }
    fun copy(result: Transcript, showTimes: Boolean = timestamps) {
        scope.launch {
            val text = withContext(Dispatchers.Default) { result.text(showTimes) }
            try {
                val clip = if (text.length <= 180_000) ClipData.newPlainText(result.title, text)
                else {
                    val file = withContext(Dispatchers.IO) {
                        val directory = File(app.cacheDir, "transcript-clipboard").apply { mkdirs() }
                        directory.listFiles().orEmpty().filter { it.lastModified() < System.currentTimeMillis() - 86_400_000L }
                            .forEach(File::delete)
                        File.createTempFile("transcript-", ".txt", directory).apply { writeText(text, Charsets.UTF_8) }
                    }
                    ClipData.newUri(context.contentResolver, result.title,
                        FileProvider.getUriForFile(app, "${app.packageName}.transcripts", file))
                }
                clipboard.setPrimaryClip(clip)
                toast(context, "Copied")
            } catch (_: Exception) { toast(context, "Couldn’t copy. Export TXT is available.") }
        }
    }
    val result = status.result
    val visible = remember(result, search) { result?.lines.orEmpty().filter { search.isBlank() || it.text.contains(search, ignoreCase = true) } }
    LazyColumn(modifier, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item {
            if (result == null) Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Transcript", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(if (ai) "Generate from video or a local file." else "Paste a YouTube link to find captions.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box {
                            IconButton(onClick = { tools = true }) { Icon(Icons.Outlined.MoreVert, "Transcript tools") }
                            DropdownMenu(tools, { tools = false }) {
                                DropdownMenuItem(text = { Text("Open transcript") }, enabled = !status.busy,
                                    onClick = { tools = false; openText.launch(localDocument("*/*")) })
                                DropdownMenuItem(text = { Text("Clear cached transcripts") }, enabled = !status.busy,
                                    onClick = { tools = false; engine.clearCache() })
                            }
                        }
                    }
                    OutlinedTextField(url, { url = it.take(16_384); selected = null; search = "" }, Modifier.fillMaxWidth(),
                        label = { Text("Video link") }, placeholder = { Text("youtube.com/watch?v=…") },
                        singleLine = true, enabled = !status.busy, trailingIcon = {
                            if (url.isNotEmpty()) IconButton(enabled = !status.busy, onClick = {
                                url = ""; inputUri = null; inputName = null; selected = null; search = ""; engine.reset()
                            }) { Icon(Icons.Outlined.Close, "Clear URL") }
                        }, supportingText = {
                            if (url.isNotBlank() && link == null) Text("Enter a complete link to a supported video.")
                        })
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(enabled = !status.busy, onClick = {
                            val pasted = runCatching { clipboard.primaryClip?.getItemAt(0)?.text?.toString() }
                                .getOrNull()?.take(16_384).orEmpty()
                            if (pasted.isBlank()) toast(context, "Copy a video link first.")
                            else {
                                url = pasted
                                selected = null
                                search = ""
                                if (!ai) transcriptLink(pasted)?.let { engine.discover(it) }
                            }
                        }) { Icon(Icons.Outlined.ContentPaste, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Paste") }
                        if (!ai) Button(enabled = link != null && !status.busy, onClick = {
                            link?.let { engine.discover(it, refresh = status.captionFailure) }; selected = null
                        }) { Icon(painterResource(R.drawable.ic_ui_search), null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (status.captionFailure) "Retry" else "Find captions") }
                        else Button(enabled = !status.busy, onClick = {
                            openMedia.launch(localDocument("*/*").putExtra(android.content.Intent.EXTRA_MIME_TYPES,
                                arrayOf("audio/*", "video/*")))
                        }) { Text("Choose local media") }
                    }
                    if (ai) TextButton(enabled = !status.busy, onClick = { ai = false }) { Text("Back to captions") }
                    else if (!status.captionFailure) TextButton(enabled = !status.busy, onClick = { ai = true }) {
                        Text("Transcribe audio")
                    }
                }
            }
        }
        if (ai) item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    inputName?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    val space = remember(revision, status.busy) { runCatching { engine.storage() }.getOrDefault(0) }
                    ChoiceMenu(model.label, SpeechModel.entries.toList(), { it.label }) {
                        if (!status.busy) model = it
                    }
                    Text("${sizeLabel(model.size)} model · ${sizeLabel(space)} available", style = MaterialTheme.typography.bodySmall)
                    ChoiceMenu(if (language == "auto") "Auto-detect language" else language, listOf("auto", "en", "ur", "ar", "hi", "es", "fr", "de", "pt", "tr"),
                        { if (it == "auto") "Auto-detect" else java.util.Locale.forLanguageTag(it).displayLanguage }) {
                        if (!status.busy) language = it
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (installed) TextButton(enabled = !status.busy, onClick = { removeModel = true }) { Text("Delete model") }
                        else TextButton(enabled = !status.busy, onClick = { confirmModel = true }) { Text("Download model") }
                        Button(enabled = installed && !status.busy && (link != null || inputUri != null), onClick = {
                            engine.startService(TranscriptService.GENERATE, model,
                                if (inputUri == null) link?.url else null, inputUri?.let(Uri::parse), language)
                        }) { Text("Generate") }
                    }
                    if (inputName != null) TextButton(enabled = !status.busy, onClick = { inputUri = null; inputName = null }) {
                        Text("Use video URL instead")
                    }
                }
            }
        }
        if (status.busy || status.message != null) item {
            Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                color = if (status.captionFailure) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(status.message.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                    if (status.busy) {
                        val progress = status.progress
                        if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = engine::cancel) { Text("Cancel") }
                    } else if (status.captionFailure && !ai) {
                        TextButton(onClick = { ai = true }) { Text("Transcribe audio instead") }
                    }
                }
            }
        }
        if (status.title != null || result != null || status.tracks.isNotEmpty()) item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                status.thumbnail?.let { Thumb(it, Modifier.size(64.dp, 42.dp)) }
                Text(result?.title ?: status.title.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (status.tracks.isNotEmpty()) ChoiceMenu(status.tracks.firstOrNull { it.key == selected }?.label ?: status.message?.takeIf { result != null } ?: "Language",
                status.tracks, CaptionTrack::label) { track ->
                if (!status.busy) { selected = track.key; engine.select(track) }
            }
        }
        if (result != null) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${result.lines.size} lines", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton({ searching = !searching; if (!searching) search = "" }) { Icon(painterResource(R.drawable.ic_ui_search), "Search transcript") }
                    IconButton({ engine.reset(); url = "" }) { Icon(Icons.Outlined.Add, "New transcript") }
                }
                AnimatedVisibility(searching) {
                    OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text("Search transcript") }, leadingIcon = { Icon(painterResource(R.drawable.ic_ui_search), null) }, shape = RoundedCornerShape(18.dp))
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(timestamps, { timestamps = !timestamps }, { Text("Time") }, leadingIcon = { Icon(Icons.Outlined.DateRange, null, Modifier.size(16.dp)) }, shape = RoundedCornerShape(18.dp))
                    FilledTonalButton({ actionSheet = "Copy transcript" }) { Icon(Icons.Outlined.ContentPaste, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Copy") }
                    FilledTonalButton({ actionSheet = "Download transcript" }, enabled = exportPending == null) { Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Save") }
                }
                if (visible.isEmpty()) Text("No matching text.")
            }
            items(visible) { line ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (timestamps && line.ms != null) Text(transcriptClock(line.ms),
                        Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 7.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SelectionContainer(Modifier.weight(1f)) {
                        Text(line.text, style = MaterialTheme.typography.bodyMedium, lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.25f)
                    }
                }
            }
        }
    }
    actionSheet?.let { action ->
        ModalBottomSheet(onDismissRequest = { actionSheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(action, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
                listOf(false, true).forEach { withTimes ->
                    ListItem(headlineContent = { Text(if (withTimes) "With timestamps" else "Clean text, without timestamps") },
                        supportingContent = { Text(if (withTimes) "Keep the time beside each line" else "Just the words, ready to use") },
                        leadingContent = { Icon(if (action.startsWith("Copy")) Icons.Outlined.ContentPaste else Icons.Outlined.Download, null) },
                        modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable {
                            result?.let { if (action.startsWith("Copy")) copy(it, withTimes) else prepareExport(it, withTimes) }
                            actionSheet = null
                        })
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    if (confirmModel) AlertDialog(onDismissRequest = { confirmModel = false }, title = { Text("Download speech model?") },
        text = { Text("${model.label} · ${sizeLabel(model.size)}. Speech recognition runs on this device.") },
        confirmButton = { TextButton(onClick = { confirmModel = false; engine.startService(TranscriptService.MODEL, model) }) { Text("Download") } },
        dismissButton = { TextButton(onClick = { confirmModel = false }) { Text("Cancel") } })
    if (removeModel) AlertDialog(onDismissRequest = { removeModel = false }, title = { Text("Delete this model?") },
        confirmButton = { TextButton(onClick = { removeModel = false; engine.deleteModel(model) }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { removeModel = false }) { Text("Cancel") } })
}
