#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Apply exact-baseline edits; abort rather than silently patching unrelated source versions."""
from pathlib import Path
import hashlib

ROOT = Path(__file__).resolve().parents[2]
APP = 'kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/'
CORE = 'kotlin-tv/core/src/main/kotlin/com/hongguotv/core/'
EXPECTED = {
    APP + 'MainActivity.kt': '5169d5848077ce7936881a9c67311da730ae3e5d',
    APP + 'AppUpdater.kt': '4c2b930db83bd32cd86b452c57cc7833bc83e4c1',
    CORE + 'ContentRepository.kt': 'e0a080c56ee9a1d7a3a48395722ab414a034a87f',
    'kotlin-tv/app/build.gradle.kts': '50ae3b4bb1cb70f3ac0699d8cd4319c59ff1c6d6',
    'kotlin-tv/app/src/main/AndroidManifest.xml': '68c2468bba1a81872677846db4530f1ed68848e8',
}
texts = {}
for name, expected in EXPECTED.items():
    raw = (ROOT / name).read_bytes()
    actual = hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
    if actual != expected:
        raise SystemExit(f'Unexpected baseline: {name}: {actual}; expected {expected}')
    texts[name] = raw.decode('utf-8')

def edit(name, old, new):
    if texts[name].count(old) != 1:
        raise SystemExit(f'Edit must match exactly once in {name}: {old[:120]!r}')
    texts[name] = texts[name].replace(old, new, 1)

repo = CORE + 'ContentRepository.kt'
edit(repo, 'fun stream(id: String, maxQuality: Int = 1080): StreamInfo {', 'fun stream(id: String, maxQuality: Int = 1080, preferAvc: Boolean = false): StreamInfo {')
edit(repo, 'return streamOnce(id,maxQuality)', 'return streamOnce(id,maxQuality,preferAvc)')
edit(repo, 'private fun streamOnce(id: String, maxQuality: Int): StreamInfo {', 'private fun streamOnce(id: String, maxQuality: Int, preferAvc: Boolean): StreamInfo {')
edit(repo,
    '        val orderedRows=rows.sortedWith(compareBy<Triple<Int,String,JSONObject>>({ if(it.first>maxQuality) 1 else 0 },{ kotlin.math.abs(it.first-maxQuality) },{ -it.first }))',
    '''        fun codecRank(row: Triple<Int,String,JSONObject>): Int {
            val item=row.third; val meta=item.optJSONObject("video_meta")
            val codec=item.str("codec_type").ifEmpty { item.str("codec").ifEmpty { item.str("vcodec").ifEmpty { meta?.str("codec_type").orEmpty().ifEmpty { meta?.str("codec").orEmpty() } } } }
            return StreamCodecPreference.rank(codec)
        }
        val orderedRows=rows.sortedWith(compareBy<Triple<Int,String,JSONObject>>({ if(preferAvc) codecRank(it) else 0 },{ if(it.first>maxQuality) 1 else 0 },{ kotlin.math.abs(it.first-maxQuality) },{ -it.first }))''')

main = APP + 'MainActivity.kt'
edit(main, '    private val recovery=RecoveryBudget()', '''    private val recovery=RecoveryBudget()
    private val decoderRecovery=DecoderRecovery()
    private var configuredDecoderMode: DecoderMode?=null
    private var decoderRetryPending=false
    private fun preferredDecoderMode(): DecoderMode {
        val default=if(TvDecoderCompatibility.isHuawei()) DecoderMode.COMPATIBLE else DecoderMode.AUTO
        return DecoderMode.parse(getSharedPreferences("decoder_compatibility",MODE_PRIVATE).getString("mode",default.name))
    }''')
edit(main, '''        val playbackStarted=android.os.SystemClock.elapsedRealtime()
        val reuse=screen=="player" && player!=null && playerView!=null && !recovering && !playError
        library.setWatched(data.series.id,false)
        val selectedIndex=index.coerceIn(0,data.episodes.lastIndex)''', '''        val playbackStarted=android.os.SystemClock.elapsedRealtime()
        val selectedIndex=index.coerceIn(0,data.episodes.lastIndex)
        decoderRecovery.begin("${data.series.id}:${data.episodes[selectedIndex]}:${library.maxQuality}",preferredDecoderMode())
        decoderRetryPending=false
        val reuse=screen=="player" && player!=null && playerView!=null && !recovering && !playError && configuredDecoderMode==decoderRecovery.mode
        library.setWatched(data.series.id,false)''')
