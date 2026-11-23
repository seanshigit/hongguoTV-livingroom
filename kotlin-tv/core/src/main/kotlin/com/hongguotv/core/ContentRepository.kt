// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit

internal fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject.str(key: String) = if (isNull(key)) "" else optString(key, "").trim()
internal fun cleanUrl(s: String) = s.replace("\\/","/").replace("\\u002F","/",ignoreCase=true).replace("\\u0026","&").replace("&amp;","&")
data class Series(val id: String, val title: String, val cover: String = "", val description: String = "", val badge: String = "", val tags: String = "") {
    fun toJson() = JSONObject().put("id",id).put("title",title).put("cover",cover).put("description",description).put("badge",badge).put("tags",tags)
    companion object { fun fromJson(o: JSONObject) = Series(o.str("id"),o.str("title"),o.str("cover"),o.str("description"),o.str("badge"),o.str("tags")) }
}
data class Detail(val series: Series, val episodes: List<String>)
data class RankPosition(val rank: Int?, val heat: String)
data class ComicRanking(val positions: Map<String,RankPosition>, val totalPages: Int, val updatedText: String)
data class CatalogPage(val items: List<Series>, val hasMore: Boolean, val ranking: ComicRanking? = null, val loadedPages: Int=1, val notice: String="", val foundCount: Int=items.size, val totalPages: Int?=null)
data class StreamCandidate(val url: String, val key: ByteArray?, val quality: String)
data class StreamInfo(val url: String, val key: ByteArray?, val quality: String, val alternatives: List<StreamCandidate> = emptyList()) {
    fun candidates() = (listOf(StreamCandidate(url,key,quality))+alternatives).distinctBy { it.url }
}
class SearchSessionExpiredException: IOException("搜索结果已过期，请从第 1 页重新搜索")
class ProviderPlaybackException(val sourceCode: Int): IOException("片源暂不可播放（错误 $sourceCode）")
private class IncompatiblePlaybackCodecException: IOException("片源仅提供电视暂不支持的视频编码")
private class EmptySearchResponseException: IOException("搜索接口未返回内容")
internal const val MAX_CATALOG_PAGES = 1000

