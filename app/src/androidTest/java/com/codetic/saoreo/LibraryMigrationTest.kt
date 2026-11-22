package com.codetic.saoreo

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), DownloadDb::class.java)

    @Test fun migrationPreservesLibraryAndExcludesImportsFromDownloads() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<SaoreoApp>()
        val name = "origin-migration-test"
        app.deleteDatabase(name)
        helper.createDatabase(name, 2).use { db ->
            for ((id, source, mime) in listOf(Triple("import", "IMPORTED", "audio/wav"), Triple("download", "YOUTUBE", "audio/mpeg"),
                Triple("video", "IMPORTED", "video/mp4"), Triple("converted", "CONVERTED", "audio/mpeg"))) {
                db.execSQL("INSERT INTO downloads (id,url,source,title,quality,state,progress,galleryUri,mime,created,hiddenFromHistory) VALUES (?,?,?,?,?,'COMPLETE',100,?,?,1,0)",
                    arrayOf(id, "content://media/$id", source, "Track $id", "BEST", "content://media/$id", mime))
            }
            db.execSQL("INSERT INTO playlists VALUES ('mix','My mix')")
            db.execSQL("INSERT INTO playlist_tracks VALUES ('mix','import',1)")
            db.execSQL("INSERT INTO song_state VALUES ('import',1,123)")
        }
        helper.runMigrationsAndValidate(name, 4, true, libraryMigration, playlistArtworkMigration).close()
        val db = Room.databaseBuilder(app, DownloadDb::class.java, name).addMigrations(libraryMigration, playlistArtworkMigration).build()
        try {
            assertEquals(listOf("download"), db.downloads().observe().first().map { it.id })
            assertEquals(3, db.downloads().audio().first().size)
            assertEquals("IMPORTED", db.downloads().find("import")?.origin)
            assertEquals(1, db.downloads().videoLibrary().first().size)
            assertEquals("mix", db.collections().tracks().first().single().playlist)
            assertNull(db.collections().playlist("mix")?.thumbnail)
            assertNull(db.collections().playlist("mix")?.sourceUrl)
            assertTrue(db.collections().songStates().first().single().liked)
            assertEquals(1, db.collections().songStates().first().single().playCount)
            db.downloads().hideAudioHistory(); db.downloads().clear()
            assertNotNull(db.downloads().find("import"))
            assertEquals(3, db.downloads().audio().first().size)
        } finally { db.close(); app.deleteDatabase(name) }
    }
}
