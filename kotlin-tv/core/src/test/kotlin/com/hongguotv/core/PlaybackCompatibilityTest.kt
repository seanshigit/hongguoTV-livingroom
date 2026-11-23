// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import java.util.zip.GZIPInputStream
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaybackCompatibilityTest {
    private fun client(reply: (Request) -> String) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .body(reply(request).toResponseBody()).build()
    }.build()

    private fun row(codec: String, height: Int = 720) = JSONObject()
        .put("main_url", "https://example.test/$codec/$height")
        .put("codec_type", codec).put("vwidth", height).put("vheight", height * 16 / 9)
        .put("encrypt_info", JSONObject().put("encrypt", false))

    private fun model(vararg rows: JSONObject) = JSONObject().put("video_list", JSONArray(rows.toList()))

    private fun response(model: JSONObject, grouped: Boolean = false, id: String = "123", encoded: Boolean = false): String {
        val entry = JSONObject().put("video_model", if (encoded) model.toString() else model)
        val data = JSONObject().put(id, entry)
        return JSONObject().put("code", 0).put("data", if (grouped) JSONObject().put("1004", data) else data).toString()
    }

    @Test fun byteVc2OnlyGetNegotiatesOneV5PostWithTheSameIdentityAndExactGzipBody() {
        var registrations = 0
        val requests = mutableListOf<Request>()
        val session = PlaybackDeviceSession { registrations++; DeviceRegister.Registered("333", "444") }
        val repo = ContentRepository(client { request ->
            requests += request
            if (requests.size == 1) {
                assertEquals("GET", request.method)
                response(model(row("ByteVC2"), row("bvc2", 480)))
            } else {
                assertEquals("POST", request.method)
                assertEquals("reading.snssdk.com", request.url.host)
                assertEquals("/novel/player/multi_video_model/preload/v1", request.url.encodedPath)
                for (field in listOf("iid", "device_id", "cdid", "aid", "version_code", "version_name", "channel", "device_type", "os_version")) {
                    assertEquals(field, requests.first().url.queryParameter(field), request.url.queryParameter(field))
                }
                assertNull(request.url.queryParameter("video_id"))
                assertNull(request.url.queryParameter("ts"))
                assertEquals("gzip", request.header("Content-Encoding"))
                assertEquals(requests.first().header("User-Agent"), request.header("User-Agent"))
                assertNull(request.header("x-argus"))
                assertNull(request.header("x-ladon"))
                assertNull(request.header("x-gorgon"))
                val compressed = Buffer().also { request.body!!.writeTo(it) }.readByteArray()
                assertEquals(digest("MD5", compressed).hex().uppercase(), request.header("x-ss-stub"))
                val payload = GZIPInputStream(compressed.inputStream()).use { JSONObject(String(it.readBytes(), Charsets.UTF_8)) }
                assertEquals("preload", payload.getString("dr_scene"))
                assertEquals("123", payload.getJSONObject("mixed_video_id_map").getJSONArray("1004").getString(0))
                val biz = payload.getJSONObject("biz_param")
                assertEquals(2, biz.getInt("device_level"))
                assertTrue(biz.getBoolean("need_all_video_definition"))
                assertFalse(biz.getBoolean("disable_video_relate_book"))
                assertFalse(biz.getBoolean("need_mp4_align"))
                assertFalse(biz.getBoolean("use_os_player"))
                val timestamp = request.header("x-khronos")!!.toInt()
                assertEquals(5, decode64(request.header("x-medusa")!!).i32(0, true) xor timestamp)
                assertTrue(decode64(request.header("x-helios")!!).size > 16)
                // The actual transmitted URL and body must select the signed V5 branch.
                assertEquals(20, PlaybackV5Signer().hashF13(
                    sm3(request.url.encodedQuery!!.toByteArray(Charsets.UTF_8)), digest("MD5", compressed), timestamp).size)
                response(model(row("bytevc1", 1080), row("h264", 480)), grouped = true, encoded = true)
            }
        }, playbackSession = session)
        val result = repo.stream("123")
        assertEquals("https://example.test/bytevc1/1080", result.url)
        assertEquals("1080P", result.quality)
        assertEquals(listOf("https://example.test/h264/480"), result.alternatives.map { it.url })
        assertEquals(2, requests.size)
        assertEquals(1, registrations)
    }

    @Test fun preloadWithOnlyByteVc2UsesModelVodForCompatibleVideoAndBackup() {
        val requests = mutableListOf<Request>()
        val initial = model(row("ByteVC2")).put("fallback_api",
            """{"fallback_api":"https://example.test/vod?codec_type=4&ticket=opaque"}""")
        val repo = ContentRepository(client { request ->
            requests += request
            when (request.url.encodedPath) {
                "/reading/bookapi/video_tab/multi_video_model/v1/" -> ""
                "/novel/player/multi_video_model/preload/v1" -> response(initial, grouped = true, encoded = true)
                "/vod" -> {
                    assertEquals("1", request.url.queryParameter("codec_type"))
                    assertEquals("opaque", request.url.queryParameter("ticket"))
                    assertNull(request.header("x-medusa"))
                    assertNull(request.header("Cookie"))
                    val primary = java.util.Base64.getEncoder().encodeToString("https://example.test/hevc/1080".toByteArray())
                    val hevc = row("hevc", 1080).put("main_url", primary)
                        .put("backup_url_1", "https://example.test/hevc-backup/1080")
                    JSONObject().put("video_info", JSONObject().put("data", model(hevc))).toString()
                }
                else -> error("Unexpected request")
            }
        }, playbackSession = PlaybackDeviceSession { DeviceRegister.Registered("333", "444") })
        val stream = repo.stream("123")
        assertEquals("1080P", stream.quality)
        assertEquals("https://example.test/hevc/1080", stream.url)
        assertEquals(listOf("https://example.test/hevc-backup/1080"), stream.alternatives.map { it.url })
        assertEquals(3, requests.size)
    }

    @Test fun aCompatibleGetDoesNotRequestAnotherModel() {
        var requests = 0
        val repo = ContentRepository(client { requests++; response(model(row("bytevc2"), row("h264", 480))) },
            playbackSession = PlaybackDeviceSession { DeviceRegister.Registered("333", "444") })
        assertEquals("480P", repo.stream("123").quality)
        assertEquals(1, requests)
    }

    @Test fun missingKeysAndInvalidUrlsAreNotMistakenForUnsupportedCodecs() {
        val missingKey = row("h264").apply { remove("encrypt_info") }
        val invalidUrl = model(row("h264")).put("key_seed", "not-a-valid-key-seed")
        val byteVc2MissingKey = row("bytevc2").put("encrypt_info", JSONObject().put("encrypt", true))
        val byteVc2InvalidKey = row("bytevc2").put("spade_a", "invalid-key")
        val byteVc2InvalidUrl = row("bytevc2").put("main_url", "garbage")
        val byteVc2InvalidSeed = model(row("bytevc2")).put("key_seed", "not-a-valid-key-seed")
        val malformedRows = model(row("bytevc2")).apply { getJSONArray("video_list").put("not-a-model") }
        for (playback in listOf(model(missingKey), model(row("bytevc2"), missingKey), invalidUrl,
            model(byteVc2MissingKey), model(byteVc2InvalidKey), model(byteVc2InvalidUrl), byteVc2InvalidSeed, malformedRows)) {
            var requests = 0
            val repo = ContentRepository(client { requests++; response(playback) },
                playbackSession = PlaybackDeviceSession { DeviceRegister.Registered("333", "444") })
            assertThrows(IOException::class.java) { repo.stream("123") }
            assertEquals(1, requests)
        }
    }

    @Test fun compatibilityProviderRefusalKeepsItsCodeAndDoesNotRotateIdentity() {
        var requests = 0
        var registrations = 0
        val repo = ContentRepository(client {
            if (++requests == 1) response(model(row("bytevc2"))) else """{"code":110001,"message":"not available"}"""
        }, playbackSession = PlaybackDeviceSession { registrations++; DeviceRegister.Registered("333", "444") })
        val error = assertThrows(ProviderPlaybackException::class.java) { repo.stream("123") }
        assertEquals(110001, error.sourceCode)
        assertEquals(2, requests)
        assertEquals(1, registrations)
    }

    @Test fun compatibilityFailureIsBoundedAndNeverSubstitutesAnotherEpisode() {
        val responses = listOf("", "malformed JSON", response(model(row("bytevc2")), grouped = true),
            response(model(), grouped = true), response(model(row("h264")), grouped = true, id = "999"))
        for (reply in responses) {
            var requests = 0
            var registrations = 0
            val repo = ContentRepository(client {
                if (++requests == 1) response(model(row("bytevc2"))) else reply
            }, playbackSession = PlaybackDeviceSession { registrations++; DeviceRegister.Registered("333", "444") })
            val error = assertThrows(IOException::class.java) { repo.stream("123") }
            assertTrue(error.message.orEmpty().isNotBlank())
            assertEquals(2, requests)
            assertEquals(1, registrations)
        }
    }

    @Test fun emptyGetUsesPreloadWithoutRepeatingTheModelRequest() {
        val paths = mutableListOf<String>()
        val repo = ContentRepository(client { request ->
            paths += request.url.encodedPath
            if (request.method == "GET") "" else response(model(row("bytevc2")))
        }, playbackSession = PlaybackDeviceSession { DeviceRegister.Registered("333", "444") })
        assertThrows(IOException::class.java) { repo.stream("123") }
        assertEquals(listOf("/reading/bookapi/video_tab/multi_video_model/v1/", "/novel/player/multi_video_model/preload/v1"), paths)
    }
}
