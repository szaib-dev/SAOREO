package com.codetic.saoreo

import android.app.Application
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import android.util.Base64
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal const val TOGETHER_SERVER = "https://szaibdev.duckdns.org:8765"

internal data class PairLink(val server: String, val code: String, val callSecret: String?) {
    companion object {
        fun parse(uri: Uri?): PairLink? {
            if (uri == null || !uri.isHierarchical) return null
            val endpoint = when {
                uri.scheme == "saoreo" && uri.host == "together" && uri.path == "/pair" ->
                    uri.getQueryParameter("server") ?: TOGETHER_SERVER
                uri.scheme == "https" && uri.path?.endsWith("/together/pair") == true ->
                    uri.buildUpon().clearQuery().fragment(null).path(uri.path.orEmpty().removeSuffix("/together/pair")).build().toString()
                else -> return null
            }
            val server = TogetherSources.endpoint(endpoint) ?: return null
            val code = uri.getQueryParameter("code")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{43}")) } ?: return null
            val secret = uri.fragment?.takeIf {
                it.matches(Regex("[A-Za-z0-9_-]{43}"))
            }
            return PairLink(server, code, secret)
        }
    }
}

internal enum class PairKind(val wire: String, val title: String) {
    WATCH("watch", "Watch together"), AUDIO("audio", "Audio call"), VIDEO("video", "Video call");

    companion object {
        fun fromWire(value: String) = entries.firstOrNull { it.wire == value }
    }
}

internal data class PairRing(val id: String, val kind: PairKind, val incoming: Boolean, val until: Long, val silent: Boolean, val delivered: Boolean = false, val started: Long = 0L)
internal data class PairLaunch(val id: String, val server: String, val kind: PairKind, val ticket: JSONObject)
internal data class PairState(
    val partner: String? = null,
    val online: Boolean = false,
    val typing: Boolean = false,
    val connected: Boolean = false,
    val visible: Boolean = false,
    val availableInBackground: Boolean = false,
    val ring: PairRing? = null,
    val requesting: PairKind? = null,
    val launch: PairLaunch? = null,
    val error: String? = null,
)

/** The pairing socket discovers the paired device. Each accepted request has separate room credentials. */
internal class TogetherPairing(app: Application, val chat: PairedChat = PairedChat(app)) {
    private val application = app
    private val callIdentity = CallIdentity(app)
    val appearance = PairedAppearance(app)
    private val chatViewers = mutableSetOf<Any>()
    private val storagePrefs = app.getSharedPreferences("paired-chat-storage", 0)

    fun mediaDownload() = MediaAutoDownload.entries.firstOrNull { it.name == storagePrefs.getString("auto", null) }
        ?: MediaAutoDownload.Never
    fun mediaDownload(value: MediaAutoDownload) {
        storagePrefs.edit().putString("auto", value.name).apply()
        if (value != MediaAutoDownload.Never) TogetherMessagingService.scheduleChatSync(application)
    }

    fun chatVisible(viewer: Any, visible: Boolean) {
        if (visible) {
            chatViewers.add(viewer)
            PairedChatNotifications.clear(application)
        } else chatViewers.remove(viewer)
    }

    suspend fun storageSize() = chatPeer?.let { chat.storage(it) } ?: Triple(0L, 0L, 0L)

