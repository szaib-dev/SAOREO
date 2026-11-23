package com.codetic.saoreo

import org.junit.Assert.assertEquals
import org.junit.Test

class CallVideoQualityTest {
    private fun sample(bps: Double?, loss: Double = 0.0, tcp: Boolean = false) =
        CallNetworkStats(90.0, loss, 55.0, bps, false, tcp, "")

    @Test fun reducesVideoAfterSustainedLossAndClimbsAfterRecovery() {
        val quality = CallVideoQuality()
        assertEquals(720, quality.update(sample(230_000.0, 0.20), true).height)
        assertEquals(240, quality.update(sample(230_000.0, 0.20), true).height)
        repeat(11) { quality.update(sample(1_800_000.0), true) }
        assertEquals(720, quality.update(sample(1_800_000.0), true).height)
    }

    @Test fun fullHdRequiresHeadroomHardwareAndSustainedHealthyFeedback() {
        val capable = CallVideoQuality()
        repeat(9) { assertEquals(720, capable.update(sample(4_200_000.0), true).height) }
        assertEquals(1080, capable.update(sample(4_200_000.0), true).height)
        val restricted = CallVideoQuality()
        repeat(12) { assertEquals(720, restricted.update(sample(4_200_000.0, tcp = true), true).height) }
        repeat(12) { assertEquals(720, restricted.update(sample(4_200_000.0), false).height) }
    }
}
