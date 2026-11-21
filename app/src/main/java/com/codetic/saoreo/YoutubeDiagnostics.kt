package com.codetic.saoreo

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import kotlin.concurrent.thread

internal enum class YoutubeTraceMode(val label: String) {
    OFF("Off"), WRAPPER("Current integration"), PROFILE("Detailed profiler"),
    STANDALONE("Standalone CLI")
}

/** Explicit trial options for Android instrumentation. The production default stays upstream's selector. */
internal enum class YoutubeTrial(val extractorArgument: String? = null) {
    DEFAULT, WEB("youtube:player_client=web"), TV("youtube:player_client=tv")
}

internal class YoutubeDiagnostics {
    private val _mode = MutableStateFlow(YoutubeTraceMode.OFF)
    val mode = _mode.asStateFlow()
    private val reports = ArrayDeque<YoutubeTrace>()
    private var nextId = 0
    private var launchMs: Long? = null

    fun select(mode: YoutubeTraceMode) { _mode.value = mode }

    @Synchronized
    fun recordActivity(nanos: Long) { launchMs = nanos / 1_000_000 }

    @Synchronized
    fun open(source: Source, route: String): YoutubeTrace? {
        val mode = _mode.value
        if (source != Source.YOUTUBE || mode == YoutubeTraceMode.OFF) return null
        val trace = YoutubeTrace(++nextId, mode)
        trace.add("route.$route")
        launchMs?.let { trace.add("activity.last_creation_to_first_frame", it * 1_000_000) }
        reports.addLast(trace)
        while (reports.size > 12) reports.removeFirst()
        return trace
    }

    @Synchronized
    fun export(): String {
        val included = ArrayDeque<String>()
        var length = 0
        for (trace in reports.reversed()) {
            val report = trace.report()
            if (length + report.length > 200_000) break
            included.addFirst(report)
            length += report.length
        }
        return included.joinToString("\n\n") +
            if (included.size < reports.size) "\nOlder reports exceeded the clipboard limit." else ""
    }

    @Synchronized
    fun clear() { reports.clear() }
}

internal class YoutubeTrace(private val id: Int, val mode: YoutubeTraceMode, val client: YoutubeTrial = YoutubeTrial.DEFAULT) {
    private val origin = SystemClock.elapsedRealtimeNanos()
    private val lines = ArrayList<Pair<Long, String>>()
    private var omitted = 0
    private var childOrigin: Long? = null
    private var childClockOrigin: Long? = null

    @Synchronized
    fun add(label: String, nanos: Long? = null, at: Long = SystemClock.elapsedRealtimeNanos()) {
        if (lines.size >= 1_600) { omitted++; return }
        val duration = nanos?.let { " duration_ms=${ms(it)}" }.orEmpty()
        lines.add((at - origin) to "${ms(at - origin)}ms $label$duration")
    }

    @Synchronized
    fun stderr(line: String, received: Long) {
        val marker = profileLine.matchEntire(line.take(2_048))
        if (marker != null) {
            val (clock, at, duration, event) = marker.destructured
            val parsedTime = at.toLongOrNull() ?: return
            val parsedDuration = duration.toLongOrNull() ?: return
            if (!knownEvents.matches(event)) { add("child.profile_unknown", at = received); return }
            if (clock == "7" && parsedTime in 1..received) {
                add("child.$event", parsedDuration, parsedTime)
            } else {
                if (childClockOrigin == null) {
                    childClockOrigin = parsedTime
                    childOrigin = received
                    add("child.clock_aligned_at_first_line", at = received)
                }
                add("child.$event", parsedDuration, childOrigin!! + parsedTime - childClockOrigin!!)
            }
        } else add(YoutubeVerbose.summary(line), at = received)
    }

    @Synchronized
    fun report(): String = buildString {
        appendLine("YouTube #$id · ${mode.label} · ${client.name} · milliseconds relative to request")
        lines.sortedBy { it.first }.forEach { appendLine(it.second) }
        if (omitted > 0) appendLine("log_limit: $omitted lines omitted")
    }

    companion object {
        private val profileLine = Regex("""SAOREO_PROFILE (-?\d+) (\d+) (\d+) ([a-zA-Z0-9_.]+)""")
        private val knownEvents = Regex(
            """(?:python\.entry|yt_dlp\.(?:imports|cli\.start)|hook\.unavailable\.[a-z_]+|(?:http\.(?:player_api|player_js|watch_page|youtube_other|googlevideo|other|body_read|wire_send|wire_headers)|youtube\.(?:player_responses|formats_and_subtitles)|sleep\.intentional|js\.(?:runtime_probe|challenge_process))\.(?:start|ok|error|finished))"""
        )
        private fun ms(nanos: Long) = String.format(Locale.US, "%.3f", nanos / 1_000_000.0)
    }
}

