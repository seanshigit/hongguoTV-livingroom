package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class PlaybackRequestRecoveryTest {
    private val model = """{"code":0,"data":{"123":{"video_model":{"video_list":[{"main_url":"https://example.test/video","vwidth":720,"vheight":1280,"codec_type":"h264","encrypt_info":{"encrypt":false}}]}}}}"""
    private val session = PlaybackDeviceSession { DeviceRegister.Registered("333", "444") }

    private fun client(reply: (Request) -> String) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .body(reply(request).toResponseBody()).build()
    }.build()

    @Test
    fun playbackSignsTheCurrentGetWithTheRegisteredIdentity() {
        lateinit var request: Request
        val result = ContentRepository(client { request = it; model }, playbackSession = session).stream("123")
        assertEquals("https://example.test/video", result.url)
        assertEquals("333", request.url.queryParameter("device_id"))
        assertEquals("444", request.url.queryParameter("iid"))
        val timestamp = request.header("x-khronos")
        assertNotNull("playback needs its own signature", timestamp)
        assertEquals(timestamp, request.url.queryParameter("ts"))
        assertEquals((timestamp!!.toLong()*1000).toString(), request.header("x-ss-req-ticket"))
        assertNull(request.body)
        assertNull(request.header("x-ss-stub"))
        assertTrue(decode64(request.header("x-argus")!!).size>100)
        assertTrue(decode64(request.header("x-ladon")!!).size>16)
    }

    @Test
    fun emptyPrimaryResponseTriesLegacyPathOnTheSameOfficialHost() {
        val paths = mutableListOf<String>()
        val http = client { request ->
            assertEquals("api5-normal-sinfonlineb.fqnovel.com",request.url.host)
            paths += request.url.encodedPath
            if (request.method == "POST") model else ""
        }
        assertEquals("https://example.test/video", ContentRepository(http, playbackSession = session).stream("123").url)
        assertEquals(listOf("/reading/bookapi/video_tab/multi_video_model/v1/", "/novel/player/multi_video_model/v1/"), paths)
    }

    @Test
    fun repeatedEmptyResponsesAllowOnlyOneFallbackPerPlayback() {
        var registrations = 0
        var allowRenewed = true
        val session = PlaybackDeviceSession {
            registrations++
            if (registrations == 1) DeviceRegister.Registered("333", "444")
            else DeviceRegister.Registered("555", "666")
        }
        val requestedDevices = mutableListOf<String?>()
        val http = client { request ->
            val device = request.url.queryParameter("device_id")
            requestedDevices += device
            if (device == "555" && allowRenewed) model else ""
        }
        val repository = ContentRepository(http, playbackSession = session)
        assertEquals("https://example.test/video", repository.stream("123").url)
        assertEquals(listOf("333", "555"), requestedDevices)
        assertEquals(2, registrations)
        allowRenewed = false
        assertThrows(IOException::class.java) { repository.stream("123") }
        assertEquals("at most two registrations and requests per playback", 4, registrations)
        assertEquals(4, requestedDevices.size)
    }
}
