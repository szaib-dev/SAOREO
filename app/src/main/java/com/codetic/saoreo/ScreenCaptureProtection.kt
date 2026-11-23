package com.codetic.saoreo

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.WindowManager.LayoutParams.FLAG_SECURE
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.WeakHashMap

/** Read synchronously before any activity can draw, including after a cold start. */
internal class ScreenCaptureProtection(app: Application) : Application.ActivityLifecycleCallbacks {
    private val preferences = app.getSharedPreferences("screen-capture-protection", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(preferences.getBoolean("enabled", false))
    val enabled = mutable.asStateFlow()
    private val activities = WeakHashMap<Activity, Boolean>()

    init { app.registerActivityLifecycleCallbacks(this) }

    fun setEnabled(enabled: Boolean) {
        mutable.value = enabled
        preferences.edit().putBoolean("enabled", enabled).apply()
        activities.keys.toList().forEach(::apply)
    }

    private fun apply(activity: Activity) {
        val window = activity.window
        val secure = window.attributes.flags and FLAG_SECURE != 0
        if (mutable.value && !secure) {
            window.addFlags(FLAG_SECURE)
            activity.setRecentsScreenshotEnabled(false)
            activities[activity] = true
        } else if (!mutable.value && activities[activity] == true) {
            window.clearFlags(FLAG_SECURE)
            activity.setRecentsScreenshotEnabled(true)
            activities[activity] = false
        }
    }

    override fun onActivityPreCreated(activity: Activity, state: Bundle?) {
        activities[activity] = false
        apply(activity)
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = apply(activity)
    override fun onActivityStarted(activity: Activity) = apply(activity)
    override fun onActivityDestroyed(activity: Activity) { activities.remove(activity) }
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
}
