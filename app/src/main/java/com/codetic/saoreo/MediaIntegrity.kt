package com.codetic.saoreo

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale

internal class InvalidMediaException(cause: Throwable? = null) :
    IOException("Invalid completed media", cause)

internal fun isDocumentContentType(raw: String?): Boolean {
    val mime = raw?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: return false
    return mime.startsWith("text/") || mime in setOf("application/json", "application/xml", "application/xhtml+xml") ||
        mime.endsWith("+json") || mime.endsWith("+xml")
}

internal fun beginsWithErrorDocument(file: File): Boolean = file.inputStream().use { it.beginsWithErrorDocument() }

private fun InputStream.beginsWithErrorDocument(): Boolean {
    val prefix = ByteArray(1024)
    val count = readNBytes(prefix, 0, prefix.size)
    if (count == 0) return false
    val text = String(prefix, 0, count, Charsets.UTF_8).trimStart { it.isWhitespace() || it == '\uFEFF' }
        .lowercase(Locale.ROOT)
    // AVIF, WebP, MP4, MKV, MP3 and the other supported media start with binary container data.
    return text.startsWith('<') || text.startsWith('{') || text.startsWith('[') ||
        text.startsWith("#extm3u") || text.startsWith("http/1.") || text.startsWith("http/2 ")
}

internal fun hasCompleteMp4Boxes(file: File, allowFragments: Boolean = true): Boolean = java.io.RandomAccessFile(file, "r").use { input ->
    val length = input.length()
    var offset = 0L
    var foundMedia = false
    var foundIndex = false
    while (offset < length) {
        val remaining = length - offset
        if (remaining < 8) return@use false
        input.seek(offset)
        val declared = input.readInt().toLong() and 0xffff_ffffL
        val type = input.readInt()
        val headerBytes = if (declared == 1L) 16 else 8
        if (remaining < headerBytes) return@use false
        val size = when (declared) {
            0L -> remaining
            1L -> input.readLong()
            else -> declared
        }
        if (size < headerBytes || size > remaining) return@use false
        if (!allowFragments && type == 0x6d6f6f66) return@use false // moof
        if (type == 0x6d646174 && size > headerBytes) foundMedia = true // mdat
        if (type == 0x6d6f6f76 && size > headerBytes) foundIndex = true // moov
        offset += size
    }
    foundMedia && foundIndex
}
