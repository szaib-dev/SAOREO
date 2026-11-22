package com.codetic.saoreo

import org.junit.Assert.*
import org.junit.Test

class MediaTest {
    @Test fun recognizesEachPlatformBeforeExtraction() {
        val links = mapOf(
            "https://youtu.be/abc" to Source.YOUTUBE,
            "https://m.youtube.com/shorts/id" to Source.YOUTUBE,
            "https://www.instagram.com/reel/id/" to Source.INSTAGRAM,
            "https://vm.tiktok.com/id/" to Source.TIKTOK,
            "https://mobile.twitter.com/user/status/1" to Source.X,
            "https://x.com/user/status/1" to Source.X,
            "https://www.snapchat.com/spotlight/id" to Source.SNAPCHAT,
            "https://pin.it/id" to Source.PINTEREST,
            "https://www.pinterest.co.uk/pin/2345/" to Source.PINTEREST,
        )
        links.forEach { (link, source) -> assertEquals(link, source, MediaLink.parse(link)?.source) }
    }

    @Test fun shareCaptionsAndFragments() {
        val text = "Watch this (https://youtu.be/abc?t=4#fragment)!"
        assertEquals("https://youtu.be/abc?t=4", MediaLink.fromText(text)?.url)
        assertEquals(Source.TIKTOK,
            MediaLink.fromText("https://not-a-platform.example/ https://vm.tiktok.com/short")?.source)
    }

    @Test fun malformedInputsAndUnexpectedDestinationsAreRejected() {
        listOf("https://youtube.com.evil.org/x", "https://youtube.com@127.0.0.1/a",
            "https://127.0.0.1/a", "https://youtu.be:444/x", "content://youtube.com/x",
            "https://youtu.be/%", "https://youtu.be\\evil/a", "https://www.snapchat.com/add/user",
            "https://youtu.be/", "https://www.youtube.com/playlist?list=abc",
            "https://www.pinterest.com/example/board/", "https://x.com/example").forEach { assertNull(it, MediaLink.parse(it)) }
        assertNull(MediaLink.fromText("a".repeat(16_385)))
    }

    @Test fun capsNeverRoundUpOrTreatUnknownHeightAsSafe() {
        val options = listOf(
            Choice("1", 2160, "webm", null),
            Choice("2", 720, "mp4", null),
            Choice("3", 480, "mp4", null),
            Choice("4", null, "mp4", null),
            Choice("5", null, "m4a", null, audioOnly = true),
        )
        assertEquals(720, selectChoice(options, Quality.P1080, "mp4")?.height)
        assertEquals(480, selectChoice(options, Quality.P480, "mp4")?.height)
        assertEquals(2160, selectChoice(options, Quality.BEST, "mp4")?.height)
        assertNull(selectChoice(options, Quality.P360, "mp4"))
        assertTrue(selectChoice(options, Quality.AUDIO, "mp4")!!.audioOnly)
    }

    @Test fun estimatesPreferExactThenApproximateThenBitrate() {
        assertEquals(ExpectedSize(1200, false), expectedSize(1200, 1440, 100.0, 20.0))
        assertEquals(ExpectedSize(1440, true), expectedSize(0, 1440, 100.0, 20.0))
        assertEquals(ExpectedSize(250_000, true), expectedSize(0, 0, 100.0, 20.0))
        assertNull(expectedSize(0, 0, Double.POSITIVE_INFINITY, 20.0))
        assertNull(expectedSize(0, 0, 100.0, 0.0))
    }

    @Test fun pinterestUsesDeclaredOriginalsAndExcludesVideoPosters() {
        val image = """
            <meta content='A &amp; B' property='og:title'>
            <meta property="og:image" content="https://i.pinimg.com/originals/a.jpg">
            <meta property="og:image" content="https://i.pinimg.com/originals/b.webp">
            <meta property="og:image" content="https://i.pinimg.com/236x/c.jpg">
            <meta property="og:image" content="https://i.pinimg.com.evil.org/originals/d.png">
        """.trimIndent()
        val preview = pinImagePreview(image)!!
        assertEquals("A & B", preview.title)
        assertEquals(listOf("jpg", "webp"), preview.choices.map { it.extension })
        assertNull(pinImagePreview(image + "<meta property='og:video' content='https://v.pinimg.com/a.mp4'>"))
        assertNull(pinImagePreview(image + "<script data-test-id='video-snippet' type='application/ld+json'>"))
        val resized = "<meta property='og:image' content='https://i.pinimg.com/736x/key.jpg'>"
        val declared = """{"url":"https://i.pinimg.com/originals/key.jpg"}"""
        assertEquals("https://i.pinimg.com/originals/key.jpg",
            pinImagePreview(resized + declared)?.choices?.single()?.imageUrl)
    }

    @Test fun spotlightUsesOnlyDeclaredMp4FromSnapchatCdn() {
        val html = """
            <meta property="og:video:secure_url" content="https://cf-st.sc-cdn.net/d/public?x=1&amp;y=2">
            <meta property="og:video:type" content="video/mp4">
            <meta property="og:video:width" content="540">
            <meta property="og:video:height" content="960">
        """.trimIndent()
        val option = spotlightPreview(html)?.choices?.single()
        assertEquals(540, option?.height)
        assertEquals("https://cf-st.sc-cdn.net/d/public?x=1&y=2", option?.directVideoUrl)
        assertNull(spotlightPreview(html.replace("cf-st.sc-cdn.net", "cf-st.sc-cdn.net.example")))
        assertNull(spotlightPreview(html.replace("video/mp4", "text/html")))
    }

    @Test fun filenamesAreSafeAndErrorsAreClassified() {
        assertFalse(safeName("../sdcard/a\u0000.jpg").contains('/'))
        assertEquals("SAOREO media", safeName(".."))
        assertEquals(90, safeName("x".repeat(500)).length)
        assertEquals("This media is restricted or requires signing in.", mediaFailure("Please sign in"))
    }

    @Test fun literalPrivateDestinationsAreRejectedBeforeDns() {
        listOf("localhost", "127.0.0.1", "192.168.0.1", "fd00::1", "router.local").forEach {
            try { PublicDns.lookup(it); fail("Accepted $it") }
            catch (_: java.net.UnknownHostException) { }
        }
    }
    @Test fun instagramPlaylistMetadataUsesTheVideoEntry() {
        val root = org.json.JSONObject("""{"title":"Public reel","entries":[{"id":"image"},{"formats":[{"format_id":"0","url":"https://cdn.example/video.mp4"}]}]}""")
        val selected = mediaMetadataObject(root)
        assertEquals("0", selected.getJSONArray("formats").getJSONObject(0).getString("format_id"))
        assertEquals("Public reel", selected.getString("title"))
    }
}
