package com.codetic.saoreo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.content.ContextCompat

/** Keeps the accepted call eligible to capture audio when another Android activity is visible. */
class TogetherActiveCallService : Service() {
    private var cameraAllowed = false
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val call = owner
        if (call == null || intent == null) { stopSelf(startId); return START_NOT_STICKY }
        if (intent.action == "hangup") { call.hangUp(); return START_NOT_STICKY }
        val projection = intent.getParcelableExtra("projection", Intent::class.java)
        cameraAllowed = cameraAllowed || intent.getBooleanExtra("cameraAllowed", false)
        try {
            promote(projection != null || call.state.value.sharing)
            activeService = this
            if (projection != null) call.shareScreen(projection)
            else if (intent.action == "start") call.start(intent.getBooleanExtra("video", false))
        } catch (_: RuntimeException) {
            if (projection != null) call.reportError("Couldn't start screen sharing. Open the call and try again.")
            else { call.stop(); call.reportError("Couldn't enable the microphone for the call. Open Together and try again."); stopSelf(startId) }
        }
        return START_NOT_STICKY
    }

    private fun promote(sharing: Boolean) {
        val channel = "together-active-call"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, "Active Together calls", NotificationManager.IMPORTANCE_LOW)
        )
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, TogetherActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
        val hangup = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction("hangup"), flags)
        val notice = Notification.Builder(this, channel).setSmallIcon(R.drawable.ic_app_notification)
            .setContentTitle(if (sharing) "Sharing your screen" else "Together call in progress")
            .setContentIntent(open).setOngoing(true).setCategory(Notification.CATEGORY_CALL)
            .addAction(Notification.Action.Builder(null, "End call", hangup).build()).build()
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            (if (cameraAllowed) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0) or
            (if (sharing) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
        startForeground(84, notice, types)
    }

    override fun onDestroy() {
        if (activeService === this) {
            activeService = null
            val call = owner
            owner = null
            call?.stop()
        }
        super.onDestroy()
    }

    companion object {
        private var owner: TogetherCall? = null
        private var activeService: TogetherActiveCallService? = null

        internal fun begin(context: Context, call: TogetherCall, cameraAllowed: Boolean, video: Boolean): Boolean {
            owner = call
            return runCatching {
                ContextCompat.startForegroundService(context, Intent(context, TogetherActiveCallService::class.java)
                    .setAction("start").putExtra("cameraAllowed", cameraAllowed).putExtra("video", video))
            }.isSuccess.also { if (!it) owner = null }
        }

        internal fun project(context: Context, call: TogetherCall, data: Intent): Boolean = owner === call && runCatching {
            context.startService(Intent(context, TogetherActiveCallService::class.java).putExtra("projection", data))
        }.isSuccess

        internal fun updateCamera(context: Context, call: TogetherCall) {
            if (owner === call) runCatching {
                context.startService(Intent(context, TogetherActiveCallService::class.java).putExtra("cameraAllowed", true))
            }
        }

        internal fun projectionEnded(call: TogetherCall) {
            if (owner === call) runCatching { activeService?.promote(false) }
        }

        internal fun end(context: Context, call: TogetherCall) {
            if (owner === call) {
                owner = null
                context.stopService(Intent(context, TogetherActiveCallService::class.java))
            }
        }
    }
}
