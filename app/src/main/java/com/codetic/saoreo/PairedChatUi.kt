package com.codetic.saoreo

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun PartnerChatHeader(
    state: PairState, finish: () -> Unit, audio: () -> Unit, video: () -> Unit,
    settings: () -> Unit, canCall: Boolean, profile: () -> Unit = settings, photo: java.io.File? = null, photoRevision: Int = 0,
    selection: ChatSelectionState? = null,
) {
    val partner = state.partner ?: return
    val colors = MaterialTheme.colorScheme
    val selecting = selection?.keys?.value?.isNotEmpty() == true
    Column {
    Box {
    val hidden = if (selecting) Modifier.alpha(0f).clearAndSetSemantics {} else Modifier
    Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 57.dp).padding(horizontal = 8.dp).then(hidden),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(finish, Modifier.size(40.dp).background(colors.onSurface.copy(alpha = .04f), CircleShape),
            enabled = !selecting) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", Modifier.size(18.dp))
        }
        Spacer(Modifier.width(4.dp))
        val online = state.connected && state.online
        Surface(onClick = profile, shape = CircleShape, color = Color.Transparent, enabled = !selecting) {
            Box {
                Box(Modifier.border(1.dp, colors.primary.copy(alpha = .58f), CircleShape).padding(1.dp)) {
                    PartnerAvatar(partner, 34, picture = photo, revision = photoRevision)
                }
                Box(Modifier.align(Alignment.BottomEnd).size(8.dp).background(
                    if (online) colors.primary else colors.onSurfaceVariant.copy(alpha = .52f), CircleShape)
                    .border(1.dp, colors.surface, CircleShape))
            }
        }
        TextButton(profile, Modifier.weight(1f), enabled = !selecting, contentPadding = PaddingValues(horizontal = 7.dp)) {
            Column(Modifier.fillMaxWidth()) {
                Text(partner, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, letterSpacing = 0.sp))
                Text(if (state.typing && state.connected) "Typing…" else if (state.online && state.connected) "Online" else "Offline",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp, lineHeight = 14.sp, letterSpacing = 0.sp))
            }
        }
        Row(Modifier.background(colors.onSurface.copy(alpha = .04f), CircleShape)
            .border(1.dp, colors.onSurface.copy(alpha = .05f), CircleShape)) {
            IconButton(audio, Modifier.size(41.dp), enabled = canCall && !selecting) {
                Icon(painterResource(R.drawable.ic_call), "Audio call", Modifier.size(17.dp))
            }
            IconButton(video, Modifier.size(41.dp), enabled = canCall && !selecting) {
                Icon(painterResource(R.drawable.ic_camera), "Video call", Modifier.size(18.dp))
            }
            IconButton(settings, Modifier.size(34.dp), enabled = !selecting) {
                Icon(Icons.Outlined.MoreVert, "Conversation options", Modifier.size(18.dp))
            }
        }
    }
    if (selecting && selection != null) {
        Row(Modifier.matchParentSize().statusBarsPadding().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            ChatSelectionActions(selection)
        }
    }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
    }
}

internal class ChatSelectionState {
    val keys = mutableStateOf(emptySet<String>())
    val confirmDelete = mutableStateOf(false)
}

@Composable
private fun RowScope.ChatSelectionActions(selection: ChatSelectionState) {
    IconButton({ selection.keys.value = emptySet() }) {
        Icon(Icons.Outlined.Close, "Clear selection")
    }
    Text("${selection.keys.value.size} selected", Modifier.weight(1f),
        style = MaterialTheme.typography.titleMedium)
    IconButton({ selection.confirmDelete.value = true }) {
        Icon(Icons.Outlined.Delete, "Delete selected messages")
    }
}

private fun PairedMessage.chatKey() = "$outgoing:$id"

internal fun pairedChatTime(time: Long): String =
    DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.getDefault())
        .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))
