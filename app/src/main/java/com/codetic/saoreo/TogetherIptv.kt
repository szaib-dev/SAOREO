package com.codetic.saoreo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.json.JSONArray
import java.security.MessageDigest
import java.io.InputStream
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal enum class IptvKind(val label: String) { LIVE("Live TV"), MOVIE("Movies"), SERIES("Series") }

internal class IptvProgram(
    val title: String, val group: String, val url: String, val kind: IptvKind,
    val logo: String? = null, val hls: Boolean = false, val seriesId: Long? = null,
    private val providerId: String? = null,
) {
    val ref: String by lazy {
        providerId ?: iptvKey(kind.name, group, title)
    }
    fun media() = TogetherMedia("", "iptv", ref, kind.label, 0, kind == IptvKind.LIVE)
}

internal fun iptvKey(vararg values: String): String {
    val hash = MessageDigest.getInstance("SHA-256")
    values.forEach { value ->
        hash.update(value.toByteArray(Charsets.UTF_8))
        hash.update(0.toByte())
    }
    val digits = "0123456789abcdef"
    return buildString(64) {
        for (byte in hash.digest()) {
            append(digits[(byte.toInt() and 0xf0) ushr 4])
            append(digits[byte.toInt() and 0x0f])
        }
    }
}

internal fun parseIptvPlaylist(input: InputStream, base: URI? = null, localManifest: String? = null): List<IptvProgram> {
    val maxBytes = 8 * 1024 * 1024
    val bytes = input.readNBytes(maxBytes + 1)
    require(bytes.size <= maxBytes) { "The playlist is larger than 8 MB." }
    val lines = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").lineSequence()
    if (lines.any { it.trimStart().startsWith("#EXT-X-") }) {
        val source = base?.toString() ?: localManifest ?: error("Open the HLS manifest as a file or URL.")
        val seekable = lines.any {
            val tag = it.trim()
            tag == "#EXT-X-ENDLIST" || tag == "#EXT-X-PLAYLIST-TYPE:VOD" || tag.startsWith("#EXT-X-STREAM-INF:")
        }
        return listOf(IptvProgram("HLS stream", "", source, if (seekable) IptvKind.MOVIE else IptvKind.LIVE,
            hls = true, providerId = base?.let { iptvKey(it.host, it.path.substringAfterLast('/')) }))
    }
    val attribute = Regex("""([\w-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    fun separator(details: String): Int {
        var quote: Char? = null
        details.forEachIndexed { index, char ->
            if (quote != null) {
                if (char == quote) quote = null
            } else when (char) {
                '"', '\'' -> quote = char
                ',' -> return index
            }
        }
        return -1
    }
    fun resolve(value: String): URI? = runCatching { base?.resolve(value)?.toString() ?: value }
        .getOrNull()?.let(::iptvUrl)
    var groupDirective = ""
    var metadata = ""
    val occurrences = mutableMapOf<String, Int>()
    return buildList {
        for (raw in lines) {
            val line = raw.trim()
            if (line.startsWith("#EXTGRP:")) groupDirective = line.substringAfter(':').trim()
            if (line.startsWith("#EXTINF:")) { metadata = line.substringAfter(':').take(4096); continue }
            if (line.isBlank() || line.startsWith("#")) continue
            val details = metadata
            metadata = ""
            val url = resolve(line) ?: continue
            val separator = separator(details)
            val attrs = attribute.findAll(if (separator >= 0) details.substring(0, separator) else details)
                .associate { it.groupValues[1].lowercase() to it.groupValues[2].ifEmpty { it.groupValues[3] } }
            val title = (if (separator >= 0) details.substring(separator + 1).trim() else "")
                .ifBlank { attrs["tvg-name"].orEmpty() }.ifBlank { "Program ${size + 1}" }
            val group = attrs["group-title"].orEmpty().ifBlank { groupDirective }
            val path = url.path.orEmpty().lowercase()
            val duration = details.substringBefore(' ').substringBefore(',').toDoubleOrNull()
            val kind = when {
                "/series/" in path || group.contains("series", true) -> IptvKind.SERIES
                "/movie/" in path || path.substringAfterLast('.') in setOf("mp4", "mkv", "webm", "mov") ||
                    group.contains("movie", true) || group.contains("vod", true) ||
                    (duration ?: 0.0) > 0.0 -> IptvKind.MOVIE
                else -> IptvKind.LIVE
            }
            val identity = iptvKey(kind.name, group, title)
            val occurrence = (occurrences[identity] ?: 0) + 1
            occurrences[identity] = occurrence
            val ref = if (occurrence == 1) identity else iptvKey(identity, occurrence.toString())
            add(IptvProgram(title.take(150), group.take(100), url.toString(), kind,
                attrs["tvg-logo"]?.let(::resolve)?.toString(), path.endsWith(".m3u8"), providerId = ref))
            groupDirective = ""
            if (size >= 30_000) break
        }
    }
}

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


internal class XtreamLogin(val name: String, private val server: URI, private val user: String, private val password: String) {
    private val providerKey = iptvKey(server.host.lowercase(), server.path.trimEnd('/'))
    private fun ref(kind: String, id: Long) = "x-$providerKey-$kind-$id"
    fun owns(ref: String) = ref.startsWith("x-$providerKey-")

    private fun endpoint() = server.toString().toHttpUrl().newBuilder()

    fun savedValue(): String = JSONObject().put("name", name).put("server", server.toString())
        .put("username", user).put("password", password).toString()

    private fun api(allowHttp: Boolean, action: String? = null, series: Long? = null): String {
        val address = endpoint().addPathSegment("player_api.php")
            .addQueryParameter("username", user).addQueryParameter("password", password)
            .apply {
                action?.let { addQueryParameter("action", it) }
                series?.let { addQueryParameter("series_id", it.toString()) }
            }
            .build().toUri()
        return IptvNetwork.open(address, allowHttp).use { response ->
            if (response.status !in 200..299) error("IPTV provider returned HTTP ${response.status}.")
            val limit = 16 * 1024 * 1024
            val bytes = response.body?.readNBytes(limit + 1) ?: error("Empty response from IPTV provider.")
            check(bytes.size <= limit) { "This provider catalog is too large." }
            bytes.toString(Charsets.UTF_8)
        }
    }

    private fun stream(path: String, id: Long, extension: String) = endpoint()
        .addPathSegment(path).addPathSegment(user).addPathSegment(password)
        .addPathSegment("$id.$extension").build().toString()

    fun catalog(allowHttp: Boolean): List<IptvProgram> = buildList {
        val account = JSONObject(api(allowHttp)).optJSONObject("user_info")
        check(account?.optInt("auth") == 1) { "The IPTV provider rejected the login." }
        val formats = account?.optJSONArray("allowed_output_formats")
        val allowed = (0 until (formats?.length() ?: 0)).map { formats?.optString(it) }
        val liveExtension = if ("ts" !in allowed && "m3u8" in allowed) "m3u8" else "ts"
        for (kind in IptvKind.entries) {
            val prefix = when (kind) {
                IptvKind.LIVE -> "live"
                IptvKind.MOVIE -> "vod"
                IptvKind.SERIES -> "series"
            }
            val categories = JSONArray(api(allowHttp, "get_${prefix}_categories")).records().associate {
                it.optString("category_id") to it.optString("category_name").take(100)
            }
            val action = if (kind == IptvKind.SERIES) "get_series" else "get_${prefix}_streams"
            for (row in JSONArray(api(allowHttp, action)).records().take(30_000)) {
                val id = row.optLong(if (kind == IptvKind.SERIES) "series_id" else "stream_id")
                if (id <= 0) continue
                val title = row.optString("name").take(150)
                val group = categories[row.optString("category_id")].orEmpty()
                val logo = row.optString("stream_icon").ifBlank { row.optString("cover") }.let(::iptvUrl)?.toString()
                when (kind) {
                    IptvKind.LIVE -> add(IptvProgram(title, group, stream("live", id, liveExtension), kind, logo,
                        hls = liveExtension == "m3u8", providerId = ref("live", id)))
                    IptvKind.MOVIE -> add(IptvProgram(title, group, stream("movie", id, row.extension()), kind, logo,
                        hls = row.extension().equals("m3u8", true), providerId = ref("movie", id)))
                    IptvKind.SERIES -> add(IptvProgram(title, group, "", kind, logo, seriesId = id,
                        providerId = ref("series", id)))
                }
            }
        }
    }

    fun episodes(allowHttp: Boolean, series: Long): List<IptvProgram> {
        val seasons = JSONObject(api(allowHttp, "get_series_info", series)).optJSONObject("episodes") ?: return emptyList()
        return seasons.keys().asSequence().sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }.flatMap { season ->
            seasons.optJSONArray(season)?.records().orEmpty().asSequence().sortedBy { it.optInt("episode_num") }.mapNotNull { row ->
                val id = row.optLong("id")
                if (id <= 0) return@mapNotNull null
                val episode = row.optString("episode_num")
                val title = row.optString("title").ifBlank { "Episode $episode" }.take(150)
                IptvProgram(title, "Season $season", stream("series", id, row.extension()), IptvKind.SERIES,
                    hls = row.extension().equals("m3u8", true), providerId = ref("episode-$series", id))
            }
        }.take(10_000).toList()
    }

    companion object {
        fun read(value: String): XtreamLogin {
            val json = JSONObject(value)
            val server = iptvUrl(json.getString("server"))?.takeUnless { it.query != null }
                ?: error("Enter a valid Xtream server URL.")
            return XtreamLogin(json.getString("name"), server, json.getString("username"), json.getString("password"))
        }
    }
}

private fun JSONObject.extension() =
    optString("container_extension").takeIf { it.matches(Regex("[a-zA-Z0-9]{1,8}")) } ?: "mp4"

private fun JSONArray.records() = (0 until length()).asSequence().mapNotNull(::optJSONObject)

internal class TogetherIptvCatalog(private val context: Context) {
    private val saved = SavedIptvPlaylist(context)
    private var login: XtreamLogin? = null
    var entries by mutableStateOf(emptyList<IptvProgram>())
        private set
    var allowHttp by mutableStateOf(false)
    var sourceName by mutableStateOf("")
        private set
    private val episodeCache = mutableMapOf<Long, List<IptvProgram>>()
    private val lock = kotlinx.coroutines.sync.Mutex()
    private var loadedSource: String? = null

    suspend fun restore() = withContext(Dispatchers.IO) { saved.read() }

    suspend fun load(source: String, persist: Boolean): Boolean {
        return lock.withLock {
            if (loadedSource == source && entries.isNotEmpty() && !persist) return@withLock true
            val xtream = if (source.startsWith("{")) XtreamLogin.read(source) else null
            val allow = allowHttp
            val (programs, stored) = withContext(Dispatchers.IO) {
                val uri = Uri.parse(source)
                val programs = when {
                    xtream != null -> xtream.catalog(allow)
                    uri.scheme == "content" -> context.contentResolver.openInputStream(uri)
                        ?.use { parseIptvPlaylist(it, localManifest = source) }.orEmpty()
                    else -> {
                        val address = iptvUrl(source) ?: error("Enter an HTTP or HTTPS playlist URL.")
                        val extension = address.path.substringAfterLast('.').lowercase()
                        if (extension == "ts" || extension == "mp4") {
                            val kind = if (extension == "ts") IptvKind.LIVE else IptvKind.MOVIE
                            listOf(IptvProgram("Direct IPTV stream", "", address.toString(), kind,
                                providerId = iptvKey(address.host, address.path.substringAfterLast('/'))))
                        } else IptvNetwork.open(address, allow).use { reply ->
                            check(reply.status in 200..299) { "IPTV provider returned HTTP ${reply.status}." }
                            reply.body?.let { parseIptvPlaylist(it, reply.uri) }.orEmpty()
                        }
                    }
                }
                check(programs.isNotEmpty()) { "No supported programs found in this playlist." }
                val stored = !persist || runCatching {
                    if (uri.scheme == "content") {
                        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    saved.write(source)
                }.isSuccess
                programs to stored
            }
            login = xtream
            episodeCache.clear()
            entries = programs
            loadedSource = source
            sourceName = xtream?.name ?: "IPTV playlist"
            stored
        }
    }

    suspend fun episodes(series: Long): List<IptvProgram> = lock.withLock {
        episodeCache[series]?.let { return@withLock it }
        val account = login ?: return@withLock emptyList()
        val allow = allowHttp
        withContext(Dispatchers.IO) { account.episodes(allow, series) }.also { episodeCache[series] = it }
    }

    suspend fun resolve(ref: String): IptvProgram? {
        if (entries.isEmpty()) restore()?.let { load(it, false) }
        return ref.takeIf { login?.owns(it) == true && "-episode-" in it }
            ?.split('-')?.getOrNull(3)?.toLongOrNull()
            ?.let { episodes(it).firstOrNull { program -> program.ref == ref } }
            ?: entries.firstOrNull { it.ref == ref && it.url.isNotBlank() }
    }
}

internal data class IptvBrowse(
    val kind: String = "", val category: String = "", val focused: String = "", val anchor: String = "",
) {
    fun json() = JSONObject().put("kind", kind).put("category", category).put("focused", focused).put("anchor", anchor)

    companion object {
        fun read(json: JSONObject) = IptvBrowse(json.optString("kind"), json.optString("category"), json.optString("focused"), json.optString("anchor"))
    }
}
