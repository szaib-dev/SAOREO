package com.codetic.saoreo

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal const val EXTRA_OPEN_PAIRED_CHAT = "openPairedChat"

private const val MESSAGE_CHANNEL = "paired-chat-v1"
private const val REPLY = "com.codetic.saoreo.chat.REPLY"
private const val READ = "com.codetic.saoreo.chat.READ"
private const val MUTE = "com.codetic.saoreo.chat.MUTE"
private const val REPLY_TEXT = "reply"
private const val MESSAGE_NOTIFICATION = 71
internal const val TOGETHER_NOTIFY_LIME = 0xffc9ee65.toInt()

internal object PairedChatNotifications {
    private fun prefs(context: Context) = context.getSharedPreferences("chat-notifications", Context.MODE_PRIVATE)
    fun muted(context: Context, conversation: String) = prefs(context).getBoolean(conversation, false)
    fun mute(context: Context, conversation: String, value: Boolean) {
        prefs(context).edit().putBoolean(conversation, value).apply()
    }

    fun partner(context: Context, name: String, peer: String? = null): Person {
        val person = Person.Builder().setName(name).setImportant(true)
        if (peer != null) person.setKey(peer)
        val app = context.applicationContext as? SaoreoApp
        val photo = app?.pairing?.appearance?.partnerPhoto(app.pairing.conversationId)
        val bitmap = photo?.takeIf { it.isFile }?.let { android.graphics.BitmapFactory.decodeFile(it.path) }
        if (bitmap != null) person.setIcon(android.graphics.drawable.Icon.createWithBitmap(bitmap))
        return person.build()
    }

    fun show(context: Context, peer: ChatPeer, name: String, unread: List<PairedMessage>) {
        if (unread.isEmpty() || muted(context, peer.conversation) ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(MESSAGE_CHANNEL, "Together messages",
            NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 71, Intent(context, TogetherActivity::class.java)
            .putExtra(EXTRA_OPEN_PAIRED_CHAT, true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun action(id: Int, name: String, mutable: Boolean = false): PendingIntent {
            val intent = Intent(context, PairedChatActionReceiver::class.java).setAction(name)
                .putExtra("conversation", peer.conversation)
                .putStringArrayListExtra("ids", ArrayList(unread.map { it.id }))
            val flags = if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getBroadcast(context, id, intent, flags or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val style = Notification.MessagingStyle(Person.Builder().setName("You").build())
        val partner = partner(context, name, peer.partner)
        for (message in unread) style.addMessage(message.previewLabel(), message.time, partner)
        val reply = Notification.Action.Builder(null, "Reply", action(72, REPLY, true))
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
            .addRemoteInput(RemoteInput.Builder(REPLY_TEXT).setLabel("Reply").build()).build()
        val read = Notification.Action.Builder(null, "Mark as read", action(73, READ))
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ).build()
        val mute = Notification.Action.Builder(null, "Mute", action(74, MUTE))
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_MUTE).build()
        val publicVersion = Notification.Builder(context, MESSAGE_CHANNEL).setSmallIcon(R.drawable.ic_chat)
            .setContentTitle("New Together message").build()
        manager.notify(MESSAGE_NOTIFICATION, Notification.Builder(context, MESSAGE_CHANNEL)
            .setSmallIcon(R.drawable.ic_chat).setColor(TOGETHER_NOTIFY_LIME).setStyle(style)
            .setCategory(Notification.CATEGORY_MESSAGE).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion).setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true)
            .addAction(reply).addAction(read).addAction(mute).build())
    }

    fun clear(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(MESSAGE_NOTIFICATION)

    fun missed(context: Context, call: PairedCallEntry, name: String): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ||
            !manager.areNotificationsEnabled()) return false
        val legacy = prefs(context).getString("missedIds", "").orEmpty().split(',')
        if (call.id in legacy) return true
        val channel = "together-missed-v1"
        manager.createNotificationChannel(NotificationChannel(channel, "Missed Together calls", NotificationManager.IMPORTANCE_DEFAULT))
        if (manager.getNotificationChannel(channel).importance == NotificationManager.IMPORTANCE_NONE) return false
        val open = PendingIntent.getActivity(context, 76, Intent(context, TogetherActivity::class.java)
            .putExtra("openCallHistory", true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val originalTime = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
            .format(java.util.Date(call.time))
        manager.notify(call.id, 76, Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_call_incoming_notification)
            .setColor(TOGETHER_NOTIFY_LIME).setCategory(Notification.CATEGORY_MISSED_CALL)
            .setContentTitle("Missed ${call.kind.title.lowercase()}").setContentText("$name · $originalTime")
            .setWhen(call.time).setShowWhen(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(open).setAutoCancel(true).build())
        return true
    }
}

class PairedChatActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val operation = intent.action
        if (operation !in setOf(REPLY, READ, MUTE)) return
        val conversation = intent.getStringExtra("conversation") ?: return
        val app = context.applicationContext as SaoreoApp
        if (app.pairing.conversationId != conversation) return
        val ids = intent.getStringArrayListExtra("ids").orEmpty()
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REPLY_TEXT)?.toString()?.trim()
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                when (operation) {
                    REPLY -> {
                        if (text.isNullOrEmpty() || !app.pairing.sendMessage(text.take(2000))) return@launch
                        app.pairing.markMessagesRead(ids)
                    }
                    READ -> app.pairing.markMessagesRead(ids)
                    MUTE -> PairedChatNotifications.mute(context, conversation, true)
                }
                PairedChatNotifications.clear(context)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Leave the original notification in place when an action could not be saved.
            } finally {
                pending.finish()
            }
        }
    }
}
