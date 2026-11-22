package com.codetic.saoreo

import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidStorageTest {
    private val app = ApplicationProvider.getApplicationContext<SaoreoApp>()

    @Test fun pinnedEngineStartsWithAndroidPythonAndQuickJs() = runBlocking {
        assertEquals(Extractor.ENGINE_VERSION, app.extractor.engineVersion())
        val quickJs = File(app.applicationInfo.nativeLibraryDir, "libqjs.so")
        val process = ProcessBuilder(quickJs.absolutePath, "-e", "console.log(6 * 7)")
            .redirectErrorStream(true).start()
        assertTrue(process.waitFor(8, java.util.concurrent.TimeUnit.SECONDS))
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        assertEquals(output, 0, process.exitValue())
        assertEquals("42", output)
    }

    @Test fun savesSharedLocalMediaAndDeduplicatesByContent() = runBlocking {
        val file = File(app.cacheDir, "duplicate-${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff6857a3.toInt())
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        var input: Uri? = null
        var record: Download? = null
        try {
            publishMedia(app, file, "SAOREO duplicate input", "image/png") { input = it }
            val original = app.statuses.fromUri(input!!)
            val first = app.statuses.save(original, Source.WHATSAPP)
            val second = app.statuses.save(original, Source.WHATSAPP)
            record = app.dao.byUri(first.first.toString())
            assertTrue(first.second)
            assertFalse(second.second)
            assertEquals(first.first, second.first)
            assertEquals(Source.WHATSAPP.name, record?.source)
        } finally {
            input?.let { app.contentResolver.delete(it, null, null) }
            record?.let { app.downloads.remove(it, true) }
            file.delete()
        }
        Unit
    }

    @Test fun historySurvivesReopeningDatabase() = runBlocking {
        val name = "test-history-${UUID.randomUUID()}.db"
        val item = Download(UUID.randomUUID().toString(), "https://youtu.be/abc", "YOUTUBE", "Test media")
        try {
            val first = Room.databaseBuilder(app, DownloadDb::class.java, name).build()
            try {
                first.downloads().insert(item)
                first.downloads().update(item.copy(state = JobState.FAILED.name, error = "Test error"))
            } finally { first.close() }
            val reopened = Room.databaseBuilder(app, DownloadDb::class.java, name).build()
            try {
                assertEquals("Test error", reopened.downloads().find(item.id)?.error)
                reopened.downloads().remove(item.id)
                assertNull(reopened.downloads().find(item.id))
            } finally { reopened.close() }
        } finally { app.deleteDatabase(name) }
        Unit
    }

    @Test fun qualityPreferenceRoundTrips() = runBlocking {
        val original = app.preferences.flow.first().quality
        try {
            app.preferences.set(SettingKeys.quality, Quality.P720.name)
            assertEquals(Quality.P720, app.preferences.flow.first().quality)
        } finally { app.preferences.set(SettingKeys.quality, original.name) }
    }

    @Test fun ungrantedStatusFolderIsRejected() = runBlocking {
        val folder = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AFAKE")
        try {
            app.statuses.connect(Source.WHATSAPP, folder)
            fail("Granted a folder without a user-selected URI permission")
        } catch (_: java.io.IOException) { }
        catch (_: SecurityException) { }
    }

    @Test fun publishesActualPngAndRollsBackIfHistoryUpdateFails() = runBlocking {
        val file = File(app.cacheDir, "test-${UUID.randomUUID()}.png")
        val name = "SAOREO Test ${UUID.randomUUID()}"
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        var saved: Uri? = null
        var rolledBack: Uri? = null
        try {
            publishMedia(app, file, name, "image/png") { saved = it }
            assertTrue(isReadable(app, saved!!))
            try {
                publishMedia(app, file, "$name rollback", "image/png") {
                    rolledBack = it
                    error("Simulated history failure")
                }
                fail("Publish should propagate failure")
            } catch (_: IllegalStateException) { }
            assertFalse(isReadable(app, rolledBack!!))
        } finally {
            saved?.let { app.contentResolver.delete(it, null, null) }
            file.delete()
        }
        Unit
    }
}
