package com.codetic.saoreo

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

internal data class TogetherCallState(val active: Boolean = false, val mic: Boolean = false, val camera: Boolean = false,
    val connection: String = "Call is off", val localVideo: VideoTrack? = null, val remoteVideo: VideoTrack? = null,
    val output: String = "Phone audio", val error: String? = null)

/** One peer connection per private room; signaling travels through the authenticated room socket. */
internal class TogetherCall(private val context: Context, private val session: TogetherSession, private val forceRelay: Boolean = false) : AutoCloseable {
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
    private var peer: PeerConnection? = null
    private var remoteId: String? = null
    private var closed = false
    private var hasRemoteDescription = false
    private var offerStarted = false
    private val candidates = mutableListOf<IceCandidate>()
    private var peerGeneration = 0

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
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        mutable.value = mutable.value.copy(active = true, mic = true, connection = "Waiting for your partner", error = null)
        audioTrack.setEnabled(true)
        if (camera) camera(true)
        announce()
        members(session.state.value)
        refreshOutput()
    }
    fun microphone(enabled: Boolean) {
        audioTrack.setEnabled(enabled && mutable.value.active)
        mutable.value = mutable.value.copy(mic = enabled && mutable.value.active)
        announce()
    }
    fun camera(enabled: Boolean) {
        if (!mutable.value.active) return
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
    fun output(device: AudioDeviceInfo) {
        if (!audioManager.setCommunicationDevice(device)) mutable.value = mutable.value.copy(error = "Audio output is unavailable")
        refreshOutput()
    }
    fun refreshOutput() {
        val device = audioManager.communicationDevice
        mutable.value = mutable.value.copy(output = device?.let(::outputName) ?: "Phone audio")
    }
    private val deviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { refreshOutput() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { refreshOutput() }
    }
    init { audioManager.registerAudioDeviceCallback(deviceCallback, main) }

    private fun announce() {
        val s = mutable.value
        session.send("call", JSONObject().put("active", s.active).put("mic", s.mic).put("camera", s.camera))
    }
    fun members(room: TogetherState) {
        if (closed) return
        val me = room.members.firstOrNull { it.id == room.member }
        val other = room.members.firstOrNull { it.id != room.member && it.online && it.call }
        if (!room.connected || !mutable.value.active || other == null) {
            disposePeer()
            if (mutable.value.active) mutable.value = mutable.value.copy(connection = "Waiting for your partner")
            return
        }
        if (remoteId != other.id || peer == null) createPeer(other.id)
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
                main.post { if (run == peerGeneration) session.send("signal", JSONObject().put("payload", JSONObject()
                    .put("type", "candidate").put("candidate", candidate.sdp).put("mid", candidate.sdpMid).put("line", candidate.sdpMLineIndex))) }
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
        val sender = peer?.addTrack(videoTrack, listOf("saoreo-call"))
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
        members(session.state.value)
        val pc = peer ?: return
        val run = peerGeneration
        when (data.optString("type")) {
            "offer", "answer" -> {
                val type = SessionDescription.Type.fromCanonicalForm(data.getString("type"))
                pc.setRemoteDescription(sdpObserver(onSet = {
                    if (run == peerGeneration) {
                        hasRemoteDescription = true
                        candidates.toList().forEach { pc.addIceCandidate(it) }; candidates.clear()
                        if (type == SessionDescription.Type.OFFER) pc.createAnswer(sdpObserver(onCreated = { if (run == peerGeneration) localDescription(it) }), MediaConstraints())
                    }
                }), SessionDescription(type, data.getString("sdp")))
            }
            "candidate" -> {
                val c = IceCandidate(data.optString("mid"), data.getInt("line"), data.getString("candidate"))
                if (hasRemoteDescription) pc.addIceCandidate(c) else if (candidates.size < 128) candidates.add(c)
            }
        }
    }
    private fun localDescription(description: SessionDescription) {
        val run = peerGeneration
        peer?.setLocalDescription(sdpObserver(onSet = {
            if (run == peerGeneration) session.send("signal", JSONObject().put("payload", JSONObject()
                .put("type", description.type.canonicalForm()).put("sdp", description.description)))
        }), description)
    }
    private fun sdpObserver(onCreated: (SessionDescription) -> Unit = {}, onSet: () -> Unit = {}) = object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) { main.post { if (!closed) onCreated(description) } }
        override fun onSetSuccess() { main.post { if (!closed) onSet() } }
        override fun onCreateFailure(error: String) { failed() }
        override fun onSetFailure(error: String) { failed() }
        private fun failed() { main.post { if (!closed) mutable.value = mutable.value.copy(error = "Call negotiation failed. End and rejoin the call.") } }
    }
    private fun disposePeer() {
        peerGeneration++; peer?.close(); peer?.dispose(); peer = null; remoteId = null
        hasRemoteDescription = false; offerStarted = false; candidates.clear()
        mutable.value = mutable.value.copy(remoteVideo = null)
    }
    fun stop() {
        camera(false); audioTrack.setEnabled(false); disposePeer()
        mutable.value = mutable.value.copy(active = false, mic = false, camera = false, connection = "Call is off", error = null)
        announce()
        audioManager.clearCommunicationDevice(); audioManager.mode = oldMode
    }
    private fun networkLost() {
        if (closed) return
        stop()
    }
    override fun close() {
        if (closed) return
        stop(); closed = true; session.onSignal = null; session.onConnectionLost = null
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        audioTrack.dispose(); audioSource.dispose(); videoTrack.dispose(); videoSource.dispose()
        factory.dispose(); adm.release(); egl.release()
    }
    companion object {
        fun outputName(device: AudioDeviceInfo): String = when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth headset"
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET -> "Headphones"
            else -> device.productName.toString()
        }
    }
}