class ContentRepository(
    val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS).build(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val playbackSession: PlaybackDeviceSession = PlaybackDeviceSession(),
) {
    private val signer = Signer()
    private val playbackSigner = PlaybackV5Signer(clock)
    private val site = "https://hongguoduanju.com"
    private data class SearchCursor(val searchId: String, val passback: String, val offset: Int?, val updatedAt: Long)
    private val searchCursors=linkedMapOf<Triple<ContentType,String,Int>,SearchCursor>()
    private val websiteSearches=linkedMapOf<Pair<ContentType,String>,Long>()
    private data class CatalogSearch(val touched: Long, val candidates: List<Series>, val keywordAvailable: Boolean)
    private val catalogSearches=linkedMapOf<Pair<ContentType,String>,CatalogSearch>()
    private data class PublicFallback(val touched: Long,val offset: Int)
    private val publicFallbacks=linkedMapOf<Pair<ContentType,String>,PublicFallback>()
    private class TitleCatalog(var touched: Long) {
        val items=linkedMapOf<String,Series>()
        var nextPage=1
        var sourceIndex=0
        var sourceSlugs: List<String>?=null
        var notice=""
        var complete=false
    }
    private val titleCatalogs=mutableMapOf<ContentType,TitleCatalog>()
    private val categorySlugs=mutableMapOf<ContentType,List<String>>()
    private val supplementHttp by lazy { http.newBuilder().connectTimeout(3,TimeUnit.SECONDS).readTimeout(3,TimeUnit.SECONDS).callTimeout(4,TimeUnit.SECONDS).build() }
    private fun text(url: String, signed: Signer.Request? = null, quick: Boolean = false): String {
        val request = Request.Builder().url(url).header("User-Agent",VendorConstants.VIDEO_UA).header("Accept-Language","zh-CN,zh;q=0.9")
        signed?.headers?.forEach { (k,v) -> request.header(k,v) }
        signed?.body?.let { request.post(it.toRequestBody("application/json; charset=utf-8".toMediaType())) }
        val built=request.build()
        val action={ RequestScope.execute((if(quick) supplementHttp else http).newCall(built)) { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code,response.header("Retry-After"),"内容请求失败（HTTP ${response.code}）")
            val body = response.body ?: throw IOException("内容响应为空")
            if (body.contentLength() > 16*1024*1024) throw IOException("内容响应过大")
            val input=body.byteStream(); val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
            while(out.size()<=16*1024*1024) { val n=input.read(buffer); if(n<0) break; out.write(buffer,0,n) }; val bytes=out.toByteArray()
            if(bytes.size > 16*1024*1024) throw IOException("内容响应过大")
            String(bytes,Charsets.UTF_8)
        } }
        return if(quick) action() else NetworkRetry.execute(built,action)
    }
    private fun router(url: String) = extractRouter(text(url)).getJSONObject("loaderData")
    private fun card(item: JSONObject): Series {
        val o=item.optJSONObject("video_data") ?: item
        val count=o.optInt("episode_cnt",o.optJSONObject("series_episode_info")?.optInt("episode_cnt",0) ?: 0)
        val tags=o.optJSONArray("tags") ?: o.optJSONArray("category_list") ?: JSONArray()
        return Series(o.str("series_id"),o.str("series_name").ifEmpty { o.str("series_title").ifEmpty { item.str("name") } },cleanUrl(o.str("series_cover")),o.str("series_intro"),o.str("episode_right_text").ifEmpty { if(count>0) "全 ${count} 集" else "" },(0 until minOf(tags.length(),5)).joinToString(" · ") { tags.optJSONObject(it)?.str("name") ?: tags.optString(it) })
    }
    private fun valid(rows: List<Series>) = rows.filter { it.id.matches(Regex("[0-9]{1,30}")) && it.title.isNotBlank() }.distinctBy { it.id }
    fun comicRanking(page: Int = 1): CatalogPage {
        require(page in 1..MAX_CATALOG_PAGES)
        return ComicRank.parse(text("$site/rank/hot-comic-drama?page=$page"),page)
    }
    fun home(page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(page in 1..MAX_CATALOG_PAGES)
        if(type==ContentType.COMIC) return comicRanking(page)
        return category(page,type)
    }
    private fun category(page: Int,type: ContentType,slug: String? = null): CatalogPage {
        val path=if(slug.isNullOrBlank()) {
            if(type==ContentType.COMIC) "/category/comic-drama" else "/category?tab=1&sort_type=1"
        } else {
            "/category/${if(type==ContentType.COMIC) "comic-drama" else "real-drama"}/$slug"
        }
        val data=router("$site$path"+if(page>1) (if('?' in path) "&" else "?")+"page=$page" else "")
        val section=data.optJSONObject("category_page") ?: data.optJSONObject("category_$") ?: throw IOException("首页数据结构已变化")
        val selectors=section.optJSONArray("selectorList")?.objects()?.flatMap { row ->
            row.optJSONArray("items")?.objects().orEmpty().mapNotNull { item ->
                item.str("selector_item_id").takeIf { it.matches(Regex("[a-z0-9-]+")) }
            }
        }?.distinct().orEmpty()
        if(selectors.isNotEmpty()) categorySlugs[type]=selectors
        val rows=section.optJSONArray("recommendList") ?: section.optJSONObject("categoryData")?.optJSONArray("recommendList") ?: throw IOException("首页数据暂不可用")
        val items=valid(rows.objects().map(::card))
        val lastPage=minOf(section.optJSONObject("pagination")?.optInt("totalPages",MAX_CATALOG_PAGES) ?: MAX_CATALOG_PAGES,MAX_CATALOG_PAGES)
        if(section.optJSONObject("pagination")?.optInt("pageNum",page)!=page) throw IOException("片库分页信息异常")
        return CatalogPage(items,items.isNotEmpty() && page<lastPage,totalPages=lastPage)
    }
    fun discover(type: ContentType): CatalogPage {
        val first=home(1,type)
        val items=first.items.toMutableList()
        var more=first.hasMore; var loaded=1; var notice=""
        for(page in 2..3) {
            if(!more) break
            val next=try { home(page,type) } catch(e: Exception) {
                RequestScope.ensureActive()
                notice="部分内容未加载，刷新可重试"; break
            }
            items+=next.items; more=next.hasMore; loaded=page
        }
        return CatalogPage(valid(items).take(90),more,loadedPages=loaded,notice=notice)
    }
    fun search(keyword: String, page: Int = 1, type: ContentType = ContentType.SHORT,progress: ((Int)->Unit)?=null): CatalogPage {
        require(keyword.isNotBlank() && keyword.length<=80 && page in 1..MAX_CATALOG_PAGES)
        val key=type to keyword
        val publicOffset=synchronized(publicFallbacks) {
            publicFallbacks.entries.removeAll { clock()-it.value.touched>600_000 }
            if(page==1) publicFallbacks.remove(key)
            publicFallbacks[key]?.offset?.takeIf { page>1 }
        }
        if(publicOffset!=null) return runCatching { publicSearch(keyword,page-publicOffset,type) }.getOrElse { catalogTitleSearch(keyword,page-publicOffset,type,progress) }
        val catalogSource=synchronized(catalogSearches) {
            catalogSearches.entries.removeAll { clock()-it.value.touched>300_000 || (page==1 && it.key==key) }
            key in catalogSearches
        }
        if(catalogSource) return catalogTitleSearch(keyword,page,type,progress)
        // Empty app tabs can be stale or incomplete; keep the type and fall back to the public index.
        if(type==ContentType.COMIC) return try {
            val app=appSearch(keyword,page,type)
            if(app.items.isNotEmpty()) app
            else if(page==1) {
                val public=runCatching { publicSearch(keyword,page,type) }.getOrNull()
                if(public!=null) {
                    if(public.hasMore) synchronized(publicFallbacks) { publicFallbacks[key]=PublicFallback(clock(),0) }
                    public
                } else app
            } else {
                synchronized(publicFallbacks) { publicFallbacks[key]=PublicFallback(clock(),1) }
                runCatching { publicSearch(keyword,page-1,type) }.getOrDefault(app)
            }
        } catch(e: EmptySearchResponseException) {
            RequestScope.ensureActive()
            if(page==1) {
                val result=runCatching { publicSearch(keyword,page,type) }.getOrNull()
                if(result!=null) {
                    if(result.hasMore) synchronized(publicFallbacks) { publicFallbacks[key]=PublicFallback(clock(),0) }
                    result
                } else catalogTitleSearch(keyword,page,type,progress)
            } else {
                synchronized(publicFallbacks) { publicFallbacks[key]=PublicFallback(clock(),1) }
                runCatching { publicSearch(keyword,page-1,type) }.getOrElse { catalogTitleSearch(keyword,page-1,type,progress) }
            }
        }
        val website=synchronized(websiteSearches) {
            websiteSearches.entries.removeAll { clock()-it.value>300_000 || (page==1 && it.key==key) }
            key in websiteSearches
        }
        if(!website) try {
            val result=appSearch(keyword,page,type)
            if(result.items.isNotEmpty()) return result
            val public=runCatching { publicSearch(keyword,page,type) }.getOrNull()
            if(public!=null) {
                if(public.hasMore) synchronized(publicFallbacks) { publicFallbacks[key]=PublicFallback(clock(),0) }
                return public
            }
            return catalogTitleSearch(keyword,page,type,progress)
        } catch (e: Exception) {
            RequestScope.ensureActive()
            if(e is EmptySearchResponseException) {
                if(page==1) {
                    val result=runCatching { publicSearch(keyword,page,type) }.getOrNull()
                    if(result!=null) {
                        if(result.hasMore) synchronized(publicFallbacks) { publicFallbacks[key]=PublicFallback(clock(),0) }
                        return result
                    }
                    return catalogTitleSearch(keyword,page,type,progress)
                }
                synchronized(publicFallbacks) { publicFallbacks[key]=PublicFallback(clock(),1) }
                return runCatching { publicSearch(keyword,page-1,type) }.getOrElse { catalogTitleSearch(keyword,page-1,type,progress) }
            }
            if(e is SearchSessionExpiredException) throw e
            if(page>1) {
                synchronized(publicFallbacks) { publicFallbacks[key]=PublicFallback(clock(),1) }
                return runCatching { publicSearch(keyword,page-1,type) }.getOrElse { catalogTitleSearch(keyword,page-1,type,progress) }
            }
            val public=runCatching { publicSearch(keyword,page,type) }.getOrNull()
            if(public!=null) {
                if(public.hasMore) synchronized(publicFallbacks) { publicFallbacks[key]=PublicFallback(clock(),0) }
                return public
            }
            return catalogTitleSearch(keyword,page,type,progress)
        }
        val data=router("$site/search/${Signer.encode(keyword)}?page=$page")
        val section=data.optJSONObject("search_(keyword)/page") ?: data.optJSONObject("search_page") ?: throw IOException("搜索数据暂不可用")
        val rows=section.optJSONArray("searchList") ?: throw IOException("搜索数据结构已变化")
        val items=valid(rows.objects().map(::card))
        synchronized(websiteSearches) {
            websiteSearches[key]=clock()
            while(websiteSearches.size>6) websiteSearches.remove(websiteSearches.keys.first())
        }
        val totalPages=section.optJSONObject("pagination")?.optInt("totalPages",100) ?: 100
        return CatalogPage(items,items.isNotEmpty() && page<minOf(totalPages,100))
    }
    fun searchSupplement(keyword: String,page: Int,type: ContentType): CatalogPage {
        require(keyword.isNotBlank() && keyword.length<=80 && page in 1..MAX_CATALOG_PAGES)
        return publicSearch(keyword,page,type,quick=true)
    }
    private fun publicSearch(keyword: String,page: Int,type: ContentType,quick: Boolean=false): CatalogPage {
        require(page in 1..MAX_CATALOG_PAGES)
        // The public endpoint intermittently serves a cached 502 for the bare page URL;
        // keep the documented web client marker in the cache key.
        val response=text("$site/api/search/result/?query=${Signer.encode(keyword)}&page=$page&client=web",quick=quick)
        return runCatching {
            val data=JSONObject(response)
            val rows=data.optJSONArray("searchList") ?: throw IOException("官网关键词搜索暂不可用")
            val genre=if(type==ContentType.COMIC) "205" else "203"
            val items=valid(rows.objects().filter { (it.optJSONObject("video_data") ?: it).str("genre")==genre }.map(::card))
            val totalPages=minOf(data.optInt("totalPages",page),MAX_CATALOG_PAGES)
            CatalogPage(items,data.optBoolean("hasMore",page<totalPages) && page<totalPages,
                notice="官网关键词搜索（公开接口）",foundCount=data.optInt("totalCount",items.size),totalPages=totalPages)
        }.getOrElse {
            val data=extractRouter(response).getJSONObject("loaderData")
            val section=data.optJSONObject("search_(keyword)/page") ?: data.optJSONObject("search_page") ?: throw IOException("搜索数据暂不可用")
            val rows=section.optJSONArray("searchList") ?: throw IOException("搜索数据结构已变化")
            val genre=if(type==ContentType.COMIC) "205" else "203"
            val items=valid(rows.objects().filter { (it.optJSONObject("video_data") ?: it).str("genre").let { value -> value.isBlank() || value==genre } }.map(::card))
            val totalPages=minOf(section.optJSONObject("pagination")?.optInt("totalPages",page) ?: page,MAX_CATALOG_PAGES)
            CatalogPage(items,items.isNotEmpty() && page<totalPages,notice="官网关键词搜索",totalPages=totalPages)
        }
    }
    private fun websiteKeywordCandidates(keyword: String,type: ContentType): List<Series> {
        return runCatching { publicSearch(keyword,1,type).items }.getOrElse {
            val data=router("$site/search/${Signer.encode(keyword)}?page=1")
            val section=data.optJSONObject("search_(keyword)/page") ?: data.optJSONObject("search_page") ?: throw IOException("搜索数据暂不可用")
            if(section.has("isSuccess") && !section.optBoolean("isSuccess")) throw IOException("官网关键词搜索暂不可用")
            val rows=section.optJSONArray("searchList") ?: throw IOException("搜索数据结构已变化")
            val genre=if(type==ContentType.COMIC) "205" else "203"
            valid(rows.objects().filter { (it.optJSONObject("video_data") ?: it).str("genre")==genre }.map(::card))
        }
    }
    @Synchronized private fun catalogTitleSearch(keyword: String,page: Int,type: ContentType,progress: ((Int)->Unit)?): CatalogPage {
        val key=type to keyword
        val supplement=synchronized(catalogSearches) { catalogSearches[key] } ?: try {
            CatalogSearch(clock(),websiteKeywordCandidates(keyword,type),true)
        } catch(e: Exception) {
            RequestScope.ensureActive(); CatalogSearch(clock(),emptyList(),false)
        }
        val index=titleCatalogs[type]?.takeIf { clock()-it.touched in 0..600_000 }
            ?: TitleCatalog(clock()).also { titleCatalogs[type]=it }
        while(!index.complete) {
            if(index.sourceSlugs==null) index.sourceSlugs=listOf("")
            if(index.sourceIndex>=index.sourceSlugs!!.size) {
                val discovered=categorySlugs[type].orEmpty()
                index.sourceSlugs=(listOf("")+discovered).distinct()
            }
            if(index.sourceIndex>=index.sourceSlugs!!.size) { index.complete=true; break }
            if(index.nextPage>MAX_CATALOG_PAGES) throw IOException("官网片库超过安全分页上限，请缩小关键词范围后重试")
            RequestScope.ensureActive()
            val slug=index.sourceSlugs!![index.sourceIndex]
            val result=try { category(index.nextPage,type,slug.ifBlank { null }) } catch(e: Exception) {
                RequestScope.ensureActive()
                if(slug.isBlank()) throw e
                index.notice="部分分类暂不可用，已跳过并继续搜索；刷新可重试"
                index.sourceIndex++; index.nextPage=1; index.complete=index.sourceIndex>=index.sourceSlugs!!.size
                continue
            }
            if(result.items.isEmpty()) {
                if(index.sourceSlugs!!.size==1) {
                    val discovered=categorySlugs[type].orEmpty()
                    index.sourceSlugs=(listOf("")+discovered).distinct()
                }
                if(!result.hasMore) { index.sourceIndex++; index.nextPage=1; index.complete=index.sourceIndex>=index.sourceSlugs!!.size; continue }
                throw IOException("官网片库数据不完整，请稍后重试")
            }
            RequestScope.ensureActive()
            result.items.forEach { index.items.putIfAbsent(it.id,it) }
            if(index.sourceSlugs!!.size==1) {
                val discovered=categorySlugs[type].orEmpty()
                index.sourceSlugs=(listOf("")+discovered).distinct()
            }
            index.nextPage++
            if(!result.hasMore) { index.sourceIndex++; index.nextPage=1 }
            index.complete=index.sourceIndex>=index.sourceSlugs!!.size
            index.touched=clock()
            progress?.invoke(index.items.size)
        }
        val items=index.items.values.toList()
        // Keyword search can find new seasons absent from category pages and abbreviated titles.
        val matches=valid(supplement.candidates+items.filter { SearchCatalog.relevance(it,keyword)<4 })
            .sortedBy { SearchCatalog.relevance(it,keyword) }
        synchronized(catalogSearches) {
            catalogSearches[key]=supplement.copy(touched=clock())
            while(catalogSearches.size>6) catalogSearches.remove(catalogSearches.keys.first())
        }
        return CatalogPage(matches.drop((page-1)*24).take(24),page*24<matches.size,
            notice=listOf(if(supplement.keywordAvailable) "官网关键词搜索＋公开片库" else "关键词搜索暂不可用 · 官网片库", "已检索 ${items.size} 部", index.notice).filter { it.isNotBlank() }.joinToString(" · "))
    }
    private fun appSearch(keyword: String, page: Int, type: ContentType): CatalogPage {
        val values=VendorConstants.device.toMutableMap()
        for(k in listOf("version_code","manifest_version_code","update_version_code","pv_player")) values[k]="72232"
        values["version_name"]="7.2.2.32"
        values.putAll(linkedMapOf("query" to keyword,"count" to "20","offset" to ((page-1)*20).toString(),"tab_type" to type.searchTab.toString(),"bookshelf_search_plan" to "4","use_correct" to "true","user_is_login" to "0"))
        val cursor=synchronized(searchCursors) {
            val now=clock()
            searchCursors.entries.removeAll { now-it.value.updatedAt>300000 || (page==1 && it.key.first==type && it.key.second==keyword) }
            searchCursors[Triple(type,keyword,page-1)]
        }
        if(page>1 && cursor==null) throw SearchSessionExpiredException()
        cursor?.let {
            if(it.searchId.isNotBlank()) values["search_id"]=it.searchId
            if(it.passback.isNotBlank()) values["passback"]=it.passback
            it.offset?.takeIf { offset -> offset>=0 }?.let { offset -> values["offset"]=offset.toString() }
        }
        val signed=signer.sign("https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/search/tab/v",values)
        val response=text(signed.url,signed)
        if(response.isBlank()) throw EmptySearchResponseException()
        val data=try { JSONObject(response) } catch(e: org.json.JSONException) { throw IOException("搜索接口返回了无法读取的数据，请稍后重试",e) }
        if(data.optInt("code",-1)!=0) throw IOException("搜索接口暂不可用")
        val tabs=data.optJSONArray("search_tabs") ?: data.optJSONObject("data")?.optJSONArray("search_tabs") ?: throw IOException("搜索格式异常")
        val tab=tabs.objects().firstOrNull { it.optInt("tab_type")==type.searchTab } ?: throw IOException("${type.label}搜索暂不可用")
        val list=mutableListOf<Series>()
        for(cell in (tab.optJSONArray("data") ?: JSONArray()).objects()) {
            val cells=mutableListOf(cell)
            runCatching { JSONObject(cell.str("lynx_data")).optJSONArray("cell_data")?.objects() }.getOrNull()?.let { cells.addAll(it) }
            for(row in cells) {
                val videos=row.optJSONArray("video_data")?.objects() ?: row.optJSONObject("video_data")?.let { listOf(it) } ?: emptyList()
                for(video in videos) {
                val id=video.str("series_id").ifEmpty { row.str("book_id").ifEmpty { row.str("search_result_id") } }
                val title=video.str("series_name").ifEmpty { video.str("series_title").ifEmpty { video.str("title").ifEmpty {
                    if(videos.size==1) row.optJSONObject("search_high_light")?.optJSONObject("title")?.str("text").orEmpty().ifEmpty { row.str("cell_name") } else ""
                } } }
                val count=video.optInt("episode_cnt")
                list+=Series(id,org.jsoup.Jsoup.parse(title).text(),cleanUrl(video.str("cover").ifEmpty { video.str("cover_url").ifEmpty { video.str("series_cover") } }),video.str("video_desc").ifEmpty { video.str("series_intro") },if(count>0) "全 $count 集" else video.str("rec_text"),video.str("sub_title"))
                }
            }
        }
        synchronized(searchCursors) {
            searchCursors[Triple(type,keyword,page)]=SearchCursor(tab.str("search_id"),tab.str("passback"),tab.str("next_offset").toIntOrNull(),clock())
            while(searchCursors.size>100) searchCursors.remove(searchCursors.keys.first())
        }
        return CatalogPage(valid(list),tab.optBoolean("has_more",false) && page<MAX_CATALOG_PAGES)
    }
    fun detail(id: String): Detail {
        require(id.matches(Regex("[0-9]{1,30}")))
        try {
            val o=router("$site/detail?series_id=$id").optJSONObject("detail_page")?.optJSONObject("seriesDetail") ?: throw IOException("剧集详情暂不可用")
            val raw=o.optJSONArray("vid_list") ?: throw IOException("分集暂不可用")
            val episodes=(0 until raw.length()).map { raw.getString(it) }
            if(o.str("series_id").let { it.isNotEmpty() && it!=id } || episodes.isEmpty() || episodes.any { !it.matches(Regex("[0-9]{1,30}")) } || episodes.distinct().size!=episodes.size) throw IOException("分集数据异常")
            return Detail(card(o).copy(id=id),episodes)
        } catch(websiteFailure: Exception) {
            RequestScope.ensureActive()
            try { return appDetail(id) } catch(fallbackFailure: Exception) {
                RequestScope.ensureActive()
                throw IOException("官网和备用详情接口均暂不可用，请稍后重试",fallbackFailure).also { it.addSuppressed(websiteFailure) }
            }
        }
    }
    private fun appDetail(id: String): Detail {
        val biz=JSONObject("""{"detail_page_version":0,"disable_digg_stat":false,"image_shrink_datas_str":"W3siaW1hZ2VfdHlwZSI6MywiaW1hZ2Vfd2lkdGgiOjkwMCwic2hyaW5rX3R5cGUiOjN9LHsiaW1hZ2VfdHlwZSI6NCwiaW1hZ2Vfd2lkdGgiOjcyLCJzaHJpbmtfdHlwZSI6NH1d\n","need_all_video_definition":false,"need_mp4_align":false,"screen_width_px":"900","source":7,"use_os_player":false,"use_server_dns":false}""")
        val payload=JSONObject().put("biz_param",biz).put("dr_scene","preload").put("series_id",id).toString().toByteArray()
        val signed=signer.sign("https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_detail/preload/v1",VendorConstants.device,payload)
        val data=JSONObject(text(signed.url,signed))
        if(data.optInt("code",data.optInt("Code",-1))!=0) throw IOException("备用详情接口未返回可用数据")
        val video=data.optJSONObject("data")?.optJSONObject(id)?.optJSONObject("video_data") ?: throw IOException("备用接口未返回所选剧集")
        val series=card(video)
        if(series.id!=id || series.title.isBlank() || video.str("series_id_str").let { it.isNotEmpty() && it!=id }) throw IOException("备用剧集信息不匹配")
        val rows=(video.optJSONArray("video_list") ?: throw IOException("备用分集列表为空")).objects().sortedBy { it.optInt("vid_index") }
        if(rows.isEmpty() || video.optInt("episode_cnt")!=rows.size || rows.withIndex().any { (index,row) -> row.optInt("vid_index")!=index+1 || row.str("series_id")!=id }) throw IOException("备用分集数据不完整")
        val episodes=rows.map { it.str("vid") }
        if(episodes.any { !it.matches(Regex("[0-9]{1,30}")) } || episodes.distinct().size!=episodes.size) throw IOException("备用分集数据异常")
        return Detail(series,episodes)
    }
    fun stream(id: String, maxQuality: Int = 1080): StreamInfo {
        var failure: IOException?=null
        repeat(3) {
            try { return streamOnce(id,maxQuality) }
            catch(e: ProviderPlaybackException) { throw e }
            catch(e: IOException) {
                if(!e.message.orEmpty().contains("没有播放资源")) throw e
                failure=e
            }
        }
        throw failure ?: IOException("没有播放资源")
    }
    private fun streamOnce(id: String, maxQuality: Int): StreamInfo {
        require(id.matches(Regex("[0-9]{1,30}")))
        val identity=playbackSession.freshProfile(http)
        val get=playbackRequest(identity,id)
        val model=readPlaybackModel(get,id)
        if(model==null) {
            val renewed=playbackSession.freshProfile(http)
            return preloadPlayback(renewed,id,maxQuality)
        }
        return try { streamFromModel(model,maxQuality) } catch(_: IncompatiblePlaybackCodecException) {
            preloadPlayback(identity,id,maxQuality)
        }
    }

    private fun readPlaybackModel(request: Signer.Request, id: String, grouped: Boolean=false): JSONObject? {
        RequestScope.ensureActive()
        val response=text(request.url,request)
        if(response.isBlank()) return null
        val result=try { JSONObject(response) }
        catch(error: org.json.JSONException) { throw IOException("播放接口返回格式异常",error) }
        return playbackModel(result,id,grouped)
    }
    private fun playbackModel(result: JSONObject, id: String, grouped: Boolean=false): JSONObject {
        val sourceCode=result.optInt("Code",result.optInt("code",0))
        if(sourceCode!=0) throw ProviderPlaybackException(sourceCode)
        val outer=result.optJSONObject("data") ?: throw IOException("播放信息为空")
        val map=(if(grouped) outer.optJSONObject("1004") else null) ?: outer.optJSONObject("video_model_data") ?: outer
        val entry=map.optJSONObject(id) ?: throw IOException("片源未返回所选分集，请稍后重试")
        return when(val rawModel=entry.opt("video_model")) {
            is JSONObject -> rawModel
            is String -> runCatching { JSONObject(rawModel) }.getOrElse { throw IOException("播放模型格式异常",it) }
            else -> throw IOException("片源未返回所选分集，请稍后重试")
        }
    }
    private fun preloadPlayback(identity: PlaybackDeviceSession.Profile, id: String, maxQuality: Int): StreamInfo {
        RequestScope.ensureActive()
        // The 7.4.5.32 request profile and preload body follow N3urda/hongguoTV#107.
        val payload="""{"biz_param":{"detail_page_version":0,"device_level":2,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$id"]},"dr_scene":"preload"}""".toByteArray(Charsets.UTF_8)
        val compressed=java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.GZIPOutputStream(out).use { it.write(payload) }
        }.toByteArray()
        val signed=playbackSigner.signPost(
            "https://reading.snssdk.com/novel/player/multi_video_model/preload/v1",
            playbackProfile(identity),compressed,
        ).let { it.copy(headers=it.headers+mapOf(
            "User-Agent" to playbackUserAgent,
            "Accept" to "application/json", "Content-Encoding" to "gzip",
        )) }
        val model=readPlaybackModel(signed,id,grouped=true)
            ?: throw IOException("播放接口返回为空，请稍后重试")
        return try {
            try { streamFromModel(model,maxQuality) }
            catch(_: IncompatiblePlaybackCodecException) { compatibleVod(model,maxQuality) }
        } catch(error: IOException) {
            RequestScope.ensureActive()
            // Bound model recovery to one POST, including when it has no compatible rendition.
            throw IOException("兼容接口仍未提供电视可播放的清晰度，请稍后重试或选择其他剧集",error)
        }
    }

    private fun vodEndpoints(model: JSONObject): List<okhttp3.HttpUrl> {
        val raw=model.opt("fallback_api")
        val urls=when(raw) {
            is JSONObject -> listOf(raw.str("fallback_api"))
            is JSONArray -> (0 until raw.length()).map { raw.optString(it) }
            is String -> listOf(runCatching { JSONObject(raw).str("fallback_api") }.getOrDefault(raw))
            else -> emptyList()
        }
        return urls.mapNotNull { cleanUrl(it).toHttpUrlOrNull() }
            .filter { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }
    }

    private fun vodModel(url: okhttp3.HttpUrl): JSONObject? =
        JSONObject(text(url.toString())).optJSONObject("video_info")?.optJSONObject("data")

    private fun compatibleVod(model: JSONObject, maxQuality: Int): StreamInfo {
        val endpoint=vodEndpoints(model).firstOrNull() ?: throw IncompatiblePlaybackCodecException()
        for(codec in listOf("1","3")) {
            RequestScope.ensureActive()
            val url=endpoint.newBuilder().setQueryParameter("codec_type",codec).build()
            val data=try { vodModel(url) }
            catch(error: org.json.JSONException) { throw IOException("兼容播放信息格式异常",error) }
            if(data==null) continue
            try { return streamFromModel(data,maxQuality,fromVod=true) }
            catch(_: IncompatiblePlaybackCodecException) { continue }
        }
        throw IncompatiblePlaybackCodecException()
    }

    private fun streamFromModel(model: JSONObject, maxQuality: Int, fromVod: Boolean=false): StreamInfo {
        val inlineList=model.opt("video_list")
        val inlineRows=when(val list=inlineList) {
            is JSONArray -> list.objects()
            is JSONObject -> list.keys().asSequence().mapNotNull { list.optJSONObject(it) }.toList()
            else -> emptyList()
        }
        val completeInline=inlineRows.size==when(inlineList) {
            is JSONArray -> inlineList.length()
            is JSONObject -> inlineList.length()
            else -> 0
        }
        val inline=inlineRows.isNotEmpty()
        var info: JSONObject?=if(inline) {
            JSONObject().put("key_seed",model.str("key_seed")).put("video_list",JSONObject().apply {
                inlineRows.forEachIndexed { index,row -> put("video_$index",row) }
            })
        } else null
        var fallbackFailure: Throwable?=null
        if(info==null && !fromVod) {
            for(fallback in vodEndpoints(model)) {
                try {
                    info=vodModel(fallback)
                    if(info!=null) break
                } catch(e: Exception) {
                    RequestScope.ensureActive()
                    fallbackFailure=e
                }
            }
        }
        val videoInfo=info ?: throw IOException("没有播放资源",fallbackFailure)
        val videoList=videoInfo.optJSONObject("video_list") ?: throw IOException("没有可用清晰度")
        // ByteVC2 resources can expose playable audio but no video track in Android's MP4 extractor.
        // Keep missing legacy codec labels usable, but never select a known incompatible variant.
        val entries=videoList.keys().asSequence().mapNotNull { k -> videoList.optJSONObject(k)?.let { k to it } }.toList()
        fun incompatible(item: JSONObject): Boolean {
            val codec=listOf(item.str("codec_type"),item.str("gear_des_key"),item.optJSONObject("video_meta")?.str("codec_type").orEmpty()).joinToString(" ").lowercase()
            return codec.contains("bytevc2") || Regex("(^|[^a-z0-9])bvc2([^a-z0-9]|$)").containsMatchIn(codec)
        }
        val compatible=entries.filterNot { incompatible(it.second) }
        val byteVc2Only=entries.isNotEmpty() && compatible.isEmpty() && entries.size==videoList.length() &&
            (!inline || completeInline) && entries.all { it.second.str("main_url").isNotEmpty() }
        // Validate the same URL/key requirements before classifying an otherwise usable
        // model as codec-incompatible. Invalid metadata alone must not start another request.
        val rows=(if(byteVc2Only) entries else compatible).filter { it.second.str("main_url").isNotEmpty() }.map { (k,o) ->
            val dimensions=listOfNotNull(o,o.optJSONObject("video_meta")).flatMap { meta ->
                listOf(meta.optInt("vwidth") to meta.optInt("vheight"),meta.optInt("width") to meta.optInt("height"))
            }.firstOrNull { (width,height) -> width>0 && height>0 }
            val meta=o.optJSONObject("video_meta")
            val quality=dimensions?.let { (width,height) -> minOf(width,height) }
                ?: qualityNumber(o.str("quality_desc").ifEmpty { o.str("height").ifEmpty { o.str("vheight").ifEmpty { o.str("quality").ifEmpty { meta?.str("definition").orEmpty().ifEmpty { k } } } } })
            Triple(quality,k,o)
        }.sortedByDescending { it.first }.toList()
        data class Variant(val resolution: Int,val candidate: StreamCandidate)
        fun candidateUrls(item: JSONObject): List<String> {
            val urls=mutableListOf<String>()
            fun add(value: String) { if(value.isNotBlank()) urls+=cleanUrl(value) }
            add(item.str("main_url"))
            add(item.str("backup_url"))
            add(item.str("backup_url_1"))
            when(val raw=item.opt("backup_url_list")) {
                is JSONArray -> (0 until raw.length()).forEach { index -> add(raw.optString(index,"")) }
                is String -> {
                    val text=raw.trim()
                    if(text.startsWith("[")) runCatching { JSONArray(text) }.getOrNull()?.let { array ->
                        (0 until array.length()).forEach { index -> add(array.optString(index,"")) }
                    } ?: add(text)
                    else add(text)
                }
            }
            return urls
        }
        fun variants(row: Triple<Int,String,JSONObject>): List<Variant> {
            val item=row.third; val seed=videoInfo.str("key_seed")
            val quality=if(row.first>0) "${row.first}P"+(if(row.first>maxQuality) "（兼容资源）" else "") else "自动"
            val encryption=item.optJSONObject("encrypt_info")
            val spade=item.str("spade_a").ifEmpty { encryption?.str("spade_a").orEmpty() }
            val encrypt=encryption?.opt("encrypt") ?: item.opt("encrypt")
            if((inline && !fromVod || encrypt != null) && encrypt !in listOf(false,0,"0","false") && spade.isEmpty()) return emptyList()
            val key=try { spade.takeIf { it.isNotEmpty() }?.let(MediaCrypto::deriveKey) }
                catch(error: IllegalArgumentException) { throw IOException("内容密钥格式异常",error) }
            return candidateUrls(item).mapNotNull { raw ->
                runCatching {
                    val url=if(seed.isNotEmpty()) {
                        cleanUrl(MediaCrypto.decryptUrl(raw,decode64(seed))).toHttpUrlOrNull()
                    } else {
                        raw.toHttpUrlOrNull() ?: cleanUrl(String(decode64(raw),Charsets.UTF_8)).toHttpUrlOrNull()
                    } ?: return@runCatching null
                    if(url.username.isNotEmpty() || url.password.isNotEmpty()) return@runCatching null
                    Variant(row.first,StreamCandidate(url.toString(),key,quality))
                }.getOrNull()
            }
        }
        val orderedRows=rows.sortedWith(compareBy<Triple<Int,String,JSONObject>>({ if(it.first>maxQuality) 1 else 0 },{ kotlin.math.abs(it.first-maxQuality) },{ -it.first }))
        val seenUrls=mutableSetOf<String>()
        val ordered=orderedRows.flatMap(::variants).filter { seenUrls.add(it.candidate.url) }
        val selected=ordered.firstOrNull() ?: throw IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集")
        if(byteVc2Only) throw IncompatiblePlaybackCodecException()
        return StreamInfo(selected.candidate.url,selected.candidate.key,selected.candidate.quality,
            ordered.drop(1).map { it.candidate })
    }
    private val playbackUserAgent get() = System.getProperty("http.agent", "okhttp/4.12.0")

    private fun playbackProfile(identity: PlaybackDeviceSession.Profile) = linkedMapOf(
        "aid" to "8662", "app_name" to "novelread",
        "version_code" to "74532", "version_name" to "7.4.5.32", "update_version_code" to "74532",
        "manifest_version_code" to "74532", "os" to "android", "ssmix" to "a",
        "channel" to "update_64", "device_platform" to "android", "device_type" to "25053RT47C",
        "device_brand" to "Redmi", "os_version" to "16", "os_api" to "36",
        "ac" to "wifi", "language" to "zh", "host_abi" to "arm64-v8a",
        "iid" to identity.values.getValue("iid"), "cdid" to identity.values.getValue("cdid"),
        "device_id" to identity.values.getValue("device_id"),
    )

    private fun playbackRequest(identity: PlaybackDeviceSession.Profile, id: String): Signer.Request {
        // Sign MD5(empty bytes) and send the final V5 query without a GET body.
        val values=playbackProfile(identity)+linkedMapOf(
            "video_id" to id, "video_id_type" to "0", "device_level" to "1",
            "need_all_video_definition" to "1", "need_mp4_align" to "0", "use_os_player" to "0",
            "video_platform" to "1024", "source" to "0", "from_video_id" to "",
        )
        val signed=playbackSigner.signGet(
            "https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/video_tab/multi_video_model/v1/",values,
        )
        return signed.copy(headers=signed.headers+mapOf(
            "User-Agent" to playbackUserAgent, "Accept" to "application/json; charset=utf-8",
        ))
    }
    companion object {
        fun extractRouter(html: String): JSONObject {
            val marker=html.indexOf("_ROUTER_DATA"); if(marker<0) throw IOException("页面没有内容数据")
            val first=html.indexOf('{',marker); if(first<0) throw IOException("页面数据格式错误")
            var depth=0; var quoted=false; var escaped=false
            for(i in first until html.length) {
                val ch=html[i]
                if(quoted) { if(escaped) escaped=false else if(ch=='\\') escaped=true else if(ch=='"') quoted=false; continue }
                when(ch) { '"' -> quoted=true; '{','[' -> depth++; '}',']' -> { depth--; if(depth==0) return JSONObject(html.substring(first,i+1)) } }
            }
            throw IOException("页面数据不完整")
        }
        fun qualityNumber(value: String): Int = Regex("2160|1440|1080|720|576|540|480|360").find(value)?.value?.toInt() ?: mapOf("1920" to 1080,"1280" to 720,"1024" to 576,"854" to 480,"640" to 360)[value] ?: value.toIntOrNull() ?: 0
    }
}