    suspend fun chatBackup(uri: Uri, password: CharArray, restore: Boolean) {
        val peer = checkNotNull(chatPeer) { "Secure pairing is required." }
        if (restore) chat.restoreBackup(peer, uri, password)
        else chat.exportBackup(peer, uri, password)
    }
    private val chatSync = Mutex()
    private val mediaSync = Mutex()
    private val missedNotifications = Mutex()
    private var socketChat = false
    private var requestSequence = 0L
    private val socketRequests = mutableMapOf<String, CompletableDeferred<JSONObject>>()
    private var reconcileChat = false
    private var activeNetwork: Network? = null
    private var networkWasLost = false
    private var mediaJob: Job? = null
    private var mediaDirty = false
    private var chatJob: Job? = null
    private var chatPeer: ChatPeer? = null
    val conversationId get() = chatPeer?.conversation
    private var chatDirty = false
    private val preferences = app.getSharedPreferences("together-pairing", 0)
    init {
        if (!preferences.getBoolean("backgroundCallsV2", false)) {
            preferences.edit().putBoolean("backgroundCallsV2", true).putBoolean("backgroundCalls", true).apply()
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val random = SecureRandom()
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS).build()
    private val mutable = MutableStateFlow(PairState(partner = preferences.getString("partner", null)))
    val state = mutable.asStateFlow()
    val server: String? get() = preferences.getString("server", null)
    val resumeAvailabilityAtBoot: Boolean get() = server != null && mutable.value.partner != null && preferences.getBoolean("backgroundCalls", false)
    fun setAvailabilityPreference(enabled: Boolean) {
        preferences.edit().putBoolean("backgroundCalls", enabled).apply()
        TogetherMessagingService.register(application)
    }

    private fun readChatPeer(endpoint: String, state: JSONObject): ChatPeer? {
        val pairId = state.optString("conversation").takeIf { it.matches(Regex("[a-f0-9]{32}")) } ?: return null
        val partner = state.optString("peer").takeIf { it.matches(Regex("[a-f0-9]{64}")) } ?: return null
        val me = java.security.MessageDigest.getInstance("SHA-256").digest(token(endpoint).encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
        return ChatPeer(pairId, me, partner)
    }

    private fun setChatPeer(peer: ChatPeer?) {
        chatPeer = peer
        preferences.edit().putString("chatConversation", peer?.conversation)
            .putString("chatSelf", peer?.self).putString("chatPartner", peer?.partner).apply()
        scope.launch { chat.activate(peer) }
    }

    private fun requestChatSync(reconcile: Boolean = false) {
        val endpoint = server ?: return
        val peer = chatPeer ?: return
        chatDirty = true
        reconcileChat = reconcileChat || reconcile
        if (chatJob?.isActive == true) return
        chatJob = scope.launch {
            while (chatDirty && peer == chatPeer && endpoint == server) {
                chatDirty = false
                val reconcileNow = reconcileChat
                reconcileChat = false
                try {
                    syncChat(endpoint, peer, reconcileNow)
                    requestMediaSync(endpoint, peer)
                    notifyUnread(peer, state.value.partner.orEmpty())
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: IOException) {
                    TogetherMessagingService.scheduleChatSync(application)
                }
            }
            if (chatDirty) {
                chatJob = null
                requestChatSync()
            }
        }
    }

    private suspend fun syncChat(endpoint: String, peer: ChatPeer, reconcile: Boolean = true) = chatSync.withLock {
        if (chatPeer != peer || server != endpoint) return@withLock
        chat.sync(peer, { route, body -> chatRequest(endpoint, route, body) },
            { request -> transferAttachment(endpoint, peer, request) }, mediaOnly = false,
            reconcileReceipts = reconcile || !socketChat)
    }

    private suspend fun syncMedia(endpoint: String, peer: ChatPeer) = mediaSync.withLock {
        if (chatPeer != peer || server != endpoint) return@withLock
        chat.sync(peer, { route, body -> post(endpoint, route, body) },
            { request -> transferAttachment(endpoint, peer, request) }, mediaOnly = true)
    }

    private fun requestMediaSync(endpoint: String, peer: ChatPeer) {
        if (chatPeer != peer || server != endpoint) return
        mediaDirty = true
        if (mediaJob?.isActive == true) return
        mediaJob = scope.launch {
            try {
                while (mediaDirty && chatPeer == peer && server == endpoint) {
                    mediaDirty = false
                    try { syncMedia(endpoint, peer) }
                    catch (_: IOException) { TogetherMessagingService.scheduleChatSync(application) }
                }
            }
            finally {
                mediaJob = null
                if (mediaDirty) {
                    val next = chatPeer
                    val nextEndpoint = server
                    if (next != null && nextEndpoint != null) requestMediaSync(nextEndpoint, next)
                }
            }
        }
    }

    private suspend fun chatRequest(endpoint: String, route: String, body: JSONObject): JSONObject {
        val current = socket
        if (!socketChat || current == null || endpoint != server) return post(endpoint, route, body)
        val id = (++requestSequence).toString()
        val response = CompletableDeferred<JSONObject>()
        socketRequests[id] = response
        try {
            val sent = current.send(JSONObject(body.toString()).put("type", route).put("requestId", id).put("pushInbox", true).toString())
            val reply = if (sent) withTimeoutOrNull(5_000) { response.await() } else null
            if (reply == null || reply === nullSocketResponse) return post(endpoint, route, body)
            if (reply.has("code")) throw ChatHttpError(reply.getInt("code"))
            return reply.getJSONObject("result")
        } finally {
            socketRequests.remove(id)
        }
    }

    private fun resetChatSocket() {
        socketChat = false
        val pending = socketRequests.values.toList()
        socketRequests.clear()
        pending.forEach { it.complete(nullSocketResponse) }
    }

    private val nullSocketResponse = JSONObject()

    suspend fun refreshMessages() = withContext(Dispatchers.Main.immediate) {
        val endpoint = server ?: return@withContext
        val state = post(endpoint, "state", JSONObject())
        val peer = readChatPeer(endpoint, state) ?: return@withContext
        if (chatPeer != peer) setChatPeer(peer)
        chat.activate(peer)
        syncChat(endpoint, peer)
        notifyUnread(peer, state.optString("partner", "Your partner"))
        syncMedia(endpoint, peer)
        val download = mediaDownload()
        val network = application.getSystemService(android.net.ConnectivityManager::class.java)
        if (chatPeer == peer && (download == MediaAutoDownload.Always ||
                download == MediaAutoDownload.Unmetered && !network.isActiveNetworkMetered)) {
            chat.autoDownload(peer) { request -> transferAttachment(endpoint, peer, request) }
        }
    }

    private suspend fun notifyUnread(peer: ChatPeer, name: String) {
        if (chatPeer != peer) return
        val nameToShow = name.ifBlank { "Your partner" }
        missedNotifications.withLock {
            var more = true
            while (chatPeer == peer && more) {
                val missed = chat.missedCalls(peer)
                for (call in missed) {
                    if (!PairedChatNotifications.missed(application, call, nameToShow)) break
                    chat.missedNotified(peer, call.id)
                }
                more = missed.size == 50 && chat.missedCalls(peer).firstOrNull()?.id != missed.first().id
            }
        }
        if (chatViewers.isNotEmpty() || chatPeer != peer) return
        val unread = chat.unread(peer)
        if (chatViewers.isEmpty() && chatPeer == peer) PairedChatNotifications.show(application, peer,
            nameToShow, unread)
    }

    suspend fun sendMessage(text: String, reply: ChatReference? = null): Boolean {
        val peer = chatPeer ?: return false
        return chat.enqueue(peer, text.trim(), reply).also {
            if (it) {
                TogetherMessagingService.scheduleChatSync(application)
                requestChatSync()
            }
        }
    }

    suspend fun messageAction(message: PairedMessage, action: ChatAction, value: String = ""): Boolean {
        val peer = chatPeer ?: return false
        return chat.act(peer, message, action, value.trim()).also {
            if (it) {
                TogetherMessagingService.scheduleChatSync(application)
                requestChatSync()
            }
        }
    }

    suspend fun markVoicePlayed(message: PairedMessage) {
        val peer = chatPeer ?: return
        try {
            if (chat.voicePlayed(peer, message)) {
                TogetherMessagingService.scheduleChatSync(application)
                requestChatSync()
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // A receipt must not interrupt local playback.
        }
    }

    suspend fun choosePartnerPhoto(uri: Uri?) {
        val peer = checkNotNull(chatPeer) { "Secure pairing is required." }
        val value = uri?.let { appearance.encodeProfilePhoto(it) }.orEmpty()
        check(peer == chatPeer && chat.choosePhoto(peer, value)) { "Couldn’t save the partner photo." }
        TogetherMessagingService.scheduleChatSync(application)
        requestChatSync()
    }

    suspend fun hideMessages(messages: List<PairedMessage>) {
        val peer = chatPeer ?: return
        chat.hide(peer, messages)
    }

    suspend fun sendAttachment(uri: Uri, voiceDuration: Long = 0, waveform: List<Int> = emptyList(),
        reply: ChatReference? = null, caption: String = "", album: String? = null): Boolean {
        val peer = chatPeer ?: return false
        val endpoint = server ?: return false
        return chat.enqueueAttachment(peer, uri, voiceDuration, waveform, reply, caption, album).also {
            if (it) {
                TogetherMessagingService.scheduleChatSync(application)
                requestMediaSync(endpoint, peer)
            }
        }
    }

    fun cachedAttachment(message: PairedMessage): java.io.File? = chatPeer?.let { chat.cachedAttachment(it, message) }

    suspend fun openAttachment(message: PairedMessage): java.io.File {
        val endpoint = server ?: throw IOException("Together is unavailable")
        val peer = chatPeer ?: throw IOException("Secure chat is unavailable")
        return chat.openAttachment(peer, message) { request -> transferAttachment(endpoint, peer, request) }
    }

    private suspend fun transferAttachment(endpoint: String, peer: ChatPeer, part: AttachmentRequest): String {
        if (peer != chatPeer || endpoint != server) throw IOException("Pairing changed")
        val request = Request.Builder()
            .url("$endpoint/together/api/pair/attachment/${peer.conversation}/${part.id}/${part.part}")
            .header("Authorization", "Bearer ${token(endpoint)}")
            .apply { if (part.total > 0) header("X-Part-Count", part.total.toString()) }
            .method(part.method, part.envelope?.toRequestBody("application/jose".toMediaType())).build()
        return withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw ChatHttpError(response.code)
                if (part.method == "HEAD") response.header("X-Parts-Stored").orEmpty()
                else response.body?.string().orEmpty()
            }
        }
    }

    fun callMedia(id: String, connectedAt: Long, duration: Long, ended: Boolean) {
        val peer = chatPeer ?: return
        scope.launch { chat.callMedia(peer, id, connectedAt, duration, ended) }
    }

    private var typingJob: Job? = null
    private var lastTyping = -3_000L

    fun typing() {
        val peer = chatPeer ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastTyping < 2500 || !state.value.connected) return
        if (socket?.send(JSONObject().put("type", "chatTyping").put("conversation", peer.conversation).toString()) == true)
            lastTyping = now
    }

