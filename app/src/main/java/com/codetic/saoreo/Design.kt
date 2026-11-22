package com.codetic.saoreo

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.sin

internal val Lime = Color(0xffd5ef72)
internal val Lavender = Color(0xffceb0f6)
internal val Ink = Color(0xff19191e)

@Composable
internal fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier, style = MaterialTheme.typography.labelSmall,
        letterSpacing = 2.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun PageHeading(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold,
            letterSpacing = (-1).sp)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
internal fun SectionHeading(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (action != null) TextButton(onAction) { Text(action) }
    }
}

/** Original vector artwork also gives imported tracks without a cover an intentional identity. */
@Composable
internal fun RecordArt(modifier: Modifier = Modifier, seed: Int = 0,
                       shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(22.dp)) {
    val palette = listOf(Lavender, Lime, Color(0xffecb8cb), Color(0xffa8d6cf))
    val color = palette[Math.floorMod(seed, palette.size)]
    Canvas(modifier.clip(shape).background(Brush.linearGradient(listOf(color, color.copy(alpha = .55f))))) {
        val radius = size.minDimension * .48f
        val center = Offset(size.width * .58f, size.height * .53f)
        drawCircle(Ink, radius, center)
        for (i in 1..12) drawCircle(color.copy(alpha = .10f), radius * (.28f + i * .05f), center, style = Stroke(1.dp.toPx()))
        drawCircle(color, radius * .25f, center)
        drawCircle(Ink, radius * .045f, center)
        drawLine(Color.White.copy(alpha = .3f), Offset(size.width * .8f, 0f), center, 2.dp.toPx())
    }
}

