package com.hongguotv.nativeapp

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.hongguotv.core.AccountSession
import com.hongguotv.core.CloudHistoryClient
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject

/** Verifies Android's JSON encoder preserves numeric 19-digit IDs in cloud requests. */
class AccountJsonAcceptance: Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        var result=Activity.RESULT_CANCELED
        var report=""
        try {
            val session=AccountSession.decode(JSONObject().put("uid","123").put("name","fixture")
                .put("cookie","sessionid=fixture").put("device",JSONObject().put("device_id","333").put("iid","444")
                    .put("cdid","11111111-2222-3333-4444-555555555555")).toString())
            var sent=""
            val http=OkHttpClient.Builder().addInterceptor { chain ->
                val request=chain.request()
                val reply=when(request.url.encodedPath) {
                    "/passport/account/info/v2/" -> """{"message":"success","data":{"user_id_str":"123"}}"""
                    "/reading/bookapi/read_history/list/v" -> """{"code":0,"data":{"data_list":[{"book_id_str":"7690163276214176792","book_name":"测试","vid":"7690163276214176793","vid_index":1,"read_timestamp_ms":1700000000000}],"has_more":false}}"""
                    "/reading/bookapi/read_progress/get/v" -> {
                        val buffer=Buffer(); request.body!!.writeTo(buffer); sent=buffer.readUtf8()
                        """{"code":0,"video_data":[]}"""
                    }
                    else -> error("unexpected request")
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(reply.toResponseBody()).build()
            }.build()
            CloudHistoryClient(session,http).readHistory()
            check(sent.contains("[7690163276214176792]")) { "Fixture encoded as $sent" }
            result=Activity.RESULT_OK
            report="PASS: Android production request retains the 19-digit numeric ID\n"
        } catch(e: Throwable) { report="FAIL: ${e.javaClass.simpleName}: ${e.message?.take(160)}\n" }
        finish(result,Bundle().apply { putString("stream",report) })
    }
}
