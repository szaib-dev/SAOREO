package com.codetic.saoreo

import android.content.Context
import android.content.Intent
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
    val torch: Boolean = false, val hasTorch: Boolean = false, val error: String? = null, val remoteBackground: Boolean = false, val remoteSharing: Boolean = false,
    val retryAvailable: Boolean = false, val connectedAtMs: Long = 0L)

/** One peer connection per private room; signaling travels through the authenticated room socket. */
internal class TogetherCall(private val context: Context, private val session: TogetherSession, private val forceRelay: Boolean = false,
    private val identity: CallIdentity = CallIdentity(context),
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val mutable = MutableStateFlow(TogetherCallState())
    val state = mutable.asStateFlow()
    val egl: EglBase = EglBase.create()
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val oldMode = audioManager.mode
    private val adm: JavaAudioDeviceModule
    private val factory: PeerConnectionFactory
    private val audioSource: AudioSource
    private val audioTrack: AudioTrack
    private val videoSource: VideoSource
    private val videoTrack: VideoTrack
    private var capturer: CameraVideoCapturer? = null
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
    private var recoveryStarted = 0L
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
                if (checkedNetwork != null && checkedNetwork != network) {
                    main.removeCallbacks(networkChanged)
                    main.postDelayed(networkChanged, 1500)
                }
                checkedNetwork = network
            }
        }
    }
    private var statsSampler = CallStatsSampler()
    private var videoQuality = CallVideoQuality()
    private var profile = CallVideoProfile.levels[3]
    private var captureHeight = 720
    private var fullHdCamera = false
    private var lastDiagnostics: String? = null
    private val sampleStats = object : Runnable {
        override fun run() {
            val pc = peer ?: return
            val run = peerGeneration
            pc.getStats { report -> main.post {
                if (run != peerGeneration || closed) return@post
                val stats = statsSampler.read(report)
                lastDiagnostics = stats.diagnostic
                if (recovering && recoveryAttempts > 0 && !negotiating && mediaConnected()) connected()
                if (mutable.value.camera && mediaConnected()) {
                    val next = videoQuality.update(stats, fullHdCamera)
                    if (next != profile) {
                        profile = next
                        if (next.height > captureHeight) {
                            capturer?.changeCaptureFormat(1920, 1080, 30)
                            captureHeight = 1080
                        }
                        configureVideoSender()
                    }
                }
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

    fun start(camera: Boolean) {
        if (closed) return
        if (mutable.value.active) return
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        mutable.value = mutable.value.copy(active = true, mic = true, connection = "Waiting for your partner", error = null)
        audioTrack.setEnabled(true)
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
    fun microphone(enabled: Boolean) {
        audioTrack.setEnabled(enabled && mutable.value.active)
        mutable.value = mutable.value.copy(mic = enabled && mutable.value.active)
        announce()
    }
    fun camera(enabled: Boolean) {
        if (enabled && !mutable.value.active) return
        if (enabled && mutable.value.sharing) stopScreenShare()
        try {
            if (enabled && capturer == null) {
                val enumerator = Camera2Enumerator(context)
                val name = enumerator.deviceNames.firstOrNull(enumerator::isFrontFacing) ?: enumerator.deviceNames.firstOrNull()
                    ?: error("No camera is available")
                capturer = enumerator.createCapturer(name, null) ?: error("Camera could not be opened")
                texture = SurfaceTextureHelper.create("SAOREO-camera", egl.eglBaseContext)
                capturer!!.initialize(texture, context, videoSource.capturerObserver)
                fullHdCamera = enumerator.getSupportedFormats(name).orEmpty().any {
                    maxOf(it.width, it.height) >= 1920 && minOf(it.width, it.height) >= 1080 && it.framerate.max >= 30_000
                }
                captureHeight = 720
                videoQuality = CallVideoQuality()
                profile = CallVideoProfile.levels[3]
                capturer!!.startCapture(1280, captureHeight, 30)
                configureVideoSender()
            } else if (!enabled && capturer != null) {
                capturer?.stopCapture(); capturer?.dispose(); capturer = null
                texture?.dispose(); texture = null
            }
            videoTrack.setEnabled(enabled)
            mutable.value = mutable.value.copy(camera = enabled, error = null)
        } catch (e: Exception) {
            videoTrack.setEnabled(false)
            mutable.value = mutable.value.copy(camera = false, error = e.message ?: "Camera unavailable")
        }
        announce()
    }
    private val cameraManager = context.getSystemService(android.hardware.camera2.CameraManager::class.java)
    private val torchId = runCatching { cameraManager.cameraIdList.firstOrNull {
        cameraManager.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    } }.getOrNull()
    private val torchCallback = object : android.hardware.camera2.CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (!closed && cameraId == torchId) mutable.value = mutable.value.copy(torch = enabled)
        }
        override fun onTorchModeUnavailable(cameraId: String) {
            if (!closed && cameraId == torchId) mutable.value = mutable.value.copy(torch = false)
        }
    }
    init {
        mutable.value = mutable.value.copy(hasTorch = torchId != null)
        cameraManager.registerTorchCallback(torchCallback, main)
    }
    fun torch(enabled: Boolean) {
        val id = torchId ?: return
        runCatching { cameraManager.setTorchMode(id, enabled) }
            .onSuccess { mutable.value = mutable.value.copy(torch = enabled) }
            .onFailure { if (enabled) reportError("Torch is unavailable while this camera is in use. Try the front camera.") }
    }
    fun flip() {
        torch(false)
        val camera = capturer ?: return
        camera.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                main.post {
                    if (capturer !== camera || closed) return@post
                    val enumerator = Camera2Enumerator(context)
                    val names = enumerator.deviceNames.filter { enumerator.isFrontFacing(it) == isFrontCamera }
                    fullHdCamera = names.size == 1 && enumerator.getSupportedFormats(names.single()).orEmpty().any {
                        maxOf(it.width, it.height) >= 1920 && minOf(it.width, it.height) >= 1080 && it.framerate.max >= 30_000
                    }
                    if (!fullHdCamera && captureHeight > 720) {
                        captureHeight = 720
                        profile = CallVideoProfile.levels[3]
                        videoQuality = CallVideoQuality()
                        camera.changeCaptureFormat(1280, 720, 30)
                        configureVideoSender()
                    }
                }
            }
            override fun onCameraSwitchError(errorDescription: String) {
                main.post { if (!closed) reportError(errorDescription) }
            }
        })
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
        mutable.value = mutable.value.copy(input = device?.let(::outputName) ?: "System default")
    }
    fun refreshOutput() {
        val device = audioManager.communicationDevice
        mutable.value = mutable.value.copy(output = device?.let(::outputName) ?: "Phone audio")
    }
    private val deviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { routeAudio() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { routeAudio() }
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
                    mutable.value = mutable.value.copy(connection = "Waiting for your partner")
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
        params.degradationPreference = if (sharing) RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            else RtpParameters.DegradationPreference.BALANCED
        params.encodings.forEach {
            it.maxBitrateBps = if (sharing) 2_500_000 else if (captureHeight > 720) 4_000_000 else 2_500_000
            it.maxFramerate = if (sharing) 15 else profile.fps
            it.scaleResolutionDownBy = if (sharing) 1.0 else (captureHeight.toDouble() / profile.height).coerceAtLeast(1.0)
        }
        if (!sender.setParameters(params)) android.util.Log.w("TogetherCall", "Unable to apply video sender limits")
    }

    internal fun diagnostics(callback: (String) -> Unit) {
        lastDiagnostics?.let(callback)
    }

    private fun mediaConnected(): Boolean = peer?.let {
        it.connectionState() == PeerConnection.PeerConnectionState.CONNECTED &&
            it.iceConnectionState() in setOf(PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED)
    } == true

    private fun connected() {
        if (negotiating || recovering && offerer && peer?.signalingState() != PeerConnection.SignalingState.STABLE) return
        wasConnected = true
        recovering = false
        recoveryAttempts = 0
        recoveryStarted = 0L
        main.removeCallbacks(recover)
        mutable.value = mutable.value.copy(connection = "Connected", retryAvailable = false,
            connectedAtMs = mutable.value.connectedAtMs.takeIf { it > 0 } ?: android.os.SystemClock.elapsedRealtime())
    }

    fun retryConnection() {
        if (!mutable.value.active || peer == null) return
        recoveryAttempts = 0
        recoveryStarted = 0L
        recovering = false
        mutable.value = mutable.value.copy(retryAvailable = false)
        scheduleRecovery(0)
    }

    private fun scheduleRecovery(delayMs: Long = 2500) {
        if (peer == null || mutable.value.retryAvailable) return
        val starting = !recovering
        if (starting) recoveryStarted = android.os.SystemClock.elapsedRealtime()
        recovering = true
        mutable.value = mutable.value.copy(connection = "Reconnecting…", retryAvailable = false)
        if (!main.hasCallbacks(recover) || starting) {
            main.removeCallbacks(recover)
            main.postDelayed(recover, delayMs)
        }
    }

    private fun recoveryStep() {
        val pc = peer ?: return
        if (mediaConnected() && !recovering) { connected(); return }
        val now = android.os.SystemClock.elapsedRealtime()
        if (recoveryStarted == 0L) recoveryStarted = now
        if (recoveryAttempts >= recoveryBackoff.size || now - recoveryStarted >= 75_000) {
            recovering = false
            mutable.value = mutable.value.copy(connection = "Connection unavailable", retryAvailable = true)
            return
        }
        recovering = true
        mutable.value = mutable.value.copy(connection = "Reconnecting…", retryAvailable = false)
        if (!session.state.value.connected || !signalingOnline) {
            main.postDelayed(recover, 2000)
            return
        }
        val delayMs = recoveryBackoff[recoveryAttempts++]
        if (offerer) {
            if (pc.signalingState() == PeerConnection.SignalingState.STABLE && !negotiating) createOffer(restart = true)
            else resendDescription()
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
                if (offerer && mutable.value.retryAvailable) retryConnection()
                else if (offerer && !recovering) scheduleRecovery(0)
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
        signalingOnline = false; recovering = false; wasConnected = false; recoveryAttempts = 0; recoveryStarted = 0L
        statsSampler = CallStatsSampler(); lastDiagnostics = null
        mutable.value = mutable.value.copy(remoteVideo = null, remoteSharing = false, retryAvailable = false, connectedAtMs = 0L)
    }
    private fun partnerEnded() {
        stopCall(notifyPartner = false)
        mutable.value = mutable.value.copy(connection = "Partner ended the call")
    }

    fun stop() = stopCall(notifyPartner = true)

    private fun stopCall(notifyPartner: Boolean) {
        main.removeCallbacks(networkChanged)
        if (monitoringNetwork) {
            connectivity.unregisterNetworkCallback(networkCallback)
            monitoringNetwork = false
        }
        checkedNetwork = null
        if (notifyPartner && mutable.value.active) sendAuthenticated(JSONObject().put("type", "hangup"))
        mutable.value = mutable.value.copy(active = false)
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
        private val recoveryBackoff = longArrayOf(3500, 5500, 8000, 12000, 15000)
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