@Composable
internal fun WaveArt(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        for (i in 0..37) {
            val x = size.width * i / 37
            val wave = (.18f + .72f * kotlin.math.abs(sin(i * .41))).toFloat()
            val height = size.height * wave
            drawLine(Ink.copy(alpha = if (i % 3 == 0) .9f else .35f),
                Offset(x, (size.height - height) / 2), Offset(x, (size.height + height) / 2),
                3.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

@Composable
internal fun CollectionCard(title: String, subtitle: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(modifier.clickable(onClick = onClick), shape = RoundedCornerShape(24.dp), color = color) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(if (title == "Liked songs") Icons.Outlined.FavoriteBorder else Icons.Outlined.PlayArrow,
                null, Modifier.size(28.dp), tint = Ink)
            Spacer(Modifier.height(13.dp))
            Text(title, color = Ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Ink.copy(alpha = .7f), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private enum class Destination(val label: String, @DrawableRes val icon: Int) {
    HOME("Home", R.drawable.ic_nav_home),
    MUSIC("Music", R.drawable.ic_nav_music),
    DOWNLOADS("Downloads", R.drawable.ic_nav_downloads),
    STUDIO("Studio", R.drawable.ic_nav_studio),
}

@Composable
private fun RowScope.ShellTab(label: String, @DrawableRes icon: Int, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val indicator by animateColorAsState(if (selected) scheme.primaryContainer else Color.Transparent, label = "Tab background")
    val tint by animateColorAsState(if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant, label = "Tab icon")
    val indicatorWidth by animateDpAsState(if (selected) 50.dp else 36.dp, tween(220), label = "Tab selection")
    Column(Modifier.weight(1f).heightIn(min = 64.dp).clip(RoundedCornerShape(15.dp))
        .selectable(selected, role = Role.Tab, onClick = onClick).padding(top = 5.dp, bottom = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
        Box(Modifier.widthIn(max = indicatorWidth).fillMaxWidth(.9f).height(31.dp).background(indicator, CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, Modifier.size(23.dp), tint)
        }
        Text(label, color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SaoreoShell(app: SaoreoApp, settings: Settings, shortcutEvents: Int, downloadEvents: Int, transcriptEvents: Int) {
    var destination by rememberSaveable { mutableStateOf(Destination.HOME) }
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf(false) }
    var activity by rememberSaveable { mutableStateOf(false) }
    var importAction by rememberSaveable { mutableIntStateOf(0) }
    var musicSection by rememberSaveable { mutableStateOf("Songs") }
    var overflow by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val holder = rememberSaveableStateHolder()
    val player = rememberMusicController(app).first
    LaunchedEffect(shortcutEvents) { if (shortcutEvents > 0) { page = null; adding = true } }
    LaunchedEffect(downloadEvents) { if (downloadEvents > 0) { page = null; destination = Destination.DOWNLOADS; activity = true } }
    LaunchedEffect(transcriptEvents) { if (transcriptEvents > 0) page = "Transcript" }
    BackHandler(page != null || search || activity) {
        when { page != null -> page = null; search -> search = false; else -> activity = false }
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (!keyboardVisible) Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (page == null) Image(painterResource(R.drawable.mark), null, Modifier.size(32.dp))
                Column(Modifier.weight(1f).padding(start = if (page == null) 10.dp else 0.dp)) {
                    Text(page ?: if (destination == Destination.HOME) "SAOREO" else destination.label,
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                if (page == null && destination == Destination.DOWNLOADS) {
                    IconButton({ search = !search }) { Icon(painterResource(R.drawable.ic_ui_search), "Search downloads") }
                    Box {
                        IconButton({ overflow = true }) { Icon(Icons.Outlined.MoreVert, "Download options") }
                        DropdownMenu(overflow, { overflow = false }) {
                            DropdownMenuItem({ Text(if (activity) "Saved media" else "Download activity") }, { activity = !activity; overflow = false })
                            DropdownMenuItem({ Text("Video library") }, { page = "Videos"; overflow = false })
                        }
                    }
                }
                if (page != "Settings") IconButton({ page = "Settings" }) { Icon(painterResource(R.drawable.ic_ui_settings), "Settings", Modifier.size(21.dp)) }
            }
        }, bottomBar = {
            if (!keyboardVisible) Column(Modifier.fillMaxWidth().animateContentSize()) {
                if (page == null) MusicMiniPlayer(app, player) { context.startActivity(MusicActivity.open(context, nowPlaying = true)) }
                Surface(shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
                    Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars
                        .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))) {
                        if (page == null) Row(Modifier.fillMaxWidth().selectableGroup().padding(horizontal = 5.dp, vertical = 3.dp)) {
                            Destination.entries.forEach { item ->
                                ShellTab(item.label, item.icon, destination == item) {
                                    if (destination != item) { destination = item; search = false; activity = false }
                                }
                                if (item == Destination.MUSIC) ShellTab("Together", R.drawable.ic_nav_together, false) {
                                    context.startActivity(TogetherGames.roomIntent(context))
                                }
                            }
                        } else Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                            TextButton({ page = null }) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Back")
                            }
                        }
                    }
                }
            }
        }) { inner ->
        AnimatedContent(targetState = page?.let { "page:$it" } ?: "tab:${destination.name}",
            modifier = Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner).imePadding(),
            transitionSpec = {
                val oldTab = Destination.entries.indexOfFirst { initialState == "tab:${it.name}" }
                val newTab = Destination.entries.indexOfFirst { targetState == "tab:${it.name}" }
                val forward = if (oldTab >= 0 && newTab >= 0) newTab > oldTab else newTab < 0
                val direction = if (forward) 1 else -1
                (fadeIn(tween(225, delayMillis = 25)) + slideInHorizontally(tween(270, easing = FastOutSlowInEasing)) { direction * it / 14 }) togetherWith
                    (fadeOut(tween(150)) + slideOutHorizontally(tween(230, easing = FastOutSlowInEasing)) { -direction * it / 22 })
            }, label = "Screen transition") { route ->
            val shownPage = route.takeIf { it.startsWith("page:") }?.substringAfter(':')
            val shownTab = route.takeIf { it.startsWith("tab:") }?.substringAfter(':')?.let(Destination::valueOf) ?: destination
            when (shownPage) {
                "Settings" -> SettingsScreen(app, settings, Modifier.fillMaxSize())
                "Transcript" -> holder.SaveableStateProvider("transcript") { TranscriptScreen(app, Modifier.fillMaxSize()) }
                "Convert media" -> ConversionTools(app, Modifier.fillMaxSize())
                "Videos" -> DownloadsScreen(app, settings, Modifier.fillMaxSize(), search = search, libraryVideos = true)
                "Status" -> StatusScreen(app, settings, Modifier.fillMaxSize())
                else -> when (shownTab) {
                    Destination.HOME -> HomeHub(app, settings, { destination = Destination.DOWNLOADS }, { section ->
                        musicSection = section; destination = Destination.MUSIC
                    }, { destination = Destination.DOWNLOADS; activity = true })
                    Destination.MUSIC -> holder.SaveableStateProvider("music:$musicSection") {
                        MusicLibrary(app, null, false, {}, controller = player, embedded = true, initialSection = musicSection,
                            onDownload = { adding = true })
                    }
                    Destination.DOWNLOADS -> holder.SaveableStateProvider("downloads") {
                        DownloadsScreen(app, settings, Modifier.fillMaxSize(), search, activity, importAction, onActivity = { activity = it }) { adding = true }
                    }
                    Destination.STUDIO -> StudioHub { page = it }
                }
            }
        }
    }
    if (adding) ModalBottomSheet(onDismissRequest = { adding = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()
            .padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Save from a link", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            LinkPanel(app, settings, input, onInput = { input = it }, onQueued = {
                adding = false; destination = Destination.DOWNLOADS; activity = true; input = ""
            })
        }
    }
}