private val messageDate = DateTimeFormatter.ofPattern("EEE, d MMM yyyy").withZone(ZoneId.systemDefault())
private fun chatDate(instant: Instant): String {
    val local = instant.atZone(ZoneId.systemDefault()).toLocalDate()
    val today = java.time.LocalDate.now()
    return when (local) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> messageDate.format(instant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PairedChatThread(pairing: TogetherPairing, modifier: Modifier = Modifier, search: Boolean = false, allowRecording: Boolean = true,
    showCallHistory: (() -> Unit)? = null, headerSelection: ChatSelectionState? = null) {
    val theme by pairing.appearance.theme.collectAsStateWithLifecycle()
    PairedConversationTheme(theme) {
        PairedChatContent(pairing, modifier, search, allowRecording, showCallHistory, headerSelection)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PairedChatContent(pairing: TogetherPairing, modifier: Modifier, search: Boolean, allowRecording: Boolean,
    showCallHistory: (() -> Unit)?, headerSelection: ChatSelectionState?) {
    val theme = LocalPairedTheme.current
    val wallpaper by pairing.appearance.wallpaper.collectAsStateWithLifecycle()
    val pairState by pairing.state.collectAsStateWithLifecycle()
    val callHistory by pairing.chat.calls.collectAsStateWithLifecycle()
    val messages by pairing.chat.messages.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }
    var reply by remember { mutableStateOf<ChatReference?>(null) }
    var editing by remember { mutableStateOf<PairedMessage?>(null) }
    val selectionState = headerSelection ?: remember(pairing.conversationId) { ChatSelectionState() }
    var selection by selectionState.keys
    var confirmDelete by selectionState.confirmDelete
    var unreadAnchor by remember(pairing.conversationId) { mutableStateOf<String?>(null) }
    var unreadInitialized by remember(pairing.conversationId) { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<PairedMessage>()) }
    var sending by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<PairedMessage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    fun selectReply(item: PairedMessage) {
        editing = null
        reply = ChatReference(item.id, item.outgoing, item.previewLabel().take(120))
    }
    fun action(item: PairedMessage, value: ChatAction, text: String = "") {
        scope.launch {
            try {
                if (!pairing.messageAction(item, value, text)) error = "Couldn’t update this message."
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { error = "Couldn’t update this message." }
        }
    }
    fun toggleSelection(item: PairedMessage) {
        val key = item.chatKey()
        selection = if (key in selection) selection - key else selection + key
    }
    BackHandler(selection.isNotEmpty()) { selection = emptySet() }
    val recorder = remember(context) { ChatRecorder(context) }
    var liveLevels by remember { mutableStateOf(emptyList<Int>()) }
    var voiceStage by remember { mutableStateOf(VoiceStage.Idle) }
    var voiceDraft by remember { mutableStateOf<RecordedNote?>(null) }
    var recordTime by remember { mutableLongStateOf(0) }
    fun sendMedia(uri: Uri, note: RecordedNote? = null, caption: String = "") {
        sending = true
        error = null
        scope.launch {
            try {
                if (pairing.sendAttachment(uri, note?.duration ?: 0, note?.waveform.orEmpty(), reply, caption))
                    reply = null
                else error = "Secure chat is unavailable."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: java.io.IOException) { error = failure.message ?: "Couldn’t save this attachment." }
            catch (_: Exception) { error = "Couldn’t save this attachment." }
            finally { note?.file?.delete(); sending = false }
        }
    }
    fun startRecording(stage: VoiceStage): Boolean {
        if (!allowRecording || sending || voiceStage != VoiceStage.Idle) return false
        return try {
            recorder.start()
            recordTime = 0L
            voiceStage = stage
            error = null
            true
        } catch (_: Exception) {
            error = "Couldn’t start recording."
            false
        }
    }
    fun discardRecording() {
        recorder.cancel()
        voiceDraft?.file?.delete()
        voiceDraft = null
        voiceStage = VoiceStage.Idle
    }
    fun stopRecording(): RecordedNote? {
        val note = recorder.stop()
        voiceStage = VoiceStage.Idle
        return note
    }
    fun finishRecording() {
        stopRecording()?.let {
            voiceDraft = it
            voiceStage = VoiceStage.Ready
        } ?: run { error = "Record a longer voice note." }
    }
    fun sendRecording() {
        val note = voiceDraft ?: stopRecording()
        voiceDraft = null
        voiceStage = VoiceStage.Idle
        if (note != null) sendMedia(Uri.fromFile(note.file), note)
        else error = "Record a longer voice note."
    }
    DisposableEffect(recorder) {
        onDispose {
            recorder.cancel()
            voiceDraft?.file?.delete()
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        error = if (granted) null else "Microphone permission is required for voice notes."
    }
    fun beginVoice(stage: VoiceStage): Boolean {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            return startRecording(stage)
        permissions.launch(Manifest.permission.RECORD_AUDIO)
        return false
    }
    val togetherActivity = androidx.activity.compose.LocalActivity.current as? TogetherActivity
    var attachmentDraft by rememberSaveable { mutableStateOf<ArrayList<Uri>>(arrayListOf()) }
    var attachmentCaption by rememberSaveable { mutableStateOf("") }
    var attachmentAlbum by rememberSaveable { mutableStateOf<String?>(null) }
    var attachmentError by remember { mutableStateOf<String?>(null) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        togetherActivity?.showingExternalPicker = false
        if (uris.isNotEmpty()) attachmentDraft = ArrayList((attachmentDraft + uris).distinct())
    }
    fun openAttachments() {
        togetherActivity?.showingExternalPicker = true
        try { pickFile.launch(arrayOf("*/*")) }
        catch (_: android.content.ActivityNotFoundException) {
            togetherActivity?.showingExternalPicker = false
            error = "No file picker is available."
        }
    }
    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        togetherActivity?.showingExternalPicker = false
        if (uris.isNotEmpty()) attachmentDraft = ArrayList((attachmentDraft + uris).distinct())
    }
    fun openGallery() {
        togetherActivity?.showingExternalPicker = true
        try {
            galleryPicker.launch(androidx.activity.result.PickVisualMediaRequest(
                ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        } catch (_: android.content.ActivityNotFoundException) { openAttachments() }
    }
    var showCamera by rememberSaveable { mutableStateOf(false) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        togetherActivity?.showingExternalPicker = false
        if (granted) showCamera = true else error = "Allow camera access to take photos and videos."
    }
    fun openCamera() {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) showCamera = true
        else {
            togetherActivity?.showingExternalPicker = true
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }
    if (showCamera && attachmentDraft.isEmpty()) TogetherChatCamera(
        close = { showCamera = false }, openPicker = ::openGallery,
        onPick = { showCamera = false; attachmentDraft = ArrayList((attachmentDraft + it).distinct()) },
    )
    LaunchedEffect(attachmentDraft.isNotEmpty()) { if (attachmentDraft.isNotEmpty()) showCamera = false }
    if (attachmentDraft.isNotEmpty()) ChatAttachmentDraftView(
        items = attachmentDraft, caption = attachmentCaption, changeCaption = { attachmentCaption = it },
        submitting = sending, sendError = attachmentError,
        add = ::openGallery, remove = { attachmentDraft = ArrayList(attachmentDraft - it) },
        replace = { old, new -> attachmentDraft = ArrayList(attachmentDraft.map { if (it == old) new else it }) },
        close = { attachmentDraft = arrayListOf(); attachmentCaption = ""; attachmentAlbum = null; attachmentError = null },
        send = {
            val chosen = attachmentDraft.toList()
            if (attachmentAlbum == null && chosen.size > 1)
                attachmentAlbum = java.util.UUID.randomUUID().toString().replace("-", "")
            val album = attachmentAlbum
            sending = true
            attachmentError = null
            scope.launch {
                try {
                    for (part in chosen) {
                        if (!pairing.sendAttachment(part, reply = reply, caption = attachmentCaption, album = album)) {
                            attachmentError = "Secure chat is unavailable. Try again."
                            break
                        }
                        attachmentDraft = ArrayList(attachmentDraft - part)
                        attachmentCaption = ""
                        reply = null
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: java.io.IOException) {
                    attachmentError = failure.message ?: "Couldn’t queue this attachment. Try again."
                } catch (_: Exception) {
                    attachmentError = "Couldn’t read this attachment. Try choosing it again."
                } finally {
                    if (attachmentDraft.isEmpty()) attachmentAlbum = null
                    sending = false
                }
            }
        },
    )
    LaunchedEffect(messages, query, search) {
        if (search && query.isNotBlank()) {
            delay(160)
            results = pairing.chat.search(query)
        } else results = emptyList()
    }
    val searching = search && query.isNotBlank()
    val shown = if (searching) results else if (showCallHistory == null) messages else
        remember(messages, callHistory) {
            (messages + callHistory.map { call ->
                PairedMessage(call.id, call.title, !call.incoming, call.time, 0, call = call)
            }).sortedBy { it.time }
        }
    val list = rememberLazyListState()
    val originals = remember(messages) { messages.associateBy { it.chatKey() } }
    val albums = remember(shown) {
        shown.filter { !it.deleted && it.attachment?.isVisualMedia == true && it.attachment.album != null }
            .groupBy { it.outgoing to it.attachment?.album }
    }
    var lastId by remember { mutableStateOf<String?>(null) }
    var jumpTarget by remember { mutableStateOf<ChatReference?>(null) }
    var highlighted by remember { mutableStateOf<String?>(null) }
    fun openReply(reference: ChatReference) {
        scope.launch {
            if (pairing.chat.revealReplyOriginal(reference)) {
                query = ""
                jumpTarget = reference
            } else {
                error = "Original message is unavailable."
            }
        }
    }
    LaunchedEffect(jumpTarget, shown, searching) {
        val target = jumpTarget ?: return@LaunchedEffect
        if (searching) return@LaunchedEffect
        val original = shown.firstOrNull { it.id == target.id && it.outgoing == target.outgoing }
            ?: return@LaunchedEffect
        val first = albums[original.outgoing to original.attachment?.album]?.firstOrNull() ?: original
        list.scrollToItem(shown.indexOf(first) + 1)
        lastId = shown.lastOrNull()?.id
        highlighted = first.chatKey()
        jumpTarget = null
    }
    LaunchedEffect(highlighted) {
        if (highlighted != null) {
            delay(1500)
            highlighted = null
        }
    }
    LaunchedEffect(messages) {
        if (!unreadInitialized && messages.isNotEmpty()) {
            unreadAnchor = messages.firstOrNull { it.call == null && !it.outgoing && !it.seen }?.id
            unreadInitialized = true
        }
    }
    var newMessages by remember { mutableIntStateOf(0) }
    LaunchedEffect(list) {
        snapshotFlow { list.canScrollForward }.collect { if (!it) newMessages = 0 }
    }
    LaunchedEffect(shown.lastOrNull()?.id, searching) {
        if (searching || shown.isEmpty() || jumpTarget != null) return@LaunchedEffect
        val newLast = shown.last()
        val keepAtBottom = lastId == null || !list.canScrollForward || newLast.outgoing
        if (lastId == null) {
            val firstUnread = shown.indexOfFirst { it.call == null && !it.outgoing && !it.seen }
            list.scrollToItem(if (firstUnread < 0) shown.lastIndex + 1 else firstUnread + 1)
        } else if (keepAtBottom) { list.scrollToItem(shown.lastIndex + 1); newMessages = 0 }
        else {
            val previous = shown.indexOfFirst { it.id == lastId }
            if (previous >= 0) newMessages += shown.drop(previous + 1).count { !it.outgoing && it.call == null }
        }
        lastId = newLast.id
    }
    val byKey = remember(shown) { shown.filter { !it.outgoing && it.call == null }.associateBy { "false:${it.id}" } }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val viewingToken = remember { Any() }
    DisposableEffect(lifecycleState, viewingToken) {
        pairing.chatVisible(viewingToken, lifecycleState == androidx.lifecycle.Lifecycle.State.RESUMED)
        onDispose { pairing.chatVisible(viewingToken, false) }
    }
    val recording = voiceStage == VoiceStage.Held || voiceStage == VoiceStage.Locked
    LaunchedEffect(recording, allowRecording, lifecycleState) {
        if (!recording) return@LaunchedEffect
        if (!allowRecording || lifecycleState != androidx.lifecycle.Lifecycle.State.RESUMED) {
            discardRecording()
            return@LaunchedEffect
        }
        while (recordTime < 300_000) {
            delay(50)
            recorder.sample()
            liveLevels = recorder.liveLevels()
            recordTime = recorder.elapsed
        }
        finishRecording()
    }
    LaunchedEffect(list, byKey, lifecycleState) {
        if (lifecycleState == androidx.lifecycle.Lifecycle.State.RESUMED) {
            snapshotFlow { list.layoutInfo.visibleItemsInfo.map { it.key } }.distinctUntilChanged().collect { keys ->
                val visible = keys.flatMap { key ->
                    val item = byKey[key] ?: return@flatMap emptyList()
                    val album = item.attachment?.album
                    if (album == null || item.attachment.isVisualMedia != true) listOf(item.id)
                    else byKey.values.filter { it.attachment?.album == album && it.attachment.isVisualMedia }.map { it.id }
                }
                if (visible.isNotEmpty()) pairing.markMessagesRead(visible)
            }
        }
    }
    Box(modifier.fillMaxWidth()) {
    if (LocalPairedDark.current && wallpaper == ChatWallpaperOption()) {
        DefaultChatBackdrop(Modifier.matchParentSize())
    } else {
        ChatWallpaper(wallpaper, pairing.appearance.pictureSource, Modifier.matchParentSize())
    }
    Column(Modifier.fillMaxSize()) {
        if (search) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(8.dp),
            label = { Text("Search conversation") }, singleLine = true)
        Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item(key = "older") {
                if (!searching && (messages.size >= 80 || callHistory.size >= 80)) TextButton({ scope.launch {
                    pairing.chat.older()
                    if (showCallHistory != null) pairing.chat.olderCalls()
                } }) { Text("Load older messages") }
            }
            itemsIndexed(shown, key = { _, item -> item.chatKey() }) { index, item ->
                val albumItems = albums[item.outgoing to item.attachment?.album].orEmpty()
                val itemKey = item.chatKey()
                if (albumItems.size > 1 && itemKey != albumItems.first().chatKey()) return@itemsIndexed
                val date = chatDate(Instant.ofEpochMilli(item.time))
                if (index == 0 || chatDate(Instant.ofEpochMilli(shown[index - 1].time)) != date) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .07f))) {
                            Text(date, Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 0.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
                    }
                }
                if (albumItems.size > 1) {
                    val marked = albumItems.any { it.chatKey() in selection }
                    ChatSelectionRow(item.outgoing, marked, highlighted == itemKey) {
                        ChatAlbum(pairing, albumItems, item.outgoing, selection,
                            select = ::toggleSelection, quote = {
                                item.reply?.let { reference ->
                                    ChatReplyPreview(pairing, reference, originals["${reference.outgoing}:${reference.id}"],
                                        pairState.partner, Modifier.fillMaxWidth().padding(5.dp),
                                        onOpen = if (selection.isEmpty()) { { openReply(reference) } } else null,
                                        onSelect = { toggleSelection(item) })
                                }
                            })
                    }
                    return@itemsIndexed
                }
                if (item.call != null) {
                    ChatSelectionRow(item.outgoing, itemKey in selection, highlighted == itemKey) {
                        ChatCallCard(item.call, pairState.partner,
                            if (selection.isEmpty()) showCallHistory ?: {} else { { toggleSelection(item) } },
                            { toggleSelection(item) })
                    }
                    return@itemsIndexed
                }
                if (!item.outgoing && item.id == unreadAnchor && !searching) {
                    Text("Unread messages", Modifier.fillMaxWidth().padding(6.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                val bubbleRadius = (theme.shape.radius * .78f).dp
                val marked = itemKey in selection
                val visual = item.attachment?.isVisualMedia == true
                val colors = MaterialTheme.colorScheme
                val bubbleColor = if (item.outgoing) Color(theme.bubble) else colors.surfaceVariant
                val lightBubble = bubbleColor.luminance() > .43f
                var drag by remember { mutableFloatStateOf(0f) }
                val swipe = if (item.deleted || selection.isNotEmpty()) Modifier else Modifier.pointerInput(item) {
                    val threshold = 58.dp.toPx()
                    detectHorizontalDragGestures(onDragEnd = {
                        if (drag >= threshold) selectReply(item)
                        drag = 0f
                    }, onDragCancel = { drag = 0f }) { change, amount ->
                        change.consume()
                        drag = (drag + amount).coerceIn(0f, 78.dp.toPx())
                    }
                }
                ChatSelectionRow(item.outgoing, marked, highlighted == itemKey) {
                    Surface(Modifier.widthIn(max = 280.dp).then(swipe).offset { IntOffset(drag.roundToInt(), 0) }
                        .combinedClickable(onClick = {
                            when {
                                selection.isNotEmpty() -> toggleSelection(item)
                                item.status == -1 -> pairing.retryMessage(item.id)
                            }
                        }, onDoubleClick = {
                            if (selection.isEmpty() && !item.deleted)
                                action(item, ChatAction.REACT, if (item.ownReaction == "👍") "" else "👍")
                        }, onLongClick = { toggleSelection(item) }),
                        border = BorderStroke(1.dp, if (item.outgoing && lightBubble) Color.White.copy(alpha = .27f)
                            else colors.onSurface.copy(alpha = .07f)),
                        shape = RoundedCornerShape(
                            topStart = bubbleRadius, topEnd = bubbleRadius,
                            bottomStart = bubbleRadius,
                            bottomEnd = bubbleRadius,
                        ),
                        color = bubbleColor,
                        contentColor = if (!item.outgoing) MaterialTheme.colorScheme.onSurface
                            else if (Color(theme.bubble).luminance() > 0.43f) Color(0xff141a0a) else Color.White) {
                        val highlight = if (item.outgoing && !lightBubble) .04f else .11f
                        Column(Modifier.background(Brush.verticalGradient(listOf(
                            Color.White.copy(alpha = highlight), Color.Transparent, Color.Black.copy(alpha = .08f),
                        ))).then(if (visual) Modifier else Modifier.padding(horizontal = 10.dp, vertical = 5.dp))) {
                            item.reply?.let { reference ->
                                ChatReplyPreview(pairing, reference, originals["${reference.outgoing}:${reference.id}"],
                                    pairState.partner, Modifier
                                        .then(if (visual) Modifier.padding(horizontal = 6.dp, vertical = 5.dp)
                                            else Modifier.padding(bottom = 4.dp)),
                                    onOpen = if (selection.isEmpty()) { { openReply(reference) } } else null,
                                    onSelect = { toggleSelection(item) })
                            }
                            if (item.attachment == null) Row(verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text(item.body, Modifier.weight(1f, fill = false),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 19.sp, letterSpacing = 0.sp),
                                    fontStyle = if (item.deleted) androidx.compose.ui.text.font.FontStyle.Italic else null)
                                ChatMessageMetadata(item, bubbleColor, LocalContentColor.current)
                            }
                            else ChatAttachmentBubble(pairing, item, compact = true, interactive = selection.isEmpty(),
                                gallery = shown.filter { it.attachment?.isVisualMedia == true }, onSelect = { toggleSelection(item) }, mediaFooter = if (visual) {
                                { ChatMessageMetadata(item, Color(0xff17171a), Color.White) }
                            } else null)
                            item.attachment?.takeUnless { it.isVisualMedia }?.let { ChatTransferStatus(pairing, it) }
                            if (item.attachment != null && !item.attachment.voice &&
                                item.body.isNotBlank() && item.body != item.attachment.name) {
                                Text(item.body, Modifier.padding(horizontal = if (visual) 9.dp else 0.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                            if (item.reactions.isNotEmpty()) Text(item.reactions.joinToString(" "),
                                modifier = if (visual) Modifier.padding(5.dp) else Modifier,
                                style = MaterialTheme.typography.labelMedium)
                            if (!visual && item.attachment != null) Box(Modifier.align(Alignment.End)) {
                                ChatMessageMetadata(item, bubbleColor, LocalContentColor.current)
                            }
                        }
                    }
                }
            }
        }
        if (!searching && list.canScrollForward && selection.isEmpty()) {
            BadgedBox(modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 9.dp),
                badge = { if (newMessages > 0) Badge { Text(if (newMessages > 99) "99+" else newMessages.toString()) } }) {
                SmallFloatingActionButton(
                    onClick = { scope.launch { list.animateScrollToItem(shown.size); newMessages = 0 } },
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Icon(painterResource(R.drawable.ic_chat_down), "Jump to latest", Modifier.size(18.dp))
                }
            }
        }
        }
        error?.let { Text(it, Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error) }
        editing?.let { item ->
            ListItem(headlineContent = { Text("Edit message") },
                supportingContent = { Text(item.body, maxLines = 1) },
                trailingContent = { TextButton({ draft = ""; editing = null }) { Text("Cancel") } })
        }
        reply?.let { reference ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ChatReplyPreview(pairing, reference, originals["${reference.outgoing}:${reference.id}"],
                    pairState.partner, Modifier.weight(1f), onOpen = { openReply(reference) })
                IconButton({ reply = null }) { Icon(Icons.Outlined.Close, "Cancel reply") }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (voiceStage == VoiceStage.Idle) {
                val colors = MaterialTheme.colorScheme
                IconButton(::openAttachments,
                    Modifier.size(44.dp).background(colors.surfaceVariant, CircleShape)
                        .border(1.dp, colors.onSurface.copy(alpha = .17f), CircleShape),
                    enabled = !sending && editing == null) {
                    Icon(Icons.Outlined.Add, "Attach files", Modifier.size(20.dp))
                }
                ChatMessageField(draft, { draft = it.take(2000); if (draft.isNotBlank()) pairing.typing() },
                    ::openCamera, Modifier.weight(1f))
            } else ChatRecordingBar(voiceStage, voiceDraft?.duration ?: recordTime, liveLevels,
                ::discardRecording, Modifier.weight(1f))

            if (voiceStage == VoiceStage.Idle && draft.isNotBlank()) FilledIconButton({
                val text = draft
                sending = true
                scope.launch {
                    try {
                        val target = editing
                        val sent = if (target == null) pairing.sendMessage(text, reply)
                            else pairing.messageAction(target, ChatAction.EDIT, text)
                        if (sent) {
                            if (draft == text) draft = ""
                            editing = null
                            reply = null
                            error = null
                        } else error = "Secure chat unavailable. Connect to the updated Together server."
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { error = "Couldn’t save the message. Try again." }
                    finally { sending = false }
                }
            }, Modifier.size(44.dp), enabled = !sending) {
                Icon(Icons.AutoMirrored.Outlined.Send, "Send message", Modifier.size(17.dp))
            } else ChatVoiceControls(voiceStage, allowRecording && !sending && editing == null,
                press = { beginVoice(VoiceStage.Held) },
                release = {
                    if (voiceStage == VoiceStage.Held) {
                        if (recorder.elapsed < 250) discardRecording() else sendRecording()
                    }
                },
                cancelHold = { if (voiceStage == VoiceStage.Held) discardRecording() },
                lock = { if (voiceStage == VoiceStage.Held) voiceStage = VoiceStage.Locked },
                accessibleStart = { beginVoice(VoiceStage.Locked) },
                send = ::sendRecording,
            )
        }
    }
    if (headerSelection == null && selection.isNotEmpty()) {
        Surface(Modifier.align(Alignment.TopCenter).fillMaxWidth().zIndex(1f)) {
            Row(Modifier.height(52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ChatSelectionActions(selectionState)
            }
        }
    }
    }
    selected?.let { item ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text("Message") }, text = {
            Column {
                if (!item.deleted) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (emojiValue in chatReactions) {
                            AssistChip(onClick = {
                                action(item, ChatAction.REACT, if (item.ownReaction == emojiValue) "" else emojiValue)
                                selected = null
                            }, label = { Text(emojiValue) })
                        }
                    }
                    TextButton({ selectReply(item); selected = null }) { Text("Reply") }
                    if (item.attachment == null) TextButton({
                        clipboard.setText(AnnotatedString(item.body))
                        selected = null
                    }) { Text("Copy") }
                    if (item.outgoing && item.attachment == null) TextButton({
                        editing = item
                        draft = item.body
                        reply = null
                        selected = null
                    }) { Text("Edit") }
                    if (item.outgoing) TextButton({ confirmDelete = true }) { Text("Delete for everyone") }
                }
                TextButton({
                    scope.launch { pairing.hideMessages(listOf(item)) }
                    selected = null
                }) { Text("Delete for me") }
                TextButton({ toggleSelection(item); selected = null }) { Text("Select messages") }
            }
        }, confirmButton = { TextButton({ selected = null }) { Text("Close") } })
    }
    if (confirmDelete) {
        val items = selected?.let(::listOf) ?: shown.filter { "${it.outgoing}:${it.id}" in selection }
        val canDeleteForEveryone = items.isNotEmpty() && items.all { it.outgoing && !it.deleted && it.call == null }
        AlertDialog(onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${items.size} message${if (items.size == 1) "" else "s"}?") },
            text = {
                Column {
                    TextButton({
                        scope.launch { pairing.hideMessages(items) }
                        selection = emptySet()
                        selected = null
                        confirmDelete = false
                    }) { Text("Delete for me") }
                    if (canDeleteForEveryone) TextButton({
                        scope.launch {
                            for (item in items) {
                                try {
                                    if (!pairing.messageAction(item, ChatAction.DELETE)) error = "Couldn’t delete every message."
                                } catch (cancel: CancellationException) { throw cancel }
                                catch (_: Exception) { error = "Couldn’t delete every message." }
                            }
                        }
                        selection = emptySet()
                        selected = null
                        confirmDelete = false
                    }) { Text("Delete for everyone") }
                }
            },
            confirmButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } })
    }
}

