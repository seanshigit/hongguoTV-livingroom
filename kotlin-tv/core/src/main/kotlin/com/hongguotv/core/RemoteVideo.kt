// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.IOException

/** One playback stream. No listening socket or local HTTP service. */
class RemoteVideo(private val http: OkHttpClient, initialInfo: StreamInfo, private val cache: PlaybackCache? = null, private val cacheKey: String = ""): Closeable {
    @Volatile private var closed=false
    private val calls=mutableSetOf<Call>()
    private val candidates=initialInfo.candidates()
    private var candidateIndex=0
    private var preparedState: MediaCrypto.Prepared?=null
    @Volatile var info: StreamInfo=initialInfo
        private set
    data class Range(val bytes: ByteArray,val total: Long)
    var total: Long=0; private set
    @Volatile private var prefix=byteArrayOf()
    private fun currentCandidate() = candidates[candidateIndex]
    private fun resetPrepared() { preparedState=null; total=0; prefix=byteArrayOf() }
    private fun publishCandidate() {
        val candidate=currentCandidate()
        info=StreamInfo(candidate.url,candidate.key,candidate.quality,candidates.drop(candidateIndex+1))
    }
    private fun range(start: Long,end: Long): Range {
        if(closed) throw IOException("播放请求已取消")
        require(start>=0 && end>=start && end-start<8*1024*1024)
        val request=Request.Builder().url(currentCandidate().url).header("User-Agent",VendorConstants.VIDEO_UA).header("Referer","https://novel.snssdk.com/").header("Range","bytes=$start-$end").header("Accept-Encoding","identity").build()
        return NetworkRetry.execute(request) {
            if(closed) throw IOException("播放请求已取消")
            val call=http.newCall(request)
            synchronized(calls) { if(closed) throw IOException("播放请求已取消"); calls+=call }
            try {
                RequestScope.execute(call) { response ->
                    if(response.code!=206) throw HttpStatusException(response.code,response.header("Retry-After"),"视频服务器不支持分段读取（HTTP ${response.code}）")
                    val match=Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.header("Content-Range").orEmpty()) ?: throw IOException("视频分段响应异常")
                    val (begin,finish,size)=match.destructured
                    val full=size.toLong(); val last=minOf(end,full-1)
                    if(begin.toLong()!=start || finish.toLong()!=last || full<=0) throw IOException("视频分段范围不匹配")
                    if(total!=0L && full!=total) throw IOException("视频资源已变化，请重试")
                    val n=(last-start+1).toInt(); val body=response.body ?: throw IOException("视频分段为空")
                    val bytes=body.source().readByteArray(n.toLong())
                    Range(bytes,full)
                }
            } finally { synchronized(calls) { calls-=call } }
        }
    }
    @Synchronized fun prepare(): RemoteVideo {
        preparedState?.let { return this }
        var failure: IOException?=null
        while(candidateIndex<candidates.size) {
            try {
                resetPrepared()
                val first=range(0,256*1024-1); total=first.total; prefix=first.bytes
                val end=MediaCrypto.headerEnd(first.bytes,total)
                val header=if(end>first.bytes.size) first.bytes+range(first.bytes.size.toLong(),end-1L).bytes else first.bytes.copyOf(end)
                val next=MediaCrypto.prepare(header,currentCandidate().key)
                if(next.samples.any { it.end>total }) throw IOException("视频样本超出资源大小")
                preparedState=next
                return this
            } catch(e: Exception) {
                if(closed) throw IOException("播放请求已取消",e)
                failure=if(e is IOException) e else IOException("视频候选地址校验失败",e)
                if(candidateIndex+1>=candidates.size) break
                candidateIndex++
                resetPrepared(); publishCandidate()
            }
        }
        throw failure ?: IOException("没有可用播放地址")
    }
    /** Bounded warm-up. Retains raw bytes so decryption still uses the absolute sample offset. */
    @Synchronized fun warm(): RemoteVideo {
        prepare()
        val size=minOf(total,1024*1024L).toInt()
        if(prefix.size<size) prefix=prefix+range(prefix.size.toLong(),size-1L).bytes
        return this
    }
    private fun readCurrent(start: Long,length: Int): ByteArray {
        if(start>=total) return byteArrayOf()
        if(cacheKey.isNotBlank()) cache?.read(cacheKey,start,length)?.let { return it }
        val cached=prefix; val end=minOf(total,start+length)
        val available=(minOf(end,cached.size.toLong())-start).coerceAtLeast(0).toInt()
        val first=if(available>0) cached.copyOfRange(start.toInt(),start.toInt()+available) else byteArrayOf()
        val raw=if(start+available<end) first+range(start+available,end-1).bytes else first
        return MediaCrypto.decrypt(raw,start,currentCandidate().key,preparedState ?: throw IOException("视频尚未准备完成")).also {
            if(cacheKey.isNotBlank()) cache?.write(cacheKey,start,it)
        }
    }
    private fun canSwitchAfter(error: IOException): Boolean {
        if(closed || Thread.currentThread().isInterrupted) return false
        if(error.message.orEmpty().contains("取消",ignoreCase=true) || error.message.orEmpty().contains("cancel",ignoreCase=true)) return false
        if(error is HttpStatusException) return error.status in setOf(408,429,500,502,503,504)
        return true
    }
    @Synchronized fun read(start: Long,length: Int): ByteArray {
        require(length in 1..1024*1024 && start>=0)
        if(closed) throw IOException("播放请求已取消")
        while(true) {
            RequestScope.ensureActive()
            if(preparedState==null) prepare()
            try {
                return readCurrent(start,length)
            } catch(error: IOException) {
                RequestScope.ensureActive()
                if(!canSwitchAfter(error) || candidateIndex+1>=candidates.size) throw error
                candidateIndex++
                resetPrepared(); publishCandidate()
                // Rebuild the header/index on the replacement CDN, then retry the
                // exact original range so callers never receive duplicate bytes.
                prepare()
            }
        }
    }
    override fun close() { synchronized(calls) { closed=true; calls.toList().forEach { it.cancel() }; calls.clear() } }
}
