package com.hongguotv.core

import com.sun.net.httpserver.HttpServer
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLPeerUnverifiedException

class NetworkRecoveryTest {
    private val catalog="""<script>window._ROUTER_DATA={"loaderData":{"category_page":{"recommendList":[{"series_id":"123","series_title":"恢复剧"}],"pagination":{"pageNum":1,"totalPages":1}}}};</script>"""
    private fun client(reply:(Request)->Pair<Int,String>)=OkHttpClient.Builder().addInterceptor { chain ->
        val (code,text)=reply(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test").body(text.toResponseBody()).build()
    }.build()
    @Test fun catalogRecoversFromTwoTransientResponses() {
        val requests=mutableListOf<Request>()
        val http=client { requests+=it;(if(requests.size<3) 503 else 200) to catalog }
        assertEquals("恢复剧",ContentRepository(http).home().items.single().title)
        assertEquals(3,requests.size);assertTrue(requests.all { it.url==requests.first().url && it.method=="GET" })
    }
    @Test fun transportFailureIsRetriedButPermanentAndSignedRequestsAreNot() {
        var count=0
        val http=client { if(++count==1) throw EOFException("unexpected end of stream");200 to catalog }
        assertEquals("123",ContentRepository(http).home().items.single().id);assertEquals(2,count)
        for(code in listOf(400,401,403,404,410,416,429)) {
            count=0;val permanent=client { count++;code to "" }
            assertThrows(IOException::class.java) { ContentRepository(permanent).home() };assertEquals(1,count)
        }
        count=0;val signed=client { count++;503 to "" }
        assertThrows(IOException::class.java) { ContentRepository(signed).stream("123") };assertEquals(1,count)
        count=0;val certificate=client { count++;throw SSLPeerUnverifiedException("certificate rejected") }
        assertThrows(SSLPeerUnverifiedException::class.java) { ContentRepository(certificate).home() };assertEquals(1,count)
    }
    @Test fun persistentFailureExhaustsTheBoundedBudget() {
        val count=AtomicInteger();val http=client { count.incrementAndGet();503 to "" }
        val error=assertThrows(IOException::class.java) { ContentRepository(http).home() }
        assertTrue(error.message.orEmpty().contains("503"));assertEquals(3,count.get())
    }
    @Test fun serverBackoffAndInvalidRangeAreNotRetried() {
        for(status in listOf(503,206)) {
            val count=AtomicInteger()
            val http=OkHttpClient.Builder().addInterceptor { chain ->
                count.incrementAndGet()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("test")
                    .header("Retry-After","120").header("Content-Range","bytes 99-100/1000").body("ok".toResponseBody()).build()
            }.build()
            assertThrows(IOException::class.java) {
                if(status==503) ContentRepository(http).home()
                else RemoteVideo(http,StreamInfo("https://example.invalid/video",null,"1080P")).use { it.prepare() }
            }
            assertEquals(1,count.get())
        }
    }
    @Test fun leavingThePageCancelsBackoffWithoutAnotherRequest() {
        val scope=RequestScope();val seen=CountDownLatch(1);val count=AtomicInteger();val failure=AtomicReference<Throwable>()
        val http=client { count.incrementAndGet();seen.countDown();503 to "" }
        val worker=Thread { try { scope.run { ContentRepository(http).home() } } catch(error:Throwable) { failure.set(error) } }
        worker.start()
        try { assertTrue(seen.await(2,TimeUnit.SECONDS));scope.cancel();worker.join(1000);assertFalse(worker.isAlive);assertEquals(1,count.get());assertTrue(failure.get() is IOException) }
        finally { scope.cancel();worker.interrupt();worker.join(1000) }
    }
    @Test fun rangeRecoveryReturnsExactlyTheRequestedBytesAfterATruncatedBody() {
        fun box(type:String,body:ByteArray)=be32(body.size+8)+type.toByteArray()+body
        val head=box("ftyp","isom0000".toByteArray())+box("moov",byteArrayOf())
        val bytes=head+ByteArray(512*1024-head.size) { (it%239).toByte() }
        val requests=java.util.Collections.synchronizedList(mutableListOf<String>())
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/video") { exchange ->
            val range=exchange.requestHeaders.getFirst("Range");requests+=range
            val (start,end)=range.removePrefix("bytes=").split('-').map(String::toInt)
            val data=bytes.copyOfRange(start,end+1)
            exchange.responseHeaders.add("Content-Range","bytes $start-$end/${bytes.size}")
            exchange.sendResponseHeaders(206,data.size.toLong())
            runCatching { exchange.responseBody.use { it.write(if(requests.size==2) data.copyOf(5) else data) } }
            exchange.close()
        }
        server.start();val http=OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        try {
            RemoteVideo(http,StreamInfo("http://127.0.0.1:${server.address.port}/video",null,"1080P")).use { video ->
                video.prepare();assertArrayEquals(bytes.copyOfRange(300000,300128),video.read(300000,128))
                assertEquals(listOf("bytes=0-262143","bytes=300000-300127","bytes=300000-300127"),requests.toList())
            }
        } finally { server.stop(0);http.connectionPool.evictAll();http.dispatcher.executorService.shutdownNow() }
    }

