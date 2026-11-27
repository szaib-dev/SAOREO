package com.codetic.saoreo

import org.junit.Assert.assertEquals
import org.junit.Test

class CallVideoQualityTest {
    private fun sample(bps: Double?, loss: Double = 0.0, cpu: Boolean = false) =
        CallNetworkStats(90.0, loss, 55.0, bps, cpu, if (cpu) "cpu" else null, "")

    @Test fun fullHdLowersFrameRateBeforeResolutionAndRecovers() {
        val quality = CallVideoQuality(CallQualityMode.SMART, 1080)
        repeat(12) { quality.update(sample(5_000_000.0)) }
        assertEquals(CallVideoProfile(1080, 30), quality.profile)
        repeat(2) { quality.update(sample(2_100_000.0)) }
        assertEquals(CallVideoProfile(1080, 24), quality.profile)
        repeat(2) { quality.update(sample(2_100_000.0)) }
        assertEquals(CallVideoProfile(1080, 15), quality.profile)
        repeat(2) { quality.update(sample(1_000_000.0)) }
        assertEquals(CallVideoProfile(720, 18), quality.profile)
        repeat(8) { quality.update(sample(5_000_000.0)) }
        assertEquals(CallVideoProfile(1080, 24), quality.profile)
    }

    @Test fun weakUploadDoesNotChangePartnersOutgoingProfile() {
        val weak = CallVideoQuality(CallQualityMode.MAXIMUM, 1080)
        val strong = CallVideoQuality(CallQualityMode.MAXIMUM, 1080)
        repeat(12) {
            weak.update(sample(450_000.0))
            strong.update(sample(6_000_000.0))
        }
        assertEquals(CallVideoProfile(480, 12), weak.profile)
        assertEquals(CallVideoProfile(1080, 30), strong.profile)
        repeat(2) { weak.update(sample(250_000.0)) }
        assertEquals(CallVideoProfile(360, 8), weak.profile)
        repeat(2) { weak.update(sample(100_000.0)) }
        assertEquals(CallVideoProfile(0, 0), weak.profile)
        repeat(4) { weak.update(sample(6_000_000.0, loss = .2)) }
        assertEquals(CallVideoProfile(360, 8), weak.profile)
        repeat(20) { weak.update(sample(6_000_000.0)) }
        assertEquals(CallVideoProfile(1080, 30), weak.profile)
        assertEquals(8_000_000, CallQualityMode.MAXIMUM.bitrateCap)
    }

    @Test fun alternatingSamplesDoNotCauseFlappingAndMissingEstimateDoesNotStrandVideo() {
        val quality = CallVideoQuality(CallQualityMode.SMART, 1080)
        repeat(10) {
            quality.update(sample(100_000.0, .15))
            quality.update(sample(5_000_000.0))
        }
        assertEquals(CallVideoProfile(720, 18), quality.profile)
        repeat(2) { quality.update(sample(100_000.0)) }
        repeat(5) { quality.update(sample(null)) }
        assertEquals(CallVideoProfile(360, 8), quality.profile)
    }

    @Test fun cameraAndManualCeilingsAreRespectedAndCpuCanDegrade() {
        val limitedCamera = CallVideoQuality(CallQualityMode.MAXIMUM, 720)
        val manual = CallVideoQuality(CallQualityMode.SD, 1080)
        repeat(20) {
            limitedCamera.update(sample(6_000_000.0))
            manual.update(sample(6_000_000.0))
        }
        assertEquals(720, limitedCamera.profile.height)
        assertEquals(480, manual.profile.height)
        repeat(2) { manual.update(sample(null, cpu = true)) }
        assertEquals(360, manual.profile.height)
    }

    @Test fun qhdNeedsMaximumModeNativeCaptureAndEnoughBandwidth() {
        val maximum = CallVideoQuality(CallQualityMode.MAXIMUM, 1440)
        val fullHdCamera = CallVideoQuality(CallQualityMode.MAXIMUM, 1080)
        val smart = CallVideoQuality(CallQualityMode.SMART, 1440)
        repeat(20) {
            maximum.update(sample(7_500_000.0))
            fullHdCamera.update(sample(7_500_000.0))
            smart.update(sample(7_500_000.0))
        }
        assertEquals(CallVideoProfile(1440, 24), maximum.profile)
        assertEquals(CallVideoProfile(1080, 30), fullHdCamera.profile)
        assertEquals(CallVideoProfile(1080, 30), smart.profile)
        repeat(2) { maximum.update(sample(4_500_000.0)) }
        assertEquals(CallVideoProfile(1080, 30), maximum.profile)
    }
}
