package com.codetic.saoreo

import org.junit.Assert.*
import org.junit.Test

class OfflineEncodingTest {
    @Test fun estimatesConstantBitrateWithLongDurations() {
        assertEquals(1_440_000L, estimatedMp3Bytes(60_000L, 192))
        assertEquals(576_000_000L, estimatedMp3Bytes(14_400_000L, 320))
        assertNull(estimatedMp3Bytes(0, 192))
        assertNull(estimatedMp3Bytes(Long.MAX_VALUE, 192))
    }

    @Test fun configurationEncodesOnlyAudioWithBoundedLocalProtocols() {
        for (rate in mp3Rates) {
            val args = mp3Arguments("/private/source video", "/private/out.mp3", rate)
            assertEquals("libmp3lame", args[args.indexOf("-c:a") + 1])
            assertEquals("file", args[args.indexOf("-protocol_whitelist") + 1])
            assertEquals("0:a:0", args[args.indexOf("-map") + 1])
            assertEquals("${rate}k", args[args.indexOf("-b:a") + 1])
            assertTrue(args.containsAll(listOf("-vn", "-sn", "-dn", "-n")))
        }
        assertTrue(runCatching { mp3Arguments("input", "output", 64) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun formatsHoursAndKeepsUnavailablePositionNonnegative() {
        assertEquals("1:01:01", playbackTime(3_661_000L))
        assertEquals("0:00", playbackTime(-1L))
    }
}
