package com.codetic.saoreo

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PairedChatPersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "chat-test-${UUID.randomUUID()}.sqlite"
    private val peer = ChatPeer(randomId(), "a".repeat(64), "b".repeat(64))
    private val backupPassword = "test backup password".toCharArray()
    private fun randomId() = UUID.randomUUID().toString().replace("-", "")
    private fun newChat() = PairedChat(context, databaseName) { _, sender, scope -> testKey(sender, scope) }
    private fun testKey(sender: String, scope: String) = MessageDigest.getInstance("SHA-256").digest("$sender/$scope".encodeToByteArray())

    @Test fun queuesAndRestoresMessageActionsAndAttachments() = runBlocking {
        val input = File.createTempFile("paired-file", ".bin", context.cacheDir).apply {
            writeBytes(ByteArray(101_201) { (it % 217).toByte() })
        }
        val backup = File.createTempFile("paired-backup", ".bin", context.cacheDir)
        val first = newChat()
        try {
            first.activate(peer)
            assertTrue(first.enqueue(peer, "before edit"))
            val original = first.messages.value.single()
            assertTrue(first.act(peer, original, ChatAction.EDIT, "edited text"))
            assertTrue(first.act(peer, original, ChatAction.REACT, "❤️"))
            assertTrue(first.enqueue(peer, "reply text", ChatReference(original.id, true, "edited text")))
            assertTrue(first.enqueueAttachment(peer, Uri.fromFile(input), 0, emptyList()))

            val resumed = newChat()
            resumed.activate(peer)
            assertEquals("edited text", resumed.messages.value.first().body)
            assertEquals(listOf("❤️"), resumed.messages.value.first().reactions)
            assertEquals(original.id, resumed.messages.value[1].reply?.id)
            val fileMessage = resumed.messages.value.last()
            assertArrayEquals(input.readBytes(), resumed.openAttachment(peer, fileMessage) { error("unexpected network") }.readBytes())

            val sent = mutableListOf<JSONObject>()
            val parts = mutableListOf<AttachmentRequest>()
            val incoming = randomId()
            val queued = JSONObject().put("sender", peer.partner).put("id", incoming)
                .put("queued", System.currentTimeMillis()).put("envelope",
                    encryptChatEnvelope(testKey(peer.partner, "chat"), JSONObject().put("id", incoming).put("text", "incoming text").toString()))
            resumed.receiveLive(peer, queued)
            assertEquals(1, resumed.messages.value.count { it.id == incoming })
            resumed.receiveLive(peer, queued)
            assertEquals(1, resumed.messages.value.count { it.id == incoming })
            val route: suspend (String, JSONObject) -> JSONObject = { name, data ->
                if (name == "chat-send") {
                    sent += data
                    JSONObject()
                } else JSONObject().put("inbox", JSONArray().put(queued))
            }
            val transfer: suspend (AttachmentRequest) -> String = {
                parts += it
                if (it.method == "HEAD") "1" else ""
            }
            assertTrue(resumed.sync(peer, route, transfer))
            assertEquals(5, sent.size)
            assertEquals(listOf("HEAD", "PUT", "PUT"), parts.map { it.method })
            assertEquals(listOf(1, 2), parts.filter { it.method == "PUT" }.map { it.part })
            assertEquals(4, resumed.messages.value.size)
            assertEquals(1, resumed.messages.value.count { it.id == incoming })
            assertTrue(resumed.act(peer, original, ChatAction.DELETE, ""))
            assertTrue(resumed.messages.value.first { it.id == original.id }.deleted)

            resumed.exportBackup(peer, Uri.fromFile(backup), backupPassword)
            val restoredPeer = peer.copy(conversation = randomId())
            val restored = newChat()
            restored.activate(restoredPeer)
            restored.restoreBackup(restoredPeer, Uri.fromFile(backup), backupPassword)
            assertEquals(4, restored.messages.value.size)
            val restoredFile = restored.messages.value.single { it.id == fileMessage.id }
            assertArrayEquals(input.readBytes(), restored.openAttachment(restoredPeer, restoredFile) { error("unexpected network") }.readBytes())
        } finally {
            input.delete()
            backup.delete()
            context.deleteDatabase(databaseName)
        }
        Unit
    }
    @Test fun missedCallCursorAndNotificationStateSurviveReopening() = runBlocking {
        try {
            val started = System.currentTimeMillis() - 86_400_000L
            val calls = (1..2).map { suffix ->
                JSONObject().put("id", "deadbeef0000000$suffix").put("kind", "video")
                    .put("incoming", true).put("started", started + suffix)
                    .put("ended", started + suffix + 30_000L).put("outcome", "missed")
            }
            val cursor = "${started + 30_002}:deadbeef00000002"
            val first = newChat()
            first.activate(peer)
            first.sync(peer, { _, request ->
                assertEquals("0:", request.getString("callCursor"))
                JSONObject().put("calls", JSONArray(calls)).put("callCursor", cursor)
            }, { error("Unexpected attachment network request") })
            assertEquals(calls.map { it.getLong("started") }, first.missedCalls(peer).map { it.time })
            first.missedNotified(peer, "deadbeef00000001")
            val restarted = newChat()
            restarted.activate(peer)
            restarted.sync(peer, { _, request ->
                assertEquals(cursor, request.getString("callCursor"))
                JSONObject()
            }, { error("Unexpected attachment network request") })
            assertEquals(listOf("deadbeef00000002"), restarted.missedCalls(peer).map { it.id })
            assertEquals(2, restarted.calls.value.size)
        } finally {
            context.deleteDatabase(databaseName)
        }
        Unit
    }

    @Test fun rejectedUploadCanBeRetriedAndInterruptedUploadResumesAtStoredPart() = runBlocking {
        val input = File.createTempFile("paired-upload", ".bin", context.cacheDir).apply {
            writeBytes(ByteArray(80_000) { (it % 229).toByte() })
        }
        val chat = newChat()
        try {
            chat.activate(peer)
            assertTrue(chat.enqueueAttachment(peer, Uri.fromFile(input), 0, emptyList()))
            assertTrue(chat.enqueueAttachment(peer, Uri.fromFile(input), 0, emptyList()))
            val (rejected, interrupted) = chat.messages.value.map { it.id }
            val sent = mutableListOf<String>()
            val post: suspend (String, JSONObject) -> JSONObject = { name, payload ->
                assertEquals("chat-send", name)
                sent += payload.getString("id")
                JSONObject()
            }
            try {
                chat.sync(peer, post, { request ->
                    when {
                        request.id == rejected -> throw ChatHttpError(404)
                        request.method == "HEAD" -> "0"
                        request.part == 1 -> throw IOException("Connection interrupted")
                        else -> ""
                    }
                }, mediaOnly = true)
                fail("The transient interruption must remain retryable")
            } catch (expected: IOException) {
                assertEquals("Connection interrupted", expected.message)
            }
            assertEquals(-1, chat.messages.value.first { it.id == rejected }.status)
            assertEquals(0, chat.messages.value.first { it.id == interrupted }.status)
            assertTrue(chat.transferProgress.value.isEmpty())
            assertTrue(sent.isEmpty())

            chat.retry(rejected)
            val uploads = mutableListOf<Pair<String, Int>>()
            assertTrue(chat.sync(peer, post, { request ->
                if (request.method == "HEAD") if (request.id == interrupted) "1" else "0"
                else {
                    uploads += request.id to request.part
                    ""
                }
            }, mediaOnly = true))
            assertEquals(listOf(rejected to 0, rejected to 1, interrupted to 1), uploads)
            assertEquals(setOf(rejected, interrupted), sent.toSet())
            assertTrue(chat.messages.value.all { it.status == 1 })
        } finally {
            chat.activate(null)
            input.delete()
            context.deleteDatabase(databaseName)
        }
        Unit
    }
}
