package com.codetic.saoreo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URI

internal data class RemotePlaylistItem(val key: Int, val title: String, val link: MediaLink?,
    val artist: String = "", val thumbnail: String? = null, val duration: Double? = null,
    val spotifyUrl: String? = null, val musicArtist: String? = null, val musicTitle: String? = null) {
    val selectable get() = link != null || spotifyUrl != null
}
internal data class RemotePlaylist(val title: String, val thumbnail: String?, val items: List<RemotePlaylistItem>,
    val sourceUrl: String, val notice: String? = null)

internal fun spotifyPlaylistUrl(text: String): String? {
    if (text.length > 16_384) return null
    return Regex("https?://[^\\s<>\"]+").findAll(text).firstNotNullOfOrNull { match ->
        val uri = runCatching { URI(match.value.trimEnd('.', ',', ')', ']')) }.getOrNull() ?: return@firstNotNullOfOrNull null
        val path = uri.path.orEmpty().replaceFirst(Regex("^/intl-[a-z]{2}"), "")
        if (uri.host != "open.spotify.com" || uri.rawUserInfo != null || uri.port !in setOf(-1, 443) ||
            !path.matches(Regex("/playlist/[A-Za-z0-9]{22}/?"))) null
        else "https://open.spotify.com${path.trimEnd('/')}"
    }
}

internal fun JSONObject.musicField(name: String): String? =
    optString(name).trim().take(240).takeUnless { it.isBlank() || it == "NA" || it == "null" }

internal fun JSONObject.youtubeMusicArtist(): String? {
    val artists = optJSONArray("artists")
    val channel = musicField("channel") ?: musicField("uploader")
    return musicField("artist") ?: artists?.optString(0)?.takeIf(String::isNotBlank)
        ?: channel?.takeIf { it.endsWith(" - Topic") }?.removeSuffix(" - Topic")
}

internal fun youtubePlaylist(root: JSONObject, sourceUrl: String): RemotePlaylist {
    val entries = root.optJSONArray("entries")
    val items = (0 until (entries?.length() ?: 0)).map { index ->
        val row = entries?.optJSONObject(index)
        val id = row?.optString("id")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
        val title = row?.optString("title")?.take(240).orEmpty().ifBlank { "Item ${index + 1}" }
        val available = id != null && row?.optString("availability") !in setOf("private", "needs_auth", "premium_only", "subscriber_only") &&
            title !in setOf("[Private video]", "[Deleted video]")
        RemotePlaylistItem(index, title, if (available) MediaLink("https://www.youtube.com/watch?v=$id", Source.YOUTUBE) else null,
            row?.optString("uploader").orEmpty(), id?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" },
            row?.optDouble("duration")?.takeIf { it.isFinite() && it > 0 },
            musicArtist = row?.youtubeMusicArtist(), musicTitle = row?.musicField("track"))
    }
    val thumbs = root.optJSONArray("thumbnails")
    return RemotePlaylist(root.optString("title").ifBlank { "YouTube playlist" }.take(80),
        thumbs?.optJSONObject((thumbs.length() - 1).coerceAtLeast(0))?.optString("url")?.takeIf { it.startsWith("https://") }
            ?: items.firstOrNull()?.thumbnail, items, sourceUrl)
}

internal fun spotifyPlaylist(html: String, sourceUrl: String): RemotePlaylist {
    val script = Regex("""<script\b[^>]*\bid=["']__NEXT_DATA__["'][^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        .find(html)?.groupValues?.get(1) ?: throw IOException("Spotify did not expose this public playlist. Private playlists cannot be imported.")
    val entity = JSONObject(script).getJSONObject("props").getJSONObject("pageProps").getJSONObject("state")
        .getJSONObject("data").getJSONObject("entity")
    val tracks = entity.optJSONArray("trackList") ?: throw IOException("Spotify returned no playlist tracks.")
    val cover = entity.optJSONObject("coverArt")?.optJSONArray("sources")?.optJSONObject(0)?.optString("url")
        ?.takeIf { it.startsWith("https://i.scdn.co/") || it.startsWith("https://image-cdn-ak.spotifycdn.com/") }
    val items = (0 until tracks.length()).map { index ->
        val track = tracks.getJSONObject(index)
        val id = track.optString("uri").removePrefix("spotify:track:").takeIf { it.matches(Regex("[A-Za-z0-9]{22}")) }
        val title = track.optString("title").take(240)
        val artist = track.musicField("subtitle").orEmpty()
        RemotePlaylistItem(index, title, null, artist, cover,
            track.optDouble("duration").takeIf { it.isFinite() && it > 0 }?.div(1000), id?.let { "https://open.spotify.com/track/$it" },
            musicArtist = artist.takeIf(String::isNotBlank), musicTitle = title.takeIf(String::isNotBlank))
    }
    return RemotePlaylist(entity.optString("name").ifBlank { entity.optString("title", "Spotify playlist") }.take(80), cover, items, sourceUrl,
        "Selected songs download from YouTube matches, which may be different recordings. Spotify's public embed may expose only part of a long playlist; only the tracks shown here will be imported.")
}

internal suspend fun loadSpotifyPlaylist(url: String): RemotePlaylist = withContext(Dispatchers.IO) {
    val canonical = spotifyPlaylistUrl(url) ?: throw IOException("Enter a Spotify playlist link.")
    val request = Request.Builder().url(canonical.replace("/playlist/", "/embed/playlist/")).build()
    mediaHttp.newCall(request).execute().use { response ->
        if (!response.isSuccessful || response.request.url.host != "open.spotify.com")
            throw IOException("Spotify playlist unavailable (HTTP ${response.code}).")
        val input = response.body?.byteStream() ?: throw IOException("Empty Spotify response.")
        val data = input.readNBytes(4_000_001)
        if (data.size > 4_000_000) throw IOException("Spotify playlist response is too large.")
        spotifyPlaylist(data.toString(Charsets.UTF_8), canonical)
    }
}

internal fun bestSongMatch(song: RemotePlaylistItem, candidates: List<RemotePlaylistItem>): RemotePlaylistItem? {
    fun words(text: String) = Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase()).map { it.value }.filter { it.length > 1 }.toSet()
    val wanted = words(song.title)
    if (wanted.isEmpty()) return null
    val artistWords = words(song.artist.substringBefore(',')).minus(setOf("the", "and", "feat"))
    return candidates.filter { candidate ->
        val found = words(candidate.title)
        candidate.link != null && (artistWords.isEmpty() ||
            artistWords.intersect(words(candidate.title + " " + candidate.artist)).isNotEmpty()) && wanted.intersect(found).size.toDouble() / wanted.size >= 0.65 &&
            (song.duration == null || candidate.duration == null || kotlin.math.abs(song.duration - candidate.duration) <= 15) &&
            listOf("karaoke", "cover", "remix", "live", "slowed", "sped").none { it in found && it !in wanted }
    }.maxByOrNull { candidate ->
        words(candidate.title).intersect(wanted).size * 3 + words(song.artist).intersect(words(candidate.title + " " + candidate.artist)).size
    }
}
