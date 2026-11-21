package com.codetic.saoreo

import org.junit.Assert.*
import org.junit.Test

class TranscriptProgressTest {
    @Test fun pastedYoutubeLinksResolveToOneVideo() {
        val expected = "https://www.youtube.com/watch?v=8KkKuTCFvzI"
        for (link in listOf(
            "Check this out! https://youtu.be/8KkKuTCFvzI?si=tracking",
            "youtube.com/shorts/8KkKuTCFvzI",
            "https://m.youtube.com/watch?list=PL123&v=8KkKuTCFvzI&t=12",
        )) {
            assertEquals(expected, transcriptLink(link)?.url)
        }
        assertNull(transcriptLink("https://www.youtube.com/watch?v=abc"))
        assertNull(transcriptLink("https://www.youtube.com/playlist?list=PL123"))
        assertNull(transcriptLink("https://youtube.com.attacker.example/watch?v=8KkKuTCFvzI"))
    }

    @Test fun jsonCaptionsJoinTimedSegmentsAndDiscardDuplicateEvents() {
        val payload = """
            {"events":[
              {"tStartMs":25},
              {"tStartMs":1000,"segs":[{"utf8":"السلام"},{"utf8":" علیکم"}]},
              {"tStartMs":1000,"segs":[{"utf8":"السلام علیکم"}]},
              {"tStartMs":2500,"segs":[{"utf8":"Second line"}]}
            ]}
        """.trimIndent()
        assertEquals(listOf(TranscriptLine(1000, "السلام علیکم"), TranscriptLine(2500, "Second line")),
            parseJsonCaptions(payload))
    }

    @Test fun vttRollingCaptionsKeepUnicodeAndRemoveRepeatedCues() {
        val vtt = """
            WEBVTT

            NOTE explanatory metadata

            00:01.000 --> 00:03.000 align:start
            <c>السلام علیکم</c>

            00:02.500 --> 00:04.000
            السلام علیکم
            World &amp; friends

            00:08.200 --> 00:07.000
            Invalid
        """.trimIndent()
        val result = Transcript("مقامی ویڈیو", parseTranscript(vtt))
        assertEquals(listOf(1000L, 2500L), result.lines.map { it.ms })
        assertEquals("السلام علیکم\nWorld & friends", result.text(false))
    }

    @Test fun srtOffsetsAndPlainTextKeepExactLines() {
        val result = parseTranscript("1\r\n00:00:01,500 --> 00:00:02,000\r\nHello\r\n", offset = 480_000L)
        assertEquals(listOf(TranscriptLine(481_500L, "Hello")), result)
        assertEquals(listOf(TranscriptLine(null, "اردو")), parseTranscript("\uFEFFاردو\n", plain = true))
    }

    @Test fun fullUnicodeExportIsIndependentOfVisibleRows() {
        val document = Transcript("اردو", List(10_000) { i -> TranscriptLine(i * 1000L, "عربی عربى $i") })
        val restored = document.text(false).toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8)
        assertEquals(10_000, restored.lineSequence().count())
        assertTrue(restored.endsWith("عربی عربى 9999"))
    }

    @Test fun unknownTotalsStayUnknownAndFinishingNeverShowsHundred() {
        assertEquals(-1, TransferProgress(bytes = 100).percent)
        assertNull(TransferProgress(bytes = 100, bytesPerSecond = 50).eta)
        assertEquals(38, TransferProgress(bytes = 45_600_000, total = 120_000_000).percent)
        assertEquals(31L, TransferProgress(bytes = 45_600_000, total = 120_000_000, bytesPerSecond = 2_400_000).eta)
        assertEquals(99, TransferProgress(bytes = 25, total = 25).percent)
    }
}
