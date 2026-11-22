package com.codetic.saoreo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageButton
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

class ShortcutService : Service() {
    private val app get() = application as SaoreoApp
    private val windowManager by lazy { getSystemService(WindowManager::class.java) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var button: ImageButton? = null
    private var params: WindowManager.LayoutParams? = null
    private var right = true
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP || !AndroidSettings.canDrawOverlays(this)) {
            scope.launch { app.preferences.set(SettingKeys.overlay, false); stopSelf() }
            return START_NOT_STICKY
        }
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("shortcut", "Floating shortcut", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, ShortcutService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        ServiceCompat.startForeground(this, 1, NotificationCompat.Builder(this, "shortcut")
            .setSmallIcon(R.drawable.ic_app_notification).setContentTitle("SAOREO shortcut")
            .setContentText("Tap the floating button to paste a link.").setOngoing(true)
            .addAction(0, "Hide", stop).build(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        if (!observing) {
            observing = true
            scope.launch {
                app.preferences.flow.collect { value ->
                    if (!value.overlay || !AndroidSettings.canDrawOverlays(this@ShortcutService)) stopSelf()
                    else runCatching { applyAppearance(value) }.onFailure {
                        app.preferences.set(SettingKeys.overlay, false)
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun applyAppearance(settings: Settings) {
        val size = dp(settings.overlaySize)
        val view = button ?: ImageButton(this).apply {
            setImageResource(R.drawable.mark)
            contentDescription = "Open SAOREO link input"
            background = null
            setPadding(0, 0, 0, 0)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            elevation = dp(3).toFloat()
            setOnClickListener {
                startActivity(Intent(this@ShortcutService, MainActivity::class.java).setAction(OPEN)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
        }.also { button = it }
        val layout = params ?: WindowManager.LayoutParams(size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.LEFT; y = dp(190) }.also {
            params = it
            setupDrag(view, it)
        }
        layout.width = size
        layout.height = size
        layout.alpha = settings.overlayOpacity / 100f
        val (x, y) = travel(layout)
        layout.x = if (right) x else 0
        layout.y = layout.y.coerceIn(0, y)
        if (view.isAttachedToWindow) windowManager.updateViewLayout(view, layout)
        else windowManager.addView(view, layout)
    }

    private fun setupDrag(view: ImageButton, layout: WindowManager.LayoutParams) {
        var touchX = 0f
        var touchY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchX = event.rawX; touchY = event.rawY
                    startX = layout.x; startY = layout.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - touchX
                    val deltaY = event.rawY - touchY
                    if (dragging || hypot(deltaX, deltaY) >= slop) {
                        dragging = true
                        val (maxX, maxY) = travel(layout)
                        layout.x = (startX + deltaX.roundToInt()).coerceIn(0, maxX)
                        layout.y = (startY + deltaY.roundToInt()).coerceIn(0, maxY)
                        windowManager.updateViewLayout(view, layout)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        val maximum = travel(layout).first
                        right = layout.x > maximum / 2
                        layout.x = if (right) maximum else 0
                        windowManager.updateViewLayout(view, layout)
                    } else view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun travel(layout: WindowManager.LayoutParams): Pair<Int, Int> {
        val metric = windowManager.currentWindowMetrics
        val insets = metric.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        return (metric.bounds.width() - insets.left - insets.right - layout.width).coerceAtLeast(0) to
            (metric.bounds.height() - insets.top - insets.bottom - layout.height).coerceAtLeast(0)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val view = button ?: return
        val layout = params ?: return
        val (x, y) = travel(layout)
        layout.x = if (right) x else 0
        layout.y = layout.y.coerceIn(0, y)
        windowManager.updateViewLayout(view, layout)
    }

    override fun onDestroy() {
        button?.let { runCatching { windowManager.removeViewImmediate(it) } }
        scope.cancel()
        super.onDestroy()
    }

    private fun dp(value: Int) = (resources.displayMetrics.density * value).roundToInt()

    companion object {
        const val OPEN = "com.codetic.saoreo.OPEN_SHORTCUT"
        private const val STOP = "com.codetic.saoreo.HIDE_SHORTCUT"
    }
}
