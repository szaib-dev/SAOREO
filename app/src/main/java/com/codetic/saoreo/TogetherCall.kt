package com.codetic.saoreo

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

internal data class TogetherCallState(val active: Boolean = false, val mic: Boolean = false, val camera: Boolean = false,
    val connection: String = "Call is off", val localVideo: VideoTrack? = null, val remoteVideo: VideoTrack? = null,
    val output: String = "Phone audio", val input: String = "System default", val sharing: Boolean = false,
    val remoteMuted: Boolean = false, val torch: Boolean = false, val hasTorch: Boolean = false, val error: String? = null, val remoteBackground: Boolean = false, val remoteSharing: Boolean = false,
    val retryAvailable: Boolean = false, val connectedAtMs: Long = 0L,
    val qualityMode: CallQualityMode = CallQualityMode.SMART, val mediaWarning: String? = null,
    val outputDeviceId: Int? = null, val inputDeviceId: Int? = null, val audioDeviceRevision: Int = 0)

/** One peer connection per private room; signaling travels through the authenticated room socket. */
internal class TogetherCall(private val context: Context, private val session: TogetherSession, private val forceRelay: Boolean = false,
    private val identity: CallIdentity = CallIdentity(context),
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val qualityPreferences = context.getSharedPreferences("saoreo_call_video", Context.MODE_PRIVATE)
    private val initialQuality = CallQualityMode.entries.firstOrNull {
        it.name == qualityPreferences.getString("mode", null)
    } ?: CallQualityMode.SMART
    private val mutable = MutableStateFlow(TogetherCallState(qualityMode = initialQuality))
    private val diagnosticsState = MutableStateFlow<String?>(null)
    val videoDiagnostics = diagnosticsState.asStateFlow()
    val state = mutable.asStateFlow()
    val egl: EglBase = EglBase.create()
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val oldMode = audioManager.mode
    private val adm: JavaAudioDeviceModule
    private val factory: PeerConnectionFactory
    private val audioSource: AudioSource
    private var remoteAudio: AudioTrack? = null
    private val audioTrack: AudioTrack
    private val videoSource: VideoSource
    private val videoTrack: VideoTrack
    private var capturer: TorchCamera2Capturer? = null
    private var cameraName: String? = null
    private var switchingCamera = false
    private var texture: SurfaceTextureHelper? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var screenTexture: SurfaceTextureHelper? = null
    private var screenSource: VideoSource? = null
    private var screenTrack: VideoTrack? = null
    private var videoSender: RtpSender? = null
    private var selectedOutput: Int? = null
    private var selectedInput: Int? = null
    private var peer: PeerConnection? = null
    private var remoteId: String? = null
    private var closed = false
    private var offerer = false
    private var negotiationId: String? = null
    private var negotiating = false
    private var remoteSdp: String? = null
    private var localSdp: JSONObject? = null
    private val remoteCandidates = mutableListOf<IceCandidate>()
    private val localCandidates = mutableListOf<JSONObject>()
    private var hasRemoteDescription = false
    private var signalingOnline = false
    private var callFlagsMayBeStale = false
    private var waitForCallFlagsUntil = 0L
    private val checkCallFlags = Runnable { members(session.state.value) }
    private var recoveryAttempts = 0
    private var recovering = false
    private var wasConnected = false
    private var checkedNetwork: Network? = null
    private var monitoringNetwork = false
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val recover = Runnable { recoveryStep() }
    private val networkChanged = Runnable {
        if (mutable.value.active) {
            session.checkSignaling()
            if (peer != null && !mediaConnected()) scheduleRecovery(0)
        }
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            main.post {
                if (!monitoringNetwork) return@post
                if (checkedNetwork != network) {
                    main.removeCallbacks(networkChanged)
                    main.postDelayed(networkChanged, 1500)
                }
                checkedNetwork = network
            }
        }
    }
    private var statsSampler = CallStatsSampler()
    @Volatile private var capturedSize: Pair<Int, Int>? = null
    private var captureHeight = 720
    private var captureTargetHeight = 1080
    private val qhdEncoderAvailable by lazy {
        val videoTypes = setOf("video/avc", "video/x-vnd.on2.vp8", "video/x-vnd.on2.vp9", "video/av01")
        android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
            codec.isEncoder && codec.isHardwareAccelerated && codec.supportedTypes.any { type ->
                type in videoTypes && runCatching {
                    codec.getCapabilitiesForType(type).videoCapabilities.areSizeAndRateSupported(2560, 1440, 24.0)
                }.getOrDefault(false)
            }
        }
    }
    private var videoQuality = CallVideoQuality(initialQuality, captureHeight)
    private var appliedProfile: CallVideoProfile? = null
    private var warningSamples = 0

    fun selectVideoQuality(mode: CallQualityMode) {
        if (mode == mutable.value.qualityMode) return
        qualityPreferences.edit().putString("mode", mode.name).apply()
        mutable.value = mutable.value.copy(qualityMode = mode)
        val wanted = requestedCaptureHeight()
        capturer?.let { capture ->
            if (wanted != captureTargetHeight) {
                captureTargetHeight = wanted
                capturedSize = null
                capture.changeCaptureFormat(if (wanted == 1440) 2560 else 1920, wanted, 30)
            }
        }
        resetVideoEnvelope()
    }

    private fun requestedCaptureHeight(): Int =
        if (mutable.value.qualityMode == CallQualityMode.MAXIMUM && qhdEncoderAvailable) 1440 else 1080

    private fun resetVideoEnvelope() {
        videoQuality = CallVideoQuality(mutable.value.qualityMode, captureHeight)
        configureVideoSender()
    }

    private val sampleStats = object : Runnable {
        override fun run() {
            val pc = peer ?: return
            val run = peerGeneration
            pc.getStats { report -> main.post {
                if (run != peerGeneration || closed) return@post
                val stats = statsSampler.read(report)
                if (!negotiating && mediaConnected() && recovering && recoveryAttempts > 0) connected()
                if (mutable.value.camera && screenTrack == null && mediaConnected()) {
                    capturedSize?.let { (width, height) ->
                        val actualHeight = minOf(width, height)
                        if (actualHeight != captureHeight) {
                            captureHeight = actualHeight
                            resetVideoEnvelope()
                        }
                    }
                    videoQuality.update(stats)
                    if (videoQuality.profile != appliedProfile) configureVideoSender()
                }
                val warning = when {
                    !mediaConnected() || !mutable.value.camera || screenTrack != null -> null
                    stats.encoderLimited -> "This device is limiting outgoing video"
                    stats.limitation == "bandwidth" || videoQuality.profile.height == 0 ->
                        "Limited bandwidth for outgoing video"
                    else -> null
                }
                warningSamples = if (warning == null) 0 else (warningSamples + 1).coerceAtMost(2)
                val visibleWarning = warning.takeIf { warningSamples >= 2 }
                if (mutable.value.mediaWarning != visibleWarning) {
                    mutable.value = mutable.value.copy(mediaWarning = visibleWarning)
                }
                val capture = capturedSize?.let { (w, h) -> "${w}×$h" } ?: "Unavailable"
                val profileLabel = when {
                    screenTrack != null -> "Screen sharing"
                    !mutable.value.camera -> "Camera off"
                    else -> appliedProfile?.label ?: "Awaiting sender settings"
                }
                diagnosticsState.value = "Camera capture: $capture\nOutgoing profile: $profileLabel\n${stats.diagnostic}"
            } }
            main.postDelayed(this, 2000)
        }
    }
    private var peerGeneration = 0
    private var authentication: CallAuthentication? = null
    private var authenticatingRoom: String? = null
    private var authenticatingPeer: String? = null
    private var authenticatingRoomSecret: String? = null
    private var approval: JSONObject? = null
    private var authenticationStarted = 0L
    private val retryAuthentication = object : Runnable {
        override fun run() {
            val exchange = authentication ?: return
            if (!mutable.value.active || peer != null) return
            if (runCatching { sendSignal(exchange.hello); approval?.let(::sendSignal) }.isFailure) {
                resetAuthentication()
                reportError("Android couldn't sign the call verification message.")
                return
            }
            if (!exchange.ready && android.os.SystemClock.elapsedRealtime() - authenticationStarted > 15_000 &&
                mutable.value.connection == "Verifying call identity") {
                mutable.value = mutable.value.copy(connection = "Waiting for verified device",
                    error = "Both phones need the updated app and the same secure pairing or room invitation.")
            }
            main.postDelayed(this, 1400)
        }
    }

    init {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        if (forceRelay) Logging.enableLogToDebugOutput(Logging.Severity.LS_WARNING)
        adm = JavaAudioDeviceModule.builder(context).setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true).createAudioDeviceModule()
        val emulator = android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk_gphone")
        val encoderFactory = DefaultVideoEncoderFactory(egl.eglBaseContext, true, true)
        val hardwareCodecs = HardwareVideoEncoderFactory(egl.eglBaseContext, true, true).supportedCodecs.toSet()
        val preferredEncoderFactory = object : VideoEncoderFactory by encoderFactory {
            override fun getSupportedCodecs(): Array<VideoCodecInfo> =
                encoderFactory.supportedCodecs.sortedBy { it !in hardwareCodecs }.toTypedArray()
        }
        factory = PeerConnectionFactory.builder().setOptions(PeerConnectionFactory.Options().apply {
            // Emulator synthetic cellular handles reject bindSocket and can replace a working Wi-Fi pair.
            // Physical devices retain native network monitoring and continual ICE gathering.
            disableNetworkMonitor = emulator
            networkIgnoreMask = networkIgnoreMask or PeerConnectionFactory.Options.ADAPTER_TYPE_LOOPBACK
        }).setAudioDeviceModule(adm)
            .setVideoEncoderFactory(preferredEncoderFactory)
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext)).createPeerConnectionFactory()
        audioSource = factory.createAudioSource(MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        })
        audioTrack = factory.createAudioTrack("saoreo-audio", audioSource).also { it.setEnabled(false) }
        videoSource = factory.createVideoSource(false)
        videoTrack = factory.createVideoTrack("saoreo-camera", videoSource).also { it.setEnabled(false) }
        mutable.value = mutable.value.copy(localVideo = videoTrack)
        session.onSignal = ::signal
        session.onConnectionLost = ::networkLost
        session.callOnReconnect = { mutable.value.active }
    }

    fun start(camera: Boolean, microphoneEnabled: Boolean = true, speaker: Boolean = false) {
        if (closed) return
        if (mutable.value.active) return
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        mutable.value = mutable.value.copy(active = true, mic = microphoneEnabled, connection = "Connecting…", error = null)
        audioTrack.setEnabled(microphoneEnabled)
        if (speaker) selectedOutput = devices().firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.id
        if (camera) camera(true)
        if (!monitoringNetwork) {
            checkedNetwork = connectivity.activeNetwork
            monitoringNetwork = runCatching { connectivity.registerDefaultNetworkCallback(networkCallback) }.isSuccess
        }
        announce()
        members(session.state.value)
        routeAudio()
    }
    private var inBackground = false
    fun background(enabled: Boolean) {
        inBackground = enabled
        sendAuthenticated(JSONObject().put("type", "background").put("enabled", enabled))
    }
    fun muteRemote(enabled: Boolean) {
        mutable.value = mutable.value.copy(remoteMuted = enabled)
        remoteAudio?.setEnabled(!enabled)
    }
    fun microphone(enabled: Boolean) {
        audioTrack.setEnabled(enabled && mutable.value.active)
        mutable.value = mutable.value.copy(mic = enabled && mutable.value.active)
        announce()
    }
    fun previewBeforeCall(enabled: Boolean) {
        if (!mutable.value.active && peer == null) setCamera(enabled, preview = true)
    }

    fun camera(enabled: Boolean) = setCamera(enabled)

    private fun setCamera(enabled: Boolean, preview: Boolean = false) {
        if (enabled && !mutable.value.active && !preview) return
        if (enabled && mutable.value.sharing) stopScreenShare()
        try {
            if (enabled && capturer == null) {
                val enumerator = Camera2Enumerator(context)
                val name = enumerator.deviceNames.firstOrNull(enumerator::isFrontFacing) ?: enumerator.deviceNames.firstOrNull()
                    ?: error("No camera is available")
                releaseStandaloneTorch()
                cameraName = name
                lateinit var capture: TorchCamera2Capturer
                capture = TorchCamera2Capturer(context, name, object : CameraVideoCapturer.CameraEventsHandler {
                    override fun onFirstFrameAvailable() {
                        main.post { if (capturer === capture && !closed) updateStandaloneTorch() }
                    }
                    override fun onCameraOpening(cameraName: String) = Unit
                    override fun onCameraError(errorDescription: String) {
                        main.post {
                            if (capturer === capture && !closed && captureTargetHeight == 1440) {
                                captureTargetHeight = 1080
                                capturedSize = null
                                capture.changeCaptureFormat(1920, 1080, 30)
                            }
                        }
                    }
                    override fun onCameraDisconnected() = Unit
                    override fun onCameraFreezed(errorDescription: String) = Unit
                    override fun onCameraClosed() = Unit
                }, flashCameras) {
                    main.post { if (capturer === capture && !closed) reportError("The camera couldn't change its flashlight.") }
                }
                capturer = capture
                texture = SurfaceTextureHelper.create("SAOREO-camera", egl.eglBaseContext)
                val observer = videoSource.capturerObserver
                capture.initialize(requireNotNull(texture), context, object : CapturerObserver by observer {
                    override fun onFrameCaptured(frame: VideoFrame) {
                        val width = frame.buffer.width
                        val height = frame.buffer.height
                        val size = capturedSize
                        if (size?.first != width || size.second != height) capturedSize = width to height
                        observer.onFrameCaptured(frame)
                    }
                })
                capture.setTorch(mutable.value.torch)
                // The capturer chooses a native size per lens. The measured frame size
                // determines sender scaling once camera frames arrive.
                captureHeight = 720
                capturedSize = null
                resetVideoEnvelope()
                captureTargetHeight = requestedCaptureHeight()
                capture.startCapture(if (captureTargetHeight == 1440) 2560 else 1920, captureTargetHeight, 30)
            } else if (!enabled && capturer != null) {
                torch(false)
                capturer?.stopCapture(); capturer?.dispose(); capturer = null
                capturedSize = null
                texture?.dispose(); texture = null
                cameraName = null
                switchingCamera = false
            }
            videoTrack.setEnabled(enabled)
            mutable.value = mutable.value.copy(camera = enabled, error = null, mediaWarning = null)
        } catch (e: Exception) {
            videoTrack.setEnabled(false)
            mutable.value = mutable.value.copy(camera = false, mediaWarning = null, error = e.message ?: "Camera unavailable")
        }
        if (!preview) announce()
    }
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val flashCameras = runCatching {
        cameraManager.cameraIdList.filter { id ->
            runCatching {
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }.getOrDefault(false)
        }.toSet()
    }.getOrDefault(emptySet())
    private val standaloneTorchId = flashCameras.firstOrNull { Camera2Enumerator(context).isBackFacing(it) }
        ?: flashCameras.firstOrNull()
    private var standaloneTorchOn = false
    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (!closed && cameraId == standaloneTorchId && !enabled) {
                standaloneTorchOn = false
                updateStandaloneTorch()
            }
        }
        override fun onTorchModeUnavailable(cameraId: String) {
            if (cameraId == standaloneTorchId) standaloneTorchOn = false
        }
    }
    init {
        mutable.value = mutable.value.copy(hasTorch = flashCameras.isNotEmpty())
        cameraManager.registerTorchCallback(torchCallback, main)
    }
    fun torch(enabled: Boolean) {
        if (enabled && (!mutable.value.active || !mutable.value.hasTorch)) return
        mutable.value = mutable.value.copy(torch = enabled)
        capturer?.setTorch(enabled)
        if (enabled) updateStandaloneTorch() else releaseStandaloneTorch()
    }
    private fun updateStandaloneTorch() {
        val id = standaloneTorchId ?: return
        if (!mutable.value.active || !mutable.value.torch || switchingCamera || cameraName in flashCameras || standaloneTorchOn) return
        standaloneTorchOn = runCatching { cameraManager.setTorchMode(id, true) }.isSuccess
    }
    private fun releaseStandaloneTorch() {
        if (standaloneTorchOn) {
            runCatching { cameraManager.setTorchMode(requireNotNull(standaloneTorchId), false) }
            standaloneTorchOn = false
        }
    }
    fun flip() {
        val camera = capturer ?: return
        val current = cameraName ?: return
        if (switchingCamera) return
        val enumerator = Camera2Enumerator(context)
        val candidates = enumerator.deviceNames.filter { it != current }
        val target = if (enumerator.isFrontFacing(current))
            candidates.firstOrNull { enumerator.isBackFacing(it) && it in flashCameras }
                ?: candidates.firstOrNull(enumerator::isBackFacing) ?: candidates.firstOrNull()
        else candidates.firstOrNull(enumerator::isFrontFacing) ?: candidates.firstOrNull()
        if (target == null) return
        switchingCamera = true
        releaseStandaloneTorch()
        camera.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                main.post {
                    if (capturer !== camera || closed) return@post
                    switchingCamera = false
                    cameraName = target
                    camera.setTorch(mutable.value.torch)
                    updateStandaloneTorch()
                    // The frame observer supplies the new lens size without another camera restart.
                }
            }
            override fun onCameraSwitchError(errorDescription: String) {
                main.post {
                    if (capturer !== camera || closed) return@post
                    switchingCamera = false
                    updateStandaloneTorch()
                    reportError(errorDescription)
                }
            }
        }, target)
    }
    fun devices(): List<AudioDeviceInfo> = audioManager.availableCommunicationDevices
    fun inputs(): List<AudioDeviceInfo> = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).filter {
        it.type in setOf(AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE)
    }.distinctBy { it.type to it.productName }
    fun output(device: AudioDeviceInfo?) {
        selectedOutput = device?.id
        routeAudio()
    }
    fun input(device: AudioDeviceInfo?) {
        selectedInput = device?.id
        applyInput()
    }
    private fun routeAudio() {
        if (!mutable.value.active) return
        val devices = devices()
        val requested = devices.firstOrNull { it.id == selectedOutput }
        if (requested == null) selectedOutput = null
        val target = requested ?: devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
        if (target == null) audioManager.clearCommunicationDevice()
        else if (!audioManager.setCommunicationDevice(target) && requested != null) reportError("Audio output is unavailable")
        applyInput()
        refreshOutput()
    }
    private fun applyInput() {
        val device = inputs().firstOrNull { it.id == selectedInput }
        if (device == null) selectedInput = null
        adm.setPreferredInputDevice(device)
        mutable.value = mutable.value.copy(input = device?.let(::outputName) ?: "System default", inputDeviceId = device?.id)
    }
    fun refreshOutput() {
        val device = audioManager.communicationDevice
        mutable.value = mutable.value.copy(output = device?.let(::outputName) ?: "Phone audio", outputDeviceId = device?.id)
    }
    private val deviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { audioDevicesChanged() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { audioDevicesChanged() }

        private fun audioDevicesChanged() {
            routeAudio()
            mutable.value = mutable.value.copy(audioDeviceRevision = mutable.value.audioDeviceRevision + 1)
        }
    }
    init { audioManager.registerAudioDeviceCallback(deviceCallback, main) }

    private fun announce() {
        val s = mutable.value
        session.send("call", JSONObject().put("active", s.active).put("mic", s.mic).put("camera", s.camera || s.sharing))
    }
    private fun announceScreenShare() {
        sendAuthenticated(JSONObject().put("type", "screenShare").put("enabled", mutable.value.sharing))
    }

    fun members(room: TogetherState) {
        if (closed) return
        val me = room.members.firstOrNull { it.id == room.member }
        val other = room.members.firstOrNull { it.id != room.member }
        if (!room.connected || !mutable.value.active) return
        if (me?.call == false) {
            announce()
            return
        }
        if (other?.online == false) {
            signalingOnline = false
            callFlagsMayBeStale = true
            if (peer != null && !mediaConnected()) scheduleRecovery()
            return
        }
        if (callFlagsMayBeStale) {
            callFlagsMayBeStale = false
            waitForCallFlagsUntil = android.os.SystemClock.elapsedRealtime() + 7000
        }
        if (other?.call == false && peer != null) {
            val remaining = waitForCallFlagsUntil - android.os.SystemClock.elapsedRealtime()
            if (remaining > 0) {
                main.removeCallbacks(checkCallFlags)
                main.postDelayed(checkCallFlags, remaining)
                return
            }
        }
        if (other == null || !other.call) {
            if (peer != null && wasConnected) partnerEnded()
            else {
                if (authentication != null || peer != null) resetAuthentication()
                if (mutable.value.connection != "Partner ended the call") {
                    mutable.value = mutable.value.copy(connection =
                        if (room.pairedCallId != null) "Connecting…" else "Waiting for your partner")
                }
            }
            return
        }
        main.removeCallbacks(checkCallFlags)
        waitForCallFlagsUntil = 0
        if (authenticatingRoom != room.room || authenticatingPeer != other.id || authenticatingRoomSecret != session.roomCallSecret) {
            resetAuthentication()
            val roomSecret = session.roomCallSecret
            val exchange = runCatching {
                if (roomSecret == null && room.pairKind == null) null
                else identity.exchange(room.room, room.member, other.id, roomSecret)
            }.getOrElse {
                mutable.value = mutable.value.copy(connection = "Verification unavailable", error = "Android could not open the device’s call key.")
                return
            }
            if (exchange == null) {
                if (mutable.value.connection != "Secure pairing required") {
                    mutable.value = mutable.value.copy(connection = "Secure pairing required",
                        error = "Join through a new secure room link, or securely pair both phones before calling.")
                }
                return
            }
            authentication = exchange
            authenticatingRoom = room.room
            authenticatingPeer = other.id
            authenticatingRoomSecret = roomSecret
            authenticationStarted = android.os.SystemClock.elapsedRealtime()
            mutable.value = mutable.value.copy(connection = "Verifying call identity")
            retryAuthentication.run()
        }
        if (authentication?.ready != true) return
        val signalingRestored = !signalingOnline
        if (remoteId != other.id || peer == null) {
            createPeer(other.id, me?.ordinal == 0)
        }
        signalingOnline = true
        if (offerer && negotiationId == null && !negotiating) createOffer(restart = false)
        if (signalingRestored) {
            resendDescription()
            announceScreenShare()
            background(inBackground)
            if (recovering) scheduleRecovery(0)
        }
    }

    private fun createPeer(id: String, initiatesOffers: Boolean) {
        disposePeer()
        remoteId = id
        offerer = initiatesOffers
        val servers = session.iceServers.objects().map { obj ->
            val urls = obj.getJSONArray("urls").let { a -> (0 until a.length()).map(a::getString) }
            PeerConnection.IceServer.builder(urls).setUsername(obj.optString("username"))
                .setPassword(obj.optString("credential")).createIceServer()
        }
        val config = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            turnPortPrunePolicy = PeerConnection.PortPrunePolicy.PRUNE_BASED_ON_PRIORITY
            if (forceRelay) iceTransportsType = PeerConnection.IceTransportsType.RELAY
        }
        val run = peerGeneration
        peer = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                main.post {
                    if (run != peerGeneration || closed) return@post
                    when (state) {
                        PeerConnection.IceConnectionState.FAILED -> scheduleRecovery(0)
                        PeerConnection.IceConnectionState.DISCONNECTED -> scheduleRecovery()
                        PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED ->
                            if (mediaConnected()) connected()
                        else -> Unit
                    }
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                main.post {
                    if (run != peerGeneration || closed) return@post
                    when (state) {
                        PeerConnection.PeerConnectionState.CONNECTED -> if (mediaConnected()) connected()
                        PeerConnection.PeerConnectionState.DISCONNECTED -> scheduleRecovery()
                        PeerConnection.PeerConnectionState.FAILED -> scheduleRecovery(0)
                        else -> Unit
                    }
                }
            }
            override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {
                main.post { if (run == peerGeneration && !closed && mediaConnected()) connected() }
            }
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(candidate: IceCandidate) {
                main.post {
                    if (run != peerGeneration || closed || localCandidates.size >= 128) return@post
                    val payload = JSONObject().put("type", "candidate").put("candidate", candidate.sdp)
                        .put("mid", candidate.sdpMid).put("line", candidate.sdpMLineIndex).put("negotiation", negotiationId)
                    localCandidates.add(payload)
                    if (localSdp != null) sendAuthenticated(payload)
                }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) { channel.close() }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
                val track = receiver.track()
                if (track is AudioTrack) main.post {
                    if (run == peerGeneration && !closed) {
                        remoteAudio = track
                        track.setEnabled(!mutable.value.remoteMuted)
                    }
                }
                if (track is VideoTrack) main.post { if (run == peerGeneration && !closed) mutable.value = mutable.value.copy(remoteVideo = track) }
            }
        }) ?: error("Unable to create call connection")
        peer?.addTrack(audioTrack, listOf("saoreo-call"))
        videoSender = peer?.addTrack(screenTrack ?: videoTrack, listOf("saoreo-call"))
        configureVideoSender()
        mutable.value = mutable.value.copy(connection = "Connecting call", error = null, retryAvailable = false)
        main.post(sampleStats)
        // Also cover initial setup where signaling or ICE stalls without emitting FAILED.
        main.postDelayed(recover, 15_000)
    }

    private fun configureVideoSender() {
        val sender = videoSender ?: return
        val params = sender.parameters
        val sharing = screenTrack != null
        val mode = mutable.value.qualityMode
        val profile = videoQuality.profile
        params.degradationPreference = if (sharing || mode != CallQualityMode.AUTOMATIC)
            RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION else RtpParameters.DegradationPreference.BALANCED
        params.encodings.forEach {
            it.active = sharing || profile.height > 0
            it.maxBitrateBps = if (sharing) 2_500_000 else mode.bitrateCap
            it.maxFramerate = if (sharing) 15 else profile.fps.coerceAtLeast(1)
            it.scaleResolutionDownBy = if (sharing || profile.height == 0) 1.0
                else (captureHeight.toDouble() / profile.height).coerceAtLeast(1.0)
        }
        val accepted = sender.setParameters(params)
        if (!accepted) android.util.Log.w("TogetherCall", "Could not apply outgoing video settings")
        appliedProfile = profile.takeIf { accepted && !sharing }
    }

    internal fun diagnostics(callback: (String) -> Unit) {
        diagnosticsState.value?.let(callback)
    }

    private fun mediaConnected(): Boolean = peer?.let {
        it.connectionState() == PeerConnection.PeerConnectionState.CONNECTED &&
            it.iceConnectionState() in setOf(PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED)
    } == true

    private var firstMediaWallTime = 0L
    private var firstMediaTick = 0L

    private fun connected() {
        if (negotiating || recovering && offerer && peer?.signalingState() != PeerConnection.SignalingState.STABLE) return
        if (mutable.value.connectedAtMs == 0L && firstMediaWallTime == 0L) {
            firstMediaWallTime = System.currentTimeMillis()
            firstMediaTick = android.os.SystemClock.elapsedRealtime()
            session.state.value.pairedCallId?.let { id ->
                (context.applicationContext as? SaoreoApp)?.pairing?.callMedia(id, firstMediaWallTime, 0L, false)
            }
        }
        wasConnected = true
        recovering = false
        recoveryAttempts = 0
        main.removeCallbacks(recover)
        mutable.value = mutable.value.copy(connection = "Connected", retryAvailable = false,
            connectedAtMs = mutable.value.connectedAtMs.takeIf { it > 0 } ?: android.os.SystemClock.elapsedRealtime())
    }

    fun retryConnection() {
        if (!mutable.value.active || peer == null) return
        recoveryAttempts = 0
        recovering = false
        mutable.value = mutable.value.copy(retryAvailable = false)
        scheduleRecovery(0)
    }

    private fun scheduleRecovery(delayMs: Long = 2500) {
        if (!mutable.value.active || closed || peer == null) return
        val starting = !recovering
        recovering = true
        mutable.value = mutable.value.copy(connection = "Reconnecting…", retryAvailable = false, mediaWarning = null)
        if (!main.hasCallbacks(recover) || starting || delayMs == 0L) {
            main.removeCallbacks(recover)
            main.postDelayed(recover, delayMs)
        }
    }

    private fun recoveryStep() {
        val pc = peer ?: return
        val signalingReady = session.state.value.connected && signalingOnline && connectivity.activeNetwork != null
        when (CallRecoveryPolicy.action(mutable.value.active && !closed, mediaConnected() && !recovering, signalingReady)) {
            CallRecoveryAction.STOP -> return
            CallRecoveryAction.CONNECTED -> { connected(); return }
            CallRecoveryAction.WAIT -> {
                recovering = true
                mutable.value = mutable.value.copy(connection = "Waiting for connection", mediaWarning = null)
                main.postDelayed(recover, 2000)
                return
            }
            CallRecoveryAction.RESTART -> Unit
        }
        recovering = true
        mutable.value = mutable.value.copy(connection = "Reconnecting…", mediaWarning = null,
            retryAvailable = recoveryAttempts >= CallRecoveryPolicy.lastBackoffIndex)
        val delayMs = CallRecoveryPolicy.delay(recoveryAttempts++)
        if (offerer) {
            when {
                pc.signalingState() == PeerConnection.SignalingState.STABLE && !negotiating -> createOffer(restart = true)
                pc.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER &&
                    recoveryAttempts % 5 == 0 -> {
                    negotiating = true
                    pc.setLocalDescription(sdpObserver(onSet = {
                        negotiating = false
                        createOffer(restart = true)
                    }), SessionDescription(SessionDescription.Type.ROLLBACK, ""))
                }
                else -> resendDescription()
            }
        } else {
            sendAuthenticated(JSONObject().put("type", "restartRequest"))
            resendDescription()
        }
        main.postDelayed(recover, delayMs)
    }

    private fun beginDescription(id: String?) {
        negotiationId = id
        negotiating = true
        hasRemoteDescription = false
        remoteSdp = null
        localSdp = null
        localCandidates.clear()
        remoteCandidates.clear()
    }

    private fun createOffer(restart: Boolean) {
        val pc = peer ?: return
        beginDescription(java.util.UUID.randomUUID().toString())
        if (restart) pc.restartIce()
        pc.createOffer(sdpObserver(onCreated = ::localDescription), MediaConstraints())
    }

    private fun resendDescription() {
        val description = localSdp ?: return
        if (sendAuthenticated(description)) localCandidates.forEach(::sendAuthenticated)
    }

    private fun signal(data: JSONObject) {
        if (!mutable.value.active || closed) return
        val room = session.state.value
        members(room)
        val exchange = authentication ?: return
        when (data.optString("type")) {
            "authHello" -> {
                if (!exchange.receiveHello(data)) return
                val pinned = identity.pairedFingerprint()
                if (authenticatingRoomSecret != null || pinned == exchange.remoteFingerprint ||
                    pinned == null && identity.rememberPaired(exchange)) {
                    approve(exchange)
                } else if (mutable.value.connection != "Partner device key changed") {
                    mutable.value = mutable.value.copy(connection = "Partner device key changed",
                        error = "This paired device’s key changed. Securely pair the devices again before calling.")
                }
                return
            }
            "authReady" -> {
                if (!exchange.receiveApproval(data)) return
                if (peer == null) approval?.let(::sendSignal)
                members(room)
                return
            }
            "authenticatedSignal" -> Unit
            else -> return
        }
        val payload = exchange.open(data) ?: return
        val pc = peer ?: return
        when (payload.optString("type")) {
            "screenShare" -> mutable.value = mutable.value.copy(remoteSharing = payload.optBoolean("enabled"))
            "background" -> mutable.value = mutable.value.copy(remoteBackground = payload.optBoolean("enabled"))
            "hangup" -> partnerEnded()
            "restartRequest" -> {
                if (offerer && !recovering) scheduleRecovery(0)
                else if (offerer) resendDescription()
            }
            "offer", "answer" -> {
                val offer = payload.optString("type") == "offer"
                val description = payload.optString("sdp")
                val id = payload.optString("negotiation").takeIf { it.isNotEmpty() }
                if (!secureFingerprint.containsMatchIn(description) || offer == offerer) return
                if (offer && description == remoteSdp) {
                    resendDescription()
                    return
                }
                if (offer) {
                    if (pc.signalingState() != PeerConnection.SignalingState.STABLE || negotiating) return
                    beginDescription(id)
                } else if (pc.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER ||
                    id != null && id != negotiationId || description == remoteSdp) return
                remoteSdp = description
                pc.setRemoteDescription(sdpObserver(onSet = {
                    hasRemoteDescription = true
                    remoteCandidates.forEach { pc.addIceCandidate(it) }
                    remoteCandidates.clear()
                    if (offer) pc.createAnswer(sdpObserver(onCreated = ::localDescription), MediaConstraints())
                    else {
                        negotiating = false
                        if (mediaConnected()) connected()
                    }
                }), SessionDescription(if (offer) SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER, description))
            }
            "candidate" -> {
                val id = payload.optString("negotiation")
                if (id.isNotEmpty() && id != negotiationId) return
                val candidate = IceCandidate(payload.optString("mid"), payload.getInt("line"), payload.getString("candidate"))
                if (hasRemoteDescription) pc.addIceCandidate(candidate)
                else if (remoteCandidates.size < 128) remoteCandidates.add(candidate)
            }
        }
    }

    private fun localDescription(description: SessionDescription) {
        if (!secureFingerprint.containsMatchIn(description.description)) {
            stop()
            reportError("The call cannot negotiate a SHA-256 media fingerprint.")
            return
        }
        peer?.setLocalDescription(sdpObserver(onSet = {
            localSdp = JSONObject().put("type", description.type.canonicalForm())
                .put("sdp", description.description).put("negotiation", negotiationId)
            resendDescription()
            if (description.type == SessionDescription.Type.ANSWER) {
                negotiating = false
                if (mediaConnected()) connected()
            }
        }), description)
    }
    private fun sendSignal(payload: JSONObject) = session.send("signal", JSONObject().put("payload", payload))

    private fun sendAuthenticated(payload: JSONObject): Boolean {
        val exchange = authentication?.takeIf { it.ready } ?: return false
        return runCatching { sendSignal(exchange.seal(payload)) }.getOrElse {
            resetAuthentication()
            reportError("Signing the call setup failed. End and rejoin the call.")
            false
        }
    }

    private fun approve(exchange: CallAuthentication) {
        if (approval == null) {
            exchange.confirm()
            approval = exchange.approval()
            sendSignal(exchange.hello)
            approval?.let(::sendSignal)
        }
        members(session.state.value)
        background(inBackground)
    }

    private fun resetAuthentication() {
        main.removeCallbacks(retryAuthentication)
        authentication = null
        authenticatingRoom = null
        authenticatingPeer = null
        authenticatingRoomSecret = null
        approval = null
        disposePeer()
    }
    private fun sdpObserver(onCreated: (SessionDescription) -> Unit = {}, onSet: () -> Unit = {}): SdpObserver {
        val run = peerGeneration
        return object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) { main.post { if (run == peerGeneration && !closed) onCreated(description) } }
            override fun onSetSuccess() { main.post { if (run == peerGeneration && !closed) onSet() } }
            override fun onCreateFailure(error: String) { failed(error) }
            override fun onSetFailure(error: String) { failed(error) }
            private fun failed(error: String) { main.post {
                if (run != peerGeneration || closed) return@post
                android.util.Log.w("TogetherCall", "Call description negotiation failed: $error")
                remoteSdp = null
                negotiating = false
                scheduleRecovery()
            } }
        }
    }

    private fun disposePeer() {
        main.removeCallbacks(recover)
        main.removeCallbacks(sampleStats)
        main.removeCallbacks(checkCallFlags)
        callFlagsMayBeStale = false; waitForCallFlagsUntil = 0
        videoSender = null
        peerGeneration++; peer?.close(); peer?.dispose(); peer = null; remoteId = null
        negotiationId = null; negotiating = false; hasRemoteDescription = false
        remoteSdp = null; localSdp = null; remoteCandidates.clear(); localCandidates.clear()
        signalingOnline = false; recovering = false; wasConnected = false; recoveryAttempts = 0
        statsSampler = CallStatsSampler()
        diagnosticsState.value = null
        appliedProfile = null
        warningSamples = 0
        mutable.value = mutable.value.copy(remoteVideo = null, remoteSharing = false, retryAvailable = false, connectedAtMs = 0L, mediaWarning = null)
    }
    private fun partnerEnded() {
        stopCall(notifyPartner = false)
        mutable.value = mutable.value.copy(connection = "Partner ended the call")
    }

    fun stop() = stopCall(notifyPartner = true)

    private fun stopCall(notifyPartner: Boolean) {
        if (mutable.value.active) session.state.value.pairedCallId?.let { id ->
            val connected = firstMediaTick
            val duration = if (connected > 0) (android.os.SystemClock.elapsedRealtime() - connected).coerceAtLeast(0L) else 0L
            (context.applicationContext as? SaoreoApp)?.pairing?.callMedia(id, firstMediaWallTime, duration, true)
        }
        firstMediaWallTime = 0L
        firstMediaTick = 0L
        main.removeCallbacks(networkChanged)
        if (monitoringNetwork) {
            connectivity.unregisterNetworkCallback(networkCallback)
            monitoringNetwork = false
        }
        checkedNetwork = null
        if (notifyPartner && mutable.value.active) sendAuthenticated(JSONObject().put("type", "hangup"))
        mutable.value = mutable.value.copy(active = false)
        muteRemote(false)
        remoteAudio = null
        torch(false)
        stopScreenShare()
        camera(false); audioTrack.setEnabled(false); resetAuthentication()
        mutable.value = mutable.value.copy(active = false, mic = false, camera = false, connection = "Call is off", error = null)
        announce()
        TogetherActiveCallService.end(context, this)
        selectedOutput = null
        selectedInput = null
        adm.setPreferredInputDevice(null)
        audioManager.clearCommunicationDevice(); audioManager.mode = oldMode
    }
    fun hangUp() {
        stop()
        if (session.state.value.pairKind in setOf(PairKind.AUDIO, PairKind.VIDEO) && session.state.value.media == null) session.leave()
    }
    fun reportError(error: String) { mutable.value = mutable.value.copy(error = error) }

    fun shareScreen(result: Intent) {
        check(mutable.value.active && screenCapturer == null)
        val capture = ScreenCapturerAndroid(result, object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopScreenShare() } }
        })
        screenCapturer = capture
        try {
            val source = factory.createVideoSource(true).also { screenSource = it }
            val helper = SurfaceTextureHelper.create("SAOREO-screen", egl.eglBaseContext).also { screenTexture = it }
            capture.initialize(helper, context, source.capturerObserver)
            val metrics = context.resources.displayMetrics
            val scale = minOf(1f, 1280f / maxOf(metrics.widthPixels, metrics.heightPixels))
            capture.startCapture((metrics.widthPixels * scale).toInt(), (metrics.heightPixels * scale).toInt(), 15)
            val track = factory.createVideoTrack("saoreo-screen", source).also { screenTrack = it }
            check(videoSender?.setTrack(track, false) != false)
            camera(false)
            configureVideoSender()
            mutable.value = mutable.value.copy(sharing = true, localVideo = track)
            announceScreenShare()
            announce()
        } catch (error: RuntimeException) {
            stopScreenShare()
            throw error
        }
    }

    fun stopScreenShare() {
        val capture = screenCapturer ?: return
        screenCapturer = null
        videoSender?.setTrack(videoTrack, false)
        runCatching { capture.stopCapture() }
        capture.dispose()
        screenTrack?.dispose(); screenTrack = null
        configureVideoSender()
        screenSource?.dispose(); screenSource = null
        screenTexture?.dispose(); screenTexture = null
        mutable.value = mutable.value.copy(sharing = false, localVideo = videoTrack)
        TogetherActiveCallService.projectionEnded(this)
        announceScreenShare()
        announce()
    }
    private fun networkLost() {
        if (closed) return
        signalingOnline = false
        callFlagsMayBeStale = true
        if (mutable.value.active && peer != null && !mediaConnected()) scheduleRecovery()
    }
    override fun close() {
        if (closed) return
        stop(); closed = true; session.onSignal = null; session.onConnectionLost = null; session.callOnReconnect = null
        cameraManager.unregisterTorchCallback(torchCallback)
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        audioTrack.dispose(); audioSource.dispose(); videoTrack.dispose(); videoSource.dispose()
        factory.dispose(); adm.release(); egl.release()
    }
    companion object {
        private val secureFingerprint = Regex("(?mi)^a=fingerprint:sha-256 (?:[0-9a-f]{2}:){31}[0-9a-f]{2}\\r?$")
        fun outputName(device: AudioDeviceInfo): String = when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Phone microphone"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth headset"
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET -> "Headphones"
            else -> device.productName.toString()
        }
    }
}
