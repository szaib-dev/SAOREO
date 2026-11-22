package com.codetic.saoreo

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil

internal data class TransferProgress(
    val stage: String = "Downloading", val bytes: Long = 0, val total: Long? = null, val bytesPerSecond: Long? = null,
) {
    val percent: Int get() = total?.takeIf { it > 0 }?.let { (100.0 * bytes / it).toInt().coerceIn(0, 99) } ?: -1
    val eta: Long? get() = total?.let { length ->
        bytesPerSecond?.takeIf { it > 0 && length > bytes }?.let { ceil((length - bytes).toDouble() / it).toLong() }
    }
}

internal fun hasDownloadNetwork(context: Context, wifi: Boolean): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
        (!wifi || capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
}

internal class DownloadNotifications(private val app: SaoreoApp) {
    private val notifications get() = app.getSystemService(NotificationManager::class.java)
    private val current = linkedMapOf<String, Pair<String, TransferProgress>>()
    private val lock = Any()
    val progress = MutableStateFlow<Map<String, TransferProgress>>(emptyMap())
    private val waitingChannel = "download_waiting"

    fun channels() {
        notifications.createNotificationChannel(NotificationChannel("progress", "Downloads in progress", NotificationManager.IMPORTANCE_LOW))
        notifications.createNotificationChannel(NotificationChannel("results", "Download results", NotificationManager.IMPORTANCE_DEFAULT))
        notifications.createNotificationChannel(NotificationChannel(waitingChannel, "Downloads waiting", NotificationManager.IMPORTANCE_LOW))
    }

    fun id(key: String) = (key.hashCode() and 0x0fffffff) + 9000
    private fun resultId(key: String) = id(key) or 0x20000000
    private fun waitingId(key: String) = id(key) or 0x40000000
    private val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    private fun downloads(key: String): PendingIntent = PendingIntent.getActivity(app, id(key),
        Intent(app, MainActivity::class.java).setAction(MainActivity.OPEN_DOWNLOADS)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)

    private fun action(key: String, action: String) = PendingIntent.getBroadcast(app, id(key),
        Intent(app, DownloadActionReceiver::class.java).setAction(action).putExtra("download_id", key), flags)

    private fun base(channel: String, title: String) = NotificationCompat.Builder(app, channel)
        .setSmallIcon(R.drawable.ic_download_notice).setContentTitle(title.take(110))
        .setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

    fun ongoing(key: String, title: String, progress: TransferProgress): Notification {
        val amount = if (progress.bytes > 0 || progress.total != null) {
            val size = sizeLabel(progress.bytes)
            val total = progress.total
            if (total == null) size else "$size / ${sizeLabel(total)} · ${progress.percent}%"
        } else null
        val rate = progress.bytesPerSecond?.takeIf { it > 0 }?.let { "${sizeLabel(it)}/s" }
        val time = progress.eta?.let { "${durationLabel(it)} remaining" }
        val lines = listOfNotNull(progress.stage, amount, listOfNotNull(rate, time).joinToString(" · ").takeIf { it.isNotBlank() })
        return base("progress", title).setContentText(lines.joinToString(" · "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(downloads(key)).setGroup("saoreo-downloads").setSilent(true)
            .setOngoing(true).setProgress(100, progress.percent.coerceAtLeast(0), progress.percent < 0)
            .addAction(0, "Pause", action(key, DownloadActionReceiver.PAUSE))
            .addAction(0, "Cancel", action(key, DownloadActionReceiver.CANCEL)).build()
    }

    fun update(key: String, title: String, progress: TransferProgress) = synchronized(lock) {
        val changed = current[key] != (title to progress)
        current[key] = title to progress
        if (changed) {
            this.progress.value = current.mapValues { it.value.second }
            notify(id(key), ongoing(key, title, progress))
            summary()
        }
    }

    fun active(key: String, title: String, progress: TransferProgress) = synchronized(lock) {
        notifications.cancel(waitingId(key))
        notifications.cancel(resultId(key))
        current[key] = title to progress
        this.progress.value = current.mapValues { it.value.second }
        summary()
    }

    fun end(key: String) = synchronized(lock) {
        current.remove(key)
        progress.value = current.mapValues { it.value.second }
        notifications.cancel(waitingId(key))
        summary()
    }

    fun waiting(key: String, title: String, status: String) {
        channels()
        notify(waitingId(key), base(waitingChannel, title).setContentText(status)
            .setContentIntent(downloads(key)).setOngoing(true).setSilent(true)
            .addAction(0, "Pause", action(key, DownloadActionReceiver.PAUSE))
            .addAction(0, "Cancel", action(key, DownloadActionReceiver.CANCEL)).build())
    }

    suspend fun finished(item: Download, error: String? = null) {
        val mime = item.mime.orEmpty()
        val playable = error == null && item.galleryUri != null && (mime.startsWith("video/") || mime.startsWith("audio/") || mime.startsWith("image/"))
        val open = if (playable) PendingIntent.getActivity(app, resultId(item.id),
            if (mime.startsWith("audio/")) MusicActivity.open(app, item.id) else LibraryPlayerActivity.open(app, item.id), flags) else downloads(item.id)
        val displayName = if (playable) withContext(Dispatchers.IO) {
            runCatching {
                app.contentResolver.query(Uri.parse(item.galleryUri), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull()
        } else null
        val builder = base("results", displayName ?: item.title).setContentIntent(open).setAutoCancel(true)
        if (error == null) {
            builder.setContentText("Download complete.")
            builder.addAction(0, "Open", open)
        } else {
            builder.setContentText(error).setStyle(NotificationCompat.BigTextStyle().bigText(error))
            builder.addAction(0, "Retry", action(item.id, DownloadActionReceiver.RETRY))
        }
        notify(resultId(item.id), builder.build())
    }

    private fun notify(id: Int, notification: Notification) {
        if (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            notifications.notify(id, notification)
        }
    }

    private fun summary() {
        val summaryId = 7200
        if (current.size < 2) { notifications.cancel(summaryId); return }
        val style = NotificationCompat.InboxStyle()
        current.values.forEach { (title, stage) ->
            style.addLine("$title · ${stage.stage}" + if (stage.percent >= 0) " · ${stage.percent}%" else "")
        }
        notify(summaryId, base("progress", "${current.size} active downloads")
            .setGroup("saoreo-downloads").setGroupSummary(true).setContentIntent(downloads("summary"))
            .setSilent(true).setOngoing(true).setStyle(style).build())
    }
}

class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("download_id") ?: return
        if (intent.action !in setOf(CANCEL, RETRY, PAUSE)) return
        val pending = goAsync()
        val app = context.applicationContext as SaoreoApp
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val item = app.dao.find(id) ?: return@launch
                if (intent.action == CANCEL) app.downloads.cancel(item)
                else if (intent.action == PAUSE) app.downloads.pause(item)
                else if (MediaLink.parse(item.url) != null) app.downloads.retry(item, app.preferences.flow.first())
            } catch (_: Exception) {
                // WorkManager failures are reflected in the existing transfer record.
            } finally { pending.finish() }
        }
    }

    companion object {
        internal const val PAUSE = "com.codetic.saoreo.DOWNLOAD_PAUSE"
        internal const val CANCEL = "com.codetic.saoreo.DOWNLOAD_CANCEL"
        internal const val RETRY = "com.codetic.saoreo.DOWNLOAD_RETRY"
    }
}
