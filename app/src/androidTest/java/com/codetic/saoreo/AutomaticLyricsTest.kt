package com.codetic.saoreo

import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class AutomaticLyricsTest {
    @Test fun completedAudioAutomaticallyFetchesDespitePlaylistAlbumAndCaches() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<SaoreoApp>()
        val file = File(app.cacheDir, "lyrics-metadata-fixture.wav")
        // A silent PCM fixture with a known duration tests metadata matching and scheduling.
        val size = 214 * 8000 * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(size + 36).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(size)
        file.outputStream().use { output ->
            output.write(header.array())
            val block = ByteArray(16000)
            repeat(214) { output.write(block) }
        }
        val ids = mutableListOf<String>()
        val media = mutableListOf<Uri>()
        val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        try {
            for (source in listOf("YOUTUBE", "IMPORTED")) {
                val id = UUID.randomUUID().toString()
                ids += id
                val title = "Rick Astley – Never Gonna Give You Up (Official Music Video)"
                val artist = if (source == "YOUTUBE") "Rick Astley" else null
                val audio = if (source == "YOUTUBE") publishMedia(app, file, id, "audio/wav") {}.also { media += it }
                    else Uri.fromFile(file)
                app.dao.insert(Download(id, "lyrics-test", source,
                    title, state = JobState.COMPLETE.name, galleryUri = audio.toString(), mime = "audio/wav",
                    artist = artist, album = "My favourite playlist"))
                val saved = withTimeout(90000) {
                    var cached = app.lyrics.dao.find(id)
                    while (cached == null) { delay(300); cached = app.lyrics.dao.find(id) }
                    cached
                }
                assertTrue("Background worker retrieved synchronized lyrics", saved.synced)
                assertTrue(parseLyrics(requireNotNull(saved.text)).any { it.time != null })
                if (source == "YOUTUBE") {
                    val sidecar = app.contentResolver.query(files, arrayOf("_id"),
                        "_display_name = ? AND relative_path = ?", arrayOf("$id.lrc", "Music/SAOREO/"), null)!!.use {
                        assertTrue("Lyrics file was saved beside the audio", it.moveToFirst())
                        Uri.withAppendedPath(files, it.getLong(0).toString()).also { uri -> media += uri }
                    }
                    assertEquals(saved.text, app.contentResolver.openInputStream(sidecar)!!.bufferedReader().use { it.readText() })
                }
                app.lyrics.fetch(id)
                assertEquals("Cached lyrics reused", saved, app.lyrics.dao.find(id))
            }
        } finally {
            for (id in ids) app.dao.remove(id)
            for (uri in media) app.contentResolver.delete(uri, null, null)
            file.delete()
        }
    }
}
