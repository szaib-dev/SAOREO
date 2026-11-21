package com.codetic.saoreo

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID

data class LocalMedia(
    val uri: Uri, val name: String, val mime: String, val bytes: Long, val modified: Long,
) {
    val video: Boolean get() = mime.startsWith("video/")
}

class StatusStore(private val app: SaoreoApp) {
    suspend fun connect(source: Source, uri: Uri?, write: Boolean = false) = withContext(Dispatchers.IO) {
        val settings = app.preferences.flow.first()
        val previous = if (source == Source.WHATSAPP) settings.whatsapp else settings.business
        val other = if (source == Source.WHATSAPP) settings.business else settings.whatsapp
        if (uri != null) {
            require(uri.scheme == "content")
            val folder = DocumentFile.fromTreeUri(app, uri) ?: throw IOException("Folder unavailable")
            // Accept the status directory itself. Never recursively inspect a broad media folder.
            if (!folder.isDirectory || !folder.canRead()) throw IOException("Folder unavailable")
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (write) runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
        app.preferences.set(
            if (source == Source.WHATSAPP) SettingKeys.whatsapp else SettingKeys.business,
            uri?.toString(),
        )
        if (previous != null && previous != uri?.toString() && previous != other) {
            for (mode in listOf(Intent.FLAG_GRANT_READ_URI_PERMISSION, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) {
                runCatching { app.contentResolver.releasePersistableUriPermission(Uri.parse(previous), mode) }
            }
        }
    }

    suspend fun list(folder: String?): List<LocalMedia> = withContext(Dispatchers.IO) {
        val uri = folder?.let(Uri::parse) ?: return@withContext emptyList()
        require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri))
        val root = DocumentsContract.getTreeDocumentId(uri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, root)
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
        val results: List<LocalMedia> = app.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
            buildList<LocalMedia> {
                while (cursor.moveToNext()) {
                    if (cursor.getString(2) == Document.MIME_TYPE_DIR) continue
                    val name = cursor.getString(1) ?: continue
                    val mime = mimeFor(name.substringAfterLast('.')) ?: continue
                    if (!mime.startsWith("image/") && !mime.startsWith("video/")) continue
                    val child = DocumentsContract.buildDocumentUriUsingTree(uri, cursor.getString(0))
                    add(LocalMedia(child, name, mime, cursor.getLong(3), cursor.getLong(4)))
                }
            }
        } ?: throw IOException("Folder permission lost")
        results.sortedByDescending { it.modified }
    }

    private val viewSettings get() = app.getSharedPreferences("status_view", android.content.Context.MODE_PRIVATE)
    private fun viewKey(source: Source, uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray())
        return source.name + digest.joinToString("") { "%02x".format(it) }
    }

    internal fun hidden(source: Source, uri: Uri) = viewSettings.getBoolean(viewKey(source, uri), false)
    internal fun hide(source: Source, uri: Uri) = viewSettings.edit().putBoolean(viewKey(source, uri), true).apply()
    internal fun showHidden() {
        val editor = viewSettings.edit()
        viewSettings.all.keys.filter { !it.startsWith("imported_") }.forEach(editor::remove)
        editor.apply()
    }

    internal fun canDelete(folder: String?, uri: Uri): Boolean = runCatching {
        val tree = Uri.parse(folder ?: return false)
        uri.scheme == "content" && uri.authority == tree.authority &&
            DocumentsContract.getTreeDocumentId(uri) == DocumentsContract.getTreeDocumentId(tree) &&
            app.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
    }.getOrDefault(false)

    internal suspend fun deleteSource(folder: String?, media: LocalMedia) = withContext(Dispatchers.IO) {
        if (!canDelete(folder, media.uri) || list(folder).none { it.uri == media.uri }) throw SecurityException("Write access unavailable")
        if (!DocumentsContract.deleteDocument(app.contentResolver, media.uri)) throw IOException("Source could not be deleted")
    }

    internal suspend fun imports(source: Source): List<LocalMedia> = withContext(Dispatchers.IO) {
        val key = "imported_${source.name}"
        viewSettings.getStringSet(key, emptySet()).orEmpty().mapNotNull { value ->
            val uri = Uri.parse(value)
            if (!isReadable(app, uri)) null else runCatching { fromUri(uri) }.getOrNull()
        }.sortedByDescending { it.modified }
    }

    internal suspend fun rememberImport(source: Source, uri: Uri) = withContext(Dispatchers.IO) {
        if (uri.scheme != "content") return@withContext
        val key = "imported_${source.name}"
        val saved = viewSettings.getStringSet(key, emptySet()).orEmpty()
        if (uri.toString() in saved || saved.size >= 100) return@withContext
        if (runCatching {
                app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.isSuccess) {
            viewSettings.edit().putStringSet(key, saved + uri.toString()).apply()
        }
    }

    suspend fun fromUri(uri: Uri): LocalMedia = withContext(Dispatchers.IO) {
        require(uri.scheme == "content")
        val document = DocumentFile.fromSingleUri(app, uri) ?: throw IOException("File unavailable")
        val name = document.name ?: throw IOException("File name unavailable")
        val mime = mimeFor(name.substringAfterLast('.')) ?: throw IOException("Unsupported file")
        if (!mime.startsWith("image/") && !mime.startsWith("video/")) throw IOException("Unsupported file")
        LocalMedia(uri, name, mime, document.length(), document.lastModified())
    }

    suspend fun duration(media: LocalMedia): Long? = withContext(Dispatchers.IO) {
        if (!media.video) return@withContext null
        runCatching {
            MediaMetadataRetriever().use {
                it.setDataSource(app, media.uri)
                it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            }
        }.getOrNull()
    }

    suspend fun save(media: LocalMedia, source: Source): Pair<Uri, Boolean> = withContext(Dispatchers.IO) {
        require(media.uri.scheme == "content")
        val id = UUID.randomUUID().toString()
        val folder = File(app.cacheDir, "imports/$id").apply { mkdirs() }
        val file = File(folder, "media.${media.name.substringAfterLast('.').lowercase()}")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            val stream = app.contentResolver.openInputStream(media.uri) ?: throw IOException("Media unavailable")
            stream.use { raw ->
                DigestInputStream(raw, digest).use { input ->
                    file.outputStream().use { output ->
                        val size = input.copyTo(output)
                        if (media.bytes > 0 && size != media.bytes) throw IOException("Incomplete local media")
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            app.publications.withLock {
                val duplicate = app.dao.saved(hash).firstNotNullOfOrNull { item ->
                    item.galleryUri?.let(Uri::parse)?.takeIf { isReadable(app, it) }
                }
                if (duplicate != null) return@withLock duplicate to false
                val title = media.name.substringBeforeLast('.')
                val uri = withContext(NonCancellable) {
                    publishMedia(app, file, title, media.mime) { saved ->
                        app.dao.insert(Download(id, media.uri.toString(), source.name, title,
                            state = JobState.COMPLETE.name, progress = 100, galleryUri = saved.toString(),
                            mime = media.mime, bytes = file.length(), hash = hash))
                    }
                }
                uri to true
            }
        } finally { folder.deleteRecursively() }
    }
}
