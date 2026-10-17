package com.hongguotv.nativeapp

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import java.io.File

/** Runs against the installed APK and live content; no test data or hooks in production. */
class TvAcceptance: Instrumentation() {
    private lateinit var activity: Activity
    private val report=StringBuilder()
    private var largeOnly=false
    private var searchOnly=false
    private var record=false
    private var recording: android.os.ParcelFileDescriptor?=null
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); largeOnly=arguments?.getString("scope")=="large"; searchOnly=arguments?.getString("scope")=="search-regression"; record=arguments?.getString("record")=="true"; start() }
    private fun views(view: View): List<View> = listOf(view)+(if(view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList())
    private fun all()=views(activity.window.decorView)
    private fun label(value: String)=all().filterIsInstance<TextView>().first { it.text.toString()==value && it.isFocusable && it.isShown }
    private fun <T> ui(action: ()->T): T {
        var result: T?=null; var error: Throwable?=null
        runOnMainSync { try { result=action() } catch(e: Throwable) { error=e } }
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun await(name: String,check: ()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+120_000
        while(SystemClock.elapsedRealtime()<deadline) {
            if(ui(check)) return
            SystemClock.sleep(100)
        }
        error("Timed out: $name")
    }
    private fun key(code: Int) { sendKeyDownUpSync(code); waitForIdleSync() }
    private fun snapshot(name: String) {
        waitForIdleSync(); uiAutomation.waitForIdle(200,5000)
        if(name.startsWith("home")) report.append(ui {
            val card=all().firstOrNull { it.tag?.toString()?.startsWith("hot:")==true || it.tag?.toString()?.startsWith("fantasy:")==true } as? ViewGroup
            "LAYOUT $name home=${home().width}x${home().height} scroll=${all().filterIsInstance<android.widget.ScrollView>().map { "${it.width}x${it.height}" }} card=${card?.width}x${card?.height} children=${card?.let { c -> (0 until c.childCount).map { "${c.getChildAt(it).measuredWidth}x${c.getChildAt(it).measuredHeight}" } }}\n"
        })
        var captured: Bitmap?=null
        repeat(5) { if(captured==null) { captured=uiAutomation.takeScreenshot(); if(captured==null) SystemClock.sleep(300) } }
        val bitmap=captured ?: error("Screenshot unavailable after five attempts")
        val file=File(targetContext.getExternalFilesDir(null),"acceptance/$name.png"); file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
    }
    private fun home(): HomeScreen=all().filterIsInstance<HomeScreen>().single()
    private fun homeReady(): Boolean {
        val home=all().filterIsInstance<HomeScreen>().firstOrNull() ?: return false
        if(home.refresh.text.contains("按确认查看")) home.refresh.performClick()
        return home.refresh.text.startsWith("刷新片库")
    }
    private fun menuAction(value: String): Boolean {
        val node=uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(value)?.firstOrNull { it.text?.toString()==value } ?: return false
        return node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
    }
    private fun cardIds()=all().filter { it.tag?.toString()?.matches(Regex("[0-9]{1,30}"))==true }.map { it.tag.toString() }.toSet()
    override fun onStart() {
        try {
            activity=startActivitySync(Intent(Intent.ACTION_MAIN).setClassName(targetContext,"com.hongguotv.nativeapp.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if(searchOnly) {
                await("initial home") { homeReady() }
                ui { label("搜索").performClick(); label("漫剧").performClick() }
                for(query in listOf("聚宝仙盆第十四季","聚宝仙盆")) {
                    ui {
                        all().filterIsInstance<EditText>().single().setText(query)
                        all().filterIsInstance<TextView>().last { it.text.toString()=="搜索" && it.isFocusable }.performClick()
                    }
                    await("season fourteen results: $query") { "7696437705822981182" in cardIds() }
                    check(ui { cardIds().size<=24 })
                    report.append("PASS: $query finds season fourteen; "+ui {
                        all().filterIsInstance<TextView>().first { it.text.toString().startsWith("找到 ") }.text
                    }+"\n")
                }
                ui { all().first { it.tag=="7696437705822981182" }.requestFocus() }
                await("season fourteen cover") { views(all().first { it.tag=="7696437705822981182" }).filterIsInstance<ImageView>().any { it.drawable!=null } }
                snapshot("search-season-fourteen")
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await("season fourteen detail") { all().filterIsInstance<TextView>().any { it.text.toString().startsWith("222 集") } }
                snapshot("detail-season-fourteen")
                key(KeyEvent.KEYCODE_BACK)
                await("season fourteen search restored") { "7696437705822981182" in cardIds() }
                check(ui { activity.currentFocus?.tag=="7696437705822981182" })
                report.append("PASS: D-pad opens correct 222-episode detail and restores search selection\n")
                finish(Activity.RESULT_OK,Bundle().apply { putString("report",report.toString()) })
                return
            }
            var blocked=false
            if(!largeOnly) {
                await("initial home") { homeReady() }
                ui { label("漫剧").performClick() }; await("comic home") { homeReady() }
                if(record) recording=uiAutomation.executeShellCommand("screenrecord --time-limit 30 /sdcard/Android/data/com.hongguotv.nativeapp/files/acceptance/channels-current.mp4")
                for(round in 1..3) {
                    await("home content") { homeReady() }
                    val channel=ui { all().firstOrNull { it.tag=="channel:hot" } }
                    check(channel!=null) { "Home has no explicit topic channel entry" }
                    ui { channel!!.performClick(); all().first { it.tag?.toString()?.startsWith("hot:")==true }.requestFocus() }
                    key(KeyEvent.KEYCODE_DPAD_LEFT); key(KeyEvent.KEYCODE_DPAD_RIGHT)
                    val first=ui { home().capturePosition() }
                    check(first.shelf=="hot" && !first.focus.startsWith("channel:")) { "Right did not enter active channel" }
                    key(KeyEvent.KEYCODE_DPAD_UP)
                    check(ui { (activity.currentFocus as? TextView)?.text?.toString()=="首页" }) { "First home row cannot return to top navigation" }
                    key(KeyEvent.KEYCODE_DPAD_DOWN)
                    check(ui { home().capturePosition().focus==first.focus })
                    key(KeyEvent.KEYCODE_DPAD_RIGHT)
                    val second=ui { home().capturePosition() }
                    check(first.focus!=second.focus && first.shelf==second.shelf)
                    key(KeyEvent.KEYCODE_DPAD_DOWN)
                    check(ui { home().capturePosition().shelf==second.shelf && home().capturePosition().row>second.row })
                    if(round==1) snapshot("home-next-row")
                    key(KeyEvent.KEYCODE_DPAD_UP)
                    check(ui { home().capturePosition().focus==second.focus })
                    key(KeyEvent.KEYCODE_DPAD_LEFT)
                    val exited=ui { home().capturePosition() }
                    key(KeyEvent.KEYCODE_DPAD_LEFT)
                    check(ui { activity.currentFocus?.tag=="channel:hot" }) { "First column left did not return to channel" }
                    val topic=ui { all().first { it.tag?.toString()?.startsWith("channel:")==true && it.tag!="channel:hot" && it.tag!="channel:resume" && it.tag!="channel:updates" && it.tag!="channel:later" } }
                    ui { topic.requestFocus() }
                    check(ui { home().capturePosition().shelf=="hot" }) { "Hover switched channel before confirmation" }
                    key(KeyEvent.KEYCODE_DPAD_CENTER); key(KeyEvent.KEYCODE_DPAD_RIGHT)
                    val topicPosition=ui { home().capturePosition() }
                    check(topicPosition.shelf!=second.shelf && !topicPosition.focus.startsWith("channel:"))
                    ui { label("首页").requestFocus() }; key(KeyEvent.KEYCODE_DPAD_DOWN)
                    check(ui { home().capturePosition().shelf==topicPosition.shelf && activity.currentFocus?.tag?.toString()?.startsWith("${topicPosition.shelf}:")==true }) { "Top navigation points to a removed channel card" }
                    if(round==1) snapshot("home-topic")
                    ui { channel!!.performClick(); channel.requestFocus() }; key(KeyEvent.KEYCODE_DPAD_RIGHT)
                    val restored=ui { home().capturePosition() }
                    check(restored.focus==exited.focus) { "Channel lost selection: exited=$exited restored=$restored" }
                    key(KeyEvent.KEYCODE_DPAD_RIGHT)
                    check(ui { home().capturePosition().focus==second.focus })
                    await("visible posters") { all().filterIsInstance<ImageView>().count { it.isShown && it.drawable!=null }>=6 }
                    if(round==1) snapshot("home-1080p")
                    key(KeyEvent.KEYCODE_DPAD_CENTER)
                    await("detail") { all().filterIsInstance<TextView>().any { it.text.toString().startsWith("开始观看") || it.text.toString().startsWith("继续第") } }
                    if(round==1) snapshot("detail-1080p")
                    key(KeyEvent.KEYCODE_BACK)
                    await("home restored") { all().any { it is HomeScreen } }
                    check(ui { home().capturePosition().focus==second.focus }) { "Detail return lost focus" }
                    ui { label("搜索").performClick() }
                    ui {
                        all().filterIsInstance<EditText>().single().setText("修仙")
                        all().filterIsInstance<TextView>().last { it.text.toString()=="搜索" && it.isFocusable }.performClick()
                    }
                    await("search results") { cardIds().isNotEmpty() && all().filterIsInstance<TextView>().any { it.text.toString().startsWith("找到 ") } }
                    check(ui {
                        val marker=all().filterIsInstance<TextView>().first { it.text.toString()=="第 1 页" }
                        val bounds=android.graphics.Rect()
                        marker.getGlobalVisibleRect(bounds) && bounds.height()==marker.height
                    }) { "Search pagination is clipped at initial selection" }
                    val pageOne=ui { cardIds() }
                    check(pageOne.size<=24)
                    ui { all().first { it.tag?.toString() in pageOne }.requestFocus() }
                    await("search posters") { all().filterIsInstance<ImageView>().count { it.isShown && it.drawable!=null }>=6 }
                    await("whole search card visible") {
                        val card=activity.currentFocus ?: return@await false
                        val rect=android.graphics.Rect()
                        card.getGlobalVisibleRect(rect) && rect.height()==card.height
                    }
                    if(round==1) snapshot("search-1080p")
                    val canPage=ui { all().filterIsInstance<TextView>().any { it.text.toString()=="下一页" && it.isEnabled } }
                    if(canPage) {
                        ui { all().last { it.tag?.toString() in pageOne }.requestFocus() }
                        key(KeyEvent.KEYCODE_DPAD_DOWN)
                        check(ui { (activity.currentFocus as? TextView)?.text?.toString()=="下一页" }) { "D-pad cannot enter search pagination" }
                        if(round==1) snapshot("search-pagination-focus")
                        key(KeyEvent.KEYCODE_DPAD_UP)
                        check(ui { activity.currentFocus?.tag?.toString() in pageOne }) { "Pagination up cannot return to results" }
                        key(KeyEvent.KEYCODE_DPAD_DOWN)
                        check(ui { (activity.currentFocus as? TextView)?.text?.toString()=="下一页" })
                        key(KeyEvent.KEYCODE_DPAD_CENTER)
                        await("search page two") { all().filterIsInstance<TextView>().any { it.text.toString()=="第 2 页" } && cardIds().isNotEmpty() }
                        check(ui { cardIds().intersect(pageOne).isEmpty() }) { "Search repeated IDs across pages" }
                        ui { label("上一页").performClick() }
                        await("search page one") { all().filterIsInstance<TextView>().any { it.text.toString()=="第 1 页" } && cardIds()==pageOne }
                    }
                    report.append("Round $round PASS: explicit channels, confirm-only switch, per-channel selection, D-pad grid, detail return, live search, pagination without duplicates\n")
                    ui { label("首页").performClick() }
                    await("search return to channel") { homeReady() }
                    check(ui { home().capturePosition().focus==second.focus }) { "Search return lost home selection" }
                }
                await("home final") { homeReady() }
                val managed=ui { all().first { it.tag?.toString()?.startsWith("hot:")==true }.also { it.requestFocus() }.tag.toString().substringAfter(':') }
                key(KeyEvent.KEYCODE_MENU)
                val wasQueued=menuAction("移出稍后看")
                if(wasQueued) { waitForIdleSync(); key(KeyEvent.KEYCODE_MENU) }
                check(menuAction("加入稍后看")) { "Quick action could not add to queue" }
                await("queue channel") { all().any { it.tag=="channel:later" } }
                ui { all().first { it.tag=="channel:later" }.performClick(); all().first { it.tag=="channel:later" }.requestFocus() }
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                ui { all().first { it.tag=="later:$managed" }.requestFocus() }
                snapshot("home-later")
                key(KeyEvent.KEYCODE_MENU); check(menuAction("移出稍后看"))
                await("queue removal") { all().none { it.tag=="later:$managed" } }
                check(ui { activity.currentFocus?.isShown==true && activity.currentFocus?.isFocusable==true }) { "Removing active queue item left focus detached" }
                ui { all().first { it.tag=="channel:hot" }.performClick(); all().first { it.tag=="hot:$managed" }.requestFocus() }
                if(wasQueued) { key(KeyEvent.KEYCODE_MENU); check(menuAction("加入稍后看")) }
                report.append("PASS: add/remove real queue item, personal channel and visible focus after removal\n")
                ui { all().first { it.tag?.toString()?.startsWith("hot:")==true }.requestFocus() }; key(KeyEvent.KEYCODE_DPAD_CENTER)
                await("play action") { all().filterIsInstance<TextView>().any { it.text.toString()=="开始观看" || it.text.toString().startsWith("继续第") } }
                ui { all().filterIsInstance<TextView>().first { it.text.toString()=="开始观看" || it.text.toString().startsWith("继续第") }.performClick() }
                await("playback outcome") {
                    all().filterIsInstance<androidx.media3.ui.PlayerView>().firstOrNull()?.player?.let { it.isPlaying && it.videoSize.width>0 && it.currentPosition>1000 }==true ||
                        all().filterIsInstance<TextView>().any { it.text.toString().contains("片源暂不可播放") }
                }
                blocked=ui { all().filterIsInstance<TextView>().any { it.text.toString().contains("片源暂不可播放") } }
                if(blocked) {
                    snapshot("playback-unavailable")
                    report.append("BLOCKED: live provider rejected playback; no video frames. Error code and recovery entry verified.\n")
                    ui { label("返回详情").performClick() }
                } else {
                    snapshot("playback")
                    key(KeyEvent.KEYCODE_DPAD_CENTER)
                    await("paused") { all().filterIsInstance<androidx.media3.ui.PlayerView>().single().player?.isPlaying==false }
                    key(KeyEvent.KEYCODE_DPAD_CENTER)
                    await("resumed") { all().filterIsInstance<androidx.media3.ui.PlayerView>().single().player?.isPlaying==true }
                    key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_BACK)
                    await("episode panel cancelled") { all().filterIsInstance<androidx.media3.ui.PlayerView>().single().player?.isPlaying==true }
                    val previousId=ui { all().filterIsInstance<androidx.media3.ui.PlayerView>().single().player!!.currentMediaItem!!.mediaId }
                    key(KeyEvent.KEYCODE_DPAD_UP)
                    ui { label("下一集").performClick() }
                    await("next episode video") { all().filterIsInstance<androidx.media3.ui.PlayerView>().single().player?.let { it.currentMediaItem?.mediaId!=previousId && it.isPlaying && it.videoSize.width>0 && it.currentPosition>1000 }==true }
                    snapshot("playback-next-episode")
                    key(KeyEvent.KEYCODE_DPAD_UP); key(KeyEvent.KEYCODE_BACK); key(KeyEvent.KEYCODE_BACK)
                    report.append("PASS: real comic video frames, pause/resume, episode panel cancel, next episode playback\n")
                }
                await("detail return from playback") { all().filterIsInstance<TextView>().any { it.text.toString()=="开始观看" || it.text.toString().startsWith("继续第") } }
                key(KeyEvent.KEYCODE_BACK); await("home after playback") { homeReady() }
                report.append("PASS: return from playback to detail and home\n")
                if(!blocked) {
                    ui { label("短剧").performClick() }; await("short home") { homeReady() }
                    ui { all().first { it.tag?.toString()?.startsWith("hot:")==true }.requestFocus() }; key(KeyEvent.KEYCODE_DPAD_CENTER)
                    await("short detail") { all().filterIsInstance<TextView>().any { it.text.toString()=="开始观看" || it.text.toString().startsWith("继续第") } }
                    ui { all().filterIsInstance<TextView>().first { it.text.toString()=="开始观看" || it.text.toString().startsWith("继续第") }.performClick() }
                    await("short drama real video") { all().filterIsInstance<androidx.media3.ui.PlayerView>().single().player?.let { it.isPlaying && it.videoSize.width>0 && it.currentPosition>1000 }==true }
                    snapshot("playback-short")
                    key(KeyEvent.KEYCODE_DPAD_UP); key(KeyEvent.KEYCODE_BACK); key(KeyEvent.KEYCODE_BACK)
                    await("short detail return") { all().filterIsInstance<TextView>().any { it.text.toString()=="开始观看" || it.text.toString().startsWith("继续第") } }
                    key(KeyEvent.KEYCODE_BACK); await("short home return") { homeReady() }
                    report.append("PASS: real short drama video and detail/home return\n")
                }
            }
            await("home before large text") { homeReady() }
            ui {
                val configuration=android.content.res.Configuration(activity.resources.configuration).apply { fontScale=1.4f }
                @Suppress("DEPRECATION") activity.resources.updateConfiguration(configuration,activity.resources.displayMetrics)
                label("首页").performClick()
            }
            await("large text home") { homeReady() }
            ui { label("首页").requestFocus() }; key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("large card visible") {
                val card=activity.currentFocus ?: return@await false
                val rect=android.graphics.Rect()
                card.tag?.toString()?.startsWith("hot:")==true && card.getGlobalVisibleRect(rect) && rect.height()==card.height
            }
            check(ui { all().filterIsInstance<TextView>().filter { it.tag?.toString()?.startsWith("channel:")==true }.all { val rect=android.graphics.Rect(); !it.getGlobalVisibleRect(rect) || rect.height()==it.height } }) { "Large topic channel clipped by fixed footer" }
            snapshot("home-large-text")
            ui { all().last { it.tag?.toString()?.startsWith("channel:")==true }.requestFocus() }
            await("last channel visible") { val view=activity.currentFocus ?: return@await false; val rect=android.graphics.Rect(); view.getGlobalVisibleRect(rect) && rect.height()==view.height }
            snapshot("home-large-channel-tail")
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            report.append("PASS: home remains operable with 1.4x text and no clipped channel labels\n")
            ui { label("搜索").performClick(); all().filterIsInstance<EditText>().single().setText("修仙"); all().filterIsInstance<TextView>().last { it.text.toString()=="搜索" && it.isFocusable }.performClick() }
            await("large search") { cardIds().isNotEmpty() && all().filterIsInstance<TextView>().any { it.text.toString().startsWith("找到 ") } }
            check(ui { val marker=all().filterIsInstance<TextView>().first { it.text.toString()=="第 1 页" }; val rect=android.graphics.Rect(); marker.getGlobalVisibleRect(rect) && rect.height()==marker.height }) { "Large search pagination clipped" }
            ui { all().first { it.tag?.toString() in cardIds() }.requestFocus() }
            await("large search posters") {
                val visible=all().filterIsInstance<ImageView>().filter { image -> val rect=android.graphics.Rect(); image.parent?.parent is View && (image.parent?.parent as View).tag?.toString() in cardIds() && image.getGlobalVisibleRect(rect) }
                visible.isNotEmpty() && visible.all { it.drawable!=null }
            }
            check(ui { (activity.currentFocus as? ViewGroup)?.getChildAt(0)?.height?.let { it>=activity.resources.displayMetrics.density*80 }==true }) { "Large search poster too small for TV selection" }
            snapshot("search-large-text")
            val before=ui { activity.currentFocus?.tag?.toString() }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("last large search row") { activity.currentFocus?.tag?.toString()?.let { it!=before && it in cardIds() }==true }
            snapshot("search-large-last-row")
            report.append("PASS: search pagination visible with 1.4x text\n")
            File(targetContext.getExternalFilesDir(null),"acceptance/report.txt").writeText(report.toString())
            finish(if(blocked) Activity.RESULT_CANCELED else Activity.RESULT_OK,Bundle().apply { putString("stream",report.toString()) })
        } catch(e: Throwable) {
            runCatching { snapshot("failure") }
            report.append("FAIL: ${e.stackTraceToString()}\n")
            File(targetContext.getExternalFilesDir(null),"acceptance/report.txt").apply { parentFile!!.mkdirs(); writeText(report.toString()) }
            finish(Activity.RESULT_CANCELED,Bundle().apply { putString("stream",report.toString()) })
        } finally { recording?.close() }
    }
}
