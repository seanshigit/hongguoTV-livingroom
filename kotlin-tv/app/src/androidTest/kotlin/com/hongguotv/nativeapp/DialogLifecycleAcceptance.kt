package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.View

/** A replaced account dialog must not clear the replacement's status callback. */
class DialogLifecycleAcceptance: Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        var result=Activity.RESULT_CANCELED
        var report=""
        var controls: TvTools?=null
        try {
            val activity=startActivitySync(Intent(Intent.ACTION_MAIN).setClassName(targetContext,"com.hongguotv.nativeapp.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            controls=TvTools(activity)
            val open=TvTools::class.java.getDeclaredMethod("open",AlertDialog::class.java,View::class.java,kotlin.jvm.functions.Function0::class.java).apply { isAccessible=true }
            var state="initial"
            lateinit var second: AlertDialog
            runOnMainSync {
                val first=AlertDialog.Builder(activity).setTitle("first").create()
                second=AlertDialog.Builder(activity).setTitle("second").create()
                open.invoke(controls,first,null,{ state="old dismissed" })
                open.invoke(controls,second,null,{ state="new dismissed" })
                state="new active"
            }
            waitForIdleSync()
            check(state=="new active") { "Old dismissal cleared the new dialog callback" }
            runOnMainSync { second.dismiss() }
            waitForIdleSync()
            check(state=="new dismissed")
            result=Activity.RESULT_OK
            report="PASS: old dialog cleanup cannot overwrite the active dialog state\n"
        } catch(e: Throwable) { report="FAIL: ${e.javaClass.simpleName}: ${e.message?.take(160)}\n" }
        finally { runOnMainSync { controls?.destroy() } }
        finish(result,Bundle().apply { putString("stream",report) })
    }
}
