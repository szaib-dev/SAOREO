package com.codetic.saoreo

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

internal enum class PairedPage { Info, Calls, Shared, Theme, Wallpaper, Storage, Notifications, Settings }

@Composable
internal fun PairedPageHeader(title: String, back: () -> Unit) {
    Row(Modifier.fillMaxWidth().statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
        Text(title, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
internal fun PartnerAvatar(partner: String, size: Int, online: Boolean = false, picture: File? = null, revision: Int = 0) {
    Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
        Surface(Modifier.fillMaxSize(), color = Color(0xff293522), contentColor = Color(0xffc9ee65), shape = CircleShape) {
            Box(contentAlignment = Alignment.Center) {
                if (picture?.isFile == true) AsyncImage(model = coil.request.ImageRequest.Builder(LocalContext.current)
                    .data(picture).memoryCacheKey(picture.path + revision).build(), contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Text(partner.take(1).uppercase(), style = if (size > 60) MaterialTheme.typography.headlineLarge
                    else MaterialTheme.typography.titleMedium)
            }
        }
        if (online) Surface(Modifier.align(Alignment.BottomEnd).size(if (size > 60) 17.dp else 11.dp),
            shape = CircleShape, color = Color(0xffb4e653)) {}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PartnerInfoPage(pairing: TogetherPairing, state: PairState, back: () -> Unit, go: (PairedPage) -> Unit,
    audio: () -> Unit, video: () -> Unit, search: () -> Unit, canCall: Boolean, oneTime: (() -> Unit)?) {
    val partner = state.partner ?: return
    val changes by pairing.chat.messages.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var photoError by remember { mutableStateOf<String?>(null) }
    val revision by pairing.appearance.photoRevision.collectAsStateWithLifecycle()
    val conversation = pairing.conversationId
    val photo = pairing.appearance.partnerPhoto(conversation)
    val myPhoto = pairing.appearance.myPhoto(conversation)
    var showMyPhoto by rememberSaveable { mutableStateOf(false) }
    val photos = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && conversation != null) scope.launch {
            try { pairing.choosePartnerPhoto(uri); photoError = null }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { photoError = "Couldn’t save this photo." }
        }
    }
    val thumbnails by produceState(emptyList<PairedMessage>(), conversation, changes) {
        value = pairing.chat.shared(SharedChatTab.Media, 4).asReversed()
    }
    val sharedCount by produceState(0L, conversation, changes) { value = pairing.chat.sharedCount() }
    var menu by rememberSaveable { mutableStateOf(false) }
    var pairingOptions by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val panelColor = colors.onSurface.copy(alpha = .038f).compositeOver(colors.background)
    val line = colors.onSurface.copy(alpha = .09f)
    val online = state.connected && state.online

    Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(back) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", Modifier.size(21.dp))
            }
            Text(partner, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 0.sp),
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box {
                IconButton({ menu = true }) {
                    Icon(Icons.Outlined.MoreVert, "Partner options", Modifier.size(21.dp))
                }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Call history") }, onClick = { menu = false; go(PairedPage.Calls) })
                    DropdownMenuItem(text = { Text("Storage and backup") }, onClick = { menu = false; go(PairedPage.Storage) })
                    DropdownMenuItem(text = { Text("Choose partner photo") }, enabled = conversation != null,
                        onClick = { menu = false; photos.launch(arrayOf("image/*")) })
                    if (photo?.isFile == true && conversation != null) DropdownMenuItem(
                        text = { Text("Remove photo") }, onClick = {
                            menu = false
                            scope.launch {
                                try { pairing.choosePartnerPhoto(null); photoError = null }
                                catch (cancel: CancellationException) { throw cancel }
                                catch (_: Exception) { photoError = "Couldn’t remove this photo." }
                            }
                        })
                    DropdownMenuItem(text = { Text("View my photo") }, onClick = { menu = false; showMyPhoto = true })
                    DropdownMenuItem(text = { Text("Pairing settings") },
                        onClick = { menu = false; pairingOptions = true })
                    if (oneTime != null) DropdownMenuItem(text = { Text("Use a one-time watch room") },
                        onClick = { menu = false; oneTime() })
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(6.dp))
            PartnerAvatar(partner, 88, online, photo, revision)
            Spacer(Modifier.height(7.dp))
            Text(partner, style = MaterialTheme.typography.titleLarge.copy(letterSpacing = 0.sp),
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp))
            Text(if (online) "Online" else "Offline", color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall.copy(letterSpacing = 0.sp))
            photoError?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.widthIn(max = 360.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 15.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PartnerInfoAction("Audio call", R.drawable.ic_call, audio, Modifier.weight(1f), canCall)
                PartnerInfoAction("Video call", R.drawable.ic_camera, video, Modifier.weight(1f), canCall)
                PartnerInfoAction("Search", R.drawable.ic_ui_search, search, Modifier.weight(1f))
            }
            val panel = RoundedCornerShape(16.dp)
            Surface(Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(horizontal = 7.dp),
                color = panelColor, shape = panel, border = BorderStroke(1.dp, line.copy(alpha = .055f))) {
                Column {
                    PartnerInfoRow("Media, links and files", R.drawable.ic_profile_image,
                        count = sharedCount.toString()) { go(PairedPage.Shared) }
                    if (thumbnails.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(start = 11.dp, end = 11.dp, bottom = 11.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (item in thumbnails) Box(Modifier.weight(1f).aspectRatio(1f)
                            .clip(RoundedCornerShape(11.dp)).clickable { go(PairedPage.Shared) }) {
                            SharedThumbnail(pairing, item)
                        }
                        repeat(4 - thumbnails.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Surface(Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(horizontal = 7.dp),
                color = panelColor, shape = panel, border = BorderStroke(1.dp, line.copy(alpha = .055f))) {
                Column {
                    PartnerInfoRow("Notifications", R.drawable.ic_profile_bell) { go(PairedPage.Notifications) }
                    HorizontalDivider(Modifier.padding(start = 55.dp, end = 12.dp), color = line)
                    PartnerInfoRow("Chat theme", R.drawable.ic_profile_image) { go(PairedPage.Theme) }
                    HorizontalDivider(Modifier.padding(start = 55.dp, end = 12.dp), color = line)
                    PartnerInfoRow("Chat wallpaper", R.drawable.ic_profile_image) { go(PairedPage.Wallpaper) }
                    HorizontalDivider(Modifier.padding(start = 55.dp, end = 12.dp), color = line)
                    PartnerInfoRow("Encryption", R.drawable.ic_chat_lock,
                        subtitle = "Messages are end-to-end encrypted")
                }
            }
        }
    }
    if (showMyPhoto) AlertDialog(
        onDismissRequest = { showMyPhoto = false },
        title = { Text("My photo") },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (myPhoto?.isFile == true) PartnerAvatar("You", 152, picture = myPhoto, revision = revision)
                Text(if (myPhoto?.isFile == true) "Chosen by $partner" else "$partner hasn’t chosen a photo for you yet.")
            }
        },
        confirmButton = { TextButton({ showMyPhoto = false }) { Text("Close") } },
    )
    if (pairingOptions) ModalBottomSheet(onDismissRequest = { pairingOptions = false }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).navigationBarsPadding()) {
            PairedTogetherActions(pairing, state, showHeader = false)
        }
    }
}

