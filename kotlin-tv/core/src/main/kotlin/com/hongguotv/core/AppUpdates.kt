// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Package-bound channels. Update metadata cannot choose a repository or mirror. */
enum class AppUpdateChannel(val packageName: String, val repository: String, val manifestUrl: String, val mirror: String?) {
    LEGACY(
        "com.hongguotv.nativeapp", "seanminjie-del/hongguoTV-livingroom",
        "https://github.com/seanminjie-del/hongguoTV-livingroom/releases/latest/download/update.json",
        "https://kvm.fullcoin.com/hongguotv-update",
    ),
    PLAYBACKFIX(
        "com.hongguotv.nativeapp.playbackfix", "seanshigit/hongguoTV-livingroom",
        "https://raw.githubusercontent.com/seanshigit/hongguoTV-livingroom/updates/playbackfix.json", null,
    );

    val releases get() = "https://github.com/$repository/releases"
    // Preserve the legacy identifier; migrating the repair build clears only its old check throttle.
    val preferenceId get() = if (this == LEGACY) repository else "$repository:$packageName"
    val manifestUrls get() = listOfNotNull(manifestUrl, mirror?.let { "$it/update.json" })

    internal fun permits(url: HttpUrl): Boolean {
        if (url.scheme != "https" || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty()) return false
        return when (url.host) {
            "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com" -> true
            "raw.githubusercontent.com" -> this == PLAYBACKFIX && url.toString() == manifestUrl
            "kvm.fullcoin.com" -> mirror != null && url.encodedPath.startsWith("/hongguotv-update/")
            else -> false
        }
    }

    companion object {
        fun forPackageName(packageName: String): AppUpdateChannel = values().firstOrNull { it.packageName == packageName }
            ?: throw IllegalArgumentException("应用没有匹配的更新渠道")
    }
}

data class AppUpdate(val versionCode: Long, val versionName: String, val minSdk: Int,
                     val tag: String, val fileName: String, val size: Long, val sha256: String, val notes: String,
                     val channel: AppUpdateChannel = AppUpdateChannel.LEGACY) {
    init {
        require(versionCode in 1..Int.MAX_VALUE.toLong() && minSdk in 26..100)
        require(versionName.matches(Regex("[0-9][0-9A-Za-z.+-]{0,63}")))
        require(tag.matches(Regex("(?:tv|kotlin)-v[0-9][0-9A-Za-z.+-]{0,80}")))
        require(fileName.matches(Regex("hongguotv-(?:kotlin-)?[0-9][0-9A-Za-z.+-]{0,80}-android8\\.apk")))
        require(size in 1..MAX_APK_BYTES && sha256.matches(Regex("[0-9a-f]{64}")))
    }
    fun newerThan(installed: Long, sdk: Int) = versionCode > installed && minSdk <= sdk
    val downloadUrl get() = "${channel.releases}/download/$tag/$fileName"
    companion object {
        const val REPOSITORY = "seanminjie-del/hongguoTV-livingroom"
        const val RELEASES = "https://github.com/$REPOSITORY/releases"
        const val MIRROR = "https://kvm.fullcoin.com/hongguotv-update"
        const val MAX_APK_BYTES = 80L * 1024 * 1024
        fun parse(json: String, channel: AppUpdateChannel = AppUpdateChannel.LEGACY): AppUpdate {
            val o = JSONObject(json)
            require(o.getInt("schema") == 1 && o.getString("packageName") == channel.packageName) { "更新信息不匹配" }
            val apk = o.getJSONObject("apk")
            return AppUpdate(o.getLong("versionCode"), o.getString("versionName"), o.getInt("minSdk"),
                o.getString("tag"), apk.getString("name"), apk.getLong("size"), apk.getString("sha256").lowercase(),
                o.optString("notes").take(1500), channel)
        }
    }
}

