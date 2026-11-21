package com.codetic.saoreo

import org.json.JSONArray
import org.json.JSONObject

internal data class TranscriptLine(val ms: Long?, val text: String)
internal data class Transcript(val title: String, val lines: List<TranscriptLine>, val thumbnail: String? = null) {
    fun text(timestamps: Boolean): String = lines.joinToString("\n") { line ->
        if (timestamps && line.ms != null) "[${transcriptClock(line.ms)}] ${line.text}" else line.text
    }
    fun encode() = JSONObject().put("title", title).put("thumbnail", thumbnail)
        .put("lines", JSONArray().apply { lines.forEach {
            put(JSONObject().put("ms", it.ms ?: -1).put("text", it.text))
        } }).toString()

    companion object {
        fun decode(text: String): Transcript {
            val root = JSONObject(text)
            val entries = root.getJSONArray("lines")
            return Transcript(root.getString("title"), List(entries.length()) { index ->
                val entry = entries.getJSONObject(index)
                TranscriptLine(entry.getLong("ms").takeIf { it >= 0 }, entry.getString("text"))
            }, root.optString("thumbnail").takeIf(String::isNotBlank))
        }
    }
}

internal data class CaptionFile(val format: String, val url: String)
internal data class CaptionTrack(val key: String, val label: String, val files: List<CaptionFile>)

internal fun transcriptLink(text: String): MediaLink? {
    val input = text.trim()
    val explicit = MediaLink.fromText(input)
    val link = explicit ?: input.takeIf { !it.any(Char::isWhitespace) }
        ?.let { MediaLink.parse("https://$it") } ?: return null
    if (link.source != Source.YOUTUBE) return link

    val uri = java.net.URI(link.url)
    val id = if (uri.path.trimEnd('/') == "/watch") {
        uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("v=") }?.substring(2)
    } else uri.path.trimEnd('/').substringAfterLast('/')
    return id?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
        ?.let { MediaLink("https://www.youtube.com/watch?v=$it", Source.YOUTUBE) }
}

internal fun parseJsonCaptions(raw: String): List<TranscriptLine> {
    val events = JSONObject(raw).optJSONArray("events") ?: return emptyList()
    val lines = mutableListOf<TranscriptLine>()
    for (i in 0 until events.length()) {
        val event = events.optJSONObject(i) ?: continue
        val segments = event.optJSONArray("segs") ?: continue
        val time = event.optLong("tStartMs", -1L)
        if (time < 0) continue
        val text = (0 until segments.length()).joinToString("") { index ->
            segments.optJSONObject(index)?.optString("utf8").orEmpty()
        }.trim()
        if (text.isBlank() || lines.lastOrNull()?.let { it.text == text && it.ms == time } == true) continue
        lines.add(TranscriptLine(time, text))
    }
    return lines
}

private val cue = Regex("""^((?:\d{1,4}:)?\d{2}:\d{2}[.,]\d{3})\s+-->\s+((?:\d{1,4}:)?\d{2}:\d{2}[.,]\d{3})(?:\s.*)?$""")
private fun timeValue(value: String): Long? {
    val parts = value.replace(',', '.').split(':', '.').map { it.toLongOrNull() ?: return null }
    val sub = parts.last()
    val seconds = parts[parts.size - 2]
    val minutes = parts[parts.size - 3]
    val hours = if (parts.size == 4) parts[0] else 0
    if (minutes >= 60 || seconds >= 60) return null
    return ((hours * 60 + minutes) * 60 + seconds) * 1000 + sub
}

internal fun transcriptClock(ms: Long): String {
    val t = ms / 1000
    return if (t < 3600) durationLabel(t) else
        "${t / 3600}:${(t / 60 % 60).toString().padStart(2, '0')}:${(t % 60).toString().padStart(2, '0')}"
}

internal fun parseTranscript(raw: String, plain: Boolean = false, offset: Long = 0): List<TranscriptLine> {
    val rows = raw.removePrefix("\uFEFF").lineSequence().map(String::trim)
    if (plain) return rows.filter(String::isNotEmpty).map { TranscriptLine(null, it) }.toList()
    var timestamp: Long? = null
    var previousCue = -1L
    var ignoredBlock = false
    val lines = mutableListOf<TranscriptLine>()
    for (row in rows) {
        if (row.isEmpty()) {
            timestamp = null
            ignoredBlock = false
            continue
        }
        if (row == "STYLE" || row == "REGION" || row == "NOTE" || row.startsWith("NOTE ")) {
            ignoredBlock = true
        }
        if (ignoredBlock) continue
        if ("-->" in row) {
            val match = cue.matchEntire(row)
            val start = match?.groupValues?.get(1)?.let(::timeValue)
            val end = match?.groupValues?.get(2)?.let(::timeValue)
            timestamp = start?.takeIf { end != null && end >= it && it >= previousCue }
            if (timestamp != null) previousCue = timestamp
            continue
        }
        val start = timestamp ?: continue
        val clean = row.replace(Regex("<[^<>]{1,160}>"), "")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
            .replace(Regex("[\\t ]+"), " ").trim()
        if (clean.isEmpty()) continue
        val recent = lines.takeLast(4)
        if (recent.any { it.text == clean && (it.ms ?: -10000) >= offset + start - 3500 }) continue
        lines.add(TranscriptLine(offset + start, clean))
    }
    return lines
}
