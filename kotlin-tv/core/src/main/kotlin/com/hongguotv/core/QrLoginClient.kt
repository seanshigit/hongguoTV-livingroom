// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Account credentials are never returned in diagnostic strings. */
class AccountSession internal constructor(
    val userId: String,
    val displayName: String,
    internal val cookieHeader: String,
    internal val device: Map<String, String>,
) {
    override fun toString() = "AccountSession(redacted)"

    fun encode(): String = JSONObject().put("uid", userId).put("name", displayName)
        .put("cookie", cookieHeader).put("device", JSONObject(device)).toString()

    companion object {
        fun decode(value: String): AccountSession {
            require(value.toByteArray(Charsets.UTF_8).size <= 32768)
            val data = JSONObject(value)
            val uid = data.getString("uid")
            val name = data.getString("name")
            val cookie = data.getString("cookie")
            val o = data.getJSONObject("device")
            val device = o.keys().asSequence().associateWith { o.getString(it) }
            require(uid.matches(Regex("[1-9][0-9]{0,29}")))
            require(name.length <= 100 && name.none(Char::isISOControl))
            require(cookie.length in 1..16384 && cookie.none(Char::isISOControl))
            require(cookie.split(';').any { it.trim().startsWith("sessionid=") || it.trim().startsWith("sessionid_ss=") })
            require(listOf("device_id", "iid").all { device[it]?.matches(Regex("[1-9][0-9]{0,29}")) == true })
            require(device["cdid"]?.matches(Regex("[0-9a-fA-F-]{36}")) == true)
            return AccountSession(uid, name.ifBlank { "红果用户" }, cookie, device)
        }
    }
}

data class QrChallenge(val png: ByteArray, val expiresAt: Long)
data class QrPoll(val state: QrLoginResult, val session: AccountSession? = null)
enum class QrLoginResult { WAITING, SCANNED, SIGNED_IN, EXPIRED, CANCELLED }

