package com.codetic.saoreo

import android.net.Uri
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
        try {
            for (source in listOf("YOUTUBE", "IMPORTED")) {
                val id = UUID.randomUUID().toString()
                ids += id
                app.dao.insert(Download(id, "lyrics-test", source,
                    "Rick Astley \u2013 Never Gonna Give You Up (Official Music Video)",
                    state = JobState.COMPLETE.name, galleryUri = Uri.fromFile(file).toString(), mime = "audio/wav",
                    album = "My favourite playlist"))
                val saved = withTimeout(90000) {
                    var cached = app.lyrics.dao.find(id)
                    while (cached == null) { delay(300); cached = app.lyrics.dao.find(id) }
                    cached
                }
                assertTrue("Background worker retrieved synchronized lyrics", saved.synced)
                assertTrue(parseLyrics(requireNotNull(saved.text)).any { it.time != null })
                app.lyrics.fetch(id)
                assertEquals("Cached lyrics reused", saved, app.lyrics.dao.find(id))
            }
        } finally {
            for (id in ids) app.dao.remove(id)
            file.delete()
        }
    }
}
