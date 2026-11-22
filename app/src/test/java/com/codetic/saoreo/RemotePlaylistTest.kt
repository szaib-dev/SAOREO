package com.codetic.saoreo

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemotePlaylistTest {
    @Test fun youtubeDoesNotSilentlyTruncateAndDisablesUnavailableEntries() {
        val entries = JSONArray()
        repeat(151) { entries.put(JSONObject().put("id", "abcdefghijk").put("title", "Song $it")) }
        entries.put(JSONObject().put("id", "abcdefghijk").put("title", "[Private video]"))
        entries.put(JSONObject().put("title", "Deleted"))
        val playlist = youtubePlaylist(JSONObject().put("title", "My mix").put("entries", entries), "https://www.youtube.com/playlist?list=PLabcdefghijk")
        assertEquals(153, playlist.items.size)
        assertEquals(151, playlist.items.count { it.selectable })
        assertFalse(playlist.items.last().selectable)
        assertEquals("My mix", playlist.title)
    }

    @Test fun spotifyPreservesMetadataWithoutUsingPreviewAudioAsFullSongs() {
        val track = JSONObject().put("title", "Song").put("subtitle", "Artist")
            .put("uri", "spotify:track:1234567890123456789012").put("duration", 215000)
            .put("audioPreview", JSONObject().put("url", "https://p.scdn.co/preview.mp3"))
        val entity = JSONObject().put("name", "My Spotify mix").put("trackList", JSONArray().put(track))
            .put("coverArt", JSONObject().put("sources", JSONArray().put(JSONObject().put("url", "https://i.scdn.co/image/cover"))))
        val root = JSONObject().put("props", JSONObject().put("pageProps", JSONObject().put("state",
            JSONObject().put("data", JSONObject().put("entity", entity)))))
        val playlist = spotifyPlaylist("<script id=\"__NEXT_DATA__\" type=\"application/json\">$root</script>", "https://open.spotify.com/playlist/1234567890123456789012")
        assertEquals("My Spotify mix", playlist.title)
        assertEquals("https://i.scdn.co/image/cover", playlist.thumbnail)
        assertNull(playlist.items.single().link)
        assertTrue(playlist.items.single().selectable)
        assertEquals(215.0, playlist.items.single().duration!!, 0.0)
        assertTrue(playlist.notice!!.contains("part of a long playlist"))
    }

    @Test fun spotifyUrlRejectsOtherOriginsAndCredentials() {
        val path = "/playlist/1234567890123456789012"
        assertEquals("https://open.spotify.com$path", spotifyPlaylistUrl("Listen https://open.spotify.com$path?si=abc"))
        assertNull(spotifyPlaylistUrl("https://open.spotify.com.evil.test$path"))
        assertNull(spotifyPlaylistUrl("https://user@open.spotify.com$path"))
        assertNull(spotifyPlaylistUrl("https://open.spotify.com:9000$path"))
    }

    @Test fun matchingRejectsWrongVersionAndDuration() {
        val song = RemotePlaylistItem(0, "A beautiful song", null, "Artist", duration = 200.0)
        fun candidate(title: String, duration: Double) = RemotePlaylistItem(1, title,
            MediaLink("https://www.youtube.com/watch?v=abcdefghijk", Source.YOUTUBE), "Artist", duration = duration)
        assertNull(bestSongMatch(song, listOf(candidate("A beautiful song karaoke", 200.0))))
        assertNull(bestSongMatch(song, listOf(candidate("A beautiful song", 600.0))))
        val good = candidate("Artist - A beautiful song (official audio)", 202.0)
        assertEquals(good, bestSongMatch(song, listOf(candidate("A beautiful song live", 200.0), good)))
    }
}