/** A single QR attempt with its own cookies and a restricted official HTTPS origin. */
class QrLoginClient(
    http: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build(),
    private val device: PlaybackDeviceSession = PlaybackDeviceSession(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false).cookieJar(CookieJar.NO_COOKIES).build()
    private val base = "https://hongguoduanju.com/".toHttpUrl()
    private val cookies = mutableListOf<Cookie>()
    private var token: String? = null
    private var expiresAt = 0L

    private fun allowed(url: HttpUrl) = url.isHttps && url.username.isEmpty() && url.password.isEmpty() &&
        url.port == 443 && url.fragment == null && (url.host == base.host || url.host.endsWith(".${base.host}"))

    private fun request(url: HttpUrl): Pair<Int, Pair<String, String?>> {
        if (!allowed(url)) throw IOException("扫码登录跳转地址无效")
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36")
            .header("Referer", base.toString()).header("Origin", "https://${base.host}")
            .header("Accept", "application/json, text/javascript")
        val current = cookies.filter { it.expiresAt > clock() && it.matches(url) }
        if (current.isNotEmpty()) request.header("Cookie", current.joinToString("; ") { "${it.name}=${it.value}" })
        return RequestScope.execute(http.newCall(request.build())) { response ->
            Cookie.parseAll(url, response.headers).forEach { cookie ->
                cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                if (cookie.expiresAt > clock()) cookies += cookie
            }
            val body = response.body ?: throw IOException("扫码登录响应为空")
            val buffer = ByteArray(8192); val output = ByteArrayOutputStream()
            body.byteStream().use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (output.size() + n > 2 * 1024 * 1024) throw IOException("扫码登录响应过大")
                    output.write(buffer, 0, n)
                }
            }
            response.code to (output.toString("UTF-8") to response.header("Location"))
        }
    }

    private fun webJson(path: String, params: Map<String, String> = emptyMap()): JSONObject {
        val url = base.newBuilder().encodedPath(path)
            .addQueryParameter("aid", "513755").addQueryParameter("passport_jssdk_version", "3.4.10")
            .addQueryParameter("passport_jssdk_type", "normal").addQueryParameter("account_sdk_source", "web")
            .addQueryParameter("is_from_ttaccountsdk", "1").addQueryParameter("language", "zh")
        params.forEach { (key, value) -> url.addQueryParameter(key, value) }
        val (status, response) = request(url.build())
        if (status != 200) throw IOException("扫码登录服务暂不可用")
        val payload = try { JSONObject(response.first) } catch (e: Exception) { throw IOException("扫码登录响应无效", e) }
        if (payload.has("code") && payload.optInt("code", -1) != 0 ||
            payload.has("message") && !payload.optString("message").equals("success", true)) throw IOException("扫码登录未完成")
        return payload.optJSONObject("data") ?: throw IOException("扫码登录响应缺少数据")
    }

    fun start(): QrChallenge {
        cancel()
        val data = webJson("/passport/web/get_qrcode/", mapOf("next" to base.toString()))
        val nextToken = data.str("token")
        if (nextToken.isBlank() || nextToken.length > 8192) throw IOException("二维码响应无效")
        val image = data.str("qrcode").substringAfter("base64,", data.str("qrcode"))
        if (image.length > 1_400_000) throw IOException("二维码响应过大")
        val png = try { Base64.getDecoder().decode(image) } catch (e: IllegalArgumentException) { throw IOException("二维码格式无效", e) }
        val magic = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        if (png.size !in 24..1_048_576 || !png.copyOfRange(0, 8).contentEquals(magic)) throw IOException("二维码图片无效")
        fun dimension(offset: Int) = java.nio.ByteBuffer.wrap(png, offset, 4).int
        if (dimension(16) !in 32..2048 || dimension(20) !in 32..2048) throw IOException("二维码图片尺寸无效")
        val now = clock()
        val raw = data.optLong("expire_time", 180)
        val seconds = when { raw > 1_000_000_000_000L -> (raw - now) / 1000; raw > 1_000_000_000L -> raw - now / 1000; else -> raw }
        if (seconds <= 0) throw IOException("二维码已过期")
        expiresAt = now + minOf(seconds, 600) * 1000
        token = nextToken
        return QrChallenge(png, expiresAt)
    }

    fun poll(): QrPoll {
        val currentToken = token ?: throw IOException("扫码会话已结束")
        if (clock() >= expiresAt) { cancel(); return QrPoll(QrLoginResult.EXPIRED) }
        val data = webJson("/passport/web/check_qrconnect/", mapOf("next" to base.toString(), "token" to currentToken))
        return when (data.str("status")) {
            "", "1", "new", "pending" -> QrPoll(QrLoginResult.WAITING)
            "2", "scanned" -> QrPoll(QrLoginResult.SCANNED)
            "4", "cancelled" -> { cancel(); QrPoll(QrLoginResult.CANCELLED) }
            "5", "expired" -> { cancel(); QrPoll(QrLoginResult.EXPIRED) }
            "3", "confirmed" -> QrPoll(QrLoginResult.SIGNED_IN, complete(data.str("redirect_url")))
            else -> throw IOException("扫码状态无效，请重新扫码")
        }
    }

    private fun complete(redirect: String): AccountSession {
        if (redirect.isNotBlank()) {
            var next = base.resolve(redirect) ?: throw IOException("扫码登录跳转地址无效")
            var finished = false
            repeat(5) {
                if (!finished) {
                    val (status, response) = request(next)
                    if (status == 200) finished = true
                    else if (status in setOf(301, 302, 303, 307, 308) && !response.second.isNullOrBlank())
                        next = next.resolve(response.second!!) ?: throw IOException("扫码登录跳转地址无效")
                    else throw IOException("扫码登录确认失败")
                }
            }
            if (!finished) throw IOException("扫码登录跳转次数过多")
        }
        val webCookies = cookies.filter { it.expiresAt > clock() && it.matches(base) && it.name != "install_id" }
        if (webCookies.none { it.name in setOf("sessionid", "sessionid_ss") && it.value.isNotBlank() }) throw IOException("扫码登录未取得会话")
        val profile = webJson("/passport/account/info/v2/")
        val uid = profile.str("user_id_str").ifBlank { profile.str("user_id") }
        if (!uid.matches(Regex("[1-9][0-9]{0,29}"))) throw IOException("扫码账号身份无效")
        val name = listOf(profile.str("name"), profile.str("screen_name"), profile.str("username"))
            .firstOrNull { it.isNotBlank() }?.filterNot(Char::isISOControl)?.take(100) ?: "红果用户"
        val registered = device.profile(http)
        val values = linkedMapOf<String, String>()
        webCookies.forEach { values[it.name] = it.value }
        registered.cookie.split(';').map(String::trim).filter { '=' in it }.forEach { values[it.substringBefore('=')] = it.substringAfter('=') }
        val session = AccountSession(uid, name, values.entries.joinToString("; ") { "${it.key}=${it.value}" }, registered.values)
        token = null
        cookies.clear()
        return session
    }

    fun cancel() { token = null; expiresAt = 0; cookies.clear() }
    fun verify(session: AccountSession) {
        cancel()
        session.cookieHeader.split(';').map(String::trim).filter(String::isNotBlank).forEach { pair ->
            Cookie.parse(base, "$pair; Secure; Path=/")?.let { cookies += it }
        }
        try {
            val data = webJson("/passport/account/info/v2/")
            val uid = data.str("user_id_str").ifBlank { data.str("user_id") }
            if (uid != session.userId) throw IOException("登录账号已变化，请重新扫码")
        } finally { cookies.clear() }
    }
}