@Composable
private fun HomeHub(app: SaoreoApp, settings: Settings, add: () -> Unit, music: (String) -> Unit, downloads: () -> Unit) {
    val records by app.dao.observe().collectAsStateWithLifecycle(emptyList())
    val recentVideos = records.filter { it.origin == MediaOrigin.DOWNLOADED.name && it.state == JobState.COMPLETE.name && it.mime?.startsWith("video/") == true && it.galleryUri != null }
    val states by app.collections.dao.songStates().collectAsStateWithLifecycle(emptyList())
    val playlists by app.collections.playlists.collectAsStateWithLifecycle(emptyList())
    val transfers by app.downloadNotices.progress.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val active = records.filter { it.active }
    var editingLink by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    LaunchedEffect(editingLink) { if (editingLink) scroll.animateScrollTo(0) }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LinkInput(app, settings, Modifier.fillMaxWidth(), onQueued = downloads, onEditing = { editingLink = it })
        if (!editingLink) Surface(Modifier.fillMaxWidth().clickable { context.startActivity(TogetherGames.roomIntent(context)) },
            shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(R.drawable.ic_nav_together), null, Modifier.size(28.dp))
                Column(Modifier.weight(1f)) {
                    Text("Together", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                    Text("Call or watch with your partner", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Open Together")
            }
        }
        if (!editingLink) {
        Surface(shape = RoundedCornerShape(24.dp), color = Lavender, modifier = Modifier.fillMaxWidth().clickable { music("Songs") }) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Music library", color = Ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    Text("Songs, favorites, and playlists", color = Ink.copy(alpha = .75f), style = MaterialTheme.typography.bodySmall)
                }
                WaveArt(Modifier.size(80.dp, 64.dp))
            }
        }
        if (active.isNotEmpty()) Surface(Modifier.fillMaxWidth().clickable(onClick = downloads),
            shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text("${active.size} download${if (active.size == 1) "" else "s"} in progress", style = MaterialTheme.typography.titleSmall)
                    Text(transfers[active.first().id]?.stage ?: "Getting things ready", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "View activity")
            }
        }
        SectionHeading("Your collections", "Open library") { music("Songs") }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CollectionCard("Liked songs", "${states.count { it.liked }} favorites", Lime, Modifier.weight(1f)) { music("Liked") }
            CollectionCard("Your playlists", "${playlists.size} collections", Color(0xffb9d7d2), Modifier.weight(1f)) { music("Playlists") }
        }
        SectionHeading("Recently added", if (recentVideos.isNotEmpty()) "See all" else null, add)
        if (recentVideos.isEmpty()) Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("No downloaded videos yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                TextButton(add, contentPadding = PaddingValues(0.dp)) { Text("Open Downloads"); Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) }
            }
        } else recentVideos.take(5).forEach { song ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable { playSavedMedia(context, song) }
                .padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                ThumbnailForDownload(song, Modifier.size(64.dp, 48.dp).clip(RoundedCornerShape(12.dp)))
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text("Downloaded video", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalIconButton({ playSavedMedia(context, song) }) { Icon(Icons.Outlined.PlayArrow, "Play ${song.title}") }
            }
        }
        Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StudioHub(open: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        StudioCard("Convert media", "Extract MP3 audio or turn an MP3 into an MP4 video.",
            Icons.Outlined.PlayArrow, Lavender) { open("Convert media") }
        StudioCard("Transcript", "Read, copy, and export the words that matter.",
            Icons.Outlined.ContentPaste, Lime) { open("Transcript") }
        StudioCard("Status", "Keep photos and videos from your local WhatsApp status folders.",
            Icons.Outlined.PhotoLibrary, Color(0xffb9d7d2)) { open("Status") }
        Surface(Modifier.fillMaxWidth().clickable { open("Settings") }, shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainer) {
            ListItem(headlineContent = { Text("Settings & preferences") }, leadingContent = { Icon(painterResource(R.drawable.ic_ui_settings), null) },
                trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent))
        }
    }
}

@Composable
private fun StudioCard(title: String, description: String, icon: ImageVector, accent: Color, action: () -> Unit) {
    Surface(Modifier.fillMaxWidth().clickable(onClick = action), shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).background(accent, RoundedCornerShape(17.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Ink)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null)
        }
    }
}
