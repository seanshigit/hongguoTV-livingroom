// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.text.Normalizer

/** Freeze displayed pages so later results never move an earlier selection. */
class SearchCatalog(
    private val load: (String,Int,ContentType)->CatalogPage,
    private val clock: ()->Long=System::currentTimeMillis,
    private val supplement: ((String,Int,ContentType)->CatalogPage)? = null,
) {
    private class Session(var touched: Long,var supplementMore: Boolean) {
        val pages=mutableListOf<CatalogPage>()
        val pending=mutableListOf<Series>()
        val seen=mutableSetOf<String>()
        var sourcePage=1
        var supplementPage=1
        var more=true
        var notice=""
    }
    private val sessions=linkedMapOf<Pair<ContentType,String>,Session>()

    @Synchronized fun page(keyword: String,page: Int,type: ContentType): CatalogPage {
        require(keyword.isNotBlank() && keyword.length<=80 && page in 1..MAX_CATALOG_PAGES)
        val key=type to keyword
        sessions.entries.removeAll { clock()-it.value.touched>300_000 }
        val session=sessions[key] ?: if(page==1) Session(clock(),supplement!=null).also { sessions[key]=it } else throw SearchSessionExpiredException()
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
            if(result.notice=="官网关键词搜索（公开接口）" || result.notice=="官网关键词搜索") session.supplementMore=false
            session.sourcePage++
            session.more=result.hasMore && session.sourcePage<=MAX_CATALOG_PAGES
            if(result.hasMore && !session.more) notice="已达到公开片库安全分页上限，可换更具体的剧名继续查找"
        }
        RequestScope.ensureActive()
        if(supplement!=null) for(attempt in 1..3) {
            if(!session.supplementMore || (attempt>1 && session.pending.size>=24)) break
            val result=try { supplement.invoke(keyword,session.supplementPage,type) } catch(e: Exception) {
                RequestScope.ensureActive()
                session.supplementMore=false
                notice="官网补充搜索暂不可用，结果可能不完整"
                session.notice=notice
                break
            }
            session.pending+=result.items.filter { session.seen.add(it.id) }
            session.supplementPage++
            session.supplementMore=result.hasMore && session.supplementPage<=MAX_CATALOG_PAGES
            if(result.hasMore && !session.supplementMore) notice="已达到公开片库安全分页上限，可换更具体的剧名继续查找"
        }
        if(page==1) {
            val season=seasonQuery(keyword)
            if(season!=null) {
                val expansions=session.pending.mapNotNull { expandSeason(it,season) }
                    .filter { normalized(it)!=normalized(keyword) }.distinct().take(3)
                for(expanded in expansions) {
                    RequestScope.ensureActive()
                    val related=try { supplement?.invoke(expanded,1,type)?.items ?: load(expanded,1,type).items } catch(e: Exception) {
                        RequestScope.ensureActive()
                        continue
                    }
                    session.pending+=related.filter { matchesSeason(it,season) && session.seen.add(it.id) }
                }
            } else {
                val root=normalized(keyword)
                for((expanded,target) in nextSeasonQueries(session.pending,root)) {
                    RequestScope.ensureActive()
                    val related=try { supplement?.invoke(expanded,1,type)?.items ?: load(expanded,1,type).items } catch(e: Exception) {
                        RequestScope.ensureActive()
                        continue
                    }
                    session.pending.addAll(0,related.filter {
                        matchesSeason(it,target) && normalized(it.title)==normalized(expanded) && session.seen.add(it.id)
                    })
                }
            }
        }
        session.pending.sortBy { relevance(it,keyword) }
        val items=session.pending.take(24)
        session.pending.subList(0,items.size).clear()
        if(items.isEmpty() && (session.more || session.supplementMore)) notice="接口本批未返回新剧，下一页继续查找"
        return CatalogPage(items,session.pending.isNotEmpty() || session.more || session.supplementMore,notice=notice,foundCount=session.seen.size).also { session.pages+=it }
    }

    companion object {
        private fun normalized(value: String)=Normalizer.normalize(value,Normalizer.Form.NFKC).lowercase(java.util.Locale.ROOT).filter { it.isLetterOrDigit() }
        private val seasonPattern=Regex("第?([0-9一二三四五六七八九十百零〇两]{1,4})季")
        private data class SeasonQuery(val root: String,val number: Int)
        private fun seasonNumber(value: String): Int? {
            value.toIntOrNull()?.let { return it.takeIf { n -> n in 1..999 } }
            val digits="零一二三四五六七八九"
            var total=0; var current=0
            for(ch in value) when(ch) {
                '十' -> { total+=(if(current==0) 1 else current)*10; current=0 }
                '百' -> { total+=(if(current==0) 1 else current)*100; current=0 }
                '〇' -> current=0
                '两' -> current=2
                else -> { val digit=digits.indexOf(ch); if(digit<0) return null; current=digit }
            }
            return (total+current).takeIf { it in 1..999 }
        }
        private fun seasonQuery(keyword: String): SeasonQuery? {
            val text=normalized(keyword)
            val match=seasonPattern.findAll(text).lastOrNull()?.takeIf { it.range.last==text.lastIndex } ?: return null
            val root=text.substring(0,match.range.first).takeIf { it.length>=3 } ?: return null
            return SeasonQuery(root,seasonNumber(match.groupValues[1]) ?: return null)
        }
        private fun matchesRoot(title: String,root: String): Boolean {
            val value=normalized(title)
            if(value.startsWith(root)) return true
            return root.length>=4 && value.length>=root.length && root.indices.count { root[it]!=value[it] }<=1
        }
        private fun matchesSeason(series: Series,season: SeasonQuery): Boolean {
            val title=normalized(series.title)
            val found=seasonPattern.findAll(title).lastOrNull()?.takeIf { it.range.last==title.lastIndex }
            return matchesRoot(title,season.root) && found!=null && seasonNumber(found.groupValues[1])==season.number
        }
        private fun expandSeason(series: Series,season: SeasonQuery): String? {
            if(!matchesRoot(series.title,season.root)) return null
            val match=seasonPattern.findAll(series.title).lastOrNull()?.takeIf { it.range.last==series.title.lastIndex } ?: return null
            val number=season.number
            val digits="零一二三四五六七八九"
            val suffix=when(number) {
                in 1..9 -> digits[number].toString()
                in 10..19 -> "十"+(if(number%10==0) "" else digits[number%10].toString())
                in 20..99 -> "${digits[number/10]}十"+(if(number%10==0) "" else digits[number%10].toString())
                else -> number.toString()
            }
            return series.title.substring(0,match.range.first)+"第${suffix}季"
        }
        private fun nextSeasonQueries(items: List<Series>,root: String): List<Pair<String,SeasonQuery>> {
            if(root.length<3) return emptyList()
            val latest=linkedMapOf<String,Pair<Series,Int>>()
            for(series in items) {
                if(!matchesRoot(series.title,root)) continue
                val match=seasonPattern.findAll(series.title).lastOrNull()?.takeIf { it.range.last==series.title.lastIndex } ?: continue
                val number=seasonNumber(match.groupValues[1]) ?: continue
                if(number>=999) continue
                val stem=normalized(series.title.substring(0,match.range.first))
                if(number>(latest[stem]?.second ?: 0)) latest[stem]=series to number
            }
            return latest.values.take(3).mapNotNull { (series,number) ->
                val target=SeasonQuery(root,number+1)
                expandSeason(series,target)?.let { it to target }
            }
        }
        fun relevance(series: Series,keyword: String): Int {
            val title=normalized(series.title); val query=normalized(keyword)
            if(query.isEmpty()) return 4
            return when {
                title==query -> 0
                seasonQuery(keyword)?.let { matchesSeason(series,it) }==true -> 1
                title.startsWith(query) -> 1
                title.contains(query) -> 2
                normalized(series.tags+series.description).contains(query) -> 3
                else -> 4
            }
        }
    }
}
