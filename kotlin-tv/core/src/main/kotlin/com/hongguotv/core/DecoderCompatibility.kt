// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.util.Locale

/** SOFTWARE uses installed Android software codecs, not a bundled FFmpeg video decoder. */
enum class DecoderMode(val label: String) {
    AUTO("自动解码"), COMPATIBLE("兼容解码"), SOFTWARE("系统软解");

    fun qualityLimit(requested: Int): Int = when (this) {
        AUTO -> requested
        COMPATIBLE -> minOf(requested, 720)
        SOFTWARE -> minOf(requested, 480)
    }

    companion object {
        fun parse(value: String?): DecoderMode = entries.firstOrNull { it.name == value } ?: AUTO
    }
}

/** Finite per-item fallback; repeated prepare/network recovery cannot replenish the budget. */
class DecoderRecovery {
    private var itemId: String? = null
    private var preferred = DecoderMode.AUTO
    var mode: DecoderMode = DecoderMode.AUTO
        private set

    fun begin(id: String, preference: DecoderMode) {
        if (itemId != id || preferred != preference) {
            itemId = id
            preferred = preference
            mode = preference
        }
    }

    fun reset(preference: DecoderMode) {
        itemId = null
        preferred = preference
        mode = preference
    }

    fun next(errorCode: Int): DecoderMode? {
        if (!isDecoderError(errorCode) && errorCode !in 5001..5002) return null
        return when (mode) {
            DecoderMode.AUTO -> DecoderMode.COMPATIBLE
            DecoderMode.COMPATIBLE -> DecoderMode.SOFTWARE
            DecoderMode.SOFTWARE -> null
        }
    }

    fun advance(errorCode: Int): DecoderMode? = next(errorCode)?.also { mode = it }

    companion object {
        // Media3 PlaybackException constants: decoder init/query/decode/capability/unsupported/reclaimed.
        fun isDecoderError(code: Int): Boolean = code in 4001..4006
        fun isContainerError(code: Int): Boolean = code == 3001 || code == 3002
    }
}

/** Prefer an explicitly-labelled AVC stream before unknown or HEVC variants in compatibility mode. */
object StreamCodecPreference {
    fun rank(codec: String): Int {
        val value = codec.trim().lowercase(Locale.ROOT).replace("-", "").replace(".", "")
        return when {
            value == "h264" || value == "avc" || value.startsWith("avc1") || value.startsWith("avc3") || value == "video/avc" -> 0
            value.isEmpty() -> 1
            value == "h265" || value == "hevc" || value == "bytevc1" || value.startsWith("hvc1") || value.startsWith("hev1") || value == "video/hevc" -> 2
            else -> 3
        }
    }
}
