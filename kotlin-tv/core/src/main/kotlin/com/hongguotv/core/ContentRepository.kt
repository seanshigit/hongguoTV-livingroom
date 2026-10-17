// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
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
data class CatalogPage(val items: List<Series>, val hasMore: Boolean, val ranking: ComicRanking? = null, val loadedPages: Int=1, val notice: String="", val foundCount: Int=items.size)
data class StreamInfo(val url: String, val key: ByteArray?, val quality: String)
class SearchSessionExpiredException: IOException("搜索结果已过期，请从第 1 页重新搜索")
class ProviderPlaybackException(val sourceCode: Int): IOException("片源暂不可播放（错误 $sourceCode）")
private class EmptySearchResponseException: IOException("搜索接口未返回内容")

class ContentRepository(val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS).build(), private val clock: () -> Long = System::currentTimeMillis) {
    private val signer = Signer()
    private val site = "https://hongguoduanju.com"
    private data class SearchCursor(val searchId: String, val passback: String, val offset: Int?, val updatedAt: Long)
    private val searchCursors=linkedMapOf<Triple<ContentType,String,Int>,SearchCursor>()
    private val websiteSearches=linkedMapOf<Pair<ContentType,String>,Long>()
    private data class CatalogSearch(val touched: Long, val candidates: List<Series>, val keywordAvailable: Boolean)
    private val catalogSearches=linkedMapOf<Pair<ContentType,String>,CatalogSearch>()
    private class TitleCatalog(var touched: Long) {
        val items=linkedMapOf<String,Series>()
        var nextPage=1
        var complete=false
    }
    private val titleCatalogs=mutableMapOf<ContentType,TitleCatalog>()
    private fun text(url: String, signed: Signer.Request? = null): String {
        val request = Request.Builder().url(url).header("User-Agent",VendorConstants.VIDEO_UA).header("Accept-Language","zh-CN,zh;q=0.9")
        signed?.headers?.forEach { (k,v) -> request.header(k,v) }
        signed?.body?.let { request.post(it.toRequestBody("application/json; charset=utf-8".toMediaType())) }
        val built=request.build()
        return NetworkRetry.execute(built) { RequestScope.execute(http.newCall(built)) { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code,response.header("Retry-After"),"内容请求失败（HTTP ${response.code}）")
            val body = response.body ?: throw IOException("内容响应为空")
            if (body.contentLength() > 16*1024*1024) throw IOException("内容响应过大")
            val input=body.byteStream(); val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
            while(out.size()<=16*1024*1024) { val n=input.read(buffer); if(n<0) break; out.write(buffer,0,n) }; val bytes=out.toByteArray()
            if(bytes.size > 16*1024*1024) throw IOException("内容响应过大")
            String(bytes,Charsets.UTF_8)
        } }
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
        require(page in 1..100)
        return ComicRank.parse(text("$site/rank/hot-comic-drama?page=$page"),page)
    }
    fun home(page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(page in 1..100)
        if(type==ContentType.COMIC) return comicRanking(page)
        return category(page,type)
    }
    private fun category(page: Int,type: ContentType): CatalogPage {
        val path=if(type==ContentType.COMIC) "/category/comic-drama" else "/category?tab=1&sort_type=1"
        val data=router("$site$path"+if(page>1) (if('?' in path) "&" else "?")+"page=$page" else "")
        val section=data.optJSONObject("category_page") ?: data.optJSONObject("category_$") ?: throw IOException("首页数据结构已变化")
        val rows=section.optJSONArray("recommendList") ?: section.optJSONObject("categoryData")?.optJSONArray("recommendList") ?: throw IOException("首页数据暂不可用")
        val items=valid(rows.objects().map(::card))
        val lastPage=section.optJSONObject("pagination")?.optInt("totalPages",100) ?: 100
        if(section.optJSONObject("pagination")?.optInt("pageNum",page)!=page) throw IOException("片库分页信息异常")
        return CatalogPage(items,items.isNotEmpty() && page<minOf(lastPage,100))
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
        require(keyword.isNotBlank() && keyword.length<=80 && page in 1..100)
        val key=type to keyword
        val catalogSource=synchronized(catalogSearches) {
            catalogSearches.entries.removeAll { clock()-it.value.touched>300_000 || (page==1 && it.key==key) }
            key in catalogSearches
        }
        if(catalogSource) return catalogTitleSearch(keyword,page,type,progress)
        // A valid empty comic tab is authoritative; only an empty HTTP body uses public search.
        if(type==ContentType.COMIC) return try { appSearch(keyword,page,type) } catch(e: EmptySearchResponseException) {
            RequestScope.ensureActive(); if(page>1) throw e; catalogTitleSearch(keyword,page,type,progress)
        }
        val website=synchronized(websiteSearches) {
            websiteSearches.entries.removeAll { clock()-it.value>300_000 || (page==1 && it.key==key) }
            key in websiteSearches
        }
        if(!website) try { return appSearch(keyword,page,type) } catch (e: Exception) {
            RequestScope.ensureActive()
            if(e is EmptySearchResponseException && page==1) return catalogTitleSearch(keyword,page,type,progress)
            if(e is SearchSessionExpiredException) throw e
            if(page>1) throw e // Never switch sources halfway through a result set.
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
    private fun websiteKeywordCandidates(keyword: String,type: ContentType): List<Series> {
        val data=router("$site/search/${Signer.encode(keyword)}?page=1")
        val section=data.optJSONObject("search_(keyword)/page") ?: data.optJSONObject("search_page") ?: throw IOException("搜索数据暂不可用")
        if(section.has("isSuccess") && !section.optBoolean("isSuccess")) throw IOException("官网关键词搜索暂不可用")
        val rows=section.optJSONArray("searchList") ?: throw IOException("搜索数据结构已变化")
        val genre=if(type==ContentType.COMIC) "205" else "203"
        return valid(rows.objects().filter { (it.optJSONObject("video_data") ?: it).str("genre")==genre }.map(::card))
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
            if(index.nextPage>100) throw IOException("官网片库超过检索上限，请稍后重试")
            RequestScope.ensureActive()
            val result=category(index.nextPage,type)
            if(result.items.isEmpty()) throw IOException("官网片库数据不完整，请稍后重试")
            RequestScope.ensureActive()
            result.items.forEach { index.items.putIfAbsent(it.id,it) }
            index.nextPage++; index.complete=!result.hasMore; index.touched=clock()
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
            notice=(if(supplement.keywordAvailable) "官网关键词搜索＋公开片库" else "关键词搜索暂不可用 · 官网片库")+" · 已检索 ${items.size} 部")
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
        return CatalogPage(valid(list),tab.optBoolean("has_more",false) && page<100)
    }
    fun detail(id: String): Detail {
        require(id.matches(Regex("[0-9]{1,30}")))
        val o=router("$site/detail?series_id=$id").optJSONObject("detail_page")?.optJSONObject("seriesDetail") ?: throw IOException("剧集详情暂不可用")
        val raw=o.optJSONArray("vid_list") ?: throw IOException("分集暂不可用")
        val episodes=(0 until raw.length()).map { raw.getString(it) }
        if(episodes.isEmpty() || episodes.any { !it.matches(Regex("[0-9]{1,30}")) } || episodes.distinct().size!=episodes.size) throw IOException("分集数据异常")
        return Detail(card(o).copy(id=id),episodes)
    }
    fun stream(id: String, maxQuality: Int = 1080): StreamInfo {
        require(id.matches(Regex("[0-9]{1,30}")))
        val payload="""{"biz_param":{"detail_page_version":0,"device_level":3,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$id"]}}""".toByteArray()
        val signed=signer.sign("https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/v1/",VendorConstants.device,payload)
        val data=JSONObject(text(signed.url,signed))
        val sourceCode=data.optInt("Code",data.optInt("code",0))
        if(sourceCode!=0) throw ProviderPlaybackException(sourceCode)
        val map=data.optJSONObject("data") ?: throw IOException("播放信息为空")
        val entry=map.optJSONObject(id) ?: throw IOException("片源未返回所选分集，请稍后重试")
        val model=entry.optJSONObject("video_model") ?: JSONObject(entry.str("video_model"))
        val raw=model.opt("fallback_api")
        val fallback=when(raw) { is JSONObject -> raw.str("fallback_api"); is JSONArray -> raw.optString(0); else -> runCatching { JSONObject(raw.toString()).str("fallback_api") }.getOrDefault(raw.toString()) }
        if(!fallback.startsWith("https://")) throw IOException("播放地址格式异常")
        val info=JSONObject(text(fallback)).optJSONObject("video_info")?.optJSONObject("data") ?: throw IOException("没有播放资源")
        val videoList=info.optJSONObject("video_list") ?: throw IOException("没有可用清晰度")
        // ByteVC2 resources can expose playable audio but no video track in Android's MP4 extractor.
        // Keep missing legacy codec labels usable, but never select a known incompatible variant.
        val rows=videoList.keys().asSequence().mapNotNull { k -> videoList.optJSONObject(k)?.let { k to it } }.filter {
            it.second.str("main_url").isNotEmpty() && it.second.str("codec_type").lowercase() !in setOf("bytevc2","bvc2")
        }.map { (k,o) -> Triple(qualityNumber(o.str("quality_desc").ifEmpty { o.str("height").ifEmpty { o.str("vheight").ifEmpty { o.str("quality").ifEmpty { k } } } }),k,o) }.sortedByDescending { it.first }.toList()
        val selected=rows.firstOrNull { it.first<=maxQuality } ?: rows.lastOrNull() ?: throw IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集")
        val item=selected.third; var url=cleanUrl(item.str("main_url")); val seed=info.str("key_seed")
        if(seed.isNotEmpty()) url=MediaCrypto.decryptUrl(url,decode64(seed))
        if(!url.startsWith("https://") && !url.startsWith("http://")) throw IOException("播放地址无效")
        val key=item.str("spade_a").takeIf { it.isNotEmpty() }?.let(MediaCrypto::deriveKey)
        val quality=if(selected.first>0) "${selected.first}P"+(if(selected.first>maxQuality) "（兼容资源）" else "") else "自动"
        return StreamInfo(url,key,quality)
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
