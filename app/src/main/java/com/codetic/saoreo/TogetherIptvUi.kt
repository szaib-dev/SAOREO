package com.codetic.saoreo

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IptvPlaylistPicker(
    catalog: TogetherIptvCatalog,
    shared: IptvBrowse?,
    onNavigate: (IptvBrowse) -> Unit,
    onClose: () -> Unit,
    onSelect: (TogetherMedia) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var setup by remember(catalog.entries.isEmpty()) { mutableStateOf(catalog.entries.isEmpty()) }
    var xtream by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf<(() -> Unit)?>(null) }
    var categoriesOpen by remember { mutableStateOf(false) }
    val view = shared ?: IptvBrowse()

    fun load(source: String, persist: Boolean) {
        if (loading) return
        scope.launch {
            loading = true
            message = null
            try {
                val saved = catalog.load(source, persist)
                setup = false
                url = ""
                username = ""
                password = ""
                query = ""
                if (!saved) message = "Playlist opened. SAOREO couldn't save it for next time."
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: IptvHttpConsentRequired) {
                retry = { load(source, persist) }
            } catch (_: Exception) {
                message = "Couldn't load the IPTV catalog. Check the provider URL, file or login."
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(catalog) {
        if (catalog.entries.isEmpty()) {
            try {
                catalog.restore()?.let { load(it, false) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                message = "Couldn't restore the saved IPTV source."
            }
        }
    }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { load(it.toString(), true) }
    }
    val kindEntries = remember(catalog.entries, view.kind) {
        catalog.entries.filter { view.kind.isEmpty() || it.kind.name == view.kind }
    }
    val categories = remember(kindEntries) {
        kindEntries.map { it.group }.filter { it.isNotBlank() }.distinct().sorted()
    }
    val results = remember(kindEntries, view.category, query) {
        val category = categories.firstOrNull { iptvKey(it) == view.category }
        kindEntries.filter {
            (view.category.isEmpty() || it.group == category) &&
                (query.isBlank() || it.title.contains(query.trim(), ignoreCase = true))
        }
    }
    val grid = rememberLazyGridState()
    val dragging by grid.interactionSource.collectIsDraggedAsState()
    var publishScroll by remember { mutableStateOf(false) }
    LaunchedEffect(dragging, grid.isScrollInProgress) {
        if (dragging) publishScroll = true
        if (publishScroll && !dragging && !grid.isScrollInProgress) {
            publishScroll = false
            if (query.isBlank() && view.focused.isEmpty()) {
                results.getOrNull(grid.firstVisibleItemIndex)?.let { entry ->
                    if (entry.ref != view.anchor) onNavigate(view.copy(anchor = entry.ref))
                }
            }
        }
    }
    LaunchedEffect(view.anchor, view.kind, view.category, view.focused, results) {
        if (query.isBlank() && view.focused.isEmpty() && results.isNotEmpty() && !grid.isScrollInProgress) {
            val index = if (view.anchor.isBlank()) 0 else results.indexOfFirst { it.ref == view.anchor }
            if (index >= 0 && index != grid.firstVisibleItemIndex) grid.scrollToItem(index)
        }
    }
    val focused = remember(catalog.entries, view.focused) { catalog.entries.firstOrNull { it.ref == view.focused } }
    var episodes by remember(focused) { mutableStateOf<List<IptvProgram>?>(null) }
    var episodeFailed by remember(focused) { mutableStateOf(false) }
    LaunchedEffect(focused, catalog.allowHttp) {
        val series = focused?.seriesId ?: return@LaunchedEffect
        episodeFailed = false
        try {
            episodes = catalog.episodes(series)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: IptvHttpConsentRequired) {
            retry = {}
        } catch (_: Exception) {
            episodeFailed = true
        }
    }

    ModalBottomSheet(onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 720.dp).imePadding().padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (setup) "Set up IPTV" else catalog.sourceName, style = MaterialTheme.typography.titleLarge)
                if (catalog.entries.isNotEmpty()) TextButton({ setup = !setup }) { Text(if (setup) "Catalog" else "Source") }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (setup) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!xtream, { xtream = false; url = "" }, { Text("M3U / M3U8") })
                    FilterChip(xtream, { xtream = true; url = "" }, { Text("Xtream Codes") })
                }
                if (shared != null && catalog.entries.isEmpty()) {
                    Text("Add the same provider or playlist on this phone to follow the shared catalog.")
                }
                if (xtream) OutlinedTextField(name, { name = it.take(100) }, Modifier.fillMaxWidth(),
                    label = { Text("Playlist name") }, singleLine = true)
                OutlinedTextField(url, { url = it.take(4096) }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(if (xtream) "Server URL" else "Playlist or stream URL") },
                    placeholder = { Text("https://") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    visualTransformation = if (xtream) androidx.compose.ui.text.input.VisualTransformation.None
                    else androidx.compose.ui.text.input.PasswordVisualTransformation())
                if (xtream) {
                    OutlinedTextField(username, { username = it.take(512) }, Modifier.fillMaxWidth(),
                        label = { Text("Username") }, singleLine = true)
                    OutlinedTextField(password, { password = it.take(512) }, Modifier.fillMaxWidth(),
                        label = { Text("Password") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({
                        val input = url.trim()
                        val address = iptvUrl(if ("://" in input) input else "https://$input")
                        if (address == null || (xtream && address.query != null)) message = "Enter an HTTP or HTTPS URL."
                        else load(if (xtream) XtreamLogin(name.trim(), address, username, password).savedValue()
                            else address.toString(), true)
                    }, enabled = !loading && url.isNotBlank() && (!xtream || name.isNotBlank() &&
                        username.isNotBlank() && password.isNotBlank())) { Text("Load catalog") }
                    if (!xtream) OutlinedButton({ file.launch(arrayOf("*/*")) }, enabled = !loading) { Text("Open file") }
                }
            } else if (focused != null) {
                TextButton({ onNavigate(view.copy(focused = "")) }) { Text("Back to catalog") }
                IptvArtwork(focused, catalog.allowHttp, Modifier.fillMaxWidth().height(160.dp))
                Text(focused.title, style = MaterialTheme.typography.headlineSmall)
                if (focused.group.isNotBlank()) Text(focused.group, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (focused.seriesId != null) {
                    when {
                        episodeFailed -> Text("Couldn't load episodes.", color = MaterialTheme.colorScheme.error)
                        episodes == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        episodes.isNullOrEmpty() -> Text("No episodes available.")
                        else -> LazyColumn(Modifier.weight(1f, fill = false)) {
                            items(episodes.orEmpty()) { episode ->
                                TextButton({ if (!onSelect(episode.media())) message = "Reconnect to Together." },
                                    Modifier.fillMaxWidth()) { Text("${episode.group} · ${episode.title}") }
                            }
                        }
                    }
                } else {
                    Button({
                        if (!onSelect(focused.media())) message = "Reconnect to Together."
                    }, Modifier.fillMaxWidth()) { Text("Watch together") }
                    Text("Each phone connects to the provider. Watching together requires two permitted connections.",
                        style = MaterialTheme.typography.bodySmall)
                }
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(view.kind.isEmpty(), { onNavigate(IptvBrowse()) }, { Text("All") }) }
                    items(IptvKind.entries) { kind ->
                        FilterChip(view.kind == kind.name, { onNavigate(IptvBrowse(kind.name)) }, { Text(kind.label) })
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(query, { query = it.take(150) }, Modifier.weight(1f), singleLine = true,
                        label = { Text("Find a program") })
                    Box {
                        TextButton({ categoriesOpen = true }) { Text("Categories") }
                        DropdownMenu(categoriesOpen, { categoriesOpen = false }, Modifier.heightIn(max = 320.dp)) {
                            DropdownMenuItem({ Text("All categories") }, {
                                categoriesOpen = false
                                onNavigate(view.copy(category = "", anchor = ""))
                            })
                            categories.forEach { category ->
                                DropdownMenuItem({ Text(category) }, {
                                    categoriesOpen = false
                                    onNavigate(view.copy(category = iptvKey(category), anchor = ""))
                                })
                            }
                        }
                    }
                }
                if (view.focused.isNotEmpty()) Text("This program is missing from your catalog.")
                if (results.isEmpty()) Text("No matching programs.")
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(120.dp),
                    state = grid,
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(results.size) { index ->
                        val program = results[index]
                        Card(onClick = { onNavigate(view.copy(focused = program.ref)) }) {
                            IptvArtwork(program, catalog.allowHttp, Modifier.fillMaxWidth().height(if (program.kind == IptvKind.LIVE) 76.dp else 140.dp))
                            Text(program.title, Modifier.padding(8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    retry?.let { action ->
        IptvHttpWarning(onCancel = { retry = null }, onAllow = {
            catalog.allowHttp = true
            retry = null
            action()
        })
    }
}

@Composable
internal fun IptvHttpWarning(onCancel: () -> Unit, onAllow: () -> Unit) {
    AlertDialog(onDismissRequest = onCancel, title = { Text("Allow unencrypted IPTV?") },
        text = { Text("HTTP can expose your IPTV login and viewing activity to anyone monitoring the connection. Use HTTPS when your provider supports it. This approval applies to IPTV on this phone for this Together session.") },
        confirmButton = { TextButton(onAllow) { Text("Allow IPTV HTTP") } },
        dismissButton = { TextButton(onCancel) { Text("Cancel") } })
}

private val artworkRequests = kotlinx.coroutines.sync.Semaphore(4)

@Composable
private fun IptvArtwork(program: IptvProgram, allowHttp: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val image by produceState<ByteArray?>(null, program.logo, allowHttp) {
        value = null
        val address = program.logo?.let(::iptvUrl)?.takeIf { it.scheme == "https" || allowHttp } ?: return@produceState
        value = withContext(Dispatchers.IO) {
            artworkRequests.withPermit {
                runCatching {
                    IptvNetwork.open(address, allowHttp).use { reply ->
                        if (reply.status !in 200..299) return@use null
                        val limit = 512 * 1024
                        reply.body?.readNBytes(limit + 1)?.takeIf { it.size <= limit }
                    }
                }.getOrNull()
            }
        }
    }
    val artwork = image
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceVariant) {
        if (artwork == null) Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
            Text(program.kind.label, style = MaterialTheme.typography.labelMedium)
        } else coil.compose.AsyncImage(
            model = coil.request.ImageRequest.Builder(context).data(artwork)
                .diskCachePolicy(coil.request.CachePolicy.DISABLED).build(),
            contentDescription = null,
            contentScale = if (program.kind == IptvKind.LIVE) androidx.compose.ui.layout.ContentScale.Fit
                else androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
