package com.codetic.saoreo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val defaultBubble = Color(0xffc9ee65)
internal val chatCharcoal = Color(0xff111214)
private val bubbleColors = listOf(defaultBubble, Color(0xff94d9eb), Color(0xffd5bbff), Color(0xfff9b88d), Color(0xfff0acc7))
private val wallpaperColors = listOf(chatCharcoal, Color(0xff202e22), Color(0xff202f42), Color(0xff36213a), Color(0xff483328))

internal enum class ChatMode { System, Light, Dark }
internal enum class BubbleStyle(val radius: Int) { Rounded(18), Compact(8), Pill(26) }
internal data class ChatThemeOption(val mode: ChatMode = ChatMode.Dark, val bubble: Int = defaultBubble.toArgb(),
    val shape: BubbleStyle = BubbleStyle.Rounded)
internal data class ChatWallpaperOption(val preset: Int = 0, val tint: Int = 0, val brightness: Float = 1f,
    val blur: Float = 0f)

internal class PairedAppearance(private val context: Context) {
    private val settings = context.getSharedPreferences("paired-chat-appearance", Context.MODE_PRIVATE)
    private val themeState = MutableStateFlow(ChatThemeOption(
        ChatMode.entries.firstOrNull { it.name == settings.getString("mode", null) } ?: ChatMode.Dark,
        settings.getInt("bubble", defaultBubble.toArgb()),
        BubbleStyle.entries.firstOrNull { it.name == settings.getString("shape", null) } ?: BubbleStyle.Rounded,
    ))
    val theme = themeState.asStateFlow()
    private val wallpaperState = MutableStateFlow(ChatWallpaperOption(settings.getInt("preset", 0),
        settings.getInt("tint", 0), settings.getFloat("brightness", 1f), settings.getFloat("blur", 0f)))
    val wallpaper = wallpaperState.asStateFlow()
    private val picture = File(context.filesDir, "chat-wallpaper.jpg")
    val stagedPicture = File(context.filesDir, "chat-wallpaper-draft.jpg")
    val pictureSource: File get() = picture

    fun applyTheme(option: ChatThemeOption) {
        settings.edit().putString("mode", option.mode.name).putInt("bubble", option.bubble)
            .putString("shape", option.shape.name).apply()
        themeState.value = option
    }

    private val photoState = MutableStateFlow(0)
    val photoRevision = photoState.asStateFlow()
    fun partnerPhoto(conversation: String?): File? = conversation?.takeIf { it.matches(Regex("[a-f0-9]{32}")) }
        ?.let { File(context.filesDir, "chat-partner-$it.jpg") }

    fun myPhoto(conversation: String?): File? = conversation?.takeIf { it.matches(Regex("[a-f0-9]{32}")) }
        ?.let { File(context.filesDir, "chat-my-photo-$it.jpg") }

    suspend fun encodeProfilePhoto(uri: Uri): String = withContext(Dispatchers.IO) {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val dimensions = info.size
            val scale = minOf(1f, 192f / maxOf(dimensions.width, dimensions.height))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize((dimensions.width * scale).toInt().coerceAtLeast(1),
                (dimensions.height * scale).toInt().coerceAtLeast(1))
        }
        try {
            val output = java.io.ByteArrayOutputStream()
            for (quality in listOf(82, 68, 52, 36, 22, 10)) {
                output.reset()
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output))
                if (output.size() <= 6000) return@withContext java.util.Base64.getEncoder().encodeToString(output.toByteArray())
            }
            throw java.io.IOException("Couldn’t fit this image as a profile photo.")
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun applyProfileChoices(choices: PairedPhotoChoices) = withContext(Dispatchers.IO) {
        val partnerChanged = applyProfileChoice(partnerPhoto(choices.conversation), choices.partnerPhoto)
        val ownChanged = applyProfileChoice(myPhoto(choices.conversation), choices.myPhoto)
        if (partnerChanged || ownChanged) photoState.value += 1
    }

    private fun applyProfileChoice(file: File?, choice: String?): Boolean {
        if (file == null || choice == null) return false
        if (choice.isEmpty()) return file.delete()
        val bytes = runCatching { java.util.Base64.getDecoder().decode(choice) }.getOrNull() ?: return false
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outMimeType != "image/jpeg" || bounds.outWidth !in 1..192 || bounds.outHeight !in 1..192) return false
        if (file.isFile && file.length() == bytes.size.toLong() && file.readBytes().contentEquals(bytes)) return false
        val output = android.util.AtomicFile(file)
        val stream = output.startWrite()
        try {
            stream.write(bytes)
            output.finishWrite(stream)
        } catch (failure: Throwable) {
            output.failWrite(stream)
            throw failure
        }
        return true
    }

    suspend fun stageWallpaper(uri: Uri) = withContext(Dispatchers.IO) {
        writeImage(uri, stagedPicture, 1600)
    }

    private fun writeImage(uri: Uri, destination: File, maximum: Int) {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val size = info.size
            val scale = minOf(1f, maximum.toFloat() / maxOf(size.width, size.height))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize((size.width * scale).toInt().coerceAtLeast(1), (size.height * scale).toInt().coerceAtLeast(1))
        }
        try {
            FileOutputStream(destination).use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)); it.fd.sync() }
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun applyWallpaper(option: ChatWallpaperOption, staged: Boolean) = withContext(Dispatchers.IO) {
        if (option.preset == -1 && staged) check(stagedPicture.renameTo(picture)) { "Couldn’t save the wallpaper." }
        settings.edit().putInt("preset", option.preset).putInt("tint", option.tint)
            .putFloat("brightness", option.brightness).putFloat("blur", option.blur).apply()
        wallpaperState.value = option
        if (option.preset != -1) picture.delete()
    }
}

