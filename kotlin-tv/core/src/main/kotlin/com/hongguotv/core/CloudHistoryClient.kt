// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigInteger
import java.net.URI
import java.util.concurrent.TimeUnit

class CloudMetadataUnavailableException: IOException("剧集资料待确认，进度已保留")
class CloudClockException: IOException("电视时间不准确，请校准后重试")
class CloudIdentityException: IOException("红果云端账号验证未通过")
object CloudSyncDiagnostics {
    private val approved = setOf(
        "云端账号响应为空", "云端账号响应过大", "云端账号响应无效", "云端未确认同步，请重试",
        "云端历史缺少数据", "云端历史缺少列表", "云端历史分页状态无效", "云端历史分页重复",
        "云端历史条目无效", "云端历史编号无效", "云端历史进度无效", "云端历史分页未完整读取",
        "云端进度缺少列表", "云端进度条目无效", "云端播放位置无效", "剧集资料响应无效",
        "播放进度无效", "本机记录保存失败", "登录账号已变化，请重新扫码",
    )
    fun describe(problem: Throwable): String = when (problem) {
        is java.net.SocketTimeoutException -> "网络请求超时"
        is java.net.UnknownHostException -> "无法解析服务器地址"
        is javax.net.ssl.SSLException -> "安全连接失败"
        is IOException -> problem.message?.takeIf { it in approved || it.matches(Regex("云端账号接口连接失败（HTTP [0-9]{3}）")) ||
            it.matches(Regex("云端(历史|进度|系列)接口错误（代码 -?[0-9]{1,10}）")) } ?: "网络或数据异常"
        else -> "数据处理异常"
    }
}

