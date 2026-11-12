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

    @Test fun usesCurrentVideoTabGetAndFullPlaybackSignatures() {
        val http = client { request ->
            assertEquals("GET", request.method)
            assertEquals("/reading/bookapi/video_tab/multi_video_model/v1/", request.url.encodedPath)
            assertEquals("123", request.url.queryParameter("video_id"))
            assertEquals("73932", request.url.queryParameter("version_code"))
            assertEquals("333", request.url.queryParameter("device_id"))
            assertTrue(decode64(request.header("x-argus")!!).size > 100)
            assertTrue(decode64(request.header("x-ladon")!!).size > 16)
            assertNull(request.header("x-medusa"))
            assertNull(request.header("x-gorgon"))
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
                assertEquals("/novel/player/multi_video_model/v1/", request.url.encodedPath)
                assertEquals("74332", request.url.queryParameter("version_code"))
                assertEquals("gzip", request.header("Content-Encoding"))
                val compressed = Buffer().also { request.body!!.writeTo(it) }.readByteArray()
                assertEquals(digest("MD5", compressed).hex(), request.header("x-ss-stub"))
                val json = GZIPInputStream(ByteArrayInputStream(compressed)).use { JSONObject(String(it.readBytes(), Charsets.UTF_8)) }
                assertEquals("123", json.getJSONObject("mixed_video_id_map").getJSONArray("1004").getString(0))
                model
            }
        }
        assertEquals("https://example.test/video", ContentRepository(http, playbackSession = session()).stream("123").url)
        assertEquals(listOf("GET", "POST"), requests.map { it.method })
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