internal val LocalPairedTheme = compositionLocalOf { ChatThemeOption() }
internal val LocalPairedDark = compositionLocalOf { true }

@Composable
internal fun PairedConversationTheme(theme: ChatThemeOption, content: @Composable () -> Unit) {
    val dark = when (theme.mode) {
        ChatMode.System -> isSystemInDarkTheme()
        ChatMode.Dark -> true
        ChatMode.Light -> false
    }
    val scheme = if (dark) darkColorScheme(primary = defaultBubble, onPrimary = Color(0xff141a0a),
        background = chatCharcoal, surface = chatCharcoal, surfaceVariant = Color(0xff222328),
        onSurface = Color(0xfff5f5f2), onSurfaceVariant = Color(0xffc2c3cb))
    else lightColorScheme(primary = Color(0xff3e5a13), background = Color(0xfff8f9f3), surface = Color(0xfff8f9f3),
        surfaceVariant = Color(0xffe6e9de), onSurface = Color(0xff191d13), onSurfaceVariant = Color(0xff484c42))
    CompositionLocalProvider(LocalPairedTheme provides theme, LocalPairedDark provides dark) {
        MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, content = content)
    }
}

@Composable
internal fun ChatWallpaper(option: ChatWallpaperOption, picture: File, modifier: Modifier = Modifier) {
    val base = if (option.preset == 0) MaterialTheme.colorScheme.background
        else wallpaperColors.getOrElse(option.preset.coerceIn(1, wallpaperColors.lastIndex)) { chatCharcoal }
    Box(modifier.background(base)) {
        if (option.preset == -1) {
            AsyncImage(model = picture, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(option.blur.dp))
        } else if (option.preset >= 10) {
            val patternColor = wallpaperColors[(option.preset - 10).coerceIn(1, wallpaperColors.lastIndex)]
            Canvas(Modifier.fillMaxSize().background(patternColor).blur(option.blur.dp)) {
                val step = 34.dp.toPx()
                val radius = if (option.preset % 2 == 0) 1.8.dp.toPx() else 8.dp.toPx()
                for (y in 0..(size.height / step).toInt()) {
                    for (x in 0..(size.width / step).toInt()) {
                        drawCircle(Color.White.copy(alpha = 0.055f), radius, Offset(x * step + y % 2 * step / 2, y * step))
                    }
                }
            }
        }
        if (option.tint != 0) Box(Modifier.fillMaxSize().background(Color(option.tint).copy(alpha = 0.26f)))
        val overlay = if (option.brightness <= 1f) Color.Black.copy(alpha = 1f - option.brightness)
            else Color.White.copy(alpha = (option.brightness - 1f) * 0.45f)
        Box(Modifier.fillMaxSize().background(overlay))
    }
}

@Composable
private fun ChatStylePreview(wallpaper: ChatWallpaperOption, source: File, modifier: Modifier = Modifier) {
    val theme = LocalPairedTheme.current
    val bubble = Color(theme.bubble)
    Box(modifier.clip(RoundedCornerShape(18.dp))) {
        ChatWallpaper(wallpaper, source, Modifier.matchParentSize())
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = RoundedCornerShape(theme.shape.radius.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Text("Ready to watch?", Modifier.padding(12.dp))
            }
            Surface(Modifier.align(Alignment.End), shape = RoundedCornerShape(theme.shape.radius.dp), color = bubble,
                contentColor = if (bubble.luminance() > 0.4f) Color(0xff11160b) else Color.White) {
                Text("I’m ready. 💚", Modifier.padding(12.dp))
            }
        }
    }
}

