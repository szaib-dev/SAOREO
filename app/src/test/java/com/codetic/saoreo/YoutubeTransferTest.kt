package com.codetic.saoreo

import org.junit.Assert.*
import org.junit.Test

class YoutubeTransferTest {
    @Test fun equivalentYouTubeFormsShareOneJobIdentity() {
        val canonical = "https://www.youtube.com/watch?v=18NGQq7p3LY"
        for (url in listOf(canonical, "https://youtu.be/18NGQq7p3LY?si=share",
            "https://www.youtube.com/shorts/18NGQq7p3LY?feature=share",
            "$canonical&list=PLexample&t=22")) {
            assertEquals(canonical, MediaLink.parse(url)?.metadataKey())
        }
    }

    @Test fun selectedResolutionUsesPlayablePairsAndAnExplicitFallback() {
        val command = youtubeSelection(Quality.P720, 360)
        assertTrue(command.startsWith("bestvideo[height<=360][ext=mp4][vcodec^=avc1]+bestaudio[ext=m4a]"))
        assertTrue(command.endsWith("worst[vcodec!=none][acodec!=none]"))
        assertEquals("bestaudio/best[vcodec!=none][acodec!=none]", youtubeSelection(Quality.AUDIO, null))
    }

    @Test fun diagnosticsContainOnlyAnAllowlistedReason() {
        val report = transferFailureReport(Source.YOUTUBE, "Downloading",
            java.io.IOException("from https://cdn.example/path?secret=123: HTTP Error 403: Forbidden"))
        assertEquals("YouTube · Downloading · HTTP 403", report)
        assertFalse(report.contains("secret"))
    }
}
