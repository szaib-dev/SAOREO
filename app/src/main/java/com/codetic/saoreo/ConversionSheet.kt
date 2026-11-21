package com.codetic.saoreo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal fun localDocument(mime: String): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
    .addCategory(Intent.CATEGORY_OPENABLE).setType(mime)
    .putExtra(Intent.EXTRA_LOCAL_ONLY, true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversionSheet(app: SaoreoApp, uri: Uri, title: String?, imported: Boolean, close: () -> Unit) {
    val context = LocalContext.current
    var rate by remember(uri) { mutableIntStateOf(192) }
    var error by remember(uri) { mutableStateOf<String?>(null) }
    val input by produceState<ConversionInput?>(null, uri) {
        try { value = inspectVideo(app, uri, title, imported) }
        catch (failure: CancellationException) { throw failure }
        catch (_: SecurityException) { error = "Android denied access. Choose the video again." }
        catch (_: java.io.FileNotFoundException) { error = "The video is missing or cannot be opened." }
        catch (failure: java.io.IOException) { error = failure.message ?: "Couldn’t inspect this video." }
        catch (_: Exception) { error = "Couldn’t inspect this video." }
    }
    val working by app.converter.status.collectAsStateWithLifecycle()
    fun start(source: ConversionInput) {
        if (app.converter.start(source, rate)) close()
        else error = "Couldn’t start. Check whether another conversion is running."
    }
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        input?.let(::start)
    }
    ModalBottomSheet(onDismissRequest = close) {
        Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Text("Convert to MP3", style = MaterialTheme.typography.headlineSmall)
            val source = input
            if (source != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Thumb(videoThumb(context, uri), Modifier.size(72.dp, 58.dp).clip(RoundedCornerShape(10.dp)))
                    Column(Modifier.weight(1f)) {
                        Text(source.filename, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOf(
                            source.durationMs.takeIf { it > 0 }?.let(::playbackTime) ?: "Unknown duration",
                            source.bytes?.let(::sizeLabel) ?: "Unknown size",
                        ).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text("MP3 audio bitrate", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    mp3Rates.forEach { option ->
                        FilterChip(rate == option, { rate = option }, { Text("${option} kbps") })
                    }
                }
                val estimate = estimatedMp3Bytes(source.durationMs, rate)
                if (estimate != null) Text("Estimated output · ${sizeLabel(estimate)}", style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    val preferences = app.getSharedPreferences("conversion_ui", android.content.Context.MODE_PRIVATE)
                    if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                        !preferences.getBoolean("asked_notifications", false)) {
                        preferences.edit().putBoolean("asked_notifications", true).apply()
                        requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else start(source)
                }, enabled = working?.active != true, modifier = Modifier.fillMaxWidth()) {
                    Text(if (working?.active == true) "Another conversion is running" else "Convert")
                }
            } else if (error == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Reading video information…")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
internal fun ConversionProgress(app: SaoreoApp, modifier: Modifier = Modifier, overlay: Boolean = false) {
    val current by app.converter.status.collectAsStateWithLifecycle()
    val status = current ?: return
    val elapsed by produceState(0L, status.started, status.ended) {
        do {
            value = (status.ended ?: SystemClock.elapsedRealtime()) - status.started
            if (status.ended != null) break
            delay(1000)
        } while (true)
    }
    var cancelling by remember(status.started) { mutableStateOf(false) }
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).widthIn(max = 520.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(status.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        val stage = if (cancelling && status.active) "Cancelling…" else status.stage
                        val percentage = status.percent?.let { " · $it%" }.orEmpty()
                        Text("$stage$percentage · ${playbackTime(elapsed)}",
                            style = MaterialTheme.typography.labelMedium)
                    }
                    if (status.active) TextButton(enabled = !cancelling, onClick = {
                        cancelling = true
                        app.converter.cancel()
                    }) { Text("Cancel") }
                    else TextButton(onClick = { app.converter.status.value = null }) { Text("Dismiss") }
                }
                if (status.active) {
                    val percent = status.percent
                    if (percent == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 3.dp))
                    else LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp))
                }
            }
        }
    }
    if (overlay && !status.active) LaunchedEffect(status.ended) {
        delay(6500)
        if (app.converter.status.value === status) app.converter.status.value = null
    }
}
