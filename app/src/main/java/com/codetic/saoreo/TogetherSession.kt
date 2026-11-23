package com.codetic.saoreo

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.os.SystemClock
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.common.util.UnstableApi
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal data class TogetherMember(val id: String, val name: String, val ordinal: Int, val online: Boolean,
    val ready: Boolean, val buffering: Boolean, val call: Boolean, val mic: Boolean, val camera: Boolean)
internal data class TogetherMedia(val id: String, val kind: String, val source: String, val title: String, val duration: Long, val live: Boolean) {
    fun json() = JSONObject().put("kind", kind).put("source", source).put("title", title).put("duration", duration).put("live", live)
}
internal data class TogetherChat(val id: String, val sender: String, val name: String, val text: String, val time: Long)
internal data class TogetherReaction(val emoji: String, val at: Long)
internal data class RoomRequest(val id: String, val kind: String, val sender: String, val recipient: String, val expires: Long)
internal data class RoomAnswer(val request: RoomRequest, val accepted: Boolean)
private fun JSONObject.roomRequest() = RoomRequest(getString("id"), getString("kind"),
    getString("sender"), getString("recipient"), getLong("expires"))

internal data class TogetherState(val connected: Boolean = false, val connecting: Boolean = false, val room: String = "",
    val member: String = "", val invite: String = "", val error: String? = null, val revision: Long = -1,
    val members: List<TogetherMember> = emptyList(), val media: TogetherMedia? = null, val queue: List<TogetherMedia> = emptyList(),
    val timeline: SharedTimeline = SharedTimeline(0, 0, false), val wanted: Boolean = false, val wait: Boolean = true,
    val chat: List<TogetherChat> = emptyList(), val reaction: TogetherReaction? = null,
    val incomingChat: TogetherChat? = null,
    val request: RoomRequest? = null, val answer: RoomAnswer? = null, val callFullscreen: Boolean = false,
    val pairKind: PairKind? = null, val connectionSlow: Boolean? = null,
    val memberConnections: Map<String, Boolean> = emptyMap(), val autoAdvanceQueue: Boolean = false)

internal val TogetherState.canStartPlayback: Boolean
    get() = connected && media != null && members.size == 2 &&
        members.all { it.online && it.ready }

internal class TogetherSession(application: Application) : AndroidViewModel(application) {
    private val ownedCall = lazy { TogetherCall(application, this) }
    val callController get() = ownedCall.value
    val games = TogetherGames(application) { type, fields -> send(type, fields) }
    private val mutable = MutableStateFlow(TogetherState())
    val state = mutable.asStateFlow()
    val preferences = application.getSharedPreferences("together", 0)
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    private var token = ""
    private var endpoint = ""
    var roomCallSecret: String? = null
        private set
    private var seq = 0L
    private var generation = 0
    private var reconnect: Job? = null
    private var reconnectAttempts = 0
    private var lastPong = 0L
    private var heartbeat: Job? = null
    private var enterRequest: Job? = null
    private var leaving = false
    private var clockOffset = 0L
    private var clockMeasured = false
    private var bestRtt = Long.MAX_VALUE
    private var latencyAverage: Double? = null
    var iceServers: JSONArray = JSONArray(); private set
    var onSignal: ((JSONObject) -> Unit)? = null
    var onConnectionLost: (() -> Unit)? = null
    var callOnReconnect: (() -> Boolean)? = null

    fun serverNow(): Long = SystemClock.elapsedRealtime() + clockOffset
    fun clearError() { mutable.value = mutable.value.copy(error = null) }
    fun clearAnswer() { mutable.value = mutable.value.copy(answer = null) }
    fun error(message: String) { mutable.value = mutable.value.copy(error = message) }

    fun inviteLink(): String = Uri.Builder().scheme("saoreo").authority("together").path("/join")
        .appendQueryParameter("server", endpoint).appendQueryParameter("invite", state.value.invite)
        .fragment(roomCallSecret).build().toString()

    fun uploadUrl(id: String): String = "$endpoint/together/media/${state.value.room}/$id"

    @androidx.annotation.OptIn(UnstableApi::class)
    fun uploadDataSource(): DefaultHttpDataSource.Factory = DefaultHttpDataSource.Factory()
        .setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token"))

