package com.hongguotv.nativeapp

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.hongguotv.core.AppUpdateRepository
import com.hongguotv.core.UpdateFiles
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException

/** Exercises the real HTTPS mirror while simulating GitHub being unreachable. */
class MirrorAcceptance : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val apk = File(targetContext.cacheDir, "mirror-acceptance.apk")
        var result = Activity.RESULT_CANCELED
        var report = ""
        try {
            AppUpdateRepository().use { repository ->
                val field = AppUpdateRepository::class.java.getDeclaredField("http").apply { isAccessible = true }
                val client = field.get(repository) as OkHttpClient
                field.set(repository, client.newBuilder().addInterceptor { chain ->
                    if (chain.request().url.host == "github.com") throw IOException("GitHub unavailable in test")
                    chain.proceed(chain.request())
                }.build())
                val update = repository.latest()
                check(update.versionCode >= 28) { "Unexpected mirror version" }
                repository.download(update, apk) {}
                check(UpdateFiles.verify(apk, update)) { "Mirror package verification failed" }
                report = "PASS: KVM HTTPS manifest and APK verified while GitHub is unavailable; version ${update.versionName}\n"
            }
            result = Activity.RESULT_OK
        } catch (e: Throwable) {
            report = "FAIL: ${e.javaClass.simpleName}: ${e.message}\n"
        } finally {
            apk.delete()
        }
        finish(result, Bundle().apply { putString("stream", report) })
    }
}
