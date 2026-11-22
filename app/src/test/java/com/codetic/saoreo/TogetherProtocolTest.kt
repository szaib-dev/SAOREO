package com.codetic.saoreo

import org.junit.Assert.*
import org.junit.Test

class TogetherProtocolTest {
    @Test fun timelineAdvancesOnlyWhenPlaying() {
        assertEquals(15000, SharedTimeline(10000, 1000, true).target(6000))
        assertEquals(10000, SharedTimeline(10000, 1000, false).target(6000))
        assertEquals(10000, SharedTimeline(10000, 1000, true).target(500))
    }
    @Test fun driftCorrectionHasTolerance() {
        val timeline = SharedTimeline(10000, 1000, true)
        assertFalse(timeline.needsSeek(14500, 6000))
        assertTrue(timeline.needsSeek(12000, 6000))
    }
    @Test fun acceptsYoutubeWatchShortsAndShareLinks() {
        for (url in listOf("https://www.youtube.com/watch?v=aqz-KE-bpKQ&t=1", "https://youtu.be/aqz-KE-bpKQ?si=x", "https://youtube.com/shorts/aqz-KE-bpKQ")) {
            assertEquals("aqz-KE-bpKQ", TogetherSources.youtube(url))
        }
        assertNull(TogetherSources.youtube("https://youtube.com.evil.example/watch?v=aqz-KE-bpKQ"))
        assertNull(TogetherSources.youtube("https://youtube.com/watch?v=invalid"))
    }
    @Test fun serverRequiresHttpsAndNoEmbeddedCredentials() {
        assertEquals("https://example.com:8765", TogetherSources.endpoint("https://example.com:8765/together/"))
        assertNull(TogetherSources.endpoint("http://example.com"))
        assertNull(TogetherSources.endpoint("https://user:pass@example.com"))
        assertNull(TogetherSources.endpoint("https://example.com?secret=x"))
    }
    @Test fun inviteRequiresFullSecret() {
        val code = "0123456789ab." + "a".repeat(32)
        assertEquals(code, TogetherSources.invite("Join me\nhttps://example.com\n$code"))
        assertEquals(code, TogetherSources.invite("saoreo://together/join?invite=$code"))
        assertNull(TogetherSources.invite("0123456789ab.short"))
    }
}
