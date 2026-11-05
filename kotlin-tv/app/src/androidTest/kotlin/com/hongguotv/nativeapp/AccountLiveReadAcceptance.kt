package com.hongguotv.nativeapp

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.hongguotv.core.CloudHistoryClient
import com.hongguotv.core.CloudSyncDiagnostics
import com.hongguotv.core.CloudHistoryState
import java.security.MessageDigest
import okhttp3.OkHttpClient
import org.json.JSONObject

/** Reads one user-selected entry and reports only its episode and position. */
class AccountLiveReadAcceptance: Instrumentation() {
    private var title=""
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); title=arguments?.getString("title").orEmpty(); start() }
    override fun onStart() {
        var result=Activity.RESULT_CANCELED
        val report=try {
            require(title.isNotBlank())
            val account=checkNotNull(AccountVault(targetContext).load())
            val key=MessageDigest.getInstance("SHA-256").digest(account.userId.toByteArray()).joinToString("") { "%02x".format(it) }
            val encoded=targetContext.getSharedPreferences("official-history-v1",Activity.MODE_PRIVATE).getString(key,null)
            val local=encoded?.let { CloudHistoryState.decode(account.userId,it) }
            val own=local?.history()?.firstOrNull { it.series.title==title }
            var fields=""
            val http=OkHttpClient.Builder().addInterceptor { chain ->
                val response=chain.proceed(chain.request())
                if(chain.request().url.encodedPath=="/reading/bookapi/read_history/list/v" && own!=null) {
                    runCatching {
                        val list=JSONObject(response.peekBody(4L*1024*1024).string()).getJSONObject("data").getJSONArray("data_list")
                        for(i in 0 until list.length()) {
                            val row=list.optJSONObject(i) ?: continue
                            if(row.optString("book_id_str")!=own.series.id) continue
                            fields=listOf("duration","current_total_time","current_play_position","vid_index","episode_cnt")
                                .joinToString("; ") { field -> "$field=${row.opt(field)?.takeIf { it is Number } ?: "absent"}" }
                        }
                    }
                }
                response
            }.build()
            val rows=CloudHistoryClient(account,http).readHistory()
            val item=rows.firstOrNull { it.series.title==title }
            result=Activity.RESULT_OK
            val pending=local?.pending()?.count { it.progress.series.title==title } ?: 0
            val cloud=if(item==null) "cloud-record: absent" else "cloud-record: present; episode=${item.episodeIndex+1}; position_ms=${item.position}; duration_ms=${item.duration}"
            val saved=if(own==null) "local-record: absent" else "local-record: present; episode=${own.episodeIndex+1}; position_ms=${own.position}; duration_ms=${own.duration}; pending=$pending"
            "$cloud\n$saved\nserver-fields: $fields\n"
        } catch(error: Exception) {
            "cloud-read: failed; reason=${CloudSyncDiagnostics.describe(error)}\n"
        }
        finish(result,Bundle().apply { putString("stream",report) })
    }
}
