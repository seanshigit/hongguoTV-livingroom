package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackDeviceRegressionTest {
    @Test
    fun playbackRegistersAnAnonymousDeviceAndUsesItsIdentity() {
        var registrations = 0
        var playbackDevice: String? = null
        var playbackInstall: String? = null
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            when {
                request.url.host == "log5-applog-lf.fqnovel.com" -> {
                    registrations++
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("{\"code\":0,\"device_id\":\"9007199254740993\",\"install_id\":\"9007199254741993\"}".toResponseBody())
                        .build()
                }
                request.url.encodedPath == "/novel/player/multi_video_model/v1/" -> {
                    playbackDevice = request.url.queryParameter("device_id")
                    playbackInstall = request.url.queryParameter("iid")
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("{\"code\":0,\"data\":{\"123\":{\"video_model\":{\"fallback_api\":\"https://example.com/stream\"}}}}".toResponseBody())
                        .build()
                }
                request.url.host == "example.com" -> {
                    val row = JSONObject().put("vheight", 720).put("codec_type", "h264").put("main_url", "https://example.com/video")
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(JSONObject().put("video_info", JSONObject().put("data", JSONObject().put("video_list", JSONObject().put("video_0", row)))).toString().toResponseBody())
                        .build()
                }
                else -> error("unexpected request: ${request.url}")
            }
        }.build()

        val result = ContentRepository(http).stream("123")

        assertEquals("https://example.com/video", result.url)
        assertEquals(1, registrations)
        assertEquals("9007199254740993", playbackDevice)
        assertEquals("9007199254741993", playbackInstall)
    }

    @Test
    fun playbackAcceptsTheRegisteredEndpointInlineVideoModel() {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = if (request.url.host == "log5-applog-lf.fqnovel.com") {
                "{\"code\":0,\"device_id\":\"12345\",\"install_id\":\"67890\"}"
            } else if (request.url.encodedPath == "/novel/player/multi_video_model/v1/") {
                JSONObject().put("code", 0).put("data", JSONObject().put("123", JSONObject().put("video_model", JSONObject()
                    .put("video_list", org.json.JSONArray().put(JSONObject()
                        .put("vheight", 720).put("vwidth", 1280).put("codec_type", "h264")
                        .put("main_url", "https://example.com/video"))) ))).toString()
            } else error("unexpected request: ${request.url}")
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }.build()

        val result = ContentRepository(http).stream("123")

        assertEquals("https://example.com/video", result.url)
        assertTrue(result.quality.contains("720"))
    }
}
