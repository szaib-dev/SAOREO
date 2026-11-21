package com.codetic.saoreo

import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.BufferedInputStream
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import android.os.SystemClock
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

object PublicDns : Dns {
    private fun public(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address.map { it.toInt() and 255 }
        if (address is Inet6Address) {
            // Limit to global unicast and exclude IETF special-purpose and transition ranges.
            return bytes[0] in 32..63 && !(bytes[0] == 32 && (bytes[1] == 1 || bytes[1] == 2)) &&
                !(bytes[0] == 63 && bytes[1] == 255)
        }
        val (a, b, c) = bytes
        return a in 1..223 && a != 127 && !(a == 100 && b in 64..127) &&
            !(a == 192 && (b == 0 || b == 88 && c == 99)) &&
            !(a == 198 && (b in 18..19 || b == 51 && c == 100)) &&
            !(a == 203 && b == 0 && c == 113)
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val lower = hostname.lowercase().trimEnd('.')
        if (!lower.contains('.') || lower.endsWith(".local") || lower.endsWith(".localhost") ||
            lower.endsWith(".internal") || !lower.matches(Regex("[a-z0-9.-]+")) ||
            lower.substringAfterLast('.').all(Char::isDigit)) throw java.net.UnknownHostException("Blocked host")
        val results = Dns.SYSTEM.lookup(lower)
        if (results.isEmpty() || results.any { !public(it) }) {
            throw java.net.UnknownHostException("Blocked destination")
        }
        return results
    }
}

val mediaHttp: OkHttpClient = OkHttpClient.Builder()
    .dns(PublicDns)
    .proxy(Proxy.NO_PROXY)
    .followRedirects(false)
    .addInterceptor { chain ->
        var request = chain.request()
        for (redirects in 0..5) {
            val url = request.url
            if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty()) {
                throw IOException("Blocked redirect destination")
            }
            val response = chain.proceed(request)
            val location = response.header("Location")
            if (response.code !in setOf(301, 302, 303, 307, 308) || location == null) return@addInterceptor response
            response.close()
            val redirect = url.resolve(location) ?: throw IOException("Invalid redirect destination")
            request = request.newBuilder().url(redirect).build()
        }
        throw IOException("Too many redirects")
    }
    .connectTimeout(12, TimeUnit.SECONDS)
    .readTimeout(25, TimeUnit.SECONDS)
    .build()

/**
 * HTTPS-only tunnels for the bundled downloader. Resolve and connect to the same public IP.
 * This guards proxy-aware requests. Native code running under the app UID is a separate trust risk.
 */
internal class MediaProxy(
    private val trace: YoutubeTrace? = null,
    private val allowedOrigin: ((String) -> Boolean)? = null,
) : AutoCloseable {
    private val server = ServerSocket(0, 24, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newFixedThreadPool(32)
    private val slots = Semaphore(16)
    private val active = ConcurrentHashMap.newKeySet<Socket>()
    val url = "http://127.0.0.1:${server.localPort}"

    init {
        thread(isDaemon = true, name = "media-proxy") {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                if (!slots.tryAcquire()) socket.close()
                else {
                    active.add(socket)
                    try {
                        workers.execute {
                            try { socket.use(::tunnel) } catch (_: IOException) {
                                // Downloader reports connection failures and retries.
                            } finally { active.remove(socket); slots.release() }
                        }
                    } catch (_: RejectedExecutionException) {
                        socket.close()
                        active.remove(socket)
                        slots.release()
                    }
                }
            }
        }
    }

    private fun tunnel(client: Socket) {
        client.soTimeout = 15_000
        val input = BufferedInputStream(client.getInputStream())
        val request = readLine(input)?.split(' ') ?: return
        val target = request.getOrNull(1)?.split(':') ?: return
        if (request.firstOrNull() != "CONNECT" || target.size != 2 || target[1] != "443") {
            reply(client, "403 Forbidden")
            return
        }
        if (allowedOrigin?.invoke(target[0].lowercase(java.util.Locale.ROOT)) == false) {
            reply(client, "403 Forbidden")
            return
        }
        var remaining = 16_384
        do {
            val line = readLine(input) ?: return
            remaining -= line.length + 2
            if (remaining <= 0) return
        } while (line.isNotEmpty())
        val category = when {
            target[0].endsWith(".youtube.com") || target[0] == "youtube.com" -> "youtube"
            target[0].endsWith(".googlevideo.com") -> "googlevideo"
            target[0] == "youtubei.googleapis.com" -> "player_api"
            else -> "other"
        }
        val dnsStart = SystemClock.elapsedRealtimeNanos()
        val addresses = try {
            PublicDns.lookup(target[0]).also {
                trace?.add("proxy.dns.$category.ok", SystemClock.elapsedRealtimeNanos() - dnsStart)
            }
        } catch (_: IOException) {
            trace?.add("proxy.dns.$category.error", SystemClock.elapsedRealtimeNanos() - dnsStart)
            reply(client, "403 Forbidden")
            return
        }
        var connected: Socket? = null
        for (address in addresses.take(4)) {
            val candidate = Socket()
            active.add(candidate)
            val connectStart = SystemClock.elapsedRealtimeNanos()
            try {
                candidate.connect(InetSocketAddress(address, 443), 5_000)
                trace?.add("proxy.tcp.$category.ok", SystemClock.elapsedRealtimeNanos() - connectStart)
                connected = candidate
                break
            } catch (_: IOException) {
                trace?.add("proxy.tcp.$category.error", SystemClock.elapsedRealtimeNanos() - connectStart)
                active.remove(candidate)
                candidate.close()
            }
        }
        val upstream = connected ?: run {
            reply(client, "502 Bad Gateway")
            return
        }
        try { upstream.use {
            client.soTimeout = 120_000
            upstream.soTimeout = 120_000
            reply(client, "200 Connection Established")
            workers.execute {
                runCatching { input.copyTo(upstream.getOutputStream()) }
                runCatching { upstream.shutdownOutput() }
            }
            upstream.getInputStream().copyTo(client.getOutputStream())
        } } finally { active.remove(upstream) }
    }

    override fun close() {
        server.close()
        workers.shutdown()
        active.forEach { runCatching { it.close() } }
    }

    private fun reply(socket: Socket, status: String) {
        socket.getOutputStream().write("HTTP/1.1 $status\r\n\r\n".toByteArray(Charsets.US_ASCII))
    }

    private fun readLine(input: BufferedInputStream): String? {
        val result = StringBuilder()
        repeat(4096) {
            val byte = input.read()
            if (byte < 0) return null
            if (byte == 10) return result.toString().trimEnd('\r')
            result.append(byte.toChar())
        }
        throw IOException("Proxy request too long")
    }
}
