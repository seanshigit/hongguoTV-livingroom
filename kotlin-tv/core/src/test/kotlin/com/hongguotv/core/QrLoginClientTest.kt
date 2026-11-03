package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import javax.imageio.ImageIO
import java.awt.image.BufferedImage

class QrLoginClientTest {
    private fun png(): String {
        val bytes = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(48, 48, BufferedImage.TYPE_INT_RGB), "png", bytes)
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }
    private fun response(request: Request, body: String, vararg cookies: String) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .apply { cookies.forEach { addHeader("Set-Cookie", it) } }.body(body.toResponseBody()).build()

    @Test
    fun scanVerifiesAccountBeforeReturningARegisteredSession() {
        var polls = 0
        var confirmed = false
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            when (request.url.encodedPath) {
                "/passport/web/get_qrcode/" -> response(request,
                    JSONObject().put("message", "success").put("data", JSONObject().put("token", "fixture-qr-token")
                        .put("qrcode", png()).put("expire_time", 180)).toString(), "csrf=fixture; Path=/; Secure")
                "/passport/web/check_qrconnect/" -> {
                    assertTrue(request.header("Cookie").orEmpty().contains("csrf=fixture"))
                    val status = listOf("new", "scanned", "confirmed")[polls++]
                    response(request, JSONObject().put("message", "success").put("data", JSONObject().put("status", status)
                        .put("redirect_url", if (status == "confirmed") "https://hongguoduanju.com/passport/finish" else "")).toString())
                }
                "/passport/finish" -> {
                    confirmed = true
                    response(request, "ok", "sessionid=fixture-session; Path=/; Secure")
                }
                "/passport/account/info/v2/" -> {
                    assertTrue(confirmed)
                    assertTrue(request.header("Cookie").orEmpty().contains("sessionid=fixture-session"))
                    response(request, """{"message":"success","data":{"user_id_str":"9876543210123456789","name":"测试用户"}}""")
                }
                else -> error("Unexpected endpoint: ${request.url.encodedPath}")
            }
        }.build()
        val device = PlaybackDeviceSession { DeviceRegister.Registered("333", "444") }
        val login = QrLoginClient(http, device) { 1_700_000_000_000L }
        val challenge = login.start()
        assertTrue(challenge.png.isNotEmpty())
        assertEquals(1_700_000_180_000L, challenge.expiresAt)
        assertEquals(QrLoginResult.WAITING, login.poll().state)
        assertEquals(QrLoginResult.SCANNED, login.poll().state)
        val success = login.poll()
        assertEquals(QrLoginResult.SIGNED_IN, success.state)
        assertEquals("9876543210123456789", success.session!!.userId)
        assertEquals("测试用户", success.session!!.displayName)
        assertFalse(success.session.toString().contains("fixture-session"))
    }

    @Test
    fun confirmationNeverFollowsAnUntrustedRedirect() {
        val requested = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); requested += request.url.host
            when (request.url.encodedPath) {
                "/passport/web/get_qrcode/" -> response(request, JSONObject().put("message", "success").put("data", JSONObject()
                    .put("token", "fixture").put("qrcode", png()).put("expire_time", 180)).toString())
                "/passport/web/check_qrconnect/" -> response(request, """{"message":"success","data":{"status":"confirmed","redirect_url":"https://evil.test/steal"}}""")
                else -> error("Unexpected request")
            }
        }.build()
        val login = QrLoginClient(http, PlaybackDeviceSession { DeviceRegister.Registered("333", "444") }) { 1_700_000_000_000L }
        login.start()
        assertThrows(IOException::class.java) { login.poll() }
        assertEquals(listOf("hongguoduanju.com", "hongguoduanju.com"), requested)
    }

    @Test
    fun restoredSessionMustStillBelongToTheSameAccount() {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("hongguoduanju.com", request.url.host)
            assertTrue(request.header("Cookie").orEmpty().contains("sessionid=fixture"))
            response(request, """{"message":"success","data":{"user_id_str":"456"}}""")
        }.build()
        val session = AccountSession("123", "测试", "sessionid=fixture", VendorConstants.device + mapOf(
            "device_id" to "333", "iid" to "444", "cdid" to "11111111-2222-3333-4444-555555555555"))
        val login = QrLoginClient(http, PlaybackDeviceSession { DeviceRegister.Registered("333", "444") })
        assertThrows(IOException::class.java) { login.verify(session) }
    }
}
