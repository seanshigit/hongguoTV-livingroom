package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SearchCatalogTest {
    @Test fun groupedResultsAreNotTruncatedAndBackNavigationDoesNotRequestAgain() {
        var requests=0
        val search=SearchCatalog({ _,_,_ -> requests++; CatalogPage((1..67).map { Series("$it","剧 $it") },false) })
        val first=search.page("剧",1,ContentType.COMIC)
        val second=search.page("剧",2,ContentType.COMIC)
        val third=search.page("剧",3,ContentType.COMIC)
        assertEquals(67,(first.items+second.items+third.items).map { it.id }.distinct().size)
        assertTrue(first.hasMore); assertTrue(second.hasMore); assertFalse(third.hasMore)
        assertEquals(second,search.page("剧",2,ContentType.COMIC)); assertEquals(1,requests)
    }
    @Test fun duplicatesAcrossPagesAreRemovedAndExactTitleLeads() {
        val search=SearchCatalog({ _,page,_ ->
            if(page==1) CatalogPage((1..20).map { Series("$it","相关推荐 $it") },true)
            else CatalogPage(listOf(Series("1","重复"),Series("21","修仙"),Series("22","修仙外传"),Series("23","我的修仙记")),false)
        })
        val result=search.page("修仙",1,ContentType.COMIC)
        assertEquals(listOf("21","22","23"),result.items.take(3).map { it.id })
        assertEquals(23,result.items.size); assertEquals(23,result.foundCount); assertFalse(result.hasMore)
    }
    @Test fun abbreviatedSeasonQueryFindsNewSeasonDespiteOneMistypedCharacter() {
        for (query in listOf("聚宝先盆第十五季", "聚宝仙盆第15季")) {
            val search = SearchCatalog({ keyword, _, type ->
                assertEquals(ContentType.COMIC, type)
                when (keyword) {
                    query -> CatalogPage(listOf(
                        Series("14", "聚宝仙盆之杂灵根才是真BOSS第十四季"),
                        Series("other", "其他故事第十五季")
                    ), false)
                    "聚宝仙盆之杂灵根才是真BOSS第十五季" -> CatalogPage(listOf(
                        Series("15", "聚宝仙盆之杂灵根才是真BOSS第十五季"),
                        Series("other", "其他故事第十五季")
                    ), false)
                    else -> CatalogPage(emptyList(), false)
                }
            })
            val result = search.page(query, 1, ContentType.COMIC)
            assertEquals("15", result.items.first().id)
            assertEquals(1, result.items.count { it.id == "15" })
        }
    }
    @Test fun broadSeriesSearchFindsTheNextSeasonMissingFromTheInitialSourcePage() {
        val search=SearchCatalog({ keyword,_,_ ->
            when(keyword) {
                "聚宝仙盆" -> CatalogPage(listOf(
                    Series("14","聚宝仙盆之杂灵根才是真BOSS第十四季"),
                    Series("12","聚宝仙盆之杂灵根才是真BOSS第十二季")
                ),false)
                "聚宝仙盆之杂灵根才是真BOSS第十五季" -> CatalogPage(listOf(
                    Series("15","聚宝仙盆之杂灵根才是真BOSS第十五季")
                ),false)
                else -> CatalogPage(emptyList(),false)
            }
        })
        val result=search.page("聚宝仙盆",1,ContentType.COMIC)
        assertTrue(result.items.any { it.id=="15" })
        assertEquals(3,result.items.map { it.id }.distinct().size)
    }
    @Test fun appResultsAreSupplementedByIndependentWebsiteMatches() {
        val search=SearchCatalog(
            { _,_,_ -> CatalogPage(listOf(Series("1","修仙第一部")),false) },
            supplement={ _,_,_ -> CatalogPage(listOf(Series("1","修仙第一部"),Series("2","修仙第二部")),false) }
        )
        val result=search.page("修仙",1,ContentType.COMIC)
        assertEquals(listOf("1","2"),result.items.map { it.id })
    }
    @Test fun independentWebsitePagesRemainReachableAfterAppResultsEnd() {
        val websitePages=mutableListOf<Int>()
        val search=SearchCatalog(
            { _,_,_ -> CatalogPage((1..24).map { Series("$it","修仙剧 $it") },false) },
            supplement={ _,page,type ->
                assertEquals(ContentType.COMIC,type)
                websitePages+=page
                val ids=when(page) { 1 -> listOf(20)+(25..48).toList(); 2 -> (49..72).toList(); 3 -> (73..80).toList(); else -> error("unexpected page $page") }
                CatalogPage(ids.map { Series("$it","修仙剧 $it") },page<3)
            }
        )
        val first=search.page("修仙",1,ContentType.COMIC)
        val all=first.items.toMutableList()
        var page=1; var more=first.hasMore
        while(more && page<10) { val next=search.page("修仙",++page,ContentType.COMIC); all+=next.items; more=next.hasMore }
        assertFalse(more)
        assertEquals(80,all.size); assertEquals(80,all.map { it.id }.distinct().size)
        assertEquals(listOf(1,2,3),websitePages)
        assertEquals(first.items,search.page("修仙",1,ContentType.COMIC).items)
    }
    @Test fun failedNextPageCanBeRetriedWithoutLosingTheEarlierPage() {
        var offline=true
        val search=SearchCatalog({ _,page,_ ->
            if(page==2 && offline) throw IOException("offline")
            CatalogPage(((page-1)*24+1..page*24).map { Series("$it","剧 $it") },page==1)
        })
        val first=search.page("剧",1,ContentType.SHORT)
        assertThrows(IOException::class.java) { search.page("剧",2,ContentType.SHORT) }
        offline=false
        val second=search.page("剧",2,ContentType.SHORT)
        assertEquals("25",second.items.first().id); assertEquals(first,first.copy())
        assertEquals(first.items,search.page("剧",1,ContentType.SHORT).items)
    }
    @Test fun emptyUpstreamPagesDoNotPretendTheSearchIsComplete() {
        val search=SearchCatalog({ _,page,_ -> CatalogPage(if(page==4) listOf(Series("1","剧")) else emptyList(),page<4) })
        val first=search.page("剧",1,ContentType.COMIC)
        assertTrue(first.items.isEmpty()); assertTrue(first.hasMore); assertTrue(first.notice.isNotBlank())
        assertEquals("1",search.page("剧",2,ContentType.COMIC).items.single().id)
    }
    @Test fun cancellationNeverStartsAnotherSourceRequest() {
        val scope=RequestScope(); var requests=0
        val search=SearchCatalog({ _,_,_ -> requests++; scope.cancel(); CatalogPage(listOf(Series("1","剧")),true) })
        assertThrows(IOException::class.java) { scope.run { search.page("剧",1,ContentType.COMIC) } }
        assertEquals(1,requests)
    }
    @Test fun typeAndQuerySessionsStaySeparateAndExpire() {
        var now=0L
        val search=SearchCatalog({ query,_,type -> CatalogPage((1..30).map { Series("$it","${type.label}$query$it") },false) },{ now })
        search.page("剧",1,ContentType.COMIC); search.page("剧",1,ContentType.SHORT)
        assertTrue(search.page("剧",2,ContentType.COMIC).items.first().title.startsWith("漫剧"))
        now=300001
        assertThrows(SearchSessionExpiredException::class.java) { search.page("剧",2,ContentType.SHORT) }
    }
    @Test fun sourcePagingContinuesPastOneHundredPages() {
        val target=101
        val search=SearchCatalog({ _,page,_ ->
            CatalogPage((1..24).map { Series("${page}_$it","第 $page 页剧集 $it") },page<target)
        })
        var found=false
        for(page in 1..target) found = found || search.page("剧",page,ContentType.SHORT).items.any { it.title.startsWith("第 101 页") }
        assertTrue(found)
    }
    @Test fun topicsUseRealTagsAndEveryLoadedSeriesAppearsOnce() {
        val items=(1..60).map { Series("$it","剧 $it",tags=if(it%2==0) "玄幻 · 修仙" else "都市 · 爱情") }
        val shelves=DiscoveryShelves.from(items+items.take(2))
        assertTrue(shelves.any { it.key=="fantasy" }); assertTrue(shelves.any { it.key=="urban" })
        assertEquals(60,shelves.flatMap { it.items }.size)
        assertEquals(60,shelves.flatMap { it.items }.map { it.id }.distinct().size)
    }
}
