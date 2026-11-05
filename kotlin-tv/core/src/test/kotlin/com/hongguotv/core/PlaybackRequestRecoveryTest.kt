package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
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
    fun playbackSignsTheActualBodyWithTheRegisteredIdentity() {
        lateinit var request: Request
        val result = ContentRepository(client { request = it; model }, playbackSession = session).stream("123")
        assertEquals("https://example.test/video", result.url)
        assertEquals("333", request.url.queryParameter("device_id"))
        assertEquals("444", request.url.queryParameter("iid"))
        val timestamp = request.header("x-khronos")
        assertNotNull("playback needs its own signature", timestamp)
        assertEquals(timestamp, request.url.queryParameter("ts"))
        assertEquals(request.url.queryParameter("_rticket"), request.header("x-ss-req-ticket"))
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        val body = buffer.readByteArray()
        assertEquals(digest("MD5", body).hex().uppercase(), request.header("x-ss-stub"))
        val gorgon = request.header("x-gorgon")!!
        val random = gorgon.substring(4, 6).toInt(16) or (gorgon.substring(6, 8).toInt(16) shl 8)
        assertEquals(gorgon, Signer().gorgon(request.url.encodedQuery!!, body, timestamp!!.toInt(), random))
        assertFalse(request.header("x-medusa").isNullOrBlank())
        assertFalse(request.header("x-helios").isNullOrBlank())
    }

    @Test
    fun emptyPrimaryResponseTriesTheAlternateOfficialHost() {
        val hosts = mutableListOf<String>()
        val http = client { request ->
            hosts += request.url.host
            if (request.url.host == "api5-normal-hl.fqnovel.com") model else ""
        }
        assertEquals("https://example.test/video", ContentRepository(http, playbackSession = session).stream("123").url)
        assertEquals(listOf("api5-normal-sinfonlineb.fqnovel.com", "api5-normal-hl.fqnovel.com"), hosts)
    }

    @Test
    fun repeatedEmptyResponsesRenewTheDeviceOnceAndBoundFurtherRenewals() {
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
        assertEquals(listOf("333", "333", "555"), requestedDevices)
        assertEquals(2, registrations)
        allowRenewed = false
        assertThrows(IOException::class.java) { repository.stream("123") }
        assertEquals("renewals must be rate limited", 2, registrations)
    }
}
