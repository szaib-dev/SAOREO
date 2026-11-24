package com.codetic.saoreo

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class TogetherIptvTest {
    @Test fun importsChannelsAndMoviesIntoExistingStreamFormat() {
        val list = """
            #EXTM3U
            #EXTINF:-1 group-title="News, Local",Morning News
            ../live/news.m3u8
            #EXTINF:-1 group-title="Movies",A film, part two
            https://iptv.example.com/movie/film.mp4
            #EXTINF:-1,Insecure channel
            http://iptv.example.com/insecure.m3u8
        """.trimIndent()
        val programs = parseIptvPlaylist(list.byteInputStream(), URI("https://iptv.example.com/lists/main.m3u"))
        assertEquals(2, programs.size)
        assertEquals("News, Local", programs[0].group)
        assertEquals("https://iptv.example.com/live/news.m3u8", programs[0].media().source)
        assertTrue(programs[0].media().live)
        assertEquals("A film, part two", programs[1].media().title)
        assertEquals("stream", programs[1].media().kind)
        assertFalse(programs[1].media().live)
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsHlsMediaSegmentsAsPlaylistEntries() {
        parseIptvPlaylist("#EXTM3U\n#EXT-X-TARGETDURATION:8\n#EXTINF:8,\nsegment.ts".byteInputStream())
    }
}
