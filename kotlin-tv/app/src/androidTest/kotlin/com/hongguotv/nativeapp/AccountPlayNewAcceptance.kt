package com.hongguotv.nativeapp

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.media3.ui.PlayerView
import com.hongguotv.core.CloudHistoryClient
import com.hongguotv.core.CloudSeriesMetadata
import com.hongguotv.core.CloudSyncDiagnostics

/** User-authorized playback of one real, previously unseen single-season title. */
class AccountPlayNewAcceptance: Instrumentation() {
    private lateinit var activity: Activity
    private var playbackAuthorized = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        playbackAuthorized = arguments?.getString("confirm_real_playback") == "yes"
        start()
    }
    private fun views(view: View): List<View> = listOf(view)+(if(view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList())
    private fun all()=views(activity.window.decorView)
    private fun <T> ui(action: ()->T): T {
        var value: T?=null; var failure: Throwable?=null
        runOnMainSync { try { value=action() } catch(error: Throwable) { failure=error } }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun await(name: String,condition: ()->Boolean) {
        val until=SystemClock.elapsedRealtime()+120_000
        while(SystemClock.elapsedRealtime()<until) { if(ui(condition)) return; SystemClock.sleep(200) }
        error("Timed out: $name")
    }
    override fun onStart() {
        if (!playbackAuthorized) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "Real-account playback requires -e confirm_real_playback yes\n") })
            return
        }
        var result=Activity.RESULT_CANCELED
        var report=""
        try {
            val account=checkNotNull(AccountVault(targetContext).load())
            val cloud=CloudHistoryClient(account)
            val known=cloud.readHistory().map { it.series.id }.toSet()
            val metadata=CloudHistoryClient::class.java.getDeclaredMethod("seriesMetadata",String::class.java).apply { isAccessible=true }
            activity=startActivitySync(Intent(Intent.ACTION_MAIN).setClassName(targetContext,"com.hongguotv.nativeapp.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("home") { all().filterIsInstance<TextView>().any { it.text.toString()=="短剧" && it.isFocusable } }
            ui { all().filterIsInstance<TextView>().first { it.text.toString()=="短剧" && it.isFocusable }.performClick() }
            await("short catalog") { all().filterIsInstance<TextView>().any { it.text.toString()=="短剧" && it.isSelected } &&
                all().any { it.tag?.toString()?.startsWith("hot:")==true && it.isShown } }
            val candidates=ui { all().filter { it.isShown && it.tag?.toString()?.startsWith("hot:")==true }
                .map { view -> Triple(view,view.tag.toString().removePrefix("hot:"),((view as? ViewGroup)?.getChildAt(1) as? TextView)?.text?.toString().orEmpty()) } }
            val selected=candidates.filter { it.second !in known && it.third.isNotBlank() }.firstOrNull { (_,id,_) ->
                val value=runCatching { metadata.invoke(cloud,id) as? CloudSeriesMetadata }.getOrNull()
                value!=null && !value.multiSeason
            } ?: error("No visible unseen single-season title with verified details")
            ui { selected.first.performClick() }
            await("detail") { all().filterIsInstance<TextView>().any { it.isFocusable && it.text.toString()=="开始观看" } }
            ui { all().filterIsInstance<TextView>().first { it.isFocusable && it.text.toString()=="开始观看" }.performClick() }
            await("real playback") { all().filterIsInstance<PlayerView>().firstOrNull()?.player?.let { it.isPlaying && it.videoSize.width>0 && it.currentPosition>=15_000 }==true }
            val position=ui { all().filterIsInstance<PlayerView>().single().player!!.currentPosition }
            sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PAUSE); waitForIdleSync()
            await("paused") { all().filterIsInstance<PlayerView>().single().player?.isPlaying==false }
            sendKeyDownUpSync(KeyEvent.KEYCODE_HOME); waitForIdleSync()
            var observed: Long?=null
            val until=SystemClock.elapsedRealtime()+90_000
            while(SystemClock.elapsedRealtime()<until) {
                val row=CloudHistoryClient(account).readHistory().firstOrNull { it.series.id==selected.second }
                if(row!=null && row.episodeIndex==0 && kotlin.math.abs(row.position-position)<=5000) { observed=row.position; break }
                SystemClock.sleep(5_000)
            }
            check(observed!=null) { "The official cloud did not confirm the new playback position" }
            result=Activity.RESULT_OK
            report="PASS: unseen single-season title ${selected.third}; episode=1; emulator_ms=$position; cloud_ms=$observed\n"
        } catch(error: Exception) {
            report="FAIL: ${error.javaClass.simpleName}: ${CloudSyncDiagnostics.describe(error)}\n"
        }
        finish(result,Bundle().apply { putString("stream",report) })
    }
}
