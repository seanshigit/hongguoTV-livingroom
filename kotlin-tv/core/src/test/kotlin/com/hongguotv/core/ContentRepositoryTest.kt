package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ContentRepositoryTest {
    @Test fun unavailableSupplementDoesNotStartAFullCatalogScan() {
        var categoryRequests=0
        val repo=repository { request ->
            when {
                request.url.host.contains("fqnovel") -> ""
                request.url.encodedPath=="/api/search/result/" && request.url.queryParameter("query")=="聚宝仙盆第十五季" ->
                    JSONObject().put("searchList",JSONArray().put(JSONObject().put("series_id","14")
                        .put("series_title","聚宝仙盆之杂灵根才是真BOSS第十四季").put("genre","205")))
                        .put("totalPages",1).put("hasMore",false).toString()
                request.url.encodedPath=="/api/search/result/" -> ""
                request.url.encodedPath.startsWith("/search/") -> websiteResults()
                else -> {
                    categoryRequests++
                    """<script>_ROUTER_DATA={"loaderData":{"category_${'$'}":{"recommendList":[],"pagination":{"pageNum":1,"totalPages":1}}}};</script>"""
                }
            }
        }
        val search=SearchCatalog({ keyword,page,type -> repo.search(keyword,page,type) },supplement={ keyword,page,type -> repo.searchSupplement(keyword,page,type) })
        assertEquals("14",search.page("聚宝仙盆第十五季",1,ContentType.COMIC).items.single().id)
        assertEquals(0,categoryRequests)
    }
    @Test fun upstreamPlaybackErrorKeepsItsCodeForRecoveryAndDiagnostics() {
        val repo=repository { """{"Code":110001,"Message":"未知异常"}""" }
        val error=assertThrows(IOException::class.java) { repo.stream("123") }
        assertTrue(error.message.orEmpty().contains("110001"))
    }
    @Test fun playbackReusesOneRegisteredIdentityAcrossRequests() {
        val visitors=mutableListOf<Pair<String?,String?>>()
        var registrations=0
        val repo=ContentRepository(repositoryHttp { request ->
            visitors+=request.url.queryParameter("iid") to request.url.queryParameter("device_id")
            """{"Code":110001}"""
        }, playbackSession=PlaybackDeviceSession {
            registrations++
            DeviceRegister.Registered("9007199254740993","9007199254741993")
        })
        repeat(2) { assertThrows(IOException::class.java) { repo.stream("123") } }
        assertEquals(1,registrations)
        assertEquals(visitors[0],visitors[1])
        assertTrue(visitors.all { (iid,device) -> iid=="9007199254741993" && device=="9007199254740993" })
    }

    @Test fun playbackNeverSubstitutesAnotherEpisodeFromTheResponse() {
        val repo=repository { request ->
            if(request.url.host=="example.com") """{"video_info":{"data":{"video_list":{"video_0":{"vheight":720,"main_url":"https://example.com/wrong-episode"}}}}}"""
            else """{"data":{"999":{"video_model":{"fallback_api":"https://example.com/stream"}}}}"""
        }
        assertThrows(IOException::class.java) { repo.stream("123") }
    }

    private fun streamRepository(vararg variants: Triple<Int,String,String>) = repository { request ->
        if(request.url.host=="example.com") {
            val rows=JSONObject()
            variants.forEachIndexed { index,(height,codec,url) -> rows.put("video_$index",JSONObject().put("vheight",height).put("codec_type",codec).put("main_url",url)) }
            JSONObject().put("video_info",JSONObject().put("data",JSONObject().put("video_list",rows))).toString()
        } else """{"data":{"123":{"video_model":{"fallback_api":"https://example.com/stream"}}}}"""
    }

    @Test fun incompatibleByteVc2DoesNotBecomeAudioOnlyPlayback() {
        val repo=streamRepository(Triple(720,"bytevc2","https://example.com/unsupported"),Triple(1080,"bytevc1","https://example.com/hevc"))
        val result=repo.stream("123",720)
        assertEquals("https://example.com/hevc",result.url)
        assertEquals("1080P（兼容资源）",result.quality)
    }

    @Test fun compatibleLowerQualityWinsBeforeHigherResolutionFallback() {
        val repo=streamRepository(Triple(720,"bytevc2","https://example.com/unsupported"),Triple(480,"h264","https://example.com/avc"),Triple(1080,"bytevc1","https://example.com/hevc"))
        assertEquals("480P",repo.stream("123",720).quality)
    }

    @Test fun streamKeepsOtherPlayableVariantsForStartupFailover() {
        val repo=streamRepository(Triple(720,"h264","https://example.com/720"),Triple(480,"h264","https://example.com/480"))
        val result=repo.stream("123",720)
        assertEquals("https://example.com/720",result.url)
        assertEquals(listOf("https://example.com/480"),result.alternatives.map { it.url })
    }

    @Test fun streamIncludesAndDeduplicatesSameQualityBackupUrls() {
        val repo=repository { request ->
            if(request.url.host=="example.com") {
                val row=JSONObject().put("vheight",720).put("codec_type","h264")
                    .put("main_url","https://example.com/main")
                    .put("backup_url","https://example.com/backup")
                    .put("backup_url_list",JSONArray().put("https://example.com/backup-2").put("https://example.com/main"))
                JSONObject().put("video_info",JSONObject().put("data",JSONObject().put("video_list",JSONObject().put("video_0",row)))).toString()
            } else """{"data":{"123":{"video_model":{"fallback_api":"https://example.com/stream"}}}}"""
        }
        val result=repo.stream("123",720)
        assertEquals(listOf("https://example.com/main","https://example.com/backup","https://example.com/backup-2"),
            result.candidates().map { it.url })
    }

    @Test fun streamTriesTheNextFallbackApiWhenTheFirstOneHasNoVideo() {
        val repo=repository { request ->
            if(request.url.host.contains("fqnovel")) {
                JSONObject().put("code",0).put("data",JSONObject().put("123",JSONObject().put("video_model",
                    JSONObject().put("fallback_api",JSONArray(listOf("https://example.com/bad","https://example.com/good")))))).toString()
            } else if(request.url.encodedPath=="/bad") {
                "{}"
            } else {
                val row=JSONObject().put("vheight",720).put("codec_type","h264").put("main_url","https://example.com/video")
                JSONObject().put("video_info",JSONObject().put("data",JSONObject().put("video_list",JSONObject().put("video_0",row)))).toString()
            }
        }
        assertEquals("https://example.com/video",repo.stream("123",720).url)
    }

    @Test fun streamRetriesAnEmptyProviderResponseWithAFreshSignedRequest() {
        var calls=0
        val repo=repository { request ->
            if(request.url.host.contains("fqnovel")) {
                calls++
                if(calls<3) "" else JSONObject().put("code",0).put("data",JSONObject().put("123",JSONObject().put("video_model",
                    JSONObject().put("fallback_api","https://example.com/good")))).toString()
            } else {
                val row=JSONObject().put("vheight",720).put("codec_type","h264").put("main_url","https://example.com/video")
                JSONObject().put("video_info",JSONObject().put("data",JSONObject().put("video_list",JSONObject().put("video_0",row)))).toString()
            }
        }
        assertEquals("https://example.com/video",repo.stream("123",720).url)
        assertEquals(3,calls)
    }

    @Test fun streamRetriesAValidProviderResponseThatHasNoVideoResources() {
        var fallbackCalls=0
        val repo=repository { request ->
            if(request.url.host.contains("fqnovel")) {
                JSONObject().put("code",0).put("data",JSONObject().put("123",JSONObject().put("video_model",
                    JSONObject().put("fallback_api","https://example.com/good")))).toString()
            } else {
                fallbackCalls++
                if(fallbackCalls<3) "{}" else {
                    val row=JSONObject().put("vheight",720).put("codec_type","h264").put("main_url","https://example.com/video")
                    JSONObject().put("video_info",JSONObject().put("data",JSONObject().put("video_list",JSONObject().put("video_0",row)))).toString()
                }
            }
        }
        assertEquals("https://example.com/video",repo.stream("123",720).url)
        assertEquals(3,fallbackCalls)
    }

    @Test fun entirelyIncompatibleSourceFailsBeforeStartingAudio() {
        val repo=streamRepository(Triple(720,"ByteVC2","https://example.com/unsupported"),Triple(480,"bvc2","https://example.com/unsupported2"))
        assertThrows(IOException::class.java) { repo.stream("123",720) }
    }

    @Test fun legacyMissingCodecLabelKeepsQualityLimit() {
        val repo=streamRepository(Triple(720,"","https://example.com/legacy"),Triple(1080,"bytevc1","https://example.com/hevc"))
        assertEquals("720P",repo.stream("123",720).quality)
    }

    @Test fun actualShortEdgeOverridesQualityLabelsForPortraitAndLandscapeVideo() {
        for(portrait in listOf(true,false)) {
            val repo=repository { request ->
                if(request.url.host=="example.com") {
                    val low=JSONObject().put("codec_type","h264").put("quality_desc","1080p")
                        .put("vwidth",if(portrait) 720 else 1280).put("vheight",if(portrait) 1280 else 720)
                        .put("main_url","https://example.com/720")
                    val high=JSONObject().put("codec_type","h264").put("quality_desc","720p")
                        .put("video_meta",JSONObject().put("width",if(portrait) 1080 else 1920)
                            .put("height",if(portrait) 1920 else 1080))
                        .put("main_url","https://example.com/1080")
                    JSONObject().put("video_info",JSONObject().put("data",JSONObject().put("video_list",
                        JSONObject().put("video_0",low).put("video_1",high)))).toString()
                } else """{"data":{"123":{"video_model":{"fallback_api":"https://example.com/stream"}}}}"""
            }
            val result=repo.stream("123",720)
            assertEquals("https://example.com/720",result.url)
            assertEquals("720P",result.quality)
        }
    }

    private fun repository(clock: () -> Long = System::currentTimeMillis, respond: (Request) -> String) = ContentRepository(
        repositoryHttp(respond),clock,PlaybackDeviceSession { DeviceRegister.Registered("12345","67890") })

    private fun repositoryHttp(respond: (Request) -> String) = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(respond(chain.request()).toResponseBody()).build()
        }.build()

    private fun rankPayload(page: Int = 1, totalPages: Int = 2) = JSONObject()
        .put("isSuccess",true)
        .put("rankList",JSONArray().put(JSONObject().put("seriesId","123").put("title","测试漫剧")
            .put("cover","https:\\u002F\\u002Fexample.com\\u002Fcover.jpg")
            .put("episodeCount",12).put("tags",JSONArray().put("玄幻"))))
        .put("pagination",JSONObject().put("pageNum",page).put("totalPages",totalPages))

    private fun script(name: String, args: JSONArray) =
        "<script data-fn-name=\"$name\" data-fn-args=\"${args.toString().replace("&","&amp;").replace("\"","&quot;")}\"></script>"

    private fun currentRank(payload: JSONObject) = script("mergeLoaderData",JSONArray()
        .put("rank_hot-comic-drama/page").put(JSONArray().put(JSONObject().put("key","content")
            .put("routerDataFnName","p").put("routerDataFnArgs",JSONArray().put(payload.toString())))))

    @Test fun comicRankingPreservesSourceRankHeatAndUpdateRatherThanRenumbering() {
        val payload=rankPayload(2,5)
        payload.getJSONArray("rankList").getJSONObject(0).put("rank",23).put("heatText","5746万热度")
        val repo=repository { request ->
            assertEquals("/rank/hot-comic-drama",request.url.encodedPath)
            assertEquals("2",request.url.queryParameter("page"))
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2,"updatedText":"9月22日已更新"}}};</script>"""+currentRank(payload)
        }
        val result=repo.comicRanking(2)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(23,"5746万热度"),result.ranking!!.positions["123"])
        assertEquals(5,result.ranking.totalPages)
        assertEquals("9月22日已更新",result.ranking.updatedText)
        assertTrue(result.hasMore)
        assertFalse(result.items.single().toJson().has("rank"))
    }

    @Test fun missingRankIsNotInventedAndLastPageStops() {
        val result=repository { currentRank(rankPayload(5,5)) }.comicRanking(5)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(null,""),result.ranking!!.positions["123"])
        assertFalse(result.hasMore)
    }

    @Test fun renderedRankingPreservesPageTwoRankAndHeat() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2,"updatedText":"今日已更新"}}};</script>
                <article aria-labelledby="rank-title-123"><span class="pc-badge-number-example">21</span>
                <h2 id="rank-title-123">漫剧</h2><p class="pc-metrics-example"><span>5746万热度</span></p></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama">1</a><span aria-current="page">2</span><a href="/rank/hot-comic-drama?page=5">5</a></nav>"""
        }
        val result=repo.comicRanking(2)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(21,"5746万热度"),result.ranking!!.positions["123"])
        assertEquals(5,result.ranking.totalPages)
        assertEquals("今日已更新",result.ranking.updatedText)
    }

    @Test fun rankingRejectsUnexpectedPageInsteadOfShowingWrongRanks() {
        val repo=repository { currentRank(rankPayload(1,5)) }
        assertThrows(IOException::class.java) { repo.comicRanking(2) }
        assertThrows(IllegalArgumentException::class.java) { repo.comicRanking(0) }
    }

    @Test fun renderedLastPageCountsCurrentPageWithoutAnAnchor() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":5}}};</script>
                <article aria-labelledby="rank-title-123"><span class="pc-badge-number-example">81</span><h2 id="rank-title-123">漫剧</h2></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama?page=4">4</a><span aria-current="page">5</span></nav>"""
        }
        val result=repo.comicRanking(5)
        assertEquals(5,result.ranking!!.totalPages)
        assertFalse(result.hasMore)
    }

    @Test fun comicHomeUsesDedicatedRankAndRealPagination() {
        val repo=repository { request ->
            assertEquals("/rank/hot-comic-drama",request.url.encodedPath)
            assertEquals("2",request.url.queryParameter("page"))
            currentRank(rankPayload(2,2))
        }
        val page=repo.home(2,ContentType.COMIC)
        assertEquals("测试漫剧",page.items.single().title)
        assertEquals("https://example.com/cover.jpg",page.items.single().cover)
        assertEquals("全 12 集",page.items.single().badge)
        assertFalse(page.hasMore)
    }

    @Test fun legacyRankIsStillSupported() {
        val repo=repository { script("r",JSONArray().put("rank_hot-comic-drama/page").put("content").put(rankPayload())) }
        assertTrue(repo.home(1,ContentType.COMIC).hasMore)
    }

    @Test fun comicHomeDoesNotUseUnrelatedRankData() {
        val repo=repository { script("r",JSONArray().put("rank_hot-real-drama/page").put("content").put(rankPayload())) }
        assertThrows(IOException::class.java) { repo.home(1,ContentType.COMIC) }
    }

    @Test fun rejectedComicRankRemainsAnError() {
        val repo=repository { currentRank(rankPayload().put("isSuccess",false)) }
        assertThrows(IOException::class.java) { repo.home(1,ContentType.COMIC) }
    }

    @Test fun renderedComicRankWorksWhenStreamedPayloadIsMissing() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2}}};</script>
                <article aria-labelledby="rank-title-123"><a href="/detail?series_id=123"><img src="https://example.com/a.jpg"></a>
                <h2 id="rank-title-123">测试 &amp; 漫剧</h2><p class="pc-description-example">简介</p><p class="pc-metrics-example">123万热度</p></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama?page=1">1</a><span aria-current="page">2</span><a href="/rank/hot-comic-drama?page=3">3</a></nav>"""
        }
        val page=repo.home(2,ContentType.COMIC)
        assertEquals("测试 & 漫剧",page.items.single().title)
        assertEquals("https://example.com/a.jpg",page.items.single().cover)
        assertTrue(page.hasMore)
    }

    @Test fun comicSearchCarriesCursorAndKeepsTypesAndQueriesSeparate() {
        val requests=mutableListOf<Request>()
        val repo=repository { request ->
            requests+=request
            JSONObject().put("code",0).put("search_tabs",JSONArray().put(searchTab(19,"999")
                .put("search_id","comic-session").put("passback","comic-cursor").put("next_offset",25))).toString()
        }
        repo.search("修仙",1,ContentType.COMIC)
        repo.search("修仙",2,ContentType.COMIC)
        assertEquals("comic-session",requests[1].url.queryParameter("search_id"))
        assertEquals("comic-cursor",requests[1].url.queryParameter("passback"))
        assertEquals("25",requests[1].url.queryParameter("offset"))
        assertThrows(SearchSessionExpiredException::class.java) { repo.search("重生",2,ContentType.COMIC) }
        assertEquals(2,requests.size)
    }

    @Test fun expiredCursorRequestsFreshSearchInsteadOfDuplicateOffsetPage() {
        var now=0L
        var requests=0
        val repo=repository(clock={ now }) {
            requests++
            JSONObject().put("code",0).put("search_tabs",JSONArray().put(searchTab(19,"999")
                .put("search_id","session").put("passback","cursor"))).toString()
        }
        repo.search("修仙",1,ContentType.COMIC)
        now=300001L
        assertThrows(SearchSessionExpiredException::class.java) { repo.search("修仙",2,ContentType.COMIC) }
        assertEquals(1,requests)
        repo.search("修仙",1,ContentType.COMIC)
        repo.search("修仙",2,ContentType.COMIC)
        assertEquals(3,requests)
    }

    @Test fun shortHomeStillUsesCategoryAndStopsOnLastPage() {
        val repo=repository { request ->
            assertEquals("1",request.url.queryParameter("tab"))
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"456","series_name":"测试短剧"}],"pagination":{"totalPages":1}}}};</script>"""
        }
        val result=repo.home(1,ContentType.SHORT)
        assertEquals("456",result.items.single().id)
        assertFalse(result.hasMore)
    }

    @Test fun titleFallbackSearchReadsPastTheLegacyHundredPageLimit() {
        val targetPage=101
        val repo=repository { request ->
            if(request.url.host != "hongguoduanju.com") return@repository ""
            if(request.url.encodedPath.startsWith("/search/")) return@repository "<script>_ROUTER_DATA={\"loaderData\":{}};</script>"
            val page=request.url.queryParameter("page")?.toIntOrNull() ?: 1
            val title=if(page==targetPage) "第 101 页的修仙剧" else "片库剧 $page"
            val id="100000000000000000$page"
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"$id","series_name":"$title"}],"pagination":{"pageNum":$page,"totalPages":$targetPage}}}};</script>"""
        }
        val result=repo.search("第 101 页",1,ContentType.SHORT)
        assertTrue("items=${result.items.map { it.title }}", result.items.any { it.title=="第 101 页的修仙剧" })
        assertTrue(result.notice.contains("已检索"))
    }

    @Test fun titleFallbackSearchIncludesDynamicCategoryPages() {
        val repo=repository { request ->
            if(request.url.host != "hongguoduanju.com") return@repository ""
            if(request.url.encodedPath.startsWith("/search/")) return@repository "<script>_ROUTER_DATA={\"loaderData\":{}};</script>"
            if(request.url.encodedPath=="/category/real-drama/romance") {
                return@repository """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"9000000000000000001","series_name":"分类页爱情修仙剧"}],"pagination":{"pageNum":1,"totalPages":1}}}};</script>"""
            }
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"selectorList":[{"items":[{"selector_item_id":"romance","show_name":"爱情"}]}],"recommendList":[{"series_id":"9000000000000000002","series_name":"基础片库剧"}],"pagination":{"pageNum":1,"totalPages":1}}}};</script>"""
        }
        val result=repo.search("分类页爱情修仙",1,ContentType.SHORT)
        assertTrue(result.items.any { it.title=="分类页爱情修仙剧" })
    }

    private fun searchTab(type: Int,id: String) = JSONObject().put("tab_type",type).put("has_more",true)
        .put("data",JSONArray().put(JSONObject().put("video_data",JSONArray().put(JSONObject()
            .put("series_id",id).put("title","测试剧集").put("episode_cnt",8)))))

    @Test fun comicSearchRequestsAndSelectsOnlyComicTab() {
        var requests=0
        val repo=repository { request ->
            requests++
            assertEquals("19",request.url.queryParameter("tab_type"))
            assertEquals(if(requests==1) "0" else "20",request.url.queryParameter("offset"))
            assertEquals("修仙",request.url.queryParameter("query"))
            JSONObject().put("code",0).put("search_tabs",JSONArray()
                .put(searchTab(11,"111")).put(searchTab(19,"999"))).toString()
        }
        repo.search("修仙",1,ContentType.COMIC)
        assertEquals(listOf("999"),repo.search("修仙",2,ContentType.COMIC).items.map { it.id })
    }

    @Test fun emptyComicSearchDoesNotFallBackToShortResults() {
        var requests=0
        val repo=repository { requests++; """{"code":0,"search_tabs":[{"tab_type":19,"data":[],"has_more":false}]}""" }
        assertTrue(repo.search("无结果",1,ContentType.COMIC).items.isEmpty())
        assertEquals(2,requests)
    }

    @Test fun emptyComicAppTabFallsBackToTheTypeSpecificPublicSearch() {
        val repo=repository { request ->
            if(request.url.host.contains("fqnovel")) {
                """{"code":0,"search_tabs":[{"tab_type":19,"data":[],"has_more":false}]}"""
            } else if(request.url.encodedPath=="/api/search/result/") {
                JSONObject().put("searchList",JSONArray().put(JSONObject().put("series_id","909").put("series_name","公开漫剧结果").put("genre","205")))
                    .put("totalCount",1).put("totalPages",1).put("hasMore",false).toString()
            } else error("unexpected request ${request.url}")
        }
        assertEquals("909",repo.search("修仙",1,ContentType.COMIC).items.single().id)
    }

    @Test fun emptyValidShortSearchFallsBackToThePublicCatalog() {
        val repo=repository { request ->
            if(request.url.host.contains("fqnovel")) {
                """{"code":0,"search_tabs":[{"tab_type":11,"data":[],"has_more":false}]}"""
            } else if(request.url.encodedPath.startsWith("/search/")) {
                websiteResults()
            } else {
                """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"777","series_name":"目录中的短剧"}],"pagination":{"pageNum":1,"totalPages":1}}}};</script>"""
            }
        }
        assertEquals("777",repo.search("目录中的短剧",1,ContentType.SHORT).items.single().id)
    }

    @Test fun publicSearchEndpointFiltersTypesAndContinuesPages() {
        val repo=repository { request ->
            if(request.url.host.contains("fqnovel")) ""
            else if(request.url.encodedPath=="/api/search/result/") {
                val page=request.url.queryParameter("page")?.toIntOrNull() ?: 1
                val row=if(page==1) JSONObject().put("series_id","701").put("series_name","修仙短剧一").put("genre","203")
                else JSONObject().put("series_id","702").put("series_name","修仙短剧二").put("genre","203")
                JSONObject().put("searchList",JSONArray().put(row).put(JSONObject().put("series_id","799").put("series_name","修仙漫剧").put("genre","205")))
                    .put("totalCount",2).put("pageNum",page).put("totalPages",2).put("hasMore",page<2).toString()
            } else error("unexpected request ${request.url}")
        }
        val first=repo.search("修仙",1,ContentType.SHORT)
        assertEquals(listOf("701"),first.items.map { it.id }); assertTrue(first.hasMore)
        val second=repo.search("修仙",2,ContentType.SHORT)
        assertEquals(listOf("702"),second.items.map { it.id }); assertFalse(second.hasMore)
    }

    @Test fun emptyAppPageFallsBackToThePublicCatalog() {
        val repo=repository { request ->
            if(request.url.host.contains("fqnovel")) {
                if(request.url.queryParameter("offset")=="0") {
                    JSONObject().put("code",0).put("search_tabs",JSONArray().put(searchTab(11,"111").put("has_more",true))).toString()
                } else ""
            } else if(request.url.encodedPath.startsWith("/search/")) {
                websiteResults()
            } else {
                """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"777","series_name":"修仙官网片库结果"}],"pagination":{"pageNum":1,"totalPages":1}}}};</script>"""
            }
        }
        assertEquals("111",repo.search("修仙",1,ContentType.SHORT).items.single().id)
        assertEquals("777",repo.search("修仙",2,ContentType.SHORT).items.single().id)
    }

    @Test fun unavailableAppPageFallsBackToThePublicCatalog() {
        val repo=repository { request ->
            if(request.url.host.contains("fqnovel")) {
                if(request.url.queryParameter("offset")=="0") {
                    JSONObject().put("code",0).put("search_tabs",JSONArray().put(searchTab(11,"111").put("has_more",true))).toString()
                } else JSONObject().put("code",1).toString()
            } else if(request.url.encodedPath=="/api/search/result/") {
                JSONObject().put("searchList",JSONArray().put(JSONObject().put("series_id","778").put("series_name","修仙公开结果").put("genre","203")))
                    .put("totalCount",1).put("totalPages",1).put("hasMore",false).toString()
            } else error("unexpected request ${request.url}")
        }
        assertEquals("111",repo.search("修仙",1,ContentType.SHORT).items.single().id)
        assertEquals("778",repo.search("修仙",2,ContentType.SHORT).items.single().id)
    }

    @Test fun missingComicTabDoesNotFallBackToShortResults() {
        var requests=0
        val repo=repository { requests++; """{"code":0,"search_tabs":[{"tab_type":11,"data":[]}]}""" }
        assertThrows(IOException::class.java) { repo.search("修仙",1,ContentType.COMIC) }
        assertEquals(1,requests)
    }

    @Test fun shortSearchKeepsWebsiteFallback() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            paths+=request.url.encodedPath
            if(paths.size==1) throw IOException("offline")
            """<script>_ROUTER_DATA={"loaderData":{"search_page":{"searchList":[{"series_id":"456","series_name":"测试短剧"}]}}}};</script>"""
        }
        assertEquals("456",repo.search("短剧",1,ContentType.SHORT).items.single().id)
        assertEquals(2,paths.size)
    }

    @Test fun everyVideoInGroupedAndLynxResultsIsKeptWithItsOwnTitle() {
        val second=JSONObject().put("series_id","222").put("title","修仙二").put("cover_url","https://example.com/2.jpg")
        val tab=searchTab(19,"111")
        val group=tab.getJSONArray("data").getJSONObject(0)
        group.getJSONArray("video_data").put(second)
        group.put("search_high_light",JSONObject().put("title",JSONObject().put("text","错误的分组名称")))
        tab.getJSONArray("data").put(JSONObject().put("lynx_data",JSONObject().put("cell_data",JSONArray()
            .put(JSONObject().put("video_data",JSONArray().put(second).put(JSONObject().put("series_id","333").put("series_name","修仙三"))))) .toString()))
        val result=repository { JSONObject().put("code",0).put("search_tabs",JSONArray().put(tab)).toString() }.search("修仙",type=ContentType.COMIC)
        assertEquals(listOf("111","222","333"),result.items.map { it.id })
        assertEquals(listOf("测试剧集","修仙二","修仙三"),result.items.map { it.title })
        assertEquals("https://example.com/2.jpg",result.items[1].cover)
    }

    @Test fun shortWebsiteFallbackStaysOnTheSameSourceOnPageTwo() {
        val repo=repository { request ->
            if(request.url.host.contains("fqnovel")) {
                if(request.url.queryParameter("offset")=="0") throw IOException("app unavailable")
                JSONObject().put("code",0).put("search_tabs",JSONArray().put(searchTab(11,"999"))).toString()
            } else {
                val id=if(request.url.queryParameter("page")=="1") "111" else "222"
                """<script>_ROUTER_DATA={"loaderData":{"search_page":{"searchList":[{"series_id":"$id","series_name":"短剧"}],"pagination":{"totalPages":2}}}};</script>"""
            }
        }
        assertEquals("111",repo.search("短剧",1,ContentType.SHORT).items.single().id)
        val next=repo.search("短剧",2,ContentType.SHORT)
        assertEquals("222",next.items.single().id)
        assertFalse(next.hasMore)
    }

    @Test fun shortSearchAlsoRejectsAnExpiredCursor() {
        var now=0L
        val repo=repository(clock={ now }) { JSONObject().put("code",0).put("search_tabs",JSONArray().put(searchTab(11,"111"))).toString() }
        repo.search("短剧",1,ContentType.SHORT); now=300001
        assertThrows(SearchSessionExpiredException::class.java) { repo.search("短剧",2,ContentType.SHORT) }
    }

    @Test fun discoveryUsesThreeDifferentPagesAndPreservesTheNextPage() {
        val repo=repository { request ->
            val page=request.url.queryParameter("page")?.toInt() ?: 1
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"$page","series_name":"剧$page"}],"pagination":{"totalPages":5}}}};</script>"""
        }
        val result=repo.discover(ContentType.SHORT)
        assertEquals(listOf("1","2","3"),result.items.map { it.id })
        assertEquals(3,result.loadedPages); assertTrue(result.hasMore)
    }

    @Test fun discoveryKeepsTheFirstPageWhenALaterPageFails() {
        val repo=repository { request ->
            if(request.url.queryParameter("page")=="2") throw IOException("offline")
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"1","series_name":"剧"}],"pagination":{"totalPages":5}}}};</script>"""
        }
        val result=repo.discover(ContentType.SHORT)
        assertEquals("1",result.items.single().id); assertEquals(1,result.loadedPages)
        assertTrue(result.hasMore); assertTrue(result.notice.isNotBlank())
    }

    @Test fun emptyAppResponseFallsBackToTheCompleteTypeSpecificPublicCatalog() {
        val repo=repository { request ->
            if(request.url.host.contains("fqnovel")) "" else {
                if(request.url.encodedPath.startsWith("/search/")) return@repository websiteResults()
                assertEquals("/category/comic-drama",request.url.encodedPath)
                assertNull(request.url.queryParameter("sort_type"))
                val page=request.url.queryParameter("page")?.toInt() ?: 1
                val rows=JSONArray((1..24).map { JSONObject().put("series_id",((page-1)*24+it).toString()).put("series_name",if(page==2 && it==24) "修仙" else "修仙外传 $page-$it") })
                "<script>_ROUTER_DATA="+JSONObject().put("loaderData",JSONObject().put("category_$",JSONObject().put("recommendList",rows).put("pagination",JSONObject().put("totalPages",2).put("pageNum",page))))+";</script>"
            }
        }
        val first=repo.search("修仙",1,ContentType.COMIC)
        assertEquals("48",first.items.first().id); assertEquals(24,first.items.size)
        assertTrue(first.hasMore); assertTrue(first.notice.isNotBlank())
        val second=repo.search("修仙",2,ContentType.COMIC)
        assertEquals(24,second.items.size); assertFalse(second.hasMore)
        assertTrue(first.items.map { it.id }.intersect(second.items.map { it.id }.toSet()).isEmpty())
    }

    private fun websiteResults(vararg rows: JSONObject) = "<script>_ROUTER_DATA="+JSONObject().put("loaderData",
        JSONObject().put("search_(keyword)/page",JSONObject().put("isSuccess",true).put("searchList",JSONArray(rows.toList()))))+";</script>"

    @Test fun emptyResponseFindsSeasonFourteenOutsideTheCategoryCatalogWithoutMixingTypes() {
        val repo=repository { request ->
            when {
                request.url.host.contains("fqnovel") -> ""
                request.url.encodedPath.startsWith("/search/") -> websiteResults(
                    JSONObject().put("video_data",JSONObject().put("series_id","7696437705822981182")
                        .put("series_title","聚宝仙盆之杂灵根才是真BOSS第十四季").put("episode_cnt",222).put("genre","205")),
                    JSONObject().put("video_data",JSONObject().put("series_id","7498612570866076734")
                        .put("series_title","聚宝仙盆").put("episode_cnt",80).put("genre","203")))
                else -> """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"1","series_name":"其他剧集"}],"pagination":{"totalPages":1,"pageNum":1}}}};</script>"""
            }
        }
        val comic=repo.search("聚宝仙盆第十四季",type=ContentType.COMIC)
        assertEquals(listOf("7696437705822981182"),comic.items.map { it.id })
        assertEquals("全 222 集",comic.items.single().badge)
        assertFalse(comic.hasMore)
        val short=repo.search("聚宝仙盆",type=ContentType.SHORT)
        assertEquals(listOf("7498612570866076734"),short.items.map { it.id })
    }

    @Test fun keywordSupplementAndCatalogKeepStablePagesAndDeduplicateSeries() {
        var keywordReads=0
        val repo=repository { request ->
            when {
                request.url.host.contains("fqnovel") -> ""
                request.url.encodedPath.startsWith("/search/") -> {
                    keywordReads++
                    websiteResults(JSONObject().put("video_data",JSONObject().put("series_id","99")
                        .put("series_title","修仙之新篇").put("genre","205")),
                        JSONObject().put("video_data",JSONObject().put("series_id","1")
                            .put("series_title","修仙旧篇1").put("genre","205")))
                }
                else -> {
                    val rows=JSONArray((1..25).map { JSONObject().put("series_id","$it").put("series_name","修仙旧篇$it") })
                    "<script>_ROUTER_DATA="+JSONObject().put("loaderData",JSONObject().put("category_$",
                        JSONObject().put("recommendList",rows).put("pagination",JSONObject().put("totalPages",1).put("pageNum",1))))+";</script>"
                }
            }
        }
        val first=repo.search("修仙",1,ContentType.COMIC)
        val second=repo.search("修仙",2,ContentType.COMIC)
        assertEquals(24,first.items.size); assertTrue(first.hasMore)
        assertEquals(2,second.items.size); assertFalse(second.hasMore)
        val ids=(first.items+second.items).map { it.id }
        assertEquals(26,ids.toSet().size); assertTrue("99" in ids)
        assertEquals(1,keywordReads)
    }

    @Test fun publicCatalogFailureDoesNotReturnAPartialSetAsComplete() {
        val repo=repository { request ->
            if(request.url.host.contains("fqnovel")) ""
            else if(request.url.queryParameter("page")=="2") throw IOException("offline")
            else """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"1","series_name":"修仙"}],"pagination":{"totalPages":2,"pageNum":1}}}};</script>"""
        }
        assertThrows(IOException::class.java) { repo.search("修仙",1,ContentType.COMIC) }
    }

    @Test fun publicCatalogRetryResumesAfterTheLastSuccessfullyReadPage() {
        var failed=true; var firstReads=0
        val repo=repository { request ->
            if(request.url.host.contains("fqnovel")) "" else {
                if(request.url.encodedPath.startsWith("/search/")) return@repository websiteResults()
                val page=request.url.queryParameter("page")?.toInt() ?: 1
                if(request.url.encodedPath.contains("/category") && page==1) firstReads++
                if(page==2 && failed) throw IOException("offline")
                """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"$page","series_name":"修仙$page"}],"pagination":{"totalPages":2,"pageNum":$page}}}};</script>"""
            }
        }
        assertThrows(IOException::class.java) { repo.search("修仙",1,ContentType.COMIC) }
        failed=false
        assertEquals(2,repo.search("修仙",1,ContentType.COMIC).items.size)
        assertEquals(1,firstReads)
    }
}