/** Official history endpoints; session cookies never follow redirects or leave the fixed API host. */
class CloudHistoryClient(
    private val session: AccountSession,
    http: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build(),
) {
    private val http = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false).build()
    private val signer = Signer()
    private val host = "https://api5-normal-lq.fqnovel.com"
    private val permitted = setOf("/passport/account/info/v2/", "/reading/bookapi/plan/v", "/novel/player/multi_video_detail/preload/v1", "/reading/bookapi/read_history/list/v", "/reading/bookapi/read_progress/get/v",
        "/reading/bookapi/read_history/update/v", "/reading/bookapi/read_progress/upload/v")
    private val idPattern = Regex("[1-9][0-9]{0,29}")
    private var verified = false
    private fun confirmedZero(value: Any?): Boolean = when (value) {
        is Number -> value.toString().toBigDecimalOrNull()?.compareTo(java.math.BigDecimal.ZERO) == 0
        is String -> value == "0"
        else -> false
    }

    private fun call(path: String, query: Map<String, String> = emptyMap(), value: JSONObject? = null): JSONObject {
        require(path in permitted)
        val values = session.device.toMutableMap()
        listOf("version_code", "manifest_version_code", "update_version_code", "pv_player").forEach { values[it] = "73532" }
        values["version_name"] = "7.3.5.32"
        values.putAll(query)
        val bytes = value?.toString()?.toByteArray(Charsets.UTF_8)
        val signed = signer.sign(host + path, values, bytes)
        val request = Request.Builder().url(signed.url)
        signed.headers.forEach { (key, header) -> request.header(key, header) }
        request.header("User-Agent", VendorConstants.VIDEO_UA.replace("/71332", "/73532"))
            .header("Accept", "application/json").header("Cookie", session.cookieHeader)
            .header("x-ss-dp", "8662").header("x-tt-store-region", "cn").header("x-tt-store-region-src", "did")
        if (bytes != null) request.post(bytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
        return RequestScope.execute(http.newCall(request.build())) { response ->
            if (response.code != 200) throw IOException("云端账号接口连接失败（HTTP ${response.code}）")
            val serverTime = response.header("Date")?.let { header -> runCatching {
                java.time.ZonedDateTime.parse(header, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            }.getOrNull() }
            if (serverTime != null && kotlin.math.abs(System.currentTimeMillis() - serverTime) > 5 * 60_000) throw CloudClockException()
            val body = response.body ?: throw IOException("云端账号响应为空")
            val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            body.byteStream().use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (output.size() + n > 4 * 1024 * 1024) throw IOException("云端账号响应过大")
                    output.write(buffer, 0, n)
                }
            }
            val result = try { JSONObject(output.toString("UTF-8")) } catch (_: Exception) { throw IOException("云端账号响应无效") }
            val data = result.optJSONObject("data")
            if (path == "/passport/account/info/v2/") {
                if (!result.optString("message").equals("success", true) || data == null) throw CloudIdentityException()
            } else if (!confirmedZero(result.opt("code")) || (data?.has("error_code") == true && !confirmedZero(data.opt("error_code")))) {
                val value = if (!confirmedZero(result.opt("code"))) result.opt("code") else data?.opt("error_code")
                val code = value?.toString()?.takeIf { it.matches(Regex("-?[0-9]{1,10}")) }
                val operation = when {
                    path.contains("read_history") -> "历史"
                    path.contains("read_progress") -> "进度"
                    else -> "系列"
                }
                if (code == null) throw IOException("云端未确认同步，请重试")
                throw IOException("云端${operation}接口错误（代码 $code）")
            }
            result
        }
    }

    private fun verifyIdentity() {
        if (verified) return
        val profile = call("/passport/account/info/v2/").optJSONObject("data") ?: throw CloudIdentityException()
        val uid = profile.str("user_id_str").ifBlank { profile.str("user_id") }
        if (uid != session.userId) throw CloudIdentityException()
        verified = true
    }

    private fun epoch(value: Long) = if (value in 1..99_999_999_999L) value * 1000 else value
    private fun cover(value: String): String = runCatching {
        val clean = LibraryBackup.cleanCover(cleanUrl(value))
        val url = URI(clean)
        if (url.port in listOf(-1, 443)) clean else ""
    }.getOrDefault("")

    private fun metadata(row: JSONObject): CloudSeriesMetadata? {
        if (!row.has("is_multi_season") || !row.has("season_index") || !row.has("episode_cnt")) return null
        val multi = when (val value = row.opt("is_multi_season")) {
            is Boolean -> value
            is Number -> when (value.toInt()) { 0 -> false; 1 -> true; else -> return null }
            else -> return null
        }
        return runCatching { CloudSeriesMetadata(row.getInt("episode_cnt"), row.getInt("season_index"), multi) }.getOrNull()
    }

    private fun seriesMetadata(id: String): CloudSeriesMetadata? {
        val query = linkedMapOf("scene" to "10", "from" to "single_col_same_ip_series", "offset" to "0", "limit" to "0",
            "total_chapter_num" to "0", "current_chapter_num" to "0", "post_id" to "0", "is_horizontal_screen" to "false",
            "bookstore_tab" to "0", "bookstore_tab_type" to "0", "book_id" to id, "filter_ids" to id)
        val blocks = call("/reading/bookapi/plan/v", query).optJSONArray("data") ?: throw IOException("剧集资料响应无效")
        var hasSeasonRows = false
        for (i in 0 until blocks.length()) {
            val block = blocks.optJSONObject(i) ?: throw IOException("剧集资料响应无效")
            if (!block.has("video_data")) continue
            val rows = block.optJSONArray("video_data") ?: throw IOException("剧集资料响应无效")
            if (rows.length() > 0) hasSeasonRows = true
            val valid = (0 until rows.length()).map { rows.optJSONObject(it) ?: throw IOException("剧集资料响应无效") }
            val ids = valid.map { it.str("series_id_str").ifBlank { it.str("series_id") } }.filter(idPattern::matches).toSet()
            if (ids.size < 2) continue
            val target = valid.firstOrNull { it.str("series_id_str").ifBlank { it.str("series_id") } == id } ?: continue
            val count = target.optInt("episode_cnt", target.optInt("episode_total_cnt", 0))
            return runCatching { CloudSeriesMetadata(count, target.getInt("season_index"), true) }.getOrNull()
        }
        if (blocks.length() == 0 || hasSeasonRows) return null
        val biz = JSONObject().put("detail_page_version", 0).put("disable_digg_stat", false)
            .put("need_all_video_definition", false).put("need_mp4_align", false).put("source", 7)
            .put("use_os_player", false).put("use_server_dns", false)
        val detail = call("/novel/player/multi_video_detail/preload/v1",
            value = JSONObject().put("dr_scene", "preload").put("series_id", id).put("biz_param", biz))
            .optJSONObject("data")?.optJSONObject(id)?.optJSONObject("video_data") ?: return null
        val detailId = detail.str("series_id_str").ifBlank { detail.str("series_id") }
        if (detailId != id || (detail.has("season_index") && detail.opt("season_index") !in listOf(0, "0"))) return null
        if (detail.has("is_multi_season") && detail.opt("is_multi_season") !in listOf(false, 0)) return null
        return runCatching { CloudSeriesMetadata(detail.getInt("episode_cnt"), 0, false) }.getOrNull()
    }

    private fun progressBatch(batch: List<String>): List<JSONObject> {
        val numbers = JSONArray()
        batch.forEach { numbers.put(BigInteger(it)) }
        val result = call("/reading/bookapi/read_progress/get/v", value = JSONObject().put("book_ids", JSONObject().put("2", numbers)))
        val data = result.optJSONArray("video_data") ?: result.optJSONObject("data")?.optJSONArray("video_data")
            ?: throw IOException("云端进度缺少列表")
        return (0 until data.length()).map { data.optJSONObject(it) ?: throw IOException("云端进度条目无效") }
    }

    fun readHistory(): List<BackupProgress> {
        verifyIdentity()
        val rows = linkedMapOf<String, BackupProgress>()
        val seenPages = mutableSetOf<String>()
        var offset = 0
        var complete = false
        for (page in 0 until 200) {
            RequestScope.ensureActive()
            val data = call("/reading/bookapi/read_history/list/v", linkedMapOf("limit" to "0", "offset" to offset.toString(),
                "book_type" to "2", "full_field" to "false", "query_soft_deleted" to "false", "last_min_read_timestamp_ms" to "0",
                "is_first_load" to (offset == 0).toString())).optJSONObject("data") ?: throw IOException("云端历史缺少数据")
            val list = data.optJSONArray("data_list") ?: throw IOException("云端历史缺少列表")
            val more = when (val flag = data.opt("has_more")) {
                true, 1, "true", "1" -> true
                false, 0, "false", "0" -> false
                else -> throw IOException("云端历史分页状态无效")
            }
            if (list.length() > 0 && !seenPages.add(digest("SHA-256", list.toString().toByteArray()).hex())) throw IOException("云端历史分页重复")
            for (i in 0 until list.length()) {
                val row = list.optJSONObject(i) ?: throw IOException("云端历史条目无效")
                if (row.optBoolean("is_delete", false)) continue
                val id = row.str("book_id_str").ifBlank { row.str("book_id") }
                if (!idPattern.matches(id)) throw IOException("云端历史编号无效")
                val index = row.optInt("vid_index", 0)
                val timestamp = epoch(row.optLong("read_timestamp_ms", 0))
                if (index !in 1..10000 || timestamp <= 0) throw IOException("云端历史进度无效")
                val episode = row.str("vid").takeIf { idPattern.matches(it) }.orEmpty()
                val title = org.jsoup.Jsoup.parse(row.str("book_name").ifBlank { row.str("series_title") }).text().ifBlank { "剧集 $id" }.take(200)
                val series = Series(id, title, cover(row.str("thumb_url").ifBlank { row.str("series_cover_url") }), badge = row.optInt("episode_cnt").takeIf { it > 0 }?.let { "全 $it 集" }.orEmpty())
                val position = row.optLong("current_play_position", 0).coerceIn(0, 604_800_000)
                // The history duration can describe the whole series, not the current episode.
                val item = BackupProgress(series, episode, index - 1, position, 0, false, timestamp, metadata(row))
                if (timestamp > (rows[id]?.updatedAt ?: -1)) rows[id] = item
            }
            if (!more) { complete = true; break }
            if (list.length() == 0 || page == 199) throw IOException("云端历史分页未完整读取")
            val next = data.optInt("next_offset", 0)
            offset = if (next > offset) next else offset + 500
        }
        if (!complete) throw IOException("云端历史分页未完整读取")
        val ids = rows.keys.toList()
        for (batch in ids.chunked(200)) {
            for (item in progressBatch(batch)) {
                val id = item.str("series_id")
                val previous = rows[id] ?: continue
                val index = item.optInt("vid_index", 0)
                val time = epoch(item.optLong("last_watch_time", 0))
                if (index in 1..10000 && time > previous.updatedAt) {
                    val changedEpisode = index - 1 != previous.episodeIndex
                    val episode = item.str("vid").ifBlank { item.str("item_id") }.takeIf(idPattern::matches)
                        ?: if (changedEpisode) "" else previous.episodeId
                    val key = listOf("current_play_time", "current_play_position").firstOrNull { item.has(it) && !item.isNull(it) }
                    val position = if (key == null) {
                        if (changedEpisode) 0 else previous.position
                    } else {
                        val value = item.opt(key)
                        if (value !is Number || value.toDouble() != value.toLong().toDouble() || value.toLong() !in 0..604_800_000)
                            throw IOException("云端播放位置无效")
                        value.toLong()
                    }
                    rows[id] = previous.copy(episodeIndex = index - 1, episodeId = episode,
                        position = position, duration = if (changedEpisode) 0 else previous.duration, updatedAt = time)
                }
            }
        }
        return rows.values.sortedByDescending { it.updatedAt }
    }

    fun upload(progress: BackupProgress) {
        if (!idPattern.matches(progress.series.id) || !idPattern.matches(progress.episodeId) || progress.episodeIndex !in 0..9999 ||
            progress.position !in 0..604_800_000 || progress.duration !in 0..604_800_000 || progress.updatedAt <= 0) throw IOException("播放进度无效")
        if (progress.updatedAt > System.currentTimeMillis() + 5 * 60_000) throw CloudClockException()
        verifyIdentity()
        val metadata = progress.cloudMetadata ?: seriesMetadata(progress.series.id) ?: throw CloudMetadataUnavailableException()
        val history = JSONObject().put("book_id", BigInteger(progress.series.id)).put("book_type", 2)
            .put("vid", BigInteger(progress.episodeId)).put("vid_index", progress.episodeIndex + 1).put("chapter_index", 0)
            .put("current_play_position", progress.position).put("player_accumulate_total_time", progress.position)
            .put("read_timestamp_ms", progress.updatedAt).put("update_timestamp_ms", progress.updatedAt)
            .put("is_delete", false).put("is_interactive_game", false).put("is_listen_mode", false).put("is_multi_season", if (metadata.multiSeason) 1 else 0)
            .put("season_index", metadata.seasonIndex).put("duration", progress.duration).put("episode_cnt", metadata.episodeCount).put("digged_count", 0)
            .put("user_digg", false).put("origin_novel_book_id", 0).put("recent_reads", 0).put("retain_video_play_time", 0)
            .put("series_play_cnt", 0).put("tone_id", 0).put("use_soft_delete", false).put("meet_guide_comment_tag", false)
            .put("user_playlet_comment_flag", false)
        call("/reading/bookapi/read_history/update/v", value = JSONObject().put("update_datas", JSONArray().put(history)))
        val item = JSONObject().put("book_id", progress.series.id).put("book_type", 2).put("item_id", progress.episodeId)
            .put("vid_index", progress.episodeIndex + 1).put("current_play_time", progress.position)
            .put("player_cumulative_total_duration", progress.position).put("read_timestamp_ms", progress.updatedAt)
            .put("progress_type", 0).put("channel_id", 0).put("cur_channel_id", 0).put("check_timestamp", false)
            .put("is_listen_mode", false).put("is_local_book", false).put("listen_and_read", false)
            .put("page_index", 0).put("page_progress_rate", 0).put("paragraph_offset", 0).put("tone_id", 0)
        call("/reading/bookapi/read_progress/upload/v", value = JSONObject().put("books", JSONArray().put(item)))
    }
}
