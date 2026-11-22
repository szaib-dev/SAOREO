package com.codetic.saoreo

import java.net.URI
import java.util.Locale

enum class Source(val label: String) {
    YOUTUBE("YouTube"), INSTAGRAM("Instagram"), TIKTOK("TikTok"),
    X("X"), SNAPCHAT("Snapchat"), PINTEREST("Pinterest"),
    WHATSAPP("WhatsApp"), BUSINESS("WhatsApp Business"), SHARED("Shared file"), IMPORTED("Imported"), CONVERTED("Conversion")
}

data class MediaLink(val url: String, val source: Source) {
    companion object {
        private val urlPattern = Regex("""https?://[^\s<>"\u0000-\u001f]+""", RegexOption.IGNORE_CASE)
        private val pinterest = setOf(
            "pinterest.com", "pinterest.ca", "pinterest.co.uk", "pinterest.de",
            "pinterest.fr", "pinterest.it", "pinterest.es", "pinterest.jp",
            "pinterest.com.au", "pinterest.com.mx", "pinterest.com.br",
            "pinterest.pt", "pinterest.ch", "pinterest.at", "pinterest.se",
            "pinterest.dk", "pinterest.nz", "pinterest.co.kr", "pin.it"
        )

        fun fromText(text: String): MediaLink? {
            if (text.length > 16_384) return null
            return urlPattern.findAll(text).firstNotNullOfOrNull {
                parse(it.value.trimEnd('.', ',', ')', ']', '}', ';', '!', '?'))
            }
        }

        fun parse(raw: String): MediaLink? {
            if (raw.length !in 1..4096 || raw.any { it.isWhitespace() || it == '\\' }) return null
            val uri = runCatching { URI(raw) }.getOrNull() ?: return null
            val host = uri.host?.lowercase(Locale.ROOT) ?: return null
            if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") ||
                uri.rawUserInfo != null || uri.port !in setOf(-1, 443) || uri.rawPath.isNullOrBlank()
            ) return null
            fun matches(domain: String) = host == domain || host.endsWith(".$domain")
            val source = when {
                matches("youtube.com") || matches("youtu.be") -> Source.YOUTUBE
                matches("instagram.com") -> Source.INSTAGRAM
                matches("tiktok.com") -> Source.TIKTOK
                matches("x.com") || matches("twitter.com") -> Source.X
                matches("snapchat.com") && uri.path.startsWith("/spotlight/") -> Source.SNAPCHAT
                pinterest.any(::matches) -> Source.PINTEREST
                else -> return null
            }
            val path = uri.rawPath
            val sections = path.split('/').filter { it.isNotEmpty() }
            val supportedPath = when (source) {
                Source.YOUTUBE -> if (matches("youtu.be")) sections.size == 1
                    else sections == listOf("watch") && uri.rawQuery.orEmpty().split('&').any {
                        it.startsWith("v=") && it.length > 2
                    } || sections.size == 2 && sections.first() in setOf("shorts", "live", "embed")
                Source.INSTAGRAM -> {
                    val media = setOf("p", "reel", "reels", "tv")
                    (sections.size == 2 && sections[0] in media + "share" &&
                        !(sections[0] in setOf("reel", "reels") && sections[1] == "audio")) ||
                        (sections.size == 3 && (sections[0] == "share" &&
                            sections[1] in setOf("p", "reel", "reels") ||
                            sections[0].matches(Regex("[A-Za-z0-9._]+")) && sections[0] !in media + "share" &&
                            sections[1] in media))
                }
                Source.TIKTOK -> when {
                    host in setOf("vm.tiktok.com", "vt.tiktok.com") -> sections.size == 1
                    sections.firstOrNull() == "t" -> sections.size == 2
                    else -> sections.size >= 3 && sections[0].startsWith("@") && sections[1] == "video"
                }
                Source.X -> sections.size >= 3 && sections[1] == "status" ||
                    sections.size == 4 && sections[0] == "i" && sections[1] == "web" && sections[2] == "status"
                Source.SNAPCHAT -> sections.size >= 2 && sections.first() == "spotlight"
                Source.PINTEREST -> if (matches("pin.it")) sections.size == 1
                    else sections.size >= 2 && sections.first() == "pin"
                else -> false
            }
            if (!supportedPath) return null
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            if (source == Source.INSTAGRAM) {
                val media = setOf("p", "reel", "reels", "tv")
                val canonicalParts = if (sections.size == 3 && sections[0] != "share" && sections[1] in media)
                    sections.drop(1) else sections
                val canonical = if (canonicalParts.first() == "reels") listOf("reel", canonicalParts[1]) else canonicalParts
                return MediaLink("https://www.instagram.com/${canonical.joinToString("/")}/$query", source)
            }
            return MediaLink("https://$host$path$query", source)
        }
    }
}

enum class Quality(val label: String, val cap: Int? = null) {
    ASK("Always Ask"), BEST("Best Available"), P2160("2160p", 2160),
    P1440("1440p", 1440), P1080("1080p", 1080), P720("720p", 720),
    P480("480p", 480), P360("360p", 360), AUDIO("Audio Only")
}

data class ExpectedSize(val bytes: Long, val approximate: Boolean) {
    fun label() = (if (approximate) "~" else "") + sizeLabel(bytes)
}

fun sizeLabel(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.US, "%.2f GB", bytes / 1e9)
    bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1e6)
    else -> String.format(Locale.US, "%.0f KB", bytes / 1e3)
}

fun expectedSize(exact: Long, approximate: Long, kbps: Double, seconds: Double): ExpectedSize? = when {
    exact > 0 -> ExpectedSize(exact, false)
    approximate > 0 -> ExpectedSize(approximate, true)
    kbps.isFinite() && seconds.isFinite() && kbps > 0 && seconds > 0 &&
        kbps * seconds * 125 < Long.MAX_VALUE -> ExpectedSize((kbps * seconds * 125).toLong(), true)
    else -> null
}