    @Test fun prepareMovesToTheNextStreamCandidateWhenTheFirstAddressFails() {
        fun box(type:String,body:ByteArray)=be32(body.size+8)+type.toByteArray()+body
        val bytes=box("ftyp","isom0000".toByteArray())+box("moov",byteArrayOf())+ByteArray(1024) { it.toByte() }
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/bad") { exchange -> exchange.sendResponseHeaders(502,0); exchange.close() }
        server.createContext("/good") { exchange ->
            val range=exchange.requestHeaders.getFirst("Range").removePrefix("bytes=").split('-').map(String::toInt)
            val end=minOf(range[1],bytes.lastIndex); val body=bytes.copyOfRange(range[0],end+1)
            exchange.responseHeaders.add("Content-Range","bytes ${range[0]}-$end/${bytes.size}")
            exchange.sendResponseHeaders(206,body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        server.start(); val http=OkHttpClient()
        try {
            val remote=RemoteVideo(http,StreamInfo("http://127.0.0.1:${server.address.port}/bad",null,"720P",
                listOf(StreamCandidate("http://127.0.0.1:${server.address.port}/good",null,"720P"))))
            remote.prepare(); assertTrue(remote.info.url.endsWith("/good")); assertEquals(1024+24,remote.total)
            remote.close()
        } finally { server.stop(0);http.connectionPool.evictAll();http.dispatcher.executorService.shutdownNow() }
    }

    @Test fun midStreamFailureSwitchesCandidateAndPreservesRequestedRange() {
        fun box(type:String,body:ByteArray)=be32(body.size+8)+type.toByteArray()+body
        val bytes=box("ftyp","isom0000".toByteArray())+box("moov",byteArrayOf())+ByteArray(512*1024) { (it%239).toByte() }
        val requests=java.util.Collections.synchronizedList(mutableListOf<String>())
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        fun serve(exchange: com.sun.net.httpserver.HttpExchange, failAfterHeader: Boolean) {
            val range=exchange.requestHeaders.getFirst("Range")
            requests += "${exchange.httpContext.path}:$range"
            if(failAfterHeader && !range.startsWith("bytes=0-")) {
                exchange.sendResponseHeaders(502,0); exchange.close(); return
            }
            val (start,end)=range.removePrefix("bytes=").split('-').map(String::toInt)
            val last=minOf(end,bytes.lastIndex); val body=bytes.copyOfRange(start,last+1)
            exchange.responseHeaders.add("Content-Range","bytes $start-$last/${bytes.size}")
            exchange.sendResponseHeaders(206,body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/primary") { exchange -> serve(exchange,true) }
        server.createContext("/backup") { exchange -> serve(exchange,false) }
        server.start(); val http=OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        try {
            RemoteVideo(http,StreamInfo("http://127.0.0.1:${server.address.port}/primary",null,"720P",
                listOf(StreamCandidate("http://127.0.0.1:${server.address.port}/backup",null,"720P")))).use { video ->
                video.prepare()
                assertArrayEquals(bytes.copyOfRange(300000,300128),video.read(300000,128))
                assertTrue(video.info.url.endsWith("/backup"))
                assertTrue(requests.any { it.startsWith("/backup:bytes=300000-300127") })
            }
        } finally { server.stop(0);http.connectionPool.evictAll();http.dispatcher.executorService.shutdownNow() }
    }
}
