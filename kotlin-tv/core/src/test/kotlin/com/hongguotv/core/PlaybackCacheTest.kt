package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class PlaybackCacheTest {
    @Test fun cacheSizeSettingRoundTripsAndNormalizesUnsupportedValues() {
        val source = BackupData(emptyList(), emptyList(), emptySet(), emptyList(), BackupSettings(playbackCacheMb = 512))
        val decoded = LibraryBackup.decode(LibraryBackup.encode(source))
        assertEquals(512L, decoded.settings.playbackCacheMb)
        assertEquals(256L, BackupSettings().playbackCacheMb)
        assertEquals(64L, PlaybackCacheSize.normalize(1))
        assertEquals(1024L, PlaybackCacheSize.normalize(9_999))
    }

    @Test fun rangeCacheReusesBytesBoundsDiskAndSupportsManagement() {
        val directory = Files.createTempDirectory("hongguotv-playback-cache").toFile()
        try {
            val cache = PlaybackCache(directory, 16)
            val first = ByteArray(8) { it.toByte() }
            val second = ByteArray(8) { (it + 20).toByte() }

            cache.write("episode:720", 0, first)
            cache.write("episode:720", 8, second)
            assertArrayEquals(first + second, cache.read("episode:720", 0, 16))
            assertEquals(16L, cache.stats().bytes)
            assertEquals(16L, cache.contiguousEnd("episode:720"))

            cache.setMaxBytes(8)
            assertTrue(cache.stats().bytes <= 8)
            assertNull(cache.read("episode:720", 0, 16))

            cache.clear()
            assertEquals(0L, cache.stats().bytes)
            assertNull(cache.read("episode:720", 0, 8))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun overlappingRangesOnlyServeCompleteRequests() {
        val directory = Files.createTempDirectory("hongguotv-playback-cache").toFile()
        try {
            val cache = PlaybackCache(directory, 128)
            cache.write("episode:1080", 10, ByteArray(10) { (it + 10).toByte() })
            assertNull(cache.read("episode:1080", 0, 5))
            assertArrayEquals(ByteArray(5) { (it + 15).toByte() }, cache.read("episode:1080", 15, 5))
            assertEquals(0L, cache.contiguousEnd("episode:1080"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
