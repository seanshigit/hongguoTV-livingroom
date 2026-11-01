// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.hongguotv.core.DecoderMode
import java.util.Locale

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object TvDecoderCompatibility {
    fun isHuawei(): Boolean = listOf(Build.MANUFACTURER, Build.BRAND, Build.HARDWARE).any {
        val v = it.lowercase(Locale.ROOT)
        v.contains("huawei") || v.contains("hisilicon") || v.contains("honor")
    }

    fun renderers(context: Context, mode: DecoderMode): DefaultRenderersFactory {
        val factory = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        // Older vendor codec drivers can advertise async support but fail once samples are queued.
        if (isHuawei() || mode != DecoderMode.AUTO) factory.forceDisableMediaCodecAsynchronousQueueing()
        if (mode == DecoderMode.SOFTWARE) {
            factory.setMediaCodecSelector { mime, secure, tunneling ->
                val candidates = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
                select(candidates, mime, mode)
            }
        }
        return factory
    }

    /** Never quietly use the same failing hardware video codec in software mode. */
    fun select(candidates: List<MediaCodecInfo>, mime: String, mode: DecoderMode): List<MediaCodecInfo> {
        if (mode != DecoderMode.SOFTWARE) return candidates
        return if (mime.startsWith("video/")) candidates.filter { it.softwareOnly }
        else candidates.sortedBy { if (it.softwareOnly) 0 else 1 }
    }

    fun attachDiagnostics(player: ExoPlayer) {
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                Log.i("HongguoCodec", "Video decoder=${safe(decoderName)}")
            }
            override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                Log.i("HongguoCodec", "Audio decoder=${safe(decoderName)}")
            }
        })
    }

    fun describe(problem: Throwable, player: ExoPlayer?): String {
        val error = problem as? PlaybackException
        val format = (problem as? ExoPlaybackException)?.rendererFormat ?: player?.videoFormat
        val mime = safe(format?.sampleMimeType ?: "unknown")
        val resolution = format?.let { if (it.width > 0 && it.height > 0) " ${it.width}x${it.height}" else "" }.orEmpty()
        // Do not log exception messages/URIs: they can contain expiring playback credentials.
        val causes = generateSequence(problem) { it.cause }.take(6).map { it.javaClass.simpleName }.joinToString(" > ")
        Log.w("HongguoCodec", "code=${error?.errorCodeName ?: "SOURCE"} mime=$mime$resolution device=${safe(Build.MODEL)} causes=$causes")
        return "${error?.errorCode ?: "SOURCE"} · $mime$resolution"
    }

    private fun safe(value: String): String = value.take(100).replace(Regex("[^a-zA-Z0-9_./+ -]"), "_")
}