    private val sharedFiles by lazy { TogetherSharedFiles(this, getApplication<Application>().contentResolver, viewModelScope) }
    fun localSharedSource(id: String): String? = sharedFiles.local(id)
    fun sharedFileUrl(id: String): String = "$endpoint/together/share/${state.value.room}/$id"
    fun fullCall(enabled: Boolean) = send("callView", JSONObject().put("enabled", enabled))

    suspend fun shareLocalVideo(resolver: ContentResolver, uri: Uri): TogetherMedia {
        val room = state.value.room
        check(state.value.connected && room.isNotEmpty()) { "Reconnect to your room before sharing." }
        val video = inspectSharedVideo(resolver, uri)
        val body = JSONObject().put("size", video.size).put("mime", video.mime)
        val request = Request.Builder().url("$endpoint/together/share/$room")
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val id = withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (response.code == 404) throw java.io.IOException("The Together server needs the 2.9.2 streaming update.")
                val payload = runCatching { JSONObject(raw) }.getOrNull()
                check(response.isSuccessful) { payload?.optString("error") ?: "Could not share this source (HTTP ${response.code})." }
                payload?.getString("id") ?: throw java.io.IOException("Invalid shared-source response.")
            }
        }
        check(state.value.connected && state.value.room == room) { "The room changed. Choose the video again." }
        sharedFiles.remember(id, room, video)
        return TogetherMedia("", "relay", id, video.title, video.duration, false)
    }

    fun enter(url: String, name: String, key: String? = null, invite: String? = null) {
        if (mutable.value.connecting || mutable.value.room.isNotEmpty()) return
        val link = invite?.trim()?.takeIf { it.startsWith("saoreo://together/join?") }?.let(Uri::parse)
        val base = TogetherSources.endpoint(link?.getQueryParameter("server") ?: url)
            ?: return error("Enter a valid HTTPS server address")
        val invitation = invite?.let { TogetherSources.invite(it) ?: return error("Paste the complete invitation code") }
        if (name.isBlank()) return error("Enter your name")
        endpoint = base
        roomCallSecret = if (invitation == null) newCallPairingSecret()
            else link?.fragment?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{43}")) }
        leaving = false
        mutable.value = TogetherState(connecting = true)
        enterRequest = viewModelScope.launch {
            try {
                val ticket = withContext(Dispatchers.IO) {
                    val body = JSONObject().put("name", name.take(32))
                    if (invitation != null) body.put("invite", invitation) else body.put("key", key.orEmpty())
                    val route = if (invitation == null) "rooms" else "join"
                    val req = Request.Builder().url("$base/together/api/$route")
                        .post(body.toString().toRequestBody("application/json".toMediaType())).build()
                    http.newCall(req).execute().use { response ->
                        val json = JSONObject(response.body?.string().orEmpty())
                        check(response.isSuccessful) { json.optString("error", "Unable to join room") }
                        json
                    }
                }
                preferences.edit().putString("server", base).putString("name", name.take(32)).apply()
                attach(base, ticket, null)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.value = TogetherState(error = e.message ?: "Unable to reach the room server")
            }
        }
    }

    fun enterPair(launch: PairLaunch) {
        roomCallSecret = null
        if (state.value.room == launch.ticket.getString("room") && endpoint == launch.server) return
        enterRequest?.cancel()
        if (state.value.room.isNotEmpty()) leave()
        attach(launch.server, launch.ticket, launch.kind)
    }

    fun watchRoomFor(server: String?): JSONObject? = state.value.takeIf { it.connected && it.room.isNotEmpty() && endpoint == server }
        ?.let { JSONObject().put("room", it.room).put("roomToken", token) }

    private fun attach(base: String, ticket: JSONObject, kind: PairKind?) {
        latencyAverage = null
        endpoint = base
        token = ticket.getString("token")
        games.attached(base, ticket)
        iceServers = ticket.optJSONArray("iceServers") ?: JSONArray()
        clockMeasured = false
        bestRtt = Long.MAX_VALUE
        leaving = false
        mutable.value = TogetherState(connecting = true, room = ticket.getString("room"),
            member = ticket.getString("member"), invite = ticket.getString("invite"), pairKind = kind)
        connect()
    }

    private fun connect() {
        val run = ++generation
        reconnect?.cancel()
        mutable.value = mutable.value.copy(connecting = true, connected = false)
        val req = Request.Builder().url(endpoint.replaceFirst("https://", "wss://") + "/together/ws").build()
        socket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                viewModelScope.launch {
                    if (run != generation || leaving) { webSocket.close(1000, null); return@launch }
                    seq = 0
                    lastPong = SystemClock.elapsedRealtime()
                    val hello = JSONObject().put("room", mutable.value.room).put("token", token).put("protocol", 3).put("games", 1)
                    if (callOnReconnect?.invoke() == false) hello.put("callActive", false)
                    webSocket.send(hello.toString())
                    heartbeat?.cancel()
                    heartbeat = viewModelScope.launch {
                        while (isActive) {
                            if (SystemClock.elapsedRealtime() - lastPong >= 16_000) {
                                reconnectSocket()
                                return@launch
                            }
                            val ping = JSONObject().put("sent", SystemClock.elapsedRealtime())
                            mutable.value.connectionSlow?.let { ping.put("slowConnection", it) }
                            send("ping", ping, requireConnected = false)
                            delay(5000)
                        }
                    }
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                viewModelScope.launch { if (run == generation && !leaving) receive(text) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                viewModelScope.launch { if (run == generation) lost() }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                viewModelScope.launch {
                    if (run != generation || leaving) return@launch
                    if (code in setOf(4001, 4003, 4004)) {
                        heartbeat?.cancel()
                        if (ownedCall.isInitialized()) ownedCall.value.stop() else onConnectionLost?.invoke()
                        mutable.value = TogetherState(error = "Room ended or invitation expired. Join a new room.")
                        games.closed()
                        token = ""
                    } else lost()
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        })
    }

    internal fun checkSignaling() {
        if (state.value.connected && SystemClock.elapsedRealtime() - lastPong >= 10_000) reconnectSocket()
    }

    private fun reconnectSocket() {
        generation++
        socket?.cancel()
        socket = null
        lost()
    }

    private fun lost() {
        if (leaving || token.isEmpty()) return
        heartbeat?.cancel()
        mutable.value = mutable.value.copy(connected = false, connecting = true)
        onConnectionLost?.invoke()
        reconnect?.cancel()
        val delayMs = 1000L shl reconnectAttempts.coerceAtMost(4)
        reconnectAttempts++
        reconnect = viewModelScope.launch { delay(delayMs); connect() }
    }

    private fun media(json: JSONObject) = TogetherMedia(json.getString("id"), json.getString("kind"), json.getString("source"),
        json.optString("title", "Watch together"), json.optLong("duration"), json.optBoolean("live"))
    private fun chat(json: JSONObject) = TogetherChat(json.getString("id"), json.getString("sender"), json.optString("name"), json.getString("text"), json.optLong("time"))
    private fun receive(text: String) {
        runCatching {
            val json = JSONObject(text)
            when (json.getString("type")) {
                "gamesAvailable", "gameState", "gameAim", "gameError", "gameClosed" ->
                    games.receive(json.getString("type"), json, mutable.value.member)
                "fileRead" -> sharedFiles.serve(json)
                "pong" -> {
                    lastPong = SystemClock.elapsedRealtime()
                    val sent = json.getLong("sent"); val received = SystemClock.elapsedRealtime(); val rtt = received - sent
                    if (rtt in 0..20_000) {
                        val average = latencyAverage?.let { it * .66 + rtt * .34 } ?: rtt.toDouble()
                        latencyAverage = average
                        val slow = if (mutable.value.connectionSlow == true) average >= 300 else average >= 450
                        mutable.value = mutable.value.copy(connectionSlow = slow)
                    }
                    if (rtt in 0..bestRtt) {
                        bestRtt = rtt; clockOffset = json.getLong("serverTime") - (sent + received) / 2; clockMeasured = true
                    }
                }
                "state" -> {
                    val old = mutable.value
                    val rev = json.getLong("revision")
                    if (rev < old.revision) return
                    if (!clockMeasured) clockOffset = json.getLong("serverTime") - SystemClock.elapsedRealtime()
                    val memberJson = json.getJSONArray("members").objects()
                    val members = memberJson.map { m -> TogetherMember(m.getString("id"), m.getString("name"),
                        m.getInt("ordinal"), m.getBoolean("online"), m.getBoolean("ready"), m.getBoolean("buffering"),
                        m.getBoolean("call"), m.getBoolean("mic"), m.getBoolean("camera")) }
                    reconnectAttempts = 0
                    mutable.value = old.copy(connected = true, connecting = false, revision = rev, members = members,
                        request = json.optJSONObject("request")?.roomRequest(),
                        media = json.optJSONObject("media")?.let(::media), queue = json.getJSONArray("queue").objects().map(::media),
                        timeline = SharedTimeline(json.getDouble("position").toLong(), json.getLong("serverTime"), json.getBoolean("playing")),
                        wanted = json.getBoolean("wanted"), wait = json.getBoolean("wait"),
                        callFullscreen = json.optBoolean("callFullscreen"), autoAdvanceQueue = json.optBoolean("autoAdvanceQueue"),
                        memberConnections = memberJson.mapNotNull { entry ->
                            (entry.opt("slowConnection") as? Boolean)?.let { entry.getString("id") to it }
                        }.toMap())
                    if (ownedCall.isInitialized()) ownedCall.value.members(mutable.value)
                }
                "network" -> mutable.value = mutable.value.copy(memberConnections = mutable.value.memberConnections +
                    (json.getString("member") to json.getBoolean("slow")))
                "history" -> mutable.value = mutable.value.copy(chat = json.getJSONArray("messages").objects().map(::chat))
                "chat" -> {
                    val item = chat(json)
                    val old = mutable.value
                    mutable.value = old.copy(
                        chat = (old.chat.filterNot { it.id == item.id } + item).takeLast(80),
                        incomingChat = if (item.sender != old.member && old.chat.none { it.id == item.id }) item else old.incomingChat,
                    )
                }
                "reaction" -> mutable.value = mutable.value.copy(reaction = TogetherReaction(json.getString("emoji"), SystemClock.elapsedRealtime()))
                "requestResult" -> mutable.value = mutable.value.copy(answer = RoomAnswer(json.roomRequest(), json.optBoolean("accepted")))
                "signal" -> onSignal?.invoke(json.getJSONObject("payload"))
                "error" -> error(json.optString("message", "Room action failed"))
                "invite" -> mutable.value = mutable.value.copy(invite = json.getString("invite"))
            }
        }.onFailure { error("Received an invalid room response") }
    }

    fun send(type: String, payload: JSONObject = JSONObject(), requireConnected: Boolean = true): Boolean {
        if (requireConnected && !mutable.value.connected) return false
        payload.put("type", type).put("seq", ++seq)
        mutable.value.media?.let { payload.put("mediaId", it.id) }
        return socket?.send(payload.toString()) == true
    }
    fun status(ready: Boolean, buffering: Boolean = false) = send("status", JSONObject().put("ready", ready).put("buffering", buffering))
    fun playback(type: String, position: Long? = null): Boolean {
        if (type in setOf("play", "seek") && !state.value.canStartPlayback) return false
        return send(type, JSONObject().apply { position?.let { put("position", it) } })
    }
    fun mediaFinished(mediaId: String) {
        val room = mutable.value
        if (room.media?.id == mediaId && room.wanted && !room.media.live) {
            send(if (room.autoAdvanceQueue) "finished" else "pause")
        }
    }
    fun setMedia(item: TogetherMedia, queue: Boolean = false) = send(if (queue) "queueAdd" else "media", JSONObject().put("media", item.json()))
    fun leave() {
        enterRequest?.cancel()
        send("leave")
        sharedFiles.clear()
        roomCallSecret = null
        leaving = true; generation++; reconnect?.cancel(); heartbeat?.cancel()
        socket?.close(1000, "Left room"); socket = null; token = ""; reconnectAttempts = 0
        onConnectionLost?.invoke()
        mutable.value = TogetherState()
        games.closed()
    }
    fun restoreGame() {
        if (state.value.room.isNotEmpty() || state.value.connecting) return
        val stored = TogetherGames.restore(getApplication<Application>()) ?: return
        val base = TogetherSources.endpoint(stored.optString("server")) ?: return
        val ticket = stored.optJSONObject("ticket") ?: return
        runCatching { attach(base, ticket, null) }.onFailure { games.closed() }
    }
    override fun onCleared() {
        if (ownedCall.isInitialized()) ownedCall.value.close()
        if (games.state.value.liveGame()) {
            leaving = true
            generation++
            heartbeat?.cancel()
            reconnect?.cancel()
            socket?.close(1000, "Game may reconnect")
        } else leave()
        val executor = http.dispatcher.executorService
        executor.execute { http.connectionPool.evictAll() }
        executor.shutdown()
    }
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
