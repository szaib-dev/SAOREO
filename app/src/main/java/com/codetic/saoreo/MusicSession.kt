package com.codetic.saoreo

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

internal class AudioControl {
    val sleepUntil = MutableStateFlow(0L)
    private val players = mutableMapOf<Player, Player.Listener>()

    fun register(player: Player) {
        if (player in players) return
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady) players.keys.toList().filter { it !== player }.forEach { it.pause() }
            }
        }
        players[player] = listener
        player.addListener(listener)
    }

    fun unregister(player: Player) {
        players.remove(player)?.let(player::removeListener)
    }
}

internal fun localAudioItem(uri: String, title: String, artist: String? = null, album: String? = null) =
    MediaItem.Builder().setUri(uri).setMediaId(uri).setMediaMetadata(
        MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album).setIsPlayable(true).build(),
    ).build()

internal class SavedMusicQueue(context: Context, name: String = "music_session") {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    fun save(player: Player) {
        if (player.mediaItemCount == 0) {
            prefs.edit().remove("queue").apply()
            return
        }
        val items = JSONArray()
        for (index in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(index)
            val uri = item.mediaId
            if (Uri.parse(uri).scheme != "content") continue
            items.put(JSONObject().put("uri", uri).put("title", item.mediaMetadata.title?.toString())
                .put("artist", item.mediaMetadata.artist?.toString()).put("album", item.mediaMetadata.albumTitle?.toString()))
        }
        prefs.edit().putString("queue", items.toString()).putInt("index", player.currentMediaItemIndex)
            .putLong("position", player.currentPosition.coerceAtLeast(0)).putInt("repeat", player.repeatMode)
            .putBoolean("shuffle", player.shuffleModeEnabled).putFloat("speed", player.playbackParameters.speed).apply()
    }

    fun restore(player: Player) {
        val queue = runCatching { JSONArray(prefs.getString("queue", "[]")) }.getOrNull() ?: return
        val items = (0 until queue.length()).mapNotNull { index ->
            val item = queue.optJSONObject(index) ?: return@mapNotNull null
            val uri = item.optString("uri")
            if (Uri.parse(uri).scheme != "content") return@mapNotNull null
            localAudioItem(uri, item.optString("title", "Audio"), item.optString("artist").ifBlank { null },
                item.optString("album").ifBlank { null })
        }
        if (items.isEmpty()) return
        player.setMediaItems(items, prefs.getInt("index", 0).coerceIn(items.indices), prefs.getLong("position", 0))
        player.repeatMode = prefs.getInt("repeat", Player.REPEAT_MODE_OFF).coerceIn(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL)
        player.shuffleModeEnabled = prefs.getBoolean("shuffle", false)
        player.setPlaybackSpeed(prefs.getFloat("speed", 1f).coerceIn(.5f, 2f))
    }
}
