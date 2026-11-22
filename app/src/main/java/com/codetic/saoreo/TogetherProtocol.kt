package com.codetic.saoreo

import java.net.URI
import kotlin.math.abs

/** Shared timeline uses the estimated server clock, never another phone's wall clock. */
internal data class SharedTimeline(val position: Long, val serverTime: Long, val playing: Boolean) {
    fun target(serverNow: Long): Long = (position + if (playing) (serverNow - serverTime).coerceAtLeast(0) else 0).coerceAtLeast(0)
    fun needsSeek(localPosition: Long, serverNow: Long, tolerance: Long = 1100): Boolean =
        abs(target(serverNow) - localPosition) > tolerance
}

internal object TogetherSources {
    fun youtube(input: String): String? = runCatching {
        val uri = URI(input.trim())
        if (uri.scheme !in setOf("https", "http")) return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        val id = when (host) {
            "youtu.be" -> uri.path.trim('/').substringBefore('/')
            "youtube.com", "m.youtube.com", "music.youtube.com" -> {
                if (uri.path == "/watch") uri.rawQuery?.split('&')?.firstOrNull { it.startsWith("v=") }?.substringAfter('=')
                else uri.path.split('/').let { if (it.getOrNull(1) in setOf("shorts", "live", "embed")) it.getOrNull(2) else null }
            }
            else -> null
        }
        id?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
    }.getOrNull()

    fun endpoint(input: String): String? = runCatching {
        val uri = URI(input.trim().trimEnd('/'))
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null) null
        else uri.toASCIIString().removeSuffix("/together").trimEnd('/')
    }.getOrNull()

    fun invite(input: String): String? {
        val text = input.trim()
        val code = if (text.startsWith("saoreo://")) runCatching { URI(text).rawQuery?.substringAfter("invite=") }.getOrNull()
        else text.lineSequence().map(String::trim).firstOrNull { it.matches(Regex("[a-f0-9]{12}\\.[A-Za-z0-9_-]{32}")) }
        return code?.takeIf { it.matches(Regex("[a-f0-9]{12}\\.[A-Za-z0-9_-]{32}")) }
    }
}