data class Choice(
    val id: String,
    val height: Int?,
    val extension: String,
    val size: ExpectedSize?,
    val audioOnly: Boolean = false,
    val imageUrl: String? = null,
    val width: Int? = null,
    val hasAudio: Boolean = true,
    val directVideoUrl: String? = null,
    val reusableStreams: List<ReusableStream> = emptyList(),
    val requiresAudio: Boolean = false,
) {
    val selector: String get() = id.removePrefix("mp3:")

    fun description(): String {
        val quality = when {
            imageUrl != null && width != null && height != null -> "${width} × $height"
            imageUrl != null -> "Original image"
            audioOnly -> "Audio"
            height != null -> "${height}p"
            else -> "Video"
        }
        return "$quality · ${extension.uppercase(Locale.ROOT)} · ${size?.label() ?: "Size unavailable"}"
    }
}

data class Preview(
    val title: String,
    val thumbnail: String?,
    val duration: Double?,
    val choices: List<Choice>,
)

internal fun outputChoices(video: List<Choice>, sortedAudio: List<Choice>): List<Choice> {
    val mp4Choices = video.map { item ->
        val compatibleTrack = when (item.extension) {
            "mp4", "mov" -> sortedAudio.firstOrNull { it.extension in setOf("m4a", "aac") }
            "webm" -> sortedAudio.firstOrNull { it.extension in setOf("webm", "opus") }
            else -> sortedAudio.firstOrNull()
        }
        val audioTrack = compatibleTrack ?: sortedAudio.firstOrNull()
        if (item.hasAudio || audioTrack == null) item else {
            val videoSize = item.size
            val audioSize = audioTrack.size
            item.copy(
                id = "${item.id}+${audioTrack.id}",
                extension = if (compatibleTrack == null) "mkv" else item.extension,
                size = if (videoSize != null && audioSize != null &&
                    videoSize.bytes <= Long.MAX_VALUE - audioSize.bytes) {
                    ExpectedSize(videoSize.bytes + audioSize.bytes, true)
                } else null,
                hasAudio = true,
                requiresAudio = true,
                reusableStreams = (item.reusableStreams + audioTrack.reusableStreams).takeIf { it.size == 2 }
                    .orEmpty(),
            )
        }
    }.groupBy { it.height }.values.map { candidates ->
        candidates.maxWith(compareBy<Choice> { it.hasAudio }.thenBy { it.extension == "mp4" })
    }.map { it.copy(extension = "mp4", size = it.size.takeIf { _ -> it.extension == "mp4" }) }
        .sortedByDescending { it.height ?: 0 }
    val audioSource = sortedAudio.firstOrNull() ?: video.lastOrNull { it.hasAudio }
    val mp3 = audioSource?.let {
        it.copy(id = "mp3:${it.id}", extension = "mp3", height = null, audioOnly = true,
            size = it.size.takeIf { _ -> it.extension == "mp3" }, requiresAudio = true)
    }
    return mp4Choices + listOfNotNull(mp3)
}

fun selectChoice(choices: List<Choice>, quality: Quality, preferred: String): Choice? {
    if (quality == Quality.AUDIO) return choices.firstOrNull { it.audioOnly }
    choices.firstOrNull { it.imageUrl != null }?.let { return it }
    return choices.filter { !it.audioOnly && (quality.cap == null || (it.height ?: Int.MAX_VALUE) <= quality.cap) }
        .maxWithOrNull(compareBy<Choice> { it.height ?: 0 }
            .thenBy { it.hasAudio }
            .thenBy { it.extension == preferred })
}

fun safeName(title: String): String = title
    .replace(Regex("""[^\p{L}\p{N} _().-]"""), "_")
    .take(90).trim(' ', '.').ifEmpty { "SAOREO media" }

fun mediaFailure(detail: String?): String {
    val text = detail.orEmpty().lowercase(Locale.ROOT)
    return when {
        "no space left" in text || "enospc" in text -> "The device is out of storage."
        "permission denied" in text -> "Android denied access to the file."
        "403" in text -> "The source refused the available media connections (403). Try this link on another network, or retry later."
        "requested format" in text || "no downloadable public formats" in text -> "The source has no supported download format. Try another quality or link."
        "429" in text -> "The platform is limiting requests. Try again later."
        "drm" in text || "private" in text || "sign in" in text || "login" in text ->
            "This media is restricted or requires signing in."
        "404" in text || "removed" in text || "deleted" in text -> "This media is no longer available."
        "timeout" in text || "timed out" in text -> "The source took too long to respond."
        "invalid completed media" in text || "unexpected document response" in text ->
            "The source returned an invalid media file. Nothing was saved."
        "merge" in text || "ffmpeg" in text -> "The audio and video could not be combined."
        else -> "Couldn’t retrieve this media from the source."
    }
}

/** yt-dlp may wrap Instagram carousel media in a playlist even when only one item is selected. */
internal fun mediaMetadataObject(root: org.json.JSONObject, depth: Int = 0): org.json.JSONObject {
    if (depth > 4 || root.optJSONArray("formats")?.length()?.let { it > 0 } == true) return root
    val entries = root.optJSONArray("entries") ?: return root
    for (index in 0 until minOf(entries.length(), 20)) {
        val item = entries.optJSONObject(index) ?: continue
        val selected = mediaMetadataObject(item, depth + 1)
        if ((selected.optJSONArray("formats")?.length() ?: 0) > 0) {
            if (selected.optString("title").isBlank()) selected.put("title", root.optString("title", "Instagram video"))
            return selected
        }
    }
    return root
}
