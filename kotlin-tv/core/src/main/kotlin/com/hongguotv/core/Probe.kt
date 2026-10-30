package com.hongguotv.core

fun main(args: Array<String>) {
    val repository=if(args.contains("--browse") || args.contains("--playback")) ContentRepository(okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
        val response=chain.proceed(chain.request())
        if(chain.request().url.host.contains("fqnovel")) {
            val prefix=response.peekBody(16).bytes()
            println("searchTransport status=${response.code}; mime=${response.header("Content-Type")}; encoding=${response.header("Content-Encoding")}; bytes=${response.body?.contentLength()}; startsJson=${prefix.firstOrNull()?.toInt()==123}; prefixHex=${prefix.take(4).joinToString("") { "%02x".format(it) }}")
        }
        if(chain.request().url.host=="log5-applog-lf.fqnovel.com") {
            val body=response.peekBody(64*1024).string()
            val registration=runCatching { org.json.JSONObject(body) }.getOrNull()
            println("deviceRegistrationTransport status=${response.code}; bytes=${body.toByteArray(Charsets.UTF_8).size}; code=${registration?.opt("Code") ?: registration?.opt("code")}; hasDeviceId=${registration?.has("device_id") == true || registration?.has("device_id_str") == true}; hasInstallId=${registration?.has("install_id") == true || registration?.has("install_id_str") == true}")
        }
        if(!response.isSuccessful) println("catalogTransport status=${response.code}; path=${chain.request().url.encodedPath}; page=${chain.request().url.queryParameter("page")}")
        if(chain.request().url.encodedPath.contains("multi_video_model")) {
            val data=runCatching { org.json.JSONObject(response.peekBody(16384).string()) }.getOrNull()
            println("playbackTransport status=${response.code}; code=${data?.opt("Code") ?: data?.opt("code")}; message=${data?.optString("Message",data.optString("message"))?.take(160)}")
        }
        response
    }.connectTimeout(15,java.util.concurrent.TimeUnit.SECONDS).readTimeout(30,java.util.concurrent.TimeUnit.SECONDS).callTimeout(45,java.util.concurrent.TimeUnit.SECONDS).build()) else ContentRepository()
    if(args.contains("--playback")) {
        var playable=0
        try {
            for(type in ContentType.entries) for(series in repository.home(type=type).items.take(2)) {
                var stage="detail"
                try {
                    val detail=repository.detail(series.id); stage="stream"
                    val stream=repository.stream(detail.episodes.first(),720); stage="range/index"
                    RemoteVideo(repository.http,stream).use { remote ->
                        remote.prepare(); val bytes=remote.read(0,65536)
                        println("playback type=${type.label}; title=${series.title}; quality=${stream.quality}; bytes=${bytes.size}; header=${String(bytes,4,4)}; total=${remote.total}")
                        playable++
                    }
                } catch(e: Exception) { println("playback type=${type.label}; title=${series.title}; stage=$stage; error=${e.javaClass.simpleName}: ${e.message?.replace(Regex("https?://\\S+"),"[address]")}") }
            }
        } finally { repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown() }
        check(playable>0) { "所有播放样本均失败，请检查上游接口" }
        return
    }
    if(args.contains("--browse")) {
        try {
            for(type in ContentType.entries) {
                val home=repository.discover(type)
                check(home.items.isNotEmpty() && home.items.distinctBy { it.id }.size==home.items.size)
                println("type=${type.label}; home=${home.items.size}; sourcePages=${home.loadedPages}; shelves=${DiscoveryShelves.from(home.items).map { it.title to it.items.size }}; notice=${home.notice}")
                val keyword=if(type==ContentType.COMIC) "修仙" else "总裁"
                val results=SearchCatalog({ keyword,page,kind -> repository.search(keyword,page,kind) },supplement={ keyword,page,kind -> repository.searchSupplement(keyword,page,kind) })
                val seen=mutableSetOf<String>()
                for(page in 1..1000) {
                    val result=results.page(keyword,page,type)
                    check(result.items.none { it.id in seen })
                    seen.addAll(result.items.map { it.id })
                    println("query=$keyword; page=$page; visible=${result.items.size}; loaded=${result.foundCount}; hasMore=${result.hasMore}; notice=${result.notice}")
                    if(!result.hasMore) break
                }
                val exact=home.items.first().title.take(80)
                val matches=SearchCatalog({ keyword,page,kind -> repository.search(keyword,page,kind) },supplement={ keyword,page,kind -> repository.searchSupplement(keyword,page,kind) }).page(exact,1,type)
                println("exactQuery=$exact; found=${matches.foundCount}; first=${matches.items.firstOrNull()?.title}; exactFound=${matches.items.any { it.title==exact }}")
            }
        } finally {
            repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if(args.contains("--ranking")) {
        try {
            val first=repository.comicRanking()
            val metadata=checkNotNull(first.ranking)
            check(first.items.isNotEmpty())
            val seen=mutableSetOf<String>()
            for(pageNumber in listOf(1,2,metadata.totalPages).distinct().filter { it<=metadata.totalPages }) {
                val result=if(pageNumber==1) first else repository.comicRanking(pageNumber)
                val ranking=checkNotNull(result.ranking)
                check(result.items.isNotEmpty() && result.items.none { it.id in seen })
                seen.addAll(result.items.map { it.id })
                val ranks=result.items.map { checkNotNull(ranking.positions[it.id]).rank }
                check(ranks.all { it!=null && it>0 } && ranks.filterNotNull().zipWithNext().all { (a,b) -> a<b })
                check(result.items.all { ranking.positions[it.id]!!.heat.isNotBlank() })
                check(result.hasMore==(pageNumber<ranking.totalPages))
                println("rankingPage=$pageNumber/${ranking.totalPages}; items=${result.items.size}; ranks=${ranks.first()}..${ranks.last()}; firstHeat=${ranking.positions[result.items.first().id]!!.heat}; updated=${ranking.updatedText}")
            }
        } finally {
            repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    val type=if(args.contains("--comic")) ContentType.COMIC else ContentType.SHORT
    val keyword=if(type==ContentType.COMIC) "修仙" else "总裁"
    val search=repository.search(keyword,type=type); println("search=${search.items.size}; hasMore=${search.hasMore}")
    if(type==ContentType.COMIC && search.hasMore) {
        val next=repository.search(keyword,2,type)
        println("searchPage2=${next.items.size}; overlap=${search.items.map { it.id }.intersect(next.items.map { it.id }.toSet()).size}")
    }
    val home=repository.home(type=type); println("type=${type.label}; home=${home.items.size}; hasMore=${home.hasMore}")
    if(type==ContentType.COMIC) {
        val next=repository.home(2,type)
        println("homePage2=${next.items.size}; overlap=${home.items.map { it.id }.intersect(next.items.map { it.id }.toSet()).size}")
    }
    val detail=repository.detail(args.firstOrNull { it.matches(Regex("[0-9]{1,30}")) } ?: home.items.first().id)
    println("detail=${detail.series.title}; episodes=${detail.episodes.size}")
    val stream=repository.stream(detail.episodes.first())
    RemoteVideo(repository.http,stream).use { remote ->
        remote.prepare(); val first=remote.read(0,1024*1024)
        val tail=remote.read((remote.total-65536).coerceAtLeast(0),65536)
        println("quality=${stream.quality}; total=${remote.total}; first=${first.size}; tail=${tail.size}; header=${String(first,4,4)}")
        if(args.contains("--save")) java.io.File("/tmp/hongguo-kotlin-first.mp4").writeBytes(first)
    }
    repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
}
