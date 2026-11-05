// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Small range cache for decrypted playback bytes.
 *
 * Each response is kept as an immutable range file. A request is served only
 * when one complete range contains it, so a missing or partially written file
 * can never be mistaken for valid video data.
 */
class PlaybackCache(directory: File, initialMaxBytes: Long = DEFAULT_MAX_BYTES) {
    companion object {
        const val DEFAULT_MAX_BYTES = 256L * 1024 * 1024
        const val MAX_CONFIGURED_BYTES = 2L * 1024 * 1024 * 1024

        fun megabytes(value: Long): Long = value / (1024 * 1024)
    }

    data class Stats(val files: Int, val bytes: Long, val maxBytes: Long)

    private val root = directory
    private val lock = Any()
    @Volatile private var limit = initialMaxBytes.coerceIn(0, MAX_CONFIGURED_BYTES)

    init {
        synchronized(lock) {
            root.mkdirs()
            trimLocked()
        }
    }

    fun maxBytes(): Long = limit

    fun setMaxBytes(value: Long) {
        synchronized(lock) {
            limit = value.coerceIn(0, MAX_CONFIGURED_BYTES)
            trimLocked()
        }
    }

    fun read(key: String, start: Long, length: Int): ByteArray? {
        require(start >= 0 && length > 0)
        val hash = digest(key)
        synchronized(lock) {
            val requestedEnd = start + length
            if (requestedEnd < start) return null
            val segments = segmentFiles(hash).mapNotNull { file -> parse(file, hash)?.let { it to file } }
            val result = ByteArray(length)
            var cursor = start
            var outputOffset = 0
            val touched = mutableListOf<File>()
            while (cursor < requestedEnd) {
                val containing = segments.filter { (meta, _) -> meta.start <= cursor && meta.end > cursor }
                    .maxByOrNull { it.first.end }
                    ?: return null
                val (meta, file) = containing
                val chunk = minOf(requestedEnd, meta.end) - cursor
                if (chunk <= 0 || chunk > Int.MAX_VALUE || meta.end - meta.start > file.length()) return null
                runCatching {
                    RandomAccessFile(file, "r").use { raf ->
                        raf.seek(cursor - meta.start)
                        raf.readFully(result, outputOffset, chunk.toInt())
                    }
                }.getOrElse { return null }
                cursor += chunk
                outputOffset += chunk.toInt()
                touched += file
            }
            touched.distinct().forEach { it.setLastModified(System.currentTimeMillis()) }
            return result
        }
    }

    fun write(key: String, start: Long, bytes: ByteArray) {
        if (bytes.isEmpty() || start < 0 || start > Long.MAX_VALUE - bytes.size) return
        synchronized(lock) {
            if (limit == 0L) return
            root.mkdirs()
            val hash = digest(key)
            val target = File(root, "$hash-$start-${bytes.size}.seg")
            val temp = File(root, ".${target.name}.${Thread.currentThread().id}.tmp")
            runCatching {
                temp.outputStream().use { it.write(bytes) }
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                target.setLastModified(System.currentTimeMillis())
                trimLocked()
            }.onFailure { temp.delete() }
        }
    }

    /** Largest contiguous cached prefix from byte zero for a stream key. */
    fun contiguousEnd(key: String): Long {
        synchronized(lock) {
            var cursor = 0L
            segmentFiles(digest(key)).mapNotNull { parse(it, digest(key)) }
                .sortedWith(compareBy<Segment> { it.start }.thenByDescending { it.end })
                .forEach { segment ->
                    if (segment.start > cursor) return cursor
                    if (segment.end > cursor) cursor = segment.end
                }
            return cursor
        }
    }

    fun stats(): Stats = synchronized(lock) {
        val files = segmentFiles().filter { it.isFile }
        Stats(files.size, files.sumOf { it.length() }, limit)
    }

    fun clear() {
        synchronized(lock) {
            if (root.exists()) root.deleteRecursively()
            root.mkdirs()
        }
    }

    private data class Segment(val start: Long, val end: Long)

    private fun segmentFiles(hash: String? = null): List<File> = root.listFiles { file ->
        file.isFile && file.name.endsWith(".seg") && (hash == null || file.name.startsWith("$hash-"))
    }?.toList().orEmpty()

    private fun parse(file: File, hash: String): Segment? {
        val prefix = "$hash-"
        if (!file.name.startsWith(prefix) || !file.name.endsWith(".seg")) return null
        val fields = file.name.removePrefix(prefix).removeSuffix(".seg").split('-')
        if (fields.size != 2) return null
        val start = fields[0].toLongOrNull() ?: return null
        val length = fields[1].toLongOrNull() ?: return null
        if (start < 0 || length <= 0 || start > Long.MAX_VALUE - length) return null
        return Segment(start, start + length)
    }

    private fun trimLocked() {
        val files = segmentFiles().filter { it.isFile }
        var total = files.sumOf { it.length() }
        if (total <= limit) return
        files.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name }).forEach { file ->
            if (total <= limit) return@forEach
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
