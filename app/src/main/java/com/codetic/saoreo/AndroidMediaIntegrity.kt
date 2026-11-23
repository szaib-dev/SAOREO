package com.codetic.saoreo

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/** Checks for readable samples on required tracks before a network transfer can be published. */
internal fun verifyDownloadedMedia(file: File, mime: String, requireAudio: Boolean) {
    if (!file.isFile || file.length() <= 0 || beginsWithErrorDocument(file)) throw InvalidMediaException()
    if (file.extension.lowercase() in setOf("mp4", "m4a", "mov") && !hasCompleteMp4Boxes(file)) {
        throw InvalidMediaException()
    }
    if (mime.startsWith("image/")) {
        try { validateMedia(file, mime); return }
        catch (error: IOException) { throw InvalidMediaException(error) }
        catch (error: RuntimeException) { throw InvalidMediaException(error) }
    }

    val needed = if (mime.startsWith("audio/")) setOf("audio")
        else if (requireAudio) setOf("audio", "video") else setOf("video")
    val verified = mutableSetOf<String>()
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(file.absolutePath)
        for (index in 0 until extractor.trackCount) {
            val group = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.substringBefore('/')
            if (group !in needed || group in verified) continue
            extractor.selectTrack(index)
            try {
                extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                // An MP4 edit list can leave decoder priming samples before time zero.
                // Look for a readable presentation sample after the preroll.
                for (attempt in 0 until 64) {
                    val sampleSize = extractor.sampleSize
                    if (sampleSize in 1..16L * 1024 * 1024 && sampleSize <= file.length() &&
                        extractor.sampleTime >= 0 &&
                        extractor.readSampleData(ByteBuffer.allocateDirect(sampleSize.toInt()), 0).toLong() == sampleSize) {
                        verified.add(requireNotNull(group))
                        break
                    }
                    if (!extractor.advance()) break
                }
            } finally {
                extractor.unselectTrack(index)
            }
            if (verified.containsAll(needed)) return
        }
    } catch (error: IOException) {
        throw InvalidMediaException(error)
    } catch (error: RuntimeException) {
        throw InvalidMediaException(error)
    } finally {
        extractor.release()
    }
    throw InvalidMediaException()
}
