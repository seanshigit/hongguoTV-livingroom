package com.hongguotv.core

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class PlaybackDeviceSessionTest {
    private val model =
        """{"data":{"123":{"video_model":{"video_list":[{"main_url":"https://example.test/video","video_meta":{"definition":"720p","codec_type":"h264","vwidth":720,"vheight":1280},"encrypt_info":{"encrypt":false}}]}}}}"""

    @Test
    fun registrationBusinessRejectionCannotBeAcceptedWithDeviceIds() {
        assertNull(DeviceRegister.parse("""{"code":1,"device_id":"333","install_id":"444"}"""))
        assertNotNull(DeviceRegister.parse("""{"code":0,"device_id":"333","install_id":"444"}"""))
    }

    @Test
    fun registersBeforePlaybackAndReusesIdentityAcrossRepositoriesAndRetries() {
        var registrations = 0
        var models = 0
        val requests = mutableListOf<okhttp3.Request>()
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val registration = request.url.encodedPath == "/service/2/device_register/"
                    val body =
                        if (registration) {
                            registrations++
                            assertNull(request.header("Cookie"))
                            assertNull(request.header("x-gorgon"))
                            """{"device_id":"333","install_id":"444"}"""
                        } else {
                            requests += request
                            assertEquals("333", request.url.queryParameter("device_id"))
                            assertEquals("444", request.url.queryParameter("iid"))
                            assertEquals("ttreq=test-only", request.header("Cookie"))
                            if (++models == 1) "" else model
                        }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("fixture")
                        .apply {
                            if (registration) addHeader("Set-Cookie", "ttreq=test-only; Path=/")
                        }
                        .body(body.toResponseBody())
                        .build()
                }
                .build()
        val session = PlaybackDeviceSession()
        ContentRepository(http, playbackSession = session).stream("123")
        ContentRepository(http, playbackSession = session).stream("123")
        assertEquals(1, registrations)
        assertEquals(3, models)
        assertEquals(1, requests.map { it.url.queryParameter("cdid") }.distinct().size)
    }

    @Test
    fun concurrentCallersRegisterOnlyOnceAndKeepOneProfile() {
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val session = PlaybackDeviceSession {
            calls.incrementAndGet()
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            DeviceRegister.Registered("333", "444")
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first =
                pool.submit<PlaybackDeviceSession.Profile> { session.profile(OkHttpClient()) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second =
                pool.submit<PlaybackDeviceSession.Profile> { session.profile(OkHttpClient()) }
            release.countDown()
            assertSame(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun rejectedRegistrationNeverRequestsPlaybackWithRandomIds() {
        var models = 0
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    models++
                    error("Playback must not be requested")
                }
                .build()
        for (registered in
            listOf(
                null,
                DeviceRegister.Registered("0", "444"),
                DeviceRegister.Registered("333", "bad"),
            )) {
            val repo =
                ContentRepository(http, playbackSession = PlaybackDeviceSession { registered })
            assertThrows(PlaybackDeviceRegistrationException::class.java) {
                repo.stream("123")
            }
        }
        assertEquals(0, models)
    }

    @Test
    fun registrationFailureCanBeRetriedButSuccessfulIdentityNeverRotates() {
        var calls = 0
        val session = PlaybackDeviceSession {
            if (++calls == 1) throw IOException("test-only")
            DeviceRegister.Registered(
                "333",
                "444",
                cookies = mapOf("ttreq" to "test-only", "sessionid" to "excluded"),
            )
        }
        val http = OkHttpClient()
        assertThrows(PlaybackDeviceRegistrationException::class.java) { session.profile(http) }
        val profile = session.profile(http)
        assertEquals("ttreq=test-only", profile.cookie)
        assertSame(profile, session.profile(http))
        assertEquals(2, calls)
    }

    @Test
    fun cancelledScopeDoesNotSendOrCacheRegistration() {
        var registrations = 0
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    registrations++
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("fixture")
                        .body("""{"device_id":"333","install_id":"444"}""".toResponseBody())
                        .build()
                }
                .build()
        val session = PlaybackDeviceSession()
        val scope = RequestScope()
        scope.cancel()
        assertThrows(IOException::class.java) { scope.run { session.profile(http) } }
        assertEquals(0, registrations)
        session.profile(http)
        assertEquals(1, registrations)
    }
}