edit(main, 'val prefetched=if(position==0L && !recovering)', 'val prefetched=if(position==0L && !recovering && decoderRecovery.mode==DecoderMode.AUTO)')
edit(main, 'val requestedId=data.episodes[episodeIndex]; val maxQuality=library.maxQuality; val ticket=generation', 'val requestedId=data.episodes[episodeIndex]; val maxQuality=decoderRecovery.mode.qualityLimit(library.maxQuality); val ticket=generation\n        val preferAvc=decoderRecovery.mode!=DecoderMode.AUTO')
edit(main, 'repository.stream(requestedId,maxQuality)', 'repository.stream(requestedId,maxQuality,preferAvc=preferAvc)')
edit(main, 'val renderers=androidx.media3.exoplayer.DefaultRenderersFactory(this).setEnableDecoderFallback(true)', 'val renderers=TvDecoderCompatibility.renderers(this,decoderRecovery.mode)')
edit(main, '                    player=it; playerView?.player=it', '                    player=it; playerView?.player=it; configuredDecoderMode=decoderRecovery.mode\n                    TvDecoderCompatibility.attachDiagnostics(it)')
edit(main, 'private fun playbackCacheKey(id: String,quality: Int)="$id:$quality"', 'private fun playbackCacheKey(id: String,quality: Int)="$id:$quality:${decoderRecovery.mode.name}"')
edit(main, '    private fun maybePrefetch() {\n        maybeCacheAhead()', '    private fun maybePrefetch() {\n        // Recovery must not reuse a preloaded HEVC source or cache bytes from another codec.\n        if(decoderRecovery.mode!=DecoderMode.AUTO) return\n        maybeCacheAhead()')
start = texts[main].index('    private fun playerFailure(problem: Throwable) {')
end = texts[main].index('    private fun hidePlaybackOverlay()', start)
texts[main] = texts[main][:start] + '''    private fun playerFailure(problem: Throwable) {
        if(decoderRetryPending) return
        retryPosition=currentPosition().coerceAtLeast(0); retryAutoplay=requestedAutoplay && !sleepStopped
        saveProgress(); cancelRecovery(); cancelPrefetch(); cancelCachePrefetch()
        playError=true; player?.pause(); progressBar.visibility=View.GONE; hud.visibility=View.VISIBLE; controls.removeAllViews(); controls.visibility=View.VISIBLE; panel=true
        val code=(problem as? PlaybackException)?.errorCode ?: 0
        val decoding=DecoderRecovery.isDecoderError(code)
        val parsing=DecoderRecovery.isContainerError(code)
        val diagnosis=TvDecoderCompatibility.describe(problem,player)
        val retryable=if(problem is PlaybackException) problem.errorCode in 2000..2999 || problem.errorCode==PlaybackException.ERROR_CODE_TIMEOUT else problem is java.io.IOException && !(problem is ProviderPlaybackException && problem.sourceCode==110001)
        val explanation=when {
            problem is ProviderPlaybackException -> "${problem.message} · 可返回选剧或稍后重试。"
            parsing -> "视频文件解析失败，并非已确认的硬件解码故障。可重试或换一集。"
            decoding && decoderRecovery.mode==DecoderMode.SOFTWARE -> "系统软解仍不可用：可能缺少对应软件解码器，或片源超出设备能力。"
            decoding -> "当前解码方式失败，可切换兼容解码或系统软解。"
            code in 5001..5002 -> "音频输出失败，可尝试切换解码方式。"
            else -> "播放失败，可重试或切换清晰度。"
        }
        playbackText.text="$explanation\\n诊断：$diagnosis · ${decoderRecovery.mode.label}"
        val normalMessage=playbackText.text
        val actions=row(); controls.addView(actions)
        addButton(actions,"立即重试") { playEpisode(episodeIndex,retryPosition,retryAutoplay) }.requestFocus()
        if(problem is ProviderPlaybackException) addButton(actions,"返回详情") { returnToDetail() }
        else {
            addButton(actions,"尝试 720P") { library.maxQuality=720; playEpisode(episodeIndex,retryPosition,retryAutoplay) }
            addButton(actions,"返回选集") { returnToDetail(true) }
            lateinit var decoder: TextView
            decoder=addButton(controls,"解码模式 · ${decoderRecovery.mode.label}") { showDecoderPicker(decoder) }
        }
        val next=decoderRecovery.next(code)
        if(next!=null && foreground && !sleepStopped && episodePanel==null) {
            val ticket=generation; val failedPlayer=player; val targetIndex=episodeIndex
            val position=retryPosition; val autoplay=retryAutoplay
            decoderRetryPending=true
            playbackText.text="正在切换${next.label}，保留播放位置…\\n诊断：$diagnosis"
            // Post outside the player callback. A page change, pause-for-background or manual action cancels this retry.
            main.post {
                decoderRetryPending=false
                if(generation==ticket && screen=="player" && foreground && player===failedPlayer && playError && !sleepStopped && episodePanel==null) {
                    decoderRecovery.advance(code)
                    Toast.makeText(this,"已切换${next.label} · 优先 H.264 兼容片源",Toast.LENGTH_LONG).show()
                    playEpisode(targetIndex,position,autoplay,recovering=true)
                } else if(generation==ticket && screen=="player" && playError) playbackText.text=normalMessage
            }
        } else if(retryable && !sleepStopped) {
            addButton(controls,"停止自动重试") { cancelRecovery(); playbackText.text="自动重试已停止，观看位置已保留。" }
            autoRecovery=true; scheduleRecovery()
        }
    }
    private fun showDecoderPicker(anchor: TextView) {
        val ticket=generation
        val choices=listOf("自动解码 · 优先设备硬解","兼容解码 · 同步驱动 / 优先 H.264 / 请求不高于 720P","系统软解 · 只选系统软件视频解码器 / 请求不高于 480P")
        tvTools.choose("解码模式（系统软解不等于内置 FFmpeg）",choices,anchor,{ choice ->
            main.post {
                if(generation==ticket && screen=="player") {
                    val position=if(playError) retryPosition else currentPosition()
                    val autoplay=if(playError) retryAutoplay else requestedAutoplay
                    val mode=DecoderMode.entries[choice]
                    getSharedPreferences("decoder_compatibility",MODE_PRIVATE).edit().putString("mode",mode.name).apply()
                    decoderRecovery.reset(mode)
                    playEpisode(episodeIndex,position,autoplay,recovering=true)
                }
            }
        })
    }
''' + texts[main][end:]
edit(main, '"定时 ${sleepTimer.label()}","从头播放"),more', '"定时 ${sleepTimer.label()}","从头播放","解码 ${decoderRecovery.mode.label}"),more')
edit(main, '                        4 -> { seekTo(0); hidePanel() }', '                        4 -> { seekTo(0); hidePanel() }\n                        5 -> showDecoderPicker(more)')
edit(main, '    private fun releasePlayer() {\n', '    private fun releasePlayer() {\n        configuredDecoderMode=null; decoderRetryPending=false\n')