@Composable
private fun ChatSelectionRow(outgoing: Boolean, marked: Boolean, highlighted: Boolean, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val leading = if (outgoing) Alignment.CenterStart else Alignment.CenterEnd
    val shading = when {
        marked -> Color.Gray.copy(alpha = .24f)
        highlighted -> colors.primary.copy(alpha = .24f)
        else -> Color.Transparent
    }
    Box(Modifier.fillMaxWidth().background(shading, RoundedCornerShape(13.dp))
        .semantics { selected = marked }) {
        Box(Modifier.fillMaxWidth().padding(start = if (outgoing) 32.dp else 0.dp, end = if (outgoing) 0.dp else 32.dp),
            contentAlignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart) {
            content()
        }
        if (marked) ChatSelectionCheck(Modifier.align(leading).padding(horizontal = 5.dp))
    }
}

@Composable
private fun ChatSelectionCheck(modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.size(21.dp).background(colors.primary, CircleShape), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.Check, null, Modifier.size(15.dp), tint = colors.onPrimary)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatReplyPreview(
    pairing: TogetherPairing,
    reference: ChatReference,
    loadedOriginal: PairedMessage?,
    partner: String?,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)?,
    onSelect: (() -> Unit)? = null,
) {
    val storedOriginal by produceState<PairedMessage?>(null, reference, loadedOriginal, pairing.conversationId) {
        value = if (loadedOriginal == null) pairing.chat.replyOriginal(reference) else null
    }
    val original = loadedOriginal ?: storedOriginal
    val item = original?.attachment
    val video = item?.mime?.startsWith("video/") == true
    val visual = item?.isVisualMedia == true
    val voice = item?.voice == true
    val colors = MaterialTheme.colorScheme
    val sender = if (reference.outgoing) "You" else partner ?: "Partner"
    val foreground = colors.onSurfaceVariant
    val shape = RoundedCornerShape(9.dp)
    val open = if (onOpen == null) Modifier else Modifier.combinedClickable(
        onClick = onOpen, onClickLabel = "Go to original message", onLongClick = onSelect,
    )
    Row(modifier.then(if (item != null) Modifier.width(240.dp) else Modifier.widthIn(max = 240.dp))
        .heightIn(min = 39.dp).clip(shape).background(colors.surfaceVariant).drawBehind {
            val stripe = 3.dp.toPx()
            val x = if (layoutDirection == LayoutDirection.Ltr) 0f else size.width - stripe
            drawRect(colors.primary, Offset(x, 0f), androidx.compose.ui.geometry.Size(stripe, size.height))
        }.then(open),
        verticalAlignment = Alignment.CenterVertically) {
            Column((if (visual) Modifier.weight(1f) else Modifier)
                .padding(start = 10.dp, end = 7.dp, top = 4.dp, bottom = 4.dp)
                .then(if (item != null && !visual) Modifier.fillMaxWidth() else Modifier)) {
                Text(sender, color = colors.primary, style = MaterialTheme.typography.labelMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (item != null && (visual || voice)) {
                        val glyph = when {
                            voice -> R.drawable.ic_mic
                            video -> R.drawable.ic_camera
                            else -> R.drawable.ic_chat_photo
                        }
                        Icon(painterResource(glyph), if (voice) "Voice note" else null, Modifier.size(15.dp), foreground)
                    }
                    if (voice && item != null) {
                        ChatWaveform(item.waveform, foreground, Modifier.weight(1f)
                            .semantics { contentDescription = "Voice note waveform" })
                        Text(chatMediaDuration(item.duration), color = foreground, style = MaterialTheme.typography.labelSmall)
                    } else {
                        val preview = when {
                            original?.deleted == true -> "Message deleted"
                            video && item != null && item.duration > 0 -> "Video · ${chatMediaDuration(item.duration)}"
                            original != null -> original.previewLabel()
                            else -> "Original message unavailable"
                        }
                        Text(preview, color = foreground, style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        if (visual && original != null) {
            Box(Modifier.size(60.dp, 43.dp).clip(shape).background(colors.surface), contentAlignment = Alignment.Center) {
                if (!video) Icon(painterResource(R.drawable.ic_chat_photo), null, Modifier.size(17.dp), foreground)
                ChatTilePreview(pairing, original, Modifier.fillMaxSize(), loadOriginal = false,
                    description = if (video) "Video thumbnail" else "Photo thumbnail")
                if (video) Box(Modifier.size(22.dp).background(Color.Black.copy(alpha = .58f), CircleShape),
                    contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_media_play), null, Modifier.size(11.dp), Color.White)
                }
            }
        }
    }
}


@Composable
private fun ChatMessageField(value: String, change: (String) -> Unit, openCamera: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val fieldStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, letterSpacing = 0.sp)
    Surface(modifier, shape = RoundedCornerShape(22.dp), color = colors.surfaceVariant,
        border = BorderStroke(1.dp, colors.onSurface.copy(alpha = .17f))) {
        Row(Modifier.heightIn(min = 44.dp).background(Brush.verticalGradient(
            listOf(Color.White.copy(alpha = .075f), Color.Transparent))).padding(start = 11.dp),
            verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(value, change, Modifier.weight(1f).padding(vertical = 6.dp)
                .semantics { contentDescription = "Message" },
                textStyle = fieldStyle.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary), maxLines = 4,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                decorationBox = { input ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) Text("Message", color = colors.onSurfaceVariant,
                            style = fieldStyle)
                        input()
                    }
                })
            IconButton(openCamera, Modifier.size(44.dp)) {
                Icon(painterResource(R.drawable.ic_chat_photo), "Camera", Modifier.size(21.dp))
            }
        }
    }
}

