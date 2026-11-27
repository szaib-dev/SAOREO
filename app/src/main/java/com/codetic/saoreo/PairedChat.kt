package com.codetic.saoreo

import android.content.Context
import android.content.ContentValues
import android.net.Uri
import java.io.File
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.nimbusds.jose.EncryptionMethod
import com.nimbusds.jose.JWEAlgorithm
import com.nimbusds.jose.JWEHeader
import com.nimbusds.jose.JWEObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.DirectDecrypter
import com.nimbusds.jose.crypto.DirectEncrypter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.SecretKey

internal data class ChatPeer(val conversation: String, val self: String, val partner: String)
internal data class PairedMessage(
    val id: String, val body: String, val outgoing: Boolean, val time: Long, val status: Int,
    val attachment: ChatAttachment? = null,
    val reply: ChatReference? = null, val edited: Boolean = false, val deleted: Boolean = false,
    val reactions: List<String> = emptyList(), val ownReaction: String? = null, val seen: Boolean = true, val call: PairedCallEntry? = null, val played: Boolean = false,
)

internal data class PairedPhotoChoices(val conversation: String, val partnerPhoto: String?, val myPhoto: String?)

internal data class ChatReference(val id: String, val outgoing: Boolean, val preview: String)
internal enum class SharedChatTab { Media, Files, Links, Voice }
internal enum class ChatAction(val wire: String) { EDIT("edit"), DELETE("delete"), REACT("react") }
internal val chatReactions = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

internal data class PairedCallEntry(
    val id: String, val kind: PairKind, val incoming: Boolean, val time: Long,
    val outcome: String, val connected: Long = 0L, val duration: Long = 0L, val ended: Long = 0L, val silent: Boolean = false,
) {
    val title: String get() = when (outcome) {
        "missed" -> "Missed ${kind.title.lowercase()}"
        "completed", "accepted", "connected" -> kind.title
        else -> "${kind.title} · ${outcome.replaceFirstChar(Char::uppercaseChar)}"
    }
}