@Composable
private fun PartnerInfoAction(label: String, icon: Int, action: () -> Unit, modifier: Modifier,
    enabled: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = action, modifier = modifier, enabled = enabled, shape = RoundedCornerShape(15.dp),
        color = colors.onSurface.copy(alpha = .022f).compositeOver(colors.background),
        border = BorderStroke(1.dp, colors.onSurface.copy(alpha = .11f))) {
        Column(Modifier.heightIn(min = 65.dp).padding(horizontal = 3.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(icon), null, Modifier.size(22.dp))
            Text(label, style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 11.sp, lineHeight = 13.sp, letterSpacing = 0.sp), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun PartnerInfoRow(label: String, icon: Int, count: String? = null, subtitle: String? = null,
    action: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().then(if (action != null) Modifier.clickable(onClick = action) else Modifier)
        .heightIn(min = 49.dp).padding(horizontal = 14.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium.copy(letterSpacing = 0.sp))
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall.copy(letterSpacing = 0.sp),
                color = colors.onSurfaceVariant)
        }
        if (count != null) Text(count, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        if (action != null) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null,
            Modifier.size(17.dp), tint = colors.onSurfaceVariant)
    }
}

@Composable
internal fun TogetherSettingsPage(pairing: TogetherPairing, state: PairState, back: () -> Unit, go: (PairedPage) -> Unit) {
    val context = LocalContext.current
    val activity = context as? TogetherActivity
    val prefs = remember { context.getSharedPreferences("together-lock", Context.MODE_PRIVATE) }
    var enabled by remember { mutableStateOf(prefs.getBoolean("enabled", false)) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).navigationBarsPadding()
        .verticalScroll(rememberScrollState())) {
        PairedPageHeader("Together settings", back)
        ListItem(headlineContent = { Text("Fingerprint lock") },
            supportingContent = { Text("Unlock Together with your device biometric") },
            trailingContent = { Switch(enabled, onCheckedChange = { value ->
                activity?.authenticateTogether({
                    prefs.edit().putBoolean("enabled", value).apply()
                    enabled = value
                    error = null
                }, { error = it })
            }) })
        error?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
        PageSettingsRow("Notifications") { go(PairedPage.Notifications) }
        PageSettingsRow("Chat theme") { go(PairedPage.Theme) }
        PageSettingsRow("Chat wallpaper") { go(PairedPage.Wallpaper) }
        PageSettingsRow("Storage and backup") { go(PairedPage.Storage) }
        Column(Modifier.padding(horizontal = 16.dp)) { PairedTogetherActions(pairing, state, showHeader = false) }
    }
}

