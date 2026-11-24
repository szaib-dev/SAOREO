package com.codetic.saoreo

import java.net.URI
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class TogetherIptvTest {
    @Test fun cleartextIsBlockedUntilConsentAndPreservesRangesAfterRedirect() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/movie"))
            server.enqueue(MockResponse().setResponseCode(206).setBody("234"))
            val uri = server.url("/redirect").toUri()
            assertThrows(IptvHttpConsentRequired::class.java) { IptvNetwork.open(uri, false) }
            assertEquals(0, server.requestCount)
            IptvNetwork.open(uri, true, "bytes=2-4").use {
                assertEquals(206, it.status)
                assertEquals("/movie", it.uri.path)
                assertArrayEquals("234".toByteArray(), it.body?.readBytes())
            }
            assertEquals(2, server.requestCount)
            assertEquals("bytes=2-4", server.takeRequest().getHeader("Range"))
            assertEquals("bytes=2-4", server.takeRequest().getHeader("Range"))
        }
    }

    @Test fun importsMixedCatalogWithRelativeUrlsAndLogos() {
        val list = """
            #EXTM3U
            #EXTINF:-1 group-title="News, Local" tvg-logo="../logos/news.png",Morning News
            ../live/account/secret/news.m3u8
            #EXTINF:-1 group-title="Movies",A film, part two
            https://iptv.example.com/movie/film.mp4
            #EXTINF:-1,Another channel
            http://iptv.example.com/live/here.ts
            #EXTINF:-1 group-title='Drama, Series',Episode 1
            https://iptv.example.com/series/account/secret/1.mp4
            #EXTINF:-1,Invalid
            file:///tmp/file
        """.trimIndent()
        val programs = parseIptvPlaylist(list.byteInputStream(), URI("https://iptv.example.com/lists/main.m3u"))
        assertEquals(4, programs.size)
        assertEquals("News, Local", programs[0].group)
        assertEquals("https://iptv.example.com/logos/news.png", programs[0].logo)
        assertEquals("https://iptv.example.com/live/account/secret/news.m3u8", programs[0].url)
        assertTrue(programs[0].media().live)
        assertTrue(programs[0].hls)
        assertEquals("A film, part two", programs[1].title)
        assertEquals("iptv", programs[1].media().kind)
        assertFalse(programs[1].media().live)
        assertEquals("http://iptv.example.com/live/here.ts", programs[2].url)
        assertEquals("Drama, Series", programs[3].group)
        assertEquals(IptvKind.SERIES, programs[3].kind)
        assertTrue(programs.all { it.media().source.matches(Regex("[a-f0-9]{64}")) })
    }

    @Test fun hlsManifestsArePlayableAsOneProgram() {
        val manifest = "#EXTM3U\n#EXT-X-TARGETDURATION:8\n#EXTINF:8,\nsegment.ts\n#EXT-X-ENDLIST"
        val uri = URI("https://iptv.example.com/live.m3u8")
        val program = parseIptvPlaylist(manifest.byteInputStream(), uri).single()
        assertEquals(uri.toString(), program.url)
        assertTrue(program.hls)
        assertFalse(program.media().live)
        val local = "content://picker/video"
        assertEquals(local, parseIptvPlaylist(manifest.byteInputStream(), localManifest = local).single().url)
    }

    @Test fun sharedReferencesMatchAcrossCredentialsAndDisambiguateDuplicateNames() {
        fun parse(user: String) = parseIptvPlaylist("""
            #EXTM3U
            #EXTINF:-1,News
            https://iptv.example.com/live/$user/pwd/101.ts
            #EXTINF:-1,News
            https://iptv.example.com/live/$user/pwd/102.ts
        """.trimIndent().byteInputStream()).map { it.media().source }
        assertEquals(parse("alice"), parse("bob"))
        assertEquals(2, parse("alice").distinct().size)
    }
}
