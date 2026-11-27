package com.codetic.saoreo

internal enum class CallRecoveryAction { STOP, CONNECTED, WAIT, RESTART }

internal object CallRecoveryPolicy {
    private val backoff = longArrayOf(3500, 5500, 8000, 12000, 15000)
    val lastBackoffIndex get() = backoff.lastIndex

    fun delay(attempt: Int): Long = backoff[attempt.coerceAtMost(lastBackoffIndex)]

    fun action(active: Boolean, mediaHealthy: Boolean, signalingReady: Boolean) = when {
        !active -> CallRecoveryAction.STOP
        mediaHealthy -> CallRecoveryAction.CONNECTED
        !signalingReady -> CallRecoveryAction.WAIT
        else -> CallRecoveryAction.RESTART
    }
}
