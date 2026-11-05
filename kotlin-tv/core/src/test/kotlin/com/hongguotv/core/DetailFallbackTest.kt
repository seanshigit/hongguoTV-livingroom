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
import java.net.SocketException

class DetailFallbackTest {
    private fun repository(respond: (Request) -> String) = ContentRepository(OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(respond(chain.request()).toResponseBody()).build()
    }.build())

    private fun payload(): JSONObject {
        val rows=JSONArray(listOf(3,1,2).map { index -> JSONObject().put("vid","${10+index}")
            .put("vid_index",index).put("series_id","42") })
        return JSONObject().put("code",0).put("data",JSONObject().put("42",JSONObject().put("video_data",JSONObject()
            .put("series_id","42").put("series_id_str","42").put("series_title","测试剧集")
            .put("episode_cnt",3).put("video_list",rows))))
    }

    @Test fun websiteConnectionResetFallsBackToSignedDetailAndRestoresEpisodeOrder() {
        var fallbackCalls=0
        val repo=repository { request ->
            if(request.url.host=="hongguoduanju.com") throw SocketException("Connection reset")
            fallbackCalls++
            assertEquals("/novel/player/multi_video_detail/preload/v1",request.url.encodedPath)
            assertEquals("POST",request.method)
            val buffer=okio.Buffer(); request.body!!.writeTo(buffer)
            val body=JSONObject(buffer.readUtf8())
            assertEquals("42",body.getString("series_id")); assertEquals("preload",body.getString("dr_scene"))
            assertNotNull(request.header("x-medusa"))
            payload().toString()
        }
        val result=repo.detail("42")
        assertEquals("42",result.series.id); assertEquals("测试剧集",result.series.title)
        assertEquals(listOf("11","12","13"),result.episodes)
        assertEquals(1,fallbackCalls)
    }

    @Test fun healthyWebsiteDoesNotNeedAnotherSource() {
        val repo=repository { request ->
            assertEquals("hongguoduanju.com",request.url.host)
            """<script>_ROUTER_DATA={"loaderData":{"detail_page":{"seriesDetail":{"series_id":"42","series_title":"官网剧集","vid_list":["11","12"]}}}};</script>"""
        }
        assertEquals(listOf("11","12"),repo.detail("42").episodes)
    }

    @Test fun malformedWebsiteDetailAlsoUsesTheCompleteFallback() {
        val repo=repository { request ->
            if(request.url.host=="hongguoduanju.com") """<script>_ROUTER_DATA={"loaderData":{}};</script>"""
            else payload().toString()
        }
        assertEquals(listOf("11","12","13"),repo.detail("42").episodes)
    }

    @Test fun fallbackRejectsWrongSeriesMissingEpisodesAndInvalidOrdering() {
        val cases=listOf<(JSONObject)->Unit>(
            { it.put("code",110001) },
            { it.getJSONObject("data").put("99",it.getJSONObject("data").remove("42")) },
            { it.getJSONObject("data").getJSONObject("42").getJSONObject("video_data").put("series_id","99") },
            { it.getJSONObject("data").getJSONObject("42").getJSONObject("video_data").put("episode_cnt",4) },
            { it.getJSONObject("data").getJSONObject("42").getJSONObject("video_data").getJSONArray("video_list").getJSONObject(0).put("series_id","99") },
            { it.getJSONObject("data").getJSONObject("42").getJSONObject("video_data").getJSONArray("video_list").getJSONObject(0).put("vid_index",2) },
            { it.getJSONObject("data").getJSONObject("42").getJSONObject("video_data").getJSONArray("video_list").getJSONObject(0).put("vid","invalid") },
            { it.getJSONObject("data").getJSONObject("42").getJSONObject("video_data").getJSONArray("video_list").getJSONObject(0).put("vid","11") }
        )
        for(change in cases) {
            val response=payload().also(change)
            val repo=repository { request ->
                if(request.url.host=="hongguoduanju.com") throw SocketException("Connection reset")
                response.toString()
            }
            assertThrows(IOException::class.java) { repo.detail("42") }
        }
    }

    @Test fun cancelledWebsiteRequestNeverStartsFallback() {
        val scope=RequestScope(); var requests=0
        val repo=repository { requests++; scope.cancel(); throw IOException("cancelled") }
        assertThrows(IOException::class.java) { scope.run { repo.detail("42") } }
        assertEquals(1,requests)
    }
}
