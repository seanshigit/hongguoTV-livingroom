// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

class RemoteVideoResourceTest {
    // These real AVC MP4s have identical sizes and moov indexes, but different
    // video samples. A legal trailing free box exercises reads beyond warm-up.
    private fun video(name: String): ByteArray {
        val mp4 = requireNotNull(javaClass.getResourceAsStream("/media/$name.mp4")).use { it.readBytes() }
        val padding = ByteArray(512 * 1024) { (it % 239).toByte() }
        return mp4 + be32(padding.size + 8) + "free".toByteArray() + padding
    }

    private fun client(bytes: (Request) -> ByteArray, fail: (Request, Int) -> Boolean = { _, _ -> false },
                       reads: MutableList<String> = mutableListOf()): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val range = requireNotNull(request.header("Range")).removePrefix("bytes=").split('-').map(String::toInt)
            reads += "${request.url.encodedPath}:${range[0]}"
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("test")
            if (fail(request, range[0])) response.code(502).body(byteArrayOf().toResponseBody()).build()
            else {
                val data = bytes(request)
                val end = minOf(range[1], data.lastIndex)
                response.code(206).header("Content-Range", "bytes ${range[0]}-$end/${data.size}")
                    .body(data.copyOfRange(range[0], end + 1).toResponseBody()).build()
            }
        }.build()

    @Test fun sameEpisodeAndQualityDoNotReuseAnotherMp4sCachedBytes() {
        val red = video("red")
        val blue = video("blue")
        assertEquals(red.size, blue.size)
        val headerEnd = MediaCrypto.headerEnd(red, red.size.toLong())
        assertArrayEquals(red.copyOf(headerEnd), blue.copyOf(headerEnd))
        assertFalse(red.contentEquals(blue))
        val directory = Files.createTempDirectory("hongguotv-resource-cache").toFile()
        try {
            val cache = PlaybackCache(directory)
            val http = client({ if (it.url.encodedPath == "/red") red else blue })
            RemoteVideo(http, StreamInfo("https://example.test/red", null, "720P"), cache, "episode:720").use {
                it.prepare()
                assertArrayEquals(red.copyOf(4096), it.read(0, 4096))
            }
            RemoteVideo(http, StreamInfo("https://example.test/blue", null, "720P"), cache, "episode:720").use {
                it.prepare()
                assertArrayEquals(blue.copyOf(4096), it.read(0, 4096))
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun theSameMp4StillReusesItsCacheAfterASignedUrlChanges() {
        val bytes = video("red")
        val reads = mutableListOf<String>()
        val http = client({ bytes }, reads = reads)
        val directory = Files.createTempDirectory("hongguotv-resource-cache").toFile()
        try {
            val cache = PlaybackCache(directory)
            for (token in listOf("old", "new")) {
                RemoteVideo(http, StreamInfo("https://example.test/video?token=$token", null, "720P"), cache, "episode:720").use {
                    it.prepare()
                    assertArrayEquals(bytes.copyOfRange(300000, 300128), it.read(300000, 128))
                }
            }
            assertEquals(1, reads.count { it == "/video:300000" })
        } finally { directory.deleteRecursively() }
    }

    @Test fun prefetchProgressAndLegacyCacheCannotCrossResourceBoundaries() {
        val red = video("red")
        val blue = video("blue")
        val http = client({ if (it.url.encodedPath == "/red") red else blue })
        val directory = Files.createTempDirectory("hongguotv-resource-cache").toFile()
        try {
            val cache = PlaybackCache(directory)
            cache.write("episode:720", 0, ByteArray(4096) { 77 })
            RemoteVideo(http, StreamInfo("https://example.test/red", null, "720P"), cache, "episode:720").use {
                it.prepare()
                assertEquals(0L, it.cachedPrefixEnd())
                assertArrayEquals(red.copyOf(4096), it.read(0, 4096))
                assertEquals(4096L, it.cachedPrefixEnd())
            }
            RemoteVideo(http, StreamInfo("https://example.test/blue", null, "720P"), cache, "episode:720").use {
                it.prepare()
                assertEquals(0L, it.cachedPrefixEnd())
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun aDifferentMp4CannotReplaceTheResourceAtAnExistingByteOffset() {
        val red = video("red")
        val blue = video("blue")
        val http = client({ if (it.url.encodedPath == "/primary") red else blue },
            fail = { request, start -> request.url.encodedPath == "/primary" && start > 0 })
        val stream = StreamInfo("https://example.test/primary", null, "720P",
            listOf(StreamCandidate("https://example.test/different", null, "720P")))
        RemoteVideo(http, stream).use {
            it.prepare()
            assertArrayEquals(red.copyOf(4096), it.read(0, 4096))
            assertThrows(IOException::class.java) { it.read(300000, 128) }
            // Failure cannot leave the replacement prepared and usable on a retry.
            assertThrows(IOException::class.java) { it.read(300000, 128) }
        }
    }

    @Test fun anIdenticalBackupCanRecoverAfterAnIncompatibleVariantIsSkipped() {
        val red = video("red")
        val blue = video("blue")
        val http = client({ if (it.url.encodedPath == "/different") blue else red },
            fail = { request, start -> request.url.encodedPath == "/primary" && start > 0 })
        val stream = StreamInfo("https://example.test/primary", null, "720P", listOf(
            StreamCandidate("https://example.test/different", null, "720P"),
            StreamCandidate("https://example.test/backup", null, "720P")))
        RemoteVideo(http, stream).use {
            it.prepare()
            assertArrayEquals(red.copyOfRange(300000, 300128), it.read(300000, 128))
            assertTrue(it.info.url.endsWith("/backup"))
        }
    }
}
