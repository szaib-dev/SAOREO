package com.codetic.saoreo

import org.junit.Assert.*
import org.junit.Test

class LyricsTest {
    @Test fun enhancedWordsAndOffsetsUsePlaybackMilliseconds() {
        val lines = parseLyrics("[offset:+150]\n[00:01.20]<00:01.20>Hello <00:01.75>world\n[00:03.00]Next")
        assertEquals(listOf(1350L, 3150L), lines.map { it.time })
        assertEquals("Hello world", lines.first().text)
        assertEquals(listOf(1350L, 1900L), lines.first().words.map { it.time })
        assertEquals(listOf("Hello ", "world"), lines.first().words.map { lines.first().text.substring(it.start, it.end) })
    }

    @Test fun matchingRejectsSimilarTitlesWrongPerformersAndVersions() {
        val song = LyricsMetadata("Hello", "Adele", "25", 295000)
        assertTrue(lyricsMatch(song, "Hello", "Adele", "25", 295.1))
        assertFalse(lyricsMatch(song, "Hello Again", "Adele", "25", 295.1))
        assertFalse(lyricsMatch(song, "Hello", "Lionel Richie", "25", 295.1))
        assertFalse(lyricsMatch(song, "Hello", "Adele", "25", 310.0))
        assertFalse(lyricsMatch(song, "Hello", "Adele", "Live", 295.1))
        assertFalse(lyricsMatch(song.copy(artist = ""), "Hello", "Adele", "25", 295.1))
    }

    @Test fun metadataCleanupPreservesVersionAndUserInformation() {
        val song = identifyLyricsMetadata("Rick Astley - Never Gonna Give You Up (Official Music Video).mp3", null, null, 213000)
        assertEquals("Rick Astley", song.artist)
        assertEquals("Never Gonna Give You Up", song.title)
        assertEquals("Hello (Live)", identifyLyricsMetadata("Hello (Live)", "Adele", null, 295000).title)
        assertEquals("My title", identifyLyricsMetadata("My title", "Known performer", null, 1000).title)
    }

    @Test fun plainLyricsHaveNoInventedTimeline() {
        assertTrue(parseLyrics("First\nSecond").all { it.time == null })
        assertEquals(listOf(1200L, 3456L), parseLyrics("[00:01.20][00:03.456]Repeat").map { it.time })
        assertTrue(parseLyrics("[ar:Artist]\n[ti:Title]").isEmpty())
    }
}
