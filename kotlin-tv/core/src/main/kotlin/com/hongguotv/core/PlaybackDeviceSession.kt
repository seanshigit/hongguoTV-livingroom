// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import java.io.InterruptedIOException
import java.util.UUID
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class PlaybackDeviceRegistrationException(cause: Throwable? = null) :
    IOException("播放设备注册失败，请稍后重试", cause)

/** Anonymous playback identity shared across repositories, with bounded renewal on empty responses. */
class PlaybackDeviceSession(
    private val clockNanos: () -> Long = System::nanoTime,
    private val register: (OkHttpClient) -> DeviceRegister.Registered? = { http ->
        val body = DeviceRegister.payload(VendorConstants.device)
        val request = Request.Builder().url(DeviceRegister.url())
        DeviceRegister.headers(body, VendorConstants.device).forEach { (key, value) ->
            request.header(key, value)
        }
        request.post(body.toRequestBody("application/octet-stream".toMediaType()))
        // Registration has no account cookies. Cancellation belongs to the requesting work scope.
        val transport = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
        RequestScope.execute(transport.newCall(request.build())) { response ->
            if (!response.isSuccessful) throw IOException("设备注册接口失败（HTTP ${response.code}）")
            val body = response.body ?: throw IOException("设备注册接口响应为空")
            val text = body.string()
            if (text.isBlank()) throw IOException("设备注册接口返回空响应")
            DeviceRegister.parse(text, response.headers("Set-Cookie"))
        }
    }
) {
    companion object {
        val shared = PlaybackDeviceSession()
    }

    internal data class Profile(val values: Map<String, String>, val cookie: String)

    private val cdid = UUID.randomUUID().toString()
    private var ready: Profile? = null
    private val refreshNanos = java.util.ArrayDeque<Long>()

    @Synchronized
    internal fun refresh(http: OkHttpClient, rejected: Profile): Profile {
        val current = profile(http)
        if (current !== rejected) return current
        val now = clockNanos()
        while (refreshNanos.isNotEmpty() && now - refreshNanos.first >= java.util.concurrent.TimeUnit.MINUTES.toNanos(1)) refreshNanos.removeFirst()
        if (refreshNanos.size >= 6 || refreshNanos.peekLast()?.let { now - it < java.util.concurrent.TimeUnit.SECONDS.toNanos(5) } == true) return current
        refreshNanos.addLast(now)
        ready = null
        return try {
            profile(http)
        } catch (error: IOException) {
            ready = current
            RequestScope.ensureActive()
            current
        }
    }

    @Synchronized
    internal fun profile(http: OkHttpClient): Profile {
        ready?.let {
            return it
        }
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("播放请求已取消")
        val device =
            try {
                register(http) ?: throw PlaybackDeviceRegistrationException()
            } catch (error: InterruptedIOException) {
                throw error
            } catch (error: IOException) {
                throw PlaybackDeviceRegistrationException(error)
            }
        val validId = Regex("[1-9][0-9]{0,29}")
        if (!validId.matches(device.deviceId) || !validId.matches(device.installId))
            throw PlaybackDeviceRegistrationException()
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("播放请求已取消")
        return Profile(
                VendorConstants.device +
                    mapOf(
                        "device_id" to device.deviceId,
                        "iid" to device.installId,
                        "cdid" to cdid,
                    ),
                device.cookies
                    .filterKeys {
                        it in setOf("install_id", "ttreq", "store-region", "store-region-src")
                    }
                    .entries
                    .joinToString("; ") { (name, value) -> "$name=$value" },
            )
            .also { ready = it }
    }
}
