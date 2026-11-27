package com.codetic.saoreo

import org.webrtc.RTCStats
import org.webrtc.RTCStatsReport

internal enum class CallQualityMode(val label: String, val maxHeight: Int, val bitrateCap: Int) {
    AUTOMATIC("Automatic", 1080, 5_000_000),
    SMART("Smart Clarity (recommended)", 1080, 5_000_000),
    SD("480p", 480, 1_800_000),
    HD("720p HD", 720, 3_500_000),
    FHD("1080p Full HD", 1080, 5_000_000),
    MAXIMUM("1440p QHD · Maximum Clarity", 1440, 8_000_000),
}

internal data class CallVideoProfile(val height: Int, val fps: Int) {
    val label get() = if (height == 0) "Audio priority · video paused" else "${height}p · up to $fps fps"
}

/** An outgoing-only envelope. WebRTC still chooses the actual encoder bitrate below the cap. */
internal class CallVideoQuality(private val mode: CallQualityMode, maxCaptureHeight: Int) {
    private val tiers = listOf(
        CallVideoProfile(0, 0), CallVideoProfile(360, 8), CallVideoProfile(480, 12),
        CallVideoProfile(720, 18), CallVideoProfile(1080, 15),
        CallVideoProfile(1080, 24), CallVideoProfile(1080, 30), CallVideoProfile(1440, 24),
    )
    private val bandwidth = listOf(0, 180_000, 400_000, 850_000, 1_650_000, 2_800_000, 4_000_000, 5_800_000)
    private val ceiling = tiers.indexOfLast { it.height <= minOf(mode.maxHeight, maxCaptureHeight) }.coerceAtLeast(1)
    private var level = if (mode == CallQualityMode.AUTOMATIC) ceiling else minOf(3, ceiling)
    private var good = 0
    private var bad = 0
    private var unknown = 0
    val profile get() = tiers[level]

    fun update(stats: CallNetworkStats): CallVideoProfile {
        if (mode == CallQualityMode.AUTOMATIC) return profile
        val estimate = stats.availableVideoBps
        // Probe with low-rate video if pausing caused the transport estimate to disappear.
        if (estimate == null && level == 0) {
            if (++unknown >= 5) { level = 1; unknown = 0 }
            return profile
        }
        unknown = 0
        val currentLoss = if (level == 0) 0.0 else stats.loss ?: 0.0
        val stressed = level > 0 && (stats.encoderLimited || currentLoss >= .08)
        val floor = if (estimate != null && estimate < bandwidth[1]) 0 else 1
        val target = when {
            estimate == null -> level
            estimate < bandwidth[level] -> bandwidth.indexOfLast { estimate >= it }.coerceAtMost(ceiling)
            else -> bandwidth.indexOfLast { estimate >= it * 1.18 }.coerceIn(level, ceiling)
        }
        val desired = if (stressed) minOf(target, (level - 1).coerceAtLeast(floor)) else target
        when {
            desired < level -> {
                good = 0
                if (++bad >= 2) {
                    // Reduce pacing first at Full HD, except when audio needs the link immediately.
                    level = if (desired > 0 && level > 4 && !stats.encoderLimited) level - 1 else desired
                    bad = 0
                }
            }
            desired > level && !stressed && estimate != null && currentLoss < .03 -> {
                bad = 0
                if (++good >= 4) { level++; good = 0 }
            }
            else -> { good = 0; bad = 0 }
        }
        return profile
    }
}

internal data class CallNetworkStats(
    val rttMs: Double?, val loss: Double?, val jitterMs: Double?, val availableVideoBps: Double?,
    val encoderLimited: Boolean, val limitation: String?, val diagnostic: String,
)

private fun RTCStats.value(key: String) = (members[key] as? Number)?.toDouble()
private fun RTCStats.video() = (members["kind"] ?: members["mediaType"]) == "video"
private fun Double?.metric(unit: String = "") =
    this?.takeIf { it.isFinite() }?.let { "%.1f%s".format(java.util.Locale.US, it, unit) } ?: "Unavailable"

internal class CallStatsSampler {
    private data class Counters(val bytes: Double?, val frames: Double?, val timestampUs: Double)
    private var previous = emptyMap<String, Counters>()