    fun acknowledgeIncomingAlert(id: String) {
        val endpoint = server ?: return
        if (state.value.ring?.let { it.incoming && it.id == id && it.until > SystemClock.elapsedRealtime() } != true) return
        scope.launch { runCatching { post(endpoint, "received", JSONObject().put("id", id)) } }
    }

    suspend fun markMessagesRead(ids: List<String>) {
        if (chat.seen(ids)) {
            TogetherMessagingService.scheduleChatSync(application)
            requestChatSync()
        }
    }

    fun retryMessage(id: String) {
        scope.launch {
            chat.retry(id)
            TogetherMessagingService.scheduleChatSync(application)
            requestChatSync()
            val peer = chatPeer
            val endpoint = server
            if (peer != null && endpoint != null) requestMediaSync(endpoint, peer)
        }
    }

    init {
        scope.launch {
            chat.photos.collect { photos ->
                if (photos != null && photos.conversation == conversationId) {
                    try { appearance.applyProfileChoices(photos) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: IOException) { }
                }
            }
        }
        val pairId = preferences.getString("chatConversation", null)
        val self = preferences.getString("chatSelf", null)
        val partner = preferences.getString("chatPartner", null)
        if (pairId != null && self != null && partner != null && mutable.value.partner != null) {
            chatPeer = ChatPeer(pairId, self, partner)
            scope.launch { chat.activate(chatPeer) }
        }
        val connectivity = application.getSystemService(ConnectivityManager::class.java)
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch {
                    val switched = networkWasLost || activeNetwork?.let { it != network } == true
                    activeNetwork = network
                    networkWasLost = false
                    if (switched && listening) {
                        stop()
                        connect()
                    }
                }
            }

