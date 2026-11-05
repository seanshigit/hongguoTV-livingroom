package com.hongguotv.nativeapp

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.hongguotv.core.AccountSession
import com.hongguotv.core.BackupProgress
import com.hongguotv.core.CloudHistoryState
import com.hongguotv.core.LibraryDisk
import com.hongguotv.core.Series
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Run with the disposable emulator offline: all accounts and records are fixtures. */
class AccountStateAcceptance: Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private fun await(latch: CountDownLatch) { check(latch.await(20,TimeUnit.SECONDS)) { "Operation timed out" } }
    private fun ui(action: ()->Unit) { runOnMainSync(action) }
    private fun load(): Library {
        val library=Library(targetContext)
        val done=CountDownLatch(1)
        var failure: Throwable?=null
        ui { library.loadAsync(onFailure={ failure=it; done.countDown() }) { done.countDown() } }
        await(done); failure?.let { throw it }
        return library
    }
    private fun manager(library: Library): OfficialAccountManager {
        val manager=OfficialAccountManager(targetContext,library,{}, {})
        val done=CountDownLatch(1)
        ui { manager.restore { done.countDown() } }
        await(done)
        return manager
    }
    override fun onStart() {
        val accountPrefs=targetContext.getSharedPreferences("official-account-v1",Activity.MODE_PRIVATE)
        val historyPrefs=targetContext.getSharedPreferences("official-history-v1",Activity.MODE_PRIVATE)
        val localPrefs=targetContext.getSharedPreferences("native-library-v1",Activity.MODE_PRIVATE)
        val oldLocal=localPrefs.all.toMap()
        val uid="123456789"
        val key=MessageDigest.getInstance("SHA-256").digest(uid.toByteArray()).joinToString("") { "%02x".format(it) }
        var result=Activity.RESULT_CANCELED
        val report=StringBuilder()
        var first: OfficialAccountManager?=null
        var second: OfficialAccountManager?=null
        var library: Library?=null
        var fixtureStarted=false
        try {
            check(!accountPrefs.contains("session") && !historyPrefs.contains(key)) { "Use a disposable emulator" }
            fixtureStarted=true
            val guest=BackupProgress(Series("41","游客剧"),"42",0,500,60000,false,1_700_000_000_000)
            check(localPrefs.edit().putString("progress",LibraryDisk.history(listOf(guest))).commit())
            val fixture=AccountSession.decode(JSONObject().put("uid",uid).put("name","测试")
                .put("cookie","sessionid=fixture-secret").put("device",JSONObject()
                    .put("device_id","333").put("iid","444").put("cdid","11111111-2222-3333-4444-555555555555")).toString())
            AccountVault(targetContext).save(fixture)
            val state=CloudHistoryState(uid)
            state.checkpoint(BackupProgress(Series("51","账号剧"),"52",0,800,60000,false,1_700_000_000_001))
            check(historyPrefs.edit().putString(key,state.encode()).commit())
            library=load()
            check(library.history().single().series.id=="41")
            first=manager(library)
            check(library.history().single().series.id=="51")
            val start=CountDownLatch(1)
            val workers=(1..8).map { worker -> Thread {
                start.await()
                for(i in 1..20) {
                    val id=(1000+worker*100+i).toString()
                    first.checkpoint(BackupProgress(Series(id,"剧$id"),(id+"9"),0,1000,60000,false,1_700_000_000_100+i))
                }
            }.apply { start() } }
            start.countDown(); workers.forEach { it.join() }
            ui { first.close(); library!!.close() }
            library=load()
            second=manager(library)
            check(library.history().size==161) { "Pending checkpoints were lost during concurrent writes or restart" }
            report.append("PASS: concurrent account checkpoints survive activity restart\n")
            val loggedOut=CountDownLatch(1)
            var ok=false
            ui { second.logout { value -> ok=value; loggedOut.countDown() } }
            await(loggedOut)
            check(ok && library.history().single().series.id=="41")
            check(AccountVault(targetContext).load()==null)
            report.append("PASS: logout restores isolated guest history and clears the account session\n")
            result=Activity.RESULT_OK
        } catch(e: Throwable) {
            report.append("FAIL: ${e.javaClass.simpleName}: ${e.message?.take(160)}\n")
        } finally {
            ui { first?.close(); second?.close(); library?.close() }
            if(fixtureStarted) {
                runCatching { AccountVault(targetContext).clear() }
                historyPrefs.edit().remove(key).commit()
                val edit=localPrefs.edit().clear()
                oldLocal.forEach { (name,value) -> when(value) {
                    is String -> edit.putString(name,value)
                    is Boolean -> edit.putBoolean(name,value)
                    is Int -> edit.putInt(name,value)
                    is Long -> edit.putLong(name,value)
                    is Float -> edit.putFloat(name,value)
                    is Set<*> -> edit.putStringSet(name,value.filterIsInstance<String>().toSet())
                } }
                edit.commit()
            }
        }
        finish(result,Bundle().apply { putString("stream",report.toString()) })
    }
}
