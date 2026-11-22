package com.codetic.saoreo

internal fun youtubeSelection(quality: Quality, selectedHeight: Int?): String {
    if (quality == Quality.AUDIO) return "bestaudio/best[vcodec!=none][acodec!=none]"
    val height = selectedHeight ?: quality.cap
    fun formats(limit: String): String =
        "bestvideo$limit[ext=mp4][vcodec^=avc1]+bestaudio[ext=m4a]/" +
            "best$limit[ext=mp4][vcodec^=avc1][acodec!=none]/" +
            "bestvideo$limit+bestaudio/best$limit[vcodec!=none][acodec!=none]"
    return if (height == null) formats("") else formats("[height<=$height]") +
        "/worstvideo+bestaudio/worst[vcodec!=none][acodec!=none]"
}

internal fun transferFailureReport(source: Source, stage: String, error: Throwable): String {
    val raw = error.message.orEmpty()
    val status = Regex("""(?:HTTP(?: Error)?|response code)\s*[:=]?\s*(4\d\d|5\d\d)\b""", RegexOption.IGNORE_CASE)
        .findAll(raw).map { it.groupValues[1] }.lastOrNull()
    val reason = when {
        status != null -> "HTTP $status"
        error is InvalidMediaException -> "media validation failed"
        raw.contains("requested format", true) -> "format unavailable"
        raw.contains("ffmpeg", true) -> "media processing failed"
        raw.contains("timed out", true) -> "timed out"
        error is SecurityException -> "permission denied"
        else -> "operation failed"
    }
    return "${source.label} · $stage · $reason"
}

/** Keep upstream defaults first; only try other public clients after an actionable source error. */
internal val youtubeClients: List<String?> = listOf(
    null,
    "youtube:player_client=web_safari",
    "youtube:player_client=default,-web_embedded",
)

internal fun shouldRetryYoutubeClient(error: Throwable): Boolean {
    if (error is java.util.concurrent.CancellationException || error is InterruptedException) return false
    val text = error.message.orEmpty().lowercase(java.util.Locale.ROOT)
    if (listOf("429", "private video", "sign in", "login", "age-restricted", "drm", "no space left", "permission denied")
        .any { it in text }) return false
    return listOf("403", "forbidden", "requested format", "no video formats", "no downloadable public formats",
        "signature solving failed", "n challenge solving failed").any { it in text }
}
