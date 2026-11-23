package com.codetic.saoreo

import org.webrtc.RTCStats
import org.webrtc.RTCStatsReport

internal data class CallVideoProfile(val height: Int, val fps: Int) {
    companion object {
        val levels = listOf(CallVideoProfile(240, 12), CallVideoProfile(360, 18),
            CallVideoProfile(540, 24), CallVideoProfile(720, 30), CallVideoProfile(1080, 30))
    }
}

/** Keeps libwebrtc in charge of congestion control and adjusts the camera's encoding envelope. */
internal class CallVideoQuality {
    private var level = 3
    private var stableSamples = 0
    private var stressedSamples = 0

    fun update(stats: CallNetworkStats, fullHd: Boolean): CallVideoProfile {
        val estimate = stats.availableVideoBps
        val limit = when {
            estimate == null -> 3
            estimate < 320_000 -> 0
            estimate < 650_000 -> 1
            estimate < 1_200_000 -> 2
            estimate < 3_400_000 || !fullHd || stats.viaTcp -> 3
            else -> 4
        }
        val stressed = (stats.loss ?: 0.0) >= 0.06 ||
            ((stats.rttMs ?: 0.0) > 450 && (stats.jitterMs ?: 0.0) > 45) || stats.encoderLimited
        val target = if (stressed) minOf(limit, (level - 1).coerceAtLeast(0)) else limit
        if (target < level) {
            stableSamples = 0
            if (++stressedSamples >= 2) {
                level = target
                stressedSamples = 0
            }
        } else {
            stressedSamples = 0
            val clear = estimate != null && (stats.loss ?: 0.0) < 0.025 &&
                (stats.rttMs ?: 0.0) < 320 && (stats.jitterMs ?: 0.0) < 30 && !stats.encoderLimited
            if (target > level && clear) {
                if (++stableSamples >= if (level == 3) 10 else 4) {
                    level++
                    stableSamples = 0
                }
            } else stableSamples = 0
        }
        return CallVideoProfile.levels[level]
    }
}

internal data class CallNetworkStats(
    val rttMs: Double?, val loss: Double?, val jitterMs: Double?, val availableVideoBps: Double?,
    val encoderLimited: Boolean, val viaTcp: Boolean, val diagnostic: String,
)

private fun RTCStats.value(key: String) = (members[key] as? Number)?.toDouble()
private fun RTCStats.video() = (members["kind"] ?: members["mediaType"]) == "video"

internal class CallStatsSampler {
    private var previousBytes: Double? = null
    private var previousStamp = 0.0
    private var previousOutboundId: String? = null

    fun read(report: RTCStatsReport): CallNetworkStats {
        val all = report.statsMap
        val transport = all.values.firstOrNull { it.type == "transport" && it.members["selectedCandidatePairId"] != null }
        val pair = all[transport?.members?.get("selectedCandidatePairId") as? String]
        val local = all[pair?.members?.get("localCandidateId") as? String]
        val relay = local?.members?.get("candidateType") == "relay"
        val protocol = if (relay) local?.members?.get("relayProtocol") ?: local?.members?.get("protocol") else local?.members?.get("protocol")
        val outbound = all.values.firstOrNull { it.type == "outbound-rtp" && it.video() }
        val feedback = all[outbound?.members?.get("remoteId") as? String]
            ?: all.values.firstOrNull { it.type == "remote-inbound-rtp" && it.video() }
        val inbound = all.values.firstOrNull { it.type == "inbound-rtp" && it.video() }
        val audio = all.values.firstOrNull { it.type == "inbound-rtp" && it.members["kind"] == "audio" }
        val available = pair?.value("availableOutgoingBitrate")?.takeIf { it > 0 }
        val rtt = (feedback?.value("roundTripTime") ?: pair?.value("currentRoundTripTime"))?.times(1000)
        val loss = feedback?.value("fractionLost")?.takeIf { it in 0.0..1.0 }
        val jitter = feedback?.value("jitter")?.times(1000)
        val bytes = outbound?.value("bytesSent")
        val elapsed = report.timestampUs - previousStamp
        val actual = if (bytes != null && previousBytes != null && outbound.id == previousOutboundId && elapsed > 0)
            ((bytes - previousBytes!!) * 8_000_000 / elapsed).coerceAtLeast(0.0).toLong() else null
        previousBytes = bytes
        previousStamp = report.timestampUs
        previousOutboundId = outbound?.id
        val codec = all[outbound?.members?.get("codecId") as? String]?.members?.get("mimeType")
        val summary = "route=${if (relay) "relay" else local?.members?.get("candidateType")}/$protocol " +
            "rttMs=$rtt loss=$loss sendJitterMs=$jitter receiveJitterMs=${inbound?.value("jitter")?.times(1000)} " +
            "availableBps=$available encoderBps=$actual codec=$codec encoder=${outbound?.members?.get("encoderImplementation")} " +
            "limit=${outbound?.members?.get("qualityLimitationReason")} " +
            "send=${outbound?.value("frameWidth")}x${outbound?.value("frameHeight")}@${outbound?.value("framesPerSecond")} " +
            "receive=${inbound?.value("frameWidth")}x${inbound?.value("frameHeight")}@${inbound?.value("framesPerSecond")} " +
            "encoded=${outbound?.value("framesEncoded")} decoded=${inbound?.value("framesDecoded")} " +
            "dropped=${inbound?.value("framesDropped")} freezes=${inbound?.value("freezeCount")} " +
            "videoPacketsLost=${inbound?.value("packetsLost")} audioPacketsLost=${audio?.value("packetsLost")} " +
            "audioConcealedSamples=${audio?.value("concealedSamples")} dtls=${transport?.members?.get("dtlsState")}"
        return CallNetworkStats(rtt, loss, jitter, available, outbound?.members?.get("qualityLimitationReason") == "cpu",
            protocol == "tcp" || protocol == "tls", summary)
    }
}
