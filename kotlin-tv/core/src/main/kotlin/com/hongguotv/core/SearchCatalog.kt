// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.text.Normalizer

/** Freeze displayed pages so later results never move an earlier selection. */
class SearchCatalog(private val load: (String,Int,ContentType)->CatalogPage, private val clock: ()->Long=System::currentTimeMillis) {
    private class Session(var touched: Long) {
        val pages=mutableListOf<CatalogPage>()
        val pending=mutableListOf<Series>()
        val seen=mutableSetOf<String>()
        var sourcePage=1
        var more=true
        var notice=""
    }
    private val sessions=linkedMapOf<Pair<ContentType,String>,Session>()

    @Synchronized fun page(keyword: String,page: Int,type: ContentType): CatalogPage {
        require(keyword.isNotBlank() && keyword.length<=80 && page in 1..MAX_CATALOG_PAGES)
        val key=type to keyword
        sessions.entries.removeAll { clock()-it.value.touched>300_000 }
        val session=sessions[key] ?: if(page==1) Session(clock()).also { sessions[key]=it } else throw SearchSessionExpiredException()
        session.touched=clock()
        while(sessions.size>6) sessions.remove(sessions.keys.first())
        if(page<=session.pages.size) return session.pages[page-1].copy(foundCount=session.seen.size)
        require(page==session.pages.size+1)
        var notice=session.notice
        // Bounded work keeps an upstream loop or empty advertising page from hanging the TV.
        for(attempt in 1..3) {
            if(session.pending.size>=24 || !session.more) break
            RequestScope.ensureActive()
            val result=try { load(keyword,session.sourcePage,type) } catch(e: Exception) {
                RequestScope.ensureActive()
                if(e is SearchSessionExpiredException || session.pending.isEmpty()) throw e
                notice="部分结果加载失败，下一页可重试"
                break
            }
            session.pending+=result.items.filter { session.seen.add(it.id) }
            if(result.notice.isNotBlank()) { session.notice=result.notice; notice=result.notice }
            session.sourcePage++
            session.more=result.hasMore && session.sourcePage<=MAX_CATALOG_PAGES
            if(result.hasMore && !session.more) notice="已达到公开片库安全分页上限，可换更具体的剧名继续查找"
        }
        RequestScope.ensureActive()
        session.pending.sortBy { relevance(it,keyword) }
        val items=session.pending.take(24)
        session.pending.subList(0,items.size).clear()
        if(items.isEmpty() && session.more) notice="接口本批未返回新剧，下一页继续查找"
        return CatalogPage(items,session.pending.isNotEmpty() || session.more,notice=notice,foundCount=session.seen.size).also { session.pages+=it }
    }

    companion object {
        private fun normalized(value: String)=Normalizer.normalize(value,Normalizer.Form.NFKC).lowercase(java.util.Locale.ROOT).filter { it.isLetterOrDigit() }
        fun relevance(series: Series,keyword: String): Int {
            val title=normalized(series.title); val query=normalized(keyword)
            if(query.isEmpty()) return 4
            return when {
                title==query -> 0
                title.startsWith(query) -> 1
                title.contains(query) -> 2
                normalized(series.tags+series.description).contains(query) -> 3
                else -> 4
            }
        }
    }
}
