package com.codetic.saoreo

import org.junit.Assert.*
import org.junit.Test

class CollectionDownloadTest {
    @Test fun destinationPreferencesRemainIndependentInAllShareModes() {
        val saved = Settings(defaultFolder = "gym", lastFolder = "chest", rememberFolder = true)
        for (folders in listOf(false, true)) {
            for (instant in listOf(false, true)) {
                val settings = saved.copy(folderOrganization = folders, autoShare = instant)
                val expected = if (!folders) null else if (instant) "gym" else "chest"
                assertEquals(expected, settings.destination(instant))
                assertEquals("gym", settings.defaultFolder)
                assertEquals("chest", settings.lastFolder)
            }
        }
        assertNull(saved.copy(folderOrganization = true, lastFolder = "").destination())
        assertEquals("gym", saved.copy(folderOrganization = true, rememberFolder = false).destination())
        assertFalse(Settings().autoShare)
        assertFalse(Settings().folderOrganization)
    }

    @Test fun nestedFolderPathsKeepParentsSeparate() {
        val folders = listOf(DownloadFolder("gym", "", "Gym"), DownloadFolder("work", "", "Work"),
            DownloadFolder("chest", "gym", "Chest"), DownloadFolder("second", "work", "Chest"))
        assertEquals("Gym / Chest", folderLabel(folders, "chest"))
        assertEquals("Work / Chest", folderLabel(folders, "second"))
        assertEquals("Standard location", folderLabel(folders, null))
        assertThrows(IllegalArgumentException::class.java) { collectionName("../Gym") }
        assertEquals("آڈیو", collectionName(" آڈیو "))
    }

    @Test fun parsesClipsToMillisecondsAndRejectsInvalidRanges() {
        assertEquals(MediaSection(80_000, 125_500), sectionFromText("01:20", "02:05.500", 150.0))
        assertEquals(3_600_003L, sectionTime("1:00:00.003"))
        assertNull(sectionTime("00:61"))
        assertNull(sectionTime("-1"))
        assertNull(sectionFromText("1:00", "0:20", 100.0))
        assertNull(sectionFromText("1:00", "01:20", 75.0))
    }

    @Test fun sectionCommandEncodesMp3AndSynchronizedVideoWithBoundedInputs() {
        val clip = MediaSection(80_000, 125_000)
        val audio = sectionCommand("/tmp/input.mp4", "/tmp/output.mp3", clip, true, 256, false)
        assertTrue(audio.containsAll(listOf("-vn", "libmp3lame", "256k", "80.000", "45.000")))
        assertEquals("file", audio[audio.indexOf("-protocol_whitelist") + 1])
        val video = sectionCommand("http://127.0.0.1:4444/source.mp4", "/tmp/output.mp4", clip, false, 192, true)
        assertTrue(video.containsAll(listOf("libx264", "aac", "0:v:0", "0:a:0?", "+faststart")))
        assertEquals("http,tcp", video[video.indexOf("-protocol_whitelist") + 1])
        assertEquals(901L, verifiedRange("bytes 0-255/901", 0, 255, 901))
        assertNull(verifiedRange("bytes 0-255/902", 0, 255, 901))
        assertNull(verifiedRange("bytes 0-210/901", 0, 255, 901))
    }

    @Test fun playlistLinksRequireSupportedOriginAndCanonicalIdentifier() {
        val canonical = "https://www.youtube.com/playlist?list=PLabcdefgh_123"
        assertEquals(canonical, supportedPlaylist("Watch https://youtu.be/test?list=PLabcdefgh_123.")?.url)
        assertNull(supportedPlaylist("https://www.youtube.com.example.net/playlist?list=PLabcdefgh_123"))
        assertNull(supportedPlaylist("https://user@www.youtube.com/playlist?list=PLabcdefgh_123"))
        assertNull(supportedPlaylist("https://www.youtube.com/playlist?list=abc"))
    }

    @Test fun configuredQualityFallsBackWithinSelectedMediaType() {
        val video = Choice("small", 1080, "mp4", null)
        val large = Choice("large", 2160, "mp4", null)
        val audio = Choice("audio", null, "mp3", null, audioOnly = true)
        val choices = listOf(large, audio, video)
        assertEquals(audio, preferredDownload(choices, Quality.AUDIO))
        assertEquals(video, preferredDownload(choices, Quality.entries.first { (it.cap ?: 5000) < 1080 }))
        assertNull(preferredDownload(listOf(video), Quality.AUDIO))
    }
}
