package com.codetic.saoreo

import java.net.URI
import java.util.Locale

class ReusableStream internal constructor(
    val id: String,
    val url: String,
    val extension: String,
    val videoCodec: String?,
    val audioCodec: String?,
    val headers: Map<String, String>,
)

private fun hostMatches(host: String, domain: String): Boolean = host == domain || host.endsWith(".$domain")

internal fun streamCdnHost(source: Source, host: String): Boolean = when (source) {
    Source.YOUTUBE -> hostMatches(host, "googlevideo.com")
    Source.INSTAGRAM -> hostMatches(host, "cdninstagram.com") || hostMatches(host, "fbcdn.net")
    Source.TIKTOK -> listOf("tiktokcdn.com", "tiktokcdn-us.com", "tiktokcdn-eu.com", "tiktokv.com")
        .any { hostMatches(host, it) }
    Source.X -> host == "video.twimg.com"
    Source.PINTEREST -> host == "v.pinimg.com" || host.matches(Regex("v[0-9]+[.]pinimg[.]com"))
    else -> false
}

internal fun reusableStream(
    source: Source, id: String, url: String, ext: String, protocol: String,
    video: String?, audio: String?, headers: Map<String, String>, hasFragments: Boolean, needsCookies: Boolean,
): ReusableStream? {
    if (protocol != "https" || hasFragments || needsCookies || url.length > 8192 ||
        !id.matches(Regex("[a-zA-Z0-9_.-]+")) || ext !in setOf("mp4", "mov", "webm", "m4a", "mp3", "aac", "opus", "mkv")
    ) return null
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    val host = uri.host?.lowercase(Locale.ROOT) ?: return null
    if (uri.scheme != "https" || uri.rawUserInfo != null || uri.rawFragment != null ||
        uri.port !in setOf(-1, 443) || !streamCdnHost(source, host)
    ) return null
    val allowed = setOf("user-agent", "accept", "accept-language", "referer", "origin", "sec-fetch-mode", "accept-encoding")
    val safeHeaders = linkedMapOf<String, String>()
    for ((name, value) in headers) {
        val lower = name.lowercase(Locale.ROOT)
        if (lower !in allowed || value.length > 4096 || value.any { it.code < 32 || it.code == 127 }) return null
        if (lower == "accept-encoding") continue
        if (lower == "referer" || lower == "origin") {
            val origin = runCatching { URI(value) }.getOrNull() ?: return null
            val candidate = MediaLink.parse(value) ?: MediaLink.parse(value.trimEnd('/') + when (source) {
                Source.YOUTUBE -> "/watch?v=origin"
                Source.INSTAGRAM -> "/p/origin"
                Source.TIKTOK -> "/@public/video/0"
                Source.X -> "/i/status/0"
                Source.PINTEREST -> "/pin/0"
                else -> return null
            })
            if (origin.scheme != "https" || candidate?.source != source ||
                origin.rawUserInfo != null || origin.rawQuery != null || origin.rawFragment != null || origin.port !in setOf(-1, 443)) return null
        }
        safeHeaders[name] = value
    }
    return ReusableStream(id, url, ext, video, audio, safeHeaders)
}

/** Preserve meaningful and unknown query parameters. These are documented campaign tags. */
internal fun MediaLink.metadataKey(): String {
    canonicalYoutubeVideo()?.let { return it }
    val uri = URI(url)
    val query = uri.rawQuery ?: return url
    val kept = query.split('&').filterNot { it.substringBefore('=').startsWith("utm_", ignoreCase = true) }
    return url.substringBefore('?') + if (kept.isEmpty()) "" else "?" + kept.joinToString("&")
}

internal fun MediaLink.canonicalYoutubeVideo(): String? {
    if (source != Source.YOUTUBE) return null
    val uri = URI(url)
    val pieces = uri.path.split('/').filter(String::isNotEmpty)
    val id = if (pieces == listOf("watch")) {
        val ids = uri.rawQuery.orEmpty().split('&').filter { it.startsWith("v=") }.map { it.substringAfter('=') }.distinct()
        ids.singleOrNull()
    } else if (pieces.size == 2 || uri.host == "youtu.be") pieces.lastOrNull() else null
    return id?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
        ?.let { "https://www.youtube.com/watch?v=$it" }
}
