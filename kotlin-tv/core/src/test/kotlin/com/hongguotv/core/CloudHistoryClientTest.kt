package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CloudHistoryClientTest {
    private val device = VendorConstants.device + mapOf("device_id" to "333", "iid" to "444", "cdid" to "11111111-2222-3333-4444-555555555555")
    private val session = AccountSession("123", "测试", "sessionid=fixture-session", device)
    private fun response(request: Request, body: String) = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        .code(200).message("fixture").body(body.toResponseBody()).build()
    private fun body(request: Request): JSONObject { val b = Buffer(); request.body!!.writeTo(b); return JSONObject(b.readUtf8()) }
    private fun client(reply: (Request) -> String) = OkHttpClient.Builder().addInterceptor { chain ->
        val r = chain.request()
        assertEquals("api5-normal-lq.fqnovel.com", r.url.host)
        assertEquals("sessionid=fixture-session", r.header("Cookie"))
        assertEquals("333", r.url.queryParameter("device_id"))
        assertFalse(r.header("x-medusa").isNullOrBlank())
        response(r, if(r.url.encodedPath=="/passport/account/info/v2/") """{"message":"success","data":{"user_id_str":"123"}}""" else reply(r))
    }.build()

    @Test
    fun cloudHistoryMustBelongToTheScannedAccountBeforeReadingOrWriting() {
        val seen=mutableListOf<String>()
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request()
            seen+=request.url.encodedPath
            val body=if(request.url.encodedPath=="/passport/account/info/v2/") """{"message":"success","data":{"user_id_str":"456"}}"""
                else """{"code":0,"data":{"data_list":[],"has_more":false}}"""
            response(request,body)
        }.build()
        assertThrows(IOException::class.java) { CloudHistoryClient(session,http).readHistory() }
        assertEquals(listOf("/passport/account/info/v2/"),seen)
    }

    @Test
    fun syncDiagnosticsRevealOnlyApprovedErrorCategories() {
        assertEquals("云端历史进度无效", CloudSyncDiagnostics.describe(IOException("云端历史进度无效")))
        assertEquals("网络请求超时", CloudSyncDiagnostics.describe(java.net.SocketTimeoutException("sensitive URL")))
        assertEquals("网络或数据异常", CloudSyncDiagnostics.describe(IOException("private credential and URL")))
    }

    @Test
    fun officialBusinessFailureReportsOnlyItsOperationAndNumericCode() {
        val http=client { """{"code":101000}""" }
        val error=assertThrows(IOException::class.java) { CloudHistoryClient(session,http).readHistory() }
        assertEquals("云端历史接口错误（代码 101000）", CloudSyncDiagnostics.describe(error))
    }

    @Test
    fun rejectedProgressBatchStopsTheSnapshotWithoutRepeatedRequests() {
        var requests=0
        val http=client { request ->
            when(request.url.encodedPath) {
                "/reading/bookapi/read_history/list/v" -> """{"code":0,"data":{"data_list":[{"book_id_str":"123","book_name":"一","vid":"456","vid_index":1,"read_timestamp_ms":1700000000000},{"book_id_str":"124","book_name":"二","vid":"457","vid_index":1,"read_timestamp_ms":1700000001000}],"has_more":false}}"""
                "/reading/bookapi/read_progress/get/v" -> {
                    requests++
                    """{"code":100103}"""
                }
                else -> error("unexpected path")
            }
        }
        assertThrows(IOException::class.java) { CloudHistoryClient(session,http).readHistory() }
        assertEquals(1,requests)
    }

    @Test
    fun readsEveryHistoryPageWithoutLosingLargeIdsOrWatchPosition() {
        val offsets = mutableListOf<String?>()
        val http = client { request ->
            when (request.url.encodedPath) {
                "/reading/bookapi/read_history/list/v" -> {
                    val offset = request.url.queryParameter("offset"); offsets += offset
                    if (offset == "0") """{"code":0,"data":{"data_list":[{"book_id_str":"7690163276214176792","book_name":"第一部","vid":"7690163276214176793","vid_index":7,"current_play_position":12500,"duration":60000,"read_timestamp_ms":1700000000000}],"has_more":true,"next_offset":500}}"""
                    else """{"code":0,"data":{"data_list":[{"book_id_str":"7690163276214176794","book_name":"第二部","vid":"7690163276214176795","vid_index":2,"current_play_position":9000,"read_timestamp_ms":1700000001000}],"has_more":false}}"""
                }
                "/reading/bookapi/read_progress/get/v" -> {
                    val ids = body(request).getJSONObject("book_ids").getJSONArray("2")
                    assertEquals("7690163276214176792", ids.get(0).toString())
                    """{"code":0,"video_data":[]}"""
                }
                else -> error("unexpected path")
            }
        }
        val items = CloudHistoryClient(session, http).readHistory()
        assertEquals(listOf("0", "500"), offsets)
        assertEquals(2, items.size)
        val first = items.first { it.series.id == "7690163276214176792" }
        assertEquals("7690163276214176793", first.episodeId)
        assertEquals(6, first.episodeIndex)
        assertEquals(12_500L, first.position)
        assertEquals(1_700_000_000_000L, first.updatedAt)
    }

    @Test
    fun seriesDurationIsNotDisplayedAsTheCurrentEpisodeDuration() {
        val http=client { request ->
            if(request.url.encodedPath.endsWith("/list/v"))
                """{"code":0,"data":{"data_list":[{"book_id_str":"123","book_name":"剧","vid":"456","vid_index":1,"current_play_position":15000,"duration":12620350,"episode_cnt":78,"read_timestamp_ms":1700000000000}],"has_more":false}}"""
            else """{"code":0,"video_data":[]}"""
        }
        val progress=CloudHistoryClient(session,http).readHistory().single()
        assertEquals(15_000L,progress.position)
        assertEquals(0L,progress.duration)
    }

    @Test
    fun newerPhonePositionReplacesTheOlderPositionWithinTheSameEpisode() {
        val http=client { request ->
            if(request.url.encodedPath.endsWith("/list/v"))
                """{"code":0,"data":{"data_list":[{"book_id_str":"123","book_name":"剧","vid":"456","vid_index":2,"current_play_position":1000,"duration":60000,"read_timestamp_ms":1700000000000}],"has_more":false}}"""
            else """{"code":0,"video_data":[{"series_id":"123","vid_index":2,"last_watch_time":1700000001,"current_play_time":45000}]}"""
        }
        val progress=CloudHistoryClient(session,http).readHistory().single()
        assertEquals(45_000L,progress.position)
        assertEquals("456",progress.episodeId)
    }

    @Test
    fun malformedProgressRowsCannotBeTreatedAsACompleteSnapshot() {
        val http=client { request ->
            if(request.url.encodedPath.endsWith("/list/v"))
                """{"code":0,"data":{"data_list":[{"book_id_str":"123","book_name":"剧","vid":"456","vid_index":1,"read_timestamp_ms":1700000000000}],"has_more":false}}"""
            else """{"code":0,"video_data":[null]}"""
        }
        assertThrows(IOException::class.java) { CloudHistoryClient(session,http).readHistory() }
    }

    @Test
    fun malformedHistoryCannotBeTreatedAsACompleteSnapshot() {
        val http=client { """{"code":0,"data":{"data_list":[{"book_id_str":"123","book_name":"剧","vid_index":0,"read_timestamp_ms":0}],"has_more":false}}""" }
        assertThrows(IOException::class.java) { CloudHistoryClient(session,http).readHistory() }
    }

    @Test
    fun uploadRequiresBothOfficialWritesToAcknowledge() {
        val seen = mutableListOf<String>()
        val p = BackupProgress(Series("7690163276214176792", "剧"), "7690163276214176793", 6, 12500, 60000, false, 1_700_000_000_000, CloudSeriesMetadata(70, 1, true))
        val http = client { request ->
            seen += request.url.encodedPath
            val json = body(request)
            when (request.url.encodedPath) {
                "/reading/bookapi/read_history/update/v" -> {
                    val row = json.getJSONArray("update_datas").getJSONObject(0)
                    assertEquals(p.series.id, row.get("book_id").toString())
                    assertEquals(p.episodeId, row.get("vid").toString())
                    assertEquals(7, row.getInt("vid_index"))
                    assertEquals(12500L, row.getLong("current_play_position"))
                    """{"code":0}"""
                }
                "/reading/bookapi/read_progress/upload/v" -> """{"code":101000}"""
                else -> error("unexpected path")
            }
        }
        assertThrows(IOException::class.java) { CloudHistoryClient(session, http).upload(p) }
        assertEquals(listOf("/reading/bookapi/read_history/update/v", "/reading/bookapi/read_progress/upload/v"), seen)
    }

    @Test
    fun incompleteCloudPaginationNeverLooksLikeASuccessfulSnapshot() {
        val http = client { """{"code":0,"data":{"data_list":[],"has_more":true}}""" }
        assertThrows(IOException::class.java) { CloudHistoryClient(session, http).readHistory() }
    }

    @Test
    fun numericPaginationFlagCannotCauseAPrematureSnapshot() {
        var pages = 0
        val http = client { request ->
            if (request.url.encodedPath.endsWith("/list/v")) {
                pages++
                if (pages == 1) """{"code":0,"data":{"data_list":[{"book_id_str":"123","book_name":"剧","vid":"456","vid_index":1,"read_timestamp_ms":1700000000000}],"has_more":1,"next_offset":500}}"""
                else """{"code":0,"data":{"data_list":[],"has_more":0}}"""
            } else """{"code":0,"video_data":[]}"""
        }
        CloudHistoryClient(session, http).readHistory()
        assertEquals(2, pages)
    }

    @Test
    fun malformedPaginationFlagCannotLookLikeACompleteSnapshot() {
        val http = client { """{"code":0,"data":{"data_list":[],"has_more":{}}}""" }
        assertThrows(IOException::class.java) { CloudHistoryClient(session, http).readHistory() }
    }

    @Test
    fun malformedBusinessStatusCannotAcknowledgeAWrite() {
        val p=BackupProgress(Series("123","剧"),"456",0,1000,60000,false,1_700_000_000_000,CloudSeriesMetadata(10,1,false))
        val http=client { """{"code":0.5,"data":{"error_code":"invalid"}}""" }
        assertThrows(IOException::class.java) { CloudHistoryClient(session,http).upload(p) }
    }

    @Test
    fun serverClockDisagreementStopsTheSnapshotBeforeAnyUpload() {
        var writes=0
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            if(chain.request().method=="POST") writes++
            response(chain.request(),"""{"code":0,"data":{"data_list":[],"has_more":false}}""")
                .newBuilder().header("Date","Wed, 01 Jan 2020 00:00:00 GMT").build()
        }.build()
        assertThrows(IOException::class.java) { CloudHistoryClient(session,http).readHistory() }
        assertEquals(0,writes)
    }

    @Test
    fun aCheckpointInTheFutureCannotBeUploaded() {
        val p=BackupProgress(Series("123","剧"),"456",0,1000,60000,false,System.currentTimeMillis()+86_400_000,CloudSeriesMetadata(10,1,false))
        var sent=false
        val http=client { sent=true; """{"code":0}""" }
        assertThrows(IOException::class.java) { CloudHistoryClient(session,http).upload(p) }
        assertFalse(sent)
    }
    @Test
    fun cloudCoverCannotPointAtTheLocalNetwork() {
        val http = client { request ->
            if (request.url.encodedPath.endsWith("/list/v"))
                """{"code":0,"data":{"data_list":[{"book_id_str":"123","book_name":"剧","vid":"456","vid_index":1,"read_timestamp_ms":1700000000000,"thumb_url":"https://192.168.1.1/private"}],"has_more":false}}"""
            else """{"code":0,"video_data":[]}"""
        }
        assertEquals("", CloudHistoryClient(session, http).readHistory().single().series.cover)
    }

    @Test
    fun uploadDoesNotReplaceUnknownSeriesMetadataWithZeros() {
        val p = BackupProgress(Series("123", "剧"), "456", 0, 1000, 60000, false, 1_700_000_000_000)
        var writes = 0
        val http = client { request ->
            if(request.url.encodedPath.endsWith("/update/v") || request.url.encodedPath.endsWith("/upload/v")) writes++
            """{"code":0,"data":[]}"""
        }
        assertThrows(IOException::class.java) { CloudHistoryClient(session, http).upload(p) }
        assertEquals(0, writes)
    }

    @Test
    fun newStandaloneSeriesUsesVerifiedOfficialDetailsBeforeUploading() {
        val p=BackupProgress(Series("123","单季剧"),"456",0,1000,60000,false,1_700_000_000_000)
        var uploaded=false
        val http=client { request ->
            when(request.url.encodedPath) {
                "/reading/bookapi/plan/v" -> """{"code":0,"data":[{"cell_name":"recommendations"}]}"""
                "/novel/player/multi_video_detail/preload/v1" -> """{"code":0,"data":{"123":{"video_data":{"series_id":"123","episode_cnt":78}}}}"""
                "/reading/bookapi/read_history/update/v" -> {
                    val row=body(request).getJSONArray("update_datas").getJSONObject(0)
                    assertEquals(0,row.getInt("is_multi_season"))
                    assertEquals(0,row.getInt("season_index"))
                    assertEquals(78,row.getInt("episode_cnt"))
                    uploaded=true
                    """{"code":0}"""
                }
                "/reading/bookapi/read_progress/upload/v" -> """{"code":0}"""
                else -> error("unexpected request")
            }
        }
        CloudHistoryClient(session,http).upload(p)
        assertTrue(uploaded)
    }

    @Test
    fun malformedSeasonMarkerCannotBeMistakenForASingleSeason() {
        val p=BackupProgress(Series("123","剧"),"456",0,1000,60000,false,1_700_000_000_000)
        var uploads=0
        val http=client { request ->
            when(request.url.encodedPath) {
                "/reading/bookapi/plan/v" -> """{"code":0,"data":[{"cell_name":"recommendations"}]}"""
                "/novel/player/multi_video_detail/preload/v1" -> """{"code":0,"data":{"123":{"video_data":{"series_id":"123","episode_cnt":78,"season_index":"invalid"}}}}"""
                else -> { uploads++; """{"code":0}""" }
            }
        }
        assertThrows(IOException::class.java) { CloudHistoryClient(session,http).upload(p) }
        assertEquals(0,uploads)
    }

    @Test
    fun mismatchedStandaloneDetailCannotBeUploaded() {
        val p=BackupProgress(Series("123","剧"),"456",0,1000,60000,false,1_700_000_000_000)
        var uploads=0
        val http=client { request ->
            when(request.url.encodedPath) {
                "/reading/bookapi/plan/v" -> """{"code":0,"data":[{"cell_name":"recommendations"}]}"""
                "/novel/player/multi_video_detail/preload/v1" -> """{"code":0,"data":{"123":{"video_data":{"series_id":"999","episode_cnt":78}}}}"""
                else -> { uploads++; """{"code":0}""" }
            }
        }
        assertThrows(IOException::class.java) { CloudHistoryClient(session,http).upload(p) }
        assertEquals(0,uploads)
    }

    @Test
    fun newSeriesUsesOfficialSeasonDetailsBeforeUploading() {
        val p = BackupProgress(Series("123", "剧"), "456", 0, 1000, 60000, false, 1_700_000_000_000)
        val paths = mutableListOf<String>()
        val http = client { request ->
            paths += request.url.encodedPath
            when (request.url.encodedPath) {
                "/reading/bookapi/plan/v" -> """{"code":0,"data":[{"video_data":[{"series_id":"123","season_index":15,"episode_cnt":300},{"series_id":"124","season_index":16,"episode_cnt":5}]}]}"""
                "/reading/bookapi/read_history/update/v" -> {
                    val row=body(request).getJSONArray("update_datas").getJSONObject(0)
                    assertEquals(1,row.getInt("is_multi_season"))
                    assertEquals(15,row.getInt("season_index"))
                    assertEquals(300,row.getInt("episode_cnt"))
                    """{"code":0}"""
                }
                "/reading/bookapi/read_progress/upload/v" -> """{"code":0}"""
                else -> error("unexpected path")
            }
        }
        CloudHistoryClient(session, http).upload(p)
        assertEquals(listOf("/reading/bookapi/plan/v","/reading/bookapi/read_history/update/v","/reading/bookapi/read_progress/upload/v"),paths)
    }

    @Test
    fun uploadPreservesSeriesMetadataReadFromCloud() {
        var observed = false
        val http = client { request ->
            when (request.url.encodedPath) {
                "/reading/bookapi/read_history/list/v" -> """{"code":0,"data":{"data_list":[{"book_id_str":"123","book_name":"剧","vid":"456","vid_index":1,"read_timestamp_ms":1700000000000,"is_multi_season":1,"season_index":15,"episode_cnt":70}],"has_more":false}}"""
                "/reading/bookapi/read_progress/get/v" -> """{"code":0,"video_data":[]}"""
                "/reading/bookapi/read_history/update/v" -> {
                    val row = body(request).getJSONArray("update_datas").getJSONObject(0)
                    assertEquals(1, row.getInt("is_multi_season"))
                    assertEquals(15, row.getInt("season_index"))
                    assertEquals(70, row.getInt("episode_cnt"))
                    observed = true
                    """{"code":0}"""
                }
                else -> """{"code":0}"""
            }
        }
        val api = CloudHistoryClient(session, http)
        val remote = api.readHistory().single()
        val state = CloudHistoryState("123")
        state.mergeRemote(listOf(remote))
        state.checkpoint(BackupProgress(remote.series, "456", 0, 5000, 60000, false, 1_700_000_000_100))
        val restored = CloudHistoryState.decode("123", state.encode())
        api.upload(restored.pending().single().progress)
        assertTrue(observed)
    }
}
