package com.codetic.saoreo

import org.junit.Assert.*
import org.junit.Test

class DownloadOutputTest {
    @Test fun usernamePrefixedReelAndSharedReelAreSupported() {
        for (path in listOf("creator/reel/Example/", "share/reel/Example/", "reel/Example/")) {
            assertEquals(Source.INSTAGRAM, MediaLink.parse("https://www.instagram.com/$path")?.source)
        }
        assertNull(MediaLink.parse("https://instagram.com/reels/audio/"))
        assertNull(MediaLink.parse("https://instagram.com/creator/reel/"))
    }

    @Test fun muxedInstagramVideoCanSupplyBothMp4AndMp3() {
        val options = outputChoices(listOf(Choice("instagram-video", 720, "mp4", null)), emptyList())
        assertEquals(listOf("mp4", "mp3"), options.map { it.extension })
        val audio = selectChoice(options, Quality.AUDIO, "mp4")!!
        assertEquals("mp3:instagram-video", audio.id)
        assertEquals("instagram-video", audio.selector)
        assertEquals("instagram-video", selectChoice(options, Quality.P720, "mp4")?.selector)
    }

    @Test fun adaptiveFormatsKeepTheExactVideoAndPreferredAudioTracks() {
        val video = Choice("v", 1080, "webm", ExpectedSize(100, false), hasAudio = false)
        val audio = Choice("a", null, "webm", ExpectedSize(20, false), audioOnly = true)
        val options = outputChoices(listOf(video), listOf(audio))
        assertEquals(listOf("mp4", "mp3"), options.map { it.extension })
        assertEquals("v+a", options[0].selector)
        assertTrue(options[0].requiresAudio)
        assertNull(options[0].size)
        assertEquals("a", options[1].selector)
    }
}
