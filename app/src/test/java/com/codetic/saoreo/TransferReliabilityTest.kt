package com.codetic.saoreo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class TransferReliabilityTest {
    @Test fun simultaneousMetadataCallersShareOneFetch() = runBlocking {
        val cache = MetadataCache<String>(this, { 10L })
        val gate = CompletableDeferred<String>()
        var fetchCount = 0
        val first = async { cache.request("video", 15) { fetchCount++; gate.await() } }
        val second = async { cache.request("video", 15) { fetchCount++; "duplicate" } }
        yield()
        gate.complete("original")
        assertEquals("original", first.await())
        assertEquals("original", second.await())
        assertEquals(1, fetchCount)
        assertEquals("original", cache.get("video"))
    }

    @Test fun cancellingOneWaiterKeepsTheOtherFetch() = runBlocking {
        val cache = MetadataCache<String>(this, { 10L })
        val fetching = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<String>()
        val first = async { cache.request("video", 15) { fetching.complete(Unit); gate.await() } }
        fetching.await()
        val second = async { cache.request("video", 15) { "duplicate" } }
        yield()
        first.cancel()
        gate.complete("original")
        assertEquals("original", second.await())
    }

    @Test fun failedFetchIsNotCachedAndExpiryUsesOriginalTime() = runBlocking {
        var time = 100L
        val scope = kotlinx.coroutines.CoroutineScope(coroutineContext + kotlinx.coroutines.SupervisorJob())
        val cache = MetadataCache<String>(scope, { time }, ttl = 5)
        try {
            assertTrue(runCatching { cache.request("ref", 15) { error("failed") } }.isFailure)
            assertNull(cache.get("ref"))
            assertEquals("fresh", cache.request("ref", 15) { "fresh" })
            time += 5
            assertNull(cache.get("ref"))
        } finally { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }

    @Test fun campaignParametersDoNotSplitCacheButUnknownParametersSurvive() {
        val a = MediaLink.parse("https://www.instagram.com/reel/example/?igsh=mystery&utm_source=campaign")!!
        val b = MediaLink.parse("https://www.instagram.com/reel/example/?igsh=mystery")!!
        assertEquals(a.metadataKey(), b.metadataKey())
    }

    @Test fun onlyDeclaredProgressiveCdnStreamsCanBeReused() {
        val samples = mapOf(
            Source.YOUTUBE to "https://rr1.googlevideo.com/videoplayback?expire=123",
            Source.TIKTOK to "https://v16.tiktokcdn.com/example.mp4",
            Source.X to "https://video.twimg.com/example.mp4",
            Source.INSTAGRAM to "https://scontent.cdninstagram.com/example.mp4",
            Source.PINTEREST to "https://v1.pinimg.com/videos/example.mp4",
        )
        fun validate(source: Source, url: String, protocol: String = "https", headers: Map<String, String> = emptyMap(),
                     cookies: Boolean = false, fragments: Boolean = false) =
            reusableStream(source, "18", url, "mp4", protocol, "avc1", "aac", headers, fragments, cookies)

        for ((source, url) in samples) assertNotNull(source.name, validate(source, url))
        val good = samples.getValue(Source.YOUTUBE)
        assertNull(validate(Source.X, good))
        assertNull(validate(Source.YOUTUBE, good.replace("googlevideo.com", "googlevideo.com.example")))
        assertNull(validate(Source.YOUTUBE, good.replace("https://", "http://")))
        assertNull(validate(Source.YOUTUBE, good.replace("https://", "https://user@")))
        assertNull(validate(Source.YOUTUBE, good, "m3u8_native"))
        assertNull(validate(Source.YOUTUBE, good, cookies = true))
        assertNull(validate(Source.YOUTUBE, good, fragments = true))
        assertNull(validate(Source.YOUTUBE, good, headers = mapOf("Authorization" to "secret")))
        assertNull(validate(Source.YOUTUBE, good, headers = mapOf("Referer" to "https://evil.example/")))
        assertNotNull(validate(Source.YOUTUBE, good, headers = mapOf("Referer" to "https://www.youtube.com/")))
    }

    @Test fun rejectsTruncatedMp4BoxesEvenWithContainerHeader() {
        val file = Files.createTempFile("saoreo-truncated", ".mp4").toFile()
        try {
            val index = byteArrayOf(0, 0, 0, 9) + "moov".toByteArray() + byteArrayOf(1)
            val payload = byteArrayOf(0, 0, 0, 10) + "mdat".toByteArray() + byteArrayOf(1, 2)
            file.writeBytes(index + payload)
            assertTrue(hasCompleteMp4Boxes(file))
            file.writeBytes(index + payload.dropLast(1).toByteArray())
            assertFalse(hasCompleteMp4Boxes(file))
        } finally { file.delete() }
    }

    @Test fun rejectsDocumentsRegardlessOfMp4ExtensionOrMissingHeader() {
        val file = Files.createTempFile("saoreo-integrity", ".mp4").toFile()
        try {
            for (document in listOf("\uFEFF \n<!doctype html><h1>Blocked</h1>", "{\"error\":\"blocked\"}", "#EXTM3U\n")) {
                file.writeText(document)
                assertTrue(beginsWithErrorDocument(file))
            }
            file.writeBytes(byteArrayOf(0, 0, 0, 20) + "ftypisom".toByteArray())
            assertFalse(beginsWithErrorDocument(file))
            assertTrue(isDocumentContentType("text/html; charset=UTF-8"))
            assertTrue(isDocumentContentType("application/problem+json"))
            assertFalse(isDocumentContentType("application/octet-stream"))
        } finally { file.delete() }
    }
}