@Composable
internal fun ChatThemePage(appearance: PairedAppearance, back: () -> Unit) {
    val theme by appearance.theme.collectAsState()
    val wallpaper by appearance.wallpaper.collectAsState()
    var custom by remember { mutableStateOf(false) }
    var hex by remember { mutableStateOf("") }
    PairedConversationTheme(theme) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PairedPageHeader("Chat theme", back)
            ChatStylePreview(wallpaper, appearance.pictureSource, Modifier.fillMaxWidth())
            Text("Message color", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (color in bubbleColors) ColorChoice(color, "Bubble color", theme.bubble == color.toArgb()) {
                    appearance.applyTheme(theme.copy(bubble = color.toArgb()))
                }
            }
            TextButton({
                hex = "%06X".format(theme.bubble and 0xffffff)
                custom = true
            }) { Text("Custom color") }
            Text("Bubble style", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                for (shape in BubbleStyle.entries) FilterChip(theme.shape == shape, { appearance.applyTheme(theme.copy(shape = shape)) }, { Text(shape.name) })
            }
            Text("Appearance", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (mode in ChatMode.entries) FilterChip(theme.mode == mode, { appearance.applyTheme(theme.copy(mode = mode)) }, { Text(mode.name) })
            }
            TextButton({ appearance.applyTheme(ChatThemeOption()) }) { Text("Reset to default") }
        }
        if (custom) {
            val value = hex.removePrefix("#").takeIf { it.length == 6 }?.toIntOrNull(16)
            AlertDialog(onDismissRequest = { custom = false }, title = { Text("Custom color") },
                text = { OutlinedTextField(hex, { hex = it.take(7) }, label = { Text("Hex color") }, singleLine = true) },
                confirmButton = { TextButton({
                    value?.let { appearance.applyTheme(theme.copy(bubble = 0xff000000.toInt() or it)) }
                    custom = false
                }, enabled = value != null) { Text("Apply") } },
                dismissButton = { TextButton({ custom = false }) { Text("Cancel") } })
        }
    }
}

@Composable
internal fun ChatWallpaperPage(appearance: PairedAppearance, back: () -> Unit) {
    val active by appearance.wallpaper.collectAsState()
    var draft by remember { mutableStateOf(active) }
    var staged by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                appearance.stageWallpaper(uri)
                staged = true
                draft = draft.copy(preset = -1)
                error = null
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { error = "Couldn’t read this image." }
            finally { busy = false }
        }
    }
    val source = if (staged) appearance.stagedPicture else appearance.pictureSource
    androidx.activity.compose.BackHandler(preview) { preview = false }
    if (preview) {
        Box(Modifier.fillMaxSize()) {
            ChatStylePreview(draft, source, Modifier.fillMaxSize())
            Button({ preview = false }, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(18.dp)) {
                Text("Close preview")
            }
        }
        return
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PairedPageHeader("Chat wallpaper", back)
        ChatStylePreview(draft, source, Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ picker.launch(arrayOf("image/*")) }, enabled = !busy) { Text("Choose from gallery") }
            TextButton({ preview = true }) { Text("Preview") }
        }
        Text("Presets", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Default" to 0, "Dots" to 12, "Pattern" to 13).forEach { (label, id) ->
                FilterChip(draft.preset == id, { draft = draft.copy(preset = id) }, { Text(label) })
            }
        }
        Text("Solid colors", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            wallpaperColors.forEachIndexed { index, color ->
                ColorChoice(color, "Wallpaper color ${index + 1}", draft.preset == index) { draft = draft.copy(preset = index) }
            }
        }
        Text("Tint", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton({ draft = draft.copy(tint = 0) }) { Text("None") }
            for (color in bubbleColors.take(4)) ColorChoice(color, "Wallpaper tint", draft.tint == color.toArgb()) {
                draft = draft.copy(tint = color.toArgb())
            }
        }
        Text("Brightness")
        Slider(draft.brightness, { draft = draft.copy(brightness = it) }, valueRange = 0.25f..1.35f)
        Text("Blur")
        Slider(draft.blur, { draft = draft.copy(blur = it) }, valueRange = 0f..28f)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button({
                scope.launch {
                    busy = true
                    try { appearance.applyWallpaper(draft, staged); back() }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { error = "Couldn’t save the wallpaper." }
                    finally { busy = false }
                }
            }, enabled = !busy) { Text("Apply") }
            TextButton({ draft = ChatWallpaperOption() }, enabled = !busy) { Text("Reset") }
        }
    }
}

@Composable
private fun ColorChoice(color: Color, label: String, selected: Boolean, choose: () -> Unit) {
    Surface(onClick = choose, modifier = Modifier.size(38.dp).semantics { contentDescription = label }, shape = CircleShape, color = color,
        border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface) else null) {
        Box(contentAlignment = Alignment.Center) {
            if (selected) Text("✓", color = if (color.luminance() > 0.4f) Color.Black else Color.White,
                modifier = Modifier.padding(4.dp))
        }
    }
}
