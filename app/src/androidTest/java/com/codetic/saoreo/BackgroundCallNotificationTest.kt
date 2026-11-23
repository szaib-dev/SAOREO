package com.codetic.saoreo

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

class BackgroundCallNotificationTest {
    @Test fun closedActivityReceivesSilentDndCallAndCallerGetsRingState() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val key = InstrumentationRegistry.getArguments().getString("togetherKey")
        assumeTrue(key != null)
        val app = ApplicationProvider.getApplicationContext<SaoreoApp>()
        if (app.pairing.state.value.partner == "Remote test phone") app.pairing.unpair()
        assumeTrue("Use an unpaired test installation", app.pairing.state.value.partner == null)
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val client = OkHttpClient()
        val events = Channel<JSONObject>(Channel.UNLIMITED)
        val token = newCallPairingSecret()
        var socket: WebSocket? = null
        suspend fun event(predicate: (JSONObject) -> Boolean): JSONObject = withTimeout(15000) {
            var item = events.receive()
            while (!predicate(item)) item = events.receive()
            item
        }
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            try {
                val link = app.pairing.invite(TOGETHER_SERVER, "Notification test", requireNotNull(key))
                val code = requireNotNull(PairLink.parse(Uri.parse(link))).code
                withContext(Dispatchers.IO) {
                    client.newCall(Request.Builder().url("$TOGETHER_SERVER/together/api/pair/accept")
                        .header("Authorization", "Bearer $token")
                        .post(JSONObject().put("code", code).put("name", "Remote test phone").toString()
                            .toRequestBody("application/json".toMediaType())).build()).execute().use {
                        assertTrue(it.isSuccessful)
                    }
                }
                socket = client.newWebSocket(Request.Builder().url(TOGETHER_SERVER.replace("https://", "wss://") + "/together/pair-ws")
                    .header("Authorization", "Bearer $token").build(), object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) { events.trySend(JSONObject(text)) }
                })
                withContext(Dispatchers.Main) {
                    app.pairing.setAvailabilityPreference(true)
                    ContextCompat.startForegroundService(app, Intent(app, TogetherAvailabilityService::class.java))
                }
                withTimeout(15000) { app.pairing.state.first { it.connected && it.online } }
                activity.moveToState(Lifecycle.State.CREATED)
                withContext(Dispatchers.Main) { app.pairing.visible(false) }
                instrumentation.uiAutomation.executeShellCommand("cmd notification set_dnd on").close()
                socket!!.send(JSONObject().put("type", "ring").put("kind", "video").toString())
                val ring = withTimeout(15000) { app.pairing.state.first { it.ring?.incoming == true }.ring!! }
                val notifications = app.getSystemService(NotificationManager::class.java)
                withTimeout(10000) { while (notifications.activeNotifications.none { it.id == 83 }) delay(100) }
                val notification = notifications.activeNotifications.single { it.id == 83 }.notification
                assertEquals(R.drawable.ic_call_incoming_notification, notification.smallIcon.resId)
                assertNull(notifications.getNotificationChannel(notification.channelId).sound)
                assertNull(notification.fullScreenIntent)
                withContext(Dispatchers.Main) { app.pairing.answer(ring.id, false) }
                withTimeout(10000) { app.pairing.state.first { it.ring == null } }
                withContext(Dispatchers.Main) { app.pairing.ring(PairKind.AUDIO) }
                val outgoing = withTimeout(10000) { app.pairing.state.first { it.ring?.incoming == false } }
                assertNull(outgoing.requesting)
                assertTrue(event { it.optJSONObject("ring")?.optBoolean("incoming") == true }.has("ring"))
                withContext(Dispatchers.Main) { app.pairing.cancel(outgoing.ring!!.id) }
            } finally {
                instrumentation.uiAutomation.executeShellCommand("cmd notification set_dnd off").close()
                socket?.close(1000, "Test complete")
                runCatching { app.pairing.unpair() }
                app.stopService(Intent(app, TogetherAvailabilityService::class.java))
                events.close()
                client.dispatcher.executorService.shutdown()
            }
        }
    }
}