    fun read(report: RTCStatsReport): CallNetworkStats {
        val all = report.statsMap
        // Repair streams may also report kind=video. Select the actual camera/screen RTP stream.
        val video = all.values.filter { it.video() && it.type in setOf("inbound-rtp", "outbound-rtp") }
        val outbound = video.filter { it.type == "outbound-rtp" }.maxByOrNull { it.value("framesEncoded") ?: -1.0 }
        val inbound = video.filter { it.type == "inbound-rtp" }.maxByOrNull { it.value("framesDecoded") ?: -1.0 }
        val transport = all[(outbound ?: inbound)?.members?.get("transportId") as? String]
            ?: all.values.firstOrNull { it.type == "transport" && it.members["selectedCandidatePairId"] != null }
        val pair = all[transport?.members?.get("selectedCandidatePairId") as? String]
        val local = all[pair?.members?.get("localCandidateId") as? String]
        val remote = all[pair?.members?.get("remoteCandidateId") as? String]
        val relay = listOfNotNull(local, remote).filter { it.members["candidateType"] == "relay" }
        val route = when {
            relay.isNotEmpty() -> "TURN relay"
            local != null && remote != null -> "Direct P2P"
            else -> "Unavailable"
        }
        val protocol = relay.mapNotNull { (it.members["relayProtocol"] ?: it.members["protocol"]) as? String }
            .distinct().joinToString(" / ").ifEmpty { local?.members?.get("protocol")?.toString() ?: "Unavailable" }
        val feedback = all[outbound?.members?.get("remoteId") as? String]
            ?: all.values.firstOrNull { it.type == "remote-inbound-rtp" && it.members["localId"] == outbound?.id && outbound != null }
        val available = pair?.value("availableOutgoingBitrate")?.takeIf { it > 0 }
        val rtt = (feedback?.value("roundTripTime") ?: pair?.value("currentRoundTripTime"))?.times(1000)
        val loss = feedback?.value("fractionLost")?.takeIf { it in 0.0..1.0 }
        val jitter = feedback?.value("jitter")?.times(1000)
        val limitation = outbound?.members?.get("qualityLimitationReason") as? String
        val current = mutableMapOf<String, Counters>()

        fun stream(rtp: RTCStats?, sending: Boolean): String {
            if (rtp == null) return "Unavailable"
            val bytes = rtp.value(if (sending) "bytesSent" else "bytesReceived")
            val frames = rtp.value(if (sending) "framesEncoded" else "framesDecoded")
            val old = previous[rtp.id]
            current[rtp.id] = Counters(bytes, frames, rtp.timestampUs)
            val seconds = old?.let { (rtp.timestampUs - it.timestampUs) / 1_000_000 }?.takeIf { it > 0 }
            fun rate(now: Double?, before: Double?): Double? =
                if (now != null && before != null && now >= before && seconds != null) (now - before) / seconds else null
            val bitrate = rate(bytes, old?.bytes)?.times(.008)
            val fps = rate(frames, old?.frames) ?: rtp.value("framesPerSecond")
            val width = rtp.value("frameWidth")?.toInt()
            val height = rtp.value("frameHeight")?.toInt()
            val resolution = if (width != null && height != null && width > 0 && height > 0 && fps != 0.0)
                "${width}×$height" else "Unavailable"
            val codec = all[rtp.members["codecId"] as? String]?.members?.get("mimeType") ?: "Unavailable"
            return "$resolution · ${fps.metric(" fps")} · ${bitrate.metric(" kbps")} · $codec"
        }

        val implementation = outbound?.members?.get("encoderImplementation")
        val efficiency = outbound?.members?.get("powerEfficientEncoder") as? Boolean
        val encoder = when {
            implementation != null && efficiency == true -> "$implementation (power-efficient)"
            implementation != null -> implementation.toString()
            efficiency == true -> "Power-efficient (implementation unavailable)"
            else -> "Unavailable"
        }
        val summary = listOf(
            "Outgoing: ${stream(outbound, true)}",
            "Incoming: ${stream(inbound, false)}",
            "Encoder: $encoder",
            "Route: $route · ${protocol.uppercase(java.util.Locale.ROOT)}",
            "Available send / receive: ${available?.div(1000).metric(" kbps")} / ${pair?.value("availableIncomingBitrate")?.div(1000).metric(" kbps")}",
            "RTT: ${rtt.metric(" ms")}",
            "Send / receive jitter: ${jitter.metric(" ms")} / ${inbound?.value("jitter")?.times(1000).metric(" ms")}",
            "Send loss: ${loss?.times(100).metric("%")} · Receive packets lost: ${inbound?.members?.get("packetsLost") ?: "Unavailable"}",
            "Dropped frames: ${inbound?.members?.get("framesDropped") ?: "Unavailable"} · Freezes: ${inbound?.members?.get("freezeCount") ?: "Unavailable"}",
            "Quality limit: ${limitation ?: "Unavailable"}",
        ).joinToString("\n")
        previous = current
        return CallNetworkStats(rtt, loss, jitter, available, limitation == "cpu", limitation, summary)
    }
}
