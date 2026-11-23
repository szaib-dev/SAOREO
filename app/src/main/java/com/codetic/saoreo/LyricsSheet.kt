package com.codetic.saoreo

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.work.WorkManager
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LyricsSheet(app: SaoreoApp, player: Player, song: Download?, close: () -> Unit,
    importLyrics: () -> Unit, importError: String?) {
    val id = song?.id.orEmpty()
    val cached by remember(id) { if (id.isEmpty()) flowOf(null) else app.lyrics.dao.observe(id) }
        .collectAsStateWithLifecycle(null)
    val work by remember(id) { WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow("lyrics-$id") }
        .collectAsStateWithLifecycle(emptyList())
    val loading = cached == null && work.any { !it.state.isFinished }
    val reading = rememberPlayback(player)
    val lines = remember(id, song?.lyrics, cached?.text) { parseLyrics(song?.lyrics ?: cached?.text.orEmpty()) }
    val synchronized = lines.isNotEmpty() && lines.all { it.time != null }
    val active = if (synchronized) lines.indexOfLast { it.time!! <= reading.position } else -1
    var expanded by rememberSaveable { mutableStateOf(false) }
    var live by rememberSaveable(id) { mutableStateOf(true) }
    val list = rememberLazyListState()
    val dragging by list.interactionSource.collectIsDraggedAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(id) { if (id.isNotEmpty()) app.lyrics.enqueue(id) }
    LaunchedEffect(dragging) { if (dragging) live = false }
    LaunchedEffect(id, active, live, expanded, lines) {
        if (live && active >= 0) list.animateScrollToItem(active, -list.layoutInfo.viewportSize.height / 3)
    }
    val content: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Lyrics", style = MaterialTheme.typography.titleLarge)
                    Text(cached?.title ?: song?.localTitle ?: song?.title ?: "Audio",
                        maxLines = 1, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton({ expanded = !expanded }) {
                    Icon(if (expanded) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowUp,
                        if (expanded) "Collapse lyrics" else "Expand lyrics")
                }
                IconButton(close) { Icon(Icons.Outlined.Close, "Close lyrics") }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxSize(), state = list,
                    contentPadding = PaddingValues(top = 48.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    itemsIndexed(lines, key = { index, _ -> "$id-$index" }) { index, line ->
                        val color by animateColorAsState(when {
                            !synchronized -> MaterialTheme.colorScheme.onSurface
                            index == active -> MaterialTheme.colorScheme.primary
                            index < active -> MaterialTheme.colorScheme.onSurface.copy(alpha = .40f)
                            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = .65f)
                        }, label = "Lyric emphasis")
                        val styled = buildAnnotatedString {
                            append(line.text.ifBlank { "♪" })
                            if (index == active) for (word in line.words) {
                                if (word.time <= reading.position) addStyle(SpanStyle(fontWeight = FontWeight.Bold), word.start, word.end)
                            }
                        }
                        Text(styled, Modifier.fillMaxWidth().clickable(enabled = line.time != null && player.isCurrentMediaItemSeekable) {
                            player.seekTo(requireNotNull(line.time))
                            live = true
                            scope.launch { list.animateScrollToItem(index, -list.layoutInfo.viewportSize.height / 3) }
                        }, color = color, style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold)
                    }
                }
                if (lines.isEmpty()) Column(Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(if (loading) "Finding lyrics…" else "Lyrics unavailable",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!live && synchronized) FilledTonalButton({ live = true }, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) {
                    Text("Resume Live")
                }
            }
            if (!synchronized && lines.isNotEmpty()) Text("Plain lyrics", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            CompactSeek(reading, player)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(importLyrics, enabled = song != null) { Text("Import LRC") }
                Spacer(Modifier.weight(1f))
                IconButton({ player.seekToPrevious() }) { Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_media_prev), "Previous track") }
                FilledIconButton({ if (player.playWhenReady) player.pause() else player.play() }) {
                    Icon(androidx.compose.ui.res.painterResource(if (reading.playing) R.drawable.ic_media_pause else R.drawable.ic_media_play),
                        if (reading.playing) "Pause" else "Play")
                }
                IconButton({ player.seekToNextMediaItem() }, enabled = player.hasNextMediaItem()) {
                    Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_media_next), "Next track")
                }
            }
            cached?.providerId?.let { Text("Lyrics from LRCLIB", style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(bottom = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            importError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (expanded) Dialog(close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) { Box(Modifier.safeDrawingPadding()) { content() } }
    } else ModalBottomSheet(close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Box(Modifier.fillMaxWidth().height((LocalConfiguration.current.screenHeightDp * .78f).dp).navigationBarsPadding()) { content() }
    }
}
