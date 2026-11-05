package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

class AppUpdatesTest {
    private val bytes = "signed-apk-test-bytes".toByteArray()
    private fun json() = JSONObject().put("schema", 1).put("packageName", "com.hongguotv.nativeapp")
        .put("versionCode", 10001).put("versionName", "0.9.0+1").put("minSdk", 26).put("tag", "kotlin-v0.9.0-build.1")
        .put("apk", JSONObject().put("name", "hongguotv-kotlin-0.9.0-build.1-android8.apk").put("size", bytes.size)
            .put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }))
    private fun update() = AppUpdate.parse(json().toString())
    private fun withFile(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("update-test").toFile()
        try { block(File(dir, "update.apk")) } finally { dir.deleteRecursively() }
    }
    private fun repository(handler: (Request) -> Response): AppUpdateRepository {
        val client = OkHttpClient.Builder().addInterceptor { handler(it.request()) }.build()
        return AppUpdateRepository().also {
            AppUpdateRepository::class.java.getDeclaredField("http").apply { isAccessible = true }.set(it, client)
        }
    }
    private fun response(request: Request, status: Int, body: ByteArray) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(status).message("test")
        .body(body.toResponseBody()).build()
    @Test fun checksKvmWhenGithubIsUnavailable() {
        repository { request ->
            when (request.url.host) {
                "github.com" -> throw IOException("unreachable")
                "kvm.fullcoin.com" -> {
                    assertEquals("/hongguotv-update/update.json", request.url.encodedPath)
                    response(request, 200, json().toString().toByteArray())
                }
                else -> throw AssertionError("unexpected update host")
            }
        }.use { assertEquals(10001L, it.latest().versionCode) }
    }
    @Test fun downloadsFromKvmWhenGithubIsUnavailable() = withFile { file ->
        repository { request ->
            when (request.url.host) {
                "github.com" -> throw IOException("unreachable")
                "kvm.fullcoin.com" -> {
                    assertEquals("/hongguotv-update/releases/kotlin-v0.9.0-build.1/hongguotv-kotlin-0.9.0-build.1-android8.apk", request.url.encodedPath)
                    response(request, 200, bytes)
                }
                else -> throw AssertionError("unexpected update host")
            }
        }.use { it.download(update(), file) {} }
        assertArrayEquals(bytes, file.readBytes())
    }
    @Test fun corruptedKvmDownloadCannotReplaceExistingFile() = withFile { file ->
        file.writeText("previous")
        var reachedMirror = false
        repository { request ->
            when (request.url.host) {
                "github.com" -> throw IOException("unreachable")
                "kvm.fullcoin.com" -> { reachedMirror = true; response(request, 200, ByteArray(bytes.size)) }
                else -> throw AssertionError("unexpected update host")
            }
        }.use { assertThrows(IOException::class.java) { it.download(update(), file) {} } }
        assertTrue(reachedMirror)
        assertEquals("previous", file.readText())
        assertFalse(File(file.path + ".part").exists())
    }
    @Test fun githubMetadataTakesPrecedenceOverMirror() {
        repository { request ->
            if (request.url.host != "github.com") throw AssertionError("mirror requested despite working primary")
            response(request, 200, json().toString().toByteArray())
        }.use { assertEquals(10001L, it.latest().versionCode) }
    }
    @Test fun interruptedCheckDoesNotStartAnotherNetworkRequest() {
        var contacted = false
        repository { _ -> contacted = true; throw IOException("unreachable") }.use { repository ->
            Thread.currentThread().interrupt()
            try { assertThrows(IOException::class.java) { repository.latest() } }
            finally { Thread.interrupted() }
        }
        assertFalse(contacted)
    }
    @Test fun onlyHigherCompatibleVersionsAreUpdates() {
        val update = update()
        assertTrue(update.newerThan(9, 26)); assertFalse(update.newerThan(10001, 36)); assertFalse(update.newerThan(10002, 36)); assertFalse(update.newerThan(9, 25))
    }
    @Test fun downloadUrlUsesOnlyFixedRepositoryAndValidatedNames() {
        assertEquals("https://github.com/seanminjie-del/hongguoTV-livingroom/releases/download/kotlin-v0.9.0-build.1/hongguotv-kotlin-0.9.0-build.1-android8.apk", update().downloadUrl)
        for (value in listOf("../../bad.apk", "evil.apk", "https://example.com/evil.apk", "hongguotv-kotlin-1/2-android8.apk")) {
            val o = json(); o.getJSONObject("apk").put("name", value)
            assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(o.toString()) }
        }
        assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(json().put("tag", "../../other").toString()) }
    }
    @Test fun publishedLivingRoomAssetsAreAcceptedWithoutAllowingTraversal() {
        val manifest=json().put("versionCode",12).put("versionName","0.12.0-local").put("tag","tv-v0.12.0-local")
        manifest.getJSONObject("apk").put("name","hongguotv-0.12.0-local-android8.apk")
        val update=AppUpdate.parse(manifest.toString())
        assertEquals("https://github.com/seanminjie-del/hongguoTV-livingroom/releases/download/tv-v0.12.0-local/hongguotv-0.12.0-local-android8.apk",update.downloadUrl)
        assertTrue(update.newerThan(11,36)); assertFalse(update.newerThan(12,36))
        for(name in listOf("../hongguotv-0.12.0-local-android8.apk","hongguotv-0.12.0-local-android8.apk?token=x","hongguotv-0.12.0-local-android8.apk/other")) {
            manifest.getJSONObject("apk").put("name",name)
            assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(manifest.toString()) }
        }
    }
    @Test fun rejectOtherPackageAndInvalidSchema() {
        assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(json().put("packageName", "other.app").toString()) }
        assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(json().put("schema", 2).toString()) }
    }
    @Test fun enforceVersionSizeAndChecksumBounds() {
        for (code in listOf(0L, -1L, Int.MAX_VALUE.toLong()+1)) assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(json().put("versionCode", code).toString()) }
        for (size in listOf(0L, -1L, AppUpdate.MAX_APK_BYTES+1)) {
            val o = json(); o.getJSONObject("apk").put("size", size)
            assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(o.toString()) }
        }
        val o = json(); o.getJSONObject("apk").put("sha256", "invalid")
        assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(o.toString()) }
    }
    @Test fun verifiedDownloadAtomicallyReplacesOldFile() = withFile { file ->
        file.writeText("previous"); val progress = mutableListOf<Int>()
        UpdateFiles.receive(ByteArrayInputStream(bytes), file, update(), progress::add)
        assertTrue(UpdateFiles.verify(file, update())); assertEquals(100, progress.last()); assertFalse(File(file.path+".part").exists())
    }
    @Test fun truncatedDownloadPreservesPreviousFile() = withFile { file ->
        file.writeText("previous")
        assertThrows(IOException::class.java) { UpdateFiles.receive(ByteArrayInputStream(bytes.copyOf(4)), file, update()) }
        assertEquals("previous", file.readText()); assertFalse(File(file.path+".part").exists())
    }
    @Test fun oversizedDownloadIsRejectedAndRemoved() = withFile { file ->
        assertThrows(IOException::class.java) { UpdateFiles.receive(ByteArrayInputStream(bytes+byteArrayOf(1)), file, update()) }
        assertFalse(file.exists()); assertFalse(File(file.path+".part").exists())
    }
    @Test fun checksumMismatchCannotBecomeInstallableFile() = withFile { file ->
        val corrupt = bytes.copyOf(); corrupt[0] = 0
        assertThrows(IOException::class.java) { UpdateFiles.receive(ByteArrayInputStream(corrupt), file, update()) }
        assertFalse(file.exists()); assertFalse(File(file.path+".part").exists())
    }
    @Test fun interruptedDownloadCleansUpPartialFile() = withFile { file ->
        Thread.currentThread().interrupt()
        try { assertThrows(IOException::class.java) { UpdateFiles.receive(ByteArrayInputStream(bytes), file, update()) } }
        finally { Thread.interrupted() }
        assertFalse(file.exists()); assertFalse(File(file.path+".part").exists())
    }
    @Test fun cacheMustStillMatchExpectedContent() = withFile { file ->
        file.writeBytes(bytes); assertTrue(UpdateFiles.verify(file, update()))
        file.writeBytes(ByteArray(bytes.size)); assertFalse(UpdateFiles.verify(file, update()))
    }
}
