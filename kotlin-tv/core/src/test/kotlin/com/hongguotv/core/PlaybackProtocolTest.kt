package com.hongguotv.core

import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaybackProtocolTest {
    private val model = """{"code":0,"data":{"123":{"video_model":{"video_list":[{"main_url":"https://example.test/video","codec_type":"h264","vwidth":720,"vheight":1280,"encrypt_info":{"encrypt":false}}]}}}}"""
    private fun client(reply: (Request) -> String) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .body(reply(request).toResponseBody()).build()
    }.build()
    private fun session() = PlaybackDeviceSession { DeviceRegister.Registered("333", "444") }

    @Test fun usesCurrentVideoTabGetWithV5PlaybackSignatures() {
        val http = client { request ->
            assertEquals("GET", request.method)
            assertEquals("/reading/bookapi/video_tab/multi_video_model/v1/", request.url.encodedPath)
            assertEquals("123", request.url.queryParameter("video_id"))
            assertEquals("74532", request.url.queryParameter("version_code"))
            assertEquals("7.4.5.32", request.url.queryParameter("version_name"))
            assertEquals("update_64", request.url.queryParameter("channel"))
            assertEquals("25053RT47C", request.url.queryParameter("device_type"))
            assertEquals("16", request.url.queryParameter("os_version"))
            assertEquals("333", request.url.queryParameter("device_id"))
            assertEquals("444", request.url.queryParameter("iid"))
            assertEquals(System.getProperty("http.agent", "okhttp/4.12.0"), request.header("User-Agent"))
            val timestamp = request.header("x-khronos")!!.toInt()
            val medusa = decode64(request.header("x-medusa")!!)
            assertEquals("v5 wire prefix", 5, medusa.i32(0, true) xor timestamp)
            assertTrue(medusa.size > 100)
            assertTrue(decode64(request.header("x-helios")!!).size > 16)
            assertNull(request.header("x-argus"))
            assertNull(request.header("x-ladon"))
            assertNull(request.header("x-gorgon"))
            assertNull(request.header("x-ss-stub"))
            assertNull(request.header("Content-Encoding"))
            assertNull(request.header("Content-Type"))
            assertNull(request.body)
            model
        }
        assertEquals("https://example.test/video", ContentRepository(http, playbackSession = session()).stream("123").url)
    }

    @Test fun emptyCurrentEndpointFallsBackOnceToSignedGzipPost() {
        val requests = mutableListOf<Request>()
        val http = client { request ->
            requests += request
            if (request.method == "GET") "" else {
                assertEquals("reading.snssdk.com", request.url.host)
                assertEquals("/novel/player/multi_video_model/preload/v1", request.url.encodedPath)
                assertEquals("74532", request.url.queryParameter("version_code"))
                assertEquals("gzip", request.header("Content-Encoding"))
                assertNull(request.header("x-argus"))
                assertNull(request.header("x-ladon"))
                assertTrue(decode64(request.header("x-medusa")!!).size > 100)
                assertTrue(decode64(request.header("x-helios")!!).size > 16)
                val compressed = Buffer().also { request.body!!.writeTo(it) }.readByteArray()
                assertEquals(digest("MD5", compressed).hex().uppercase(), request.header("x-ss-stub"))
                val json = GZIPInputStream(ByteArrayInputStream(compressed)).use { JSONObject(String(it.readBytes(), Charsets.UTF_8)) }
                assertEquals("preload", json.getString("dr_scene"))
                assertEquals(2, json.getJSONObject("biz_param").getInt("device_level"))
                assertEquals("123", json.getJSONObject("mixed_video_id_map").getJSONArray("1004").getString(0))
                model
            }
        }
        assertEquals("https://example.test/video", ContentRepository(http, playbackSession = session()).stream("123").url)
        assertEquals(listOf("GET", "POST"), requests.map { it.method })
    }

    @Test fun providerRejectionIsNotRetriedWithAnotherIdentityOrProtocol() {
        var requests = 0
        var registrations = 0
        val session = PlaybackDeviceSession { registrations++; DeviceRegister.Registered("333", "444") }
        val repository = ContentRepository(client { requests++; """{"code":403,"message":"not available"}""" }, playbackSession = session)
        val failure = assertThrows(ProviderPlaybackException::class.java) { repository.stream("123") }
        assertEquals(403, failure.sourceCode)
        assertEquals(1, requests)
        assertEquals(1, registrations)
    }

    @Test fun eachEpisodeGetsANewAnonymousPlaybackIdentity() {
        var registrations = 0
        val identities = mutableListOf<String?>()
        val session = PlaybackDeviceSession { DeviceRegister.Registered((++registrations + 100).toString(), "444") }
        val repository = ContentRepository(client { request -> identities += request.url.queryParameter("device_id"); model }, playbackSession = session)
        repeat(2) { repository.stream("123") }
        assertEquals(listOf("101", "102"), identities)
        assertEquals(2, registrations)
    }

    @Test fun readsCurrentNestedModelAndObjectVideoList() {
        val json = JSONObject(model)
        val data = json.getJSONObject("data")
        val playback = data.getJSONObject("123").getJSONObject("video_model")
        playback.put("video_list", JSONObject().put("video_1", playback.getJSONArray("video_list").getJSONObject(0)))
        json.put("data", JSONObject().put("video_model_data", data))
        assertEquals("https://example.test/video", ContentRepository(client { json.toString() }, playbackSession = session()).stream("123").url)
    }

    @Test fun inlineStreamWithoutEncryptionMetadataIsNeverTreatedAsClearVideo() {
        val json = JSONObject(model)
        json.getJSONObject("data").getJSONObject("123").getJSONObject("video_model").getJSONArray("video_list").getJSONObject(0).remove("encrypt_info")
        assertThrows(IOException::class.java) { ContentRepository(client { json.toString() }, playbackSession = session()).stream("123") }
    }
}
