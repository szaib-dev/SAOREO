package com.codetic.saoreo

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.io.InputStream
import java.net.URI
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.core5.util.Timeout
import java.util.concurrent.TimeUnit

internal fun iptvUrl(value: String): URI? {
    if (value.length > 4096) return null
    val scheme = value.substringBefore(':').lowercase()
    if (scheme !in setOf("https", "http")) return null
    return runCatching { URI(scheme + ":" + value.substringAfter(':')) }.getOrNull()?.takeIf {
        !it.host.isNullOrBlank() && it.userInfo == null && it.fragment == null
    }
}

internal class IptvHttpConsentRequired : IOException()

internal class IptvResponse(
    val uri: URI, val status: Int, val location: String?, val range: String?,
    val length: Long, val body: InputStream?, private val cleanup: () -> Unit,
) : AutoCloseable {
    override fun close() = cleanup()
}

internal object IptvNetwork {
    private val tls = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .readTimeout(25, TimeUnit.SECONDS).build()
    // Android's app-wide cleartext policy stays disabled. Only this IPTV transport
    // can open cleartext sockets, after opt-in on the device making the request.
    private val cleartext by lazy {
        HttpClients.custom().disableRedirectHandling().disableAutomaticRetries().disableCookieManagement()
            .setDefaultRequestConfig(RequestConfig.custom().setConnectTimeout(Timeout.ofSeconds(10))
                .setResponseTimeout(Timeout.ofSeconds(25)).build()).build()
    }

    fun open(start: URI, allowHttp: Boolean, range: String? = null): IptvResponse {
        var address = iptvUrl(start.toString()) ?: throw IOException("Invalid IPTV address.")
        repeat(6) {
            if (address.scheme == "http" && !allowHttp) throw IptvHttpConsentRequired()
            val response = try {
                if (address.scheme == "https") {
                    val request = Request.Builder().url(address.toASCIIString()).apply {
                        range?.let { header("Range", it) }
                    }.build()
                    val reply = tls.newCall(request).execute()
                    IptvResponse(address, reply.code, reply.header("Location"), reply.header("Content-Range"),
                        reply.body?.contentLength() ?: -1, reply.body?.byteStream(), reply::close)
                } else {
                    val request = HttpGet(address).apply { range?.let { setHeader("Range", it) } }
                    val reply = cleartext.executeOpen(null, request, null)
                    try {
                        IptvResponse(address, reply.code, reply.getFirstHeader("Location")?.value,
                            reply.getFirstHeader("Content-Range")?.value, reply.entity?.contentLength ?: -1,
                            reply.entity?.content, reply::close)
                    } catch (failure: Exception) {
                        reply.close()
                        throw failure
                    }
                }
            } catch (_: Exception) {
                throw IOException("Couldn't connect to the IPTV provider.")
            }
            if (response.status !in setOf(301, 302, 303, 307, 308)) return response
            response.use {
                val location = it.location?.takeUnless(String::isBlank) ?: throw IOException("Invalid IPTV redirect.")
                address = runCatching { address.resolve(location).toString() }.getOrNull()?.let(::iptvUrl)
                    ?: throw IOException("Invalid IPTV redirect.")
            }
        }
        throw IOException("Too many IPTV redirects.")
    }
}

@OptIn(UnstableApi::class)
internal class IptvDataSource(private val allowHttp: Boolean) : BaseDataSource(true) {
    private var response: IptvResponse? = null
    private var remaining = C.LENGTH_UNSET.toLong()
    private var started = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        try {
            val address = iptvUrl(dataSpec.uri.toString()) ?: throw IOException("Invalid IPTV stream address.")
            val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) "" else (dataSpec.position + dataSpec.length - 1).toString()
            val range = if (dataSpec.position == 0L && end.isEmpty()) null else "bytes=${dataSpec.position}-$end"
            val reply = IptvNetwork.open(address, allowHttp, range).also { response = it }
            if (reply.status == 416 && reply.range?.substringAfterLast('/')?.toLongOrNull() == dataSpec.position) {
                remaining = 0
            } else {
                if (reply.status !in 200..299 || reply.body == null) throw IOException("IPTV provider returned HTTP ${reply.status}.")
                val skip = if (reply.status == 200) dataSpec.position else 0L
                if (skip > 0) reply.body.skipNBytes(skip)
                remaining = when {
                    dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
                    reply.length >= 0 -> (reply.length - skip).coerceAtLeast(0)
                    else -> C.LENGTH_UNSET.toLong()
                }
            }
            started = true
            transferStarted(dataSpec)
            return remaining
        } catch (failure: Exception) {
            runCatching { close() }
            if (failure is IptvHttpConsentRequired) throw failure
            throw IOException("Couldn't open the IPTV stream.")
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val requested = if (remaining < 0) length else minOf(length.toLong(), remaining).toInt()
        val count = try {
            checkNotNull(response?.body).read(buffer, offset, requested)
        } catch (_: Exception) {
            throw IOException("Couldn't read the IPTV stream.")
        }
        if (count > 0) {
            if (remaining > 0) remaining -= count.toLong()
            bytesTransferred(count)
        }
        return count
    }

    override fun getUri(): Uri? = response?.let { Uri.parse(it.uri.toASCIIString()) }

    override fun close() {
        try {
            runCatching { response?.close() }
        } finally {
            response = null
            if (started) {
                started = false
                transferEnded()
            }
        }
    }
}

// Only the user-selected document may be opened locally. Its variants, keys
// and segments go through the same network checks as remote HLS.
@OptIn(UnstableApi::class)
internal class IptvManifestDataSource(
    context: android.content.Context, private val manifest: String, allowHttp: Boolean,
) : androidx.media3.datasource.DataSource {
    private val local = androidx.media3.datasource.ContentDataSource(context)
    private val remote = IptvDataSource(allowHttp)
    private var active: androidx.media3.datasource.DataSource? = null

    override fun addTransferListener(listener: androidx.media3.datasource.TransferListener) {
        local.addTransferListener(listener)
        remote.addTransferListener(listener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = if (dataSpec.uri.toString() == manifest) local else remote
        active = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(active).read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun close() {
        val source = active
        active = null
        source?.close()
    }
}
