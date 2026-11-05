package com.hongguotv.nativeapp

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.hongguotv.core.AccountSession
import org.json.JSONObject

/** Runs only against a disposable emulator; never records or prints a real login QR or credential. */
class AccountAcceptance: Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private fun find(text: String): AccessibilityNodeInfo? =
        uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.firstOrNull { it.text?.toString()?.contains(text)==true }
    private fun waitFor(name: String, condition: ()->Boolean) {
        val end=SystemClock.elapsedRealtime()+30_000
        while(SystemClock.elapsedRealtime()<end) {
            if(condition()) return
            SystemClock.sleep(200)
        }
        error("Timed out: $name")
    }
    private fun click(text: String) = find(text)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true

    override fun onStart() {
        var result=Activity.RESULT_CANCELED
        val report=StringBuilder()
        try {
            val prefs=targetContext.getSharedPreferences("official-account-v1",Activity.MODE_PRIVATE)
            check(!prefs.contains("session")) { "Use a disposable emulator without an existing account" }
            val fixture=AccountSession.decode(JSONObject().put("uid","123456").put("name","测试用户")
                .put("cookie","sessionid=fixture-secret").put("device",JSONObject()
                    .put("device_id","333").put("iid","444").put("cdid","11111111-2222-3333-4444-555555555555")).toString())
            val vault=AccountVault(targetContext)
            try {
                vault.save(fixture)
                val stored=prefs.getString("session","").orEmpty()
                check(stored.isNotBlank() && !stored.contains("fixture-secret") && !stored.contains("123456"))
                val restored=checkNotNull(vault.load())
                check(restored.userId=="123456" && restored.encode()==fixture.encode())
                check(prefs.edit().putString("session","invalid").commit())
                check(runCatching { vault.load() }.isFailure)
                report.append("PASS: account vault encrypts, restores, and rejects damaged data\n")
            } finally { vault.clear() }
            check(vault.load()==null)
            startActivitySync(Intent(Intent.ACTION_MAIN).setClassName(targetContext,"com.hongguotv.nativeapp.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            waitFor("settings navigation") { click("设置") }
            waitFor("account entry") { click("红果账号") }
            waitFor("live QR challenge") { find("请用红果 App 扫码")!=null }
            report.append("PASS: live account QR reaches the scan-ready state\n")
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("close QR dialog") { find("红果账号  ·  扫码登录")!=null }
            report.append("PASS: back closes the QR dialog and returns to settings\n")
            result=Activity.RESULT_OK
        } catch(e: Throwable) {
            report.append("FAIL: ${e.javaClass.simpleName}: ${e.message?.take(160)}\n")
        }
        finish(result,Bundle().apply { putString("stream",report.toString()) })
    }
}