build = 'kotlin-tv/app/build.gradle.kts'
edit(build, 'applicationId = "com.hongguotv.nativeapp"', 'applicationId = "com.hongguotv.nativeapp.huawei"')
edit(build, '?: 23', '?: 24')
edit(build, '?: "0.14.9-local"', '?: "0.14.9-huawei-compat"')
manifest = 'kotlin-tv/app/src/main/AndroidManifest.xml'
edit(manifest, 'android:label="红果 TV 原生版"', 'android:label="红果 TV 华为兼容版"')
edit(manifest, '    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES"/>\n', '')
updater = APP + 'AppUpdater.kt'
edit(updater, '    fun resume() {\n', '    fun resume() {\n        // Independently signed, side-by-side diagnostic build must never use the production update channel.\n        if(BuildConfig.APPLICATION_ID.endsWith(".huawei")) return\n')
edit(updater, '    fun show(from: View?) {\n', '''    fun show(from: View?) {
        if(BuildConfig.APPLICATION_ID.endsWith(".huawei")) {
            AlertDialog.Builder(activity).setTitle("华为兼容测试版")
                .setMessage("版本 ${BuildConfig.VERSION_NAME}\\n与旧版并存，自动更新已隔离。此版本尚需华为实体电视验证。")
                .setPositiveButton("确定",null).show()
            return
        }
''')

for name, text in texts.items():
    (ROOT / name).write_text(text, encoding='utf-8')
    print('Patched', name)
print('Exact-baseline compatibility patch applied successfully.')
