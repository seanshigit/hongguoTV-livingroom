// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.hongguotv.core.AccountSession
import com.hongguotv.core.BackupProgress
import com.hongguotv.core.CloudHistoryClient
import com.hongguotv.core.CloudHistoryState
import com.hongguotv.core.CloudMetadataUnavailableException
import com.hongguotv.core.CloudClockException
import com.hongguotv.core.CloudIdentityException
import com.hongguotv.core.CloudSyncDiagnostics
import com.hongguotv.core.CloudPending
import com.hongguotv.core.QrChallenge
import com.hongguotv.core.QrLoginClient
import com.hongguotv.core.QrLoginResult
import com.hongguotv.core.RequestScope
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Account-scoped history and an in-process, durable pull-before-upload queue. */
class OfficialAccountManager(
    context: Context,
    private val library: Library,
    private val changed: () -> Unit,
    private val historyChanged: () -> Unit,
) : AccountHistoryEvents {
    interface QrEvents {
        fun challenge(value: QrChallenge)
        fun state(value: QrLoginResult)
        fun failed(message: String)
        fun signedIn(name: String)
    }
    data class Status(val signedIn: Boolean, val name: String, val syncing: Boolean, val pending: Int, val message: String)
    private data class Persisted(val key: String, val json: String, val pending: List<CloudPending>)

    private companion object {
        // A replacement Activity waits behind the previous Activity's last queued commit.
        val disk = Executors.newSingleThreadExecutor { task -> Thread(task, "hongguotv-account-store") }
    }
    private val app = context.applicationContext
    private val vault = AccountVault(app)
    private val prefs = app.getSharedPreferences("official-history-v1", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "hongguotv-account-sync") }
    private val qrWorker = Executors.newSingleThreadExecutor { task -> Thread(task, "hongguotv-account-qr") }
    private val lock = Any()
    private var session: AccountSession? = null
    private var history: CloudHistoryState? = null
    private var generation = 0L
    private var historyRevision = 0L
    private var qrGeneration = 0L
    private var syncScope: RequestScope? = null
    private var qrScope: RequestScope? = null
    private var qrFuture: Future<*>? = null
    private var syncing = false
    private var syncAgain = false
    private var closing = false
    private var closed = false
    private var message = "未登录"
    private var delayedPending = false
    private val delayedSync = Runnable { delayedPending = false; requestSync() }
    private val periodic = object : Runnable {
        override fun run() {
            if (synchronized(lock) { closed }) return
            requestSync()
            main.postDelayed(this, 90_000)
        }
    }

    private fun key(uid: String) = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private fun notifyChanged() { main.post { if (!synchronized(lock) { closed }) changed() } }
    fun status(): Status = synchronized(lock) {
        Status(session != null, session?.displayName.orEmpty(), syncing, history?.pending()?.size ?: 0, message)
    }

    fun restore(done: () -> Unit) {
        disk.execute {
            val loaded = runCatching {
                val account = vault.load()
                val state = account?.let { value -> prefs.getString(key(value.userId), null)?.let { CloudHistoryState.decode(value.userId, it) } ?: CloudHistoryState(value.userId) }
                account to state
            }
            main.post {
                if (synchronized(lock) { closed }) return@post
                loaded.onSuccess { (account, state) ->
                    synchronized(lock) {
                        session = account; history = state; generation++; historyRevision++
                        message = if (account == null) "未登录" else "正在核验账号并同步"
                    }
                    if (state != null) { library.useAccountHistory(state.history(), state.watched(), this); historyChanged() }
                }.onFailure { synchronized(lock) { message = "账号资料读取失败，请检查设备安全存储" } }
                changed(); done()
                if (loaded.getOrNull()?.first != null) requestSync()
            }
        }
        main.removeCallbacks(periodic); main.postDelayed(periodic, 90_000)
    }

    private fun capture(expected: Long): Persisted? = synchronized(lock) {
        if (closed || generation != expected) return@synchronized null
        val account = session ?: return@synchronized null
        val state = history ?: return@synchronized null
        Persisted(key(account.userId), state.encode(), state.pending())
    }
    private fun commit(value: Persisted): Boolean = prefs.edit().putString(value.key, value.json).commit()
    private fun persistAsync(sync: Boolean) {
        synchronized(lock) {
            val expected = generation
            val snapshot = capture(expected) ?: return
            disk.execute {
                val ok = runCatching { commit(snapshot) }.getOrDefault(false)
                if (!ok) synchronized(lock) { if (!closed && generation == expected) message = "本机记录保存失败，稍后重试" }
                notifyChanged()
                if (ok && sync) main.post {
                    if (synchronized(lock) { !closed && generation == expected }) {
                        if (!delayedPending) { delayedPending = true; main.postDelayed(delayedSync, 30_000) }
                    }
                }
            }
        }
    }
    private fun persistBlocking(expected: Long): Persisted {
        val (value, write) = synchronized(lock) {
            val snapshot = capture(expected) ?: throw java.io.IOException("账号已切换")
            snapshot to disk.submit<Boolean> { commit(snapshot) }
        }
        val success = write.get(30, TimeUnit.SECONDS)
        if (!success) throw java.io.IOException("本机记录保存失败")
        return value
    }
    private fun publishHistory(expected: Long) {
        val snapshot = synchronized(lock) {
            if (closed || generation != expected) return
            val state = history ?: return
            Triple(historyRevision, state.history(), state.watched())
        }
        main.post {
            val valid = synchronized(lock) { !closed && generation == expected && historyRevision == snapshot.first }
            if (valid) { library.useAccountHistory(snapshot.second, snapshot.third, this); historyChanged(); changed() }
        }
    }

    override fun checkpoint(progress: WatchProgress) {
        val updated = synchronized(lock) {
            val state = history ?: return
            if (!state.checkpoint(progress)) false else { historyRevision++; true }
        }
        if (updated) persistAsync(true)
    }
    override fun remove(id: String) {
        synchronized(lock) { history?.remove(id) ?: return; historyRevision++ }
        persistAsync(false)
    }
    override fun clear() {
        synchronized(lock) { history?.clear() ?: return; historyRevision++ }
        persistAsync(false)
    }
    override fun setWatched(id: String, value: Boolean) {
        synchronized(lock) { history?.setWatched(id, value) ?: return; historyRevision++ }
        persistAsync(false)
    }
    fun importGuest(): Int {
        val guest = library.snapshot().history
        val count = synchronized(lock) { history?.importLocal(guest)?.also { if (it > 0) historyRevision++ } ?: 0 }
        if (count > 0) { publishHistory(synchronized(lock) { generation }); persistAsync(true) }
        return count
    }

    fun requestSync() {
        val request = synchronized(lock) {
            if (closed || closing || session == null || history == null) return
            if (syncing) { syncAgain = true; return }
            syncing = true; syncAgain = false; message = "正在同步观看记录"
            val scope = RequestScope(); syncScope = scope
            Triple(generation, session!!, scope)
        }
        notifyChanged()
        worker.execute {
            val (expected, account, scope) = request
            var ok = false
            var stage = "登录状态核验"
            try {
                scope.run {
                    QrLoginClient().verify(account)
                    val api = CloudHistoryClient(account)
                    stage = "云端记录读取"
                    val remote = api.readHistory()
                    synchronized(lock) {
                        if (closed || generation != expected) throw java.io.IOException("账号已切换")
                        history!!.mergeRemote(remote); historyRevision++
                    }
                    stage = "本机记录保存"
                    var committed = persistBlocking(expected)
                    publishHistory(expected)
                    val deferred = mutableSetOf<Long>()
                    while (true) {
                        RequestScope.ensureActive()
                        val pending = committed.pending.firstOrNull { it.sequence !in deferred } ?: break
                        val current = synchronized(lock) { if (closed || generation != expected) null else history?.pending()?.firstOrNull { it.sequence == pending.sequence } }
                        if (current != null) {
                            try {
                                stage = "观看进度上传"
                                api.upload(current.progress)
                                synchronized(lock) {
                                    if (closed || generation != expected) throw java.io.IOException("账号已切换")
                                    history!!.acknowledge(current.sequence); historyRevision++
                                }
                            } catch (_: CloudMetadataUnavailableException) {
                                deferred += current.sequence
                            }
                        }
                        stage = "本机记录保存"
                        committed = persistBlocking(expected)
                    }
                    ok = true
                }
            } catch (_: CloudClockException) {
                synchronized(lock) { if (!closed && generation == expected) message = "电视时间不准确，请校准后重试" }
            } catch (_: CloudIdentityException) {
                synchronized(lock) { if (!closed && generation == expected) message = "红果云端账号验证未通过，记录已保留" }
            } catch (error: Exception) {
                val detail = CloudSyncDiagnostics.describe(error)
                synchronized(lock) { if (!closed && generation == expected) message = "$stage 未完成（$detail），进度已保留" }
            } finally {
                val again = synchronized(lock) {
                    if (closed || generation != expected) false else {
                        syncing = false; syncScope = null
                        if (ok) message = if (history?.pending().isNullOrEmpty()) "观看记录已同步" else "部分进度等待确认剧集资料"
                        val next = ok && syncAgain; syncAgain = false; next
                    }
                }
                notifyChanged()
                if (again) main.post { requestSync() }
            }
        }
    }

    fun startQr(events: QrEvents) {
        cancelQr()
        val attempt = synchronized(lock) {
            if (closed || closing || session != null) return
            val scope = RequestScope(); qrScope = scope
            ++qrGeneration to scope
        }
        qrFuture = qrWorker.submit {
            val (number, scope) = attempt
            val client = QrLoginClient()
            fun current() = synchronized(lock) { !closed && !closing && session == null && qrGeneration == number }
            fun ui(action: () -> Unit) { main.post { if (current()) action() } }
            try {
                scope.run {
                    val challenge = client.start()
                    ui { events.challenge(challenge) }
                    while (current()) {
                        Thread.sleep(1800)
                        val result = try { client.poll() } catch (error: java.io.IOException) {
                            RequestScope.ensureActive()
                            if (System.currentTimeMillis() >= challenge.expiresAt) throw error
                            ui { events.failed("连接暂时中断，正在重试") }
                            continue
                        }
                        when (result.state) {
                            QrLoginResult.WAITING, QrLoginResult.SCANNED -> ui { events.state(result.state) }
                            QrLoginResult.SIGNED_IN -> {
                                val account = checkNotNull(result.session)
                                disk.execute {
                                    val success = runCatching {
                                        synchronized(lock) {
                                            check(!closed && !closing && session == null && qrGeneration == number)
                                            val state = prefs.getString(key(account.userId), null)?.let { CloudHistoryState.decode(account.userId, it) }
                                                ?: CloudHistoryState(account.userId)
                                            vault.save(account)
                                            session = account; history = state; generation++; historyRevision++; qrScope = null
                                            message = "正在同步观看记录"
                                        }
                                    }.isSuccess
                                    if (success) {
                                        val expected = synchronized(lock) { generation }
                                        publishHistory(expected)
                                        main.post { if (!synchronized(lock) { closed }) { events.signedIn(account.displayName); changed(); requestSync() } }
                                    } else ui { events.failed("账号保存失败，请重试") }
                                }
                                break
                            }
                            QrLoginResult.EXPIRED, QrLoginResult.CANCELLED -> { ui { events.state(result.state) }; break }
                        }
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Exception) {
                ui { events.failed("扫码登录暂不可用，请重新获取二维码") }
            } finally { client.cancel() }
        }
    }
    fun cancelQr() {
        synchronized(lock) { qrGeneration++; qrScope?.cancel(); qrScope = null; qrFuture?.cancel(true); qrFuture = null }
    }

    fun logout(done: (Boolean) -> Unit) {
        cancelQr()
        synchronized(lock) {
            val account = session ?: return
            val state = history ?: return
            closing = true; generation++; syncScope?.cancel(); syncScope = null; syncing = false
            message = "正在退出账号"
            val current = key(account.userId) to state.encode()
            disk.execute {
                val ok = runCatching { check(prefs.edit().putString(current.first, current.second).commit()); vault.clear() }.isSuccess
                main.post {
                    synchronized(lock) {
                        if (closed) return@post
                        closing = false
                        if (ok) { session = null; history = null; historyRevision++; message = "未登录" }
                        else message = "退出未完成，请重试"
                    }
                    if (ok) { library.useGuestHistory(); historyChanged() }
                    changed(); done(ok)
                }
            }
        }
        notifyChanged()
    }

    fun close() {
        cancelQr()
        synchronized(lock) {
            capture(generation)?.let { snapshot -> disk.execute { runCatching { commit(snapshot) } } }
            closed = true; generation++; syncScope?.cancel(); syncScope = null
        }
        main.removeCallbacks(delayedSync); main.removeCallbacks(periodic)
        delayedPending = false
        worker.shutdownNow(); qrWorker.shutdownNow()
    }
}
