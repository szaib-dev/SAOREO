package org.webrtc

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.view.Surface

// WebRTC keeps its Camera2 session private. Supply a session for flash-equipped cameras
// while keeping its existing capture lifecycle, camera switching and video track.
internal class TorchCamera2Capturer(
    context: Context,
    cameraName: String,
    eventsHandler: CameraVideoCapturer.CameraEventsHandler,
    private val flashCameras: Set<String>,
    private val torchError: () -> Unit,
) : Camera2Capturer(context, cameraName, eventsHandler) {
    @Volatile private var torchRequested = false
    private lateinit var texture: SurfaceTextureHelper
    private var torchSession: TorchSession? = null

    override fun initialize(helper: SurfaceTextureHelper, context: Context, observer: CapturerObserver) {
        texture = helper
        super.initialize(helper, context, observer)
    }

    fun setTorch(enabled: Boolean) {
        torchRequested = enabled
        texture.handler.post { torchSession?.updateTorch() }
    }

    override fun createCameraSession(
        callback: CameraSession.CreateSessionCallback, events: CameraSession.Events, context: Context,
        helper: SurfaceTextureHelper, name: String, width: Int, height: Int, fps: Int,
    ) {
        // Select from the active lens on every open, including Camera2's built-in switch path.
        val format = Camera2Enumerator(context).getSupportedFormats(name).orEmpty()
            .filter { it.width * 9 == it.height * 16 && it.height <= height && it.framerate.max >= 15_000 }
            .maxByOrNull { it.height }
        val captureWidth = format?.width ?: minOf(width, 1280)
        val captureHeight = format?.height ?: minOf(height, 720)
        val captureFps = minOf(fps, (format?.framerate?.max ?: 30_000) / 1000)
        if (name !in flashCameras) {
            torchSession = null
            super.createCameraSession(callback, events, context, helper, name, captureWidth, captureHeight, captureFps)
            return
        }
        val manager = context.getSystemService(CameraManager::class.java)
        val session = TorchSession(callback, events, context, manager, helper, name)
        torchSession = session
        session.open(captureWidth, captureHeight, captureFps)
    }

    private inner class TorchSession(
        private val callback: CameraSession.CreateSessionCallback,
        private val events: CameraSession.Events,
        private val context: Context,
        private val manager: CameraManager,
        private val helper: SurfaceTextureHelper,
        private val name: String,
    ) : CameraSession {
        private val handler = helper.handler
        private var device: CameraDevice? = null
        private var capture: CameraCaptureSession? = null
        private var surface: Surface? = null
        private var request: CaptureRequest.Builder? = null
        private var started = false
        private var stopped = false
        private var torchApplied = false
        private lateinit var characteristics: CameraCharacteristics
        private lateinit var size: Size
        private lateinit var fpsRange: Range<Int>

        @SuppressLint("MissingPermission")
        fun open(width: Int, height: Int, fps: Int) {
            events.onCameraOpening()
            try {
                characteristics = manager.getCameraCharacteristics(name)
                val ranges = requireNotNull(characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES))
                val factor = Camera2Enumerator.getFpsUnitFactor(ranges)
                val range = CameraEnumerationAndroid.getClosestSupportedFramerateRange(
                    Camera2Enumerator.convertFramerates(ranges, factor), fps)
                fpsRange = Range(range.min / factor, range.max / factor)
                size = CameraEnumerationAndroid.getClosestSupportedSize(
                    Camera2Enumerator.getSupportedSizes(characteristics), width, height)
                manager.openCamera(name, deviceCallback, handler)
            } catch (error: Exception) {
                fail(error.message ?: "Camera could not be opened")
            }
        }

        private val deviceCallback = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                try {
                    helper.setTextureSize(size.width, size.height)
                    val output = Surface(helper.surfaceTexture).also { surface = it }
                    @Suppress("DEPRECATION")
                    camera.createCaptureSession(listOf(output), sessionCallback, handler)
                } catch (error: Exception) {
                    fail(error.message ?: "Camera session could not be created")
                }
            }

            override fun onDisconnected(camera: CameraDevice) {
                device = camera
                fail("Camera disconnected", disconnected = true)
            }

            override fun onError(camera: CameraDevice, error: Int) {
                device = camera
                fail("Camera error ($error)")
            }

            override fun onClosed(camera: CameraDevice) = events.onCameraClosed(this@TorchSession)
        }

        private val sessionCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (stopped) {
                    session.close()
                    return
                }
                capture = session
                try {
                    request = requireNotNull(device).createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(requireNotNull(surface))
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        if (characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                                ?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) == true) {
                            set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
                            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                        } else if (characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                                ?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true) {
                            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                            set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                        }
                        if (characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                                ?.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) == true) {
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        }
                    }
                    repeatCapture(false)
                    updateTorch()
                    helper.startListening(::onFrame)
                    started = true
                    callback.onDone(this@TorchSession)
                } catch (error: Exception) {
                    fail(error.message ?: "Camera capture could not be started")
                }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                session.close()
                fail("Camera session configuration failed")
            }
        }

        fun updateTorch() {
            val enabled = torchRequested
            if (capture == null || stopped || enabled == torchApplied) return
            try {
                repeatCapture(enabled)
            } catch (_: Exception) {
                torchError()
            }
        }

        private fun repeatCapture(enabled: Boolean) {
            val builder = requireNotNull(request)
            builder.set(CaptureRequest.FLASH_MODE,
                if (enabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            requireNotNull(capture).setRepeatingRequest(builder.build(), null, handler)
            torchApplied = enabled
        }

        private fun onFrame(frame: VideoFrame) {
            val front = characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            val deviceRotation = CameraSession.getDeviceOrientation(context)
            val sensorRotation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val rotation = (sensorRotation + if (front) deviceRotation else 360 - deviceRotation) % 360
            val buffer = CameraSession.createTextureBufferWithModifiedTransformMatrix(
                frame.buffer as TextureBufferImpl, front, -sensorRotation)
            val output = VideoFrame(buffer, rotation, frame.timestampNs)
            try { events.onFrameCaptured(this, output) } finally { output.release() }
        }

        private fun fail(message: String, disconnected: Boolean = false) {
            if (stopped) {
                device?.close()
                device = null
                return
            }
            stop()
            when {
                !started -> callback.onFailure(
                    if (disconnected) CameraSession.FailureType.DISCONNECTED else CameraSession.FailureType.ERROR, message)
                disconnected -> events.onCameraDisconnected(this)
                else -> events.onCameraError(this, message)
            }
        }

        override fun stop() {
            if (stopped) return
            if (torchApplied) runCatching { repeatCapture(false) }
            stopped = true
            if (started) helper.stopListening()
            capture?.close()
            capture = null
            surface?.release()
            device?.close()
            device = null
            if (torchSession === this) torchSession = null
        }
    }
}
