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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.PreloadManagerListener
import androidx.media3.exoplayer.source.preload.PreloadMediaSource
import androidx.media3.ui.PlayerView
import java.io.File

/** Exercises the real decrypting source, official sample prebuffering and handoff on a TV. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PreloadAcceptance: Instrumentation() {
    private lateinit var activity: Activity
    private val report=StringBuilder()
    private var playbackSmoke=false
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); playbackSmoke=arguments?.getString("scope")=="playback-smoke"; start() }
    private fun views(view: View): List<View> = listOf(view)+(if(view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList())
    private fun all()=views(activity.window.decorView)
    private fun <T> ui(action: ()->T): T {
        var result: T?=null; var failure: Throwable?=null
        runOnMainSync { try { result=action() } catch(e: Throwable) { failure=e } }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun await(name: String, condition: ()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+120_000
        while(SystemClock.elapsedRealtime()<deadline) {
            if(ui(condition)) return
            SystemClock.sleep(100)
        }
        error("Timed out: $name")
    }
    private fun key(code: Int) { sendKeyDownUpSync(code); waitForIdleSync() }
    private fun click(prefix: String)=ui { all().filterIsInstance<TextView>().first { it.isShown && it.isFocusable && it.text.toString().startsWith(prefix) }.performClick() }
    private fun player()=all().filterIsInstance<PlayerView>().single().player as ExoPlayer
    private fun manager()=activity.javaClass.declaredFields.firstOrNull { it.type==DefaultPreloadManager::class.java }
        ?.apply { isAccessible=true }?.get(activity) as? DefaultPreloadManager
    private fun snapshot(name: String) {
        val bitmap=uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        File(targetContext.getExternalFilesDir(null),"preload-acceptance/$name.png").apply {
            parentFile!!.mkdirs(); outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        }; bitmap.recycle()
    }
    override fun onStart() {
        var result=Activity.RESULT_CANCELED
        try {
            activity=startActivitySync(Intent(Intent.ACTION_MAIN).setClassName(targetContext,"com.hongguotv.nativeapp.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            for(type in listOf("漫剧","短剧")) {
                await("home") { all().filterIsInstance<HomeScreen>().firstOrNull()?.refresh?.text?.let { it.startsWith("刷新片库") || it.contains("按确认查看") }==true }
                click(type)
                await("topic posters") { all().any { it.tag?.toString()?.startsWith("hot:")==true } }
                ui { all().first { it.tag?.toString()?.startsWith("hot:")==true }.performClick() }
                await("detail") { all().filterIsInstance<TextView>().any { it.text.toString().startsWith("开始观看") || it.text.toString().startsWith("继续第") } }
                ui { all().filterIsInstance<TextView>().first { it.isFocusable && (it.text.toString().startsWith("开始观看") || it.text.toString().startsWith("继续第")) }.performClick() }
                await("real video") { all().filterIsInstance<PlayerView>().firstOrNull()?.player?.let { it.isPlaying && it.videoSize.width>0 && it.currentPosition>1000 }==true }
                if(playbackSmoke) {
                    report.append("PASS $type: actual video frames and playback after account integration\n")
                    result=Activity.RESULT_OK
                    break
                }
                val p=ui { player() }
                val manager=ui { manager() } ?: error("Next episode never gets Media3 sample prebuffering; only raw-byte warm-up is available")
                var completed: MediaItem?=null
                ui { manager.addListener(object: PreloadManagerListener {
                    override fun onCompleted(mediaItem: MediaItem) { completed=mediaItem }
                }) }
                repeat(2) { round ->
                    ui { p.seekTo((p.duration-25_000).coerceAtLeast(0)); p.play() }
                    await("official next-episode sample buffer") { completed!=null }
                    val next=completed!!
                    val source=ui { manager.getMediaSource(next) } as? PreloadMediaSource ?: error("No reusable Media3 source")
                    check(ui { manager.sourceCount==1 && p.isPlaying }) { "Preloading owns more than the next item or interrupts current playback" }
                    snapshot("$type-buffered-$round")
                    var endedAt=0L; var renderedAt=0L
                    val listener=object: Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) { if(state==Player.STATE_ENDED) endedAt=SystemClock.elapsedRealtime() }
                        override fun onRenderedFirstFrame() { if(p.currentMediaItem?.mediaId==next.mediaId) renderedAt=SystemClock.elapsedRealtime() }
                    }
                    ui { p.addListener(listener); p.seekTo((p.duration-1000).coerceAtLeast(0)) }
                    await("automatic next episode frame") { renderedAt>0 && p.currentMediaItem?.mediaId==next.mediaId && p.isPlaying && p.videoSize.width>0 }
                    check(endedAt>0 && renderedAt>=endedAt) { "Missing actual episode-end timestamp" }
                    // Observe Media3's actual handoff flag, so preparing a fresh source cannot pass.
                    check(ui { source.javaClass.getDeclaredField("onUsedByPlayerNotified").apply { isAccessible=true }.getBoolean(source) }) { "Next episode discarded the preloaded MediaSource" }
                    check(ui { player()===p && manager.sourceCount==0 }) { "Handoff leaks the preload holder or rebuilds the player" }
                    report.append("PASS $type round ${round+1}: real samples prebuffered, one next source, automatic handoff reused source/player; end-to-first-frame=${renderedAt-endedAt}ms\n")
                    ui { p.removeListener(listener) }; completed=null
                    key(KeyEvent.KEYCODE_MEDIA_PAUSE); check(ui { !p.playWhenReady }); key(KeyEvent.KEYCODE_MEDIA_PLAY)
                    await("resume") { p.isPlaying }
                }
                ui { p.seekTo((p.duration-25_000).coerceAtLeast(0)) }
                await("next buffer before cancellation") { completed!=null }
                ui { activity.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) }
                check(ui { manager.sourceCount==0 && p.isPlaying }) { "Low-memory cancellation retained next source or stopped current playback" }
                key(KeyEvent.KEYCODE_HOME)
                await("background release") { manager()==null }
                check(ui { !p.isPlaying && manager.sourceCount==0 })
                report.append("PASS $type: pause/resume, low-memory cancellation preserves current video, background releases preloader\n")
                // singleTop resumes the existing Activity; startActivitySync waits for a new one.
                targetContext.startActivity(Intent(Intent.ACTION_MAIN).setClassName(targetContext,"com.hongguotv.nativeapp.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                await("paused foreground restoration") { all().filterIsInstance<PlayerView>().firstOrNull()?.player?.let { it.playbackState==Player.STATE_READY && !it.playWhenReady }==true }
                key(KeyEvent.KEYCODE_BACK)
                await("detail restored") { all().filterIsInstance<TextView>().any { it.text.toString().startsWith("继续第") } }
                key(KeyEvent.KEYCODE_BACK)
            }
            result=Activity.RESULT_OK
        } catch(e: Throwable) { report.append("FAIL: ${e.stackTraceToString()}\n"); runCatching { snapshot("failure") } }
        File(targetContext.getExternalFilesDir(null),"preload-acceptance/report.txt").apply { parentFile!!.mkdirs(); writeText(report.toString()) }
        finish(result,Bundle().apply { putString("stream",report.toString()) })
    }
}
