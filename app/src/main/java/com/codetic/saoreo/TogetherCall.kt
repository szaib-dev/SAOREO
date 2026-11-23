package com.codetic.saoreo

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.projection.MediaProjection
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
    val error: String? = null, val remoteBackground: Boolean = false, val remoteSharing: Boolean = false)

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
    private var hasRemoteDescription = false
    private var offerStarted = false
    private val candidates = mutableListOf<IceCandidate>()
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
        factory = PeerConnectionFactory.builder().setOptions(PeerConnectionFactory.Options().apply {
            // Emulator synthetic cellular handles reject bindSocket and can replace a working Wi-Fi pair.
            // Physical devices retain native network monitoring and continual ICE gathering.
            disableNetworkMonitor = emulator
        }).setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
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
    }

    fun start(camera: Boolean) {
        if (closed) return
        if (mutable.value.active) return
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        mutable.value = mutable.value.copy(active = true, mic = true, connection = "Waiting for your partner", error = null)
        audioTrack.setEnabled(true)
        if (camera) camera(true)
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
        if (!mutable.value.active) return
        if (enabled && mutable.value.sharing) stopScreenShare()
        try {
            if (enabled && capturer == null) {
                val enumerator = Camera2Enumerator(context)
                val name = enumerator.deviceNames.firstOrNull(enumerator::isFrontFacing) ?: enumerator.deviceNames.firstOrNull()
                    ?: error("No camera is available")
                capturer = enumerator.createCapturer(name, null) ?: error("Camera could not be opened")
                texture = SurfaceTextureHelper.create("SAOREO-camera", egl.eglBaseContext)
                capturer!!.initialize(texture, context, videoSource.capturerObserver)
                capturer!!.startCapture(640, 360, 20)
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
    fun flip() { capturer?.switchCamera(null) }
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
        val other = room.members.firstOrNull { it.id != room.member && it.online && it.call }
        if (!room.connected) return
        if (mutable.value.active && me?.call == false) {
            if (authentication != null || peer != null) resetAuthentication()
            announce()
            return
        }
        if (!mutable.value.active || other == null) {
            if (authentication != null || peer != null) resetAuthentication()
            if (mutable.value.active) mutable.value = mutable.value.copy(connection = "Waiting for your partner")
            return
        }
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
        if (remoteId != other.id || peer == null) {
            createPeer(other.id)
            announceScreenShare()
        }
        if (me?.ordinal == 0 && !offerStarted) {
            offerStarted = true
            val run = peerGeneration
            peer?.createOffer(sdpObserver(onCreated = { description ->
                if (run == peerGeneration) localDescription(description)
            }), MediaConstraints())
        }
    }
    private fun createPeer(id: String) {
        disposePeer()
        remoteId = id
        val servers = session.iceServers.objects().map { obj ->
            val urls = obj.getJSONArray("urls").let { a -> (0 until a.length()).map(a::getString) }
            PeerConnection.IceServer.builder(urls).setUsername(obj.optString("username"))
                .setPassword(obj.optString("credential")).createIceServer()
        }
        val config = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            if (forceRelay) iceTransportsType = PeerConnection.IceTransportsType.RELAY
        }
        val run = peerGeneration
        peer = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                main.post {
                    if (run != peerGeneration || closed) return@post
                    mutable.value = mutable.value.copy(connection = when (state) {
                        PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED ->
                            if (mutable.value.connection == "Connected") "Connected" else "Establishing encrypted call"
                        PeerConnection.IceConnectionState.FAILED -> "Connection failed - reconnect call"
                        PeerConnection.IceConnectionState.DISCONNECTED -> "Reconnecting call"
                        else -> "Connecting call"
                    })
                    if (state == PeerConnection.IceConnectionState.FAILED) {
                        mutable.value = mutable.value.copy(error = "Call connection failed. End and rejoin the call.")
                    }
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                main.post {
                    if (run == peerGeneration && !closed && state == PeerConnection.PeerConnectionState.CONNECTED)
                        mutable.value = mutable.value.copy(connection = "Connected")
                }
            }
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(candidate: IceCandidate) {
                main.post { if (run == peerGeneration) sendAuthenticated(JSONObject()
                    .put("type", "candidate").put("candidate", candidate.sdp).put("mid", candidate.sdpMid).put("line", candidate.sdpMLineIndex)) }
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
        val sender = peer?.addTrack(screenTrack ?: videoTrack, listOf("saoreo-call"))
        videoSender = sender
        // Cap camera bitrate; the watched video never goes through this connection.
        sender?.let {
            val params = sender.parameters
            params.encodings.forEach { it.maxBitrateBps = 650_000; it.maxFramerate = 20 }
            sender.setParameters(params)
        }
        mutable.value = mutable.value.copy(connection = "Connecting call", error = null)
    }

    internal fun diagnostics(callback: (String) -> Unit) {
        peer?.getStats { report ->
            if (closed) return@getStats
            val stats = report.statsMap.values.filter { it.type in setOf("inbound-rtp", "outbound-rtp", "transport") }.map { stat ->
                stat.type + ":" + stat.members.filterKeys { it in setOf("kind", "mediaType", "framesEncoded", "framesDecoded", "bytesSent", "bytesReceived", "dtlsState", "packetsReceived", "packetsSent") }
            }
            callback("camera=${mutable.value.camera}, enabled=${videoTrack.enabled()}, " + stats.joinToString("; "))
        }
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
        val run = peerGeneration
        when (payload.optString("type")) {
            "screenShare" -> mutable.value = mutable.value.copy(remoteSharing = payload.optBoolean("enabled"))
            "background" -> mutable.value = mutable.value.copy(remoteBackground = payload.optBoolean("enabled"))
            "offer", "answer" -> {
                val type = SessionDescription.Type.fromCanonicalForm(payload.getString("type"))
                val description = payload.getString("sdp")
                if (!secureFingerprint.containsMatchIn(description)) return
                pc.setRemoteDescription(sdpObserver(onSet = {
                    if (run == peerGeneration) {
                        hasRemoteDescription = true
                        candidates.toList().forEach { pc.addIceCandidate(it) }; candidates.clear()
                        if (type == SessionDescription.Type.OFFER) pc.createAnswer(sdpObserver(onCreated = { if (run == peerGeneration) localDescription(it) }), MediaConstraints())
                    }
                }), SessionDescription(type, description))
            }
            "candidate" -> {
                val c = IceCandidate(payload.optString("mid"), payload.getInt("line"), payload.getString("candidate"))
                if (hasRemoteDescription) pc.addIceCandidate(c) else if (candidates.size < 128) candidates.add(c)
            }
        }
    }
    private fun localDescription(description: SessionDescription) {
        if (!secureFingerprint.containsMatchIn(description.description)) {
            stop()
            reportError("The call cannot negotiate a SHA-256 media fingerprint.")
            return
        }
        val run = peerGeneration
        peer?.setLocalDescription(sdpObserver(onSet = {
            if (run == peerGeneration) sendAuthenticated(JSONObject()
                .put("type", description.type.canonicalForm()).put("sdp", description.description))
        }), description)
    }
    private fun sendSignal(payload: JSONObject) = session.send("signal", JSONObject().put("payload", payload))

    private fun sendAuthenticated(payload: JSONObject) {
        val exchange = authentication?.takeIf { it.ready } ?: return
        runCatching { sendSignal(exchange.seal(payload)) }.onFailure {
            resetAuthentication()
            reportError("Signing the call setup failed. End and rejoin the call.")
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
    private fun sdpObserver(onCreated: (SessionDescription) -> Unit = {}, onSet: () -> Unit = {}) = object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) { main.post { if (!closed) onCreated(description) } }
        override fun onSetSuccess() { main.post { if (!closed) onSet() } }
        override fun onCreateFailure(error: String) { failed() }
        override fun onSetFailure(error: String) { failed() }
        private fun failed() { main.post { if (!closed) mutable.value = mutable.value.copy(error = "Call negotiation failed. End and rejoin the call.") } }
    }
    private fun disposePeer() {
        videoSender = null
        peerGeneration++; peer?.close(); peer?.dispose(); peer = null; remoteId = null
        hasRemoteDescription = false; offerStarted = false; candidates.clear()
        mutable.value = mutable.value.copy(remoteVideo = null, remoteSharing = false)
    }
    fun stop() {
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
        if (session.state.value.pairKind in setOf(PairKind.AUDIO, PairKind.VIDEO)) session.leave()
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
        screenSource?.dispose(); screenSource = null
        screenTexture?.dispose(); screenTexture = null
        mutable.value = mutable.value.copy(sharing = false, localVideo = videoTrack)
        TogetherActiveCallService.projectionEnded(this)
        announceScreenShare()
        announce()
    }
    private fun networkLost() {
        if (closed) return
        if (mutable.value.active) mutable.value = mutable.value.copy(connection = "Reconnecting to Together")
    }
    override fun close() {
        if (closed) return
        stop(); closed = true; session.onSignal = null; session.onConnectionLost = null
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
