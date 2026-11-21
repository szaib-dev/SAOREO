package com.codetic.saoreo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.sample

class TranscriptService : Service() {
    private val engine get() = (application as SaoreoApp).transcripts
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var active = false

    override fun onBind(intent: Intent?) = null

    @OptIn(FlowPreview::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == CANCEL) {
            engine.cancel()
            if (!active) stopSelf()
            return START_NOT_STICKY
        }
        if (active) return START_NOT_STICKY
        val model = SpeechModel.entries.firstOrNull { it.name == intent?.getStringExtra("model") }
        if (model == null || action !in setOf(MODEL, GENERATE)) {
            engine.serviceUnavailable()
            stopSelf()
            return START_NOT_STICKY
        }
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Transcript processing", NotificationManager.IMPORTANCE_LOW))
        val type = if (action == GENERATE && Build.VERSION.SDK_INT >= 35)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        try { ServiceCompat.startForeground(this, ID, notice("Preparing…", null), type) }
        catch (_: Exception) { engine.serviceUnavailable(); stopSelf(); return START_NOT_STICKY }
        active = true
        scope.launch {
            engine.state.sample(1000).collect { if (it.busy) notifications.notify(ID, notice(it.message.orEmpty(), it.progress)) }
        }
        val finish = {
            active = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        if (action == MODEL) engine.model(model, finish)
        else {
            val link = intent?.getStringExtra("url")?.let(MediaLink::parse)
            val local = intent?.data?.takeIf { it.scheme == "content" }
            val language = intent?.getStringExtra("language")?.takeIf { it.matches(Regex("[a-z]{2,4}")) } ?: "auto"
            if (link == null && local == null) { engine.serviceUnavailable(); finish() }
            else engine.transcribe(model, link, local, language, finish)
        }
        return START_NOT_STICKY
    }

    private fun notice(text: String, progress: Int?): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val view = Intent(this, MainActivity::class.java).setAction(MainActivity.OPEN_TRANSCRIPT)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val cancel = PendingIntent.getService(this, ID, Intent(this, TranscriptService::class.java).setAction(CANCEL), flags)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("SAOREO Transcript").setContentText(text).setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(this, ID, view, flags))
            .setProgress(100, progress ?: 0, progress == null).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Cancel", cancel).build()).build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) { engine.cancel(); stopSelf() }
    override fun onDestroy() { if (active) engine.cancel(); scope.cancel(); super.onDestroy() }

    companion object {
        internal const val MODEL = "download-model"
        internal const val GENERATE = "generate-transcript"
        private const val CANCEL = "cancel-transcript"
        private const val CHANNEL = "transcript_processing"
        private const val ID = 7160
    }
}