/** Emit one safe event per stderr line. Never include strings supplied by a media site. */
internal object YoutubeVerbose {
    private val retry = Regex("""(?i)retrying\b.*?\((\d{1,3})\s*/\s*(\d{1,3})\)""")
    private val http = Regex("""(?i)HTTP(?:\s+Error|\s+status(?:\s+code)?)?[: ]+(\d{3})\b""")
    private val client = Regex(
        """(?i)Downloading (web_safari|web_embedded|web_creator|web_music|web|mweb|ios|android_vr|android|tv_downgraded|tv_embedded|tv_simply|tv|visionos|mediaconnect|tv samsung) player API JSON\b"""
    )
    private val checks = listOf(
        "player api json" to "player_api",
        "downloading webpage" to "watch_page",
        "downloading player" to "player_js",
        "m3u8 information" to "hls_manifest",
        "mpd manifest" to "dash_manifest",
        "solving js challenges" to "js_challenge",
        "js challenge providers" to "js_provider_inventory",
        "js runtimes:" to "js_runtime_inventory",
        "request handlers:" to "http_handlers",
        "timed out" to "timeout",
        "timeout" to "timeout",
        "connection refused" to "connection_refused",
        "certificate verify failed" to "tls_certificate_failed",
        "unable to download" to "fetch_failed",
        "no supported javascript runtime" to "js_runtime_unavailable",
        "po token" to "player_token_notice",
        "sign in" to "sign_in_required",
        "drm" to "drm_notice",
        "subtitles" to "subtitles_notice",
        "sleeping" to "sleep",
        "cache" to "cache_notice",
    )
    fun summary(raw: String): String {
        val line = raw.take(8_192)
        val lower = line.lowercase(Locale.US)
        val severity = when {
            lower.startsWith("error:") -> "error"
            lower.startsWith("warning:") -> "warning"
            lower.startsWith("[debug]") -> "debug"
            else -> "info"
        }
        val phase = client.find(line)?.let { "player_api.client_${it.groupValues[1].lowercase(Locale.US).replace(' ', '_')}" }
            ?: checks.firstOrNull { (needle, _) -> needle in lower }?.second
            ?: if ("retrying" in lower) "retry" else "redacted"
        return buildString {
            append("verbose.$severity.$phase")
            http.find(line)?.let { append(" status=${it.groupValues[1]}") }
            retry.find(line)?.let { append(" retry=${it.groupValues[1]}/${it.groupValues[2]}") }
        }
    }
}

internal class YoutubeProfileProcess(private val context: Context) {
    fun execute(arguments: List<String>, trace: YoutubeTrace): String {
        val bin = File(context.applicationInfo.nativeLibraryDir)
        val engine = File(context.noBackupFilesDir, "youtubedl-android")
        val packages = File(engine, "packages")
        val python = File(packages, "python/usr")
        val profile = File.createTempFile("youtube-profile-", ".py", context.cacheDir)
        try {
            val instrument = trace.mode == YoutubeTraceMode.PROFILE
            if (instrument) context.resources.openRawResource(R.raw.yt_profile).use { input ->
                profile.outputStream().use { input.copyTo(it) }
            }
            val standalone = File(engine, "yt-dlp/yt-dlp")
            val argv = listOf(File(bin, "libpython.so").absolutePath) +
                (if (instrument) listOf(profile.absolutePath) else emptyList()) + listOf(standalone.absolutePath) +
                listOf("--js-runtimes", "quickjs:${File(bin, "libqjs.so").absolutePath}",
                    "--ffmpeg-location", File(bin, "libffmpeg.so").absolutePath) + arguments
            val builder = ProcessBuilder(argv)
            builder.environment().apply {
                this["LD_LIBRARY_PATH"] = listOf("python", "ffmpeg", "aria2c").joinToString(":") {
                    File(packages, "$it/usr/lib").absolutePath
                }
                this["SSL_CERT_FILE"] = File(python, "etc/tls/cert.pem").absolutePath
                this["PATH"] = System.getenv("PATH") + ":" + bin.absolutePath
                this["PYTHONHOME"] = python.absolutePath
                this["HOME"] = python.absolutePath
                this["TMPDIR"] = context.cacheDir.absolutePath
            }
            val launched = SystemClock.elapsedRealtimeNanos()
            trace.add("process.launch", at = launched)
            val process = builder.start()
            trace.add("process.started", SystemClock.elapsedRealtimeNanos() - launched)
            try {
                val stdout = FutureTask { process.inputStream.bufferedReader().use { it.readText() } }
                val stderr = FutureTask {
                    process.errorStream.bufferedReader().useLines { lines ->
                        lines.forEach { trace.stderr(it, SystemClock.elapsedRealtimeNanos()) }
                    }
                }
                thread(isDaemon = true, name = "yt-profile-stdout") { stdout.run() }
                thread(isDaemon = true, name = "yt-profile-stderr") { stderr.run() }
                val exit = process.waitFor()
                stderr.get()
                trace.add("process.exit_$exit", SystemClock.elapsedRealtimeNanos() - launched)
                if (exit != 0) throw IOException("YouTube extraction failed. See diagnostics.")
                return stdout.get()
            } catch (failure: ExecutionException) {
                throw IOException("Unable to capture the YouTube profile", failure)
            } finally {
                if (process.isAlive) {
                    process.destroyForcibly()
                }
            }
        } finally { profile.delete() }
    }
}
