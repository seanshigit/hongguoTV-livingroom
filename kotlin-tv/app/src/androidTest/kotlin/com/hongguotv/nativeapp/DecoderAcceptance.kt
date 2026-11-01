// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.PlaceholderSurface
import com.hongguotv.core.DecoderMode
import java.io.File

/** Local generated H.264/AAC sample only. This is NOT a Huawei-TV/provider playback test. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DecoderAcceptance : Instrumentation() {
    private val report = StringBuilder()
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    private fun <T> ui(action: () -> T): T {
        var value: T? = null; var failure: Throwable? = null
        runOnMainSync { try { value = action() } catch (t: Throwable) { failure = t } }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST") return value as T
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ui(condition)) return
            SystemClock.sleep(100)
        }
        error("Timed out: $label")
    }

    override fun onStart() {
        var result = Activity.RESULT_CANCELED
        try {
            val clip = File(targetContext.cacheDir, "codec-sample.mp4")
            context.assets.open("codec-sample.mp4").use { source -> clip.outputStream().use { source.copyTo(it) } }
            for (mode in DecoderMode.entries) {
                var error: PlaybackException? = null
                var firstFrame = false
                var codecName = ""
                val surface = ui { PlaceholderSurface.newInstanceV17(targetContext, false) }
                val player = ui {
                    ExoPlayer.Builder(targetContext, TvDecoderCompatibility.renderers(targetContext, mode)).build().also { p ->
                        p.volume = 0f
                        p.setVideoSurface(surface)
                        p.addListener(object : Player.Listener {
                            override fun onRenderedFirstFrame() { firstFrame = true }
                            override fun onPlayerError(e: PlaybackException) { error = e }
                        })
                        p.addAnalyticsListener(object : AnalyticsListener {
                            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) { codecName = decoderName }
                        })
                        p.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(clip)))
                        p.prepare(); p.play()
                    }
                }
                try {
                    await("$mode first video frame and advancing clock") {
                        error?.let { throw it }
                        firstFrame && player.isPlaying && player.currentPosition > 1200 && player.videoFormat?.width == 640
                    }
                    if (mode == DecoderMode.SOFTWARE) {
                        val softwareNames = MediaCodecSelector.DEFAULT.getDecoderInfos("video/avc", false, false).filter { it.softwareOnly }.map { it.name }
                        check(ui { codecName } in softwareNames) { "SOFTWARE mode used hardware: $codecName" }
                    }
                    ui { player.pause(); player.seekTo(3000) }
                    await("$mode paused seek") { error?.let { throw it }; player.playbackState == Player.STATE_READY && player.currentPosition >= 2950 }
                    val paused = ui { player.currentPosition }
                    SystemClock.sleep(400)
                    check(ui { !player.playWhenReady && kotlin.math.abs(player.currentPosition - paused) < 150 }) { "Pause was not retained" }
                    ui { player.play() }
                    await("$mode resume after seek") { error?.let { throw it }; player.isPlaying && player.currentPosition > 3600 }
                    report.appendLine("PASS $mode: first frame, advancing video, pause, seek, resume; decoder=${ui { codecName }}")
                } finally {
                    ui { player.release(); surface.release() }
                }
            }
            report.appendLine("CODEC_ACCEPTANCE_OK")
            report.appendLine("Not tested: Huawei hardware and live provider content.")
            result = Activity.RESULT_OK
        } catch (t: Throwable) {
            report.appendLine("CODEC_ACCEPTANCE_FAILED: ${t.javaClass.name}: ${t.message}")
        }
        File(targetContext.getExternalFilesDir(null), "codec-acceptance/report.txt").apply { parentFile!!.mkdirs(); writeText(report.toString()) }
        finish(result, Bundle().apply { putString("stream", report.toString()) })
    }
}
