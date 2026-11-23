package com.codetic.saoreo

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

internal data class LyricWord(val time: Long, val start: Int, val end: Int)
internal data class LyricLine(val time: Long?, val text: String, val words: List<LyricWord> = emptyList())
private val lineStamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
private val wordStamp = Regex("<(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?>")
private fun stamp(match: MatchResult): Long =
    (match.groupValues[1].toLong() * 60 + match.groupValues[2].toLong()) * 1000 +
        (match.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0)

internal fun parseLyrics(text: String): List<LyricLine> {
    val offset = Regex("(?i)\\[offset:([+-]?\\d+)]").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0
    val rows = text.removePrefix("\uFEFF").lineSequence().take(5000).flatMap { raw ->
        val stamps = lineStamp.findAll(raw).filter { it.groupValues[2].toInt() < 60 }.toList()
        if (stamps.isEmpty()) {
            listOfNotNull(raw.trim().takeIf { it.isNotEmpty() && !it.startsWith("[") }?.let { LyricLine(null, it) })
        } else {
            val body = raw.substring(stamps.last().range.last + 1)
            val markers = wordStamp.findAll(body).toList()
            val clean = StringBuilder()
            val words = mutableListOf<LyricWord>()
            var cursor = 0
            for ((index, marker) in markers.withIndex()) {
                clean.append(body.substring(cursor, marker.range.first))
                val start = clean.length
                val end = markers.getOrNull(index + 1)?.range?.first ?: body.length
                clean.append(body.substring(marker.range.last + 1, end))
                words += LyricWord((stamp(marker) + offset).coerceAtLeast(0), start, clean.length)
                cursor = end
            }
            if (markers.isEmpty()) clean.append(body)
            stamps.map { LyricLine((stamp(it) + offset).coerceAtLeast(0), clean.toString(), words.toList()) }
        }
    }.toList()
    val timed = rows.filter { it.time != null }
    return if (timed.isNotEmpty()) timed.sortedBy { it.time } else rows
}

internal data class LyricsMetadata(val title: String, val artist: String, val album: String?, val duration: Long)
internal fun normalizedMusicName(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")

internal fun identifyLyricsMetadata(title: String, artist: String?, album: String?, duration: Long): LyricsMetadata {
    var clean = title.replace(Regex("(?i)\\.(mp3|m4a|flac|wav|ogg|opus)$"), "")
        .replace(Regex("(?i)\\s*[\\[(](?:official\\s+)?(?:audio|music video|video|lyric video|lyrics|hd|hq|4k|4k remaster|4k remastered)[\\])]"), "")
        .trim()
    var performer = artist?.trim()?.takeUnless { it.isBlank() || it.equals("Unknown artist", true) || it == "<unknown>" }
    val parts = clean.split(Regex("\\s+[-–—]\\s+"), limit = 2)
    if (parts.size == 2 && parts.all { it.isNotBlank() } &&
        (performer == null || normalizedMusicName(parts[0]) == normalizedMusicName(performer))) {
        performer = parts[0]
        clean = parts[1]
    }
    return LyricsMetadata(clean, performer.orEmpty(), album?.takeIf(String::isNotBlank), duration)
}

internal fun lyricsMatch(query: LyricsMetadata, title: String, artist: String, album: String?, seconds: Double): Boolean =
    query.artist.isNotBlank() && query.duration > 0 && seconds.isFinite() &&
        normalizedMusicName(query.title) == normalizedMusicName(title) &&
        normalizedMusicName(query.artist) == normalizedMusicName(artist) &&
        abs(query.duration / 1000.0 - seconds) <= 3.0 &&
        (query.album.isNullOrBlank() || normalizedMusicName(query.album) == normalizedMusicName(album.orEmpty()))