            override fun onLost(network: Network) {
                scope.launch {
                    if (activeNetwork == network) {
                        activeNetwork = null
                        networkWasLost = true
                    }
                }
            }
        })
    }

    suspend fun registerPush() {
        val endpoint = server ?: return
        if (state.value.partner == null) return
        val token = application.getSharedPreferences("together-push", 0).getString("token", null) ?: return
        post(endpoint, "push", JSONObject().put("token", token).put("enabled", resumeAvailabilityAtBoot).put("chat", true))
    }

    suspend fun refreshIncoming(id: String) {
        val endpoint = server ?: return
        val result = post(endpoint, "state", JSONObject())
        val ring = result.optJSONObject("ring") ?: return
        if (ring.optString("id") != id || !ring.optBoolean("incoming") || ring.optLong("remaining") <= 0) return
        withContext(Dispatchers.Main.immediate) { receive(endpoint, result, fromSocket = false) }
    }

    suspend fun respondRing(id: String, accepted: Boolean) {
        val endpoint = server ?: return
        post(endpoint, "answer", JSONObject().put("id", id).put("accepted", accepted))
    }
    private var socket: WebSocket? = null
    private var retry: Job? = null
    private var generation = 0
    private var reconnectDelay = 1_000L
    private var requestGeneration = 0

    private fun token(server: String): String {
        val key = "credential:$server"
        return preferences.getString(key, null) ?: Base64.encodeToString(ByteArray(32).also(random::nextBytes),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING).also { preferences.edit().putString(key, it).apply() }
    }

    private suspend fun post(server: String, route: String, body: JSONObject): JSONObject {
        val request = Request.Builder().url("$server/together/api/pair/$route")
            .header("Authorization", "Bearer ${token(server)}")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                val reply = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
                if (!response.isSuccessful) {
                    if (route.startsWith("chat-")) throw ChatHttpError(response.code)
                    val error = reply?.optString("error").orEmpty()
                    throw IOException(error.takeUnless { it.isBlank() || it == "404: Not Found" }
                        ?: if (response.code == 404) "This Together server needs the pairing update." else "Couldnâ€™t reach pairing (${response.code}).")
                }
                reply ?: throw IOException("Invalid pairing response")
            }
        }
    }

    suspend fun invite(address: String, name: String, key: String): String {
        val endpoint = TogetherSources.endpoint(address) ?: throw IOException("Enter a valid HTTPS server address")
        check(mutable.value.partner == null) { "Unpair your current partner first." }
        val reply = post(endpoint, "invite", JSONObject().put("name", name).put("key", key))
        val secret = newCallPairingSecret()
        check(callIdentity.startPairing(secret)) { "Couldnâ€™t store the secure pairing key." }
        use(endpoint, null)
        return Uri.parse("$endpoint/together/pair").buildUpon()
            .appendQueryParameter("code", reply.getString("code"))
            .fragment(secret).build().toString()
    }

    suspend fun preview(link: PairLink): String = post(link.server, "preview", JSONObject().put("code", link.code)).getString("name")

    suspend fun accept(link: PairLink, name: String) {
        check(mutable.value.partner == null) { "Unpair your current partner first." }
        val secret = requireNotNull(link.callSecret) { "Ask your partner for a new secure pairing link." }
        val reply = post(link.server, "accept", JSONObject().put("code", link.code).put("name", name))
        check(callIdentity.startPairing(secret)) { "Couldnâ€™t store the secure pairing key." }
        use(link.server, reply.getString("name"))
        TogetherMessagingService.register(application)
    }

    suspend fun unpair() {
        val endpoint = server ?: return
        post(endpoint, "unpair", JSONObject())
        setChatPeer(null)
        callIdentity.forgetAll()
        savePartner(null)
        mutable.value = mutable.value.copy(partner = null, online = false, ring = null, launch = null)
    }

    private fun use(endpoint: String, partner: String?) {
        if (server != endpoint) {
            stop()
            setChatPeer(null)
        }
        preferences.edit().putString("server", endpoint).apply()
        savePartner(partner)
        mutable.value = mutable.value.copy(partner = partner)
        connect()
    }

    private fun savePartner(partner: String?) {
        preferences.edit().putString("partner", partner).apply()
    }

    fun visible(visible: Boolean) {
        mutable.value = mutable.value.copy(visible = visible)
        if (listening) connect() else stop()
    }

    fun background(enabled: Boolean) {
        mutable.value = mutable.value.copy(availableInBackground = enabled)
        if (listening) connect() else stop()
    }

    private val listening get() = mutable.value.visible || mutable.value.availableInBackground

    private fun stop() {
        generation++
        resetChatSocket()
        retry?.cancel()
        retry = null
        socket?.close(1000, null)
        socket = null
        mutable.value = mutable.value.copy(connected = false, online = false, ring = null, requesting = null)
    }

    private fun connect() {
        val endpoint = server ?: return
        if (!listening || socket != null || retry?.isActive == true) return
        val run = ++generation
        val request = Request.Builder().url(endpoint.replaceFirst("https://", "wss://") + "/together/pair-ws")
            .header("Authorization", "Bearer ${token(endpoint)}").build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { if (generation == run) runCatching { receive(endpoint, JSONObject(text)) } }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch { if (generation == run) disconnected() }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch {
                    if (generation == run) {
                        if (response?.code in setOf(401, 404)) {
                            stop()
                            error(if (response?.code == 404) "This Together server needs the pairing update." else "Your pairing connection has expired.")
                        } else disconnected()
                    }
                }
            }
        })
    }

    private fun disconnected() {
        resetChatSocket()
        socket = null
        mutable.value = mutable.value.copy(online = false, connected = false, ring = null)
        if (!listening) return
        retry = scope.launch {
            delay(reconnectDelay)
            reconnectDelay = (reconnectDelay * 2).coerceAtMost(15_000)
            retry = null
            connect()
        }
    }

    private var lastResolvedRing: PairRing? = null
    private var cancelPending = false

    private fun receive(endpoint: String, message: JSONObject, fromSocket: Boolean = true) {
        when (message.getString("type")) {
            "pairState" -> {
                reconnectDelay = 1_000
                val verifiedChat = readChatPeer(endpoint, message)
                val reconnecting = fromSocket && !mutable.value.connected
                if (fromSocket) socketChat = message.optBoolean("socketChat")
                val changedChat = verifiedChat != chatPeer
                if (changedChat) setChatPeer(verifiedChat)
                val partner = message.optString("partner").takeUnless { it.isBlank() || it == "null" }
                if (partner != mutable.value.partner) savePartner(partner)
                val ring = message.optJSONObject("ring")?.let { item ->
                    val wire = item.getString("kind")
                    val silent = wire.endsWith("_silent")
                    val kind = PairKind.fromWire(wire.removeSuffix("_silent")) ?: return@let null
                    val remaining = item.getLong("remaining").coerceIn(0L, 30_000L)
                    PairRing(item.getString("id"), kind, item.getBoolean("incoming"), SystemClock.elapsedRealtime() + remaining, silent, item.optBoolean("delivered"), item.optLong("started", System.currentTimeMillis()))
                }
                if (ring?.incoming == true && ring.kind != PairKind.WATCH && TogetherActiveCallService.inCall.value) {
                    scope.launch { runCatching { post(endpoint, "answer", JSONObject().put("id", ring.id).put("busy", true)) } }
                    return
                }
                if (cancelPending && ring?.incoming == false) {
                    cancelPending = false
                    if (ring.kind != PairKind.WATCH) cancel(ring.id)
                }
                if (partner != null && mutable.value.partner != partner) TogetherMessagingService.register(application)
                mutable.value = mutable.value.copy(connected = fromSocket || mutable.value.connected, partner = partner, online = message.optBoolean("online"), ring = ring, requesting = if (ring != null) null else mutable.value.requesting,
                    launch = mutable.value.launch.takeIf { partner != null })
                if (fromSocket && verifiedChat != null && (changedChat || reconnecting)) requestChatSync(reconcile = true)
            }
            "chatResponse" -> socketRequests[message.optString("requestId")]?.complete(message)
            "chatReceipts" -> {
                val peer = chatPeer ?: return
                if (message.optString("conversation") == peer.conversation) {
                    val statuses = message.optJSONArray("status") ?: return
                    scope.launch { chat.applyReceipts(peer, statuses) }
                }
            }
            "chatMessage" -> {
                val peer = chatPeer ?: return
                if (message.optString("conversation") != peer.conversation) return
                val row = message.optJSONObject("message") ?: return
                scope.launch {
                    try {
                        chat.receiveLive(peer, row)
                        requestChatSync()
                        if (mediaDownload() != MediaAutoDownload.Never && row.has("envelope"))
                            TogetherMessagingService.scheduleChatSync(application)
                    } catch (_: IOException) {
                        requestChatSync()
                    }
                }
            }
            "chatWake" -> if (message.optString("conversation") == chatPeer?.conversation) {
                requestChatSync(reconcile = true)
                if (mediaDownload() != MediaAutoDownload.Never) TogetherMessagingService.scheduleChatSync(application)
            }
            "chatTyping" -> if (chatPeer != null && message.optString("conversation") == chatPeer?.conversation) {
                typingJob?.cancel()
                mutable.value = mutable.value.copy(typing = true)
                typingJob = scope.launch { delay(4200); mutable.value = mutable.value.copy(typing = false) }
            }
            "ringResult" -> {
                requestChatSync()
                val ring = mutable.value.ring
                if (ring?.id == message.getString("id")) {
                    lastResolvedRing = ring
                    mutable.value = mutable.value.copy(ring = null)
                    if (!ring.incoming) when (message.getString("outcome")) {
                        "declined" -> error("Your partner declined.")
                        "missed" -> error("Your partner didnâ€™t answer.")
                    }
                }
            }
            "pairLaunch" -> {
                val kind = PairKind.fromWire(message.getString("kind")) ?: return
                val id = message.getString("id")
                chatPeer?.let { peer ->
                    val incoming = message.optBoolean("incoming", lastResolvedRing?.incoming == true)
                    val started = message.optLong("started", lastResolvedRing?.started ?: System.currentTimeMillis())
                    scope.launch { chat.acceptedCall(peer, id, kind, incoming, started) }
                }
                mutable.value = mutable.value.copy(launch = PairLaunch(message.getString("id"), endpoint, kind, message.getJSONObject("ticket")))
            }
            "error" -> {
                cancelPending = false
                error(message.getString("message"))
            }
        }
    }

    private fun send(type: String, body: JSONObject): Boolean {
        if (mutable.value.connected && socket?.send(body.put("type", type).toString()) == true) return true
        error("Reconnect to Together and try again.")
        return false
    }

    fun ring(kind: PairKind, room: JSONObject? = null, silent: Boolean = false) {
        if (cancelPending || mutable.value.requesting != null || mutable.value.ring != null || mutable.value.launch != null ||
            kind != PairKind.WATCH && TogetherActiveCallService.inCall.value) return
        cancelPending = false
        val attempt = ++requestGeneration
        mutable.value = mutable.value.copy(requesting = kind, error = null)
        if (!send("ring", (room ?: JSONObject()).put("kind", if (silent && kind != PairKind.WATCH) "${kind.wire}_silent" else kind.wire))) {
            mutable.value = mutable.value.copy(requesting = null)
            return
        }
        scope.launch {
            delay(10000)
            if (attempt == requestGeneration && mutable.value.requesting != null) mutable.value = mutable.value.copy(requesting = null,
                error = "The call request was not acknowledged. Please try again.")
        }
    }
    fun answer(id: String, accepted: Boolean): Boolean {
        val kind = mutable.value.ring?.kind
        val busy = TogetherActiveCallService.inCall.value && kind != PairKind.WATCH
        return send("answer", JSONObject().put("id", id).put("accepted", accepted && !busy).put("busy", busy))
    }
    fun cancelPendingCall() {
        if (mutable.value.requesting == null) return
        cancelPending = true
        mutable.value = mutable.value.copy(requesting = null)
        scope.launch { delay(10_000); cancelPending = false }
    }

    fun cancel(id: String) = send("cancel", JSONObject().put("id", id))
    fun joined(launch: PairLaunch) {
        if (mutable.value.launch?.id == launch.id) mutable.value = mutable.value.copy(launch = null)
        send("ack", JSONObject().put("id", launch.id))
    }
    fun error(text: String?) { mutable.value = mutable.value.copy(error = text, requesting = if (text != null) null else mutable.value.requesting) }
}