@Composable
private fun PageSettingsRow(label: String, open: () -> Unit) {
    ListItem(headlineContent = { Text(label) }, modifier = Modifier.clickable(onClick = open))
}

private enum class CallFilter { All, Missed, Incoming, Outgoing }

@Composable
internal fun CallHistoryPage(pairing: TogetherPairing, name: String, back: () -> Unit, canCall: Boolean) {
    var filter by rememberSaveable { mutableStateOf(CallFilter.All) }
    val history by pairing.chat.calls.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val photo = pairing.appearance.partnerPhoto(pairing.conversationId)
    val photoRevision by pairing.appearance.photoRevision.collectAsStateWithLifecycle()
    val visible = history.filter {
        when (filter) {
            CallFilter.All -> true
            CallFilter.Missed -> it.outcome == "missed"
            CallFilter.Incoming -> it.incoming
            CallFilter.Outgoing -> !it.incoming
        }
    }
    Column(Modifier.fillMaxSize().navigationBarsPadding().background(MaterialTheme.colorScheme.background)) {
        PairedPageHeader("Calls", back)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (entry in CallFilter.entries) FilterChip(filter == entry, { filter = entry }, { Text(entry.name) })
        }
        if (visible.isEmpty()) Text("No calls", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.fillMaxWidth()) {
            items(visible, key = { it.id }) { call ->
                ListItem(leadingContent = { PartnerAvatar(name, 42, picture = photo, revision = photoRevision) }, headlineContent = { Text(name) },
                    supportingContent = {
                        val detail = when (call.outcome) {
                            "completed" -> " · Completed"
                            "accepted" -> " · Accepted"
                            "connected" -> " · Connected"
                            else -> ""
                        }
                        Text(callSummary(call) + detail)
                    }, trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(callDate(call.time), style = MaterialTheme.typography.labelSmall)
                            IconButton({ pairing.ring(call.kind) }, enabled = canCall) {
                                Icon(painterResource(callIcon(call.kind)), "Call back", Modifier.size(23.dp))
                            }
                        }
                    })
                HorizontalDivider(Modifier.padding(start = 68.dp, end = 16.dp))
            }
            if (history.size >= 80) item {
                TextButton({ scope.launch { pairing.chat.olderCalls() } }, Modifier.padding(8.dp)) { Text("Load older calls") }
            }
        }
    }
}

