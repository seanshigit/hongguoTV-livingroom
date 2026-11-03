// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.hongguotv.core.EpisodeTarget
import com.hongguotv.core.PhoneSearchServer
import com.hongguotv.core.LibraryTransferServer
import com.hongguotv.core.BackupData
import com.hongguotv.core.QrChallenge
import com.hongguotv.core.QrLoginResult
import java.net.Inet4Address
import java.util.concurrent.Executors

/** Native modal controls kept separate from catalog/player navigation. */
class TvTools(private val activity: Activity) {
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private var dialog: AlertDialog?=null
    private var dialogCleanup: (() -> Unit)?=null
    private var accountRefresh: (() -> Unit)?=null
    val showing: Boolean get()=dialog?.isShowing==true
    private var phone: java.io.Closeable?=null
    private fun dp(value: Int)=(value*activity.resources.displayMetrics.density).toInt()
    private fun label(value: String,size: Float=17f)=TextView(activity).apply { text=value; textSize=size; setTextColor(Color.WHITE); setPadding(dp(8),dp(4),dp(8),dp(4)) }
    private fun column()=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(4),dp(16),dp(8)) }
    private fun open(next: AlertDialog,anchor: View?,onClose: ()->Unit={}) {
        dialog?.let { previous ->
            dialog=null
            phone?.close(); phone=null
            val cleanup=dialogCleanup; dialogCleanup=null
            cleanup?.invoke(); previous.dismiss()
        }
        dialog=next; dialogCleanup=onClose
        next.setOnDismissListener {
            if(dialog===next) {
                phone?.close(); phone=null; dialog=null
                val cleanup=dialogCleanup; dialogCleanup=null; cleanup?.invoke()
                if(anchor?.isAttachedToWindow==true) anchor.requestFocus()
            }
        }
        next.show()
    }
    fun close() { dialog?.dismiss(); phone?.close(); phone=null }
    fun destroy() { close(); worker.shutdownNow() }
    fun info(title: String,message: String,anchor: View?) = open(AlertDialog.Builder(activity).setTitle(title).setMessage(message).setPositiveButton("知道了",null).create(),anchor)
    fun refreshAccount() { accountRefresh?.invoke() }

    fun account(manager: OfficialAccountManager, anchor: View?) {
        val current=manager.status()
        val content=column()
        if(current.signedIn) {
            content.addView(label("已登录：${current.name}",18f))
            val status=label("",15f); content.addView(status)
            fun refresh() {
                val state=manager.status()
                status.text=state.message + if(state.pending>0) " · 待同步 ${state.pending} 条" else ""
            }
            val actions=LinearLayout(activity)
            fun action(title: String,run: ()->Unit) {
                actions.addView(Button(activity).apply { text=title; setOnClickListener { run() } },LinearLayout.LayoutParams(0,-2,1f))
            }
            action("立即同步") { manager.requestSync() }
            action("合并本机记录") {
                confirm("合并登录前的本机记录","较新的本机观看进度会合并到当前红果账号，并在同步时上传。",anchor) {
                    val count=runCatching { manager.importGuest() }.getOrNull()
                    Toast.makeText(activity,if(count==null) "记录无法合并，请稍后重试" else "已合并 $count 条本机记录",Toast.LENGTH_LONG).show()
                }
            }
            action("退出登录") {
                confirm("退出红果账号","退出后显示本机记录；该账号的待同步进度保留在本机。",anchor) {
                    manager.logout { ok -> Toast.makeText(activity,if(ok) "已退出红果账号" else "退出未完成，请重试",Toast.LENGTH_LONG).show() }
                }
            }
            content.addView(actions)
            val next=AlertDialog.Builder(activity).setTitle("红果账号").setView(content).setNegativeButton("关闭",null).create()
            open(next,anchor) { accountRefresh=null }
            accountRefresh=::refresh; refresh()
            actions.getChildAt(0).requestFocus()
            return
        }
        content.addView(label("请打开手机红果短剧 App，使用扫一扫并在手机上确认登录。",16f))
        val image=ImageView(activity).apply { contentDescription="红果账号登录二维码"; adjustViewBounds=true }
        content.addView(image,LinearLayout.LayoutParams(dp(200),dp(200)).apply { gravity=Gravity.CENTER_HORIZONTAL })
        val status=label("正在获取二维码…",15f); content.addView(status)
        val next=AlertDialog.Builder(activity).setTitle("扫码登录红果账号").setView(content)
            .setNegativeButton("关闭",null).setPositiveButton("刷新二维码",null).create()
        open(next,anchor) { manager.cancelQr(); accountRefresh=null }
        fun start() {
            image.setImageDrawable(null); status.text="正在获取二维码…"
            manager.startQr(object: OfficialAccountManager.QrEvents {
                override fun challenge(value: QrChallenge) {
                    val bitmap=BitmapFactory.decodeByteArray(value.png,0,value.png.size)
                    if(bitmap==null) status.text="二维码图片无效，请刷新" else {
                        image.setImageBitmap(bitmap); status.text="请用红果 App 扫码，并在手机上确认"
                    }
                }
                override fun state(value: QrLoginResult) {
                    status.text=when(value) {
                        QrLoginResult.WAITING -> "请用红果 App 扫码，并在手机上确认"
                        QrLoginResult.SCANNED -> "已扫码，请在手机上确认登录"
                        QrLoginResult.EXPIRED -> "二维码已过期，请刷新"
                        QrLoginResult.CANCELLED -> "手机端已取消，请刷新"
                        QrLoginResult.SIGNED_IN -> "登录成功"
                    }
                }
                override fun failed(message: String) { status.text=message }
                override fun signedIn(name: String) {
                    if(dialog===next) { next.dismiss(); account(manager,anchor) }
                }
            })
        }
        next.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { start() }
        next.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
        start()
    }
    fun confirm(title: String,message: String,anchor: View?,action: ()->Unit) {
        val next=AlertDialog.Builder(activity).setTitle(title).setMessage(message).setNegativeButton("取消",null).setPositiveButton("确认") { _,_-> action() }.create()
        open(next,anchor); next.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
    }
    fun choose(title: String,labels: List<String>,anchor: View?,onChoice: (Int)->Unit,extra: String?=null,onExtra: ()->Unit={}) {
        val builder=AlertDialog.Builder(activity).setTitle(title).setItems(labels.toTypedArray()) { _,index-> onChoice(index) }.setNegativeButton("取消",null)
        if(extra!=null) builder.setNeutralButton(extra) { _,_-> onExtra() }
        val next=builder.create(); open(next,anchor); next.listView.requestFocus()
    }
    fun episodePicker(count: Int,current: Int,anchor: View?,select: (Int)->Unit) {
        val content=column(); var digits=(current+1).toString(); var replace=true
        val value=label(digits,28f).apply { gravity=Gravity.CENTER }; content.addView(value)
        val error=label("定位到该集，再按确认播放",14f); content.addView(error)
        fun digit(key: String) { digits=if(replace) key else (digits+key).take(6); replace=false; value.text=digits; error.text="定位到该集，再按确认播放" }
        fun erase() { digits=if(replace) "" else digits.dropLast(1); replace=false; value.text=digits.ifEmpty { "—" } }
        val buttons=mutableListOf<Button>()
        listOf(listOf("1","2","3"),listOf("4","5","6"),listOf("7","8","9"),listOf("清空","0","退格")).forEach { keys ->
            val row=LinearLayout(activity)
            keys.forEach { key ->
                val button=Button(activity).apply {
                    id=View.generateViewId(); text=key; minHeight=dp(42)
                    backgroundTintList=android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused),intArrayOf()),intArrayOf(Color.rgb(255,99,76),Color.rgb(31,36,47)))
                    setOnClickListener { when(key) { "清空"->{ digits="";replace=false;value.text="—" }; "退格"->erase(); else->digit(key) } }
                }
                buttons+=button
                row.addView(button,LinearLayout.LayoutParams(0,-2,1f))
            }
            content.addView(row)
        }
        val next=AlertDialog.Builder(activity).setTitle("跳转集数 · 1—$count").setView(ScrollView(activity).apply { addView(content) }).setNegativeButton("取消",null).setPositiveButton("定位",null).create()
        next.setOnKeyListener { _,key,event ->
            when {
                key in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { if(event.action==KeyEvent.ACTION_DOWN) digit((key-KeyEvent.KEYCODE_0).toString()); true }
                key==KeyEvent.KEYCODE_DEL -> { if(event.action==KeyEvent.ACTION_DOWN) erase(); true }
                else -> false
            }
        }
        open(next,anchor)
        val locate=next.getButton(AlertDialog.BUTTON_POSITIVE)
        val cancel=next.getButton(AlertDialog.BUTTON_NEGATIVE)
        buttons.forEachIndexed { i,button ->
            button.nextFocusLeftId=buttons[if(i%3==0) i else i-1].id
            button.nextFocusRightId=buttons[if(i%3==2) i else i+1].id
            button.nextFocusUpId=buttons[if(i<3) i else i-3].id
            button.nextFocusDownId=if(i<9) buttons[i+3].id else locate.id
        }
        locate.nextFocusUpId=buttons[10].id; cancel.nextFocusUpId=buttons[9].id
        next.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val target=EpisodeTarget.parse(digits,count)
            if(target==null) error.text="请输入 1—$count 之间的集数"
            else { next.dismiss(); select(target) }
        }
        buttons.first().requestFocus()
    }
    private fun qr(url: String)=ImageView(activity).apply {
        val bits=QRCodeWriter().encode(url,BarcodeFormat.QR_CODE,360,360)
        val pixels=IntArray(360*360) { i->if(bits[i%360,i/360]) Color.BLACK else Color.WHITE }
        setImageBitmap(Bitmap.createBitmap(pixels,360,360,Bitmap.Config.ARGB_8888))
        contentDescription="二维码"
    }
    fun phoneInput(anchor: View?,submit: (String)->Unit) {
        val connectivity=activity.getSystemService(ConnectivityManager::class.java)
        val network=connectivity.activeNetwork
        val capabilities=connectivity.getNetworkCapabilities(network)
        val address=connectivity.getLinkProperties(network)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
        if(address==null || capabilities==null || !(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
            info("手机输入","请让电视和手机连接同一局域网（Wi-Fi 或有线网络）后重试。",anchor); return
        }
        val content=column(); val status=label("正在生成二维码…"); content.addView(status)
        val next=AlertDialog.Builder(activity).setTitle("手机输入剧名").setView(content).setNegativeButton("关闭",null).create()
        open(next,anchor)
        worker.execute {
            try {
                val session=PhoneSearchServer(address,{ query -> main.post { if(dialog===next) { next.dismiss(); submit(query) } } },{ main.post { if(dialog===next) { content.removeAllViews(); content.addView(label("二维码已过期，请关闭后重新打开。")) } } })
                main.post {
                    if(dialog!==next) { session.close(); return@post }
                    phone=session; content.removeAllViews()
                    val row=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL }
                    row.addView(qr(session.url),LinearLayout.LayoutParams(dp(185),dp(185)))
                    val instructions=column()
                    instructions.addView(label("手机扫码后用浏览器打开，输入剧名发送到电视。\n需连接同一局域网；关闭窗口即失效，最长 5 分钟。",16f))
                    instructions.addView(label(session.url,12f))
                    row.addView(instructions,LinearLayout.LayoutParams(0,-2,1f)); content.addView(row)
                }
            } catch(_: Exception) { main.post { if(dialog===next) status.text="无法开启手机输入，请检查网络后重试。" } }
        }
    }
    fun libraryTransfer(backup: String,anchor: View?,restore: (BackupData,Boolean)->Unit) {
        val connectivity=activity.getSystemService(ConnectivityManager::class.java)
        val network=connectivity.activeNetwork; val capabilities=connectivity.getNetworkCapabilities(network)
        val address=connectivity.getLinkProperties(network)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
        if(address==null || capabilities==null || !(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
            info("备份与恢复","请让电视和手机连接同一局域网后重试。",anchor); return
        }
        val content=column(); val status=label("正在生成二维码…"); content.addView(status)
        val next=AlertDialog.Builder(activity).setTitle("手机备份与恢复").setView(ScrollView(activity).apply { addView(content) }).setNegativeButton("关闭",null).create()
        open(next,anchor)
        worker.execute {
            try {
                val session=LibraryTransferServer(address,backup,{ data -> main.post {
                    if(dialog!==next) return@post
                    next.dismiss()
                    val preview=column()
                    preview.addView(label("备份含 ${data.favorites.size} 部收藏、${data.history.size} 条观看记录、${data.searches.size} 条搜索、${data.later.size} 部稍后看、${data.hidden.size} 部已隐藏推荐。\n\n收藏合并，观看位置以较新的记录为准；已有收藏优先保留（最多 500 部），历史保留最近 200 条。",16f))
                    val settings=CheckBox(activity).apply { text="同时恢复播放设置和内容分类"; isChecked=false }; preview.addView(settings)
                    val confirm=AlertDialog.Builder(activity).setTitle("确认恢复本机记录")
                        .setView(ScrollView(activity).apply { addView(preview) }).setNegativeButton("取消",null)
                        .setPositiveButton("合并恢复") { _,_-> restore(data,settings.isChecked) }.create()
                    open(confirm,anchor); confirm.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
                } },{ main.post { if(dialog===next) { content.removeAllViews(); content.addView(label("二维码已过期，请关闭后重新打开。")) } } })
                main.post {
                    if(dialog!==next) { session.close(); return@post }
                    phone=session; content.removeAllViews()
                    val row=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL }
                    row.addView(qr(session.url),LinearLayout.LayoutParams(dp(185),dp(185)))
                    val instructions=column()
                    instructions.addView(label("手机扫码，用浏览器下载备份或选择备份文件恢复。\n仅限同一局域网；关闭即失效，最长 5 分钟。恢复需在电视确认。",16f))
                    instructions.addView(label(session.url,12f)); row.addView(instructions,LinearLayout.LayoutParams(0,-2,1f)); content.addView(row)
                }
            } catch(_: Exception) { main.post { if(dialog===next) status.text="无法开启备份传输，请检查网络后重试。" } }
        }
    }
}
