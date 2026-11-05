package com.hongguotv.nativeapp

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.hongguotv.core.CloudHistoryClient
import com.hongguotv.core.CloudSeriesMetadata
import com.hongguotv.core.CloudSyncDiagnostics
import com.hongguotv.core.ContentRepository
import com.hongguotv.core.ContentType

/** Chooses an actual unseen single-season title using only read-only official requests. */
class AccountChooseNewAcceptance: Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        var result=Activity.RESULT_CANCELED
        val report=try {
            val account=checkNotNull(AccountVault(targetContext).load())
            val cloud=CloudHistoryClient(account)
            val known=cloud.readHistory().map { it.series.id }.toSet()
            val repo=ContentRepository()
            try {
                val metadata=CloudHistoryClient::class.java.getDeclaredMethod("seriesMetadata",String::class.java).apply { isAccessible=true }
                val titles=repo.home(1,ContentType.SHORT).items
                val ordered=listOfNotNull(titles.getOrNull(3),titles.getOrNull(5))+titles.take(15)
                val selected=ordered.distinctBy { it.id }.filterNot { it.id in known }.firstOrNull { item ->
                    val value=runCatching { metadata.invoke(cloud,item.id) as? CloudSeriesMetadata }.getOrNull()
                    value!=null && !value.multiSeason
                }
                result=Activity.RESULT_OK
                if(selected==null) "unseen-single-season: unavailable\n" else "unseen-single-season: ${selected.title}\n"
            } finally { repo.http.connectionPool.evictAll(); repo.http.dispatcher.executorService.shutdown() }
        } catch(error: Exception) { "selection failed: ${CloudSyncDiagnostics.describe(error)}\n" }
        finish(result,Bundle().apply { putString("stream",report) })
    }
}