internal class PairedChat(private val context: Context, private val databaseName: String = "paired-chat-v1.db",
    private val deriveKey: ((ChatPeer, String, String) -> ByteArray)? = null) {
    private val attachments = PairedAttachments(context)
    val transferProgress get() = attachments.progress
    private val database = object : SQLiteOpenHelper(context, databaseName, null, 6) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE message (
                conversation TEXT NOT NULL, id TEXT NOT NULL, body TEXT NOT NULL,
                outgoing INTEGER NOT NULL, time INTEGER NOT NULL, status INTEGER NOT NULL,
                envelope TEXT, attachment TEXT, delivered INTEGER NOT NULL DEFAULT 0, seen INTEGER NOT NULL DEFAULT 0,
                read_ack INTEGER NOT NULL DEFAULT 0, checked INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(conversation, outgoing, id)
            )""")
            db.execSQL("CREATE INDEX chat_time ON message(conversation, time, id)")
            addActions(db)
            addCalls(db)
            addSyncState(db)
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) db.execSQL("ALTER TABLE message ADD COLUMN attachment TEXT")
            if (oldVersion < 3) addActions(db)
            if (oldVersion < 4) addCalls(db)
            else if (oldVersion < 5) {
                db.execSQL("ALTER TABLE paired_call ADD COLUMN ended INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE paired_call ADD COLUMN silent INTEGER NOT NULL DEFAULT 0")
            }
            if (oldVersion < 6) addSyncState(db)
        }

        private fun addSyncState(db: SQLiteDatabase) {
            db.execSQL("ALTER TABLE paired_call ADD COLUMN notified INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE TABLE call_sync (conversation TEXT PRIMARY KEY,cursor TEXT NOT NULL)")
            db.execSQL("CREATE INDEX missed_call_notice ON paired_call(conversation,incoming,outcome,silent,notified,time)")
        }

        private fun addCalls(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE paired_call (
                conversation TEXT NOT NULL,id TEXT NOT NULL,kind TEXT NOT NULL,incoming INTEGER NOT NULL,
                time INTEGER NOT NULL,outcome TEXT NOT NULL,connected INTEGER NOT NULL DEFAULT 0,
                duration INTEGER NOT NULL DEFAULT 0, ended INTEGER NOT NULL DEFAULT 0, silent INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(conversation,id)
            )""")
            db.execSQL("CREATE INDEX paired_call_time ON paired_call(conversation,time DESC)")
        }

        private fun addActions(db: SQLiteDatabase) {
            db.execSQL("ALTER TABLE message ADD COLUMN reply TEXT")
            db.execSQL("ALTER TABLE message ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE message ADD COLUMN event TEXT")
            db.execSQL("ALTER TABLE message ADD COLUMN target TEXT")
            db.execSQL("ALTER TABLE message ADD COLUMN target_outgoing INTEGER")
            db.execSQL("ALTER TABLE message ADD COLUMN revision INTEGER")
            db.execSQL("CREATE INDEX chat_action ON message(conversation,target,target_outgoing,event,outgoing,revision)")
        }
    }
    private val lock = Mutex()
    private val mutable = MutableStateFlow(emptyList<PairedMessage>())
    val messages = mutable.asStateFlow()
    private val mutablePhotos = MutableStateFlow<PairedPhotoChoices?>(null)
    val photos = mutablePhotos.asStateFlow()
    private val mutableCalls = MutableStateFlow(emptyList<PairedCallEntry>())
    val calls = mutableCalls.asStateFlow()
    private var callLimit = 80
    private var active: ChatPeer? = null
    private var limit = 80
    private val idPattern = Regex("[a-f0-9]{32}")

    suspend fun activate(peer: ChatPeer?) = onDatabase {
        if (peer != active) {
            limit = 80
            callLimit = 80
        }
        active = peer
        publish()
        publishCalls()
    }

    suspend fun older() = onDatabase {
        limit += 80
        publish()
    }

    suspend fun replyOriginal(reference: ChatReference): PairedMessage? = onDatabase {
        val peer = active ?: return@onDatabase null
        readMessages(peer, 1, reference = reference).singleOrNull()
    }

    suspend fun revealReplyOriginal(reference: ChatReference): Boolean = onDatabase {
        val peer = active ?: return@onDatabase false
        if (mutable.value.any { it.id == reference.id && it.outgoing == reference.outgoing }) return@onDatabase true
        val count = database.readableDatabase.rawQuery(
            """SELECT count(*) FROM message WHERE conversation=? AND hidden=0 AND event IS NULL
                AND time >= (SELECT time FROM message WHERE conversation=? AND id=? AND outgoing=?
                    AND hidden=0 AND event IS NULL)""",
            arrayOf(peer.conversation, peer.conversation, reference.id, if (reference.outgoing) "1" else "0"),
        ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
        if (count == 0) return@onDatabase false
        limit = maxOf(limit, count)
        publish()
        true
    }

    suspend fun olderCalls() = onDatabase {
        callLimit += 80
        publishCalls()
    }

    suspend fun callMedia(peer: ChatPeer, id: String, connectedAt: Long, duration: Long, ended: Boolean) = onDatabase {
        val db = database.writableDatabase
        db.execSQL("""UPDATE paired_call SET connected=?, duration=?, outcome=?, ended=? WHERE conversation=? AND id=?""",
            arrayOf<Any>(connectedAt, duration, if (!ended) "connected" else if (connectedAt > 0) "completed" else "failed",
                if (ended) System.currentTimeMillis() else 0L, peer.conversation, id))
        publishCalls()
    }

    suspend fun acceptedCall(peer: ChatPeer, id: String, kind: PairKind, incoming: Boolean, time: Long) = onDatabase {
        if (kind == PairKind.WATCH) return@onDatabase
        recordCall(database.writableDatabase, peer, id, kind, incoming, time, "accepted")
        publishCalls()
    }

    private fun recordCall(db: SQLiteDatabase, peer: ChatPeer, id: String, kind: PairKind, incoming: Boolean,
        time: Long, outcome: String, ended: Long = 0L, silent: Boolean = false) {
        db.insertWithOnConflict("paired_call", null, ContentValues().apply {
            put("conversation", peer.conversation)
            put("id", id)
            put("kind", kind.wire)
            put("incoming", if (incoming) 1 else 0)
            put("time", time)
            put("outcome", outcome)
            put("ended", ended)
            put("silent", if (silent) 1 else 0)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    suspend fun missedCalls(peer: ChatPeer): List<PairedCallEntry> = onDatabase {
        database.readableDatabase.rawQuery("""SELECT id,kind,time FROM paired_call WHERE conversation=?
            AND incoming=1 AND outcome='missed' AND silent=0 AND notified=0 ORDER BY time,id LIMIT 50""",
            arrayOf(peer.conversation)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val kind = PairKind.fromWire(cursor.getString(1)) ?: continue
                    add(PairedCallEntry(cursor.getString(0), kind, true, cursor.getLong(2), "missed"))
                }
            }
        }
    }

    suspend fun missedNotified(peer: ChatPeer, id: String) = onDatabase {
        database.writableDatabase.execSQL("UPDATE paired_call SET notified=1 WHERE conversation=? AND id=?",
            arrayOf(peer.conversation, id))
    }

    suspend fun applyReceipts(peer: ChatPeer, statuses: JSONArray) = onDatabase {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            for (i in 0 until statuses.length()) {
                val row = statuses.optJSONObject(i) ?: continue
                val id = row.optString("id").takeIf(idPattern::matches) ?: continue
                writeStatus(peer, id, row)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        publish()
    }

    private fun publishCalls() {
        val peer = active
        val hidden = peer?.let { context.getSharedPreferences("paired-hidden-calls", Context.MODE_PRIVATE)
            .getStringSet(it.conversation, emptySet()) }.orEmpty()
        mutableCalls.value = if (peer == null) emptyList() else database.readableDatabase.rawQuery(
            "SELECT id,kind,incoming,time,outcome,connected,duration,ended,silent FROM paired_call WHERE conversation=? ORDER BY time DESC LIMIT ?",
            arrayOf(peer.conversation, callLimit.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val kind = PairKind.fromWire(cursor.getString(1)) ?: continue
                    if (cursor.getString(0) in hidden) continue
                    add(PairedCallEntry(cursor.getString(0), kind, cursor.getInt(2) == 1, cursor.getLong(3),
                        cursor.getString(4), cursor.getLong(5), cursor.getLong(6), cursor.getLong(7), cursor.getInt(8) == 1))
                }
            }
        }
    }

    suspend fun enqueue(peer: ChatPeer, text: String, reply: ChatReference? = null): Boolean = onDatabase {
        if (peer != active || text.isBlank() || text.length > 2000) return@onDatabase false
        insertOutgoing(peer, newId(), text, key(peer, peer.self), null, reply)
    }

    suspend fun enqueueAttachment(peer: ChatPeer, uri: Uri, duration: Long, waveform: List<Int>, reply: ChatReference? = null, caption: String = "", album: String? = null): Boolean {
        if (!onDatabase { peer == active }) return false
        val id = newId()
        val localKey = withContext(Dispatchers.IO) { key(peer, peer.self) }
        var committed = false
        try {
            val attachment = attachments.stage(peer, withContext(Dispatchers.IO) { key(peer, peer.self, "attachment") },
                id, uri, duration > 0, duration, waveform).copy(album = album)
            return onDatabase {
                if (peer != active) return@onDatabase false
                val descriptor = if (caption.encodeToByteArray().size > 2700) attachment.copy(thumbnail = null) else attachment
                insertOutgoing(peer, id, if (caption.isNotBlank()) caption else if (attachment.voice) "Voice note" else attachment.name, localKey, descriptor, reply)
                    .also { committed = it }
            }
        } finally {
            if (!committed) withContext(kotlinx.coroutines.NonCancellable) { attachments.discardOutgoing(peer, id) }
        }
    }

    suspend fun hide(peer: ChatPeer, items: List<PairedMessage>) = onDatabase {
        if (peer != active) return@onDatabase
        val db = database.writableDatabase
        val callIds = items.mapNotNull { it.call?.id }
        if (callIds.isNotEmpty()) {
            val prefs = context.getSharedPreferences("paired-hidden-calls", Context.MODE_PRIVATE)
            val hidden = prefs.getStringSet(peer.conversation, emptySet()).orEmpty()
            prefs.edit().putStringSet(peer.conversation, hidden + callIds).apply()
            publishCalls()
        }
        for (item in items.filter { it.call == null }) db.execSQL("UPDATE message SET hidden=1 WHERE conversation=? AND id=? AND outgoing=? AND event IS NULL",
            arrayOf<Any>(peer.conversation, item.id, if (item.outgoing) 1 else 0))
        publish()
    }

    suspend fun act(peer: ChatPeer, item: PairedMessage, action: ChatAction, value: String): Boolean = onDatabase {
        if (peer != active || item.deleted || (action != ChatAction.REACT && !item.outgoing) ||
            (action == ChatAction.EDIT && (item.attachment != null || value.isBlank() || value.length > 2000)) ||
            (action == ChatAction.REACT && value.isNotEmpty() && value !in chatReactions)) return@onDatabase false
        enqueueEvent(peer, action.wire, item.id, item.outgoing, value)
    }

    suspend fun voicePlayed(peer: ChatPeer, item: PairedMessage): Boolean = onDatabase {
        if (peer != active || item.outgoing || item.deleted || item.attachment?.voice != true) return@onDatabase false
        val db = database.readableDatabase
        val alreadyQueued = db.rawQuery("""SELECT 1 FROM message WHERE conversation=? AND event='played'
            AND outgoing=1 AND target=? AND target_outgoing=0 AND status>=0 LIMIT 1""",
            arrayOf(peer.conversation, item.id)).use { it.moveToFirst() }
        if (alreadyQueued) return@onDatabase false
        enqueueEvent(peer, "played", item.id, false, "")
    }

    suspend fun choosePhoto(peer: ChatPeer, value: String): Boolean = onDatabase {
        if (peer != active || !validPhotoChoice(value)) return@onDatabase false
        enqueueEvent(peer, "profile_photo", peer.conversation, false, value)
    }

    private fun enqueueEvent(peer: ChatPeer, type: String, target: String, own: Boolean, value: String): Boolean {
        val db = database.writableDatabase
        val revision = db.rawQuery("SELECT coalesce(max(revision),0)+1 FROM message WHERE conversation=? AND outgoing=1",
            arrayOf(peer.conversation)).use { it.moveToFirst(); it.getLong(0) }
        val event = JSONObject().put("type", type).put("target", target).put("own", own).put("revision", revision)
        return insertOutgoing(peer, newId(), value, key(peer, peer.self), null, event = event)
    }

    suspend fun storage(peer: ChatPeer): Triple<Long, Long, Long> = onDatabase {
        val (media, cache) = attachments.size(peer)
        val dbFile = context.getDatabasePath(databaseName)
        Triple(media, cache, dbFile.length() + File(dbFile.path + "-wal").length())
    }

    suspend fun clearMediaCache() { attachments.clearCache() }

    suspend fun autoDownload(peer: ChatPeer, transfer: suspend (AttachmentRequest) -> String) {
        val recent = onDatabase {
            if (active != peer) emptyList() else readMessages(peer, 32).takeLast(24).asReversed()
        }
        var stored = 0
        for (message in recent) {
            val item = message.attachment ?: continue
            if (message.outgoing || attachments.contains(peer, peer.partner, item)) continue
            try { openAttachment(peer, message, transfer) }
            catch (failure: ChatHttpError) { if (failure.code !in listOf(404, 410)) throw failure }
            if (++stored >= 6) break
        }
    }

    suspend fun exportBackup(peer: ChatPeer, uri: Uri, password: CharArray) = onDatabase {
        check(peer == active)
        val db = database.readableDatabase
        val senderKey by lazy { key(peer, peer.self, "attachment") }
        val recipientKey by lazy { key(peer, peer.partner, "attachment") }
        checkNotNull(context.contentResolver.openOutputStream(uri, "w")).use { file ->
            PairedBackup.write(file, password) { output ->
                db.rawQuery("""SELECT id,body,outgoing,time,status,attachment,reply,hidden,event,target,target_outgoing,revision,seen
                    FROM message WHERE conversation=? ORDER BY rowid""", arrayOf(peer.conversation)).use { cursor ->
                    while (cursor.moveToNext()) {
                        val row = JSONObject()
                        for (column in cursor.columnNames) {
                            val index = cursor.getColumnIndexOrThrow(column)
                            if (!cursor.isNull(index)) row.put(column, cursor.getString(index))
                        }
                        val outgoing = cursor.getInt(2) == 1
                        val author = if (outgoing) peer.self else peer.partner
                        val item = cursor.getString(5)?.let { ChatAttachment.parse(JSONObject(it)) }
                        val media = item != null && attachments.contains(peer, author, item)
                        output.writeByte(1)
                        output.writeUTF(row.put("media", media).toString())
                        if (media) attachments.exportMedia(peer, author, checkNotNull(item), if (outgoing) senderKey else recipientKey, output)
                    }
                }
                db.rawQuery("""SELECT id,kind,incoming,time,outcome,connected,duration,ended,silent FROM paired_call
                    WHERE conversation=? ORDER BY time""", arrayOf(peer.conversation)).use { cursor ->
                    while (cursor.moveToNext()) {
                        val row = JSONObject()
                        for (column in cursor.columnNames) row.put(column, cursor.getString(cursor.getColumnIndexOrThrow(column)))
                        output.writeByte(2)
                        output.writeUTF(row.toString())
                    }
                }
                output.writeByte(0)
            }
        }
    }

    suspend fun restoreBackup(peer: ChatPeer, uri: Uri, password: CharArray) = onDatabase {
        check(peer == active)
        val db = database.writableDatabase
        val work = java.nio.file.Files.createTempDirectory(context.filesDir.toPath(), "backup-import-").toFile()
        val staged = mutableListOf<Pair<File, File>>()
        val moved = mutableListOf<File>()
        val senderKey by lazy { key(peer, peer.self, "attachment") }
        val recipientKey by lazy { key(peer, peer.partner, "attachment") }
        var count = 0
        var bytes = 0L
        var complete = false
        db.beginTransaction()
        try {
            checkNotNull(context.contentResolver.openInputStream(uri)).use { file ->
                PairedBackup.read(file, password) { input ->
                    while (true) {
                        val type = input.readUnsignedByte()
                        if (type == 0) break
                        check(++count <= 250_000) { "Backup has too many records" }
                        val row = JSONObject(input.readUTF())
                        val id = row.getString("id")
                        val time = row.getLong("time").also { check(it > 0) }
                        when (type) {
                            1 -> {
                                check(idPattern.matches(id))
                                val body = row.getString("body")
                                val outgoing = row.getInt("outgoing").also { check(it in 0..1) }
                                val item = row.optString("attachment").takeIf(String::isNotEmpty)?.let {
                                    checkNotNull(ChatAttachment.parse(JSONObject(it))).also { descriptor -> check(descriptor.id == id) }
                                }
                                val event = row.optString("event")
                                val target = if (event == "profile_photo") peer.conversation else row.optString("target")
                                check(event == "profile_photo" || body.length <= 2000)
                                val reference = row.optString("reply").takeIf(String::isNotEmpty)?.let(::JSONObject)?.let {
                                    check(idPattern.matches(it.getString("id")) && it.getString("preview").length <= 120)
                                    check(it.opt("own") is Boolean)
                                    it.toString()
                                }
                                val originalStatus = row.getInt("status")
                                if (event.isNotEmpty()) {
                                    val command = JSONObject().put("type", event).put("target", target).put("revision", row.getLong("revision"))
                                        .put("own", row.getInt("target_outgoing") == outgoing)
                                    check(validEvent(command, body, peer.conversation))
                                }
                                if (row.optBoolean("media")) {
                                    val descriptor = checkNotNull(item)
                                    bytes += descriptor.size
                                    check(bytes <= 2L * 1024 * 1024 * 1024) { "Backup media exceeds 2 GB" }
                                    val author = if (outgoing == 1) peer.self else peer.partner
                                    staged += attachments.restoreMedia(peer, author, descriptor,
                                        if (outgoing == 1) senderKey else recipientKey, input, work)
                                }
                                val storedStatus = if (outgoing == 1 && originalStatus <= 0) 4 else originalStatus.coerceIn(1, 4)
                                db.insertWithOnConflict("message", null, ContentValues().apply {
                                    put("conversation", peer.conversation)
                                    put("id", id)
                                    put("time", time)
                                    put("outgoing", outgoing)
                                    put("body", body)
                                    put("status", if (event.isNotEmpty() && originalStatus < 0) -1 else storedStatus)
                                    put("attachment", item?.json()?.toString())
                                    put("reply", reference)
                                    put("hidden", row.optInt("hidden").coerceIn(0, 1))
                                    put("seen", 1)
                                    put("delivered", 1)
                                    put("read_ack", 1)
                                    if (event.isNotEmpty()) {
                                        put("event", event)
                                        put("target", target)
                                        put("target_outgoing", row.getInt("target_outgoing").coerceIn(0, 1))
                                        put("revision", row.getLong("revision"))
                                    }
                                }, SQLiteDatabase.CONFLICT_IGNORE)
                            }
                            2 -> {
                                check(id.matches(Regex("[a-f0-9]{16}")))
                                val kind = checkNotNull(PairKind.fromWire(row.getString("kind")))
                                val outcome = row.getString("outcome")
                                check(kind != PairKind.WATCH && outcome in setOf("accepted", "connected", "completed", "failed",
                                    "unavailable", "missed", "declined", "cancelled", "busy"))
                                recordCall(db, peer, id, kind, row.getInt("incoming") == 1, time, outcome, row.optLong("ended"), row.optInt("silent") == 1)
                                db.execSQL("""UPDATE paired_call SET connected=?, duration=? WHERE conversation=? AND id=?
                                    AND connected=0 AND outcome=?""", arrayOf<Any>(row.getLong("connected"), row.getLong("duration"),
                                    peer.conversation, id, outcome))
                            }
                            else -> error("Unknown backup record")
                        }
                    }
                    check(input.read() == -1) { "Unexpected backup contents" }
                }
            }
            for ((source, destination) in staged) {
                if (destination.exists()) continue
                destination.parentFile?.mkdirs()
                check(source.renameTo(destination)) { "Couldn’t restore encrypted media" }
                moved += destination
            }
            db.setTransactionSuccessful()
            complete = true
        } finally {
            try { db.endTransaction() }
            catch (failure: Exception) { complete = false; throw failure }
            finally {
                if (!complete) moved.forEach { it.deleteRecursively() }
                work.deleteRecursively()
            }
        }
        publish()
        publishCalls()
    }

    suspend fun shared(tab: SharedChatTab, maximum: Int): List<PairedMessage> = onDatabase {
        val peer = active ?: return@onDatabase emptyList()
        readMessages(peer, maximum, shared = tab)
    }

    suspend fun sharedCount(): Long = onDatabase {
        val peer = active ?: return@onDatabase 0L
        val body = """coalesce((SELECT e.body FROM message e WHERE e.conversation=m.conversation
            AND e.target=m.id AND e.target_outgoing=m.outgoing AND e.outgoing=m.outgoing
            AND e.status>=0 AND e.event='edit' ORDER BY e.revision DESC LIMIT 1),m.body)"""
        database.readableDatabase.rawQuery("""SELECT count(*) FROM message m
            WHERE m.conversation=? AND m.hidden=0 AND m.event IS NULL
            AND NOT EXISTS (SELECT 1 FROM message e WHERE e.conversation=m.conversation AND e.target=m.id
                AND e.target_outgoing=m.outgoing AND e.outgoing=m.outgoing AND e.status>=0 AND e.event='delete')
            AND ((m.attachment IS NOT NULL AND json_extract(m.attachment,'$.voice')=0)
                OR (m.attachment IS NULL AND ($body LIKE '%https://%' OR $body LIKE '%http://%')))""",
            arrayOf(peer.conversation)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    suspend fun search(query: String): List<PairedMessage> = onDatabase {
        val peer = active ?: return@onDatabase emptyList()
        if (query.isBlank()) return@onDatabase mutable.value
        readMessages(peer, 150, query.trim())
    }

    fun cachedAttachment(peer: ChatPeer, message: PairedMessage): File? = message.attachment?.let {
        attachments.cached(peer, if (message.outgoing) peer.self else peer.partner, it).takeIf(File::isFile)
    }

    suspend fun openAttachment(peer: ChatPeer, message: PairedMessage, transfer: suspend (AttachmentRequest) -> String): File {
        val item = requireNotNull(message.attachment)
        check(onDatabase { peer == active })
        val author = if (message.outgoing) peer.self else peer.partner
        val decryptionKey = withContext(Dispatchers.IO) { key(peer, author, "attachment") }
        return attachments.open(peer, author, item, decryptionKey, transfer)
    }

    private fun newId() = UUID.randomUUID().toString().replace("-", "")

    private fun insertOutgoing(peer: ChatPeer, id: String, text: String, key: ByteArray, attachment: ChatAttachment?,
        reply: ChatReference? = null, event: JSONObject? = null): Boolean {
        val clear = JSONObject().put("id", id).put("text", text)
        attachment?.let { clear.put("attachment", it.json()) }
        val reference = reply?.let { JSONObject().put("id", it.id).put("own", it.outgoing).put("preview", it.preview.take(120)) }
        reference?.let { clear.put("reply", it) }
        event?.let { clear.put("event", it) }
        val envelope = encryptChatEnvelope(key, clear.toString())
        database.writableDatabase.insertOrThrow("message", null, ContentValues().apply {
            put("conversation", peer.conversation)
            put("id", id)
            put("body", text)
            put("outgoing", 1)
            put("time", System.currentTimeMillis())
            put("status", 0)
            put("envelope", envelope)
            put("attachment", attachment?.json()?.toString())
            put("reply", reference?.toString())
            if (event != null) putEvent(event, true)
        })
        publish()
        return true
    }

    suspend fun retry(id: String) = onDatabase {
        val peer = active ?: return@onDatabase
        database.writableDatabase.execSQL("UPDATE message SET status=0, time=? WHERE conversation=? AND id=? AND outgoing=1 AND status=-1 AND envelope IS NOT NULL",
            arrayOf<Any>(System.currentTimeMillis(), peer.conversation, id))
        publish()
    }

    suspend fun seen(ids: List<String>): Boolean = onDatabase {
        val peer = active ?: return@onDatabase false
        val values = ContentValues().apply { put("seen", 1) }
        var changed = false
        ids.forEach { id ->
            changed = database.writableDatabase.update("message", values,
                "conversation=? AND id=? AND outgoing=0 AND seen=0", arrayOf(peer.conversation, id)) > 0 || changed
        }
        changed
    }

    suspend fun unread(peer: ChatPeer): List<PairedMessage> = onDatabase {
        database.readableDatabase.rawQuery("""SELECT id,body,time FROM message
            WHERE conversation=? AND outgoing=0 AND seen=0 AND hidden=0 AND event IS NULL ORDER BY time DESC, id DESC LIMIT 8""",
            arrayOf(peer.conversation)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(PairedMessage(cursor.getString(0), cursor.getString(1),
                    false, cursor.getLong(2), 2))
            }.asReversed()
        }
    }

    suspend fun sync(peer: ChatPeer, post: suspend (String, JSONObject) -> JSONObject,
        transfer: suspend (AttachmentRequest) -> String, mediaOnly: Boolean? = null, reconcileReceipts: Boolean = true): Boolean {
        if (mediaOnly != false) attachments.acknowledgeDownloads(peer, transfer)
        val pendingFilter = when (mediaOnly) {
            true -> "AND attachment IS NOT NULL"
            false -> "AND attachment IS NULL"
            null -> ""
        }
        val receiptSince = System.currentTimeMillis()
        var reject = emptyList<String>()
        while (true) {
            var deferred: ChatHttpError? = null
            val pending = onDatabase {
                database.readableDatabase.rawQuery(
                    "SELECT id,envelope,attachment FROM message WHERE conversation=? AND outgoing=1 AND status=0 $pendingFilter ORDER BY rowid LIMIT 12",
                    arrayOf(peer.conversation)
                ).use { cursor -> buildList { while (cursor.moveToNext()) add(Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2))) } }
            }
            for ((id, envelope, descriptor) in pending) {
                try {
                    if (descriptor != null) {
                        val item = ChatAttachment.parse(JSONObject(descriptor)) ?: error("Invalid stored attachment")
                        attachments.upload(peer, item, transfer)
                    }
                    val ack = post("chat-send", JSONObject().put("conversation", peer.conversation).put("id", id)
                        .put("envelope", envelope).put("attachment", descriptor != null))
                    updateStatus(peer, id, ack)
                } catch (error: java.io.FileNotFoundException) {
                    failPending(peer, id)
                } catch (error: ChatHttpError) {
                    if (descriptor == null && error.code in listOf(403, 404)) return false
                    if (error.code == 429) {
                        deferred = error
                        break
                    }
                    val permanent = error.code in listOf(400, 409, 413, 410) ||
                        descriptor != null && error.code in listOf(401, 403, 404, 405, 415, 422)
                    if (!permanent) throw error
                    failPending(peer, id)
                }
            }

            if (mediaOnly == true) {
                deferred?.let { throw it }
                if (pending.size < 12) return true
                continue
            }

            val (delivered, read, tracked) = onDatabase {
                val cutoff = System.currentTimeMillis() - 14L * 86_400_000
                database.writableDatabase.execSQL("""UPDATE message SET status=-1 WHERE conversation=? AND outgoing=1
                    AND status=1 AND time<=? AND envelope IS NOT NULL""", arrayOf<Any>(peer.conversation, cutoff))
                val read = ids(peer, "outgoing=0 AND event IS NULL AND seen=1 AND read_ack=0")
                val delivered = (read + ids(peer, "outgoing=0 AND delivered=0")).distinct().take(50)
                Triple(delivered, read,
                    if (reconcileReceipts) ids(peer, "outgoing=1 AND status BETWEEN 1 AND 2 AND checked < $receiptSince AND time > $cutoff",
                        order = "checked, time DESC") else emptyList())
            }
            val callCursor = onDatabase {
                database.readableDatabase.rawQuery("SELECT cursor FROM call_sync WHERE conversation=?", arrayOf(peer.conversation))
                    .use { if (it.moveToFirst()) it.getString(0) else "0:" }
            }
            val reply = post("chat-sync", JSONObject().put("conversation", peer.conversation).put("callCursor", callCursor)
                .put("delivered", JSONArray(delivered)).put("read", JSONArray(read)).put("status", JSONArray(tracked)).put("rejected", JSONArray(reject)))
            val rejected = mutableListOf<String>()
            val added = onDatabase {
                val db = database.writableDatabase
                db.beginTransaction()
                try {
                    for (id in delivered) db.execSQL(
                        "UPDATE message SET delivered=1 WHERE conversation=? AND outgoing=0 AND id=?", arrayOf(peer.conversation, id))
                    for (id in read) db.execSQL(
                        "UPDATE message SET read_ack=1 WHERE conversation=? AND outgoing=0 AND id=?", arrayOf(peer.conversation, id))
                    val inbox = reply.optJSONArray("inbox") ?: JSONArray()
                    var count = 0
                    val decrypter by lazy { DirectDecrypter(key(peer, peer.partner)) }
                    for (i in 0 until inbox.length()) {
                        val row = inbox.getJSONObject(i)
                        when (storeIncoming(db, peer, row) { decrypter }) {
                            1 -> count++
                            -1 -> rejected.add(row.getString("id"))
                        }
                    }
                    for (id in tracked) db.execSQL(
                        "UPDATE message SET checked=? WHERE conversation=? AND outgoing=1 AND id=?",
                        arrayOf<Any>(receiptSince, peer.conversation, id))
                    val statuses = reply.optJSONArray("status") ?: JSONArray()
                    for (i in 0 until statuses.length()) {
                        val row = statuses.getJSONObject(i)
                        writeStatus(peer, row.getString("id"), row)
                    }
                    val callHistory = reply.optJSONArray("calls") ?: JSONArray()
                    for (i in 0 until callHistory.length()) {
                        val row = callHistory.optJSONObject(i) ?: continue
                        val id = row.optString("id").takeIf { it.matches(Regex("[a-f0-9]{16}")) } ?: continue
                        val kind = PairKind.fromWire(row.optString("kind")) ?: continue
                        val result = row.optString("outcome")
                        if (kind == PairKind.WATCH || result !in setOf("accepted", "unavailable", "missed", "declined", "cancelled", "busy", "failed")) continue
                        recordCall(db, peer, id, kind, row.optBoolean("incoming"), row.optLong("started"), result,
                            row.optLong("ended"), row.optBoolean("silent"))
                    }
                    val next = reply.optString("callCursor").takeIf { it.matches(Regex("[0-9]{1,15}:[a-f0-9]{16}")) }
                    if (next != null) db.execSQL("INSERT OR REPLACE INTO call_sync(conversation,cursor) VALUES(?,?)",
                        arrayOf(peer.conversation, next))
                    db.setTransactionSuccessful()
                    count
                } finally {
                    db.endTransaction()
                    publish()
                    publishCalls()
                }
            }
            val repeatedRejection = reject.isNotEmpty() && rejected == reject
            reject = rejected
            if (deferred != null) throw deferred
            if (added == 0 && delivered.isEmpty() && read.isEmpty() && tracked.size < 50 && pending.size < 12 && !reply.optBoolean("moreCalls") &&
                (reject.isEmpty() || repeatedRejection)) return true
        }
    }

    suspend fun receiveLive(peer: ChatPeer, row: JSONObject) = onDatabase {
        val db = database.writableDatabase
        if (storeIncoming(db, peer, row) { DirectDecrypter(key(peer, peer.partner)) } == 1) publish()
    }

    // The socket and catch-up share the same authenticated decoding and durable insert.
    private fun storeIncoming(db: SQLiteDatabase, peer: ChatPeer, row: JSONObject, decrypter: () -> DirectDecrypter): Int {
        if (row.optString("sender") != peer.partner) return 0
        val id = row.optString("id").takeIf(idPattern::matches) ?: return 0
        val reader = decrypter()
        val clear = runCatching { JSONObject(decryptChatEnvelope(reader, row.getString("envelope"))) }
            .getOrNull()?.takeIf { it.optString("id") == id }
        val body = clear?.optString("text").orEmpty()
        val event = clear?.optJSONObject("event")
        val attachment = ChatAttachment.parse(clear?.optJSONObject("attachment"))
        val photoEvent = event?.optString("type") == "profile_photo"
        if (clear == null || !photoEvent && body.length > 2000 || event == null && body.isBlank() ||
            clear.has("event") && !validEvent(event, body, peer.conversation) ||
            clear.has("attachment") && (attachment?.id != id || event != null)) return -1
        val reference = clear.optJSONObject("reply")?.takeIf {
            idPattern.matches(it.optString("id")) && it.opt("own") is Boolean &&
                it.optString("preview").length <= 120
        }
        val inserted = db.insertWithOnConflict("message", null, ContentValues().apply {
            put("conversation", peer.conversation)
            put("id", id)
            put("body", body)
            put("attachment", attachment?.json()?.toString())
            put("reply", reference?.toString())
            if (event != null) {
                putEvent(event, false)
                put("seen", 1)
            }
            put("outgoing", 0)
            put("time", row.getLong("queued"))
            put("status", 2)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        return if (inserted == -1L) 0 else 1
    }

    private suspend fun failPending(peer: ChatPeer, id: String) = onDatabase {
        database.writableDatabase.execSQL("UPDATE message SET status=-1 WHERE conversation=? AND id=? AND outgoing=1 AND status=0",
            arrayOf(peer.conversation, id))
        publish()
    }

    private suspend fun updateStatus(peer: ChatPeer, id: String, ack: JSONObject) = onDatabase {
        writeStatus(peer, id, ack)
        publish()
    }

    private fun writeStatus(peer: ChatPeer, id: String, ack: JSONObject) {
        if (!ack.isNull("rejected")) {
            database.writableDatabase.execSQL("""UPDATE message SET status=-1
                WHERE conversation=? AND outgoing=1 AND id=? AND status<2""", arrayOf(peer.conversation, id))
            return
        }
        val status = when {
            !ack.isNull("read") -> 3
            !ack.isNull("delivered") -> 2
            else -> 1
        }
        database.writableDatabase.execSQL(
            "UPDATE message SET status=max(status, ?), time=coalesce(?,time) WHERE conversation=? AND outgoing=1 AND id=?",
            arrayOf<Any?>(status, ack.optLong("queued").takeIf { it > 0 }, peer.conversation, id))
    }

    private fun ids(peer: ChatPeer, where: String, order: String = "time, id"): List<String> =
        database.readableDatabase.rawQuery("SELECT id FROM message WHERE conversation=? AND $where ORDER BY $order LIMIT 50",
            arrayOf(peer.conversation)).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    private fun validEvent(event: JSONObject?, body: String, conversation: String): Boolean {
        event ?: return false
        if (!idPattern.matches(event.optString("target")) || event.opt("own") !is Boolean ||
            event.optLong("revision") !in 1..Long.MAX_VALUE) return false
        return when (event.optString("type")) {
            "edit" -> event.optBoolean("own") && body.isNotBlank()
            "delete" -> event.optBoolean("own") && body.isEmpty()
            "react" -> body.isEmpty() || body in chatReactions
            "played" -> !event.optBoolean("own") && body.isEmpty()
            "profile_photo" -> !event.optBoolean("own") && event.optString("target") == conversation && validPhotoChoice(body)
            else -> false
        }
    }

    private fun validPhotoChoice(body: String): Boolean = body.length <= 8000 && body.length % 4 == 0 &&
        body.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' || it == '=' }

    private fun ContentValues.putEvent(event: JSONObject, outgoing: Boolean) {
        put("event", event.getString("type"))
        put("target", event.getString("target"))
        put("target_outgoing", if (event.getBoolean("own") == outgoing) 1 else 0)
        put("revision", event.getLong("revision"))
    }

    private fun publish() {
        mutable.value = active?.let { readMessages(it, limit) }.orEmpty()
        mutablePhotos.value = active?.let { peer ->
            fun choice(outgoing: String): String? = database.readableDatabase.rawQuery("""SELECT body FROM message
                WHERE conversation=? AND event='profile_photo' AND outgoing=? AND status>=0
                ORDER BY revision DESC LIMIT 1""", arrayOf(peer.conversation, outgoing)).use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            PairedPhotoChoices(peer.conversation, choice("1"), choice("0"))
        }
    }

    private fun readMessages(peer: ChatPeer, limit: Int, query: String? = null, shared: SharedChatTab? = null,
        reference: ChatReference? = null): List<PairedMessage> {
        val actions = """SELECT e.body FROM message e WHERE e.conversation=m.conversation AND e.target=m.id
            AND e.target_outgoing=m.outgoing AND e.status>=0"""
        val edit = "($actions AND e.event='edit' AND e.outgoing=m.outgoing ORDER BY e.revision DESC LIMIT 1)"
        val deleted = "EXISTS ($actions AND e.event='delete' AND e.outgoing=m.outgoing)"
        val selfReaction = "($actions AND e.event='react' AND e.outgoing=1 ORDER BY e.revision DESC LIMIT 1)"
        val peerReaction = "($actions AND e.event='react' AND e.outgoing=0 ORDER BY e.revision DESC LIMIT 1)"
        val played = "EXISTS ($actions AND e.event='played' AND e.outgoing<>m.outgoing)"
        val search = if (query == null) "" else "AND NOT ($deleted) AND instr(lower(coalesce($edit, m.body)), lower(?))>0"
        val category = when (shared) {
            SharedChatTab.Media -> "m.attachment IS NOT NULL AND (json_extract(m.attachment,'$.mime') LIKE 'image/%' OR json_extract(m.attachment,'$.mime') LIKE 'video/%')"
            SharedChatTab.Voice -> "json_extract(m.attachment,'$.voice')=1"
            SharedChatTab.Files -> "m.attachment IS NOT NULL AND json_extract(m.attachment,'$.voice')=0 AND json_extract(m.attachment,'$.mime') NOT LIKE 'image/%' AND json_extract(m.attachment,'$.mime') NOT LIKE 'video/%'"
            SharedChatTab.Links -> "m.attachment IS NULL AND (coalesce($edit,m.body) LIKE '%https://%' OR coalesce($edit,m.body) LIKE '%http://%')"
            null -> ""
        }.let { if (it.isEmpty()) "" else "AND NOT ($deleted) AND ($it)" }
        val target = if (reference == null) "" else "AND m.id=? AND m.outgoing=?"
        val args = buildList {
            add(peer.conversation)
            query?.let(::add)
            if (reference != null) {
                add(reference.id)
                add(if (reference.outgoing) "1" else "0")
            }
            add(limit.toString())
        }.toTypedArray()
        return database.readableDatabase.rawQuery("""SELECT m.id,m.body,m.outgoing,m.time,m.status,m.attachment,m.reply,
            $edit,$deleted,$selfReaction,$peerReaction,m.seen,$played FROM message m WHERE m.conversation=? AND m.hidden=0
            AND m.event IS NULL $search $category $target ORDER BY m.time DESC,m.rowid DESC LIMIT ?""", args).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val outgoing = cursor.getInt(2) == 1
                    val deletedMessage = cursor.getInt(8) == 1
                    val revised = cursor.getString(7)
                    val own = cursor.getString(9)?.takeIf(String::isNotEmpty)
                    val partner = cursor.getString(10)?.takeIf(String::isNotEmpty)
                    val reply = cursor.getString(6)?.let(::JSONObject)?.let {
                        ChatReference(it.getString("id"), it.getBoolean("own") == outgoing, it.getString("preview"))
                    }
                    add(PairedMessage(cursor.getString(0), if (deletedMessage) "Message deleted" else revised ?: cursor.getString(1),
                        outgoing, cursor.getLong(3), cursor.getInt(4),
                        if (deletedMessage) null else cursor.getString(5)?.let { ChatAttachment.parse(JSONObject(it)) },
                        reply.takeUnless { deletedMessage }, revised != null && !deletedMessage, deletedMessage,
                        if (deletedMessage) emptyList() else listOfNotNull(own, partner), own, cursor.getInt(11) == 1, played = cursor.getInt(12) == 1))
                }
            }.asReversed()
        }
    }

    private suspend fun <T> onDatabase(block: () -> T): T = withContext(Dispatchers.IO) { lock.withLock { block() } }

    // RFC 5869 HKDF-Expand with the uniformly random pairing secret as PRK.
    // Keystore performs HMAC without exporting the existing pairing key.
    private fun key(peer: ChatPeer, sender: String, scope: String = "chat"): ByteArray {
        deriveKey?.let { return it(peer, sender, scope) }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val pairing = store.getKey("saoreo-call-identity-v1:pairing-key", null) as? SecretKey
            ?: throw java.io.IOException("Secure pairing key unavailable")
        return Mac.getInstance("HmacSHA256").run {
            init(pairing)
            update("SAOREO $scope JWE v1|${peer.conversation}|$sender".encodeToByteArray())
            doFinal(byteArrayOf(1))
        }
    }
}

internal class ChatHttpError(val code: Int) : java.io.IOException("Chat request returned HTTP $code")


internal fun encryptChatEnvelope(key: ByteArray, clear: String): String =
    JWEObject(JWEHeader(JWEAlgorithm.DIR, EncryptionMethod.A256GCM), Payload(clear)).apply {
        encrypt(DirectEncrypter(key))
    }.serialize()

internal fun decryptChatEnvelope(decrypter: DirectDecrypter, envelope: String): String =
    JWEObject.parse(envelope).run {
        require(header.algorithm == JWEAlgorithm.DIR && header.encryptionMethod == EncryptionMethod.A256GCM)
        decrypt(decrypter)
        payload.toString()
    }

internal fun PairedMessage.previewLabel(): String {
    val media = attachment ?: return body
    return when {
        media.voice -> "Voice note"
        media.mime.startsWith("image/") -> "Photo"
        media.mime.startsWith("video/") -> "Video"
        else -> "Attachment"
    }
}