private fun callIcon(kind: PairKind) = if (kind == PairKind.VIDEO) R.drawable.ic_camera else R.drawable.ic_call
private fun callDate(time: Long) = "${DateFormat.getDateInstance(DateFormat.SHORT).format(Date(time))}, ${pairedChatTime(time)}"
private fun callSummary(entry: PairedCallEntry): String {
    val direction = if (entry.incoming) "Incoming" else "Outgoing"
    val duration = if (entry.duration > 0) " · ${chatMediaDuration(entry.duration)}" else ""
    return "$direction · ${entry.title}$duration"
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun SharedContentPage(pairing: TogetherPairing, back: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(SharedChatTab.Media) }
    var limit by rememberSaveable(tab) { mutableIntStateOf(48) }
    val messages by pairing.chat.messages.collectAsStateWithLifecycle()
    val entries by produceState(emptyList<PairedMessage>(), tab, limit, messages) {
        value = pairing.chat.shared(tab, limit).asReversed()
    }
    var active by remember { mutableStateOf<PairedMessage?>(null) }
    var selection by remember(tab) { mutableStateOf(setOf<String>()) }
    var sharing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun select(item: PairedMessage) {
        val key = "${item.outgoing}:${item.id}"
        selection = if (key in selection) selection - key else if (selection.size < 10) selection + key else selection
    }
    fun open(item: PairedMessage) {
        if (selection.isNotEmpty()) {
            select(item)
            return
        }
        val url = if (tab == SharedChatTab.Links) chatUrl.find(item.body)?.value else null
        if (url != null) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { status = "Couldn’t open this link." }
        else active = item
    }
    androidx.activity.compose.BackHandler(selection.isNotEmpty()) { selection = emptySet() }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).navigationBarsPadding()) {
        PairedPageHeader("Media, links and files", back)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (entry in SharedChatTab.entries) FilterChip(entry == tab, { tab = entry },
                { Text(if (entry == SharedChatTab.Voice) "Voice notes" else entry.name) })
        }
        if (selection.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ selection = emptySet() }, enabled = !sharing) { Text("Cancel") }
            Text("${selection.size} selected", Modifier.weight(1f))
            TextButton({
                val chosen = entries.filter { "${it.outgoing}:${it.id}" in selection }
                scope.launch {
                    sharing = true
                    status = null
                    try {
                        val links = chosen.flatMap { message -> chatUrl.findAll(message.body).map { it.value }.toList() }
                        if (tab == SharedChatTab.Links) shareTogetherLink(context, links.joinToString("\n"), "Share links")
                        else {
                            val urls = ArrayList<Uri>()
                            for (item in chosen) {
                                val file = pairing.openAttachment(item)
                                urls += FileProvider.getUriForFile(context, context.packageName + ".pairedmedia", file)
                            }
                            if (urls.isNotEmpty()) context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE)
                                .setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, urls)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share attachments"))
                        }
                        selection = emptySet()
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) { status = attachmentFailure(failure) }
                    finally { sharing = false }
                }
            }, enabled = !sharing) { Text("Share") }
        }
        if (sharing) LinearProgressIndicator(Modifier.fillMaxWidth())
        status?.let { Text(it, Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error) }
        if (entries.isEmpty()) Text("Nothing shared yet", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (tab == SharedChatTab.Media) {
            LazyVerticalGrid(GridCells.Adaptive(105.dp), contentPadding = PaddingValues(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(entries, key = { "${it.outgoing}:${it.id}" }) { item ->
                    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp)).combinedClickable(onClick = { open(item) }, onLongClick = { select(item) })) {
                        SharedThumbnail(pairing, item)
                        if ("${item.outgoing}:${item.id}" in selection) Text("✓", Modifier.align(Alignment.TopEnd)
                            .background(MaterialTheme.colorScheme.primary, CircleShape).padding(horizontal = 7.dp, vertical = 3.dp),
                            color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                if (entries.size == limit) item { TextButton({ limit += 48 }) { Text("Load more") } }
            }
        } else {
            LazyColumn {
                items(entries, key = { "${it.outgoing}:${it.id}" }) { item ->
                    val info = item.attachment
                    val url = if (tab == SharedChatTab.Links) chatUrl.find(item.body)?.value else null
                    ListItem(headlineContent = {
                        Text(info?.name ?: url ?: item.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }, supportingContent = {
                        val metadata = if (info != null) "${info.mime} · ${Formatter.formatFileSize(context, info.size)} · " else ""
                        Text(metadata + callDate(item.time))
                    }, trailingContent = {
                        if ("${item.outgoing}:${item.id}" in selection) Text("✓", color = MaterialTheme.colorScheme.primary)
                    }, modifier = Modifier.combinedClickable(onClick = { open(item) }, onLongClick = { select(item) }))
                }
                if (entries.size == limit) item { TextButton({ limit += 48 }) { Text("Load more") } }
            }
        }
    }
    active?.let { item ->
        if (item.attachment?.isVisualMedia == true) ChatMediaViewer(pairing, item, album = entries,
            initialIndex = entries.indexOf(item).coerceAtLeast(0)) { active = null }
        else MediaActionsDialog(pairing, item) { active = null }
    }
}

private val chatUrl = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE)