/** Streaming validation also protects a cached APK from partial downloads and stale metadata. */
object UpdateFiles {
    fun verify(file: File, update: AppUpdate): Boolean {
        if (!file.isFile || file.length() != update.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buf = ByteArray(32768); while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) } == update.sha256
    }
    fun receive(input: InputStream, destination: File, update: AppUpdate, progress: (Int) -> Unit = {}) {
        val partial = File(destination.parentFile, destination.name + ".part")
        destination.parentFile?.mkdirs()
        try {
            partial.outputStream().use { output ->
                val buffer = ByteArray(32768); var total = 0L; var last = -1
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw IOException("下载已取消")
                    val n = input.read(buffer); if (n < 0) break
                    total += n
                    if (total > update.size) throw IOException("安装包长度异常")
                    output.write(buffer, 0, n)
                    val percent = (total * 100 / update.size).toInt()
                    if (percent != last) { last = percent; progress(percent) }
                }
                output.fd.sync()
            }
            if (!verify(partial, update)) throw IOException("安装包校验未通过，请重新下载")
            if (!partial.renameTo(destination)) throw IOException("无法保存安装包")
        } finally { partial.delete() }
    }
}

class AppUpdateRepository(private val channel: AppUpdateChannel = AppUpdateChannel.LEGACY) : java.io.Closeable {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES).addNetworkInterceptor { chain ->
            val url = chain.request().url
            // Check every redirect as well as the initial fixed channel source.
            if (!channel.permits(url))
                throw IOException("更新下载地址不受支持")
            chain.proceed(chain.request())
        }.build()
    private fun request(url: String): Request {
        val request = Request.Builder().url(url).header("User-Agent", "HongguoTV-Updater")
            .header("Cache-Control", "no-cache").build()
        if (!channel.permits(request.url)) throw IOException("更新下载地址不受支持")
        return request
    }
    private inline fun <T> sources(urls: List<String>, action: (String) -> T): T {
        var failure: IOException? = null
        for (url in urls) {
            if (Thread.currentThread().isInterrupted) throw IOException("更新已取消", failure)
            try { return action(url) } catch (e: IOException) { failure = e }
        }
        throw failure ?: IOException("更新失败，请稍后重试")
    }
    fun latest(): AppUpdate = sources(channel.manifestUrls) { url ->
        val call = http.newCall(request(url))
        call.timeout().timeout(25, TimeUnit.SECONDS)
        call.execute().use { response ->
            if (!response.isSuccessful) throw IOException(if (response.code == 404) "尚无可用的自动更新版本" else "无法检查更新，请稍后重试")
            val bytes = response.body?.byteStream()?.use { input ->
                val out = ByteArrayOutputStream(); val buffer = ByteArray(4096)
                while (true) { val n = input.read(buffer); if (n < 0) break; if (out.size() + n > 65536) throw IOException("更新信息过大"); out.write(buffer, 0, n) }
                out.toByteArray()
            } ?: throw IOException("更新信息为空")
            try { AppUpdate.parse(bytes.toString(Charsets.UTF_8), channel) } catch (_: Exception) { throw IOException("更新信息格式不正确") }
        }
    }
    fun download(update: AppUpdate, file: File, progress: (Int) -> Unit) {
        if (update.channel != channel) throw IOException("更新渠道不匹配")
        if (UpdateFiles.verify(file, update)) return
        sources(listOfNotNull(update.downloadUrl, channel.mirror?.let { "$it/releases/${update.tag}/${update.fileName}" })) { url ->
            http.newCall(request(url)).execute().use { response ->
                if (!response.isSuccessful) throw IOException("安装包下载失败，请稍后重试")
                val body = response.body ?: throw IOException("安装包为空")
                if (body.contentLength() >= 0 && body.contentLength() != update.size) throw IOException("安装包长度异常")
                body.byteStream().use { UpdateFiles.receive(it, file, update, progress) }
            }
        }
    }
    fun cancel() = http.dispatcher.cancelAll()
    override fun close() { cancel(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
}
