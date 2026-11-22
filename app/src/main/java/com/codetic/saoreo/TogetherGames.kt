package com.codetic.saoreo

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

internal enum class TogetherGame(
    val wire: String, val title: String, val description: String, val rules: String,
) {
    POOL("pool", "8-Ball Pool", "Read the table. Run your rack.",
        "Private 8-ball rules. A legal break pockets a ball or sends four object balls to rails. " +
            "The table stays open after the break. The first legal pocket assigns solids or stripes. " +
            "After first contact, pocket a ball or reach a rail. A foul gives your partner ball in hand. " +
            "The eight is re-spotted on the break. Clear your group before the eight. Early eight or a foul on the eight loses."),
    GOLF("golf", "Mini Golf", "Three handcrafted courses, one shared leaderboard.",
        "Alternate strokes with a separate ball each. The hole ends when both putt out or each reaches eight strokes. " +
            "Lowest total across three holes wins. Equal totals are a draw. A timeout costs one stroke."),
    ARCHERY("archery", "Archery Duel", "Find the center as the wind shifts.",
        "Shoot five arrows each in three rounds. Distance and wind increase as the target shrinks. " +
            "The inner ring is worth ten points. Equal totals go to one-arrow shoot-offs."),
    TANK("tank", "Tank Duel", "Arc a shot across a shifting battlefield.",
        "Alternate shots. Direct hits deal the most damage and blasts deform the hills. " +
            "Heavy and scatter shells have two uses each. Reduce your opponent’s tank health to zero."),
    SOCCER("soccer", "Car Soccer", "Two drivers, one ball, no turns.",
        "Steer, accelerate, reverse and boost in real time. Aim for your partner’s goal. " +
            "Most goals after three minutes wins. A tie enters thirty seconds of golden-goal overtime."),
    ;

    val realTime get() = this == SOCCER
}

internal class TogetherGames(private val context: Context, private val send: (String, JSONObject) -> Boolean) {
    private val mutable = MutableStateFlow(JSONObject())
    val state = mutable.asStateFlow()
    private val availability = MutableStateFlow(false)
    val available = availability.asStateFlow()
    private val failure = MutableStateFlow<String?>(null)
    val error = failure.asStateFlow()
    private val preview = MutableStateFlow<JSONObject?>(null)
    val remoteAim = preview.asStateFlow()
    private var seq = 0L
    private var roomTicket: JSONObject? = null
    private var endpoint: String? = null

    fun attached(base: String, ticket: JSONObject) {
        endpoint = base
        roomTicket = ticket
        availability.value = false
        mutable.value = JSONObject()
        preview.value = null
        failure.value = null
    }

    fun closed() {
        availability.value = false
        mutable.value = JSONObject()
        preview.value = null
        failure.value = null
        roomTicket = null
        endpoint = null
        prefs(context).edit().remove(RESTORE).apply()
    }

    fun receive(type: String, event: JSONObject, member: String) {
        when (type) {
            "gamesAvailable" -> availability.value = event.optInt("version") == 1
            "gameError" -> failure.value = event.optString("message", "Game action failed")
            "gameAim" -> {
                val current = mutable.value
                if (event.optString("id") == current.optString("id") &&
                    event.optInt("turnNumber") == current.optInt("turnNumber") && event.optString("sender") != member) {
                    preview.value = event.optJSONObject("aim")
                }
            }
            "gameClosed" -> if (event.optString("id") == mutable.value.optString("id")) {
                mutable.value = JSONObject()
                preview.value = null
                prefs(context).edit().remove(RESTORE).apply()
            }
            "gameState" -> {
                val current = mutable.value
                val same = current.optString("id") == event.optString("id")
                if (same && current.optLong("revision") >= event.optLong("revision")) return
                if (!same || current.optInt("turnNumber") != event.optInt("turnNumber") ||
                    current.optString("phase") != event.optString("phase")) preview.value = null
                seq = maxOf(seq, event.optJSONObject("ack")?.optLong(member) ?: 0)
                mutable.value = event
                if (!same || current.optString("phase") != event.optString("phase")) {
                    val phase = event.optString("phase")
                    val ticket = roomTicket
                    val base = endpoint
                    if (phase in setOf("inviting", "lobby", "active", "resolving", "paused") && ticket != null && base != null) {
                        prefs(context).edit().putString(RESTORE, JSONObject().put("server", base)
                            .put("ticket", ticket).put("saved", System.currentTimeMillis()).toString()).apply()
                    } else prefs(context).edit().remove(RESTORE).apply()
                }
            }
        }
    }

    fun command(action: String, fields: JSONObject = JSONObject()): Boolean {
        if (!availability.value) return false
        val current = mutable.value
        fields.put("action", action).put("gameSeq", ++seq).put("turnNumber", current.optInt("turnNumber"))
        current.optString("id").takeIf(String::isNotBlank)?.let { fields.put("id", it) }
        return send("game", fields)
    }

    fun dismissError() { failure.value = null }

    companion object {
        private const val RESTORE = "active-room"
        private fun prefs(context: Context) = context.getSharedPreferences("together-games", Context.MODE_PRIVATE)
        fun restore(context: Context): JSONObject? = runCatching {
            val stored = prefs(context).getString(RESTORE, null)?.let(::JSONObject) ?: return null
            stored.takeIf { System.currentTimeMillis() - it.optLong("saved") in 0..(30 * 60_000) }
        }.getOrNull()

        fun roomIntent(context: Context): Intent = Intent(context, TogetherActivity::class.java).apply {
            if (restore(context) != null) addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
    }
}
