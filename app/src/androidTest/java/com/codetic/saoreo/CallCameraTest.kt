package com.codetic.saoreo

import android.Manifest
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.webrtc.VideoSink
import java.util.concurrent.atomic.AtomicInteger

class CallCameraTest {
    @Test fun switchingToTheRearCameraKeepsFlashRequestedAndResumesFramesOnTheSameTrack() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<SaoreoApp>()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(app.packageName, Manifest.permission.CAMERA)
        automation.grantRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO)
        val manager = app.getSystemService(CameraManager::class.java)
        val characteristics = manager.cameraIdList.associateWith(manager::getCameraCharacteristics)
        val front = characteristics.entries.firstOrNull {
            it.value.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        }?.key
        val rear = characteristics.entries.firstOrNull {
            it.value.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                it.value.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }?.key
        assumeTrue(front != null && rear != null)
        val unavailable = mutableSetOf<String>()
        val availability = object : CameraManager.AvailabilityCallback() {
            override fun onCameraUnavailable(cameraId: String) { unavailable += cameraId }
            override fun onCameraAvailable(cameraId: String) { unavailable -= cameraId }
        }
        manager.registerAvailabilityCallback(availability, Handler(Looper.getMainLooper()))
        withContext(Dispatchers.Main) {
            val session = TogetherSession(app)
            val call = session.callController
            val frames = AtomicInteger()
            val sink = VideoSink { frames.incrementAndGet() }
            val track = requireNotNull(call.state.value.localVideo)
            track.addSink(sink)
            try {
                call.start(true)
                withTimeout(15_000) { while (front !in unavailable || frames.get() < 3) delay(50) }
                call.torch(true)
                call.flip()
                withTimeout(15_000) { while (rear !in unavailable || front in unavailable) delay(50) }
                val before = frames.get()
                withTimeout(15_000) { while (frames.get() < before + 3) delay(50) }
                assertTrue(call.state.value.active)
                assertTrue(call.state.value.torch)
                assertSame(track, call.state.value.localVideo)
                assertNull(call.state.value.error)
                call.torch(false)
                assertFalse(call.state.value.torch)
                call.stop()
                withTimeout(15_000) { while (rear in unavailable) delay(50) }
            } finally {
                track.removeSink(sink)
                call.close()
                session.leave()
                manager.unregisterAvailabilityCallback(availability)
            }
        }
    }
}
