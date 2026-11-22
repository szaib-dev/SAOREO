package com.codetic.saoreo

import org.junit.Assert.*
import org.junit.Test

class TogetherPlaybackDriverTest {
    @Test fun youtubeBufferingNeverSendsTheRoomIntoThePauseDeadlock() {
        var room = TogetherState(connected = true, wanted = true)
        val statuses = mutableListOf<Pair<Boolean, Boolean>>()
        val driver = TogetherPlayback({ room }, { 0 }, { ready, buffering -> statuses.add(ready to buffering); true }, { 0 })
        driver.ready = true
        driver.youtube = true
        driver.report()
        driver.buffering = true
        driver.report()
        driver.buffering = false
        driver.report()
        assertEquals(listOf(true to false), statuses)
        driver.youtube = false
        driver.buffering = true
        driver.report()
        assertEquals(true to true, statuses.last())
        room = room.copy(wanted = false)
        driver.report()
        assertEquals(true to false, statuses.last())
    }

    @Test fun aRejectedPlayCommandIsRetriedWithoutReloadingAnAlreadyPlayingVideo() {
        val room = TogetherState(connected = true, timeline = SharedTimeline(0, 0, true))
        var clock = 10000L
        val commands = mutableListOf<Boolean>()
        val driver = TogetherPlayback({ room }, { 0 }, { _, _ -> true }, { clock })
        driver.ready = true
        driver.play = { commands.add(it) }
        driver.apply(room)
        clock += 1200
        driver.apply(room)
        assertEquals(listOf(true), commands)
        clock += 1500
        driver.apply(room)
        assertEquals(listOf(true, true), commands)
        driver.actualPlaying = true
        clock += 5000
        driver.apply(room)
        assertEquals(2, commands.size)
        driver.apply(room.copy(timeline = SharedTimeline(0, 0, false)))
        assertEquals(false, commands.last())
    }

    @Test fun reconnectResendsReadinessAndBufferingIsNotRetriedAsANewPlay() {
        var room = TogetherState(connected = true)
        val statuses = mutableListOf<Pair<Boolean, Boolean>>()
        val driver = TogetherPlayback({ room }, { 0 }, { a, b -> statuses.add(a to b); true }, { 10000 })
        driver.ready = true
        driver.report()
        room = room.copy(connected = false)
        driver.report()
        room = room.copy(connected = true)
        driver.report()
        assertEquals(2, statuses.size)
        assertFalse(retryTogetherPlay(true, false, true, 10000))
    }

    @Test fun aSharedReplaySeeksAnEndedClipEvenBelowTheDriftThreshold() {
        val room = TogetherState(connected = true, timeline = SharedTimeline(0, 0, true))
        val driver = TogetherPlayback({ room }, { 0 }, { _, _ -> true }, { 100 })
        val commands = mutableListOf<String>()
        driver.ready = true
        driver.ended = true
        driver.position = 500
        driver.duration = 500
        driver.seek = { commands += "seek $it" }
        driver.play = { commands += "play $it" }

        driver.apply(room)

        assertEquals(listOf("seek 0", "play true"), commands)
    }
}
