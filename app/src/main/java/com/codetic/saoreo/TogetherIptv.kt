package com.codetic.saoreo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.util.concurrent.TimeUnit

internal data class IptvProgram(val title: String, val group: String, val url: String, val live: Boolean) {
    fun media() = TogetherMedia("", "stream", url, title.take(150), 0, live)
}

private fun iptvUrl(value: String): URI? = runCatching { URI(value) }.getOrNull()?.takeIf {
    it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null && value.length <= 4096
}

internal fun parseIptvPlaylist(input: InputStream, base: URI? = null): List<IptvProgram> {
    val maxBytes = 8 * 1024 * 1024
    val bytes = input.readNBytes(maxBytes + 1)
    require(bytes.size <= maxBytes) { "The playlist is larger than 8 MB." }
    val lines = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").lineSequence()
    val namePattern = Regex("tvg-name=\"([^\"]+)\"")
    val groupPattern = Regex("group-title=\"([^\"]+)\"")
    val commaOutsideQuotes = Regex(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")
    var metadata = ""
    return buildList {
        for (raw in lines) {
            val line = raw.trim()
            if (line.startsWith("#EXT-X-")) error("This is a single HLS stream. Open it with Direct stream.")
            if (line.startsWith("#EXTINF:")) { metadata = line.substringAfter(':').take(4096); continue }
            if (line.isBlank() || line.startsWith("#")) continue
            val details = metadata
            metadata = ""
            val url = runCatching { base?.resolve(line)?.toString() ?: line }.getOrNull()?.let(::iptvUrl) ?: continue
            val separator = commaOutsideQuotes.find(details)?.range?.first ?: -1
            val title = (if (separator >= 0) details.substring(separator + 1).trim() else "")
                .ifBlank { namePattern.find(details)?.groupValues?.get(1).orEmpty() }
                .ifBlank { "Program ${size + 1}" }
            val group = groupPattern.find(details)?.groupValues?.get(1).orEmpty()
            val path = url.path.orEmpty().lowercase()
            val vod = path.startsWith("/movie/") || path.startsWith("/series/") ||
                path.substringAfterLast('.').let { it in setOf("mp4", "mkv", "webm", "mov") } ||
                group.contains("movie", true) || group.contains("series", true) || group.contains("vod", true)
            val duration = details.substringBefore(' ').substringBefore(',').toDoubleOrNull()
            add(IptvProgram(title.take(150), group.take(100), url.toString(), !vod && (path.startsWith("/live/") || duration == -1.0)))
            if (size >= 30_000) break
        }
    }
}

private val playlistHttp = OkHttpClient.Builder().followSslRedirects(false)
    .callTimeout(25, TimeUnit.SECONDS).build()

private class SavedIptvPlaylist(context: Context) {
    private val prefs = context.getSharedPreferences("iptv-playlist", Context.MODE_PRIVATE)
    private val alias = "saoreo-iptv-playlist"

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun read(): String? {
        val parts = prefs.getString("source", null)?.split(':', limit = 2) ?: return null
        val key = keyStore().getKey(alias, null) as? SecretKey ?: error("Playlist key unavailable")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        return cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    fun write(source: String) {
        val key = keyStore().getKey(alias, null) as? SecretKey ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
                generateKey()
            }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val ciphertext = Base64.encodeToString(cipher.doFinal(source.toByteArray()), Base64.NO_WRAP)
        check(prefs.edit().putString("source", "$iv:$ciphertext").commit())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IptvPlaylistPicker(onClose: () -> Unit, onSelect: (TogetherMedia) -> Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf(emptyList<IptvProgram>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<IptvProgram?>(null) }
    val saved = remember(context) { SavedIptvPlaylist(context.applicationContext) }
    fun load(source: String, rememberSource: Boolean = false) {
        if (loading) return
        error = null
        loading = true
        scope.launch {
            try {
                val (programs, savedSuccessfully) = withContext(Dispatchers.IO) {
                    val uri = Uri.parse(source)
                    val programs = if (uri.scheme == "content") {
                        context.contentResolver.openInputStream(uri)?.use { parseIptvPlaylist(it) }.orEmpty()
                    } else {
                        val address = iptvUrl(source) ?: error("Enter an HTTPS playlist URL.")
                        playlistHttp.newCall(Request.Builder().url(address.toString()).build()).execute().use { response ->
                            check(response.isSuccessful) { "The playlist provider returned HTTP ${response.code}." }
                            response.body?.byteStream()?.use { parseIptvPlaylist(it, response.request.url.toUri()) }.orEmpty()
                        }
                    }
                    val stored = !rememberSource || programs.isEmpty() || runCatching {
                        if (uri.scheme == "content") {
                            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        saved.write(source)
                    }.isSuccess
                    programs to stored
                }
                if (programs.isEmpty()) error = "No supported HTTPS channels or movies found."
                else {
                    entries = programs
                    query = ""
                    if (!savedSuccessfully) error = "Playlist opened, but SAOREO couldn't save it for next time."
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (problem: IllegalStateException) {
                error = problem.message
            } catch (problem: IllegalArgumentException) {
                error = problem.message
            } catch (_: Exception) {
                error = "Couldn't open the playlist. Check the link or choose a file."
            } finally { loading = false }
        }
    }
    LaunchedEffect(saved) {
        val previous = withContext(Dispatchers.IO) { runCatching { saved.read() } }
        previous.onSuccess { source ->
            if (source != null && entries.isEmpty() && url.isBlank() && !loading) {
                if (iptvUrl(source) != null) url = source
                load(source)
            }
        }.onFailure { error = "Couldn't restore the saved playlist. Open it again to save it." }
    }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) result.data?.data?.let { uri ->
            load(uri.toString(), rememberSource = true)
        }
    }
    val results = remember(entries, query) {
        val term = query.trim()
        if (term.isEmpty()) entries else entries.filter { it.title.contains(term, true) || it.group.contains(term, true) }
    }
    ModalBottomSheet(onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).imePadding().padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("M3U IPTV", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(url, { url = it.take(4096) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("HTTPS playlist URL") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({
                    val address = iptvUrl(url.trim())
                    if (address == null) error = "Enter an HTTPS playlist URL."
                    else load(address.toString(), rememberSource = true)
                }, enabled = !loading && url.isNotBlank()) { Text("Load playlist") }
                OutlinedButton({
                    file.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION).setType("*/*"))
                }, enabled = !loading) { Text("Open M3U file") }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (entries.isNotEmpty()) {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Find a channel or movie") })
                if (results.isEmpty()) Text("No matching programs.")
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    items(results) { program ->
                        TextButton({ selected = program }, Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(program.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (program.group.isNotBlank()) Text(program.group, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    selected?.let { program ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(program.title) },
            text = { Text("Together shares this program’s stream link with the room. Each phone connects to the provider.") },
            confirmButton = { TextButton({
                selected = null
                if (!onSelect(program.media())) error = "Reconnect to Together and select the program again."
            }) { Text("Select in Together") } },
            dismissButton = { TextButton({ selected = null }) { Text("Cancel") } })
    }
}
