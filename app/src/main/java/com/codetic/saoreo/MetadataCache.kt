package com.codetic.saoreo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/** Shares a fetch while at least one caller is waiting and caches successful results only. */
internal class MetadataCache<T : Any>(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val ttl: Long = 300_000,
    private val limit: Int = 20,
) {
    private data class PendingKey(val media: String, val timeout: Int, val generation: Long)
    private class Flight<T>(val result: Deferred<T>, var waiters: Int = 0)

    private val lock = Any()
    private val completed = LinkedHashMap<String, Pair<Long, T>>(16, 0.75f, true)
    private val pending = mutableMapOf<PendingKey, Flight<T>>()
    private var generation = 0L

    fun get(media: String): T? = synchronized(lock) {
        val entry = completed[media] ?: return@synchronized null
        if (now() - entry.first < ttl) entry.second else {
            completed.remove(media)
            null
        }
    }

    fun clear() = synchronized(lock) {
        completed.clear()
        generation++
    }

    suspend fun request(media: String, timeout: Int, fetch: suspend () -> T): T {
        val (key, flight) = synchronized(lock) {
            get(media)?.let { return it }
            val key = PendingKey(media, timeout, generation)
            val flight = pending.getOrPut(key) {
                lateinit var started: Flight<T>
                started = Flight(scope.async(start = CoroutineStart.LAZY) {
                    val item = fetch()
                    synchronized(lock) {
                        if (key.generation == generation && pending[key] === started) {
                            completed[media] = now() to item
                            if (completed.size > limit) completed.remove(completed.keys.first())
                        }
                    }
                    item
                })
                started
            }
            flight.waiters++
            key to flight
        }
        return try {
            flight.result.await()
        } finally {
            synchronized(lock) {
                if (--flight.waiters == 0) {
                    pending.remove(key)
                    flight.result.cancel()
                }
            }
        }
    }
}
