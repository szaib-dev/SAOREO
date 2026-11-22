package com.codetic.saoreo

import org.junit.Assert.*
import org.junit.Test

class LibraryAndPlaybackTest {
    @Test fun originComesFromPersistedSourceRatherThanPath() {
        assertEquals("IMPORTED", legacyOrigin("IMPORTED"))
        assertEquals("IMPORTED", legacyOrigin("SHARED"))
        assertEquals("CONVERTED", legacyOrigin("CONVERTED"))
        assertEquals("STATUS", legacyOrigin("WHATSAPP"))
        for (source in listOf("YOUTUBE", "X", "INSTAGRAM")) assertEquals("DOWNLOADED", legacyOrigin(source))
        val imported = Download("one", "content://documents/Music/SAOREO/song.wav", "IMPORTED", "Song")
        assertEquals("IMPORTED", imported.origin)
    }

    @Test fun pauseIsPersistentAndNotAnActiveTransfer() {
        val item = Download("one", "https://youtu.be/18NGQq7p3LY", "YOUTUBE", "Song", state = "PAUSED")
        assertFalse(item.active)
        assertTrue(item.copy(state = "DOWNLOADING").active)
    }

    @Test fun repeatCyclesAndSleepCancellationAreDeterministic() {
        assertEquals(2, nextRepeatMode(0))
        assertEquals(1, nextRepeatMode(2))
        assertEquals(0, nextRepeatMode(1))
        assertEquals(901000L, sleepDeadline(1000, 15))
        assertEquals(0L, sleepDeadline(1000, 0))
    }

    @Test fun localLyricsSupportMultipleTimestampsAndPlainText() {
        val rows = parseLyrics("[ar:Artist]\n[00:01.20][00:03.456]A line\n[00:02]Second line")
        assertEquals(listOf(1200L, 2000L, 3456L), rows.map { it.time })
        assertEquals(listOf("A line", "Second line", "A line"), rows.map { it.text })
        assertEquals(listOf(LyricLine(null, "Plain lyrics")), parseLyrics("Plain lyrics"))
        assertTrue(parseLyrics("").isEmpty())
    }
    @Test fun publicIpv6DoesNotBecomeALocal403() {
        assertTrue(PublicDns.isPublicAddress(java.net.InetAddress.getByName("2001:4860:4860::8888")))
        assertTrue(PublicDns.isPublicAddress(java.net.InetAddress.getByName("2404:6800:4007:80a::200e")))
        for (ip in listOf("127.0.0.1", "192.168.1.1", "::1", "fc00::1", "2001:db8::1", "2001::1", "2002::1"))
            assertFalse(ip, PublicDns.isPublicAddress(java.net.InetAddress.getByName(ip)))
    }
    @Test fun extractionAndCdnCannotSwitchIpFamilies() {
        val v4 = java.net.InetAddress.getByName("8.8.8.8")
        val v6 = java.net.InetAddress.getByName("2001:4860:4860::8888")
        assertEquals(listOf(v4, v6), routeCandidates(listOf(v6, v4), 0))
        assertEquals(listOf(v4), routeCandidates(listOf(v6, v4), 4))
        assertEquals(listOf(v6), routeCandidates(listOf(v6, v4), 6))
        assertTrue(routeCandidates(listOf(v4), 6).isEmpty())
    }
}