@Composable
private fun ChatMessageMetadata(item: PairedMessage, background: Color, foreground: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        val style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 0.sp)
        val color = foreground.copy(alpha = .84f)
        if (item.edited) Text("Edited", style = style, color = color)
        Text(pairedChatTime(item.time), style = style, color = color)
        if (item.outgoing) {
            if (item.played && item.attachment?.voice == true) {
                Text("Played", style = style, color = chatReadColor(background))
            } else ChatReceipt(item.status, background, foreground)
        }
    }
}

private fun chatReadColor(background: Color): Color =
    if (background.luminance() > .43f) Color(0xff004b9b) else Color(0xff67bcff)

@Composable
private fun ChatReceipt(status: Int, background: Color, foreground: Color) {
    val (icon, label) = when (status) {
        -1 -> R.drawable.ic_chat_retry to "Failed, tap to retry"
        0 -> R.drawable.ic_chat_clock to "Pending"
        1 -> R.drawable.ic_chat_tick to "Sent"
        2 -> R.drawable.ic_chat_ticks to "Delivered"
        3 -> R.drawable.ic_chat_ticks to "Read"
        else -> R.drawable.ic_chat_saved to "Restored from backup"
    }
    val tint = if (status == 3) chatReadColor(background) else foreground.copy(alpha = .78f)
    Icon(painterResource(icon), label, Modifier.size(if (status in 2..3) 16.dp else 12.dp), tint)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatCallCard(call: PairedCallEntry, partner: String?, open: () -> Unit, select: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val accent = if (call.outcome == "missed") colors.error else colors.primary
    val shape = RoundedCornerShape(12.dp)
    val caller = if (call.incoming) "${partner ?: "Partner"} called" else "You called"
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = if (call.incoming) Arrangement.Start else Arrangement.End) {
        Surface(modifier = Modifier.widthIn(max = 220.dp).clip(shape).combinedClickable(onClick = open, onLongClick = select), shape = shape,
            color = colors.onSurface.copy(alpha = .045f).compositeOver(colors.surface),
            border = BorderStroke(1.dp, accent.copy(alpha = .26f))) {
            Row(Modifier.background(Brush.verticalGradient(listOf(accent.copy(alpha = .08f), Color.Transparent)))
                .padding(horizontal = 8.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Surface(shape = RoundedCornerShape(8.dp), color = accent.copy(alpha = .12f), contentColor = accent) {
                    Icon(painterResource(if (call.kind == PairKind.VIDEO) R.drawable.ic_camera else R.drawable.ic_call),
                        null, Modifier.padding(7.dp).size(15.dp))
                }
                Column(Modifier.weight(1f, fill = false)) {
                    val duration = call.duration.takeIf { it > 0L }?.let { " · ${chatMediaDuration(it)}" }.orEmpty()
                    Text(call.title + duration, style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.sp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(painterResource(if (call.incoming) R.drawable.ic_chat_incoming else R.drawable.ic_chat_outgoing),
                            null, Modifier.size(10.dp), accent)
                        Text("$caller · ${pairedChatTime(call.time)}",
                            color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 10.sp, lineHeight = 13.sp, letterSpacing = 0.sp))
                    }
                }
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Open call history", Modifier.size(14.dp),
                    colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DefaultChatBackdrop(modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier.background(Color(0xff0d1014))) {
        val w = size.width
        val h = size.height
        if (w == 0f || h == 0f) return@Canvas
        drawRect(Brush.radialGradient(listOf(accent.copy(alpha = .085f), Color.Transparent),
            center = Offset(w * .93f, h * .06f), radius = w * .58f))
        val curve = Path().apply {
            moveTo(-w * .12f, h * .57f)
            cubicTo(w * .22f, h * .61f, w * .52f, h * .91f, w * 1.12f, h * .70f)
        }
        val edge = Brush.horizontalGradient(listOf(Color.Transparent, accent.copy(alpha = .24f),
            accent.copy(alpha = .045f), Color.Transparent), endX = w)
        drawPath(curve, edge, style = Stroke(width = 1.dp.toPx()))
        drawPath(curve, Brush.horizontalGradient(listOf(Color.Transparent, accent.copy(alpha = .035f),
            Color.Transparent), endX = w), style = Stroke(width = 18.dp.toPx()))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatAlbum(pairing: TogetherPairing, items: List<PairedMessage>, outgoing: Boolean,
    selection: Set<String>, select: (PairedMessage) -> Unit, quote: @Composable () -> Unit) {
    var current by remember { mutableIntStateOf(-1) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start) {
        Column(Modifier.width(220.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = {
                if (selection.isEmpty()) current = 0 else select(items.first())
            }, onLongClick = { select(items.first()) })) {
            quote()
            Box(contentAlignment = Alignment.Center) {
            Column {
            for (row in 0..1) Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (column in 0..1) {
                    val index = row * 2 + column
                    val item = items.getOrNull(index)
                    if (item != null) Box(Modifier.weight(1f).aspectRatio(1f).combinedClickable(
                        onClick = { if (selection.isEmpty()) current = index else select(item) },
                        onLongClick = { select(item) }),
                        contentAlignment = Alignment.Center) {
                        ChatTilePreview(pairing, item, Modifier.fillMaxSize())
                        if (item.attachment?.mime?.startsWith("video/") == true)
                            Icon(painterResource(R.drawable.ic_media_play), "Video", tint = Color.White)
                        if (index == 3 && items.size > 4) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .55f)),
                            contentAlignment = Alignment.Center) { Text("+${items.size - 4}", color = Color.White) }
                        if (item.chatKey() in selection) {
                            Box(Modifier.matchParentSize().background(Color.Gray.copy(alpha = .24f)))
                            ChatSelectionCheck(Modifier.align(Alignment.TopEnd).padding(4.dp))
                        }
                    } else Spacer(Modifier.weight(1f))
                }
            }
            }
            ChatAlbumTransfer(pairing, items)
            if (selection.isEmpty()) ChatRetryUploads(pairing, items, Modifier.align(Alignment.BottomCenter))
            }
            val first = items.first()
            if (first.body != first.attachment?.name) Text(first.body, Modifier.padding(8.dp))
            Box(Modifier.align(Alignment.End).padding(6.dp)) {
                ChatMessageMetadata(items.last(), MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurface)
            }
        }
    }
    if (current >= 0) {
        ChatMediaViewer(pairing, items[current], album = items, initialIndex = current) { current = -1 }
    }
}
