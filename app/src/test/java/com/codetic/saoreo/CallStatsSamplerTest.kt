package com.codetic.saoreo

import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.RTCStats
import org.webrtc.RTCStatsReport

class CallStatsSamplerTest {
    private fun report(us: Long, sent: Long, received: Long, frames: Long): RTCStatsReport {
        fun stat(type: String, id: String, vararg fields: Pair<String, Any>) =
            RTCStats(us, type, id, fields.toMap())
        val stats = listOf(
            stat("outbound-rtp", "out", "kind" to "video", "bytesSent" to sent,
                "framesEncoded" to frames, "frameWidth" to 1920, "frameHeight" to 1080),
            stat("inbound-rtp", "in", "kind" to "video", "bytesReceived" to received,
                "framesDecoded" to frames, "frameWidth" to 1280, "frameHeight" to 720),
            stat("transport", "transport", "selectedCandidatePairId" to "pair"),
            stat("candidate-pair", "pair", "localCandidateId" to "local", "remoteCandidateId" to "remote"),
            stat("local-candidate", "local", "candidateType" to "host", "protocol" to "udp"),
            stat("remote-candidate", "remote", "candidateType" to "relay", "protocol" to "udp"),
        )
        return RTCStatsReport(us, stats.associateBy { it.id })
    }

    @Test fun reportsSeparateMeasuredRatesAndDetectsRemoteRelay() {
        val sampler = CallStatsSampler()
        sampler.read(report(1_000_000, 0, 0, 0))
        val result = sampler.read(report(2_000_000, 500_000, 250_000, 24)).diagnostic
        assertTrue(result.contains("Outgoing: 1920×1080 · 24.0 fps · 4000.0 kbps"))
        assertTrue(result.contains("Incoming: 1280×720 · 24.0 fps · 2000.0 kbps"))
        assertTrue(result.contains("TURN relay · UDP"))
        assertTrue(result.contains("Encoder: Unavailable"))
    }
}
