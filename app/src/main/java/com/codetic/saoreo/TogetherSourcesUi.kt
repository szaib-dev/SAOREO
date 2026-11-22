package com.codetic.saoreo

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun SourceOption(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(subtitle) },
            trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) },
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        )
    }
}

@Composable
internal fun TogetherSeekBar(position: Long, duration: Long, enabled: Boolean, onSeek: (Long) -> Unit) {
    var dragged by remember { mutableStateOf<Float?>(null) }
    if (duration <= 0) return
    val fraction = (position.toFloat() / duration).coerceIn(0f, 1f)
    Slider(
        value = dragged ?: fraction,
        onValueChange = { dragged = it },
        onValueChangeFinished = {
            if (enabled) dragged?.let { onSeek((it * duration).toLong()) }
            dragged = null
        },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun TogetherYouTubeBrowser(onDismiss: () -> Unit, onChoose: (id: String, live: Boolean) -> Boolean) {
    val choose by rememberUpdatedState(onChoose)
    var browser by remember { mutableStateOf<WebView?>(null) }
    var selected by remember { mutableStateOf(false) }
    fun select(url: String): Boolean {
        if (selected) return true
        val id = TogetherSources.youtube(url) ?: return false
        selected = choose(id, Uri.parse(url).path?.startsWith("/live/") == true)
        return true
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ browser?.takeIf { it.canGoBack() }?.goBack() ?: onDismiss() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Browser back")
                    }
                    Text("Browse YouTube", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onDismiss) { Icon(Icons.Outlined.Close, "Close YouTube browser") }
                }
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.mediaPlaybackRequiresUserGesture = true
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    if (!request.isForMainFrame) return false
                                    val uri = request.url
                                    if (uri.scheme != "https") return true
                                    if (select(uri.toString())) return true
                                    return uri.host !in setOf("m.youtube.com", "www.youtube.com", "youtube.com")
                                }
                                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                                    // YouTube can change routes without a main-frame navigation.
                                    if (select(url)) view.stopLoading()
                                }
                            }
                            browser = this
                            loadUrl("https://m.youtube.com/")
                        }
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    onRelease = { it.stopLoading(); it.destroy() },
                )
            }
        }
    }
}
