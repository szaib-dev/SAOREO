package com.codetic.saoreo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CollectionStorageTest {
    @Test fun foldersAndPlaylistReferencesSurviveReopening() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<SaoreoApp>()
        val name = "collection-test-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(app, DownloadDb::class.java, name).build()
        val song = Download(UUID.randomUUID().toString(), "https://youtu.be/test", Source.YOUTUBE.name, "Track",
            state = JobState.COMPLETE.name, galleryUri = "content://media/external/audio/media/12345", mime = "audio/mpeg")
        suspend fun withDb(block: suspend (DownloadDb) -> Unit) {
            val db = open()
            try { block(db) } finally { db.close() }
        }
        try {
            withDb { db ->
                val dao = db.collections()
                dao.insert(DownloadFolder("gym", "", "Gym"))
                dao.insert(DownloadFolder("chest", "gym", "Chest"))
                dao.insert(MusicPlaylist("easy", "Easy"))
                dao.insert(MusicPlaylist("heavy", "Heavy", "https://i.scdn.co/image/cover", "https://open.spotify.com/playlist/1234567890123456789012"))
                db.downloads().insert(song)
                db.downloads().move(song.id, "chest")
                dao.add(PlaylistTrack("easy", song.id))
                dao.add(PlaylistTrack("easy", song.id))
                dao.add(PlaylistTrack("heavy", song.id))
                assertEquals(listOf("easy", "heavy"), dao.orderedPlaylistIds())
                dao.setPlaylistPosition("heavy", 0)
                dao.setPlaylistPosition("easy", 1)
            }
            withDb { db ->
                val dao = db.collections()
                assertEquals(listOf("heavy", "easy"), dao.playlists().first().map { it.id })
                dao.insert(MusicPlaylist("new", "A New Playlist"))
                assertEquals(listOf("heavy", "easy", "new"), dao.orderedPlaylistIds())
                assertEquals("Gym / Chest", folderLabel(dao.folders().first(), db.downloads().find(song.id)?.folder))
                assertEquals(2, dao.tracks().first().size)
                assertEquals(listOf(song.galleryUri), db.downloads().audio().first().map { it.galleryUri })
                dao.renamePlaylist("heavy", "Gym Mix")
                assertEquals("Gym Mix", dao.playlist("heavy")?.name)
                assertEquals("https://i.scdn.co/image/cover", dao.playlist("heavy")?.thumbnail)
                assertEquals("https://open.spotify.com/playlist/1234567890123456789012", dao.playlist("heavy")?.sourceUrl)
                dao.deletePlaylist("easy")
                assertEquals(listOf("heavy"), dao.tracks().first().map { it.playlist })
                db.downloads().hideAudioHistory()
                db.downloads().clear()
                assertTrue(db.downloads().observe().first().isEmpty())
                assertNotNull(db.downloads().find(song.id))
                assertEquals(1, dao.tracks().first().size)
            }
        } finally { app.deleteDatabase(name) }
    }
}