@Composable
private fun SharedThumbnail(pairing: TogetherPairing, message: PairedMessage) {
    val item = message.attachment ?: return
    val file = pairing.cachedAttachment(message)
    val preview = remember(item) { item.previewBitmap() }
    val video by produceState<Pair<Bitmap?, Long>?>(null, file, item.id) {
        if (file?.isFile == true && item.mime.startsWith("video/")) value = withContext(Dispatchers.IO) {
            runCatching {
                MediaMetadataRetriever().use {
                    it.setDataSource(file.absolutePath)
                    val duration = it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    it.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 256, 256) to duration
                }
            }.getOrNull()
        }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val frame = video?.first
        if (file?.isFile == true && item.mime.startsWith("image/")) {
            AsyncImage(model = file, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else if (frame != null) {
            Image(frame.asImageBitmap(), item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else if (preview != null) {
            Image(preview.asImageBitmap(), item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else Icon(painterResource(if (item.mime.startsWith("video/")) R.drawable.ic_camera else R.drawable.ic_chat), item.name)
        val duration = video?.second ?: item.duration
        if (item.mime.startsWith("video/") && duration > 0L) Text(chatMediaDuration(duration),
            Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = .68f)).padding(4.dp),
            color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun MediaActionsDialog(pairing: TogetherPairing, message: PairedMessage, close: () -> Unit) {
    val item = message.attachment ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    fun run(action: suspend (File) -> Unit) {
        scope.launch {
            busy = true
            try { action(pairing.openAttachment(message)) }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { feedback = attachmentFailure(failure) }
            finally { busy = false }
        }
    }
    val export = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) run { file ->
            withContext(Dispatchers.IO) {
                checkNotNull(context.contentResolver.openOutputStream(uri, "w")).use { output -> file.inputStream().use { it.copyTo(output) } }
            }
            feedback = "File saved"
        }
    }
    AlertDialog(onDismissRequest = close, title = { Text(item.name, maxLines = 2) }, text = {
        Column {
            if (item.voice) ChatAttachmentBubble(pairing, message)
            TextButton({ if (item.isVisualMedia) viewing = true else run { file -> openSharedFile(context, item, file, false) } },
                enabled = !busy) { Text("Open") }
            TextButton({ run { file -> openSharedFile(context, item, file, true) } }, enabled = !busy) { Text("Share") }
            TextButton({ run { feedback = "Downloaded to SAOREO" } }, enabled = !busy) { Text("Download") }
            if (item.mime.startsWith("image/") || item.mime.startsWith("video/")) {
                TextButton({ run { file -> saveChatGallery(context, item, file); feedback = "Saved to gallery" } }, enabled = !busy) { Text("Save to gallery") }
            } else TextButton({ export.launch(item.name) }, enabled = !busy) { Text("Save file") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            feedback?.let { Text(it) }
        }
    }, confirmButton = { TextButton(close) { Text("Close") } })
    if (viewing) ChatMediaViewer(pairing, message) { viewing = false }
}

private fun openSharedFile(context: Context, item: ChatAttachment, file: File, share: Boolean) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".pairedmedia", file)
    val intent = (if (share) Intent(Intent.ACTION_SEND).setType(item.mime).putExtra(Intent.EXTRA_STREAM, uri)
        else Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.mime))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri(item.name, uri) }
    context.startActivity(if (share) Intent.createChooser(intent, "Share attachment") else intent)
}

internal suspend fun saveChatGallery(context: Context, item: ChatAttachment, file: File) = withContext(Dispatchers.IO) {
    val video = item.mime.startsWith("video/")
    val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val resolver = context.contentResolver
    val uri = checkNotNull(resolver.insert(collection, ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
        put(MediaStore.MediaColumns.MIME_TYPE, item.mime)
        put(MediaStore.MediaColumns.RELATIVE_PATH, if (video) "Movies/SAOREO" else "Pictures/SAOREO")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }))
    try {
        checkNotNull(resolver.openOutputStream(uri, "w")).use { output -> file.inputStream().use { it.copyTo(output) } }
        check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
    } catch (failure: Throwable) {
        resolver.delete(uri, null, null)
        throw failure
    }
}
