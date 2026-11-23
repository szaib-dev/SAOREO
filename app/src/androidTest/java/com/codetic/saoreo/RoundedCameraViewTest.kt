package com.codetic.saoreo

import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.EglBase
import org.webrtc.JavaI420Buffer
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoFrame

@RunWith(AndroidJUnit4::class)
class RoundedCameraViewTest {
    @Test fun videoPixelsStayInsideTheRoundedPreview() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions
            .builder(instrumentation.targetContext).createInitializationOptions())
        val egl = EglBase.create()
        var preview: RoundedCameraView? = null
        val frameBuffer = JavaI420Buffer.allocate(36, 64).apply {
            dataY.apply { while (hasRemaining()) put(150.toByte()) }
            dataU.apply { while (hasRemaining()) put(44.toByte()) }
            dataV.apply { while (hasRemaining()) put(21.toByte()) }
        }
        val frame = VideoFrame(frameBuffer, 0, 0)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    val pixelWidth = (108 * activity.resources.displayMetrics.density).toInt()
                    val pixelHeight = (156 * activity.resources.displayMetrics.density).toInt()
                    val camera = RoundedCameraView(activity, egl.eglBaseContext)
                    preview = camera
                    camera.configure(fill = true, mirror = false)
                    activity.setContentView(FrameLayout(activity).apply {
                        setBackgroundColor(Color.BLACK)
                        addView(camera, FrameLayout.LayoutParams(pixelWidth, pixelHeight, Gravity.CENTER))
                    })
                }
                var rendered = false
                var clipped = false
                var filled = false
                for (attempt in 0 until 30) {
                    val points = IntArray(4)
                    scenario.onActivity {
                        val view = checkNotNull(preview)
                        if (view.isAvailable) {
                            view.getLocationOnScreen(points)
                            points[2] = view.width / 2
                            points[3] = view.height / 2
                            view.onFrame(frame)
                        }
                    }
                    Thread.sleep(60)
                    if (points[2] == 0) continue
                    val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: continue
                    try {
                        val center = bitmap.getPixel(points[0] + points[2], points[1] + points[3])
                        val corner = bitmap.getPixel(points[0] + 2, points[1] + 2)
                        val edge = bitmap.getPixel(points[0] + 2, points[1] + points[3])
                        if (Color.green(center) > 140 && Color.green(center) > Color.red(center) + 70) {
                            rendered = true
                            clipped = Color.green(corner) < 35 && Color.red(corner) < 35
                            filled = Color.green(edge) > 140 && Color.green(edge) > Color.red(edge) + 70
                            break
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
                assertTrue("The synthetic green camera frame was visible", rendered)
                assertTrue("The corner outside the rounded outline stayed black", clipped)
                assertTrue("The portrait camera frame filled the width", filled)
            } finally {
                scenario.onActivity { preview?.release() }
                frame.release()
                egl.release()
            }
        }
    }
}
