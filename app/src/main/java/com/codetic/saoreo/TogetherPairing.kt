package com.codetic.saoreo

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
                uri.scheme == "https" && uri.host == "szaibdev.duckdns.org" && uri.port == 8765 && uri.path == "/together/pair" -> TOGETHER_SERVER
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

internal data class PairRing(val id: String, val kind: PairKind, val incoming: Boolean, val until: Long, val silent: Boolean, val delivered: Boolean = false)
internal data class PairLaunch(val id: String, val server: String, val kind: PairKind, val ticket: JSONObject)
internal data class PairState(
    val partner: String? = null,
    val online: Boolean = false,
    val connected: Boolean = false,
    val visible: Boolean = false,
    val availableInBackground: Boolean = false,
    val ring: PairRing? = null,
    val requesting: PairKind? = null,
    val launch: PairLaunch? = null,
    val error: String? = null,
)

/** The pairing socket discovers the paired device. Each accepted request has separate room credentials. */
internal class TogetherPairing(app: Application) {
    private val application = app
    private val callIdentity = CallIdentity(app)
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

    suspend fun registerPush() {
        val endpoint = server ?: return
        if (state.value.partner == null) return
        val token = application.getSharedPreferences("together-push", 0).getString("token", null) ?: return
        post(endpoint, "push", JSONObject().put("token", token).put("enabled", resumeAvailabilityAtBoot))
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
                    val error = reply?.optString("error").orEmpty()
                    throw IOException(error.takeUnless { it.isBlank() || it == "404: Not Found" }
                        ?: if (response.code == 404) "This Together server needs the pairing update." else "Couldn’t reach pairing (${response.code}).")
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
        check(callIdentity.startPairing(secret)) { "Couldn’t store the secure pairing key." }
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
        check(callIdentity.startPairing(secret)) { "Couldn’t store the secure pairing key." }
        use(link.server, reply.getString("name"))
        TogetherMessagingService.register(application)
    }

    suspend fun unpair() {
        val endpoint = server ?: return
        post(endpoint, "unpair", JSONObject())
        callIdentity.forgetAll()
        savePartner(null)
        mutable.value = mutable.value.copy(partner = null, online = false, ring = null, launch = null)
    }

    private fun use(endpoint: String, partner: String?) {
        if (server != endpoint) stop()
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

    private fun receive(endpoint: String, message: JSONObject, fromSocket: Boolean = true) {
        when (message.getString("type")) {
            "pairState" -> {
                reconnectDelay = 1_000
                val partner = message.optString("partner").takeUnless { it.isBlank() || it == "null" }
                if (partner != mutable.value.partner) savePartner(partner)
                val ring = message.optJSONObject("ring")?.let { item ->
                    val wire = item.getString("kind")
                    val silent = wire.endsWith("_silent")
                    val kind = PairKind.fromWire(wire.removeSuffix("_silent")) ?: return@let null
                    val remaining = item.getLong("remaining").coerceIn(0L, 30_000L)
                    PairRing(item.getString("id"), kind, item.getBoolean("incoming"), SystemClock.elapsedRealtime() + remaining, silent, item.optBoolean("delivered"))
                }
                if (partner != null && mutable.value.partner != partner) TogetherMessagingService.register(application)
                if (ring?.incoming == true) scope.launch {
                    runCatching { post(endpoint, "received", JSONObject().put("id", ring.id)) }
                }
                mutable.value = mutable.value.copy(connected = fromSocket || mutable.value.connected, partner = partner, online = message.optBoolean("online"), ring = ring, requesting = if (ring != null) null else mutable.value.requesting,
                    launch = mutable.value.launch.takeIf { partner != null })
            }
            "ringResult" -> {
                val ring = mutable.value.ring
                if (ring?.id == message.getString("id")) {
                    mutable.value = mutable.value.copy(ring = null)
                    if (!ring.incoming) when (message.getString("outcome")) {
                        "declined" -> error("Your partner declined.")
                        "missed" -> error("Your partner didn’t answer.")
                    }
                }
            }
            "pairLaunch" -> {
                val kind = PairKind.fromWire(message.getString("kind")) ?: return
                mutable.value = mutable.value.copy(launch = PairLaunch(message.getString("id"), endpoint, kind, message.getJSONObject("ticket")))
            }
            "error" -> error(message.getString("message"))
        }
    }

    private fun send(type: String, body: JSONObject): Boolean {
        if (mutable.value.connected && socket?.send(body.put("type", type).toString()) == true) return true
        error("Reconnect to Together and try again.")
        return false
    }

    fun ring(kind: PairKind, room: JSONObject? = null, silent: Boolean = false) {
        if (mutable.value.requesting != null || mutable.value.ring != null) return
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
    fun answer(id: String, accepted: Boolean) = send("answer", JSONObject().put("id", id).put("accepted", accepted))
    fun cancel(id: String) = send("cancel", JSONObject().put("id", id))
    fun joined(launch: PairLaunch) {
        if (mutable.value.launch?.id == launch.id) mutable.value = mutable.value.copy(launch = null)
        send("ack", JSONObject().put("id", launch.id))
    }
    fun error(text: String?) { mutable.value = mutable.value.copy(error = text, requesting = if (text != null) null else mutable.value.requesting) }
}
