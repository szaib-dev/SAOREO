package com.codetic.saoreo

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

internal const val EXTRA_PAIR_ANSWER = "com.codetic.saoreo.PAIR_ANSWER"
internal const val EXTRA_SHOW_RING = "com.codetic.saoreo.SHOW_RING"

internal fun togetherCallAlertsAllowed(context: Context): Boolean =
    context.getSystemService(NotificationManager::class.java).currentInterruptionFilter ==
        NotificationManager.INTERRUPTION_FILTER_ALL &&
        context.getSystemService(AudioManager::class.java).ringerMode == AudioManager.RINGER_MODE_NORMAL

class TogetherBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val pairing = (context.applicationContext as SaoreoApp).pairing
        if (pairing.resumeAvailabilityAtBoot) runCatching {
            ContextCompat.startForegroundService(context, Intent(context, TogetherAvailabilityService::class.java))
        }
    }
}

class TogetherAvailabilityService : Service() {
    private val pairing get() = (application as SaoreoApp).pairing
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notifications get() = getSystemService(NotificationManager::class.java)
    private var shownRing: String? = null
    private lateinit var availableNotification: Notification

    private fun promote(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(id, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(NotificationChannel(AVAILABLE, "Together availability", NotificationManager.IMPORTANCE_LOW))
        notifications.createNotificationChannel(NotificationChannel(SILENT_RING, "Silent Together calls", NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null)
            enableVibration(false)
        })
        notifications.createNotificationChannel(NotificationChannel(RING, "Together calls and invites", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
            enableVibration(true)
        })
        val stop = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction(STOP), pendingFlags)
        val notification = Notification.Builder(this, AVAILABLE)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle("Available on Together")
            .setContentText("Your paired partner can reach you.")
            .setContentIntent(openTogether(2))
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Go offline", stop).build())
            .build()
        availableNotification = notification
        promote(AVAILABLE_ID, notification)
        pairing.background(true)
        scope.launch {
            pairing.state.collect { state ->
                val ring = state.ring?.takeIf { it.incoming && it.until > SystemClock.elapsedRealtime() }
                if (ring?.id != shownRing) {
                    promote(AVAILABLE_ID, availableNotification)
                    notifications.cancel(RING_ID)
                    shownRing = ring?.id
                    if (ring != null && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                        showRing(ring, state.partner.orEmpty().ifBlank { "Your partner" })
                    }
                }
            }
        }
    }

    private fun showRing(ring: PairRing, partner: String) {
        val quiet = ring.silent || pairing.state.value.visible || !togetherCallAlertsAllowed(this)
        val decline = PendingIntent.getService(this, 3, Intent(this, javaClass).setAction(DECLINE).putExtra("id", ring.id), pendingFlags)
        val answer = openTogether(4, ring.id)
        val builder = Notification.Builder(this, if (quiet) SILENT_RING else RING)
            .setSmallIcon(R.drawable.ic_call_incoming_notification)
            .setContentTitle(ring.kind.title)
            .setContentText(partner)
            .setCategory(if (ring.kind == PairKind.WATCH) Notification.CATEGORY_EVENT else Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(openTogether(5, showRing = ring.kind != PairKind.WATCH))
            .setTimeoutAfter((ring.until - SystemClock.elapsedRealtime()).coerceAtLeast(1))
        if (quiet) builder.setGroup(SILENT_RING).setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
        if (ring.kind == PairKind.WATCH) {
            builder.addAction(Notification.Action.Builder(null, "Decline", decline).build())
                .addAction(Notification.Action.Builder(null, "Accept", answer).build())
        } else {
            builder.setStyle(Notification.CallStyle.forIncomingCall(Person.Builder().setName(partner).setImportant(true).build(), decline, answer))
            if (!quiet && (Build.VERSION.SDK_INT < 34 || notifications.canUseFullScreenIntent())) {
                builder.setFullScreenIntent(openTogether(6, showRing = true), true)
            }
        }
        if (ring.kind == PairKind.WATCH) notifications.notify(RING_ID, builder.build())
        else {
            promote(RING_ID, builder.build())
            notifications.cancel(AVAILABLE_ID)
        }
    }

    private fun openTogether(request: Int, answer: String? = null, showRing: Boolean = false): PendingIntent = PendingIntent.getActivity(this, request,
        Intent(this, TogetherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_PAIR_ANSWER, answer).putExtra(EXTRA_SHOW_RING, showRing), pendingFlags)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> { pairing.setAvailabilityPreference(false); stopSelf(); return START_NOT_STICKY }
            DECLINE -> intent.getStringExtra("id")?.let { id -> scope.launch { runCatching { pairing.respondRing(id, false) } } }
        }
        intent?.getStringExtra("pushRing")?.let { id ->
            scope.launch { runCatching { pairing.refreshIncoming(id) } }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        notifications.cancel(RING_ID)
        pairing.background(false)
        super.onDestroy()
    }

    private companion object {
        const val AVAILABLE = "together-availability"
        const val RING = "together-incoming"
        const val SILENT_RING = "together-incoming-silent-v2"
        const val STOP = "stop"
        const val DECLINE = "decline"
        const val AVAILABLE_ID = 82
        const val RING_ID = 83
        const val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
}
